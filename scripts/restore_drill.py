"""W6 备份恢复 + 回滚演练。

步骤：
  1. v2 服务端起临时实例，上传并转写一条；
  2. POST /backups 生成备份；
  3. 停服务，把备份文件恢复为 v2 主库（音频目录保留）；
  4. 重启，校验记录/转写/音频都在；
  5. 用 VOXNOTE_V2=0 回滚启动 v1 路径，确认旧控制台与 v1 数据仍在。

用法：python scripts/restore_drill.py
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

from e2e_smoke import free_port, read_sync_token, start, stop  # noqa: E402
from make_fixture_audio import gen_wav  # noqa: E402


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


def start_server(tmp, data_dir, v2=True):
    port = free_port()
    base = "http://127.0.0.1:%d" % port
    env = os.environ.copy()
    env["VOXNOTE_DATA"] = str(data_dir)
    env["VOXNOTE_V2_DB"] = str(data_dir / "voxnote-v2.db")
    env["VOXNOTE_V2"] = "1" if v2 else "0"
    log_path = tmp / ("server-%s-%d.log" % ("v2" if v2 else "v1", port))
    p, log = start([sys.executable, "run.py", "--host", "127.0.0.1", "--port", port],
                   SERVER_DIR, log_path, env)
    if v2:
        wait_http(base + "/api/v2/health")
    else:
        wait_http(base + "/api/v1/health")
    return p, log, base


def main():
    tmp = Path(tempfile.mkdtemp(prefix="restore-", dir=str(ROOT / ".tmp")))
    data = tmp / "data"
    results = []

    def check(name, ok, detail=""):
        results.append((name, ok, detail))
        print("[%s] %s%s" % ("PASS" if ok else "FAIL", name,
                             "" if ok else "  " + str(detail)[:300]))

    p = log = None
    try:
        p, log, base = start_server(tmp, data, v2=True)
        token = read_sync_token(data / "voxnote-v2.db")
        h = {"Authorization": "Bearer " + token}

        wav = gen_wav(tmp / "fixtures" / "r.wav", 4)
        r = httpx.post(base + "/api/v2/sync/upload", headers=h,
                       data={"meta": json.dumps({"title": "恢复演练", "recorded_at": 1000},
                                                ensure_ascii=False)},
                       files={"file": ("r.wav", wav.read_bytes(), "audio/wav")},
                       timeout=60).json()
        rid = r["id"]
        eng = httpx.post(base + "/api/v2/engines", headers=h, json={
            "name": "fake", "url": "http://127.0.0.1:9", "model": "fake"}).json()
        # 引擎不可达也没关系：恢复演练只关心数据结构；这里直接写一条尝试
        ar = httpx.post(base + "/api/v2/records/%s/attempts" % rid, headers=h, json={
            "audio_sha256": "restore-sha", "source": "hub", "engine": "fake",
            "segments": [{"t_ms": 0, "e_ms": 4000, "text": "恢复前的内容"}]})
        check("准备数据", ar.status_code == 200, ar.text[:200])

        bk = httpx.post(base + "/api/v2/backups", headers=h, timeout=60).json()
        check("生成备份", bool(bk.get("path")) and Path(bk["path"]).is_file(), str(bk))
        backup = Path(bk["path"])

        stop(p, log)
        p = log = None

        # 恢复：备份文件覆盖 v2 主库，清理 WAL/SHM（模拟换库）
        main_db = data / "voxnote-v2.db"
        for suffix in ("-wal", "-shm"):
            try:
                (data / ("voxnote-v2.db" + suffix)).unlink()
            except OSError:
                pass
        shutil.copy(str(backup), str(main_db))
        check("恢复备份文件", main_db.is_file() and main_db.stat().st_size > 0, "")

        p, log, base = start_server(tmp, data, v2=True)
        token2 = read_sync_token(data / "voxnote-v2.db")
        h2 = {"Authorization": "Bearer " + token2}
        rec = httpx.get(base + "/api/v2/records/%s" % rid, headers=h2, timeout=20)
        check("恢复后记录在", rec.status_code == 200 and "恢复演练" in rec.text,
              rec.text[:200])
        tr = httpx.get(base + "/api/v2/records/%s/transcript" % rid, headers=h2,
                       timeout=20).json()
        check("恢复后转写在", any("恢复前的内容" in s.get("text", "")
                                  for s in tr.get("segments", [])), str(tr)[:200])
        audio = httpx.get(base + "/api/v2/records/%s/audio" % rid, headers=h2, timeout=20)
        check("恢复后音频在", audio.status_code == 200 and len(audio.content) > 1000,
              "len=%s" % len(audio.content))

        # 回滚：VOXNOTE_V2=0 → v1 路径恢复，旧控制台与 v1 库仍在
        stop(p, log)
        p = log = None
        p, log, base = start_server(tmp, data, v2=False)
        root = httpx.get(base + "/", timeout=20)
        check("回滚后旧控制台", root.status_code == 200 and "v2 控制台" not in root.text,
              "status=%s" % root.status_code)
        v1h = httpx.get(base + "/api/v1/health", timeout=20)
        check("回滚后 v1 健康", v1h.status_code == 200, v1h.text[:200])
        check("v1 旧库未动", (data / "voxnote.db").exists(), "")
    finally:
        if p is not None:
            stop(p, log)
        shutil.rmtree(str(tmp), ignore_errors=True)

    ok = all(x[1] for x in results)
    print("\n恢复/回滚演练：%s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
