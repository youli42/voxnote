"""FastAPI 应用工厂：路由装配、静态控制台、启动初始化。

安全模型（当前阶段，v0.1）：
  - 默认只绑 127.0.0.1，控制台 API 本机免鉴权；
  - 设备同步 API（/api/v1/sync/*）始终要求 Bearer 配对令牌；
  - 绑 0.0.0.0 前请自行加反代鉴权（README 有说明）。
"""
import importlib
import importlib.util
import os

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

from . import db, config


def create_app() -> FastAPI:
    app = FastAPI(title="voxnote server", version="0.1.0",
                  docs_url="/api/docs", openapi_url="/api/openapi.json")

    config.ensure_dirs()
    db.init()
    _wire_routers(app)

    @app.get("/api/v1/health")
    def health():
        from . import __version__
        return {"ok": True, "version": __version__}

    # 控制台（静态文件放最后，避免吞掉 /api）
    web = config.web_dir()
    if os.path.isdir(web):
        app.mount("/", StaticFiles(directory=web, html=True), name="web")
    return app


def _wire_routers(app: FastAPI):
    """各功能提交逐步接入；模块不存在才跳过（内部错误照常抛出）。"""
    spec = [
        ("api_sync", "/api/v1/sync", ["sync"]),
        ("api_records", "/api/v1", ["records"]),
        ("api_jobs", "/api/v1", ["jobs"]),
        ("api_settings", "/api/v1", ["settings"]),
        ("api_ai", "/api/v1/ai", ["ai"]),
    ]
    for mod, prefix, tags in spec:
        if importlib.util.find_spec("voxnote_server." + mod) is None:
            continue
        m = importlib.import_module("voxnote_server." + mod)
        app.include_router(m.router, prefix=prefix, tags=tags)
