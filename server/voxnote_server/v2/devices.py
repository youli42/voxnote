"""v2 设备与配对令牌管理。

- 设备列表：直接读 `devices` 表（空表返回空数组）；
- 令牌重置：生成新的 `settings.sync_token`；鉴权每次实时读库，旧令牌立即失效。
"""
import secrets

from . import db

# 控制台设备列表字段（见控制台补充接口 §6；last_cursor 属同步内部状态不对外）
DEVICE_FIELDS = (
    "id", "name", "kind", "app_version", "last_seen_at", "status", "created_at",
)


def list_all(conn) -> list:
    with db.lock:
        rows = conn.execute(
            "SELECT * FROM devices ORDER BY COALESCE(last_seen_at, 0) DESC,"
            " created_at DESC, id").fetchall()
    out = []
    for r in rows:
        d = dict(r)
        item = {k: d.get(k) for k in DEVICE_FIELDS}
        item["last_seen_at"] = int(item.get("last_seen_at") or 0)
        item["created_at"] = int(item.get("created_at") or 0)
        out.append(item)
    return out


def new_token() -> str:
    """新设备令牌：`vn2-` 前缀与 store 初始令牌保持一致。"""
    return "vn2-" + secrets.token_urlsafe(24)


def reset_token(conn) -> str:
    """重置设备令牌并立即生效；手机端需重新配对。"""
    token = new_token()
    with db.lock, conn:
        conn.execute(
            "INSERT OR REPLACE INTO settings(k, v) VALUES('sync_token', ?)", (token,))
    return token
