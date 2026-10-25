"""设置与统计 API（控制台）。

密钥类值只在 GET 时给掩码；写入用原值。sync_token 属于"可看可重置"，
重置后旧令牌立即失效（设备端需要更新）。
存储位置（音频目录 / 数据库文件）不入库——数据库自己的位置不能存在数据库里，
写 data/config.json，由 config 模块在每次取路径时应用。
"""
import datetime
import os
import re
import secrets
import socket
import time

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel
from typing import Optional

from . import db, config

router = APIRouter()


@router.get("/stats")
def stats():
    return build_stats()


SECRET_KEYS = {"cloud_asr_key", "ai_key", "sync_password"}


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
    llama_server_path: Optional[str] = None
    llama_port: Optional[str] = None
    llama_repo: Optional[str] = None
    hf_mirror: Optional[str] = None
    disabled_engines: Optional[str] = None
    # 密钥与令牌：传非空才写（掩码回传不算更改）
    cloud_asr_key: Optional[str] = None
    ai_key: Optional[str] = None
    sync_password: Optional[str] = None


@router.patch("/settings")
def patch_settings(p: SettingsPatch):
    plain = {k: v for k, v in p.dict().items() if v is not None}
    for k, v in plain.items():
        if k in SECRET_KEYS and set(v) == {"•"}:
            continue  # 掩码原样回传，忽略
        if k == "auto_transcribe":
            v = "1" if v else "0"
        if k == "disabled_engines":
            ids = [x.strip() for x in v.split(",") if x.strip()]
            bad = [x for x in ids if not re.fullmatch(r"[a-z0-9_.-]{1,40}", x)]
            if bad:
                raise HTTPException(400, "非法引擎 id：%s" % ",".join(bad))
            v = ",".join(ids)
        db.set(k, v)
    if "default_engine" in plain:
        # 设置页选了哪个引擎就用哪个：把它移出停用表（历史遗留值不挡转写）
        from . import engines
        engines.ensure_default_enabled()
    return {"ok": True}


@router.post("/settings/reset-token")
def reset_token():
    token = "vn-" + secrets.token_hex(8)
    db.set("sync_token", token)
    return {"sync_token": token}


def _local_ips() -> list:
    """本机所有 IPv4（排除链路本地 169.254.*）：多网卡/虚拟组网（Tailscale、
    ZeroTier）时控制台让用户挑哪个 IP 生成配对码。UDP connect 探测出的主 IP 排最前。"""
    ips = []
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(("10.255.255.255", 1))
            ips.append(s.getsockname()[0])
        finally:
            s.close()
    except OSError:
        pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip.startswith("169.254.") or ip in ips:
                continue
            ips.append(ip)
    except OSError:
        pass
    return ips


@router.get("/settings/pair-info")
def pair_info(request: Request):
    """扫码配对原料：本机 IP 候选、端口（取控制台当前访问用的）、令牌。
    二维码由控制台前端生成，服务端不引 QR 依赖。"""
    host = request.headers.get("host", "")
    port = host.rsplit(":", 1)[1] if ":" in host else "8787"
    return {"ips": _local_ips(), "port": port,
            "token": db.get("sync_token", ""),
            "password_set": bool(db.get("sync_password"))}


@router.get("/storage")
def storage():
    total = 0
    for root, _, files in os.walk(config.audio_base()):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    db_bytes = os.path.getsize(config.db_path()) if os.path.isfile(config.db_path()) else 0
    ov = config.overrides()
    tmp = config.temp_dir()
    temp_bytes = temp_files = 0
    if os.path.isdir(tmp):
        for f in os.listdir(tmp):
            fp = os.path.join(tmp, f)
            try:
                if os.path.isfile(fp):
                    temp_files += 1
                    temp_bytes += os.path.getsize(fp)
            except OSError:
                pass
    return {"audio_bytes": total, "db_bytes": db_bytes, "data_dir": config.data_dir(),
            "audio_dir": config.audio_base(), "db_path": config.db_path(),
            "temp_dir": tmp, "temp_bytes": temp_bytes, "temp_files": temp_files,
            # 原始覆盖值（表单用）：空 = 未覆盖（默认）；解析后的绝对路径别回填表单，
            # 否则保存会把绝对路径固化进 config.json，数据目录搬家后就指错地方了。
            "audio_dir_set": ov.get("audio_dir") or "",
            "db_path_set": ov.get("db_path") or "",
            "config_file": config.config_file()}


class OpenTarget(BaseModel):
    """storage/open 的目标：data=数据目录 audio=音频目录 db=数据库文件 temp=转码临时目录"""
    target: str


def _open_in_file_manager(path: str):
    """在宿主机文件管理器里打开目录；文件则定位选中（Windows 的 /select,）。"""
    import subprocess
    import sys
    if sys.platform == "win32":
        if os.path.isfile(path):
            subprocess.Popen(["explorer", "/select,", os.path.normpath(path)])
        else:
            os.startfile(path)  # noqa: 只有 Windows 有，这里必然是
    elif sys.platform == "darwin":
        subprocess.Popen(["open", path if os.path.isdir(path) else os.path.dirname(path)])
    else:
        subprocess.Popen(["xdg-open", path if os.path.isdir(path) else os.path.dirname(path)])


@router.post("/storage/open")
def storage_open(p: OpenTarget):
    """在服务器所在机器打开对应目录的文件管理器（自托管本机用的运维便利）。"""
    targets = {"data": config.data_dir(), "audio": config.audio_base(),
               "db": config.db_path(), "temp": config.temp_dir()}
    if p.target not in targets:
        raise HTTPException(400, "未知目标：%s" % p.target)
    path = targets[p.target]
    if not os.path.exists(path):
        os.makedirs(path, exist_ok=True)
    _open_in_file_manager(path)
    return {"ok": True}


@router.post("/storage/clean-temp")
def storage_clean_temp():
    """清空转码临时目录。转写成功会自动删，这里兜底清失败残留。"""
    tmp = config.temp_dir()
    removed = freed = 0
    if os.path.isdir(tmp):
        for f in os.listdir(tmp):
            fp = os.path.join(tmp, f)
            try:
                if os.path.isfile(fp):
                    freed += os.path.getsize(fp)
                    os.remove(fp)
                    removed += 1
            except OSError:
                pass
    return {"ok": True, "removed": removed, "freed": freed}


class StoragePatch(BaseModel):
    audio_dir: Optional[str] = None
    db_path: Optional[str] = None


@router.patch("/storage")
def patch_storage(p: StoragePatch):
    """写 data/config.json 覆盖路径。

    音频目录：保存后即时生效（只影响之后的上传/导出，旧文件留在原处仍可播放，
    records 里存的是绝对路径）；留空恢复默认。
    数据库路径：db_path 进程启动时固化，改完需手动把 voxnote.db 搬到新位置并
    重启服务——运行中切库会新旧分叉，服务端不做在线迁移。
    """
    cur = config.overrides()
    notes = []
    if p.audio_dir is not None:
        v = p.audio_dir.strip()
        if v:
            try:
                os.makedirs(config._resolve(v), exist_ok=True)
            except OSError as e:
                raise HTTPException(400, "音频目录不可创建：%s" % e)
            cur["audio_dir"] = v
            notes.append("音频目录已更新，新上传即存过去")
        else:
            cur.pop("audio_dir", None)
            notes.append("音频目录已恢复默认")
    if p.db_path is not None:
        v = p.db_path.strip()
        if v:
            try:
                os.makedirs(os.path.dirname(config._resolve(v)) or ".", exist_ok=True)
            except OSError as e:
                raise HTTPException(400, "数据库目录不可创建：%s" % e)
            cur["db_path"] = v
            notes.append("数据库路径已更新（搬移 voxnote.db 并重启后生效）")
        else:
            cur.pop("db_path", None)
            notes.append("数据库路径已恢复默认（重启后生效）")
    config.write_overrides(cur)
    return {"ok": True, "note": "；".join(notes) or "无改动"}
