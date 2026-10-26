"""v2 清单与内容哈希：用于设备重连后的全量对账。

`sync_hash` 是**规范化后的记录哈希**，只包含跨设备一致的内容：
元数据（标题/标签/标记/模式/状态/时长/音频 sha）+ 生效尝试指针 + 修订版本 + deleted。
不含本地字段（路径、设备、bytes 等）。设备只需比较 id/rev/hash 就能决定"要不要拉"。
"""
import hashlib
import json

from . import db


def _canonical(payload: dict) -> str:
    return json.dumps(payload, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"))


def record_sync_hash(row) -> str:
    """row 需含 records 字段与 edits_rev（无则由调用方补 0）。"""
    r = dict(row)
    payload = {
        "v": 1,
        "id": r["id"],
        "deleted": r.get("deleted_at") is not None,
        "title": r.get("title", ""),
        "cat": r.get("cat"),
        "marks": r.get("marks", "[]"),
        "mode": r.get("mode", ""),
        "src": r.get("src", ""),
        "recorded_at": r.get("recorded_at", 0),
        "dur_ms": r.get("dur_ms", 0),
        "audio_sha256": r.get("audio_sha256"),
        "active_attempt_id": r.get("active_attempt_id"),
        "active_pinned": r.get("active_pinned", 0),
        "edits_rev": int(r.get("edits_rev") or 0),
        "status": r.get("status", ""),
        "error_code": r.get("error_code", ""),
        "error_hint": r.get("error_hint", ""),
    }
    return hashlib.sha256(_canonical(payload).encode("utf-8")).hexdigest()


def build(conn, cursor: str = "", limit: int = 500) -> dict:
    """按 id 字典序分页返回清单；包含墓碑（deleted=true）。"""
    cursor = str(cursor or "")
    limit = max(1, min(int(limit), 1000))
    with db.lock:
        rows = conn.execute(
            """SELECT r.*, COALESCE((SELECT MAX(e.rev) FROM edits e
                                     WHERE e.record_id = r.id), 0) AS edits_rev
               FROM records r WHERE r.id > ? ORDER BY r.id LIMIT ?""",
            (cursor, limit + 1),
        ).fetchall()
        has_more = len(rows) > limit
        rows = rows[:limit]
        items = [{
            "id": r["id"],
            "rev": r["rev"],
            "deleted": r["deleted_at"] is not None,
            "sync_hash": record_sync_hash(r),
        } for r in rows]

    next_cursor = items[-1]["id"] if items else cursor
    return {"items": items, "next_cursor": next_cursor, "has_more": has_more}
