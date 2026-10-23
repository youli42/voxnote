"""voxnote server：录音同步、转写、整理与 AI 对接的自托管服务端。

用法：
    python run.py [--host 0.0.0.0] [--port 8787] [--data ./data]
"""
import argparse
import socket

import uvicorn


def pick_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def main():
    ap = argparse.ArgumentParser(description="voxnote server")
    ap.add_argument("--host", default="127.0.0.1",
                    help="监听地址；默认只绑本机，局域网访问请用 0.0.0.0 并自行加鉴权")
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--data", default=None, help="数据目录（默认 ./data，可用 VOXNOTE_DATA 覆盖）")
    args = ap.parse_args()

    if args.data:
        import os
        os.environ["VOXNOTE_DATA"] = args.data

    print("voxnote server")
    print("  本机   http://127.0.0.1:%d" % args.port)
    if args.host == "0.0.0.0":
        print("  局域网 http://%s:%d" % (pick_local_ip(), args.port))
    uvicorn.run("voxnote_server.app:create_app", factory=True,
                host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
