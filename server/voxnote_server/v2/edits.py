"""v2 人工修订与生效稿合成。

规则（见 docs/设计/架构设计.md §3.5）：
  - edits 只追加（append-only），每次修订一条，带时间锚；
  - 任意重转写/切尝试都不改 edits；
  - 生效稿 = 生效尝试的句 + 与该句时间重叠的最新修订覆盖；
  - 修订本身发 `edit` change，record 的 sync_hash 因 edits_rev 变化而变化。
"""
import uuid

from . import attempts, db, service


def public(row) -> dict:
    r = dict(row)
    return {
        "id": r["id"],
        "record_id": r["record_id"],
        "rev": r["rev"],
        "anchor_t_ms": r["anchor_t_ms"],
        "anchor_e_ms": r["anchor_e_ms"],
        "original_hash": r["original_hash"],
        "text": r["text"],
        "spk": r["spk"],
        "deleted": bool(r["deleted"]),
        "author": r["author"],
        "base_attempt_id": r["base_attempt_id"],
        "edited_at": r["edited_at"],
    }


def get(conn, edit_id: str):
    with db.lock:
        row = conn.execute("SELECT * FROM edits WHERE id=?", (edit_id,)).fetchone()
        return public(row) if row else None


def list_for_record(conn, record_id: str) -> list:
    with db.lock:
        rows = conn.execute(
            "SELECT * FROM edits WHERE record_id=? ORDER BY anchor_t_ms, rev, id",
            (record_id,)).fetchall()
    return [public(r) for r in rows]


def create(conn, record_id: str, data: dict):
    try:
        t0 = int(data.get("anchor_t_ms") or 0)
        t1 = int(data.get("anchor_e_ms") or 0)
    except (TypeError, ValueError):
        raise ValueError("时间锚必须是整数毫秒")
    if t0 < 0 or t1 < 0:
        raise ValueError("时间锚不能为负")
    if t1 < t0:
        raise ValueError("anchor_e_ms 不能小于 anchor_t_ms")
    deleted = bool(data.get("deleted"))
    text = data.get("text")
    if not deleted and text is None:
        raise ValueError("非删除修订必须提供 text")

    with db.lock:
        if service.get_record(conn, record_id) is None:
            return None
        eid = str(data.get("id") or "").strip() or uuid.uuid4().hex
        now = service.now_ms()
        with conn:
            rev = service.append_change(conn, "edit", eid)
            conn.execute(
                """INSERT INTO edits(
                     id, record_id, rev, anchor_t_ms, anchor_e_ms, original_hash,
                     text, spk, deleted, author, base_attempt_id, edited_at)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                (eid, record_id, rev, t0, t1,
                 str(data.get("original_hash") or ""), text,
                 None if data.get("spk") is None else int(data["spk"]),
                 1 if deleted else 0, str(data.get("author") or ""),
                 data.get("base_attempt_id"), now),
            )
    return get(conn, eid)


def _overlaps(segment: dict, edit: dict) -> bool:
    if edit["anchor_t_ms"] == edit["anchor_e_ms"]:
        return segment["t_ms"] <= edit["anchor_t_ms"] < segment["e_ms"]
    return edit["anchor_t_ms"] < segment["e_ms"] and edit["anchor_e_ms"] > segment["t_ms"]


def apply(segments: list, edits: list) -> list:
    """把修订套到句上：同句命中多条时，取最新一条（rev 最大）。"""
    out = []
    for seg in segments:
        seg = dict(seg)
        hits = [e for e in edits if _overlaps(seg, e)]
        if hits:
            latest = max(hits, key=lambda e: (e["rev"], e["edited_at"], e["id"]))
            seg["edited"] = True
            seg["edit_id"] = latest["id"]
            if latest["deleted"]:
                seg["deleted"] = True
                seg["text"] = ""
            else:
                seg["text"] = latest["text"] if latest["text"] is not None else ""
                if latest["spk"] is not None:
                    seg["spk"] = latest["spk"]
        out.append(seg)
    return out


def transcript(conn, record_id: str):
    """生效稿：生效尝试 + 修订覆盖；没有尝试时返回空句。"""
    base = attempts.transcript(conn, record_id)
    if base is None:
        return None
    edits = list_for_record(conn, record_id)
    base["segments"] = apply(base["segments"], edits)
    base["edits"] = edits
    return base


def delete(conn, record_id: str, edit_id: str) -> bool:
    """删除一条修订（恢复机器原文）；返回是否删到。

    删除后重新套用剩余修订，修订历史本身仍受 changes 流约束（追加一条 edit change），
    但不再保留被删修订的内容。
    """
    with db.lock:
        row = conn.execute(
            "SELECT id FROM edits WHERE id=? AND record_id=?",
            (edit_id, record_id)).fetchone()
        if row is None:
            return False
        with conn:
            service.append_change(conn, "edit", edit_id)
            conn.execute("DELETE FROM edits WHERE id=?", (edit_id,))
    return True
