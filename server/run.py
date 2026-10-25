"""voxnote server：录音同步、转写、整理与 AI 对接的自托管服务端。

用法：
    python run.py [--host 0.0.0.0] [--port 8787] [--data ./data]
"""
import argparse
import asyncio
import logging
import os
import socket
import sys

import uvicorn


def _quiet_shutdown_noise():
    """滤掉 Ctrl+C 退出时的假错误刷屏。

    uvicorn 优雅关闭后会把捕获的 SIGINT 重放一次（保持 Ctrl+C 的退出语义），
    此时 asyncio 收尾取消残留的 lifespan 任务。这条 CancelledError 有两种
    落到日志的形态，都是关闭噪音而非真实故障：
      1) uvicorn lifespan 的 except 分支按 exc_info 记录；
      2) starlette 把整段 traceback 格式化成字符串，经 lifespan.shutdown.failed
         消息发回，uvicorn 当 message 原样按 ERROR 记（exc_info 为空）。
    只滤带 CancelledError 的记录；应用关闭期的真实异常照常透出。"""
    class NoCancelTrace(logging.Filter):
        def filter(self, record):
            ei = record.exc_info
            if ei and ei[1] is not None \
                    and isinstance(ei[1], asyncio.exceptions.CancelledError):
                return False
            msg = record.getMessage()
            return not (record.levelno == logging.ERROR
                        and msg.startswith("Traceback (most recent call last):")
                        and "CancelledError" in msg)

    logging.getLogger("uvicorn.error").addFilter(NoCancelTrace())


def pick_local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser(description="voxnote server")
    ap.add_argument("--host", default="0.0.0.0",
                    help="监听地址；默认全网卡（手机等局域网设备可连），只本机用可改 127.0.0.1")
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--data", default=None, help="数据目录（默认 ./data，可用 VOXNOTE_DATA 覆盖）")
    args = ap.parse_args()

    if args.data:
        os.environ["VOXNOTE_DATA"] = args.data

    print("voxnote server")
    print("  本机   http://127.0.0.1:%d" % args.port)
    if args.host == "0.0.0.0":
        print("  局域网 http://%s:%d" % (pick_local_ip(), args.port))
        if not os.environ.get("VOXNOTE_API_KEY", "").strip():
            print("  提示   局域网内任何人可访问；建议设置环境变量 VOXNOTE_API_KEY 开启鉴权")
    _quiet_shutdown_noise()
    try:
        uvicorn.run("voxnote_server.app:create_app", factory=True,
                    host=args.host, port=args.port, log_level="info")
    except KeyboardInterrupt:
        # uvicorn 优雅关闭后按语义重放 SIGINT；这里收下，不往 stderr 打
        # 最终的 KeyboardInterrupt traceback，安静退出（130 = 被 SIGINT 终止）。
        print("\n已停止")
        sys.exit(130)


if __name__ == "__main__":
    main()
