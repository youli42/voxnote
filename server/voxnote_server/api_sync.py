"""设备同步 API（App / S1 → 服务端）。

鉴权：Authorization: Bearer <配对令牌>（设置页可见可重置）。

协议是"设备推、设备拉"的最简形态：
  POST /upload          音频 + 元数据（multipart），幂等键 stamp
  GET  /records?since=  增量枚举（毫秒时间戳游标 = updated_at；附 preview 转写预览）
  GET  /records/{id}/audio        下载音频原件
  GET  /records/{id}/transcript   生效转写稿（机器层 + 人工修订合并，JSONL）
  POST /records/{id}/transcribe   设备端触发（重）转写
  POST /records/{id}/meta         设备端改元数据（标题改名、打点标记）
  GET  /asr-config                 读服务器转写配置（引擎 / Whisper 模型清单）
  POST /asr-config                 改默认引擎与 Whisper 模型
"""
import datetime
import os
import re
import sqlite3
import uuid

from fastapi import APIRouter, Depends, Form, Header, HTTPException, UploadFile, File
from fastapi.responses import FileResponse, PlainTextResponse
from pydantic import BaseModel

from . import db, config

router = APIRouter()

P_STAMP = re.compile(r"^(\d{8}_\d{6})")
AUDIO_EXTS = {".m4a", ".mp4", ".aac", ".wav", ".mp3", ".amr", ".ogg", ".3gp"}


def auth(authorization: str = Header(default="")):
    token = authorization[7:] if authorization.lower().startswith("bearer ") else authorization
    if not db.check_token(token.strip()):
        raise HTTPException(401, "无效的配对令牌")


@router.get("/ping")
def ping(_: None = Depends(auth)):
    return {"ok": True, "server_time": int(datetime.datetime.now().timestamp() * 1000)}


# ------------------------------------------------------------ 上传

class UploadMeta(BaseModel):
    stamp: str = ""            # yyyyMMdd_HHmmss，记录主键（缺省用当前时间）
    title: str = ""
    cat: str = ""
    mode: str = ""             # meeting / memo / ...
    sid: int = 0               # 厂商会话号（可无）
    device: str = ""           # 上传设备名（控制台可当标签看）
    recorded_at: int = 0       # 毫秒时间戳（缺省 = stamp / 当前）
    dur_ms: int = 0            # 已知时长（可无，转写后覆盖）
    marks: list = None         # 打点标记（秒；随录音一起上来的初始值）


@router.post("/upload")
async def upload(meta: str = Form(default=""), file: UploadFile = File(...), _: None = Depends(auth)):
    import json
    try:
        m = UploadMeta(**(json.loads(meta) if meta else {}))
    except Exception:
        raise HTTPException(400, "meta 不是合法 JSON")
    rec = await save_upload(file, src="app", stamp=m.stamp, title=m.title, cat=m.cat,
                            mode=m.mode, sid=m.sid, device=m.device,
                            recorded_at=m.recorded_at, dur_ms=m.dur_ms,
                            marks=_clean_marks(m.marks))
    return {"id": rec["id"], "bytes": rec["bytes"], "status": rec["status"]}


async def save_upload(file: UploadFile, *, src: str, stamp: str = "", title: str = "",
                      cat: str = "", mode: str = "", sid: int = 0, device: str = "",
                      recorded_at: int = 0, dur_ms: int = 0, marks: str = "[]"):
    """上传音频落盘 + 登记 + 自动入队（设备同步与控制台导入共用）。

    并发安全：先写同目录唯一临时文件（同秒并发互不覆盖），记录插入成功后
    才改名为最终名；主键撞车（unique_record_id 的查-用间隙被并发穿过）时
    换 -2/-3 后缀重试，不再出现后写者覆盖先写者音频的错位。
    marks 传已序列化的 JSON 数组文本（None → '[]'）。
    """
    name = os.path.basename(file.filename or "")
    ext = os.path.splitext(name)[1].lower()
    if ext not in AUDIO_EXTS:
        raise HTTPException(400, "不支持的音频格式：%s" % ext)

    m = P_STAMP.match(stamp or "") or P_STAMP.match(name)
    stamp = m.group(1) if m else datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    ts = _clamp_ts(recorded_at, _stamp_ms(stamp))
    ym = datetime.datetime.fromtimestamp(ts / 1000.0).strftime("%Y.%m")
    dest_dir = config.audio_dir(ym)
    os.makedirs(dest_dir, exist_ok=True)
    tmp = os.path.join(dest_dir, ".%s%s.part" % (uuid.uuid4().hex[:8], ext))
    size = 0
    with open(tmp, "wb") as f:
        while True:
            chunk = await file.read(1 << 20)
            if not chunk:
                break
            size += len(chunk)
            f.write(chunk)

    def drop_tmp():
        try:
            os.remove(tmp)
        except OSError:
            pass

    if size == 0:
        drop_tmp()
        raise HTTPException(400, "空文件")

    rid = rec = None
    try:
        for _ in range(5):
            rid = db.unique_record_id(stamp)
            try:
                rec = db.insert_record(
                    id=rid, sid=sid or 0, title=title or "", cat=cat or None, mode=mode or "",
                    src=src, device=device or "", dur_ms=dur_ms or 0, status="raw", created_at=ts,
                    audio_path=os.path.join(dest_dir, rid + ext), audio_name=name, bytes=size,
                    marks=marks or "[]")
            except sqlite3.IntegrityError:
                continue  # 并发先插了同 id：换后缀重来
            try:
                os.replace(tmp, os.path.join(dest_dir, rid + ext))
            except OSError:
                with db.conn() as c:
                    c.execute("DELETE FROM records WHERE id=?", (rid,))
                raise HTTPException(500, "音频文件改名失败")
            break
        else:
            raise HTTPException(500, "记录登记失败（id 冲突重试耗尽）")
    except Exception:
        drop_tmp()
        raise

    from .jobs import enqueue_default
    enqueue_default(rid)
    return rec


def _clamp_ts(ts: int, fallback: int) -> int:
    """设备时钟错乱的极端时间戳会让 fromtimestamp 在 Windows 上抛
    OSError/OverflowError（上传 500、AI 引用崩溃），钳到合理区间外即回退。"""
    if 978307200000 <= ts <= 4102444800000:  # 2001-01-01 ~ 2100-01-01
        return ts
    return fallback


def _stamp_ms(stamp: str) -> int:
    try:
        dt = datetime.datetime.strptime(stamp, "%Y%m%d_%H%M%S")
        return int(dt.timestamp() * 1000)
    except ValueError:
        return int(datetime.datetime.now().timestamp() * 1000)


# ------------------------------------------------------------ 拉取

@router.get("/records")
def list_records(since: int = 0, _: None = Depends(auth)):
    rows = db.conn().execute(
        "SELECT id, title, cat, mode, dur_ms, status, engine, device, src, bytes,"
        " audio_name, marks, updated_at"
        " FROM records WHERE updated_at > ? ORDER BY updated_at, id LIMIT 500", (since,)).fetchall()
    previews = _previews([r["id"] for r in rows])
    out = []
    for r in rows:
        d = dict(r)
        d["preview"] = previews.get(r["id"], "")
        d["marks"] = _parse_marks(d.get("marks"))
        out.append(d)
    return {"records": out, "server_time": db.now_ms()}


def _clean_marks(v, cap=500):
    """设备端打点标记：数值列表 → 序列化 JSON 文本；None → None（不改动）。"""
    if v is None:
        return None
    if not isinstance(v, list):
        raise HTTPException(400, "marks 必须是数值数组")
    out = []
    for x in v[:cap]:
        try:
            out.append(round(float(x), 3))
        except (TypeError, ValueError):
            raise HTTPException(400, "marks 必须是数值数组")
    import json as _json
    return _json.dumps(out)


def _parse_marks(text):
    import json
    try:
        v = json.loads(text or "[]")
        return v if isinstance(v, list) else []
    except Exception:
        return []


def _previews(ids, cap=120):
    """每条记录生效转写稿的开头一段，设备端云端页当几行预览。
    单条 SQL 捞齐（按 t_ms, idx 排序，同记录内的相对顺序不变），
    凑够 cap 字符的记录不再追加，全部凑齐即提前收手。"""
    out = {i: "" for i in ids}
    pending = [i for i in ids if i]  # 保持顺序，去重
    seen = set()
    pending = [i for i in pending if not (i in seen or seen.add(i))]
    if not pending:
        return out
    qmarks = ",".join("?" * len(pending))
    waiting = set(pending)
    for s in db.conn().execute(
            "SELECT record_id, COALESCE(e_text, m_text) AS text FROM segments"
            " WHERE record_id IN (%s) ORDER BY t_ms, idx" % qmarks, pending):
        rid = s["record_id"]
        if rid not in waiting:
            continue
        text = (s["text"] or "").strip()
        if text:
            out[rid] = text if not out[rid] else out[rid] + " " + text
            if len(out[rid]) >= cap:
                out[rid] = out[rid][:cap]
                waiting.discard(rid)
                if not waiting:
                    break
    return out


@router.get("/records/{rid}/audio")
def get_audio(rid: str, _: None = Depends(auth)):
    row = db.record_row(rid)
    if row is None or not row["audio_path"] or not os.path.isfile(row["audio_path"]):
        raise HTTPException(404, "音频不存在")
    return FileResponse(row["audio_path"], filename=row["audio_name"] or os.path.basename(row["audio_path"]))


@router.get("/records/{rid}/transcript")
def get_transcript(rid: str, _: None = Depends(auth)):
    row = db.record_row(rid)
    if row is None:
        raise HTTPException(404, "记录不存在")
    segs = db.effective_segments(rid)
    lines = []
    import json
    for s in segs:
        lines.append(json.dumps({
            "id": s["id"], "t": s["t_ms"], "e": s["e_ms"], "spk": s["spk"],
            "text": s["text"], "edited": bool(s["edited"]),
        }, ensure_ascii=False))
    return PlainTextResponse("\n".join(lines) + ("\n" if lines else ""),
                             media_type="application/x-ndjson")


# ------------------------------------------------------------ 设备端触发的（重）转写

class TranscribeReq(BaseModel):
    engine: str = ""   # 空 = 服务器默认引擎


@router.post("/records/{rid}/transcribe")
def sync_transcribe(rid: str, req: TranscribeReq, _: None = Depends(auth)):
    """App 详情页「转文字 / 重新转写」的落点：校验与入队逻辑和控制台一致，
    新稿转好后设备靠增量拉取（updated_at 变化）回填。"""
    from .api_records import enqueue_transcribe
    job_id = enqueue_transcribe(rid, req.engine)
    return {"ok": True, "job": job_id}


# ------------------------------------------------------------ 设备端改元数据（改名 / 打点标记）

class MetaPatch(BaseModel):
    title: str = ""      # 空 = 不改；非空 = 改名
    marks: list = None   # 缺省 = 不改；[] = 清空标记


@router.post("/records/{rid}/meta")
def sync_meta(rid: str, p: MetaPatch, _: None = Depends(auth)):
    """App 端改名 / 打点的上推落点：只动记录级元数据，不碰逐句层。
    改动会推 updated_at，其它设备靠增量拉取看到；返回改后的完整元数据
    （设备端记成"服务器已知值"基线）。"""
    row = db.record_row(rid)
    if row is None:
        raise HTTPException(404, "记录不存在")
    kw = {}
    if p.title and p.title.strip():
        kw["title"] = p.title.strip()[:200]
    mk = _clean_marks(p.marks)
    if mk is not None:
        kw["marks"] = mk
    if kw:
        db.update_record(rid, **kw)
    row = db.record_row(rid)
    return {"ok": True, "title": row["title"], "marks": _parse_marks(row["marks"])}


# ------------------------------------------------------------ 服务器转写配置（App 设置页）

@router.get("/asr-config")
def get_asr_config(_: None = Depends(auth)):
    """App「设置 → 服务器」的引擎选择数据：引擎清单（含可用/停用）、默认引擎、
    Whisper 模型清单（含已装状态）。其余配置（云端 Key、llama 路径等）只在控制台改。"""
    from . import engines
    from .api_models import whisper_status
    w = whisper_status()
    return {
        "default_engine": db.get("default_engine", "whisper-local"),
        "whisper_model": db.get("whisper_model", "small"),
        "engines": [dict(e, enabled=engines.enabled(e["id"])) for e in engines.all_infos()],
        "whisper_models": w["models"],
    }


class AsrConfigPatch(BaseModel):
    default_engine: str = ""     # 空 = 不改
    whisper_model: str = ""


@router.post("/asr-config")
def patch_asr_config(p: AsrConfigPatch, _: None = Depends(auth)):
    from . import engines
    from .api_models import _check_model_name
    if p.default_engine:
        e = engines.get(p.default_engine)
        if e is None:
            raise HTTPException(400, "未知引擎：%s" % p.default_engine)
        if not engines.enabled(p.default_engine):
            raise HTTPException(400, "引擎已停用：%s" % e.name)
        db.set("default_engine", p.default_engine)
    if p.whisper_model:
        name = p.whisper_model.strip().lower()
        _check_model_name(name)
        db.set("whisper_model", name)
    return {"ok": True}
