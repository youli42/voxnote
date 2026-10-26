"""v2 冲突：检测、落库、列出、用户选择解决。

设计（见 docs/设计/架构设计.md §5.4）：
  - 不静默覆盖：本地与服务器基于同一 base_rev 各改一份时，进 conflicts；
  - 两边版本都保留在 conflict 行里，解决结果再写成新修订；
  - 解决动作本身也发一条 `conflict` change，客户端据此刷新。
"""
import json
import uuid

from . import db, service


def _row(conn, conflict_id: str):
    row = conn.execute("SELECT * FROM conflicts WHERE id=?", (conflict_id,)).fetchone()
    return dict(row) if row else None


def public(conn, row) -> dict:
    c = dict(row)
    rec = service.get_record(conn, c["record_id"])
    return {
        "id": c["id"],
        "record_id": c["record_id"],
        "kind": c["kind"],
        "base_rev": c["base_rev"],
        "local": json.loads(c["local_payload"] or "{}"),
        "server": json.loads(c["server_payload"] or "{}"),
        "record": service.record_summary(rec) if rec else None,
        "created_at": c["created_at"],
        "resolved_at": c["resolved_at"],
        "resolution": c["resolution"],
    }


def create(conn, record_id: str, kind: str, base_rev: int,
           local_payload: dict, server_payload: dict) -> dict:
    cid = uuid.uuid4().hex
    now = service.now_ms()
    with db.lock, conn:
        conn.execute(
            "INSERT INTO conflicts(id, record_id, kind, base_rev, local_payload,"
            " server_payload, created_at) VALUES(?,?,?,?,?,?,?)",
            (cid, record_id, kind, int(base_rev),
             json.dumps(local_payload, ensure_ascii=False),
             json.dumps(server_payload, ensure_ascii=False), now),
        )
        service.append_change(conn, "conflict", cid)
    return public(conn, _row(conn, cid))


def list_open(conn, limit: int = 100) -> list:
    limit = max(1, min(int(limit), 500))
    with db.lock:
        rows = conn.execute(
            "SELECT * FROM conflicts WHERE resolved_at IS NULL"
            " ORDER BY created_at, id LIMIT ?", (limit,)).fetchall()
        return [public(conn, r) for r in rows]


def get(conn, conflict_id: str):
    with db.lock:
        row = _row(conn, conflict_id)
        return public(conn, row) if row else None


def resolve(conn, conflict_id: str, resolution: str, fields: dict = None):
    """resolution: local | server | merge（merge 时 fields 必填）。"""
    if resolution not in ("local", "server", "merge"):
        raise ValueError("resolution 必须是 local/server/merge")
    with db.lock:
        row = _row(conn, conflict_id)
        if row is None:
            return None
        if row["resolved_at"] is not None:
            return public(conn, row)

        local = json.loads(row["local_payload"] or "{}")
        if resolution == "local":
            if local.get("kind") == "delete_conflict":
                service.tombstone_record(conn, row["record_id"])
            else:
                service.update_record(conn, row["record_id"], local.get("fields") or {})
        elif resolution == "merge":
            if not fields:
                raise ValueError("merge 必须提供 fields")
            service.update_record(conn, row["record_id"], fields)
        # server: 保持服务器版本，不改记录

        now = service.now_ms()
        with conn:
            conn.execute(
                "UPDATE conflicts SET resolved_at=?, resolution=? WHERE id=?",
                (now, resolution, conflict_id))
            service.append_change(conn, "conflict", conflict_id)
        return public(conn, _row(conn, conflict_id))
