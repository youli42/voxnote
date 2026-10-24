"""任务与引擎 API（控制台）。"""
from fastapi import APIRouter, HTTPException

from . import db, engines

router = APIRouter()


@router.get("/engines")
def list_engines():
    return {"engines": engines.all_infos(),
            "default": db.get("default_engine", "whisper-local")}


@router.get("/jobs")
def list_jobs(state: str = "", limit: int = 50, offset: int = 0):
    limit = max(0, min(limit, 200))  # 同 records：负值会绕过分页上限
    where, args = [], []
    if state:
        where.append("j.state=?")
        args.append(state)
    cond = (" WHERE " + " AND ".join(where)) if where else ""
    c = db.conn()
    total = c.execute("SELECT COUNT(*) FROM jobs j" + cond, args).fetchone()[0]
    sql = ("SELECT j.*, r.title FROM jobs j JOIN records r ON r.id=j.record_id"
           + cond + " ORDER BY j.id DESC LIMIT ? OFFSET ?")
    rows = c.execute(sql, args + [limit, max(0, offset)]).fetchall()
    out = []
    for r in rows:
        out.append({
            "id": r["id"], "record_id": r["record_id"], "title": r["title"],
            "engine": r["engine"], "state": r["state"], "progress": r["progress"],
            "error": r["error"], "created_at": r["created_at"],
            "started_at": r["started_at"], "finished_at": r["finished_at"],
        })
    return {"jobs": out, "total": total}


@router.post("/jobs/{job_id}/retry")
def retry(job_id: int):
    row = db.conn().execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
    if row is None:
        raise HTTPException(404, "任务不存在")
    from . import engines as es, jobs as jq
    e = es.get(row["engine"])
    if e is None or not e.available():
        raise HTTPException(400, "引擎不可用：%s" % row["engine"])
    if not es.enabled(row["engine"]):
        raise HTTPException(400, "引擎已停用：%s（设置 → 转写可重新启用）" % e.name)
    new_id = jq.enqueue(row["record_id"], row["engine"])
    return {"ok": True, "job": new_id}


@router.post("/jobs/{job_id}/cancel")
def cancel(job_id: int):
    from . import jobs as jq
    if not jq.cancel(job_id):
        raise HTTPException(400, "任务不在排队状态，无法取消")
    return {"ok": True}
