"""v2 上传：音频归档 + 幂等记录创建。

幂等：
  - `Idempotency-Key` 已存在 → 返回原记录；
  - 同一 `audio_sha256` 已存在 → 返回原记录（避免重复音频）；
  - 记录 id 缺省用 UUID（v1 的 stamp 只作为 legacy/suggest）。
"""
import hashlib
import json
import os
import re
import time
import uuid
from datetime import datetime
from pathlib import Path

from fastapi import HTTPException

from . import config, db, service

AUDIO_EXTS = {".m4a", ".mp4", ".aac", ".wav", ".mp3", ".amr", ".ogg", ".3gp"}
P_STAMP = re.compile(r"^(\d{8}_\d{6})")


def _now_ms() -> int:
    return int(time.time() * 1000)


def _stamp_ms(stamp: str):
    try:
        return int(datetime.strptime(stamp, "%Y%m%d_%H%M%S").timestamp() * 1000)
    except Exception:
        return None


def _remember(conn, key: str, record_id: str):
    if not key:
        return
    with conn:
        conn.execute(
            "INSERT OR REPLACE INTO idempotency(key, record_id, created_at) VALUES(?,?,?)",
            (key, record_id, _now_ms()),
        )


def _existing(conn, idem_key: str, sha256: str):
    if idem_key:
        row = conn.execute(
            "SELECT record_id FROM idempotency WHERE key=?", (idem_key,)).fetchone()
        if row:
            rec = service.get_record(conn, row["record_id"])
            if rec:
                return rec
    if sha256:
        row = conn.execute(
            "SELECT * FROM records WHERE audio_sha256=?", (sha256,)).fetchone()
        if row:
            return dict(row)
    return None


def save_upload(conn, data: bytes, filename: str, meta: dict, idem_key: str = "") -> dict:
    name = os.path.basename(filename or "")
    ext = Path(name).suffix.lower()
    if ext not in AUDIO_EXTS:
        raise HTTPException(400, "不支持的音频格式：%s" % (ext or "(无扩展名)"))
    if not data:
        raise HTTPException(400, "空文件")

    sha256 = hashlib.sha256(data).hexdigest()
    stamp = str(meta.get("stamp") or "")
    m = P_STAMP.match(stamp)
    legacy_stamp = m.group(1) if m else ""
    recorded_at = int(meta.get("recorded_at") or 0)
    if recorded_at <= 0:
        recorded_at = _stamp_ms(legacy_stamp) or _now_ms()

    with db.lock:
        found = _existing(conn, idem_key, sha256)
        if found:
            _remember(conn, idem_key, found["id"])
            return {"id": found["id"], "rev": found["rev"], "duplicate": True}

        record_id = str(meta.get("id") or "").strip() or uuid.uuid4().hex
        while conn.execute("SELECT 1 FROM records WHERE id=?", (record_id,)).fetchone():
            record_id = uuid.uuid4().hex

        ym = datetime.fromtimestamp(recorded_at / 1000.0).strftime("%Y.%m")
        dest_dir = config.audio_dir(ym)
        dest_dir.mkdir(parents=True, exist_ok=True)
        final = dest_dir / (record_id + ext)
        tmp = dest_dir / (".%s.part" % uuid.uuid4().hex)
        try:
            tmp.write_bytes(data)
            os.replace(str(tmp), str(final))
        except Exception as e:
            try:
                tmp.unlink()
            except Exception:
                pass
            raise HTTPException(500, "音频写入失败：%s" % e)

        try:
            rec = service.create_record(conn, {
                "id": record_id,
                "legacy_id": legacy_stamp or None,
                "title": str(meta.get("title") or ""),
                "cat": meta.get("cat") or None,
                "mode": str(meta.get("mode") or ""),
                "src": str(meta.get("src") or "app"),
                "device_id": str(meta.get("device") or ""),
                "marks": json.dumps(meta.get("marks") or [], ensure_ascii=False),
                "recorded_at": recorded_at,
                "dur_ms": int(meta.get("dur_ms") or 0),
                "bytes": len(data),
                "audio_path": str(final),
                "audio_name": name,
                "audio_sha256": sha256,
                "status": "uploaded",
            })
        except Exception:
            try:
                final.unlink()
            except Exception:
                pass
            raise
        _remember(conn, idem_key, record_id)
        return {"id": rec["id"], "rev": rec["rev"], "duplicate": False}
