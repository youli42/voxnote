"""v2 维护：保留设置 / 备份 / 清理 / 存储 / 诊断测试。"""
import io
import json
import os
import sys
import tempfile
import time
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


class MaintenanceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="voxnote-v2-maint-")
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
            data={"meta": json.dumps({"recorded_at": 1000, "title": "待清理"})},
            files={"file": ("a.wav", wav_bytes(), "audio/wav")},
        )
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()

    def test_settings_defaults_and_patch(self):
        s = self.client.get("/api/v2/settings", headers=self.h).json()["settings"]
        self.assertEqual(s["tombstone_retention_days"], "90")
        self.assertEqual(s["backup_keep"], "7")

        r = self.client.patch("/api/v2/settings", headers=self.h,
                              json={"tombstone_retention_days": "30"})
        self.assertEqual(r.json()["settings"]["tombstone_retention_days"], "30")
        self.assertEqual(
            self.client.patch("/api/v2/settings", headers=self.h,
                              json={"bad_key": 1}).status_code, 400)
        self.assertEqual(
            self.client.patch("/api/v2/settings", headers=self.h,
                              json={"backup_keep": -1}).status_code, 400)

    def test_backup_and_prune(self):
        self.client.patch("/api/v2/settings", headers=self.h, json={"backup_keep": "2"})
        for _ in range(3):
            r = self.client.post("/api/v2/backups", headers=self.h)
            self.assertEqual(r.status_code, 200, r.text)
            self.assertTrue(Path(r.json()["path"]).is_file())
            time.sleep(0.01)
        lst = self.client.get("/api/v2/backups", headers=self.h).json()["backups"]
        self.assertLessEqual(len(lst), 2)

    def test_cleanup_tombstone_dry_run_and_apply(self):
        up = self.upload()
        self.client.post("/api/v2/sync/ops", headers=self.h, json={"ops": [{
            "op_id": "del-1", "kind": "tombstone_record",
            "record_id": up["id"], "base_rev": up["rev"]}]})
        old = int(time.time() * 1000) - 100 * 86400000
        db.conn().execute("UPDATE records SET deleted_at=? WHERE id=?", (old, up["id"]))
        db.conn().commit()

        dry = self.client.post("/api/v2/cleanup", headers=self.h,
                               json={"dry_run": True}).json()
        self.assertEqual(dry["tombstones"], 1)
        self.assertFalse(dry["dry_run"] is False)
        self.assertEqual(
            self.client.get("/api/v2/records/%s" % up["id"], headers=self.h).status_code, 200)

        audio_path = db.conn().execute(
            "SELECT audio_path FROM records WHERE id=?", (up["id"],)).fetchone()["audio_path"]
        real = self.client.post("/api/v2/cleanup", headers=self.h,
                                json={"dry_run": False}).json()
        self.assertEqual(real["tombstones"], 1)
        self.assertGreaterEqual(real["bytes"], 0)
        self.assertEqual(
            self.client.get("/api/v2/records/%s" % up["id"], headers=self.h).status_code, 404)
        self.assertFalse(Path(audio_path).exists())

    def test_storage_and_diagnostics(self):
        up = self.upload()
        st = self.client.get("/api/v2/storage", headers=self.h).json()
        self.assertEqual(st["records"], 1)
        self.assertGreater(st["audio_bytes"], 0)
        self.assertGreater(st["db_bytes"], 0)

        dg = self.client.get("/api/v2/diagnostics", headers=self.h).json()
        self.assertEqual(dg["schema_version"], 1)
        self.assertIn("storage", dg)
        self.assertIn("jobs", dg)
        self.assertIn("disk", dg)
        self.assertTrue(up["id"])

    def test_auth_required(self):
        for path in ("/api/v2/settings", "/api/v2/storage", "/api/v2/diagnostics"):
            self.assertEqual(self.client.get(path).status_code, 401)


if __name__ == "__main__":
    unittest.main(verbosity=2)
