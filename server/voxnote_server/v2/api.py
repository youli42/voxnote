"""v2 HTTP API（开发阶段）。

只在环境变量 `VOXNOTE_V2=1` 时由 app.py 挂载，避免影响 v1。
当前提供：health / changes / records / upload（设备令牌鉴权）。
"""
import json

from fastapi import (
    APIRouter, Depends, File, Form, Header, HTTPException, Query, UploadFile,
)

from . import auth, config, db, service, store, uploads

router = APIRouter()


@router.get("/health")
def health():
    conn = db.conn()
    with db.lock:
        count = conn.execute("SELECT COUNT(*) n FROM records").fetchone()["n"]
    return {
        "ok": True,
        "schema_version": store.schema_version(conn),
        "db": config.db_path(),
        "records": count,
    }


@router.get("/sync/changes", dependencies=[Depends(auth.require_device)])
def sync_changes(
    cursor: int = Query(0, ge=0),
    limit: int = Query(200, ge=1, le=500),
):
    return service.changes_since(db.conn(), cursor=cursor, limit=limit)


@router.get("/records", dependencies=[Depends(auth.require_device)])
def records(
    limit: int = Query(50, ge=1, le=500),
    offset: int = Query(0, ge=0),
    include_deleted: bool = Query(False),
):
    return {"records": service.list_records(
        db.conn(), limit=limit, offset=offset, include_deleted=include_deleted)}


@router.get("/records/{record_id}", dependencies=[Depends(auth.require_device)])
def record(record_id: str):
    row = service.get_record(db.conn(), record_id)
    if row is None:
        raise HTTPException(404, "记录不存在")
    return service.record_summary(row)


@router.post("/sync/upload", dependencies=[Depends(auth.require_device)])
async def upload(
    file: UploadFile = File(...),
    meta: str = Form(default=""),
    idempotency_key: str = Header(default="", alias="Idempotency-Key"),
):
    try:
        m = json.loads(meta) if meta else {}
    except Exception:
        raise HTTPException(400, "meta 不是合法 JSON")
    if not isinstance(m, dict):
        raise HTTPException(400, "meta 必须是 JSON 对象")
    data = await file.read()
    return uploads.save_upload(
        db.conn(), data, file.filename or "", m, idempotency_key)
