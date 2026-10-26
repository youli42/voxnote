"""运行环境自检（M0 安全网）。

检查：Python 版本、关键依赖、ffmpeg/ffprobe、数据目录可写、空闲端口。
用法：
    python scripts/selftest.py
    python scripts/selftest.py --strict   # 警告也算失败
"""
import argparse
import importlib
import shutil
import socket
import sys
import tempfile


def check_python():
    v = sys.version_info
    ok = v >= (3, 10)
    warn = v < (3, 12)
    detail = "%d.%d.%d" % (v.major, v.minor, v.micro)
    if warn:
        detail += "（README 建议 3.12+，当前可运行但请留意依赖）"
    return "Python", ok, warn, detail


def check_import(mod: str):
    try:
        m = importlib.import_module(mod)
        ver = getattr(m, "__version__", "")
        return mod, True, False, ver or "ok"
    except Exception as e:
        return mod, False, False, str(e)


def check_cmd(name: str):
    path = shutil.which(name)
    return name, bool(path), False, path or "未找到（音频转码/时长探测会受影响）"


def check_writable():
    try:
        with tempfile.NamedTemporaryFile(prefix="voxnote-selftest-", delete=True) as f:
            f.write(b"ok")
        return "临时目录可写", True, False, tempfile.gettempdir()
    except Exception as e:
        return "临时目录可写", False, False, str(e)


def check_port():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        return "空闲端口", True, False, "127.0.0.1:%d" % port
    except Exception as e:
        return "空闲端口", False, False, str(e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true", help="警告也视为失败")
    args = ap.parse_args()

    results = [check_python(), check_writable(), check_port()]
    for mod in ("fastapi", "uvicorn", "httpx", "multipart"):
        results.append(check_import(mod))
    for cmd in ("ffmpeg", "ffprobe"):
        results.append(check_cmd(cmd))

    hard_fail = 0
    warns = 0
    print("voxnote 运行环境自检")
    print("-" * 64)
    for name, ok, warn, detail in results:
        if not ok:
            mark = "FAIL"
            hard_fail += 1
        elif warn:
            mark = "WARN"
            warns += 1
        else:
            mark = " OK "
        print("[%s] %-18s %s" % (mark, name, detail))
    print("-" * 64)
    print("结果：%d 项失败，%d 项警告" % (hard_fail, warns))
    if hard_fail or (args.strict and warns):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
