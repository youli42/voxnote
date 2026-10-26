"""v2 schema / 空库初始化测试（stdlib unittest，无第三方依赖）。"""
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from voxnote_server.v2.schema import SCHEMA_VERSION  # noqa: E402
from voxnote_server.v2.store import SchemaVersionError, init  # noqa: E402

TABLES = {
    "schema_meta", "records", "transcript_attempts", "attempt_segments",
    "edits", "jobs", "devices", "engines", "conflicts", "changes", "settings",
    "idempotency",
}


class V2StoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="voxnote-v2-")
        self.db = Path(self.tmp.name) / "voxnote.db"

    def tearDown(self):
        self.tmp.cleanup()

    def test_init_creates_tables(self):
        conn = init(self.db)
        try:
            names = {r["name"] for r in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'").fetchall()}
            self.assertTrue(TABLES.issubset(names), TABLES - names)
        finally:
            conn.close()

    def test_schema_version(self):
        conn = init(self.db)
        try:
            row = conn.execute(
                "SELECT value FROM schema_meta WHERE key='schema_version'").fetchone()
            self.assertEqual(int(row["value"]), SCHEMA_VERSION)
        finally:
            conn.close()

    def test_init_idempotent(self):
        c1 = init(self.db)
        c1.close()
        c2 = init(self.db)
        try:
            row = c2.execute(
                "SELECT value FROM schema_meta WHERE key='schema_version'").fetchone()
            self.assertEqual(int(row["value"]), SCHEMA_VERSION)
        finally:
            c2.close()

    def test_schema_version_mismatch(self):
        conn = init(self.db)
        conn.execute("UPDATE schema_meta SET value='999' WHERE key='schema_version'")
        conn.commit()
        conn.close()
        with self.assertRaises(SchemaVersionError):
            init(self.db)

    def test_foreign_keys_enforced(self):
        conn = init(self.db)
        try:
            with self.assertRaises(sqlite3.IntegrityError):
                conn.execute(
                    "INSERT INTO transcript_attempts(id, record_id, audio_sha256, source, engine, created_at)"
                    " VALUES('a1','missing','sha','hub','fake',1)")
                conn.commit()
        finally:
            conn.close()

    def test_attempt_unique_key_and_cascade(self):
        conn = init(self.db)
        try:
            now = 1000
            conn.execute(
                "INSERT INTO records(id, recorded_at, created_at, updated_at)"
                " VALUES('r1', ?, ?, ?)", (now, now, now))
            row = ("a1", "r1", "sha", "hub", "fake")
            sql = ("INSERT INTO transcript_attempts"
                   "(id, record_id, audio_sha256, source, engine, created_at)"
                   " VALUES(?,?,?,?,?,?)")
            conn.execute(sql, row + (now,))
            with self.assertRaises(sqlite3.IntegrityError):
                conn.execute(sql, row + (now,))
            conn.execute(
                "INSERT INTO attempt_segments(attempt_id, idx, text) VALUES('a1', 0, '你好')")
            conn.execute("DELETE FROM records WHERE id='r1'")
            conn.commit()
            left = conn.execute("SELECT COUNT(*) n FROM attempt_segments").fetchone()["n"]
            self.assertEqual(left, 0, "删除记录应级联删除尝试与句")
        finally:
            conn.close()

    def test_changes_autoincrement(self):
        conn = init(self.db)
        try:
            conn.execute("INSERT INTO changes(kind, entity_id, created_at) VALUES('record','r1',1)")
            conn.execute("INSERT INTO changes(kind, entity_id, created_at) VALUES('record','r2',2)")
            conn.commit()
            revs = [r["rev"] for r in conn.execute("SELECT rev FROM changes ORDER BY rev")]
            self.assertEqual(revs, [1, 2])
        finally:
            conn.close()

    def test_pragmas(self):
        conn = init(self.db)
        try:
            self.assertEqual(conn.execute("PRAGMA foreign_keys").fetchone()[0], 1)
            self.assertEqual(
                str(conn.execute("PRAGMA journal_mode").fetchone()[0]).lower(), "wal")
        finally:
            conn.close()

    def test_default_sync_token(self):
        conn = init(self.db)
        try:
            row = conn.execute("SELECT v FROM settings WHERE k='sync_token'").fetchone()
            self.assertIsNotNone(row)
            self.assertTrue(row["v"].startswith("vn2-"))
        finally:
            conn.close()


if __name__ == "__main__":
    unittest.main(verbosity=2)
