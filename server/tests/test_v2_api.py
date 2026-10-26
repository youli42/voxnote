"""v2 API 测试（health / changes / records / upload / 鉴权）。"""
import io
import json
import os
import sys
import tempfile
import unittest
import wave
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


def wav_bytes(seconds: float = 1.0) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b"\x00\x00" * int(16000 * seconds))
    return buf.getvalue()


class ApiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="voxnote-v2-api-")
        self.root = Path(self.tmp.name)
        self.data_dir = self.root / "data"
        os.environ["VOXNOTE_DATA"] = str(self.data_dir)
        os.environ["VOXNOTE_V2_DB"] = str(self.root / "voxnote.db")
        db.reset()
        self.client = make_app()
        token = db.conn().execute(
            "SELECT v FROM settings WHERE k='sync_token'").fetchone()["v"]
        self.headers = {"Authorization": "Bearer " + token}

    def tearDown(self):
        db.reset()
        os.environ.pop("VOXNOTE_DATA", None)
        os.environ.pop("VOXNOTE_V2_DB", None)
        self.tmp.cleanup()

    def test_health(self):
        r = self.client.get("/api/v2/health")
        self.assertEqual(r.status_code, 200)
        body = r.json()
        self.assertTrue(body["ok"])
        self.assertEqual(body["schema_version"], 1)
        self.assertEqual(body["records"], 0)

    def test_changes_requires_auth(self):
        self.assertEqual(self.client.get("/api/v2/sync/changes").status_code, 401)
        self.assertEqual(
            self.client.get("/api/v2/sync/changes", headers={"Authorization": "Bearer nope"}).status_code,
            401,
        )

    def test_changes_empty(self):
        r = self.client.get("/api/v2/sync/changes?cursor=0", headers=self.headers)
        body = r.json()
        self.assertEqual(body["changes"], [])
        self.assertEqual(body["next_cursor"], 0)
        self.assertFalse(body["has_more"])

    def test_changes_with_record(self):
        service.create_record(db.conn(), {"id": "r1", "title": "接口记录", "recorded_at": 1})
        r = self.client.get("/api/v2/sync/changes?cursor=0&limit=10", headers=self.headers)
        body = r.json()
        self.assertEqual(len(body["changes"]), 1)
        self.assertEqual(body["changes"][0]["record"]["title"], "接口记录")
        self.assertEqual(body["next_cursor"], 1)

    def test_record_detail(self):
        service.create_record(db.conn(), {"id": "r1", "recorded_at": 1})
        r = self.client.get("/api/v2/records/r1", headers=self.headers)
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.json()["id"], "r1")
        self.assertEqual(
            self.client.get("/api/v2/records/nope", headers=self.headers).status_code, 404)

    def test_records_list(self):
        service.create_record(db.conn(), {"id": "r1", "recorded_at": 1})
        service.create_record(db.conn(), {"id": "r2", "recorded_at": 2})
        r = self.client.get("/api/v2/records?limit=10", headers=self.headers)
        ids = [x["id"] for x in r.json()["records"]]
        self.assertEqual(ids, ["r2", "r1"])

    def test_changes_validation(self):
        self.assertEqual(
            self.client.get("/api/v2/sync/changes?limit=0", headers=self.headers).status_code, 422)
        self.assertEqual(
            self.client.get("/api/v2/sync/changes?cursor=-1", headers=self.headers).status_code, 422)

    def test_upload_and_changes(self):
        data = {"meta": json.dumps({"stamp": "20261026_170000", "title": "上传",
                                    "recorded_at": 1000}, ensure_ascii=False)}
        r = self.client.post(
            "/api/v2/sync/upload", headers=self.headers, data=data,
            files={"file": ("a.wav", wav_bytes(), "audio/wav")},
        )
        self.assertEqual(r.status_code, 200, r.text)
        body = r.json()
        self.assertFalse(body["duplicate"])
        self.assertTrue(body["id"])

        page = self.client.get(
            "/api/v2/sync/changes?cursor=0", headers=self.headers).json()
        recs = [c["record"] for c in page["changes"] if c["kind"] == "record"]
        self.assertEqual(recs[0]["id"], body["id"])
        self.assertEqual(recs[0]["status"], "uploaded")
        audio_path = db.conn().execute(
            "SELECT audio_path FROM records WHERE id=?", (body["id"],)).fetchone()["audio_path"]
        self.assertTrue(Path(audio_path).is_file())

    def test_upload_idempotency_key(self):
        payload = wav_bytes()
        headers = dict(self.headers, **{"Idempotency-Key": "k1"})
        data = {"meta": json.dumps({"recorded_at": 1000})}
        r1 = self.client.post("/api/v2/sync/upload", headers=headers, data=data,
                              files={"file": ("a.wav", payload, "audio/wav")}).json()
        r2 = self.client.post("/api/v2/sync/upload", headers=headers, data=data,
                              files={"file": ("a.wav", payload, "audio/wav")}).json()
        self.assertFalse(r1["duplicate"])
        self.assertTrue(r2["duplicate"])
        self.assertEqual(r1["id"], r2["id"])

    def test_upload_same_audio_different_key(self):
        payload = wav_bytes()
        data = {"meta": json.dumps({"recorded_at": 1000})}
        r1 = self.client.post("/api/v2/sync/upload", headers=self.headers, data=data,
                              files={"file": ("a.wav", payload, "audio/wav")}).json()
        r2 = self.client.post("/api/v2/sync/upload", headers=self.headers, data=data,
                              files={"file": ("b.wav", payload, "audio/wav")}).json()
        self.assertTrue(r2["duplicate"])
        self.assertEqual(r1["id"], r2["id"])

    def test_upload_rejects_bad_input(self):
        r = self.client.post("/api/v2/sync/upload", headers=self.headers,
                             data={"meta": "not-json"},
                             files={"file": ("a.wav", b"xx", "audio/wav")})
        self.assertEqual(r.status_code, 400)
        r = self.client.post("/api/v2/sync/upload", headers=self.headers,
                             data={"meta": "{}"},
                             files={"file": ("a.txt", b"xx", "text/plain")})
        self.assertEqual(r.status_code, 400)
        r = self.client.post("/api/v2/sync/upload", data={"meta": "{}"},
                             files={"file": ("a.wav", b"xx", "audio/wav")})
        self.assertEqual(r.status_code, 401)


if __name__ == "__main__":
    unittest.main(verbosity=2)
