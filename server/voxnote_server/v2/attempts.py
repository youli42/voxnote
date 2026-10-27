"""v2 转写尝试与生效指针。

规则（见 docs/设计/架构设计.md §3.4）：
  - 尝试不可变：同一 (record, audio_sha256, source, engine, params_hash) 只建一次；
  - 多来源/多引擎/重转写 = 新尝试，不覆盖旧尝试；
  - 记录只有 `active_attempt_id` 一个指针，切指针不改任何尝试内容；
  - 指针切换与尝试创建都发 change。
"""
import uuid

from . import db, service


def public(row) -> dict:
    r = dict(row)
    return {
        "id": r["id"],
        "record_id": r["record_id"],
        "rev": r["rev"],
        "audio_sha256": r["audio_sha256"],
        "source": r["source"],
        "engine": r["engine"],
        "model": r["model"],
        "params_hash": r["params_hash"],
        "quality_tier": r["quality_tier"],
        "duration_ms": r["duration_ms"],
        "created_at": r["created_at"],
    }


def segments(conn, attempt_id: str) -> list:
    with db.lock:
        rows = conn.execute(
            "SELECT idx, t_ms, e_ms, text, spk FROM attempt_segments"
            " WHERE attempt_id=? ORDER BY idx", (attempt_id,)).fetchall()
    return [dict(r) for r in rows]


def get(conn, attempt_id: str):
    with db.lock:
        row = conn.execute(
            "SELECT * FROM transcript_attempts WHERE id=?", (attempt_id,)).fetchone()
        if row is None:
            return None
        out = public(row)
        out["segments"] = segments(conn, attempt_id)
        return out


def list_for_record(conn, record_id: str) -> list:
    with db.lock:
        rows = conn.execute(
            "SELECT * FROM transcript_attempts WHERE record_id=?"
            " ORDER BY created_at, id", (record_id,)).fetchall()
    return [public(r) for r in rows]


def _validate(data: dict):
    sha = str(data.get("audio_sha256") or "").strip()
    source = str(data.get("source") or "").strip()
    engine = str(data.get("engine") or "").strip()
    segs = data.get("segments") or []
    if not sha:
        raise ValueError("audio_sha256 必填")
    if not source:
        raise ValueError("source 必填")
    if not engine:
        raise ValueError("engine 必填")
    if not isinstance(segs, list):
        raise ValueError("segments 必须是数组")
    return sha, source, engine, segs


def create(conn, record_id: str, data: dict):
    sha, source, engine, segs = _validate(data)
    model = str(data.get("model") or "")
    params_hash = str(data.get("params_hash") or "")
    quality = int(data.get("quality_tier") or 2)
    duration = int(data.get("duration_ms") or 0)

    with db.lock:
        rec = service.get_record(conn, record_id)
        if rec is None:
            return None
        existing = conn.execute(
            "SELECT * FROM transcript_attempts WHERE record_id=? AND audio_sha256=?"
            " AND source=? AND engine=? AND params_hash=?",
            (record_id, sha, source, engine, params_hash)).fetchone()
        if existing is not None:
            out = public(existing)
            out["duplicate"] = True
            return out

        aid = str(data.get("id") or "").strip() or uuid.uuid4().hex
        now = service.now_ms()
        with conn:
            conn.execute(
                """INSERT INTO transcript_attempts(
                     id, record_id, audio_sha256, source, engine, model,
                     params_hash, quality_tier, duration_ms, created_at)
                   VALUES(?,?,?,?,?,?,?,?,?,?)""",
                (aid, record_id, sha, source, engine, model,
                 params_hash, quality, duration, now),
            )
            for i, seg in enumerate(segs):
                conn.execute(
                    "INSERT INTO attempt_segments(attempt_id, idx, t_ms, e_ms, text, spk)"
                    " VALUES(?,?,?,?,?,?)",
                    (aid, i, int(seg.get("t_ms") or 0), int(seg.get("e_ms") or 0),
                     str(seg.get("text") or ""), int(seg.get("spk", -1))),
                )
            service.append_change(conn, "attempt", aid)

            activate = data.get("activate")
            if activate is None:
                activate = rec.get("active_attempt_id") is None
            if activate and rec.get("active_attempt_id") != aid:
                rev = service.append_change(conn, "record", record_id)
                conn.execute(
                    "UPDATE records SET active_attempt_id=?, rev=?, updated_at=? WHERE id=?",
                    (aid, rev, now, record_id))
        out = get(conn, aid)
        out["duplicate"] = False
        return out


def set_active(conn, record_id: str, attempt_id: str, pinned=None):
    with db.lock:
        rec = service.get_record(conn, record_id)
        if rec is None:
            return None
        att = conn.execute(
            "SELECT * FROM transcript_attempts WHERE id=? AND record_id=?",
            (attempt_id, record_id)).fetchone()
        if att is None:
            raise ValueError("尝试不存在或不属于该记录")
        if rec["active_attempt_id"] == attempt_id and (
                pinned is None or int(rec["active_pinned"]) == int(bool(pinned))):
            return service.record_summary(rec)

        now = service.now_ms()
        with conn:
            rev = service.append_change(conn, "record", record_id)
            if pinned is None:
                conn.execute(
                    "UPDATE records SET active_attempt_id=?, rev=?, updated_at=? WHERE id=?",
                    (attempt_id, rev, now, record_id))
            else:
                conn.execute(
                    "UPDATE records SET active_attempt_id=?, active_pinned=?, rev=?, updated_at=?"
                    " WHERE id=?",
                    (attempt_id, int(bool(pinned)), rev, now, record_id))
        return service.record_summary(service.get_record(conn, record_id))


def transcript(conn, record_id: str):
    with db.lock:
        rec = service.get_record(conn, record_id)
        if rec is None:
            return None
        aid = rec.get("active_attempt_id")
        att = get(conn, aid) if aid else None
        return {
            "record": service.record_summary(rec),
            "attempt": att,
            "segments": att["segments"] if att else [],
        }


def get_for_record(conn, record_id: str, attempt_id: str):
    """尝试详情（控制台补充接口 §5）：只暴露契约字段 + segments + is_active。"""
    with db.lock:
        row = conn.execute(
            "SELECT * FROM transcript_attempts WHERE id=? AND record_id=?",
            (attempt_id, record_id)).fetchone()
        if row is None:
            return None
        rec = conn.execute(
            "SELECT active_attempt_id FROM records WHERE id=?",
            (record_id,)).fetchone()
    r = dict(row)
    return {
        "attempt": {
            "id": r["id"],
            "engine": r["engine"],
            "model": r["model"],
            "quality_tier": r["quality_tier"],
            "duration_ms": r["duration_ms"],
            "created_at": r["created_at"],
        },
        "segments": segments(conn, attempt_id),
        "is_active": bool(rec and rec["active_attempt_id"] == attempt_id),
    }


# diff 的 change 取值（按 idx 对齐，缺边时对应 text 为空）
CHANGE_SAME = "same"
CHANGE_CHANGED = "changed"
CHANGE_ADDED = "added"
CHANGE_REMOVED = "removed"


def diff(conn, record_id: str, from_id: str, to_id: str):
    """两次尝试逐句对比；尝试不存在/不属于该记录返回 None。"""
    with db.lock:
        rec = service.get_record(conn, record_id)
        if rec is None:
            return None
        for aid in (from_id, to_id):
            found = conn.execute(
                "SELECT 1 FROM transcript_attempts WHERE id=? AND record_id=?",
                (aid, record_id)).fetchone()
            if found is None:
                return None
        from_segs = {int(r["idx"]): dict(r) for r in conn.execute(
            "SELECT idx, t_ms, e_ms, text FROM attempt_segments"
            " WHERE attempt_id=?", (from_id,)).fetchall()}
        to_segs = {int(r["idx"]): dict(r) for r in conn.execute(
            "SELECT idx, t_ms, e_ms, text FROM attempt_segments"
            " WHERE attempt_id=?", (to_id,)).fetchall()}
        active = rec.get("active_attempt_id")

    segments_out = []
    for idx in sorted(set(from_segs) | set(to_segs)):
        f = from_segs.get(idx)
        t = to_segs.get(idx)
        if f is not None and t is not None:
            change = CHANGE_SAME if (f["text"] or "") == (t["text"] or "") else CHANGE_CHANGED
            base = f
        elif f is not None:
            change, base = CHANGE_REMOVED, f
        else:
            change, base = CHANGE_ADDED, t
        segments_out.append({
            "idx": idx,
            "t_ms": int(base["t_ms"] or 0),
            "e_ms": int(base["e_ms"] or 0),
            "from_text": (f["text"] if f is not None else "") or "",
            "to_text": (t["text"] if t is not None else "") or "",
            "change": change,
        })
    return {"from": from_id, "to": to_id, "active": active,
            "segments": segments_out}
