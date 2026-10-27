"""控制台 v2 可用性自检（W4 补充接口）。

覆盖：
  - 页面：/ 返回新控制台，含工作台/记录库/任务/设备等导航
  - 接口：/stats、/records 筛选、/jobs 列表、PATCH/DELETE 记录、edits 增删、
          attempts 详情、diff、/devices、/tokens/reset、transcribe+retry/cancel
全部走真实 HTTP，临时数据目录 + 假引擎，结束后清理。

用法：python scripts/console_check.py
"""
import json
import os
import shutil
import sys
import tempfile
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"
SERVER_DIR = ROOT / "server"
sys.path.insert(0, str(SCRIPTS))

from e2e_smoke import free_port, read_sync_token, start, stop, wait_http  # noqa: E402
from make_fixture_audio import gen_wav  # noqa: E402

RESULTS = []


def check(name, ok, detail=""):
    RESULTS.append((name, ok, detail))
    print("[%s] %s%s" % ("PASS" if ok else "FAIL", name,
                         "" if ok else "  " + str(detail)[:400]))


def main():
    tmp = Path(tempfile.mkdtemp(prefix="console-", dir=str(ROOT / ".tmp")))
    data = tmp / "data"
    port = free_port()
    base = "http://127.0.0.1:%d" % port
    env = os.environ.copy()
    env["VOXNOTE_DATA"] = str(data)
    env["VOXNOTE_V2_DB"] = str(data / "voxnote-v2.db")
    env["VOXNOTE_V2"] = "1"
    p, log = start([sys.executable, "run.py", "--host", "127.0.0.1", "--port", port],
                   SERVER_DIR, tmp / "server.log", env)
    engine_p = engine_log = None
    try:
        wait_http(base + "/api/v2/health")
        token = read_sync_token(data / "voxnote-v2.db")
        h = {"Authorization": "Bearer " + token}

        # ---------- 页面
        page = httpx.get(base + "/", timeout=20)
        text = page.text
        checks = ["工作台", "记录库", "任务"]
        missing = [c for c in checks if c not in text]
        check("控制台首页可加载", page.status_code == 200 and not missing,
              "status=%s missing=%s" % (page.status_code, missing))
        check("控制台含设备区", ("设备" in text) and ("令牌" in text), "")

        # ---------- 准备一条记录
        wav = gen_wav(tmp / "fixtures" / "c.wav", 4)
        up = httpx.post(base + "/api/v2/sync/upload", headers=h,
                        data={"meta": json.dumps({"title": "控制台自检",
                                                  "recorded_at": int(time.time() * 1000)},
                                                 ensure_ascii=False)},
                        files={"file": ("c.wav", wav.read_bytes(), "audio/wav")},
                        timeout=60).json()
        rid = up.get("id", "")
        check("上传记录", bool(rid), up)

        # ---------- 统计（删除前口径）
        st_before = httpx.get(base + "/api/v2/stats", headers=h, timeout=20).json()
        check("GET /stats 字段齐全",
              all(k in st_before for k in ("records", "records_today", "audio_bytes",
                                           "jobs", "edited_records", "attempts")),
              st_before)
        check("stats 记录数 >=1", int(st_before.get("records", 0)) >= 1, st_before)
        check("stats audio_bytes 只算未删除", int(st_before.get("audio_bytes", 0)) > 0,
              st_before)

        # ---------- 记录列表筛选
        rs = httpx.get(base + "/api/v2/records", headers=h, params={"q": "控制台"},
                       timeout=20).json()
        check("GET /records?q= 命中", any(r["id"] == rid for r in rs.get("records", [])), rs)
        rn = httpx.get(base + "/api/v2/records", headers=h, params={"cat": "__none__"},
                       timeout=20).json()
        check("GET /records?cat=__none__", "records" in rn, rn)
        ra = httpx.get(base + "/api/v2/records", headers=h, params={"has_audio": "true"},
                       timeout=20).json()
        check("GET /records?has_audio=true", len(ra.get("records", [])) >= 1, ra)
        rbad = httpx.get(base + "/api/v2/records", headers=h, params={"status": "bogus"},
                         timeout=20)
        check("GET /records?status=非法 → 400", rbad.status_code == 400, rbad.text[:200])

        # ---------- 记录编辑
        pr = httpx.patch(base + "/api/v2/records/" + rid, headers=h,
                         json={"title": "改名后", "cat": "工作", "marks": "[1.5]"},
                         timeout=20)
        check("PATCH /records/{id}", pr.status_code == 200, pr.text[:200])
        rec = httpx.get(base + "/api/v2/records/" + rid, headers=h, timeout=20).json()
        check("编辑生效", rec.get("title") == "改名后" and rec.get("cat") == "工作"
              and rec.get("marks") == "[1.5]", rec)
        check("PATCH rev 等于记录当前 rev",
              pr.status_code == 200 and pr.json().get("rev") == rec.get("rev"), pr.text[:200])

        # ---------- 假引擎 + 转写 + 任务列表
        eng_port = free_port()
        engine_p, engine_log = start(
            [sys.executable, SCRIPTS / "fake_engine.py", "--port", str(eng_port)],
            ROOT, tmp / "fake-engine.log")
        wait_http("http://127.0.0.1:%d/health" % eng_port)
        er = httpx.post(base + "/api/v2/engines", headers=h, json={
            "name": "自检假引擎", "url": "http://127.0.0.1:%d" % eng_port,
            "model": "fake-whisper", "quality_tier": 3,
        }, timeout=20)
        eng = er.json() if er.status_code == 200 else {}
        check("配置假引擎", bool(eng.get("id")), er.text[:200])
        engine_id = eng.get("id", "")

        tr = httpx.post(base + "/api/v2/records/" + rid + "/transcribe", headers=h,
                        json={"engine_id": engine_id} if engine_id else {}, timeout=20)
        tr_body = tr.json() if tr.status_code == 200 else {}
        check("POST transcribe", tr.status_code == 200 and bool(tr_body.get("job_id")),
              tr.text[:200])
        jid = tr_body.get("job_id")
        job = {}
        if jid:
            for _ in range(150):
                job = httpx.get(base + "/api/v2/jobs/%s" % jid, headers=h,
                                timeout=20).json()
                if job.get("state") in ("done", "failed"):
                    break
                time.sleep(0.2)
        check("转写任务完成", job.get("state") == "done", job)

        jobs = httpx.get(base + "/api/v2/jobs", headers=h, timeout=20).json()
        js = jobs.get("jobs", [])
        check("GET /jobs 列表", len(js) >= 1 and all(
            k in js[0] for k in ("id", "record_id", "record_title", "state",
                                 "progress", "error_code", "error_hint")), jobs)
        done_jobs = httpx.get(base + "/api/v2/jobs", headers=h,
                              params={"state": "done"}, timeout=20).json().get("jobs", [])
        check("GET /jobs?state=done 命中", any(j["id"] == jid for j in done_jobs),
              done_jobs)
        check("GET /jobs 非法 state → 400", httpx.get(
            base + "/api/v2/jobs", headers=h, params={"state": "bogus"},
            timeout=20).status_code == 400, "")

        # 重试（done → 新建 queued）+ 取消（queued → canceled / running → requested）
        rt = httpx.post(base + "/api/v2/jobs/%s/retry" % jid, headers=h, timeout=20)
        new_jid = rt.json().get("job_id") if rt.status_code == 200 else 0
        check("POST /jobs/{id}/retry", rt.status_code == 200 and bool(new_jid),
              rt.text[:200])
        if new_jid:
            cx = httpx.post(base + "/api/v2/jobs/%s/cancel" % new_jid, headers=h,
                            timeout=20)
            cx_body = cx.json() if cx.status_code == 200 else {}
            check("POST /jobs/{id}/cancel",
                  cx.status_code == 200
                  and cx_body.get("state") in ("canceled", "requested"), cx.text[:200])

        # ---------- 修订
        ed = httpx.post(base + "/api/v2/records/" + rid + "/edits", headers=h,
                        json={"anchor_t_ms": 0, "anchor_e_ms": 2000, "text": "人工修订"},
                        timeout=20)
        check("POST /records/{id}/edits", ed.status_code in (200, 201), ed.text[:200])
        eds = httpx.get(base + "/api/v2/records/" + rid + "/edits", headers=h,
                        timeout=20).json()
        eid = ""
        for e in eds.get("edits", []):
            if e.get("text") == "人工修订":
                eid = e.get("id", "")
        check("GET edits 命中", bool(eid), eds)
        if eid:
            de = httpx.delete(base + "/api/v2/records/%s/edits/%s" % (rid, eid),
                              headers=h, timeout=20)
            check("DELETE edit 恢复原文",
                  de.status_code == 200 and de.json().get("ok") is True, de.text[:200])
            again = httpx.delete(base + "/api/v2/records/%s/edits/%s" % (rid, eid),
                                 headers=h, timeout=20)
            check("DELETE edit 重复删 → 404", again.status_code == 404, again.text[:200])

        # ---------- 尝试与 diff
        at = httpx.post(base + "/api/v2/records/" + rid + "/attempts", headers=h,
                        json={"audio_sha256": "shaA", "source": "hub", "engine": "fake",
                              "segments": [{"t_ms": 0, "e_ms": 2000, "text": "甲"},
                                           {"t_ms": 2000, "e_ms": 4000, "text": "乙"}]},
                        timeout=20).json()
        a1 = at.get("id", "")
        at2 = httpx.post(base + "/api/v2/records/" + rid + "/attempts", headers=h,
                         json={"audio_sha256": "shaA", "source": "hub", "engine": "fake2",
                               "segments": [{"t_ms": 0, "e_ms": 2000, "text": "甲改"},
                                            {"t_ms": 2000, "e_ms": 4000, "text": "乙"}]},
                         timeout=20).json()
        a2 = at2.get("id", "")
        one = httpx.get(base + "/api/v2/records/%s/attempts/%s" % (rid, a1), headers=h,
                        timeout=20).json()
        check("GET attempt 详情",
              one.get("attempt") is not None and set(one["attempt"]) == {
                  "id", "engine", "model", "quality_tier", "duration_ms", "created_at"}
              and "segments" in one, one)
        if a1 and a2:
            df = httpx.get(base + "/api/v2/records/%s/diff" % rid, headers=h,
                           params={"from": a1, "to": a2}, timeout=20).json()
            segs = df.get("segments", [])
            check("GET attempt diff", len(segs) == 2
                  and segs[0].get("change") == "changed", df)
            # 缺省 from/to 回落 active（transcribe 完成后的尝试）
            dflt = httpx.get(base + "/api/v2/records/%s/diff" % rid, headers=h,
                             timeout=20).json()
            check("GET diff 缺省回落 active",
                  bool(dflt.get("from")) and dflt.get("from") == dflt.get("to"), dflt)
            av = httpx.post(base + "/api/v2/records/" + rid + "/active-attempt",
                            headers=h, json={"attempt_id": a2}, timeout=20)
            check("POST active-attempt", av.status_code == 200, av.text[:200])

        # ---------- 设备与令牌
        dv = httpx.get(base + "/api/v2/devices", headers=h, timeout=20).json()
        check("GET /devices", isinstance(dv.get("devices"), list), dv)
        tk = httpx.post(base + "/api/v2/tokens/reset", headers=h, timeout=20).json()
        newtok = tk.get("token", "")
        check("POST /tokens/reset 返回新令牌", bool(newtok) and newtok != token, tk)
        check("旧令牌失效", httpx.get(base + "/api/v2/stats", headers=h,
                                      timeout=20).status_code == 401, "")
        h2 = {"Authorization": "Bearer " + newtok}
        check("新令牌可用", httpx.get(base + "/api/v2/stats", headers=h2,
                                      timeout=20).status_code == 200, "")

        # ---------- 墓碑 + 删除后 stats 口径
        de = httpx.delete(base + "/api/v2/records/" + rid, headers=h2, timeout=20)
        check("DELETE /records/{id}", de.status_code == 200, de.text[:200])
        after = httpx.get(base + "/api/v2/records/" + rid, headers=h2, timeout=20).json()
        check("删除是墓碑", after.get("deleted") is True, after)
        st_after = httpx.get(base + "/api/v2/stats", headers=h2, timeout=20).json()
        check("stats 只算未删除",
              int(st_after.get("records", -1)) == int(st_before.get("records", 0)) - 1
              and int(st_after.get("deleted", -1)) == int(st_before.get("deleted", 0)) + 1
              and int(st_after.get("audio_bytes", -1)) == 0, st_after)
    finally:
        if engine_p is not None:
            stop(engine_p, engine_log)
        stop(p, log)
        shutil.rmtree(str(tmp), ignore_errors=True)

    bad = [r for r in RESULTS if not r[1]]
    print("\n控制台自检：%s（%d/%d）" % ("PASS" if not bad else "FAIL",
                                       len(RESULTS) - len(bad), len(RESULTS)))
    return 0 if not bad else 1


if __name__ == "__main__":
    sys.exit(main())
