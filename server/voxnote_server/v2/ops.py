"""v2 批量操作（ops）：客户端 outbox 的落点。

支持的操作：
  - `update_record`：改名/标签/标记/状态；带 `base_rev` 做冲突检测；
  - `tombstone_record`：删除（墓碑）；带 `base_rev` 做冲突检测。

幂等：`op_id` 已处理过 → 直接返回上次结果（`duplicate=true`），不重复执行。
"""
import json
import uuid

from . import conflicts, db, service


def _stored(conn, op_id: str):
    return conn.execute(
        "SELECT status, result FROM ops WHERE op_id=?", (op_id,)).fetchone()


def _remember(conn, op_id: str, device_id: str, result: dict):
    with conn:
        conn.execute(
            "INSERT OR REPLACE INTO ops(op_id, device_id, status, result, created_at)"
            " VALUES(?,?,?,?,?)",
            (op_id, device_id or "", result.get("status", "error"),
             json.dumps(result, ensure_ascii=False), service.now_ms()),
        )


def _invalid(op_id, code, message):
    return {"op_id": op_id, "status": "error", "code": code, "message": message}


def apply(conn, device_id: str, ops: list) -> dict:
    if not isinstance(ops, list):
        raise ValueError("ops 必须是数组")
    if len(ops) > 200:
        raise ValueError("一次最多 200 个 op")

    results = []
    for op in ops:
        if not isinstance(op, dict):
            results.append(_invalid("", "bad_request", "op 必须是对象"))
            continue
        op_id = str(op.get("op_id") or "").strip() or uuid.uuid4().hex
        with db.lock:
            row = _stored(conn, op_id)
            if row is not None:
                result = json.loads(row["result"] or "{}")
                result.update({"op_id": op_id, "duplicate": True})
                results.append(result)
                continue

            kind = op.get("kind")
            rid = str(op.get("record_id") or "").strip()
            base_rev = op.get("base_rev")
            result = _invalid(op_id, "bad_request", "未知操作：%s" % kind)

            if kind in ("update_record", "tombstone_record"):
                current = service.get_record(conn, rid) if rid else None
                if current is None:
                    result = _invalid(op_id, "not_found", "记录不存在：%s" % rid)
                elif base_rev is not None and int(base_rev) != int(current["rev"]):
                    local = {"kind": ("delete_conflict" if kind == "tombstone_record"
                                      else "update_conflict"),
                             "base_rev": int(base_rev)}
                    if kind == "update_record":
                        local["fields"] = op.get("fields") or {}
                    c = conflicts.create(
                        conn, rid, local["kind"], int(base_rev), local,
                        service.record_summary(current))
                    result = {"op_id": op_id, "status": "conflict",
                              "conflict_id": c["id"], "record": c["record"]}
                elif kind == "tombstone_record":
                    rec = service.tombstone_record(conn, rid)
                    result = {"op_id": op_id, "status": "applied",
                              "record": service.record_summary(rec)}
                else:
                    try:
                        rec = service.update_record(conn, rid, op.get("fields") or {})
                    except ValueError as e:
                        result = _invalid(op_id, "invalid_fields", str(e))
                    else:
                        result = {"op_id": op_id, "status": "applied",
                                  "record": service.record_summary(rec)}
            _remember(conn, op_id, device_id, result)
            results.append(result)

    applied = sum(1 for r in results if r.get("status") == "applied")
    return {"results": results, "applied": applied}
