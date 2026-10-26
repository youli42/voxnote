"""v2 设备鉴权：Bearer 令牌（一服务器一令牌，后续做多设备令牌）。"""
import hmac

from fastapi import Header, HTTPException

from . import db


def device_token(conn) -> str:
    with db.lock:
        row = conn.execute("SELECT v FROM settings WHERE k='sync_token'").fetchone()
    return row["v"] if row else ""


def require_device(authorization: str = Header(default="")) -> bool:
    token = authorization[7:] if authorization.lower().startswith("bearer ") else authorization
    expected = device_token(db.conn())
    if not expected or not hmac.compare_digest(token.strip(), expected):
        raise HTTPException(401, "无效的设备令牌")
    return True
