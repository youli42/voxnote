"""模型管理 API（控制台）：Qwen3-ASR GGUF 下载 / llama-server 启停 / 状态。

控制台本机免鉴权（与 /records 等一致）；下载进度是进程内状态。
"""
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from typing import Optional

from .engines import local_llama
from . import db

router = APIRouter()


@router.get("/asr")
def model_status():
    m = local_llama.scan_model()
    return {
        "model": m,
        "server": local_llama.server_status(),
        "download": local_llama.download_state(),
        "settings": {
            "llama_server_path": db.get("llama_server_path", ""),
            "llama_port": db.get("llama_port", "8849"),
            "llama_repo": db.get("llama_repo", ""),
            "hf_mirror": db.get("hf_mirror", ""),
        },
    }


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
