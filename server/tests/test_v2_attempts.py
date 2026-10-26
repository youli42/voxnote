"""v2 转写尝试 / 生效指针 / transcript 测试。"""
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

from voxnote_server.v2 import api, db  # noqa: E402


def wav_bytes(seconds: float = 1.0) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b"\x00\x00" * int(16000 * seconds))
    return buf.getvalue()


class AttemptsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="voxnote-v2-attempts-")
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
            data={"meta": json.dumps({"recorded_at": 1000})},
            files={"file": ("a.wav", wav_bytes(), "audio/wav")},
        )
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()

    def add_attempt(self, rid, source="hub", engine="fake", text="第一版",
                    activate=None):
        payload = {
            "audio_sha256": "sha-" + source + engine + text,
            "source": source, "engine": engine, "model": "fake-1",
            "quality_tier": 2, "duration_ms": 1000,
            "segments": [
                {"t_ms": 0, "e_ms": 500, "text": text + "-1"},
                {"t_ms": 500, "e_ms": 1000, "text": text + "-2"},
            ],
        }
        if activate is not None:
            payload["activate"] = activate
        r = self.client.post("/api/v2/records/%s/attempts" % rid,
                             headers=self.h, json=payload)
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()

    def test_first_attempt_becomes_active(self):
        up = self.upload()
        a = self.add_attempt(up["id"], text="甲")
        self.assertFalse(a["duplicate"])
        self.assertEqual(len(a["segments"]), 2)

        tr = self.client.get("/api/v2/records/%s/transcript" % up["id"], headers=self.h).json()
        self.assertEqual(tr["attempt"]["id"], a["id"])
        self.assertEqual(tr["segments"][0]["text"], "甲-1")

    def test_attempt_duplicate(self):
        up = self.upload()
        a1 = self.add_attempt(up["id"], text="甲")
        a2 = self.add_attempt(up["id"], text="甲")
        self.assertTrue(a2["duplicate"])
        self.assertEqual(a1["id"], a2["id"])
        lst = self.client.get("/api/v2/records/%s/attempts" % up["id"], headers=self.h).json()
        self.assertEqual(len(lst["attempts"]), 1)

    def test_switch_active_keeps_both(self):
        up = self.upload()
        a1 = self.add_attempt(up["id"], source="hub", text="快稿")
        a2 = self.add_attempt(up["id"], source="remote", engine="strong", text="精稿",
                              activate=True)

        lst = self.client.get("/api/v2/records/%s/attempts" % up["id"], headers=self.h).json()
        self.assertEqual(len(lst["attempts"]), 2)
        tr = self.client.get("/api/v2/records/%s/transcript" % up["id"], headers=self.h).json()
        self.assertEqual(tr["attempt"]["id"], a2["id"])
        self.assertEqual(tr["segments"][0]["text"], "精稿-1")

        # 切回旧尝试，旧尝试内容仍在
        self.client.post("/api/v2/records/%s/active-attempt" % up["id"],
                         headers=self.h, json={"attempt_id": a1["id"], "pinned": True})
        tr = self.client.get("/api/v2/records/%s/transcript" % up["id"], headers=self.h).json()
        self.assertEqual(tr["attempt"]["id"], a1["id"])
        self.assertEqual(tr["segments"][0]["text"], "快稿-1")

    def test_bad_attempt(self):
        up = self.upload()
        r = self.client.post("/api/v2/records/%s/attempts" % up["id"],
                             headers=self.h, json={"audio_sha256": "x"})
        self.assertEqual(r.status_code, 400)
        r = self.client.post("/api/v2/records/nope/attempts", headers=self.h,
                             json={"audio_sha256": "x", "source": "hub", "engine": "e"})
        self.assertEqual(r.status_code, 404)
        r = self.client.post("/api/v2/records/%s/active-attempt" % up["id"],
                             headers=self.h, json={"attempt_id": "nope"})
        self.assertEqual(r.status_code, 400)

    def test_attempts_require_auth(self):
        self.assertEqual(
            self.client.post("/api/v2/records/r1/attempts", json={}).status_code, 401)


if __name__ == "__main__":
    unittest.main(verbosity=2)
