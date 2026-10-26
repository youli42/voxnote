"""FastAPI 应用工厂：路由装配、静态控制台、启动初始化。

安全模型（当前阶段，v0.1）：
  - 默认绑 0.0.0.0（局域网设备可连，手机同步/控制台都能用）；
    建议设置环境变量 VOXNOTE_API_KEY 开启全站密钥（控制台收到 401 会提示输入，
    README 有说明）；只本机用可 --host 127.0.0.1；
  - 设备同步 API（/api/v1/sync/*）始终要求 Bearer 配对令牌。
"""
import importlib
import importlib.util
import logging
import os
import secrets

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from . import db, config

# 可选全站密钥：设置后除设备同步（自带 Bearer 配对令牌）与健康检查外，
# 所有 /api/* 都要求 X-VoxNote-Key 请求头；控制台收到 401 会提示输入。
# 留空 = 维持"默认只绑本机、控制台免鉴权"的原模型。
API_KEY = os.environ.get("VOXNOTE_API_KEY", "").strip()


def create_app() -> FastAPI:
    app = FastAPI(title="voxnote server", version="0.1.0",
                  docs_url="/api/docs", openapi_url="/api/openapi.json",
                  lifespan=_lifespan)
    _wire_routers(app)

    @app.middleware("http")
    async def _guard(request: Request, call_next):
        if API_KEY and request.url.path.startswith("/api"):
            exempt = (request.url.path.startswith("/api/v1/sync")
                      or request.url.path == "/api/v1/health")
            if not exempt and not secrets.compare_digest(
                    request.headers.get("x-voxnote-key", ""), API_KEY):
                return JSONResponse({"detail": "需要 X-VoxNote-Key"}, status_code=401)
        return await call_next(request)

    @app.get("/api/v1/health")
    def health():
        from . import __version__
        return {"ok": True, "version": __version__}

    # 控制台（静态文件放最后，避免吞掉 /api）
    web = config.web_dir()
    if os.path.isdir(web):
        v2_page = os.path.join(web, "v2.html")
        if os.path.isfile(v2_page):
            from fastapi.responses import FileResponse

            @app.get("/v2")
            def v2_console():
                return FileResponse(v2_page)
        app.mount("/", StaticFiles(directory=web, html=True), name="web")
    return app


async def _lifespan(app: FastAPI):
    config.ensure_dirs()
    db.init()
    try:
        # 历史遗留：默认引擎可能还躺在停用表里（启用开关的 UI 已移除），
        # 启动即清，保证"设置页用哪个引擎就跑哪个引擎"。
        from . import engines
        engines.ensure_default_enabled()
    except Exception:
        logging.exception("默认引擎自愈失败")
    try:
        from . import jobs
        jobs.start_worker()
    except Exception:
        # worker 起不来时所有任务会永远停在 queued，必须留痕
        logging.exception("转写 worker 启动失败，新任务将停留在排队状态")
    yield
    try:
        from . import jobs
        jobs.stop_worker()
    except Exception:
        logging.exception("转写 worker 停止失败")
    try:
        from .engines import local_llama
        local_llama.stop_server()
    except Exception:
        logging.exception("llama-server 停止失败")


def _wire_routers(app: FastAPI):
    """各功能提交逐步接入；模块不存在才跳过（内部错误照常抛出）。"""
    spec = [
        ("api_sync", "/api/v1/sync", ["sync"]),
        ("api_records", "/api/v1", ["records"]),
        ("api_jobs", "/api/v1", ["jobs"]),
        ("api_settings", "/api/v1", ["settings"]),
        ("api_models", "/api/v1/models", ["models"]),
        ("api_ai", "/api/v1/ai", ["ai"]),
    ]
    for mod, prefix, tags in spec:
        if importlib.util.find_spec("voxnote_server." + mod) is None:
            continue
        m = importlib.import_module("voxnote_server." + mod)
        app.include_router(m.router, prefix=prefix, tags=tags)

    # v2 开发路由：显式开关，默认不挂载，避免影响 v1
    if os.environ.get("VOXNOTE_V2", "").strip() == "1":
        from .v2 import api as v2_api
        app.include_router(v2_api.router, prefix="/api/v2", tags=["v2"])
