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
    _backfill_errors()
    _thread = threading.Thread(target=_loop, name="voxnote-jobs", daemon=True)
    _thread.start()


def stop_worker():
    _stop.set()


def _recover():
    with db.conn() as c:
        c.execute("UPDATE jobs SET state='queued' WHERE state='running'")


def _backfill_errors():
    """老库里已失败的记录 error 还是原始异常串：归类一遍（幂等，只处理没有 detail 的）。
    已有 detail 的说明是新代码写的，不动。"""
    try:
        with db.conn() as c:
            rows = c.execute(
                "SELECT id, error FROM records WHERE status='fail' AND error_detail=''").fetchall()
    except Exception:
        return  # 老库还没补 error_detail 列时 db.init 已负责迁移，这里兜底别带死启动
    for r in rows:
        if not r["error"]:
            continue
        friendly, raw = _classify_error(Exception(r["error"]))
        if friendly:
            with db.conn() as c:
                c.execute("UPDATE records SET error=?, error_detail=? WHERE id=?",
                          (friendly, raw, r["id"]))


def enqueue(record_id: str, engine_id: str) -> int:
    now = int(time.time() * 1000)
    with db.conn() as c:
        cur = c.execute(
            "INSERT INTO jobs(record_id, engine, state, created_at) VALUES(?,?,?,?)",
            (record_id, engine_id, "queued", now))
        c.execute("UPDATE records SET status='queued', engine=?, error='', error_detail='' WHERE id=?",
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


# ------------------------------------------------------------ 错误归类

# 已知/常见异常 → 人类可读文案。原始异常串存 records.error_detail 供控制台展开。
# 顺序即优先级，越具体的越靠前；对整个异常串做小写包含匹配。
_ERROR_RULES = [
    (("failed to load image or audio file", "failed to load audio", "unsupported audio"),
     "音频文件无法解码：格式不受支持或文件已损坏（可转成 16kHz wav 后重试）"),
    (("http 401", "unauthorized", "invalid api key", "invalid_api_key", "incorrect api key"),
     "云端 API Key 无效或未授权"),
    (("http 429", "rate limit", "rate_limit", "quota"),
     "云端请求被限流：配额用尽或频率过高"),
    (("model_not_found", "model does not exist", "http 404"),
     "模型不存在：检查引擎的模型名/仓库配置"),
    (("connecterror", "connection refused", "connectionreset", "all connection attempts failed",
      "remoteendclosed", "积极拒绝", "无法连接"),
     "无法连接转写服务：本地服务未启动或端口不对"),
    (("timedout", "timed out", "timeout", "没有正确答复"),
     "请求超时：音频过长或服务无响应"),
    (("cublas", "cudnn", "cuda error", "cuda driver"),
     "GPU 运行库缺失或不可用（可在设置里把转写引擎切到 CPU 重试）"),
    (("out of memory", "outofmemory", "not enough memory"),
     "显存/内存不足：换更小的模型或改用 CPU"),
    (("转写结果为空", "no speech", "vad did not detect"),
     "未识别到语音内容：录音可能是静音或噪音"),
    (("no such file", "file not found", "cannot find"),
     "文件缺失：音频可能被移动或删除"),
]


def _classify_error(exc):
    """→ (friendly, detail)。friendly 为 None 表示没有已知归类（调用方回退展示原文）。"""
    raw = str(exc).strip() or exc.__class__.__name__
    low = raw.lower()
    for keys, msg in _ERROR_RULES:
        if any(k in low for k in keys):
            return msg, raw
    return None, raw


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
                _finish(job, "fail", "任务异常，见服务端日志", None, traceback.format_exc()[:2000])
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

    # 已有内容的重转写：新结果先攒在内存，成功后才整层替换——中途失败不清旧稿。
    # 首次转写照旧边转边落库（本来就没有内容可丢）。
    with db.conn() as c:
        had_content = c.execute(
            "SELECT COUNT(*) FROM segments WHERE record_id=?", (rid,)).fetchone()[0] > 0

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

    # 增量落盘：引擎边转写边回调，节流 3 秒写一次机器层 + 导出镜像。
    # 手机端轮询 /sync/records?since= 即可看到"转写中"的场次内容实时增长。
    # 重转写（had_content）时跳过：增量不落库，控制台旧稿保持原样，成功后一次替换。
    last_flush = [0.0]

    def on_partial(segs_partial):
        if had_content or not segs_partial:
            return
        now = time.time()
        if now - last_flush[0] < 3.0:
            return
        last_flush[0] = now
        try:
            db.replace_machine_segments(rid, segs_partial)
            db.update_record(rid, status="asr",
                             dur_ms=segs_partial[-1]["e_ms"] or rec["dur_ms"])
            db.export_mirror(rid)
        except Exception:
            traceback.print_exc()

    try:
        segs = e.transcribe(rec["audio_path"], rec, on_progress, on_partial)
    except Exception as t:
        traceback.print_exc()
        friendly, raw = _classify_error(t)
        if had_content:
            # 旧稿还在：不把记录打成 fail（否则详情页只剩失败卡片，像被清空了一样）。
            # 保留 done 状态与全部内容，失败原因挂在记录元信息上，任务页仍可重试。
            db.update_record(rid, status="done",
                             error="重转写失败：" + (friendly or raw[:280]),
                             error_detail=raw[:2000])
            with db.conn() as c:
                c.execute("UPDATE jobs SET state='fail', error=?, finished_at=?, progress=0 WHERE id=?",
                          (friendly or ("重转写失败：" + raw[:280]), now(), job["id"]))
        else:
            _finish(job, "fail", friendly or ("转写失败：" + raw[:280]), None, raw[:2000])
        return

    db.replace_machine_segments(rid, segs)
    dur = segs[-1]["e_ms"] if segs else rec["dur_ms"]
    db.update_record(rid, status="done", engine=e.id, dur_ms=dur or rec["dur_ms"],
                     error="", error_detail="")
    db.export_mirror(rid)
    _finish(job, "done", "", len(segs))


def _finish(job, state, error, n_segs, detail=""):
    with db.conn() as c:
        c.execute("UPDATE jobs SET state=?, error=?, finished_at=?, progress=? WHERE id=?",
                  (state, error, int(time.time() * 1000),
                   1.0 if state == "done" else 0.0, job["id"]))
        if state == "fail":
            c.execute("UPDATE records SET status='fail', error=?, error_detail=? WHERE id=?",
                      (error, detail, job["record_id"]))
