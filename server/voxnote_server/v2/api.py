"""v2 HTTP API（开发阶段）。

只在环境变量 `VOXNOTE_V2=1` 时由 app.py 挂载，避免影响 v1。
鉴权/上传/ops 在后续工作包接入；当前只提供 health / changes / records 读接口。
"""
from fastapi import APIRouter, HTTPException, Query

from . import config, db, service, store

router = APIRouter()


@router.get("/health")
def health():
    conn = db.conn()
    count = conn.execute("SELECT COUNT(*) n FROM records").fetchone()["n"]
    return {
        "ok": True,
        "schema_version": store.schema_version(conn),
        "db": config.db_path(),
        "records": count,
    }


@router.get("/sync/changes")
def sync_changes(
    cursor: int = Query(0, ge=0),
    limit: int = Query(200, ge=1, le=500),
):
    return service.changes_since(db.conn(), cursor=cursor, limit=limit)


@router.get("/records")
def records(
    limit: int = Query(50, ge=1, le=500),
    offset: int = Query(0, ge=0),
    include_deleted: bool = Query(False),
):
    return {"records": service.list_records(
        db.conn(), limit=limit, offset=offset, include_deleted=include_deleted)}


@router.get("/records/{record_id}")
def record(record_id: str):
    row = service.get_record(db.conn(), record_id)
    if row is None:
        raise HTTPException(404, "记录不存在")
    return service.record_summary(row)
