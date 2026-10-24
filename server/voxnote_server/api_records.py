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
        "src": r["src"], "device": r["device"],
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
                 limit: int = 50, offset: int = 0, preview_len: int = 80):
    limit = max(0, min(limit, 500))  # 负值在 SQLite 里 LIMIT -1 = 无限制，会绕过分页上限
    preview_len = max(1, min(preview_len, 1000))  # 预览上限：录音流卡片也就三五行，用不着更多
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

    previews = db.preview_texts([r["id"] for r in rows], preview_len)
    out = []
    for r in rows:
        o = _rec_out(r)
        pv = (previews.get(r["id"]) or "").strip()
        if pv:
            o["preview"] = pv
        out.append(o)
    return {"records": out, "total": total, "untagged": untagged,
            "cats": [dict(x) for x in db.distinct_cats()]}


@router.get("/records/{rid}")
def get_record(rid: str):
    r = db.record_row(rid)
    if r is None:
        raise HTTPException(404, "记录不存在")
    return _rec_out(r, with_segs=True)


@router.get("/records/{rid}/versions")
def record_versions(rid: str):
    """转写版本：历史机器结果存档（有差异才产生）+ 当前机器结果。
    供控制台详情页对比不同引擎/不同时间的转写产出。"""
    r = db.record_row(rid)
    if r is None:
        raise HTTPException(404, "记录不存在")
    cur = db.machine_text(rid)
    return {
        "versions": db.list_versions(rid),
        "current": ({"engine": r["engine"], "at": r["updated_at"],
                     "nseg": cur["nseg"], "text": cur["text"]} if cur else None),
    }


@router.get("/records/{rid}/meta")
def record_meta(rid: str):
    """详细信息弹窗：文件落盘位置、音频元数据、转写统计与最近一次转写任务。"""
    import time
    from . import config
    r = db.record_row(rid)
    if r is None:
        raise HTTPException(404, "记录不存在")
    segs = db.conn().execute(
        "SELECT COUNT(*) AS n, SUM(CASE WHEN e_text IS NOT NULL OR e_spk IS NOT NULL"
        " THEN 1 ELSE 0 END) AS ed FROM segments WHERE record_id=?",
        (rid,)).fetchone()
    job = db.conn().execute(
        "SELECT engine, state, progress, error, created_at, started_at, finished_at"
        " FROM jobs WHERE record_id=? ORDER BY id DESC LIMIT 1", (rid,)).fetchone()

    def stat(p):
        if not p or not os.path.isfile(p):
            return None
        s = os.stat(p)
        return {"size": s.st_size, "mtime": int(s.st_mtime * 1000),
                "ctime": int(s.st_ctime * 1000)}

    audio_file = r["audio_path"] if (r["audio_path"] and os.path.isfile(r["audio_path"])) else None
    tdir = config.transcript_dir(rid)
    tfiles = {}
    for n in ("transcript.md", "segments.jsonl"):
        p = os.path.join(tdir, n)
        s = stat(p)
        if s:
            s["path"] = p
            tfiles[n] = s
    return {
        "id": r["id"], "sid": r["sid"], "title": r["title"], "cat": r["cat"],
        "mode": r["mode"], "src": r["src"], "device": r["device"], "status": r["status"],
        "engine": r["engine"], "error": r["error"], "error_detail": r["error_detail"],
        "dur_ms": r["dur_ms"], "bytes": r["bytes"],
        "created_at": r["created_at"], "updated_at": r["updated_at"],
        "audio_name": r["audio_name"],
        "audio_path": r["audio_path"] or "",
        "audio_url": "/api/v1/records/%s/audio" % rid,
        "audio_file": stat(audio_file),
        "transcript_dir": tdir,
        "transcript_files": tfiles,
        "segments": segs["n"], "edited": segs["ed"] or 0,
        "last_job": dict(job) if job else None,
        "server_now": int(time.time() * 1000),
    }


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
    rec = await save_upload(file, src="upload", device="web",
                            title=title or os.path.splitext(name)[0], mode=mode)
    return {"id": rec["id"], "bytes": rec["bytes"]}
