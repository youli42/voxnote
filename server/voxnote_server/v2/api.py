"""v2 HTTP API（开发阶段）。

只在环境变量 `VOXNOTE_V2=1` 时由 app.py 挂载，避免影响 v1。
当前提供：health / changes / records / upload（设备令牌鉴权）。
"""
import asyncio
import hmac
import json
import mimetypes
import os

from fastapi import (
    APIRouter, Body, Depends, File, Form, Header, HTTPException, Query, Request,
    UploadFile,
)
from fastapi.responses import FileResponse, PlainTextResponse, StreamingResponse

from . import (
    attempts, auth, config, conflicts, db, devices, edits, engines, export, jobs,
    maintenance, manifest, ops, service, store, uploads,
)

router = APIRouter()

# 记录状态筛选白名单（控制台补充接口 §7）
RECORD_STATUSES = {"uploaded", "queued", "transcribing", "done", "failed"}
# PATCH /records/{id} 允许的字段（控制台补充接口 §3）
RECORD_PATCHABLE = {"title", "cat", "marks"}


def _marks_json(value):
    """marks 归一化：数组或 JSON 数组字符串 → 存储用的 JSON 字符串。"""
    if isinstance(value, list):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, str):
        try:
            parsed = json.loads(value)
        except Exception:
            raise HTTPException(400, "marks 必须是 JSON 数组或数组字符串")
        if not isinstance(parsed, list):
            raise HTTPException(400, "marks 必须是 JSON 数组或数组字符串")
        return json.dumps(parsed, ensure_ascii=False)
    raise HTTPException(400, "marks 必须是 JSON 数组或数组字符串")


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


@router.get("/stats", dependencies=[Depends(auth.require_device)])
def stats():
    """控制台工作台统计（控制台补充接口 §1）。"""
    return maintenance.stats(db.conn())


@router.get("/console/pair")
def console_pair(request: Request):
    """本机控制台自动取设备令牌；非本机请求必须自己带令牌，避免局域网泄露。"""
    host = request.client.host if request.client else ""
    if host not in ("127.0.0.1", "::1", "localhost"):
        raise HTTPException(403, "只允许本机自动取令牌；远程请手动填写")
    return {"token": auth.device_token(db.conn())}


@router.get("/events")
async def events(token: str = Query("")):
    """SSE：变更流有更新就推一条事件；控制台据此刷新，不再 3 秒轮询。

    浏览器 EventSource 不能带 Authorization 头，所以令牌走 query（仅本机控制台使用）。
    """
    expected = auth.device_token(db.conn())
    if not expected or not hmac.compare_digest(token or "", expected):
        raise HTTPException(401, "无效的设备令牌")

    async def gen():
        last = -1
        yield "event: hello\ndata: {}\n\n"
        while True:
            try:
                conn = db.conn()
                with db.lock:
                    row = conn.execute(
                        "SELECT COALESCE(MAX(rev),0) r FROM changes").fetchone()
                rev = int(row["r"])
                if rev != last:
                    last = rev
                    yield "event: change\ndata: %s\n\n" % json.dumps({"rev": rev})
                else:
                    yield ": ping\n\n"
            except Exception:
                yield ": error\n\n"
            await asyncio.sleep(2)

    return StreamingResponse(gen(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache",
                                      "X-Accel-Buffering": "no"})


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
    q: str = Query(""),
    status: str = Query(""),
    cat: str = Query(""),
    has_audio: bool = Query(False),
):
    """记录列表；筛选扩展见控制台补充接口 §7（返回结构不变）。"""
    status_filter = (status or "").strip().lower()
    if status_filter and status_filter not in RECORD_STATUSES:
        raise HTTPException(400, "无效的状态筛选：%s" % status)
    return {"records": service.list_records(
        db.conn(), limit=limit, offset=offset, include_deleted=include_deleted,
        q=q, status=status_filter, cat=cat, has_audio=has_audio)}


@router.get("/records/{record_id}", dependencies=[Depends(auth.require_device)])
def record(record_id: str):
    row = service.get_record(db.conn(), record_id)
    if row is None:
        raise HTTPException(404, "记录不存在")
    return service.record_summary(row)


@router.patch("/records/{record_id}", dependencies=[Depends(auth.require_device)])
def patch_record(record_id: str, payload: dict = Body(...)):
    """控制台记录编辑：title / cat / marks 任意组合（控制台补充接口 §3）。"""
    unknown = set(payload) - RECORD_PATCHABLE
    if unknown:
        raise HTTPException(400, "不允许修改字段：%s" % "、".join(sorted(unknown)))

    fields = {}
    if "title" in payload:
        if not isinstance(payload["title"], str):
            raise HTTPException(400, "title 必须是字符串")
        fields["title"] = payload["title"]
    if "cat" in payload:
        cat = payload["cat"]
        if cat is not None and not isinstance(cat, str):
            raise HTTPException(400, "cat 必须是字符串或 null")
        fields["cat"] = cat
    if "marks" in payload:
        fields["marks"] = _marks_json(payload["marks"])

    conn = db.conn()
    if service.get_record(conn, record_id) is None:
        raise HTTPException(404, "记录不存在")
    try:
        rec = service.update_record(conn, record_id, fields)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if rec is None:
        raise HTTPException(404, "记录不存在")
    return {"record": service.record_summary(rec), "rev": rec["rev"]}


@router.delete("/records/{record_id}", dependencies=[Depends(auth.require_device)])
def delete_record(record_id: str):
    """墓碑删除：只标记 deleted_at 并递增 rev，不删除音频文件。"""
    rec = service.tombstone_record(db.conn(), record_id)
    if rec is None:
        raise HTTPException(404, "记录不存在")
    return {"ok": True, "rev": rec["rev"]}


@router.get("/records/{record_id}/attempts", dependencies=[Depends(auth.require_device)])
def record_attempts(record_id: str):
    if service.get_record(db.conn(), record_id) is None:
        raise HTTPException(404, "记录不存在")
    return {"attempts": attempts.list_for_record(db.conn(), record_id)}


@router.get("/records/{record_id}/attempts/{attempt_id}",
            dependencies=[Depends(auth.require_device)])
def record_attempt_detail(record_id: str, attempt_id: str):
    """单次尝试详情：attempt + segments + is_active（控制台补充接口 §5）。"""
    if service.get_record(db.conn(), record_id) is None:
        raise HTTPException(404, "记录不存在")
    out = attempts.get_for_record(db.conn(), record_id, attempt_id)
    if out is None:
        raise HTTPException(404, "尝试不存在")
    return out


@router.get("/records/{record_id}/diff", dependencies=[Depends(auth.require_device)])
def record_diff(record_id: str,
                from_attempt: str = Query("", alias="from"),
                to_attempt: str = Query("", alias="to")):
    """两次尝试逐句对比；缺边对应 text 为空（控制台补充接口 §5）。"""
    conn = db.conn()
    rec = service.get_record(conn, record_id)
    if rec is None:
        raise HTTPException(404, "记录不存在")
    active = rec.get("active_attempt_id") or ""
    from_id = (from_attempt or "").strip() or active
    to_id = (to_attempt or "").strip() or active
    if not from_id or not to_id:
        raise HTTPException(400, "缺少 from/to 参数，且记录没有生效尝试")
    out = attempts.diff(conn, record_id, from_id, to_id)
    if out is None:
        raise HTTPException(404, "尝试不存在")
    return out


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


@router.delete("/records/{record_id}/edits/{edit_id}",
               dependencies=[Depends(auth.require_device)])
def delete_edit(record_id: str, edit_id: str):
    """删除某条修订（恢复机器原文），返回 {"ok": true}。"""
    if service.get_record(db.conn(), record_id) is None:
        raise HTTPException(404, "记录不存在")
    if not edits.delete(db.conn(), record_id, edit_id):
        raise HTTPException(404, "修订不存在")
    return {"ok": True}


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


@router.get("/engines/{engine_id}/probe", dependencies=[Depends(auth.require_device)])
def engine_probe(engine_id: str):
    e = engines.get(db.conn(), engine_id)
    if e is None:
        raise HTTPException(404, "引擎不存在")
    return engines.probe(e)


@router.post("/records/{record_id}/transcribe", dependencies=[Depends(auth.require_device)])
def transcribe_record(record_id: str, payload: dict = Body(default={})):
    conn = db.conn()
    if service.get_record(conn, record_id) is None:
        raise HTTPException(404, "记录不存在")
    try:
        engine = engines.pick(conn, payload.get("engine_id"))
    except ValueError as e:
        raise HTTPException(400, str(e))
    job_id = jobs.create(
        conn, record_id,
        engine_id=engine["id"],
        engine=engine["name"] or engine["id"],
        source="hub",
        language=str(payload.get("language") or "zh"),
    )
    service.update_record(conn, record_id, {
        "status": "queued",
        "engine": engine["name"] or engine["id"],
        "error_code": "", "error_hint": "",
    })
    return {"job_id": job_id, "state": "queued", "engine": engines.public(engine)}


@router.get("/jobs", dependencies=[Depends(auth.require_device)])
def job_list(state: str = Query("all"), limit: int = Query(50, ge=1, le=500)):
    """任务队列（控制台补充接口 §2）：state=all|queued|running|failed|done。"""
    try:
        items = jobs.list_jobs(db.conn(), state=state, limit=limit)
    except ValueError as e:
        raise HTTPException(400, str(e))
    return {"jobs": items, "counts": jobs.counts(db.conn())}


@router.get("/jobs/{job_id}", dependencies=[Depends(auth.require_device)])
def job_detail(job_id: int):
    j = jobs.get(db.conn(), job_id)
    if j is None:
        raise HTTPException(404, "任务不存在")
    return j


@router.post("/jobs/{job_id}/retry", dependencies=[Depends(auth.require_device)])
def job_retry(job_id: int):
    """重试：新建一条 queued 任务（不复活原任务），记录状态置 queued。"""
    try:
        new_id = jobs.retry(db.conn(), job_id)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if new_id is None:
        raise HTTPException(404, "任务不存在")
    return {"job_id": new_id}


@router.post("/jobs/{job_id}/cancel", dependencies=[Depends(auth.require_device)])
def job_cancel(job_id: int):
    """取消：queued → canceled；running → cancel_requested=1。"""
    try:
        state = jobs.cancel(db.conn(), job_id)
    except ValueError as e:
        raise HTTPException(400, str(e))
    if state is None:
        raise HTTPException(404, "任务不存在")
    return {"ok": True, "state": state}


@router.get("/records/{record_id}/jobs", dependencies=[Depends(auth.require_device)])
def record_jobs(record_id: str, limit: int = Query(20, ge=1, le=100)):
    return {"jobs": jobs.for_record(db.conn(), record_id, limit)}


@router.get("/devices", dependencies=[Depends(auth.require_device)])
def device_list():
    """设备列表：读 devices 表，空表返回空数组（控制台补充接口 §6）。"""
    return {"devices": devices.list_all(db.conn())}


@router.post("/tokens/reset", dependencies=[Depends(auth.require_device)])
def reset_token():
    """重置设备令牌：返回新令牌，旧令牌立即失效（手机需重新配对）。"""
    return {"token": devices.reset_token(db.conn())}


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
