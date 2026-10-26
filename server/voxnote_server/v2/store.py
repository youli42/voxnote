"""v2 存储层：连接、空库初始化、schema 版本校验。

只做持久化，不含业务规则。破坏性重建策略下，`init()` 只负责：
  - 建目录；
  - 建表（IF NOT EXISTS，幂等）；
  - 写入/校验 schema_version；
  - 返回已配置好的连接（WAL + foreign_keys）。

命令行初始化：
    cd server
    python -m voxnote_server.v2.store --db data/voxnote.db
"""
import argparse
import secrets
import sqlite3
import sys
from pathlib import Path

from .schema import SCHEMA_SQL, SCHEMA_VERSION


class SchemaVersionError(RuntimeError):
    """库里的 schema 版本与代码不一致（破坏性重建阶段不做迁移）。"""


def connect(db_path) -> sqlite3.Connection:
    """v2 开发期使用单连接 + 外部读写锁（见 v2/db.py）。

    check_same_thread=False：FastAPI 同步端点跑在线程池里，连接需要跨线程复用；
    并发安全由 v2.db.lock 串行化（单用户服务器足够，后续可按需改为连接池）。
    """
    p = Path(db_path)
    p.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(str(p), timeout=15, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("PRAGMA busy_timeout=15000")
    return conn


def schema_version(conn: sqlite3.Connection):
    row = conn.execute(
        "SELECT value FROM schema_meta WHERE key='schema_version'").fetchone()
    return int(row["value"]) if row else None


def _ensure_defaults(conn: sqlite3.Connection):
    """首次初始化写入设备同步令牌（与 v1 策略一致：可用、可重置）。"""
    row = conn.execute("SELECT v FROM settings WHERE k='sync_token'").fetchone()
    if row is None:
        conn.execute(
            "INSERT INTO settings(k, v) VALUES('sync_token', ?)",
            ("vn2-" + secrets.token_urlsafe(24),),
        )


def init(db_path) -> sqlite3.Connection:
    """初始化空库；已存在且版本一致时幂等返回。版本不一致直接报错。"""
    conn = connect(db_path)
    try:
        conn.executescript(SCHEMA_SQL)
        current = schema_version(conn)
        if current is None:
            conn.execute(
                "INSERT INTO schema_meta(key, value) VALUES('schema_version', ?)",
                (str(SCHEMA_VERSION),),
            )
        elif current != SCHEMA_VERSION:
            raise SchemaVersionError(
                "schema 版本不一致：库=%s，代码=%s；破坏性重建阶段请换新库"
                % (current, SCHEMA_VERSION))
        _ensure_defaults(conn)
        conn.commit()
        return conn
    except Exception:
        conn.close()
        raise


def main(argv=None):
    ap = argparse.ArgumentParser(description="初始化 voxnote v2 数据库")
    ap.add_argument("--db", required=True, help="数据库文件路径（不存在则新建）")
    args = ap.parse_args(argv)
    conn = init(args.db)
    try:
        print("ok schema=%s db=%s" % (schema_version(conn), Path(args.db).resolve()))
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
