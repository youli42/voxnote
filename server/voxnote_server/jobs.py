"""转写任务队列：DB 为真源，单 worker 线程顺序消费。

一次只跑一个转写任务（本地引擎加载模型后顺序跑最快，也避免 GPU 抢占）。
崩溃恢复：启动时把遗留 running 复位回 queued。
"""
import threading
import time
import traceback

from . import db

_stop = threading.Event()
_thread = None
# 进度回写节流：同一任务最多 1 秒一次，避免高频写库
_last_progress = {}


def start_worker():
    global _thread
    if _thread is not None and _thread.is_alive():
        return
    _stop.clear()
    _recover()
    _thread = threading.Thread(target=_loop, name="voxnote-jobs", daemon=True)
    _thread.start()


def stop_worker():
    _stop.set()


def _recover():
    with db.conn() as c:
        c.execute("UPDATE jobs SET state='queued' WHERE state='running'")


def enqueue(record_id: str, engine_id: str) -> int:
    now = int(time.time() * 1000)
    with db.conn() as c:
        cur = c.execute(
            "INSERT INTO jobs(record_id, engine, state, created_at) VALUES(?,?,?,?)",
            (record_id, engine_id, "queued", now))
        c.execute("UPDATE records SET status='queued', engine=?, error='' WHERE id=?",
                  (engine_id, record_id))
        return cur.lastrowid


def enqueue_default(record_id: str) -> int:
    """上传/导入后的自动入队（受 auto_transcribe 设置控制）。"""
    if db.get("auto_transcribe", "1") != "1":
        return 0
    engine_id = db.get("default_engine", "whisper-local")
    from . import engines
    e = engines.get(engine_id)
    if e is None or not e.available():
        # 默认引擎不可用：记录停在 raw，控制台可手动换引擎重试
        return 0
    return enqueue(record_id, engine_id)


def cancel(job_id: int) -> bool:
    with db.conn() as c:
        cur = c.execute("UPDATE jobs SET state='cancel', finished_at=? WHERE id=? AND state='queued'",
                        (int(time.time() * 1000), job_id))
        if cur.rowcount > 0:
            row = c.execute("SELECT record_id FROM jobs WHERE id=?", (job_id,)).fetchone()
            if row:
                c.execute("UPDATE records SET status='raw' WHERE id=? AND status='queued'", (row["record_id"],))
            return True
        return False


def _loop():
    from . import engines
    while not _stop.is_set():
        job = _claim()
        if job is None:
            _stop.wait(1.0)
            continue
        try:
            _run(job, engines)
        except Exception:
            # 任何阶段崩了都不能带死 worker：记失败，接着消费下一个
            traceback.print_exc()
            try:
                _finish(job, "fail", "任务异常，见服务端日志", None)
            except Exception:
                traceback.print_exc()


def _claim():
    with db.conn() as c:
        row = c.execute(
            "SELECT * FROM jobs WHERE state='queued' ORDER BY id LIMIT 1").fetchone()
        if row is None:
            return None
        c.execute("UPDATE jobs SET state='running', started_at=? WHERE id=?",
                  (int(time.time() * 1000), row["id"]))
        c.execute("UPDATE records SET status='asr' WHERE id=?", (row["record_id"],))
        return row


def _run(job, engines):
    rid = job["record_id"]
    rec = db.record_row(rid)
    now = lambda: int(time.time() * 1000)  # noqa: E731
    if rec is None or not rec["audio_path"]:
        _finish(job, "fail", "音频文件缺失", None)
        return
    e = engines.get(job["engine"])
    if e is None or not e.available():
        _finish(job, "fail", "引擎不可用：%s" % job["engine"], None)
        return

    last = [0.0]

    def on_progress(f):
        if f - last[0] < 0.05:
            return
        last[0] = f
        job_id = job["id"]
        if _last_progress.get(job_id, 0) and time.time() - _last_progress[job_id] < 1.0:
            return
        _last_progress[job_id] = time.time()
        with db.conn() as c:
            c.execute("UPDATE jobs SET progress=? WHERE id=?", (f, job_id))

    try:
        segs = e.transcribe(rec["audio_path"], rec, on_progress)
    except Exception as t:
        traceback.print_exc()
        _finish(job, "fail", str(t)[:300], None)
        return

    db.replace_machine_segments(rid, segs)
    dur = segs[-1]["e_ms"] if segs else rec["dur_ms"]
    db.update_record(rid, status="done", engine=e.id, dur_ms=dur or rec["dur_ms"], error="")
    db.export_mirror(rid)
    _finish(job, "done", "", len(segs))


def _finish(job, state, error, n_segs):
    with db.conn() as c:
        c.execute("UPDATE jobs SET state=?, error=?, finished_at=?, progress=? WHERE id=?",
                  (state, error, int(time.time() * 1000),
                   1.0 if state == "done" else 0.0, job["id"]))
        if state == "fail":
            c.execute("UPDATE records SET status='fail', error=? WHERE id=?",
                      (error, job["record_id"]))
