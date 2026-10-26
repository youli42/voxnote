"""v2 只读 API 测试（health / changes / records）。"""
import os
import sys
import tempfile
import unittest
from pathlib import Path

from fastapi import FastAPI
from fastapi.testclient import TestClient

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from voxnote_server.v2 import api, db, service  # noqa: E402


def make_app():
    app = FastAPI()
    app.include_router(api.router, prefix="/api/v2")
    return TestClient(app)


class ApiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="voxnote-v2-api-")
        self.db_path = str(Path(self.tmp.name) / "voxnote.db")
        os.environ["VOXNOTE_V2_DB"] = self.db_path
        db.reset()
        self.client = make_app()

    def tearDown(self):
        db.reset()
        os.environ.pop("VOXNOTE_V2_DB", None)
        self.tmp.cleanup()

    def test_health(self):
        r = self.client.get("/api/v2/health")
        self.assertEqual(r.status_code, 200)
        body = r.json()
        self.assertTrue(body["ok"])
        self.assertEqual(body["schema_version"], 1)
        self.assertEqual(body["records"], 0)

    def test_changes_empty(self):
        r = self.client.get("/api/v2/sync/changes?cursor=0")
        self.assertEqual(r.status_code, 200)
        body = r.json()
        self.assertEqual(body["changes"], [])
        self.assertEqual(body["next_cursor"], 0)
        self.assertFalse(body["has_more"])

    def test_changes_with_record(self):
        service.create_record(db.conn(), {"id": "r1", "title": "接口记录", "recorded_at": 1})
        r = self.client.get("/api/v2/sync/changes?cursor=0&limit=10")
        body = r.json()
        self.assertEqual(len(body["changes"]), 1)
        self.assertEqual(body["changes"][0]["record"]["title"], "接口记录")
        self.assertEqual(body["next_cursor"], 1)

    def test_record_detail(self):
        service.create_record(db.conn(), {"id": "r1", "recorded_at": 1})
        r = self.client.get("/api/v2/records/r1")
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.json()["id"], "r1")
        self.assertEqual(self.client.get("/api/v2/records/nope").status_code, 404)

    def test_records_list(self):
        service.create_record(db.conn(), {"id": "r1", "recorded_at": 1})
        service.create_record(db.conn(), {"id": "r2", "recorded_at": 2})
        r = self.client.get("/api/v2/records?limit=10")
        ids = [x["id"] for x in r.json()["records"]]
        self.assertEqual(ids, ["r2", "r1"])

    def test_changes_validation(self):
        self.assertEqual(self.client.get("/api/v2/sync/changes?limit=0").status_code, 422)
        self.assertEqual(self.client.get("/api/v2/sync/changes?cursor=-1").status_code, 422)


if __name__ == "__main__":
    unittest.main(verbosity=2)
