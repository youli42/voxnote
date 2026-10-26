"""W6 性能基准：1k 记录 / 1 小时音频（临时库，不碰真实数据）。

用法：
    python scripts/bench.py
    python scripts/bench.py --records 1000 --hour 1 --keep

预算（见 docs/设计/架构设计.md §12）：
    列表冷启动 <1s；详情 <300ms；增量拉取 <300ms/轮。
"""
import argparse
import hashlib
import os
import shutil
import sys
import tempfile
import time
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER = ROOT / "server"
sys.path.insert(0, str(SERVER))


def bench_records(n: int):
    os.environ["VOXNOTE_V2_DB"] = str(Path(os.environ["BENCH_TMP"]) / "bench.db")
    from voxnote_server.v2 import db, manifest, service

    db.reset()
    conn = db.conn()
    t0 = time.perf_counter()
    for i in range(n):
        service.create_record(conn, {
            "id": "r%06d" % i, "title": "记录 %d" % i,
            "recorded_at": 1700000000000 + i, "dur_ms": 30000,
            "status": "done", "active_attempt_id": "a%d" % i,
        })
    create_s = time.perf_counter() - t0

    # 列表（limit 50，模拟打开 App）
    t0 = time.perf_counter()
    for _ in range(20):
        service.list_records(conn, limit=50)
    list_ms = (time.perf_counter() - t0) / 20 * 1000

    # 详情/单条
    t0 = time.perf_counter()
    for _ in range(100):
        service.get_record(conn, "r000500")
    detail_ms = (time.perf_counter() - t0) / 100 * 1000

    # manifest 全量分页
    t0 = time.perf_counter()
    cursor, pages, items = "", 0, 0
    while True:
        page = manifest.build(conn, cursor=cursor, limit=500)
        items += len(page["items"])
        pages += 1
        cursor = page["next_cursor"]
        if not page["has_more"]:
            break
    manifest_ms = (time.perf_counter() - t0) * 1000

    # changes 全量分页
    t0 = time.perf_counter()
    cursor, pages = 0, 0
    while True:
        page = service.changes_since(conn, cursor=cursor, limit=200)
        pages += 1
        cursor = page["next_cursor"]
        if not page["has_more"]:
            break
    changes_ms = (time.perf_counter() - t0) * 1000
    db.reset()
    return {
        "create_1k_s": create_s,
        "list_ms": list_ms,
        "detail_ms": detail_ms,
        "manifest_ms": manifest_ms,
        "manifest_items": items,
        "manifest_pages": pages,
        "changes_ms": changes_ms,
        "changes_pages": pages,
    }


def bench_audio(hours: float, tmp: Path):
    """生成 1 小时 16k/mono WAV：测落盘 + sha256 吞吐（上传前的本地成本）。"""
    path = tmp / "one-hour.wav"
    rate = 16000
    frames = int(rate * 3600 * hours)
    chunk = b"\x00\x00" * rate  # 1 秒
    t0 = time.perf_counter()
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        for _ in range(int(3600 * hours)):
            w.writeframes(chunk)
    write_s = time.perf_counter() - t0

    t0 = time.perf_counter()
    h = hashlib.sha256()
    with open(str(path), "rb") as f:
        while True:
            b = f.read(1 << 20)
            if not b:
                break
            h.update(b)
    sha_s = time.perf_counter() - t0
    return {"audio_mb": path.stat().st_size / 1024 / 1024,
            "write_s": write_s, "sha256_s": sha_s}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--records", type=int, default=1000)
    ap.add_argument("--hour", type=float, default=1.0)
    ap.add_argument("--keep", action="store_true")
    args = ap.parse_args()

    tmp = Path(tempfile.mkdtemp(prefix="bench-", dir=str(ROOT / ".tmp")))
    os.environ["BENCH_TMP"] = str(tmp)
    try:
        rec = bench_records(args.records)
        aud = bench_audio(args.hour, tmp)
    finally:
        shutil.rmtree(str(tmp), ignore_errors=True)
        if args.keep:
            print("（--keep 仍清理临时库，避免残留大文件）")

    print("== 记录 %d 条 ==" % args.records)
    print("  建库: %.1fs" % rec["create_1k_s"])
    print("  列表(50): %.1f ms  (预算 <1000)" % rec["list_ms"])
    print("  详情: %.1f ms  (预算 <300)" % rec["detail_ms"])
    print("  manifest %d 页/%d 条: %.1f ms" % (
        rec["manifest_pages"], rec["manifest_items"], rec["manifest_ms"]))
    print("  changes %d 页: %.1f ms" % (rec["changes_pages"], rec["changes_ms"]))
    print("== 音频 %.1f 小时 ==" % args.hour)
    print("  文件: %.1f MB" % aud["audio_mb"])
    print("  写入: %.1fs" % aud["write_s"])
    print("  sha256: %.1fs" % aud["sha256_s"])

    ok = (rec["list_ms"] < 1000 and rec["detail_ms"] < 300
          and rec["changes_ms"] < 300)
    print("\n预算检查：%s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
