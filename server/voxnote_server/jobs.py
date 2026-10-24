"""转写任务队列：DB 为真源，单 worker 线程顺序消费。

一次只跑一个转写任务（本地引擎加载模型后顺序跑最快，也避免 GPU 抢占）。
崩溃恢复：启动时把遗留 running 复位回 queued。
"""
import threading
import time
import traceback

from . import db
from .db import now_ms

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
    """老库里已失败的记录 error 还是原始异常串：归类一遍（幂等，一次性）。
    无论能否归类都把原文落进 error_detail——既是控制台展开的详情，
    也标记"已处理过"，下次启动不再扫这批行。"""
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
        with db.conn() as c:
            c.execute("UPDATE records SET error=?, error_detail=? WHERE id=?",
                      (friendly or r["error"], raw, r["id"]))


def enqueue(record_id: str, engine_id: str) -> int:
    import sqlite3
    with db.conn() as c:
        # 同记录已有排队/进行中的任务：不重复入队（retry 连点、手动+自动叠加），
        # 只把引擎参数带上，返回已有 job。
        row = c.execute(
            "SELECT id, state FROM jobs WHERE record_id=? AND state IN ('queued','running')"
            " ORDER BY id DESC LIMIT 1", (record_id,)).fetchone()
        if row is not None:
            c.execute("UPDATE jobs SET engine=? WHERE id=?", (engine_id, row["id"]))
            if row["state"] == "queued":
                c.execute("UPDATE records SET status='queued', engine=?, error='', error_detail=''"
                          " WHERE id=?", (engine_id, record_id))
            else:
                c.execute("UPDATE records SET engine=? WHERE id=?", (engine_id, record_id))
            return row["id"]
        try:
            cur = c.execute(
                "INSERT INTO jobs(record_id, engine, state, created_at) VALUES(?,?,?,?)",
                (record_id, engine_id, "queued", now_ms()))
        except sqlite3.IntegrityError:
            # 两个请求并发穿过上面的 SELECT：idx_jobs_active 部分唯一索引兜底，
            # 已有任务插入成功的那条算数，这里改成复用它。
            row = c.execute(
                "SELECT id, state FROM jobs WHERE record_id=? AND state IN ('queued','running')"
                " ORDER BY id DESC LIMIT 1", (record_id,)).fetchone()
            if row is None:
                raise
            c.execute("UPDATE jobs SET engine=? WHERE id=?", (engine_id, row["id"]))
            if row["state"] == "queued":
                c.execute("UPDATE records SET status='queued', engine=?, error='', error_detail=''"
                          " WHERE id=?", (engine_id, record_id))
            return row["id"]
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
    if e is None or not e.available() or not engines.enabled(engine_id):
        # 默认引擎不可用或已被停用：记录停在 raw，控制台可手动换引擎重试
        return 0
    return enqueue(record_id, engine_id)


def cancel(job_id: int) -> bool:
    with db.conn() as c:
        cur = c.execute("UPDATE jobs SET state='cancel', finished_at=? WHERE id=? AND state='queued'",
                        (now_ms(), job_id))
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
    # 模型下载失败常被包成 LocalEntryNotFoundError（文本只有 "cannot find ... snapshot
    # folder"，会被下面的文件缺失规则误报），靠因果链里的 huggingface.co 抢先命中
    (("huggingface", "hf-mirror", "snapshot folder", "faster-whisper-", "model download"),
     "模型下载失败：访问 HuggingFace 超时或不可达（检查网络，或配置镜像后重试）"),
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
    """→ (friendly, detail)。friendly 为 None 表示没有已知归类（调用方回退展示原文）。

    匹配文本沿 __cause__/__context__ 拼整条因果链：下游库常把网络/模型错误
    包一层本地异常，只看最外层会误报（如 HF 下载超时被标成"文件缺失"）。"""
    parts, seen, t = [], set(), exc
    while t is not None and id(t) not in seen:
        seen.add(id(t))
        parts.append(str(t) or t.__class__.__name__)
        t = t.__cause__ or t.__context__
    chain = "\n-- caused by --\n".join(parts)
    raw = (str(exc).strip() or exc.__class__.__name__)
    low = chain.lower()
    for keys, msg in _ERROR_RULES:
        if any(k in low for k in keys):
            return msg, chain
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
                _finish(job, "fail", "任务异常，见服务端日志", traceback.format_exc()[:2000])
            except Exception:
                traceback.print_exc()


def _claim():
    with db.conn() as c:
        row = c.execute(
            "SELECT * FROM jobs WHERE state='queued' ORDER BY id LIMIT 1").fetchone()
        if row is None:
            return None
        cur = c.execute("UPDATE jobs SET state='running', started_at=? WHERE id=? AND state='queued'",
                        (now_ms(), row["id"]))
        if cur.rowcount == 0:
            return None  # SELECT 与 UPDATE 之间恰好被取消：让它保持 cancel，本轮不领
        c.execute("UPDATE records SET status='asr' WHERE id=?", (row["record_id"],))
        return row


def _run(job, engines):
    rid = job["record_id"]
    rec = db.record_row(rid)
    if rec is None:
        _finish(job, "fail", "记录不存在")
        return
    # 已有内容的重转写：新结果先攒在内存，成功后才整层替换——中途失败不清旧稿。
    # 首次转写照旧边转边落库（本来就没有内容可丢）。
    had_content = _has_content(rid)
    if not rec["audio_path"]:
        _fail(job, rid, had_content, "音频文件缺失")
        return
    e = engines.get(job["engine"])
    if e is None or not e.available():
        _fail(job, rid, had_content, "引擎不可用：%s" % job["engine"])
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

    # 增量落盘：引擎边转写边回调，节流 3 秒写一次机器层 + 导出镜像。
    # 手机端轮询 /sync/records?since= 即可看到"转写中"的场次内容实时增长。
    # 重转写（had_content）时跳过：增量不落库，控制台旧稿保持原样，成功后一次替换。
    last_flush = [0.0]

    def on_partial(segs_partial):
        if had_content or not segs_partial:
            return
        t_now = time.time()
        if t_now - last_flush[0] < 3.0:
            return
        last_flush[0] = t_now
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
        _fail(job, rid, had_content, friendly or ("转写失败：" + raw[:280]), raw[:2000])
        return

    # 旧机器结果先存档为历史版本（与新结果不同才存，相同不制造版本噪音），
    # 然后才整层替换——控制台详情页的「转写版本」页签读的就是这些存档。
    old = db.machine_text(rid)
    if old is not None:
        new_text = "\n".join(s["text"] for s in segs)
        if old["text"] != new_text:
            db.archive_version(rid, rec["engine"], rec["updated_at"], old["text"], old["nseg"])

    db.replace_machine_segments(rid, segs)
    dur = segs[-1]["e_ms"] if segs else rec["dur_ms"]
    db.update_record(rid, status="done", engine=e.id, dur_ms=dur or rec["dur_ms"],
                     error="", error_detail="")
    db.export_mirror(rid)
    _finish(job, "done", "")


def _has_content(rid) -> bool:
    with db.conn() as c:
        return c.execute(
            "SELECT COUNT(*) FROM segments WHERE record_id=?", (rid,)).fetchone()[0] > 0


def _fail(job, rid, had_content, msg, raw=""):
    """任务失败落账，统一收尾。已有旧稿的重转写失败不打断旧稿：记录保持 done、
    失败原因挂元信息（详情页照常显示逐句稿 + 错误 chip），任务页仍可重试；
    只有首次转写（没有旧稿可保）才把记录置 fail。"""
    if had_content:
        full = ("重转写失败：" + msg)[:300]
        db.update_record(rid, status="done", error=full, error_detail=raw[:2000])
        _finish(job, "fail", full, raw[:2000], keep_record=True)
    else:
        _finish(job, "fail", msg, raw[:2000])


def _finish(job, state, error, detail="", keep_record=False):
    with db.conn() as c:
        c.execute("UPDATE jobs SET state=?, error=?, finished_at=?, progress=? WHERE id=?",
                  (state, error, now_ms(),
                   1.0 if state == "done" else 0.0, job["id"]))
        if state == "fail" and not keep_record:
            c.execute("UPDATE records SET status='fail', error=?, error_detail=? WHERE id=?",
                      (error, detail, job["record_id"]))
    _last_progress.pop(job["id"], None)
