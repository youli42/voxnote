"""v2 引擎配置 / 同步转写 / 任务状态测试。"""
import io
import json
import os
import sys
import tempfile
import unittest
import wave
from pathlib import Path
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

SERVER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SERVER_DIR))

from voxnote_server.v2 import api, db, worker  # noqa: E402


def wav_bytes(seconds: float = 1.0) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b"\x00\x00" * int(16000 * seconds))
    return buf.getvalue()


class EnginesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="voxnote-v2-engines-")
        root = Path(self.tmp.name)
        os.environ["VOXNOTE_DATA"] = str(root / "data")
        os.environ["VOXNOTE_V2_DB"] = str(root / "voxnote.db")
        db.reset()
        app = FastAPI()
        app.include_router(api.router, prefix="/api/v2")
        self.client = TestClient(app)
        token = db.conn().execute(
            "SELECT v FROM settings WHERE k='sync_token'").fetchone()["v"]
        self.h = {"Authorization": "Bearer " + token}

    def tearDown(self):
        db.reset()
        os.environ.pop("VOXNOTE_DATA", None)
        os.environ.pop("VOXNOTE_V2_DB", None)
        self.tmp.cleanup()

    def upload(self):
        r = self.client.post(
            "/api/v2/sync/upload", headers=self.h,
            data={"meta": json.dumps({"recorded_at": 1000, "dur_ms": 2000})},
            files={"file": ("a.wav", wav_bytes(2), "audio/wav")},
        )
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()

    def add_engine(self):
        r = self.client.post("/api/v2/engines", headers=self.h, json={
            "name": "假引擎", "url": "http://127.0.0.1:9", "token": "secret",
            "model": "fake-whisper", "quality_tier": 3,
        })
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()

    def test_engine_crud_and_masking(self):
        e = self.add_engine()
        self.assertTrue(e["has_token"])
        self.assertEqual(e["token"], "", "列表/创建响应不应回显 token")

        lst = self.client.get("/api/v2/engines", headers=self.h).json()["engines"]
        self.assertEqual(len(lst), 1)

        up = self.client.patch("/api/v2/engines/%s" % e["id"], headers=self.h,
                               json={"name": "改名引擎", "enabled": False}).json()
        self.assertEqual(up["name"], "改名引擎")
        self.assertFalse(up["enabled"])
        self.assertEqual(
            self.client.patch("/api/v2/engines/%s" % e["id"], headers=self.h,
                              json={"bad": 1}).status_code, 400)

        self.assertEqual(self.client.delete("/api/v2/engines/%s" % e["id"],
                                            headers=self.h).status_code, 200)
        self.assertEqual(self.client.delete("/api/v2/engines/%s" % e["id"],
                                            headers=self.h).status_code, 404)

    def test_transcribe_creates_attempt(self):
        up = self.upload()
        e = self.add_engine()
        body = {
            "text": "这是第 1 句测试转写。这是第 2 句测试转写。",
            "duration": 2.0,
            "segments": [
                {"start": 0.0, "end": 1.0, "text": "这是第 1 句测试转写。"},
                {"start": 1.0, "end": 2.0, "text": "这是第 2 句测试转写。"},
            ],
        }
        with patch("voxnote_server.v2.engines.call_engine", return_value=body):
            r = self.client.post("/api/v2/records/%s/transcribe" % up["id"],
                                 headers=self.h, json={"engine_id": e["id"]})
        self.assertEqual(r.status_code, 200, r.text)
        out = r.json()
        self.assertEqual(out["state"], "queued")
        self.assertTrue(out["job_id"])

        # 后台 worker 单步执行（测试里同步跑，避免线程时序）
        with patch("voxnote_server.v2.engines.call_engine", return_value=body):
            self.assertTrue(worker.run_once(db.conn()))
        job = self.client.get("/api/v2/jobs/%s" % out["job_id"], headers=self.h).json()
        self.assertEqual(job["state"], "done")
        self.assertEqual(job["engine_id"], e["id"])

        tr = self.client.get("/api/v2/records/%s/transcript" % up["id"],
                             headers=self.h).json()
        self.assertEqual(tr["segments"][1]["text"], "这是第 2 句测试转写。")
        rec = self.client.get("/api/v2/records/%s" % up["id"], headers=self.h).json()
        self.assertEqual(rec["status"], "done")

    def test_transcribe_failure_marks_record(self):
        up = self.upload()
        e = self.add_engine()
        r = self.client.post("/api/v2/records/%s/transcribe" % up["id"],
                             headers=self.h, json={"engine_id": e["id"]})
        self.assertEqual(r.status_code, 200, r.text)
        job_id = r.json()["job_id"]
        with patch("voxnote_server.v2.engines.call_engine",
                   side_effect=RuntimeError("引擎挂了")):
            self.assertTrue(worker.run_once(db.conn()))
        rec = self.client.get("/api/v2/records/%s" % up["id"], headers=self.h).json()
        self.assertEqual(rec["status"], "failed")
        self.assertEqual(rec["error_code"], "engine_error")
        self.assertIn("引擎挂了", rec["error_hint"])
        job = self.client.get("/api/v2/jobs/%s" % job_id, headers=self.h).json()
        self.assertEqual(job["state"], "failed")

    def test_engine_probe(self):
        e = self.add_engine()
        with patch("voxnote_server.v2.engines.probe",
                   return_value={"ready": True, "latency_ms": 1}):
            r = self.client.get("/api/v2/engines/%s/probe" % e["id"], headers=self.h)
        self.assertEqual(r.status_code, 200)
        self.assertTrue(r.json()["ready"])

    def test_transcribe_requires_engine(self):
        up = self.upload()
        r = self.client.post("/api/v2/records/%s/transcribe" % up["id"],
                             headers=self.h, json={})
        self.assertEqual(r.status_code, 400)
        self.assertIn("没有可用引擎", r.json()["detail"])

    def test_engines_require_auth(self):
        self.assertEqual(self.client.get("/api/v2/engines").status_code, 401)
        self.assertEqual(self.client.post("/api/v2/engines", json={}).status_code, 401)


if __name__ == "__main__":
    unittest.main(verbosity=2)
