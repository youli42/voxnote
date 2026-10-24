"""模型管理 API（控制台）：Whisper/Qwen3-ASR 本地模型查看、下载、卸载，llama-server 启停。

控制台本机免鉴权（与 /records 等一致）；下载进度是进程内状态。
"""
import os
import re
import shutil
import threading

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Optional

from .engines import local_llama
from . import db

router = APIRouter()

# ------------------------------------------------------------ Whisper（faster-whisper · HuggingFace 缓存）

WHISPER_ORG = "Systran"
WHISPER_KNOWN = ["tiny", "base", "small", "medium", "large-v2", "large-v3"]
WHISPER_SIZE_HINT = {
    "tiny": "≈75 MB", "base": "≈145 MB", "small": "≈484 MB",
    "medium": "≈1.5 GB", "large-v2": "≈3.1 GB", "large-v3": "≈3.1 GB",
}
# 模型名单里的短名只允许这一组字符，直接拼进路径也不会逃出缓存目录
_P_MODEL = re.compile(r"^[a-z0-9._-]{1,64}$")

_wstate = {"state": "idle", "name": "", "error": "", "lock": threading.Lock()}


def _hub_cache():
    try:
        from huggingface_hub import constants
        return constants.HF_HUB_CACHE
    except Exception:
        return os.path.join(os.path.expanduser("~"), ".cache", "huggingface", "hub")


def _whisper_repo(name: str) -> str:
    return "%s/faster-whisper-%s" % (WHISPER_ORG, name)


def _dir_size(path: str) -> int:
    total = 0
    for root, _dirs, files in os.walk(path):
        for f in files:
            try:
                total += os.path.getsize(os.path.join(root, f))
            except OSError:
                pass
    return total


@router.get("/whisper")
def whisper_status():
    cache = _hub_cache()
    models = []
    for name in WHISPER_KNOWN:
        d = os.path.join(cache, "models--%s--faster-whisper-%s" % (WHISPER_ORG, name))
        ok = os.path.isdir(d)
        models.append({"name": name, "installed": ok,
                       "bytes": _dir_size(d) if ok else 0,
                       "size_hint": WHISPER_SIZE_HINT.get(name, "")})
    # 缓存里的非名单模型（手动下过的仓库）也列出来，可查看可删除
    prefix = "models--%s--faster-whisper-" % WHISPER_ORG
    seen = {m["name"] for m in models}
    try:
        for entry in os.listdir(cache):
            if not entry.startswith(prefix):
                continue
            name = entry[len(prefix):]
            if not name or name in seen:
                continue
            d = os.path.join(cache, entry)
            models.append({"name": name, "installed": True,
                           "bytes": _dir_size(d), "size_hint": ""})
    except OSError:
        pass
    return {"cache": cache, "models": models,
            "download": {k: _wstate[k] for k in ("state", "name", "error")},
            "active": db.get("whisper_model", "small")}


class WhisperDownloadReq(BaseModel):
    name: str


def _check_model_name(name: str):
    if not _P_MODEL.match(name or ""):
        raise HTTPException(400, "模型名只允许小写字母、数字与 .-_（如 small / large-v3）")


@router.post("/whisper/download")
def whisper_download(req: WhisperDownloadReq):
    name = (req.name or "").strip().lower()
    _check_model_name(name)
    try:
        import huggingface_hub  # noqa: F401
    except Exception:
        raise HTTPException(400, "需要安装 faster-whisper（自带 huggingface_hub）才能下载模型")
    d = os.path.join(_hub_cache(), "models--%s--faster-whisper-%s" % (WHISPER_ORG, name))
    if os.path.isdir(d) and _dir_size(d) > 0:
        return {"ok": True, "note": "已存在，无需下载"}
    with _wstate["lock"]:
        if _wstate["state"] == "downloading":
            raise HTTPException(400, "已有下载在进行")
        _wstate.update({"state": "downloading", "name": name, "error": ""})
    threading.Thread(target=_download_whisper, args=(name,), daemon=True).start()
    return {"ok": True}


def _download_whisper(name: str):
    try:
        from huggingface_hub import HfApi
        mirror = (db.get("hf_mirror") or "").strip().rstrip("/")
        kwargs = {}
        if mirror and "huggingface.co" not in mirror:
            kwargs["endpoint"] = mirror  # 与 Qwen3 下载一致，默认走镜像
        HfApi(**kwargs).snapshot_download(repo_id=_whisper_repo(name))
        _wstate["state"] = "done"
    except Exception as t:
        _wstate["state"] = "fail"
        _wstate["error"] = str(t)[:300]


@router.delete("/whisper/{name}")
def whisper_delete(name: str):
    _check_model_name(name)
    cache = os.path.realpath(_hub_cache())
    target = os.path.realpath(
        os.path.join(cache, "models--%s--faster-whisper-%s" % (WHISPER_ORG, name)))
    if not target.startswith(cache + os.sep):
        raise HTTPException(400, "路径不在模型缓存内")
    if not os.path.isdir(target):
        raise HTTPException(404, "该模型未下载")
    try:
        shutil.rmtree(target)
    except OSError as t:
        raise HTTPException(500, "删除失败（模型可能正被转写任务占用）：%s" % t)
    return {"ok": True}


@router.get("/asr")
def model_status():
    m = local_llama.scan_model()
    return {
        "model": m,
        "files": _gguf_files(),
        "server": local_llama.server_status(),
        "download": local_llama.download_state(),
        "settings": {
            "llama_server_path": db.get("llama_server_path", ""),
            "llama_port": db.get("llama_port", "8849"),
            "llama_repo": db.get("llama_repo", ""),
            "hf_mirror": db.get("hf_mirror", ""),
        },
    }


def _gguf_files():
    """模型目录里的全部 GGUF 文件（主模型 + mmproj 编码器），管理页列出可卸载。"""
    d = local_llama.model_dir()
    out = []
    try:
        for f in os.listdir(d):
            p = os.path.join(d, f)
            if not f.lower().endswith(".gguf") or not os.path.isfile(p):
                continue
            out.append({"name": f, "bytes": os.path.getsize(p), "path": p})
    except OSError:
        pass
    out.sort(key=lambda x: -x["bytes"])
    return out


class FileDelReq(BaseModel):
    name: str


@router.delete("/asr/file")
def asr_file_delete(req: FileDelReq):
    name = os.path.basename((req.name or "").strip())
    if not name.lower().endswith(".gguf"):
        raise HTTPException(400, "只能删除 GGUF 模型文件")
    d = os.path.realpath(local_llama.model_dir())
    p = os.path.realpath(os.path.join(d, name))
    if not p.startswith(d + os.sep):
        raise HTTPException(400, "路径不在模型目录内")
    if not os.path.isfile(p):
        raise HTTPException(404, "文件不存在")
    if local_llama.server_status()["running"]:
        raise HTTPException(400, "llama-server 正在运行且可能占用该文件，先停止推理服务")
    try:
        os.remove(p)
    except OSError as t:
        raise HTTPException(500, "删除失败：%s" % t)
    return {"ok": True}


class DownloadReq(BaseModel):
    repo: str
    quant: Optional[str] = ""


@router.post("/asr/download")
def download(req: DownloadReq):
    repo = req.repo.strip().strip("/")
    if not repo or repo.count("/") != 1:
        raise HTTPException(400, "仓库格式应为 用户名/仓库名（HuggingFace 仓库 id）")
    db.set("llama_repo", repo)
    try:
        local_llama.start_download(repo, req.quant or "")
    except RuntimeError as t:
        raise HTTPException(400, str(t))
    return {"ok": True}


@router.post("/asr/download/stop")
def download_stop():
    local_llama.stop_download()
    return {"ok": True}


class ServerReq(BaseModel):
    action: str  # start | stop


@router.post("/asr/server")
def server_ctl(req: ServerReq):
    if req.action == "start":
        try:
            local_llama.start_server()
        except RuntimeError as t:
            raise HTTPException(400, str(t))
        return {"ok": True, "server": local_llama.server_status()}
    if req.action == "stop":
        local_llama.stop_server()
        return {"ok": True, "server": local_llama.server_status()}
    raise HTTPException(400, "action 应为 start / stop")
