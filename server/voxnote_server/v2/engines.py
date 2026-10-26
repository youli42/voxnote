"""v2 引擎配置与 OpenAI 兼容转写适配器。

协议只依赖最小公共集：
  POST <url>/v1/audio/transcriptions  (multipart: file, model, language, response_format=verbose_json)
  返回：{ text, segments?: [{start,end,text}], duration? }

v1 不做自动切换：由调用方指定 engine_id（不指定就取第一个启用引擎）。
"""
import hashlib
import json
import time
import uuid
from pathlib import Path

import httpx

from . import attempts, db, jobs, service


def public(row, with_secret: bool = False) -> dict:
    r = dict(row)
    return {
        "id": r["id"],
        "name": r["name"],
        "kind": r["kind"],
        "url": r["url"],
        "model": r["model"],
        "quality_tier": r["quality_tier"],
        "slots": r["slots"],
        "timeout_ms": r["timeout_ms"],
        "enabled": bool(r["enabled"]),
        "has_token": bool(r.get("token")),
        "token": r.get("token", "") if with_secret else "",
    }


def list_all(conn) -> list:
    with db.lock:
        rows = conn.execute("SELECT * FROM engines ORDER BY quality_tier DESC, name").fetchall()
    return [public(r) for r in rows]


def get(conn, engine_id: str):
    with db.lock:
        row = conn.execute("SELECT * FROM engines WHERE id=?", (engine_id,)).fetchone()
        return dict(row) if row else None


def create(conn, data: dict) -> dict:
    url = str(data.get("url") or "").strip()
    if not url:
        raise ValueError("url 必填")
    eid = str(data.get("id") or "").strip() or uuid.uuid4().hex
    with db.lock, conn:
        conn.execute(
            """INSERT INTO engines(id, kind, name, url, token, model, quality_tier,
                                   slots, timeout_ms, enabled)
               VALUES(?,?,?,?,?,?,?,?,?,?)""",
            (eid, str(data.get("kind") or "openai"), str(data.get("name") or eid),
             url.rstrip("/"), str(data.get("token") or ""), str(data.get("model") or ""),
             int(data.get("quality_tier") or 3), int(data.get("slots") or 1),
             int(data.get("timeout_ms") or 600000), 1 if data.get("enabled", True) else 0),
        )
    return public(get(conn, eid))


UPDATABLE = {"name", "url", "token", "model", "quality_tier", "slots",
             "timeout_ms", "enabled"}


def update(conn, engine_id: str, fields: dict):
    unknown = set(fields) - UPDATABLE
    if unknown:
        raise ValueError("不允许修改字段 %s" % sorted(unknown))
    if get(conn, engine_id) is None:
        return None
    if not fields:
        return public(get(conn, engine_id))
    sql = "UPDATE engines SET " + ", ".join("%s=?" % k for k in fields) + " WHERE id=?"
    values = [1 if (k == "enabled" and v) else 0 if k == "enabled" else v
              for k, v in fields.items()]
    with db.lock, conn:
        conn.execute(sql, values + [engine_id])
    return public(get(conn, engine_id))


def delete(conn, engine_id: str) -> bool:
    with db.lock, conn:
        cur = conn.execute("DELETE FROM engines WHERE id=?", (engine_id,))
        return cur.rowcount > 0


def pick(conn, engine_id=None):
    if engine_id:
        eng = get(conn, engine_id)
        if eng is None or not eng.get("enabled"):
            raise ValueError("引擎不存在或未启用")
        return eng
    with db.lock:
        row = conn.execute(
            "SELECT * FROM engines WHERE enabled=1 ORDER BY quality_tier DESC, name LIMIT 1"
        ).fetchone()
    if row is None:
        raise ValueError("没有可用引擎，请先在设置里添加")
    return dict(row)


def probe(engine: dict, timeout: float = 3.0) -> dict:
    """轻量健康探测：/health 或 /v1/models 任一 2xx/3xx 即视为就绪。"""
    base = engine["url"].rstrip("/")
    headers = {}
    if engine.get("token"):
        headers["Authorization"] = "Bearer " + engine["token"]
    last = ""
    for url in (base + "/health", base + "/v1/models"):
        t0 = time.time()
        try:
            with httpx.Client(timeout=timeout) as client:
                r = client.get(url, headers=headers)
            ms = int((time.time() - t0) * 1000)
            if r.status_code < 400:
                return {"ready": True, "url": url, "latency_ms": ms}
            last = "HTTP %d" % r.status_code
        except Exception as e:
            last = str(e)
    return {"ready": False, "detail": last}


def call_engine(engine: dict, audio_path: str, language: str = "zh") -> dict:
    """真实调用 OpenAI 兼容转写接口；独立成函数便于测试替换。"""
    url = engine["url"].rstrip("/")
    if not url.endswith("/v1/audio/transcriptions"):
        url += "/v1/audio/transcriptions"
    headers = {}
    if engine.get("token"):
        headers["Authorization"] = "Bearer " + engine["token"]
    data = {
        "model": engine.get("model") or "whisper-1",
        "language": language,
        "response_format": "verbose_json",
    }
    timeout = max(1, int(engine.get("timeout_ms") or 600000)) / 1000.0
    with open(audio_path, "rb") as f:
        files = {"file": (Path(audio_path).name, f, "application/octet-stream")}
        with httpx.Client(timeout=timeout) as client:
            r = client.post(url, headers=headers, data=data, files=files)
    if r.status_code >= 400:
        raise RuntimeError("引擎返回 HTTP %d：%s" % (r.status_code, r.text[:300]))
    try:
        body = r.json()
    except Exception:
        raise RuntimeError("引擎返回的不是 JSON")
    if not isinstance(body, dict):
        raise RuntimeError("引擎返回格式不对")
    return body


def _segments(body: dict, duration_ms: int) -> list:
    segs = body.get("segments")
    if isinstance(segs, list) and segs:
        out = []
        for s in segs:
            out.append({
                "t_ms": int(float(s.get("start") or 0) * 1000),
                "e_ms": int(float(s.get("end") or 0) * 1000),
                "text": str(s.get("text") or ""),
            })
        return out
    text = str(body.get("text") or "")
    return [{"t_ms": 0, "e_ms": max(duration_ms, 1000), "text": text}] if text else []


def transcribe(conn, record_id: str, engine_id=None, language: str = "zh"):
    """同步转写一条记录：建 job → 调引擎 → 建 attempt → 更新记录状态。"""
    rec = service.get_record(conn, record_id)
    if rec is None:
        return None
    engine = pick(conn, engine_id)
    audio_path = rec.get("audio_path")
    if not audio_path or not Path(audio_path).is_file():
        raise ValueError("音频文件不存在，无法转写")

    sha = rec.get("audio_sha256")
    if not sha:
        with open(audio_path, "rb") as f:
            sha = hashlib.sha256(f.read()).hexdigest()

    job_id = jobs.create(conn, record_id, engine["name"] or engine["id"])
    jobs.start(conn, job_id)
    try:
        body = call_engine(engine, audio_path, language=language)
        duration_ms = int(float(body.get("duration") or 0) * 1000) or int(rec.get("dur_ms") or 0)
        segs = _segments(body, duration_ms)
        att = attempts.create(conn, record_id, {
            "audio_sha256": sha,
            "source": "hub",
            "engine": engine["name"] or engine["id"],
            "model": engine.get("model") or "",
            "quality_tier": int(engine.get("quality_tier") or 3),
            "duration_ms": duration_ms,
            "segments": segs,
            "activate": True,
        })
        jobs.finish(conn, job_id, "done")
        service.update_record(conn, record_id, {
            "status": "done", "engine": engine["name"] or engine["id"],
            "error_code": "", "error_hint": "",
        })
        return {"job_id": job_id, "attempt": att, "engine": public(engine)}
    except Exception as e:
        jobs.finish(conn, job_id, "failed", "engine_error", str(e)[:500])
        service.update_record(conn, record_id, {
            "status": "failed", "error_code": "engine_error",
            "error_hint": str(e)[:500],
        })
        raise
