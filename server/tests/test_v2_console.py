"""v2 控制台补充接口测试（控制台补充接口 §1-§7）。

覆盖 12 个端点/扩展点：
  GET  /stats
  GET  /jobs?state=&limit=、POST /jobs/{id}/retry、POST /jobs/{id}/cancel
  PATCH /records/{id}、DELETE /records/{id}
  DELETE /records/{id}/edits/{edit_id}
  GET  /records/{id}/attempts/{attempt_id}
  GET  /records/{id}/diff?from=&to=
  GET  /devices、POST /tokens/reset
  GET  /records 扩展 q/status/cat/has_audio

断言口径以 docs/设计/控制台补充接口.md + task-5 的“实现口径”为准：
  - stats.records 只算未删除，deleted 单列，audio_bytes/audio_hours 只算未删除；
  - PATCH/DELETE 的 rev 是全局 changes.rev（递增且等于记录当前 rev）；
  - retry 对 queued/running 返回 400；done/failed/canceled 允许并新建 queued；
  - cancel queued → canceled、running → requested，已结束 → 400；
  - has_audio=false/空串等同不筛选，status 非法 → 400，cat=__none__ 为未分类；
  - diff 缺省回落 active，无 active 又缺参 → 400，尝试不属于记录 → 404；
  - attempts/{id} 的 attempt 只含 6 个契约字段；
  - DELETE edits 返回 {"ok": true}，重复删 404；
  - PATCH 空 body 为 no-op，未知字段/类型错 → 400。
"""
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

from voxnote_server.v2 import api, db, jobs, service  # noqa: E402


def wav_bytes(seconds: float = 1.0) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(b"\x00\x00" * int(16000 * seconds))
    return buf.getvalue()


def now_ms() -> int:
    return int(time.time() * 1000)


class ConsoleApiTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="voxnote-v2-console-")
        root = Path(self.tmp.name)
        os.environ["VOXNOTE_DATA"] = str(root / "data")
        os.environ["VOXNOTE_V2_DB"] = str(root / "voxnote.db")
        db.reset()
        app = FastAPI()
        app.include_router(api.router, prefix="/api/v2")
        self.client = TestClient(app)
        self.token = db.conn().execute(
            "SELECT v FROM settings WHERE k='sync_token'").fetchone()["v"]
        self.h = {"Authorization": "Bearer " + self.token}
        self._seq = 0

    def tearDown(self):
        db.reset()
        os.environ.pop("VOXNOTE_DATA", None)
        os.environ.pop("VOXNOTE_V2_DB", None)
        self.tmp.cleanup()

    # ---------------- fixtures / helpers ----------------

    def upload(self, title="", recorded_at=None, dur_ms=0, cat=None):
        """上传一条带音频的记录（每次音频字节不同，避免 sha 去重）。"""
        self._seq += 1
        meta = {
            "title": title,
            "dur_ms": int(dur_ms),
            "recorded_at": int(now_ms() if recorded_at is None else recorded_at),
        }
        if cat is not None:
            meta["cat"] = cat
        r = self.client.post(
            "/api/v2/sync/upload", headers=self.h,
            data={"meta": json.dumps(meta, ensure_ascii=False)},
            files={"file": ("a.wav", wav_bytes(1.0 + self._seq * 0.1), "audio/wav")},
        )
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()

    def make_record(self, rid, **fields):
        fields.setdefault("recorded_at", 1000)
        return service.create_record(db.conn(), dict(fields, id=rid))

    def record(self, rid):
        r = self.client.get("/api/v2/records/%s" % rid, headers=self.h)
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()

    def make_job(self, rid, state="queued", error_code="", error_hint=""):
        """按目标状态造一条任务（测试不启动 worker，状态稳定）。"""
        conn = db.conn()
        jid = jobs.create(conn, rid, engine_id="", engine="fake", source="hub")
        if state in ("running", "transcribing"):
            jobs.start(conn, jid)
        if state == "transcribing":
            with conn:
                conn.execute("UPDATE jobs SET state='transcribing' WHERE id=?", (jid,))
        elif state == "failed":
            jobs.finish(conn, jid, "failed", error_code or "engine_error",
                        error_hint or "引擎挂了")
        elif state == "done":
            jobs.finish(conn, jid, "done")
        elif state == "canceled":
            with conn:
                conn.execute("UPDATE jobs SET state='canceled' WHERE id=?", (jid,))
        return jid

    def add_attempt(self, rid, engine="fake", text="甲", segs=None, activate=None,
                    sha=None):
        payload = {
            "audio_sha256": sha or ("sha-" + engine + text),
            "source": "hub", "engine": engine, "model": "m-1",
            "quality_tier": 2, "duration_ms": 1000,
            "segments": segs or [{"t_ms": 0, "e_ms": 500, "text": text + "-1"}],
        }
        if activate is not None:
            payload["activate"] = activate
        r = self.client.post("/api/v2/records/%s/attempts" % rid, headers=self.h,
                             json=payload)
        self.assertEqual(r.status_code, 200, r.text)
        return r.json()

    def latest_rev(self) -> int:
        return int(db.conn().execute(
            "SELECT MAX(rev) r FROM changes").fetchone()["r"])

    # ---------------- §1 统计 ----------------

    def test_stats_semantics(self):
        a = self.upload(title="今天", dur_ms=3600000)
        b = self.upload(title="很久以前", recorded_at=1000, dur_ms=7200000)
        r = self.client.delete("/api/v2/records/%s" % b["id"], headers=self.h)
        self.assertEqual(r.status_code, 200, r.text)

        st = self.client.get("/api/v2/stats", headers=self.h).json()
        for key in ("records", "records_today", "deleted", "audio_bytes", "audio_hours",
                    "jobs", "edited_records", "attempts", "devices", "last_sync_at"):
            self.assertIn(key, st)
        self.assertEqual(set(st["jobs"]), {"queued", "running", "failed", "done"})

        self.assertEqual(st["records"], 1, "records 只算未删除记录")
        self.assertEqual(st["deleted"], 1, "deleted 单列墓碑数")
        self.assertEqual(st["records_today"], 1, "records_today 只算未删除且 recorded_at 在今天")
        row = db.conn().execute(
            "SELECT bytes, dur_ms FROM records WHERE id=?", (a["id"],)).fetchone()
        self.assertEqual(st["audio_bytes"], row["bytes"], "audio_bytes 只算未删除")
        self.assertEqual(st["audio_hours"], 1.0, "audio_hours 只算未删除")
        self.assertEqual(st["jobs"],
                         {"queued": 0, "running": 0, "failed": 0, "done": 0})
        self.assertEqual(st["edited_records"], 0)
        self.assertEqual(st["attempts"], 0)
        self.assertEqual(st["devices"], 0)
        self.assertIsInstance(st["last_sync_at"], int)

    def test_stats_counts_jobs_edits_attempts_devices(self):
        self.make_record("r1", title="统计", status="done")
        self.add_attempt("r1", text="甲", activate=True)
        self.client.post("/api/v2/records/r1/edits", headers=self.h,
                         json={"anchor_t_ms": 0, "anchor_e_ms": 500, "text": "改"})
        self.make_job("r1", "queued")
        self.make_job("r1", "running")
        self.make_job("r1", "transcribing")   # running 口径同时包含 transcribing
        self.make_job("r1", "failed")
        self.make_job("r1", "done")
        conn = db.conn()
        with conn:
            conn.execute(
                "INSERT INTO devices(id, name, kind, app_version, last_seen_at,"
                " status, created_at) VALUES('d1','手机','phone','1.0',?,'active',1000)",
                (now_ms(),))

        st = self.client.get("/api/v2/stats", headers=self.h).json()
        self.assertEqual(st["jobs"],
                         {"queued": 1, "running": 2, "failed": 1, "done": 1})
        self.assertEqual(st["edited_records"], 1)
        self.assertEqual(st["attempts"], 1)
        self.assertEqual(st["devices"], 1)
        self.assertGreaterEqual(st["last_sync_at"], 1000)

    # ---------------- §2 任务队列 ----------------

    def test_jobs_list_states_and_fields(self):
        self.make_record("r1", title="一条记录")
        self.make_record("r2", title="另一条")
        done = self.make_job("r1", "done")
        failed = self.make_job("r1", "failed", "engine_unreachable", "连不上")
        queued = self.make_job("r2", "queued")
        transcribing = self.make_job("r2", "transcribing")

        data = self.client.get("/api/v2/jobs", headers=self.h).json()
        self.assertEqual([j["id"] for j in data["jobs"]],
                         [transcribing, queued, failed, done])
        self.assertEqual(data["counts"],
                         {"queued": 1, "running": 1, "failed": 1, "done": 1})
        first = data["jobs"][0]
        self.assertEqual(set(first), {
            "id", "record_id", "record_title", "engine", "source", "language",
            "state", "stage", "progress", "error_code", "error_hint",
            "queued_at", "started_at", "finished_at"})
        self.assertEqual(first["record_title"], "另一条")
        failed_item = [j for j in data["jobs"] if j["id"] == failed][0]
        self.assertEqual(failed_item["error_code"], "engine_unreachable")
        self.assertEqual(failed_item["error_hint"], "连不上")

        def ids(state):
            return [j["id"] for j in self.client.get(
                "/api/v2/jobs", headers=self.h, params={"state": state}).json()["jobs"]]

        self.assertEqual(ids("done"), [done])
        self.assertEqual(ids("running"), [transcribing], "running 同时包含 transcribing")
        self.assertEqual(ids("queued"), [queued])
        self.assertEqual(ids("all"), [transcribing, queued, failed, done])
        self.assertEqual(len(self.client.get(
            "/api/v2/jobs", headers=self.h, params={"limit": 1}).json()["jobs"]), 1)
        self.assertEqual(self.client.get(
            "/api/v2/jobs", headers=self.h, params={"state": "bogus"}).status_code, 400)
        # 任务详情（控制台点击跳转用）
        detail = self.client.get("/api/v2/jobs/%s" % failed, headers=self.h).json()
        self.assertEqual(detail["state"], "failed")
        self.assertEqual(self.client.get(
            "/api/v2/jobs/999999", headers=self.h).status_code, 404)

    def test_job_retry_creates_new_queued_job(self):
        self.make_record("r1", title="失败记录", status="failed",
                         error_code="engine_error", error_hint="引擎挂了")
        old_rev = self.record("r1")["rev"]
        jid = self.make_job("r1", "failed")

        r = self.client.post("/api/v2/jobs/%s/retry" % jid, headers=self.h)
        self.assertEqual(r.status_code, 200, r.text)
        new_id = r.json()["job_id"]
        self.assertNotEqual(new_id, jid, "retry 必须新建任务，不能复活原任务")

        new_job = self.client.get("/api/v2/jobs/%s" % new_id, headers=self.h).json()
        self.assertEqual(new_job["state"], "queued")
        self.assertEqual(new_job["record_id"], "r1")
        rec = self.record("r1")
        self.assertEqual(rec["status"], "queued")
        self.assertEqual(rec["error_code"], "", "retry 要清空错误码")
        self.assertEqual(rec["error_hint"], "")
        self.assertGreater(rec["rev"], old_rev)
        # 原任务保持 failed
        self.assertEqual(self.client.get(
            "/api/v2/jobs/%s" % jid, headers=self.h).json()["state"], "failed")

    def test_job_retry_rules(self):
        self.make_record("r1")
        queued = self.make_job("r1", "queued")
        running = self.make_job("r1", "running")
        canceled = self.make_job("r1", "canceled")
        done = self.make_job("r1", "done")

        self.assertEqual(self.client.post(
            "/api/v2/jobs/%s/retry" % queued, headers=self.h).status_code, 400)
        self.assertEqual(self.client.post(
            "/api/v2/jobs/%s/retry" % running, headers=self.h).status_code, 400)
        # done / failed / canceled 都允许重试
        for jid in (canceled, done):
            r = self.client.post("/api/v2/jobs/%s/retry" % jid, headers=self.h)
            self.assertEqual(r.status_code, 200, r.text)
            self.assertTrue(r.json()["job_id"])
        self.assertEqual(self.client.post(
            "/api/v2/jobs/999999/retry", headers=self.h).status_code, 404)

    def test_job_cancel_states(self):
        self.make_record("r1")
        queued = self.make_job("r1", "queued")
        r = self.client.post("/api/v2/jobs/%s/cancel" % queued, headers=self.h)
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(r.json(), {"ok": True, "state": "canceled"})
        self.assertEqual(self.client.get(
            "/api/v2/jobs/%s" % queued, headers=self.h).json()["state"], "canceled")

        running = self.make_job("r1", "running")
        r = self.client.post("/api/v2/jobs/%s/cancel" % running, headers=self.h)
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(r.json(), {"ok": True, "state": "requested"})
        self.assertEqual(int(db.conn().execute(
            "SELECT cancel_requested FROM jobs WHERE id=?", (running,)).fetchone()[0]), 1)

        # 已结束（done/failed/canceled）不能再取消
        for jid in (self.make_job("r1", "done"), self.make_job("r1", "failed"), queued):
            self.assertEqual(self.client.post(
                "/api/v2/jobs/%s/cancel" % jid, headers=self.h).status_code, 400)
        self.assertEqual(self.client.post(
            "/api/v2/jobs/999999/cancel", headers=self.h).status_code, 404)

    # ---------------- §3 记录编辑 / 墓碑 ----------------

    def test_patch_record_fields_and_rev(self):
        up = self.upload(title="原题")
        old_rev = up["rev"]
        r = self.client.patch("/api/v2/records/" + up["id"], headers=self.h,
                              json={"title": "新题", "cat": "工作", "marks": [1.5, 3]})
        self.assertEqual(r.status_code, 200, r.text)
        body = r.json()
        self.assertEqual(body["record"]["title"], "新题")
        self.assertEqual(body["record"]["cat"], "工作")
        self.assertEqual(json.loads(body["record"]["marks"]), [1.5, 3])
        self.assertEqual(body["rev"], body["record"]["rev"])
        self.assertGreater(body["rev"], old_rev, "rev 必须递增")
        self.assertEqual(body["rev"], self.latest_rev(), "rev 是全局 changes.rev")
        self.assertEqual(self.record(up["id"])["rev"], body["rev"])

        # marks 用 JSON 数组字符串；cat=null 清空；title 空串合法
        r = self.client.patch("/api/v2/records/" + up["id"], headers=self.h,
                              json={"marks": "[2, 4]", "cat": None, "title": ""})
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(r.json()["record"]["title"], "")
        self.assertIsNone(r.json()["record"]["cat"])
        self.assertEqual(json.loads(r.json()["record"]["marks"]), [2, 4])
        self.assertGreater(r.json()["rev"], body["rev"])

    def test_patch_record_noop_and_bad_input(self):
        up = self.upload(title="原题")
        rev = self.record(up["id"])["rev"]
        r = self.client.patch("/api/v2/records/" + up["id"], headers=self.h, json={})
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(r.json()["rev"], rev, "空 body 是 no-op，不 bump rev")
        self.assertEqual(self.record(up["id"])["rev"], rev)

        for payload in ({"unknown": 1}, {"title": 123}, {"cat": 123},
                        {"marks": "not-json"}, {"marks": {"a": 1}}):
            self.assertEqual(self.client.patch(
                "/api/v2/records/" + up["id"], headers=self.h,
                json=payload).status_code, 400, payload)
        self.assertEqual(self.client.patch(
            "/api/v2/records/nope", headers=self.h, json={"title": "x"}).status_code, 404)

    def test_delete_record_is_tombstone(self):
        up = self.upload(title="待删")
        audio_path = db.conn().execute(
            "SELECT audio_path FROM records WHERE id=?", (up["id"],)).fetchone()["audio_path"]
        old_rev = up["rev"]
        r = self.client.delete("/api/v2/records/" + up["id"], headers=self.h)
        self.assertEqual(r.status_code, 200, r.text)
        body = r.json()
        self.assertEqual(body["ok"], True)
        self.assertGreater(body["rev"], old_rev, "墓碑 rev 必须递增")
        rec = self.record(up["id"])
        self.assertTrue(rec["deleted"])
        self.assertIsNotNone(rec["deleted_at"])
        self.assertEqual(rec["rev"], body["rev"])
        self.assertEqual(rec["rev"], self.latest_rev())
        self.assertTrue(Path(audio_path).is_file(), "墓碑不能删除音频文件")

        ids = [x["id"] for x in self.client.get(
            "/api/v2/records", headers=self.h).json()["records"]]
        self.assertNotIn(up["id"], ids)
        ids = [x["id"] for x in self.client.get(
            "/api/v2/records", headers=self.h,
            params={"include_deleted": "true"}).json()["records"]]
        self.assertIn(up["id"], ids)
        self.assertEqual(self.client.delete(
            "/api/v2/records/nope", headers=self.h).status_code, 404)

    # ---------------- §4 修订删除 ----------------

    def test_delete_edit_restores_text(self):
        up = self.upload()
        self.add_attempt(up["id"], text="机器", activate=True)
        ed = self.client.post("/api/v2/records/%s/edits" % up["id"], headers=self.h,
                              json={"anchor_t_ms": 0, "anchor_e_ms": 500,
                                    "text": "人工"}).json()
        tr = self.client.get(
            "/api/v2/records/%s/transcript" % up["id"], headers=self.h).json()
        self.assertEqual(tr["segments"][0]["text"], "人工")

        r = self.client.delete("/api/v2/records/%s/edits/%s" % (up["id"], ed["id"]),
                               headers=self.h)
        self.assertEqual(r.status_code, 200, r.text)
        self.assertEqual(r.json(), {"ok": True})
        self.assertEqual(self.client.get(
            "/api/v2/records/%s/edits" % up["id"], headers=self.h).json()["edits"], [])
        tr = self.client.get(
            "/api/v2/records/%s/transcript" % up["id"], headers=self.h).json()
        self.assertEqual(tr["segments"][0]["text"], "机器-1", "删修订要恢复机器原文")
        # 重复删 / 记录不存在 → 404
        self.assertEqual(self.client.delete(
            "/api/v2/records/%s/edits/%s" % (up["id"], ed["id"]),
            headers=self.h).status_code, 404)
        self.assertEqual(self.client.delete(
            "/api/v2/records/nope/edits/%s" % ed["id"], headers=self.h).status_code, 404)

    def test_delete_edit_wrong_record(self):
        self.make_record("r1")
        self.make_record("r2")
        ed = self.client.post("/api/v2/records/r1/edits", headers=self.h,
                              json={"anchor_t_ms": 0, "anchor_e_ms": 100,
                                    "text": "x"}).json()
        self.assertEqual(self.client.delete(
            "/api/v2/records/r2/edits/%s" % ed["id"], headers=self.h).status_code, 404)

    # ---------------- §5 尝试详情 / 对比 ----------------

    def test_attempt_detail_fields(self):
        up = self.upload()
        a1 = self.add_attempt(up["id"], engine="fake", text="甲", activate=True)
        d = self.client.get(
            "/api/v2/records/%s/attempts/%s" % (up["id"], a1["id"]),
            headers=self.h).json()
        self.assertEqual(set(d["attempt"]), {
            "id", "engine", "model", "quality_tier", "duration_ms", "created_at"},
            "attempt 只暴露 6 个契约字段")
        self.assertEqual(d["attempt"]["engine"], "fake")
        self.assertEqual(d["attempt"]["model"], "m-1")
        self.assertEqual(d["attempt"]["quality_tier"], 2)
        self.assertTrue(d["is_active"])
        self.assertEqual(d["segments"][0],
                         {"idx": 0, "t_ms": 0, "e_ms": 500, "text": "甲-1", "spk": -1})

        a2 = self.add_attempt(up["id"], engine="strong", text="乙", activate=True)
        self.assertFalse(self.client.get(
            "/api/v2/records/%s/attempts/%s" % (up["id"], a1["id"]),
            headers=self.h).json()["is_active"])
        self.assertTrue(self.client.get(
            "/api/v2/records/%s/attempts/%s" % (up["id"], a2["id"]),
            headers=self.h).json()["is_active"])

        # 尝试不属于该记录 / 不存在 / 记录不存在 → 404
        self.make_record("r2")
        a3 = self.add_attempt("r2", engine="x", text="丙")
        for path in ("/api/v2/records/%s/attempts/%s" % (up["id"], a3["id"]),
                     "/api/v2/records/%s/attempts/nope" % up["id"],
                     "/api/v2/records/nope/attempts/%s" % a1["id"]):
            self.assertEqual(self.client.get(path, headers=self.h).status_code, 404, path)

    def test_diff_changes_and_defaults(self):
        up = self.upload()
        a1 = self.add_attempt(up["id"], engine="fake", sha="shaA", activate=True, segs=[
            {"t_ms": 0, "e_ms": 500, "text": "甲"},
            {"t_ms": 500, "e_ms": 1000, "text": "乙"},
            {"t_ms": 1000, "e_ms": 1500, "text": "丙"},
        ])
        a2 = self.add_attempt(up["id"], engine="strong", sha="shaB", segs=[
            {"t_ms": 0, "e_ms": 500, "text": "甲改"},
            {"t_ms": 500, "e_ms": 1000, "text": "乙"},
        ])

        d = self.client.get(
            "/api/v2/records/%s/diff" % up["id"], headers=self.h,
            params={"from": a1["id"], "to": a2["id"]}).json()
        self.assertEqual(d["from"], a1["id"])
        self.assertEqual(d["to"], a2["id"])
        self.assertEqual([s["change"] for s in d["segments"]],
                         ["changed", "same", "removed"])
        self.assertEqual(d["segments"][0]["from_text"], "甲")
        self.assertEqual(d["segments"][0]["to_text"], "甲改")
        self.assertEqual(d["segments"][2]["to_text"], "", "缺边 text 为空")

        rev = self.client.get(
            "/api/v2/records/%s/diff" % up["id"], headers=self.h,
            params={"from": a2["id"], "to": a1["id"]}).json()
        self.assertEqual(rev["segments"][2]["change"], "added")
        self.assertEqual(rev["segments"][2]["from_text"], "")
        self.assertEqual(rev["segments"][2]["to_text"], "丙")

        # 缺省 from/to 回落 active（仍是 a1，因为第二次尝试没有 activate）
        same = self.client.get("/api/v2/records/%s/diff" % up["id"],
                               headers=self.h).json()
        self.assertEqual(same["from"], a1["id"])
        self.assertEqual(same["to"], a1["id"])
        self.assertEqual(same["active"], a1["id"])
        self.assertTrue(all(s["change"] == "same" for s in same["segments"]))

    def test_diff_errors(self):
        up = self.upload()
        # 无 active 又缺参 → 400
        self.assertEqual(self.client.get(
            "/api/v2/records/%s/diff" % up["id"], headers=self.h).status_code, 400)

        a1 = self.add_attempt(up["id"], engine="fake", sha="s1", activate=True)
        # 尝试不存在 → 404
        self.assertEqual(self.client.get(
            "/api/v2/records/%s/diff" % up["id"], headers=self.h,
            params={"from": "nope", "to": a1["id"]}).status_code, 404)
        # 尝试不属于该记录 → 404
        self.make_record("r2")
        a2 = self.add_attempt("r2", engine="fake", sha="s2")
        self.assertEqual(self.client.get(
            "/api/v2/records/r2/diff", headers=self.h,
            params={"from": a1["id"], "to": a2["id"]}).status_code, 404)
        # 记录不存在 → 404
        self.assertEqual(self.client.get(
            "/api/v2/records/nope/diff", headers=self.h,
            params={"from": "a", "to": "b"}).status_code, 404)

    # ---------------- §6 设备与令牌 ----------------

    def test_devices_list(self):
        self.assertEqual(self.client.get(
            "/api/v2/devices", headers=self.h).json(), {"devices": []}, "空表返回空数组")
        conn = db.conn()
        with conn:
            conn.execute(
                "INSERT INTO devices(id, name, kind, app_version, last_seen_at,"
                " status, created_at) VALUES('d-old','旧手机','phone','0.9',1000,'active',900)")
            conn.execute(
                "INSERT INTO devices(id, name, kind, app_version, last_seen_at,"
                " status, created_at) VALUES('d-new','新手机','tablet','1.0',2000,'active',950)")
        ds = self.client.get("/api/v2/devices", headers=self.h).json()["devices"]
        self.assertEqual([d["id"] for d in ds], ["d-new", "d-old"])
        self.assertEqual(set(ds[0]), {"id", "name", "kind", "app_version",
                                      "last_seen_at", "status", "created_at"})
        self.assertEqual(ds[0]["kind"], "tablet")
        self.assertEqual(ds[0]["last_seen_at"], 2000)
        self.assertEqual(ds[1]["name"], "旧手机")

    def test_tokens_reset_invalidates_old(self):
        r = self.client.post("/api/v2/tokens/reset", headers=self.h)
        self.assertEqual(r.status_code, 200, r.text)
        new_token = r.json()["token"]
        self.assertTrue(new_token.startswith("vn2-"))
        self.assertNotEqual(new_token, self.token)
        # 旧令牌立即失效，新令牌可用
        self.assertEqual(self.client.get(
            "/api/v2/stats", headers=self.h).status_code, 401)
        h2 = {"Authorization": "Bearer " + new_token}
        self.assertEqual(self.client.get("/api/v2/stats", headers=h2).status_code, 200)
        row = db.conn().execute(
            "SELECT v FROM settings WHERE k='sync_token'").fetchone()
        self.assertEqual(row["v"], new_token)

    def test_console_endpoints_require_auth(self):
        cases = [
            ("get", "/api/v2/stats"),
            ("get", "/api/v2/jobs"),
            ("post", "/api/v2/jobs/1/retry"),
            ("post", "/api/v2/jobs/1/cancel"),
            ("patch", "/api/v2/records/r1"),
            ("delete", "/api/v2/records/r1"),
            ("delete", "/api/v2/records/r1/edits/e1"),
            ("get", "/api/v2/records/r1/attempts/a1"),
            ("get", "/api/v2/records/r1/diff"),
            ("get", "/api/v2/devices"),
            ("post", "/api/v2/tokens/reset"),
            ("get", "/api/v2/records"),
        ]
        for method, path in cases:
            kwargs = {"json": {}} if method == "patch" else {}
            r = getattr(self.client, method)(path, **kwargs)
            self.assertEqual(r.status_code, 401,
                             "%s %s 应要求鉴权，实际 %s" % (method, path, r.status_code))

    # ---------------- §7 记录列表筛选 ----------------

    def test_records_filters(self):
        audio = self.upload(title="会议记录 周会")   # cat NULL / status uploaded / 有音频
        self.make_record("r-done", title="项目 完成稿", recorded_at=2000,
                         status="done", cat="工作")
        self.make_record("r-nocat", title="100%完成 笔记", recorded_at=3000)
        self.make_record("r-queued", title="排队中", recorded_at=4000,
                         status="queued", cat="")

        def ids(**params):
            r = self.client.get("/api/v2/records", headers=self.h, params=params)
            self.assertEqual(r.status_code, 200, r.text)
            return [x["id"] for x in r.json()["records"]]

        self.assertEqual(ids(q="周会"), [audio["id"]])
        self.assertEqual(ids(q="r-do"), ["r-done"], "q 应同时匹配 id")
        self.assertEqual(ids(q="%"), ["r-nocat"], "q 里的 % 按普通字符匹配")
        self.assertEqual(ids(status="done"), ["r-done"])
        self.assertEqual(ids(status="queued"), ["r-queued"])
        self.assertEqual(self.client.get(
            "/api/v2/records", headers=self.h,
            params={"status": "bogus"}).status_code, 400)
        self.assertEqual(len(ids(status="")), 4, "空串 status 不筛选")
        self.assertEqual(len(ids(cat="")), 4, "空串 cat 不筛选")
        self.assertEqual(sorted(ids(cat="__none__")),
                         sorted([audio["id"], "r-nocat", "r-queued"]),
                         "__none__ = cat IS NULL 或空串")
        self.assertEqual(ids(cat="工作"), ["r-done"])
        self.assertEqual(ids(has_audio="true"), [audio["id"]])
        self.assertEqual(len(ids(has_audio="false")), 4, "has_audio=false 等同不筛选")
        self.assertEqual(ids(q="完成", cat="工作", has_audio="false"), ["r-done"])
        self.assertEqual(len(ids(limit=2)), 2)
        self.assertEqual(len(ids(limit=2, offset=2)), 2)

        # include_deleted：默认排除墓碑，置 true 可见
        self.client.delete("/api/v2/records/r-nocat", headers=self.h)
        self.assertNotIn("r-nocat", ids())
        self.assertIn("r-nocat", ids(include_deleted="true"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
