"""v2 HTTP API（开发阶段）。

只在环境变量 `VOXNOTE_V2=1` 时由 app.py 挂载，避免影响 v1。
当前提供：health / changes / records / upload（设备令牌鉴权）。
"""
import json
import mimetypes
import os

from fastapi import (
    APIRouter, Body, Depends, File, Form, Header, HTTPException, Query, UploadFile,
)
from fastapi.responses import FileResponse, PlainTextResponse

from . import (
    attempts, auth, config, conflicts, db, edits, engines, export, jobs,
    maintenance, manifest, ops, service, store, uploads,
)

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


@router.get("/sync/manifest", dependencies=[Depends(auth.require_device)])
def sync_manifest(
    cursor: str = Query(""),
    limit: int = Query(500, ge=1, le=1000),
):
    return manifest.build(db.conn(), cursor=cursor, limit=limit)


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


@router.get("/records/{record_id}/attempts", dependencies=[Depends(auth.require_device)])
def record_attempts(record_id: str):
    if service.get_record(db.conn(), record_id) is None:
        raise HTTPException(404, "记录不存在")
    return {"attempts": attempts.list_for_record(db.conn(), record_id)}


@router.post("/records/{record_id}/attempts", dependencies=[Depends(auth.require_device)])
def create_attempt(record_id: str, payload: dict = Body(...)):
    try:
        out = attempts.create(db.conn(), record_id, payload)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if out is None:
        raise HTTPException(404, "记录不存在")
    return out


@router.get("/records/{record_id}/transcript", dependencies=[Depends(auth.require_device)])
def record_transcript(record_id: str):
    out = edits.transcript(db.conn(), record_id)
    if out is None:
        raise HTTPException(404, "记录不存在")
    return out


@router.get("/records/{record_id}/edits", dependencies=[Depends(auth.require_device)])
def record_edits(record_id: str):
    if service.get_record(db.conn(), record_id) is None:
        raise HTTPException(404, "记录不存在")
    return {"edits": edits.list_for_record(db.conn(), record_id)}


@router.post("/records/{record_id}/edits", dependencies=[Depends(auth.require_device)])
def create_edit(record_id: str, payload: dict = Body(...)):
    try:
        out = edits.create(db.conn(), record_id, payload)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if out is None:
        raise HTTPException(404, "记录不存在")
    return out


@router.get("/records/{record_id}/audio", dependencies=[Depends(auth.require_device)])
def record_audio(record_id: str):
    rec = service.get_record(db.conn(), record_id)
    if rec is None:
        raise HTTPException(404, "记录不存在")
    path = rec.get("audio_path")
    if not path or not os.path.isfile(path):
        raise HTTPException(404, "音频文件不存在")
    media = mimetypes.guess_type(path)[0] or "application/octet-stream"
    return FileResponse(path, media_type=media,
                        filename=rec.get("audio_name") or None)


@router.get("/records/{record_id}/export", dependencies=[Depends(auth.require_device)])
def record_export(record_id: str, format: str = Query("srt")):
    body, media, filename = export.build(db.conn(), record_id, format)
    return PlainTextResponse(
        body, media_type=media,
        headers={"Content-Disposition": 'attachment; filename="%s"' % filename})


@router.post("/records/{record_id}/active-attempt",
             dependencies=[Depends(auth.require_device)])
def set_active_attempt(record_id: str, payload: dict = Body(...)):
    attempt_id = str(payload.get("attempt_id") or "").strip()
    if not attempt_id:
        raise HTTPException(400, "attempt_id 必填")
    try:
        out = attempts.set_active(
            db.conn(), record_id, attempt_id, payload.get("pinned"))
    except ValueError as e:
        raise HTTPException(400, str(e))
    if out is None:
        raise HTTPException(404, "记录不存在")
    return out


@router.get("/engines", dependencies=[Depends(auth.require_device)])
def engine_list():
    return {"engines": engines.list_all(db.conn())}


@router.post("/engines", dependencies=[Depends(auth.require_device)])
def engine_create(payload: dict = Body(...)):
    try:
        return engines.create(db.conn(), payload)
    except ValueError as e:
        raise HTTPException(400, str(e))


@router.patch("/engines/{engine_id}", dependencies=[Depends(auth.require_device)])
def engine_update(engine_id: str, payload: dict = Body(...)):
    try:
        out = engines.update(db.conn(), engine_id, payload)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if out is None:
        raise HTTPException(404, "引擎不存在")
    return out


@router.delete("/engines/{engine_id}", dependencies=[Depends(auth.require_device)])
def engine_delete(engine_id: str):
    if not engines.delete(db.conn(), engine_id):
        raise HTTPException(404, "引擎不存在")
    return {"deleted": True}


@router.post("/records/{record_id}/transcribe", dependencies=[Depends(auth.require_device)])
def transcribe_record(record_id: str, payload: dict = Body(default={})):
    try:
        out = engines.transcribe(
            db.conn(), record_id,
            engine_id=payload.get("engine_id"),
            language=str(payload.get("language") or "zh"))
    except ValueError as e:
        raise HTTPException(400, str(e))
    except Exception as e:
        raise HTTPException(502, "转写失败：%s" % e)
    if out is None:
        raise HTTPException(404, "记录不存在")
    return out


@router.get("/jobs/{job_id}", dependencies=[Depends(auth.require_device)])
def job_detail(job_id: int):
    j = jobs.get(db.conn(), job_id)
    if j is None:
        raise HTTPException(404, "任务不存在")
    return j


@router.get("/records/{record_id}/jobs", dependencies=[Depends(auth.require_device)])
def record_jobs(record_id: str, limit: int = Query(20, ge=1, le=100)):
    return {"jobs": jobs.for_record(db.conn(), record_id, limit)}


@router.get("/settings", dependencies=[Depends(auth.require_device)])
def get_settings():
    return {"settings": maintenance.settings(db.conn())}


@router.patch("/settings", dependencies=[Depends(auth.require_device)])
def patch_settings(payload: dict = Body(...)):
    conn = db.conn()
    for k, v in payload.items():
        try:
            maintenance.set_setting(conn, k, v)
        except ValueError as e:
            raise HTTPException(400, str(e))
    return {"settings": maintenance.settings(conn)}


@router.get("/storage", dependencies=[Depends(auth.require_device)])
def get_storage():
    return maintenance.storage(db.conn())


@router.get("/backups", dependencies=[Depends(auth.require_device)])
def get_backups():
    return {"backups": maintenance.backups(db.conn())}


@router.post("/backups", dependencies=[Depends(auth.require_device)])
def create_backup():
    return maintenance.backup(db.conn())


@router.post("/cleanup", dependencies=[Depends(auth.require_device)])
def run_cleanup(payload: dict = Body(default={})):
    return maintenance.cleanup(db.conn(), dry_run=bool(payload.get("dry_run", True)))


@router.get("/diagnostics", dependencies=[Depends(auth.require_device)])
def diagnostics():
    return maintenance.diagnostics(db.conn())


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


@router.post("/sync/ops", dependencies=[Depends(auth.require_device)])
def sync_ops(payload: dict = Body(...)):
    ops_list = payload.get("ops")
    if not isinstance(ops_list, list):
        raise HTTPException(400, "ops 必须是数组")
    try:
        return ops.apply(db.conn(), str(payload.get("device_id") or ""), ops_list)
    except ValueError as e:
        raise HTTPException(400, str(e))


@router.get("/sync/conflicts", dependencies=[Depends(auth.require_device)])
def sync_conflicts(limit: int = Query(100, ge=1, le=500)):
    return {"conflicts": conflicts.list_open(db.conn(), limit=limit)}


@router.post("/sync/conflicts/{conflict_id}/resolve",
             dependencies=[Depends(auth.require_device)])
def resolve_conflict(conflict_id: str, payload: dict = Body(...)):
    try:
        c = conflicts.resolve(
            db.conn(), conflict_id, payload.get("resolution"),
            payload.get("fields"))
    except ValueError as e:
        raise HTTPException(400, str(e))
    if c is None:
        raise HTTPException(404, "冲突不存在")
    return c
