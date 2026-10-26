"""v2 人工修订 / 生效稿 / 导出 / 音频接口测试。"""
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


class EditsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="voxnote-v2-edits-")
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
        self.wav = wav_bytes()
        r = self.client.post(
            "/api/v2/sync/upload", headers=self.h,
            data={"meta": json.dumps({"recorded_at": 1000, "title": "修订测试"},
                                     ensure_ascii=False)},
            files={"file": ("a.wav", self.wav, "audio/wav")},
        )
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()

    def add_attempt(self, rid):
        r = self.client.post("/api/v2/records/%s/attempts" % rid, headers=self.h, json={
            "audio_sha256": "sha-1", "source": "hub", "engine": "fake",
            "segments": [
                {"t_ms": 0, "e_ms": 1000, "text": "原始第一句"},
                {"t_ms": 1000, "e_ms": 2000, "text": "原始第二句"},
            ],
        })
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()

    def add_edit(self, rid, t0=0, t1=1000, text="改过的第一句", deleted=False, op_id=None):
        payload = {"anchor_t_ms": t0, "anchor_e_ms": t1, "text": text,
                   "deleted": deleted, "author": "me"}
        if op_id:
            payload["id"] = op_id
        r = self.client.post("/api/v2/records/%s/edits" % rid, headers=self.h, json=payload)
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()

    def test_edit_effective_and_history(self):
        up = self.upload()
        self.add_attempt(up["id"])
        e1 = self.add_edit(up["id"], text="改过的第一句")
        tr = self.client.get("/api/v2/records/%s/transcript" % up["id"], headers=self.h).json()
        self.assertEqual(tr["segments"][0]["text"], "改过的第一句")
        self.assertEqual(tr["segments"][1]["text"], "原始第二句")
        self.assertTrue(tr["segments"][0].get("edited"))

        e2 = self.add_edit(up["id"], text="第二次改")
        tr = self.client.get("/api/v2/records/%s/transcript" % up["id"], headers=self.h).json()
        self.assertEqual(tr["segments"][0]["text"], "第二次改")
        edits = self.client.get("/api/v2/records/%s/edits" % up["id"], headers=self.h).json()["edits"]
        self.assertEqual(len(edits), 2, "append-only：旧修订必须保留")
        self.assertEqual({e1["id"], e2["id"]}, {e["id"] for e in edits})

    def test_deleted_edit_hides_segment(self):
        up = self.upload()
        self.add_attempt(up["id"])
        self.add_edit(up["id"], t0=1000, t1=2000, text="", deleted=True)
        tr = self.client.get("/api/v2/records/%s/transcript" % up["id"], headers=self.h).json()
        self.assertTrue(tr["segments"][1]["deleted"])
        srt = self.client.get("/api/v2/records/%s/export?format=srt" % up["id"], headers=self.h)
        self.assertEqual(srt.status_code, 200)
        self.assertNotIn("原始第二句", srt.text)
        self.assertIn("原始第一句", srt.text)

    def test_edit_changes_sync_hash(self):
        up = self.upload()
        self.add_attempt(up["id"])
        h1 = self.client.get("/api/v2/sync/manifest", headers=self.h).json()["items"][0]["sync_hash"]
        self.add_edit(up["id"])
        h2 = self.client.get("/api/v2/sync/manifest", headers=self.h).json()["items"][0]["sync_hash"]
        self.assertNotEqual(h1, h2)
        ch = self.client.get("/api/v2/sync/changes?cursor=0&limit=50", headers=self.h).json()
        self.assertTrue(any(c["kind"] == "edit" for c in ch["changes"]))

    def test_export_md_and_srt(self):
        up = self.upload()
        self.add_attempt(up["id"])
        self.add_edit(up["id"], text="导出的第一句")
        md = self.client.get("/api/v2/records/%s/export?format=md" % up["id"], headers=self.h)
        self.assertEqual(md.status_code, 200)
        self.assertIn("修订测试", md.text)
        self.assertIn("导出的第一句", md.text)
        srt = self.client.get("/api/v2/records/%s/export?format=srt" % up["id"], headers=self.h)
        self.assertIn("00:00:00,000 --> 00:00:01,000", srt.text)
        self.assertEqual(
            self.client.get("/api/v2/records/%s/export?format=x" % up["id"],
                            headers=self.h).status_code, 400)

    def test_audio_download(self):
        up = self.upload()
        r = self.client.get("/api/v2/records/%s/audio" % up["id"], headers=self.h)
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.content, self.wav)

    def test_bad_edit(self):
        up = self.upload()
        r = self.client.post("/api/v2/records/%s/edits" % up["id"], headers=self.h,
                             json={"anchor_t_ms": 0, "anchor_e_ms": 100})
        self.assertEqual(r.status_code, 400)
        r = self.client.post("/api/v2/records/nope/edits", headers=self.h,
                             json={"anchor_t_ms": 0, "anchor_e_ms": 100, "text": "x"})
        self.assertEqual(r.status_code, 404)
        self.assertEqual(
            self.client.post("/api/v2/records/%s/edits" % up["id"], json={}).status_code, 401)


if __name__ == "__main__":
    unittest.main(verbosity=2)
