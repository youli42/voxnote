"""M0 端到端冒烟：验证开发基座能跑通。

覆盖：
  A. 假引擎：/health、verbose_json、text 响应；
  B. 现有 v1 服务端：临时数据目录启动、健康检查、上传一条录音、记录可查。

用法：
    python scripts/e2e_smoke.py
    python scripts/e2e_smoke.py --keep          # 保留临时目录与日志
    python scripts/e2e_smoke.py --skip-server   # 只测假引擎

设计约束：
  - 子进程输出写日志文件，不用管道（Windows 沙箱下管道可能被限制）；
  - 不碰真实数据：服务端用 VOXNOTE_DATA 指向 .tmp 临时目录；
  - 失败时打印日志尾部，方便定位。
"""
import argparse
import json
import os
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
SCRIPTS = ROOT / "scripts"
SERVER_DIR = ROOT / "server"
sys.path.insert(0, str(SCRIPTS))

from make_fixture_audio import gen_wav  # noqa: E402


def free_port() -> int:
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def wait_http(url: str, timeout: float = 30.0) -> httpx.Response:
    last = ""
    end = time.time() + timeout
    while time.time() < end:
        try:
            r = httpx.get(url, timeout=1.0)
            if r.status_code == 200:
                return r
            last = "HTTP %d" % r.status_code
        except Exception as e:
            last = str(e)
        time.sleep(0.2)
    raise RuntimeError("等待 %s 超时：%s" % (url, last))


def start(cmd, cwd: Path, log_path: Path, env=None):
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log = open(str(log_path), "wb")
    p = subprocess.Popen(
        [str(x) for x in cmd],
        cwd=str(cwd),
        stdout=log,
        stderr=subprocess.STDOUT,
        env=env,
    )
    return p, log


def stop(p, log, timeout: float = 5.0):
    try:
        if p.poll() is None:
            p.terminate()
            try:
                p.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                p.kill()
                p.wait(timeout=timeout)
    finally:
        try:
            log.close()
        except Exception:
            pass


def tail(path: Path, lines: int = 25) -> str:
    try:
        data = path.read_text("utf-8", errors="replace").splitlines()
        return "\n".join(data[-lines:])
    except Exception:
        return "(无日志)"


def check_fake_engine(tmp: Path) -> list:
    results = []
    port = free_port()
    log_path = tmp / "fake-engine.log"
    p, log = start([sys.executable, SCRIPTS / "fake_engine.py", "--port", port], ROOT, log_path)
    try:
        wait_http("http://127.0.0.1:%d/health" % port)
        results.append(("假引擎 /health", True, ""))

        wav = gen_wav(tmp / "fixtures" / "tone-4s.wav", 4.0)
        files = {"file": ("tone-4s.wav", wav.read_bytes(), "audio/wav")}
        data = {"model": "fake-whisper", "language": "zh", "response_format": "verbose_json"}
        r = httpx.post("http://127.0.0.1:%d/v1/audio/transcriptions" % port,
                       files=files, data=data, timeout=20)
        body = r.json()
        ok = r.status_code == 200 and body.get("segments") and "第 1 句" in body.get("text", "")
        results.append(("假引擎 verbose_json", ok,
                        "" if ok else "status=%s body=%s" % (r.status_code, body)))

        files = {"file": ("tone-4s.wav", wav.read_bytes(), "audio/wav")}
        r2 = httpx.post("http://127.0.0.1:%d/v1/audio/transcriptions" % port,
                        files=files, data={"response_format": "text"}, timeout=20)
        ok2 = r2.status_code == 200 and "第 1 句" in r2.text
        results.append(("假引擎 text 响应", ok2, "" if ok2 else r2.text[:200]))
    finally:
        stop(p, log)
    if not all(x[1] for x in results):
        print("[fake-engine log]\n" + tail(log_path))
    return results


def read_sync_token(db_path: Path, timeout: float = 20.0) -> str:
    last = ""
    end = time.time() + timeout
    while time.time() < end:
        if db_path.exists():
            try:
                conn = sqlite3.connect(str(db_path), timeout=5)
                row = conn.execute("SELECT v FROM settings WHERE k='sync_token'").fetchone()
                conn.close()
                if row and row[0]:
                    return row[0]
            except Exception as e:
                last = str(e)
        time.sleep(0.2)
    raise RuntimeError("读取 sync_token 失败：%s" % last)


def disable_auto_transcribe(db_path: Path):
    conn = sqlite3.connect(str(db_path), timeout=5)
    conn.execute("INSERT OR REPLACE INTO settings(k, v) VALUES('auto_transcribe','0')")
    conn.commit()
    conn.close()


def check_server(tmp: Path) -> list:
    results = []
    port = free_port()
    data_dir = tmp / "server-data"
    env = os.environ.copy()
    env["VOXNOTE_DATA"] = str(data_dir)
    log_path = tmp / "server.log"
    p, log = start([sys.executable, "run.py", "--host", "127.0.0.1", "--port", port],
                   SERVER_DIR, log_path, env)
    try:
        wait_http("http://127.0.0.1:%d/api/v1/health" % port)
        results.append(("v1 服务端启动", True, ""))

        db = data_dir / "voxnote.db"
        token = read_sync_token(db)
        disable_auto_transcribe(db)

        wav = gen_wav(tmp / "fixtures" / "tone-4s.wav", 4.0)
        meta = {
            "stamp": "20261026_170000", "title": "e2e 冒烟", "device": "e2e",
            "recorded_at": 1793000000000, "dur_ms": 4000,
        }
        with open(str(wav), "rb") as f:
            r = httpx.post(
                "http://127.0.0.1:%d/api/v1/sync/upload" % port,
                headers={"Authorization": "Bearer " + token},
                data={"meta": json.dumps(meta, ensure_ascii=False)},
                files={"file": ("tone-4s.wav", f, "audio/wav")},
                timeout=60,
            )
        ok_upload = r.status_code == 200 and r.json().get("id")
        results.append(("上传录音", bool(ok_upload), "" if ok_upload else r.text[:300]))

        if ok_upload:
            rid = r.json()["id"]
            lst = httpx.get("http://127.0.0.1:%d/api/v1/records" % port, timeout=20).json()
            ids = [x["id"] for x in lst.get("records", [])]
            results.append(("记录可查", rid in ids, "" if rid in ids else "ids=%s" % ids))
    finally:
        stop(p, log)
    if not all(x[1] for x in results):
        print("[server log]\n" + tail(log_path, 40))
    return results


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="保留临时目录与日志")
    ap.add_argument("--skip-server", action="store_true")
    ap.add_argument("--skip-engine", action="store_true")
    args = ap.parse_args()

    base = ROOT / ".tmp"
    base.mkdir(exist_ok=True)
    tmp = Path(tempfile.mkdtemp(prefix="e2e-", dir=str(base)))

    results = []
    failed = False
    try:
        if not args.skip_engine:
            results += check_fake_engine(tmp)
        if not args.skip_server:
            results += check_server(tmp)
    except Exception as e:
        print("[FATAL] %s" % e)
        failed = True
    finally:
        print("\n== 冒烟结果 ==")
        for name, ok, detail in results:
            print("[%s] %s%s" % ("PASS" if ok else "FAIL", name, ("" if ok else "  " + detail)))
        failed = failed or not all(x[1] for x in results)
        if args.keep:
            print("临时目录保留：%s" % tmp)
        else:
            shutil.rmtree(str(tmp), ignore_errors=True)

    if failed:
        print("\n冒烟失败")
        return 1
    print("\n冒烟通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
