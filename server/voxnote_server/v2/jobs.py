"""v2 任务表：转写任务的创建与状态流转（同步执行版本）。

后续 W5 会换成后台队列；这里先把状态机与错误码跑通，接口保持兼容。
"""
from . import db, service


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
