"""v2 任务表：转写任务的创建与状态流转（同步执行版本）。

后续 W5 会换成后台队列；这里先把状态机与错误码跑通，接口保持兼容。
"""
from . import db, service

# 控制台筛选口径：running 同时包含 transcribing（见控制台补充接口 §2）
STATES = ("queued", "running", "failed", "done")
_ACTIVE = ("queued", "running", "transcribing")


def create(conn, record_id: str, engine_id: str = "", engine: str = "",
           source: str = "hub", language: str = "zh") -> int:
    with db.lock, conn:
        cur = conn.execute(
            """INSERT INTO jobs(record_id, engine_id, engine, source, language,
                                state, stage, progress, queued_at)
               VALUES(?,?,?,?,?,'queued','queued',0,?)""",
            (record_id, engine_id, engine, source, language, service.now_ms()),
        )
        return int(cur.lastrowid)


def start(conn, job_id: int):
    with db.lock, conn:
        conn.execute(
            "UPDATE jobs SET state='running', stage='transcribing', started_at=? WHERE id=?",
            (service.now_ms(), job_id))


def progress(conn, job_id: int, stage: str, value: float):
    with db.lock, conn:
        conn.execute("UPDATE jobs SET stage=?, progress=? WHERE id=?",
                     (stage, float(value), job_id))


def finish(conn, job_id: int, state: str, error_code: str = "", error_hint: str = ""):
    with db.lock, conn:
        conn.execute(
            "UPDATE jobs SET state=?, stage=?, progress=1.0, error_code=?, error_hint=?,"
            " finished_at=? WHERE id=?",
            (state, state, error_code, error_hint, service.now_ms(), job_id))


def get(conn, job_id: int):
    with db.lock:
        row = conn.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone()
        return dict(row) if row else None


def for_record(conn, record_id: str, limit: int = 20) -> list:
    with db.lock:
        rows = conn.execute(
            "SELECT * FROM jobs WHERE record_id=? ORDER BY id DESC LIMIT ?",
            (record_id, max(1, min(int(limit), 100)))).fetchall()
    return [dict(r) for r in rows]


def public(row) -> dict:
    """任务列表项：字段名严格对齐控制台补充接口 §2（含记录标题）。"""
    r = dict(row)
    return {
        "id": int(r["id"]),
        "record_id": r.get("record_id", ""),
        "record_title": r.get("record_title") or "",
        "engine": r.get("engine") or "",
        "source": r.get("source") or "",
        "language": r.get("language") or "",
        "state": r.get("state") or "",
        "stage": r.get("stage") or "",
        "progress": float(r.get("progress") or 0),
        "error_code": r.get("error_code") or "",
        "error_hint": r.get("error_hint") or "",
        "queued_at": int(r.get("queued_at") or 0),
        "started_at": int(r.get("started_at") or 0),
        "finished_at": int(r.get("finished_at") or 0),
    }


def counts(conn) -> dict:
    """队列计数：queued / running（含 transcribing）/ failed / done。"""
    with db.lock:
        rows = conn.execute(
            "SELECT state, COUNT(*) n FROM jobs GROUP BY state").fetchall()
    m = {r["state"]: int(r["n"]) for r in rows}
    return {
        "queued": m.get("queued", 0),
        "running": m.get("running", 0) + m.get("transcribing", 0),
        "failed": m.get("failed", 0),
        "done": m.get("done", 0),
    }


def list_jobs(conn, state: str = "all", limit: int = 50) -> list:
    """任务列表（带记录标题）；state=all 时含 canceled 等所有状态。"""
    state = str(state or "all").strip().lower() or "all"
    if state != "all" and state not in STATES:
        raise ValueError("无效的任务状态筛选：%s" % state)
    limit = max(1, min(int(limit), 500))
    where, args = "", []
    if state == "running":
        where, args = " WHERE j.state IN ('running','transcribing')", []
    elif state != "all":
        where, args = " WHERE j.state=?", [state]
    with db.lock:
        rows = conn.execute(
            "SELECT j.*, r.title AS record_title FROM jobs j"
            " LEFT JOIN records r ON r.id=j.record_id" + where +
            " ORDER BY j.id DESC LIMIT ?", args + [limit]).fetchall()
    return [public(r) for r in rows]


def retry(conn, job_id: int):
    """失败/完成/取消的任务 → 新建一条 queued 任务，并把记录状态置 queued。

    返回新任务 id；任务不存在返回 None；任务仍在跑则报错。
    """
    with db.lock:
        job = get(conn, job_id)
        if job is None:
            return None
        if job["state"] in _ACTIVE:
            raise ValueError("任务尚未结束，不能重试")
        new_id = create(
            conn, job["record_id"], engine_id=job.get("engine_id") or "",
            engine=job.get("engine") or "", source=job.get("source") or "hub",
            language=job.get("language") or "zh")
        service.update_record(conn, job["record_id"], {
            "status": "queued",
            "engine": job.get("engine") or "",
            "error_code": "", "error_hint": "",
        })
    return new_id


def cancel(conn, job_id: int):
    """取消任务：queued 直接置 canceled，running 置 cancel_requested=1。

    返回 "canceled" / "requested"；任务不存在返回 None；已结束则报错。
    """
    with db.lock:
        job = get(conn, job_id)
        if job is None:
            return None
        state = job["state"]
        if state == "queued":
            with conn:
                conn.execute(
                    "UPDATE jobs SET state='canceled', stage='canceled', finished_at=?"
                    " WHERE id=?", (service.now_ms(), job_id))
            return "canceled"
        if state in ("running", "transcribing"):
            with conn:
                conn.execute(
                    "UPDATE jobs SET cancel_requested=1 WHERE id=?", (job_id,))
            return "requested"
        raise ValueError("任务已结束，不能取消")
