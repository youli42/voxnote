"""W6 验收：5 个任务脚本（真实服务端 + 假引擎，临时数据目录）。

用法：
    python scripts/acceptance.py
    python scripts/acceptance.py --keep

脚本：
  1. 新用户：配对 → 上传 → 转写 → 读稿 → 导出 → 音频下载
  2. 断网/恢复：幂等重放、游标续传、服务重启后数据仍在
  3. 失败：引擎不可达 → job failed + 记录 error_code，不静默
  4. 一致性：changes 与 manifest 对账（分页、墓碑、sync_hash）
  5. 多来源：多个尝试并存、切指针、修订不丢
"""
import argparse
import json
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

from e2e_smoke import free_port, read_sync_token, start, stop, tail  # noqa: E402
from make_fixture_audio import gen_wav  # noqa: E402


class Ctx:
    def __init__(self, tmp: Path):
        self.tmp = tmp
        self.server = None
        self.server_log = None
        self.engine = None
        self.engine_log = None
        self.port = 0
        self.base = ""
        self.data_dir = tmp / "data"
        self.token = ""
        self.h = {}

    def start_engine(self):
        port = free_port()
        self.engine_log = self.tmp / "engine.log"
        self.engine, self.engine_log = start(
            [sys.executable, SCRIPTS / "fake_engine.py", "--port", port],
            ROOT, self.engine_log)
        wait_http("http://127.0.0.1:%d/health" % port)
        return port

    def start_server(self):
        self.port = free_port()
        self.base = "http://127.0.0.1:%d" % self.port
        env = __import__("os").environ.copy()
        env["VOXNOTE_DATA"] = str(self.data_dir)
        env["VOXNOTE_V2"] = "1"
        env["VOXNOTE_V2_DB"] = str(self.data_dir / "voxnote-v2.db")
        self.server_log = self.tmp / ("server-%d.log" % self.port)
        self.server, self.server_log = start(
            [sys.executable, "run.py", "--host", "127.0.0.1", "--port", self.port],
            SERVER_DIR, self.server_log, env)
        wait_http(self.base + "/api/v2/health")
        self.token = read_sync_token(self.data_dir / "voxnote-v2.db")
        self.h = {"Authorization": "Bearer " + self.token}

    def stop_server(self):
        if self.server is not None:
            stop(self.server, self.server_log)
            self.server = None

    def stop_all(self):
        self.stop_server()
        if self.engine is not None:
            stop(self.engine, self.engine_log)
            self.engine = None

    # --- http helpers ---
    def get(self, path, **kw):
        return httpx.get(self.base + path, headers=self.h, timeout=30, **kw)

    def post(self, path, **kw):
        return httpx.post(self.base + path, headers=self.h, timeout=60, **kw)

    def upload(self, name: str, audio: bytes, recorded_at=1700000000000):
        meta = {"stamp": name, "title": name, "recorded_at": recorded_at,
                "dur_ms": 4000}
        r = self.post("/api/v2/sync/upload",
                      data={"meta": json.dumps(meta, ensure_ascii=False)},
                      files={"file": (name + ".wav", audio, "audio/wav")})
        if r.status_code != 200:
            raise RuntimeError("上传失败：%s %s" % (r.status_code, r.text[:300]))
        return r.json()

    def add_engine(self, name="fake", url=None):
        r = self.post("/api/v2/engines", json={
            "name": name, "url": url or ("http://127.0.0.1:%d" % self.engine_port),
            "model": "fake-whisper", "quality_tier": 3,
        })
        if r.status_code != 200:
            raise RuntimeError("引擎配置失败：%s %s" % (r.status_code, r.text[:300]))
        return r.json()

    def transcribe(self, rid, engine_id):
        r = self.post("/api/v2/records/%s/transcribe" % rid,
                      json={"engine_id": engine_id})
        if r.status_code != 200:
            raise RuntimeError("转写入队失败：%s %s" % (r.status_code, r.text[:300]))
        return r.json()

    def wait_job(self, jid, timeout=30):
        end = time.time() + timeout
        state = ""
        while time.time() < end:
            j = self.get("/api/v2/jobs/%s" % jid).json()
            state = j.get("state", "")
            if state in ("done", "failed"):
                return j
            time.sleep(0.2)
        raise RuntimeError("job 超时：%s" % jid)


def wait_http(url, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        try:
            if httpx.get(url, timeout=1).status_code == 200:
                return
        except Exception:
            pass
        time.sleep(0.2)
    raise RuntimeError("等待超时：" + url)


# ---------------------------------------------------------------- 场景

def s1_new_user(ctx):
    out = []
    up = ctx.upload("新用户", gen_wav(ctx.tmp / "fixtures" / "a.wav", 4).read_bytes())
    out.append(("上传", bool(up.get("id")), str(up)))
    eng = ctx.add_engine()
    sub = ctx.transcribe(up["id"], eng["id"])
    job = ctx.wait_job(sub["job_id"])
    out.append(("转写完成", job.get("state") == "done", str(job)))

    tr = ctx.get("/api/v2/records/%s/transcript" % up["id"]).json()
    segs = tr.get("segments", [])
    out.append(("读到稿", len(segs) > 0 and "这是第 1 句" in segs[0].get("text", ""),
                str(tr)[:300]))

    srt = ctx.get("/api/v2/records/%s/export?format=srt" % up["id"])
    out.append(("导出 srt", srt.status_code == 200 and "-->" in srt.text,
                srt.text[:200]))

    audio = ctx.get("/api/v2/records/%s/audio" % up["id"])
    out.append(("音频可下", audio.status_code == 200 and len(audio.content) > 1000,
                "len=%s" % len(audio.content)))
    return out


def s2_offline_recovery(ctx):
    out = []
    audio = gen_wav(ctx.tmp / "fixtures" / "b.wav", 4).read_bytes()

    # 幂等：同 Idempotency-Key 重复上传不产生副本
    meta = {"stamp": "offline", "title": "断网", "recorded_at": 1700000000000}
    hh = dict(ctx.h, **{"Idempotency-Key": "accept-offline-1"})
    r1 = httpx.post(ctx.base + "/api/v2/sync/upload", headers=hh,
                    data={"meta": json.dumps(meta)}, files={"file": ("x.wav", audio, "audio/wav")},
                    timeout=60).json()
    r2 = httpx.post(ctx.base + "/api/v2/sync/upload", headers=hh,
                    data={"meta": json.dumps(meta)}, files={"file": ("x.wav", audio, "audio/wav")},
                    timeout=60).json()
    out.append(("上传幂等", r1.get("id") == r2.get("id") and r2.get("duplicate") is True,
                "%s %s" % (r1, r2)))

    # 游标续传：先拿游标，再新增，增量能拿到
    ch0 = ctx.get("/api/v2/sync/changes?cursor=0&limit=10").json()
    cur = ch0["next_cursor"]
    up = ctx.upload("断网新增", gen_wav(ctx.tmp / "fixtures" / "c.wav", 3).read_bytes(),
                    recorded_at=1700000100000)
    ch1 = ctx.get("/api/v2/sync/changes?cursor=%d&limit=10" % cur).json()
    ids = [x.get("record", {}).get("id") for x in ch1["changes"] if x.get("kind") == "record"]
    out.append(("游标续传", up["id"] in ids, str(ids)))
    out.append(("游标不重复", ch1["next_cursor"] >= cur and ch1.get("resync_required") is False,
                str(ch1)[:200]))

    # 服务重启后数据仍在（断网/崩溃恢复的底线）
    before = ctx.get("/api/v2/records?limit=500").json()["records"]
    ctx.stop_server()
    ctx.start_server()
    after = ctx.get("/api/v2/records?limit=500").json()["records"]
    out.append(("重启后数据在", len(after) == len(before) and len(after) > 0,
                "%d -> %d" % (len(before), len(after))))
    return out


def s3_failure(ctx):
    out = []
    up = ctx.upload("失败场景", gen_wav(ctx.tmp / "fixtures" / "d.wav", 3).read_bytes())
    dead = free_port()  # 没有服务监听
    eng = ctx.add_engine(name="dead", url="http://127.0.0.1:%d" % dead)
    sub = ctx.transcribe(up["id"], eng["id"])
    job = ctx.wait_job(sub["job_id"])
    out.append(("job 失败", job.get("state") == "failed", str(job)))
    out.append(("失败有错误码", bool(job.get("error_code")), str(job)))

    rec = ctx.get("/api/v2/records/%s" % up["id"]).json()
    out.append(("记录标记失败", rec.get("status") == "failed"
                and rec.get("error_code") == "engine_error", str(rec)))
    return out


def s4_consistency(ctx):
    out = []
    for i in range(3):
        ctx.upload("一致性%d" % i, gen_wav(ctx.tmp / "fixtures" / ("e%d.wav" % i), 2).read_bytes(),
                   recorded_at=1700000200000 + i)
    # manifest 分页收集
    seen, cursor = [], ""
    while True:
        page = ctx.get("/api/v2/sync/manifest?limit=2&cursor=%s" % cursor).json()
        seen += [x["id"] for x in page.get("items", [])]
        cursor = page.get("next_cursor", "")
        if not page.get("has_more"):
            break
    out.append(("manifest 分页", len(seen) == 3 and len(set(seen)) == 3, str(seen)))

    # changes 全量收集
    ids, cur = [], 0
    while True:
        page = ctx.get("/api/v2/sync/changes?cursor=%d&limit=2" % cur).json()
        ids += [x["record"]["id"] for x in page["changes"] if x.get("kind") == "record"]
        cur = page["next_cursor"]
        if not page.get("has_more"):
            break
    out.append(("changes 与 manifest 对账", set(ids) == set(seen),
                "changes=%s manifest=%s" % (ids, seen)))

    # 墓碑：删除后 manifest deleted=true
    target = seen[0]
    rec = ctx.get("/api/v2/records/%s" % target).json()
    r = ctx.post("/api/v2/sync/ops", json={"ops": [{
        "op_id": "accept-del-1", "kind": "tombstone_record",
        "record_id": target, "base_rev": rec["rev"]}]})
    out.append(("删除操作", r.status_code == 200, r.text[:200]))
    page = ctx.get("/api/v2/sync/manifest?limit=50").json()
    item = [x for x in page["items"] if x["id"] == target]
    out.append(("manifest 墓碑", bool(item) and item[0]["deleted"] is True, str(item)))
    return out


def s5_multi_source(ctx):
    out = []
    up = ctx.upload("多来源", gen_wav(ctx.tmp / "fixtures" / "f.wav", 4).read_bytes())
    eng = ctx.add_engine()
    sub = ctx.transcribe(up["id"], eng["id"])
    ctx.wait_job(sub["job_id"])
    attempts = ctx.get("/api/v2/records/%s/attempts" % up["id"]).json()["attempts"]
    out.append(("引擎尝试", len(attempts) >= 1, str(attempts)))

    # 手工再加一个"强机"尝试并切过去
    r = ctx.post("/api/v2/records/%s/attempts" % up["id"], json={
        "audio_sha256": "strong-sha", "source": "remote", "engine": "strong",
        "quality_tier": 3, "segments": [{"t_ms": 0, "e_ms": 4000, "text": "强机精稿"}],
        "activate": True,
    })
    strong = r.json()
    tr = ctx.get("/api/v2/records/%s/transcript" % up["id"]).json()
    out.append(("切到强机稿", tr.get("attempt", {}).get("id") == strong.get("id"),
                str(tr)[:300]))

    # 修订后切回旧尝试，修订仍在（作用于时间区间，不绑尝试）
    ctx.post("/api/v2/records/%s/edits" % up["id"], json={
        "anchor_t_ms": 0, "anchor_e_ms": 4000, "text": "人工修订"})
    ctx.post("/api/v2/records/%s/active-attempt" % up["id"],
             json={"attempt_id": attempts[0]["id"]})
    tr = ctx.get("/api/v2/records/%s/transcript" % up["id"]).json()
    out.append(("切回旧尝试修订仍在",
                tr["segments"] and tr["segments"][0]["text"] == "人工修订",
                str(tr)[:300]))
    out.append(("旧尝试保留", len(ctx.get("/api/v2/records/%s/attempts" % up["id"])
                                  .json()["attempts"]) >= 2, ""))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()
    base = ROOT / ".tmp"
    base.mkdir(exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix="accept-", dir=str(base)))
    ctx = Ctx(tmp)
    results = []
    try:
        ctx.engine_port = ctx.start_engine()
        ctx.start_server()
        scenarios = [
            ("1 新用户", s1_new_user),
            ("2 断网/恢复", s2_offline_recovery),
            ("3 失败", s3_failure),
            ("4 一致性", s4_consistency),
            ("5 多来源", s5_multi_source),
        ]
        for name, fn in scenarios:
            try:
                rows = fn(ctx)
            except Exception as e:
                rows = [("场景异常", False, str(e))]
            ok = all(r[1] for r in rows)
            results.append((name, ok, rows))
            print(("== %s: %s" % (name, "PASS" if ok else "FAIL")))
            for label, good, detail in rows:
                print("  [%s] %s%s" % ("PASS" if good else "FAIL", label,
                                       "" if good else "  " + str(detail)[:300]))
    finally:
        ctx.stop_all()
        if args.keep:
            print("临时目录保留：%s" % tmp)
        else:
            shutil.rmtree(str(tmp), ignore_errors=True)

    print("\n== 验收汇总 ==")
    all_ok = True
    for name, ok, _ in results:
        print("%s  %s" % ("PASS" if ok else "FAIL", name))
        all_ok = all_ok and ok
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main())
