"""v2 ops 批量操作 + 冲突解决测试。"""
import io
import json
import os
import sys
import tempfile
import unittest
import uuid
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


class OpsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="voxnote-v2-ops-")
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
            data={"meta": json.dumps({"recorded_at": 1000, "title": "原始"}, ensure_ascii=False)},
            files={"file": ("a.wav", wav_bytes(), "audio/wav")},
        )
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()

    def op(self, rid, base_rev, fields=None, op_id=None, kind="update_record"):
        body = {"device_id": "dev1", "ops": [{
            "op_id": op_id or uuid.uuid4().hex,
            "kind": kind,
            "record_id": rid,
            "base_rev": base_rev,
            "fields": fields or {},
        }]}
        r = self.client.post("/api/v2/sync/ops", headers=self.h, json=body)
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()["results"][0]

    def test_update_applied(self):
        up = self.upload()
        r = self.op(up["id"], up["rev"], {"title": "改名", "cat": "工作"})
        self.assertEqual(r["status"], "applied")
        rec = self.client.get("/api/v2/records/%s" % up["id"], headers=self.h).json()
        self.assertEqual(rec["title"], "改名")
        self.assertEqual(rec["cat"], "工作")
        self.assertEqual(rec["rev"], up["rev"] + 1)

    def test_op_replay_idempotent(self):
        up = self.upload()
        op_id = "fixed-op-1"
        r1 = self.op(up["id"], up["rev"], {"title": "第一次"}, op_id=op_id)
        r2 = self.op(up["id"], up["rev"], {"title": "第一次"}, op_id=op_id)
        self.assertEqual(r1["status"], "applied")
        self.assertTrue(r2.get("duplicate"))
        rec = self.client.get("/api/v2/records/%s" % up["id"], headers=self.h).json()
        self.assertEqual(rec["rev"], up["rev"] + 1, "重放不应再 bump rev")

    def test_conflict_created_and_resolve_local(self):
        up = self.upload()
        r = self.op(up["id"], 999, {"title": "本地版本"})
        self.assertEqual(r["status"], "conflict")
        cid = r["conflict_id"]

        cs = self.client.get("/api/v2/sync/conflicts", headers=self.h).json()["conflicts"]
        self.assertEqual(len(cs), 1)
        self.assertEqual(cs[0]["local"]["fields"]["title"], "本地版本")
        self.assertEqual(cs[0]["server"]["title"], "原始")

        res = self.client.post("/api/v2/sync/conflicts/%s/resolve" % cid,
                               headers=self.h, json={"resolution": "local"}).json()
        self.assertEqual(res["resolution"], "local")
        self.assertIsNotNone(res["resolved_at"])

        rec = self.client.get("/api/v2/records/%s" % up["id"], headers=self.h).json()
        self.assertEqual(rec["title"], "本地版本")
        open_after = self.client.get("/api/v2/sync/conflicts", headers=self.h).json()["conflicts"]
        self.assertEqual(open_after, [])

    def test_conflict_resolve_server(self):
        up = self.upload()
        r = self.op(up["id"], 999, {"title": "本地版本"})
        cid = r["conflict_id"]
        self.client.post("/api/v2/sync/conflicts/%s/resolve" % cid,
                         headers=self.h, json={"resolution": "server"})
        rec = self.client.get("/api/v2/records/%s" % up["id"], headers=self.h).json()
        self.assertEqual(rec["title"], "原始")

    def test_tombstone_conflict_resolve_local(self):
        up = self.upload()
        r = self.op(up["id"], 999, kind="tombstone_record")
        self.assertEqual(r["status"], "conflict")
        self.client.post("/api/v2/sync/conflicts/%s/resolve" % r["conflict_id"],
                         headers=self.h, json={"resolution": "local"})
        rec = self.client.get("/api/v2/records/%s" % up["id"], headers=self.h).json()
        self.assertTrue(rec["deleted"])

    def test_bad_op_and_limits(self):
        up = self.upload()
        r = self.op(up["id"], up["rev"], kind="unknown")
        self.assertEqual(r["status"], "error")
        self.assertEqual(r["code"], "bad_request")

        many = [{"op_id": "x%d" % i, "kind": "update_record", "record_id": up["id"],
                 "base_rev": up["rev"], "fields": {"title": "t"}} for i in range(201)]
        r = self.client.post("/api/v2/sync/ops", headers=self.h, json={"ops": many})
        self.assertEqual(r.status_code, 400)

    def test_ops_requires_auth(self):
        r = self.client.post("/api/v2/sync/ops", json={"ops": []})
        self.assertEqual(r.status_code, 401)


if __name__ == "__main__":
    unittest.main(verbosity=2)
