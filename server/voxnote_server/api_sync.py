"""设备同步 API（App / S1 → 服务端）。

鉴权：Authorization: Bearer <配对令牌>（设置页可见可重置）。

协议是"设备推、设备拉"的最简形态：
  POST /upload          音频 + 元数据（multipart），幂等键 stamp
  GET  /records?since=  增量枚举（毫秒时间戳游标 = updated_at）
  GET  /records/{id}/audio        下载音频原件
  GET  /records/{id}/transcript   生效转写稿（机器层 + 人工修订合并，JSONL）
"""
import datetime
import os
import re

from fastapi import APIRouter, Depends, Header, HTTPException, UploadFile, File
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
    recorded_at: int = 0       # 毫秒时间戳（缺省 = stamp / 当前）
    dur_ms: int = 0            # 已知时长（可无，转写后覆盖）


@router.post("/upload")
async def upload(meta: str = "", file: UploadFile = File(...), _: None = Depends(auth)):
    import json
    try:
        m = UploadMeta(**(json.loads(meta) if meta else {}))
    except Exception:
        raise HTTPException(400, "meta 不是合法 JSON")

    name = os.path.basename(file.filename or "")
    ext = os.path.splitext(name)[1].lower()
    if ext not in AUDIO_EXTS:
        raise HTTPException(400, "不支持的音频格式：%s" % ext)

    stamp = m.stamp if P_STAMP.match(m.stamp or "") else datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    rid = db.unique_record_id(stamp)

    ym = datetime.datetime.fromtimestamp(
        m.recorded_at / 1000 if m.recorded_at else _stamp_ms(stamp)).strftime("%Y.%m")
    dest_dir = config.audio_dir(ym)
    os.makedirs(dest_dir, exist_ok=True)
    dest = os.path.join(dest_dir, "%s%s" % (rid, ext))
    size = 0
    with open(dest, "wb") as f:
        while True:
            chunk = await file.read(1 << 20)
            if not chunk:
                break
            size += len(chunk)
            f.write(chunk)
    if size == 0:
        try:
            os.remove(dest)
        except OSError:
            pass
        raise HTTPException(400, "空文件")

    rec = db.insert_record(
        id=rid, sid=m.sid or 0, title=m.title or "", cat=m.cat or None, mode=m.mode or "",
        src="app", dur_ms=m.dur_ms or 0, status="raw",
        created_at=m.recorded_at or _stamp_ms(stamp),
        audio_path=dest, audio_name=name, bytes=size)

    try:
        from .jobs import enqueue_default
        enqueue_default(rid)
    except ImportError:
        pass  # 队列功能尚未接入
    return {"id": rid, "bytes": size, "status": rec["status"]}


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
        "SELECT id, title, cat, mode, dur_ms, status, engine, updated_at, bytes"
        " FROM records WHERE updated_at > ? ORDER BY updated_at LIMIT 500", (since,)).fetchall()
    return {"records": [dict(r) for r in rows], "server_time": _now_ms()}


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


def _now_ms():
    return int(datetime.datetime.now().timestamp() * 1000)
