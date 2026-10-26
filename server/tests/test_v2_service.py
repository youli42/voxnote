"""v2 记录服务 / 变更流测试。"""
import os
import sys
import tempfile
import unittest
from pathlib import Path

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from voxnote_server.v2 import db, service  # noqa: E402


class ServiceBase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="voxnote-v2-svc-")
        self.db_path = str(Path(self.tmp.name) / "voxnote.db")
        os.environ["VOXNOTE_V2_DB"] = self.db_path
        db.reset()
        self.conn = db.conn()

    def tearDown(self):
        db.reset()
        os.environ.pop("VOXNOTE_V2_DB", None)
        self.tmp.cleanup()

    def make(self, rid, title="", recorded_at=1000):
        return service.create_record(self.conn, {
            "id": rid, "title": title, "recorded_at": recorded_at,
        })


class RecordServiceTest(ServiceBase):
    def test_create_emits_change(self):
        rec = self.make("r1", "第一条")
        self.assertEqual(rec["rev"], 1)
        page = service.changes_since(self.conn, cursor=0)
        self.assertEqual(len(page["changes"]), 1)
        item = page["changes"][0]
        self.assertEqual(item["kind"], "record")
        self.assertEqual(item["entity_id"], "r1")
        self.assertEqual(item["record"]["title"], "第一条")
        self.assertEqual(page["next_cursor"], 1)
        self.assertFalse(page["has_more"])

    def test_update_bumps_rev_and_change(self):
        self.make("r1")
        rec = service.update_record(self.conn, "r1", {"title": "改名", "status": "done"})
        self.assertEqual(rec["rev"], 2)
        page = service.changes_since(self.conn, cursor=1)
        self.assertEqual(len(page["changes"]), 1)
        self.assertEqual(page["changes"][0]["record"]["title"], "改名")

    def test_update_rejects_unknown_field(self):
        self.make("r1")
        with self.assertRaises(ValueError):
            service.update_record(self.conn, "r1", {"id": "hack"})

    def test_update_missing_record(self):
        self.assertIsNone(service.update_record(self.conn, "nope", {"title": "x"}))

    def test_tombstone(self):
        self.make("r1")
        rec = service.tombstone_record(self.conn, "r1")
        self.assertIsNotNone(rec["deleted_at"])
        self.assertEqual(rec["rev"], 2)
        page = service.changes_since(self.conn, cursor=0)
        self.assertTrue(page["changes"][-1]["record"]["deleted"])

        visible = service.list_records(self.conn)
        self.assertEqual(visible, [])
        all_rows = service.list_records(self.conn, include_deleted=True)
        self.assertEqual(len(all_rows), 1)
        self.assertTrue(all_rows[0]["deleted"])

    def test_tombstone_idempotent(self):
        self.make("r1")
        first = service.tombstone_record(self.conn, "r1")
        second = service.tombstone_record(self.conn, "r1")
        self.assertEqual(first["rev"], second["rev"])
        page = service.changes_since(self.conn, cursor=0)
        self.assertEqual(len(page["changes"]), 2, "重复删除不应再发 change")

    def test_changes_pagination(self):
        for i in range(5):
            self.make("r%d" % i, recorded_at=1000 + i)
        seen, cursor, rounds = [], 0, 0
        while True:
            page = service.changes_since(self.conn, cursor=cursor, limit=2)
            seen += [c["rev"] for c in page["changes"]]
            cursor = page["next_cursor"]
            rounds += 1
            if not page["has_more"]:
                break
            self.assertLess(rounds, 10, "分页未收敛")
        self.assertEqual(seen, [1, 2, 3, 4, 5])
        self.assertEqual(service.changes_since(self.conn, cursor=5)["changes"], [])

    def test_list_records_order(self):
        self.make("old", recorded_at=1000)
        self.make("new", recorded_at=2000)
        ids = [r["id"] for r in service.list_records(self.conn)]
        self.assertEqual(ids, ["new", "old"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
