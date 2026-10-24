"""控制台 API：记录库（列表 / 详情 / 元信息 / 逐句修订 / 重转写 / 删除 / 导入）。

逐句修订只写 e_*（人工层），重转写只替换机器层 —— 两层互不覆盖。
"""
import os

from fastapi import APIRouter, HTTPException, UploadFile, File
from fastapi.responses import FileResponse
from pydantic import BaseModel
from typing import List, Optional

from . import db, config

router = APIRouter()


def _rec_out(r, with_segs=False):
    o = {
        "id": r["id"], "sid": r["sid"], "title": r["title"], "cat": r["cat"],
        "mode": r["mode"], "dur_ms": r["dur_ms"], "status": r["status"],
        "engine": r["engine"], "error": r["error"], "error_detail": r["error_detail"],
        "src": r["src"],
        "bytes": r["bytes"], "created_at": r["created_at"], "updated_at": r["updated_at"],
        "has_audio": bool(r["audio_path"] and os.path.isfile(r["audio_path"])),
        "audio_url": "/api/v1/records/%s/audio" % r["id"],
    }
    if with_segs:
        segs = db.effective_segments(r["id"])
        o["segments"] = [dict(s) for s in segs]
        o["edited"] = sum(1 for s in segs if s["edited"])
        o["audio_path"] = r["audio_path"] or ""
    return o


@router.get("/records")
def list_records(status: str = "", q: str = "", cat: str = "",
                 limit: int = 50, offset: int = 0):
    limit = max(0, min(limit, 500))  # 负值在 SQLite 里 LIMIT -1 = 无限制，会绕过分页上限
    sql = "SELECT * FROM records"
    where, args = [], []
    if status == "edited":
        where.append("id IN (SELECT DISTINCT record_id FROM segments WHERE e_text IS NOT NULL OR e_spk IS NOT NULL)")
    elif status:
        where.append("status=?")
        args.append(status)
    if cat:
        if cat == "__none__":
            # 未分类筛选（控制台批量归类入口）；__none__ 是哨兵值，别当普通标签查
            where.append("(cat IS NULL OR cat='')")
        else:
            where.append("cat=?")
            args.append(cat)
    if q:
        # 标题/全文（FTS 句级召回出 record_id 集合）
        hits = db.search_sentences(q, limit=50)
        ids = list(dict.fromkeys([h["record_id"] for h in hits]))
        where.append("(title LIKE ? OR id IN (%s))"
                     % (",".join("?" * len(ids)) if ids else "SELECT ''"))
        args.append("%" + q + "%")
        args += ids
    if where:
        sql += " WHERE " + " AND ".join(where)

    c = db.conn()
    cond = (" WHERE " + " AND ".join(where)) if where else ""
    total = c.execute("SELECT COUNT(*) FROM records" + cond, args).fetchone()[0]
    untagged = c.execute(
        "SELECT COUNT(*) FROM records WHERE cat IS NULL OR cat=''").fetchone()[0]

    sql += " ORDER BY created_at DESC LIMIT ? OFFSET ?"
    rows = c.execute(sql, args + [limit, max(0, offset)]).fetchall()

    previews = db.first_texts([r["id"] for r in rows])
    out = []
    for r in rows:
        o = _rec_out(r)
        pv = (previews.get(r["id"]) or "").strip()
        if pv:
            o["preview"] = pv[:80]
        out.append(o)
    return {"records": out, "total": total, "untagged": untagged,
            "cats": [dict(x) for x in db.distinct_cats()]}


@router.get("/records/{rid}")
def get_record(rid: str):
    r = db.record_row(rid)
    if r is None:
        raise HTTPException(404, "记录不存在")
    return _rec_out(r, with_segs=True)


class RecordPatch(BaseModel):
    title: Optional[str] = None
    cat: Optional[str] = None


@router.patch("/records/{rid}")
def patch_record(rid: str, p: RecordPatch):
    r = db.record_row(rid)
    if r is None:
        raise HTTPException(404, "记录不存在")
    kw = {}
    if p.title is not None:
        kw["title"] = p.title.strip()
    if p.cat is not None:
        kw["cat"] = p.cat or None
    if kw:
        db.update_record(rid, **kw)
    return {"ok": True}


class BatchCatReq(BaseModel):
    ids: List[str]
    cat: str = ""   # 空 = 批量清除标签


@router.post("/records/batch-cat")
def batch_cat(req: BatchCatReq):
    """批量设标签：控制台筛出"未分类"后批量归类的落点。逐条 update_record
    （每条都触碰 updated_at），App 端增量拉取会按名字对上/新建本地分类自动跟上。"""
    n = 0
    for rid in req.ids:
        if db.record_row(rid) is None:
            continue
        db.update_record(rid, cat=req.cat or None)
        n += 1
    return {"ok": True, "updated": n}


class SegmentPatch(BaseModel):
    text: Optional[str] = None
    spk: Optional[int] = None


@router.patch("/records/{rid}/segments/{seg_id}")
def patch_segment(rid: str, seg_id: int, p: SegmentPatch):
    if not db.patch_segment(rid, seg_id, p.text, p.spk):
        raise HTTPException(404, "句子不存在")
    db.update_record(rid)  # 触碰 updated_at，设备端增量可见
    db.export_mirror(rid)
    return {"ok": True}


@router.delete("/records/{rid}/segments/{seg_id}")
def del_segment(rid: str, seg_id: int):
    if not db.delete_segment(rid, seg_id):
        raise HTTPException(404, "句子不存在")
    db.update_record(rid)
    db.export_mirror(rid)
    return {"ok": True}


class TranscribeReq(BaseModel):
    engine: str = ""


@router.post("/records/{rid}/transcribe")
def transcribe(rid: str, req: TranscribeReq):
    from . import engines, jobs
    r = db.record_row(rid)
    if r is None:
        raise HTTPException(404, "记录不存在")
    if not r["audio_path"] or not os.path.isfile(r["audio_path"]):
        raise HTTPException(400, "音频文件缺失")
    engine_id = req.engine or db.get("default_engine", "whisper-local")
    e = engines.get(engine_id)
    if e is None:
        raise HTTPException(400, "未知引擎：%s" % engine_id)
    if not e.available():
        raise HTTPException(400, "引擎不可用：%s" % e.name)
    job_id = jobs.enqueue(rid, engine_id)
    return {"ok": True, "job": job_id}


@router.delete("/records/{rid}")
def delete_record(rid: str):
    r = db.record_row(rid)
    if r is None:
        raise HTTPException(404, "记录不存在")
    if r["audio_path"]:
        try:
            os.remove(r["audio_path"])
        except OSError:
            pass
    import shutil
    try:
        shutil.rmtree(config.transcript_dir(rid), ignore_errors=True)
    except Exception:
        pass
    with db.conn() as c:
        c.execute("DELETE FROM segments WHERE record_id=?", (rid,))
        c.execute("DELETE FROM fts WHERE record_id=?", (rid,))
        c.execute("DELETE FROM jobs WHERE record_id=?", (rid,))
        c.execute("DELETE FROM records WHERE id=?", (rid,))
    return {"ok": True}


@router.get("/records/{rid}/audio")
def record_audio(rid: str):
    r = db.record_row(rid)
    if r is None or not r["audio_path"] or not os.path.isfile(r["audio_path"]):
        raise HTTPException(404, "音频不存在")
    return FileResponse(r["audio_path"])


@router.get("/records/{rid}/transcript.md")
def transcript_md(rid: str):
    # 先查库再拼路径：rid 是 URL 单段，不查库直接 join 的话 ".."/Windows 反斜杠
    # 编码能把路径带出 transcripts 目录
    if db.record_row(rid) is None:
        raise HTTPException(404, "记录不存在")
    db.export_mirror(rid)
    path = os.path.join(config.transcript_dir(rid), "transcript.md")
    if not os.path.isfile(path):
        raise HTTPException(404, "暂无转写稿")
    return FileResponse(path, filename="%s.md" % rid)


@router.get("/records/{rid}/segments.jsonl")
def segments_jsonl(rid: str):
    if db.record_row(rid) is None:
        raise HTTPException(404, "记录不存在")
    db.export_mirror(rid)
    path = os.path.join(config.transcript_dir(rid), "segments.jsonl")
    if not os.path.isfile(path):
        raise HTTPException(404, "暂无转写稿")
    return FileResponse(path, filename="%s.segments.jsonl" % rid)


# ------------------------------------------------------------ 导入（控制台拖拽上传，本机免令牌）

@router.post("/records/upload")
async def console_upload(file: UploadFile = File(...), title: str = "", mode: str = ""):
    """控制台手动导入；登记逻辑与设备同步一致（src=upload）。"""
    from .api_sync import save_upload
    name = os.path.basename(file.filename or "")
    rec = await save_upload(file, src="upload",
                            title=title or os.path.splitext(name)[0], mode=mode)
    return {"id": rec["id"], "bytes": rec["bytes"]}
