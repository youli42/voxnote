"""v2 记录服务：记录读写与变更流。

约定：
  - 所有会改变记录可见状态的写操作，必须在**同一事务**里追加一条 changes；
  - records.rev 永远等于最后一次变更的 changes.rev；
  - 删除是墓碑（deleted_at），不物理删；
  - 这里只做记录与 change；尝试/任务/编辑的 change 在各自服务里补（W1-2 只做记录）。
"""
import time

from . import db as _db

# update_record 允许改的字段（whitelist，防止调用方乱写）
UPDATABLE = {
    "title", "cat", "mode", "src", "device_id", "recorded_at", "dur_ms", "bytes",
    "audio_path", "audio_name", "audio_sha256", "status", "engine",
    "error_code", "error_hint", "active_attempt_id", "active_pinned", "marks",
}  # fmt: skip


def now_ms() -> int:
    return int(time.time() * 1000)


def append_change(conn, kind: str, entity_id: str) -> int:
    cur = conn.execute(
        "INSERT INTO changes(kind, entity_id, created_at) VALUES(?,?,?)",
        (kind, entity_id, now_ms()),
    )
    return int(cur.lastrowid)


def get_record(conn, record_id: str):
    with _db.lock:
        row = conn.execute("SELECT * FROM records WHERE id=?", (record_id,)).fetchone()
        return dict(row) if row else None


def record_summary(row) -> dict:
    r = dict(row)
    return {
        "id": r["id"],
        "rev": r["rev"],
        "deleted": r.get("deleted_at") is not None,
        "deleted_at": r.get("deleted_at"),
        "title": r.get("title", ""),
        "cat": r.get("cat"),
        "marks": r.get("marks", "[]"),
        "src": r.get("src", ""),
        "device_id": r.get("device_id", ""),
        "recorded_at": r.get("recorded_at", 0),
        "dur_ms": r.get("dur_ms", 0),
        "status": r.get("status", ""),
        "active_attempt_id": r.get("active_attempt_id"),
        "active_pinned": r.get("active_pinned", 0),
        "audio_sha256": r.get("audio_sha256"),
        "error_code": r.get("error_code", ""),
        "error_hint": r.get("error_hint", ""),
        "updated_at": r.get("updated_at", 0),
    }


def create_record(conn, data: dict) -> dict:
    """插入记录 + 一条 change；返回最新记录。id / recorded_at 必填。"""
    rid = str(data.get("id") or "").strip()
    if not rid:
        raise ValueError("create_record: id 必填")
    if "recorded_at" not in data:
        raise ValueError("create_record: recorded_at 必填")

    now = now_ms()
    with _db.lock, conn:
        rev = append_change(conn, "record", rid)
        conn.execute(
            """INSERT INTO records(
                 id, rev, legacy_id, deleted_at, title, cat, mode, src, device_id,
                 marks, recorded_at, dur_ms, bytes, audio_path, audio_name,
                 audio_sha256, status, engine, error_code, error_hint,
                 active_attempt_id, active_pinned, created_at, updated_at)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                rid, rev, data.get("legacy_id"), None, data.get("title", ""),
                data.get("cat"), data.get("mode", ""), data.get("src", "app"),
                data.get("device_id", ""), data.get("marks", "[]"),
                int(data["recorded_at"]), int(data.get("dur_ms", 0)),
                int(data.get("bytes", 0)), data.get("audio_path"),
                data.get("audio_name", ""), data.get("audio_sha256"),
                data.get("status", "uploaded"), data.get("engine", ""),
                data.get("error_code", ""), data.get("error_hint", ""),
                data.get("active_attempt_id"), int(data.get("active_pinned", 0)),
                now, now,
            ),
        )
    return get_record(conn, rid)


def update_record(conn, record_id: str, fields: dict):
    """更新白名单字段 + 一条 change；返回最新记录。未知字段报错。"""
    unknown = set(fields) - UPDATABLE
    if unknown:
        raise ValueError("update_record: 不允许修改字段 %s" % sorted(unknown))
    if not fields:
        return get_record(conn, record_id)

    now = now_ms()
    sql = "UPDATE records SET " + ", ".join("%s=?" % k for k in fields)
    sql += ", rev=?, updated_at=? WHERE id=?"
    with _db.lock:
        if get_record(conn, record_id) is None:
            return None
        with conn:
            rev = append_change(conn, "record", record_id)
            conn.execute(sql, list(fields.values()) + [rev, now, record_id])
    return get_record(conn, record_id)


def tombstone_record(conn, record_id: str):
    """删除 = 墓碑 + change；不物理删文件。已删除则原样返回。"""
    with _db.lock:
        current = get_record(conn, record_id)
        if current is None:
            return None
        if current.get("deleted_at") is not None:
            return current

        now = now_ms()
        with conn:
            rev = append_change(conn, "record", record_id)
            conn.execute(
                "UPDATE records SET deleted_at=?, rev=?, updated_at=? WHERE id=?",
                (now, rev, now, record_id),
            )
        return get_record(conn, record_id)


def list_records(conn, limit: int = 50, offset: int = 0,
                 include_deleted: bool = False) -> list:
    limit = max(1, min(int(limit), 500))
    offset = max(0, int(offset))
    where = "" if include_deleted else " WHERE deleted_at IS NULL"
    with _db.lock:
        rows = conn.execute(
            "SELECT * FROM records" + where +
            " ORDER BY recorded_at DESC, id DESC LIMIT ? OFFSET ?",
            (limit, offset),
        ).fetchall()
    return [record_summary(r) for r in rows]


def changes_since(conn, cursor: int = 0, limit: int = 200) -> dict:
    """按 rev 游标取变更；记录类变更附带最新记录摘要与 deleted 标记。"""
    cursor = max(0, int(cursor))
    limit = max(1, min(int(limit), 500))
    with _db.lock:
        rows = conn.execute(
            "SELECT rev, kind, entity_id, created_at FROM changes"
            " WHERE rev > ? ORDER BY rev LIMIT ?",
            (cursor, limit + 1),
        ).fetchall()
        has_more = len(rows) > limit
        rows = rows[:limit]

        out = []
        for r in rows:
            item = {
                "rev": r["rev"],
                "kind": r["kind"],
                "entity_id": r["entity_id"],
                "created_at": r["created_at"],
            }
            if r["kind"] == "record":
                rec = conn.execute(
                    "SELECT * FROM records WHERE id=?", (r["entity_id"],)).fetchone()
                if rec is None:
                    item["deleted"] = True
                else:
                    item["record"] = record_summary(rec)
            out.append(item)

    next_cursor = rows[-1]["rev"] if rows else cursor
    return {"changes": out, "next_cursor": next_cursor, "has_more": has_more}
