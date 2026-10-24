"""设置与统计 API（控制台）。

密钥类值只在 GET 时给掩码；写入用原值。sync_token 属于"可看可重置"，
重置后旧令牌立即失效（设备端需要更新）。
"""
import datetime
import os
import secrets
import time

from fastapi import APIRouter
from pydantic import BaseModel
from typing import Optional

from . import db, config

router = APIRouter()

SECRET_KEYS = {"cloud_asr_key", "ai_key"}


def build_stats() -> dict:
    c = db.conn()
    now = time.time() * 1000
    today0 = datetime.datetime.now().replace(
        hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000

    def one(sql, *args):
        return c.execute(sql, args).fetchone()[0]

    edited_recs = one("SELECT COUNT(DISTINCT record_id) FROM segments WHERE e_text IS NOT NULL OR e_spk IS NOT NULL")
    audio_bytes = one("SELECT COALESCE(SUM(bytes),0) FROM records")
    total_ms = one("SELECT COALESCE(SUM(dur_ms),0) FROM records")
    queue = c.execute(
        "SELECT state, COUNT(*) n FROM jobs WHERE state IN ('queued','running') GROUP BY state").fetchall()
    running = next((r["n"] for r in queue if r["state"] == "running"), 0)
    queued = next((r["n"] for r in queue if r["state"] == "queued"), 0)
    recent = c.execute(
        "SELECT id, title, status, created_at FROM records ORDER BY created_at DESC LIMIT 5").fetchall()
    active_jobs = c.execute(
        "SELECT j.id, j.state, j.progress, j.error, r.title FROM jobs j"
        " JOIN records r ON r.id=j.record_id"
        " WHERE j.state IN ('queued','running','fail') ORDER BY j.id DESC LIMIT 5").fetchall()

    return {
        "records": one("SELECT COUNT(*) FROM records"),
        "today": one("SELECT COUNT(*) FROM records WHERE created_at>=?", int(today0)),
        "by_status": {
            r["status"]: r["n"] for r in
            c.execute("SELECT status, COUNT(*) n FROM records GROUP BY status").fetchall()},
        "edited": edited_recs,
        "audio_bytes": audio_bytes,
        "total_hours": round(total_ms / 3600000.0, 1),
        "queue": {"running": running, "queued": queued},
        "recent": [dict(r) for r in recent],
        "active_jobs": [dict(r) for r in active_jobs],
        "ai_ready": bool(db.get("ai_key")),
        "server_time": int(now),
    }


@router.get("/settings")
def get_settings():
    out = {}
    for k in db.PUBLIC_SETTING_KEYS:
        out[k] = db.get(k, "")
    out["auto_transcribe"] = db.get("auto_transcribe", "1") == "1"
    out["sync_token"] = db.get("sync_token", "")
    for k in SECRET_KEYS:
        v = db.get(k, "")
        out[k] = ("•" * 12) if v else ""
        out[k + "_set"] = bool(v)
    out["cloud_asr_ready"] = bool(db.get("cloud_asr_base") and db.get("cloud_asr_key"))
    return out


class SettingsPatch(BaseModel):
    default_engine: Optional[str] = None
    auto_transcribe: Optional[bool] = None
    whisper_model: Optional[str] = None
    whisper_device: Optional[str] = None
    whisper_compute: Optional[str] = None
    whisper_language: Optional[str] = None
    cloud_asr_base: Optional[str] = None
    cloud_asr_model: Optional[str] = None
    ai_base: Optional[str] = None
    ai_model: Optional[str] = None
    # 密钥与令牌：传非空才写（掩码回传不算更改）
    cloud_asr_key: Optional[str] = None
    ai_key: Optional[str] = None


@router.patch("/settings")
def patch_settings(p: SettingsPatch):
    plain = {k: v for k, v in p.dict().items() if v is not None}
    for k, v in plain.items():
        if k in SECRET_KEYS and set(v) == {"•"}:
            continue  # 掩码原样回传，忽略
        if k == "auto_transcribe":
            v = "1" if v else "0"
        db.set(k, v)
    return {"ok": True}


@router.post("/settings/reset-token")
def reset_token():
    token = "vn-" + secrets.token_hex(8)
    db.set("sync_token", token)
    return {"sync_token": token}


@router.get("/storage")
def storage():
    total = used = 0
    audio_dir = os.path.join(config.data_dir(), "audio")
    for root, _, files in os.walk(audio_dir):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    db_bytes = os.path.getsize(config.db_path()) if os.path.isfile(config.db_path()) else 0
    return {"audio_bytes": total, "db_bytes": db_bytes, "data_dir": config.data_dir()}
