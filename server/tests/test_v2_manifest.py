"""v2 manifest / sync_hash / resync_required 测试。"""
import os
import sys
import tempfile
import unittest
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from voxnote_server.v2 import api, db, manifest, service  # noqa: E402


class ManifestTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="voxnote-v2-manifest-")
        self.db_path = Path(self.tmp.name) / "voxnote.db"
        os.environ["VOXNOTE_V2_DB"] = str(self.db_path)
        db.reset()
        app = FastAPI()
        app.include_router(api.router, prefix="/api/v2")
        self.client = TestClient(app)
        token = db.conn().execute(
            "SELECT v FROM settings WHERE k='sync_token'").fetchone()["v"]
        self.h = {"Authorization": "Bearer " + token}

    def tearDown(self):
        db.reset()
        os.environ.pop("VOXNOTE_V2_DB", None)
        self.tmp.cleanup()

    def make(self, rid, title="", recorded_at=1000):
        return service.create_record(db.conn(), {
            "id": rid, "title": title, "recorded_at": recorded_at})

    def test_manifest_empty(self):
        body = self.client.get("/api/v2/sync/manifest", headers=self.h).json()
        self.assertEqual(body["items"], [])
        self.assertEqual(body["next_cursor"], "")
        self.assertFalse(body["has_more"])

    def test_manifest_pagination_and_deleted(self):
        for rid in ("a", "b", "c"):
            self.make(rid)
        service.tombstone_record(db.conn(), "b")

        p1 = self.client.get("/api/v2/sync/manifest?limit=2", headers=self.h).json()
        self.assertEqual([x["id"] for x in p1["items"]], ["a", "b"])
        self.assertTrue(p1["has_more"])
        self.assertTrue(p1["items"][1]["deleted"])
        p2 = self.client.get(
            "/api/v2/sync/manifest?limit=2&cursor=%s" % p1["next_cursor"],
            headers=self.h).json()
        self.assertEqual([x["id"] for x in p2["items"]], ["c"])
        self.assertFalse(p2["has_more"])

    def test_sync_hash_stable_and_sensitive(self):
        self.make("r1", "标题")
        h1 = self.client.get("/api/v2/sync/manifest", headers=self.h).json()["items"][0]["sync_hash"]

        # 同内容再写一次（只 bump rev）→ hash 不变
        service.update_record(db.conn(), "r1", {"title": "标题"})
        h2 = self.client.get("/api/v2/sync/manifest", headers=self.h).json()["items"][0]["sync_hash"]
        self.assertEqual(h1, h2)

        # 改标题 → hash 变
        service.update_record(db.conn(), "r1", {"title": "新标题"})
        h3 = self.client.get("/api/v2/sync/manifest", headers=self.h).json()["items"][0]["sync_hash"]
        self.assertNotEqual(h1, h3)

    def test_hash_changes_on_tombstone(self):
        self.make("r1")
        row = db.conn().execute("SELECT * FROM records WHERE id='r1'").fetchone()
        h1 = manifest.record_sync_hash(dict(row, edits_rev=0))
        service.tombstone_record(db.conn(), "r1")
        row = db.conn().execute("SELECT * FROM records WHERE id='r1'").fetchone()
        h2 = manifest.record_sync_hash(dict(row, edits_rev=0))
        self.assertNotEqual(h1, h2)

    def test_resync_required(self):
        for i in range(3):
            self.make("r%d" % i)
        self.assertFalse(
            self.client.get("/api/v2/sync/changes?cursor=1", headers=self.h).json()["resync_required"])
        # 模拟变更流被清理：只保留 rev>=3
        db.conn().execute("DELETE FROM changes WHERE rev < 3")
        db.conn().commit()
        self.assertTrue(
            self.client.get("/api/v2/sync/changes?cursor=1", headers=self.h).json()["resync_required"])
        self.assertFalse(
            self.client.get("/api/v2/sync/changes?cursor=2", headers=self.h).json()["resync_required"])

    def test_manifest_requires_auth(self):
        self.assertEqual(self.client.get("/api/v2/sync/manifest").status_code, 401)


if __name__ == "__main__":
    unittest.main(verbosity=2)
