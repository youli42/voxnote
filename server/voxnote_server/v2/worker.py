"""v2 后台转写 worker：queued → running → done/failed。

单用户服务器，单 worker 顺序跑（避免 GPU/远端并发争抢；远程引擎自己的并发在引擎侧）。
API 只负责入队并立即返回；进度、错误码写 jobs，记录状态写 records，客户端靠 changes 感知。
"""
import logging
import threading
import time

from . import attempts, db, engines, jobs, service

log = logging.getLogger("voxnote.v2.worker")

_stop = threading.Event()
_thread = None


def start():
    global _thread
    if _thread is not None and _thread.is_alive():
        return
    _stop.clear()
    _thread = threading.Thread(target=_loop, name="v2-transcribe-worker", daemon=True)
    _thread.start()
    log.info("v2 转写 worker 已启动")


def stop():
    _stop.set()
    t = _thread
    if t is not None:
        t.join(timeout=5)
    log.info("v2 转写 worker 已停止")


def _loop():
    conn = db.conn()
    while not _stop.is_set():
        try:
            worked = run_once(conn)
        except Exception:
            log.exception("v2 worker 单步异常")
            worked = False
        _stop.wait(0.2 if worked else 1.0)


def run_once(conn) -> bool:
    """取一个 queued job 跑完；没有任务返回 False。测试可直接调用。"""
    with db.lock:
        row = conn.execute(
            "SELECT * FROM jobs WHERE state='queued' ORDER BY priority DESC, id LIMIT 1"
        ).fetchone()
    if row is None:
        return False
    job = dict(row)
    jid = job["id"]
    jobs.start(conn, jid)
    jobs.progress(conn, jid, "loading", 0.05)
    try:
        rec = service.get_record(conn, job["record_id"])
        if rec is None:
            raise RuntimeError("记录不存在")
        engine = (engines.get(conn, job["engine_id"]) if job.get("engine_id")
                  else engines.pick(conn))
        if engine is None:
            raise RuntimeError("引擎不存在")
        audio_path = rec.get("audio_path")
        if not audio_path:
            raise RuntimeError("音频文件不存在")

        sha = rec.get("audio_sha256")
        if not sha:
            import hashlib
            from pathlib import Path
            with open(audio_path, "rb") as f:
                sha = hashlib.sha256(f.read()).hexdigest()

        jobs.progress(conn, jid, "transcribing", 0.2)
        body = engines.call_engine(engine, audio_path, job.get("language") or "zh")
        duration_ms = int(float(body.get("duration") or 0) * 1000) or int(rec.get("dur_ms") or 0)
        segs = engines._segments(body, duration_ms)
        jobs.progress(conn, jid, "finalizing", 0.9)

        attempts.create(conn, job["record_id"], {
            "audio_sha256": sha,
            "source": job.get("source") or "hub",
            "engine": engine["name"] or engine["id"],
            "model": engine.get("model") or "",
            "quality_tier": int(engine.get("quality_tier") or 3),
            "duration_ms": duration_ms,
            "segments": segs,
            "activate": True,
        })
        jobs.finish(conn, jid, "done")
        service.update_record(conn, job["record_id"], {
            "status": "done",
            "engine": engine["name"] or engine["id"],
            "error_code": "", "error_hint": "",
        })
    except Exception as e:
        log.warning("转写失败 record=%s: %s", job.get("record_id"), e)
        jobs.finish(conn, jid, "failed", "engine_error", str(e)[:500])
        try:
            service.update_record(conn, job["record_id"], {
                "status": "failed", "error_code": "engine_error",
                "error_hint": str(e)[:500]})
        except Exception:
            pass
    return True
