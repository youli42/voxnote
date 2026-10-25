"""本地引擎：Qwen3-ASR-1.7B（GGUF）· 由 llama.cpp 的 llama-server 提供推理。

模型与音频编码器（mmproj）分开下载到 data/models/qwen3-asr/，来源可选
HuggingFace 量化仓库（默认走 hf-mirror.com 镜像，国内可达）。下载完启动
llama-server 子进程（-m 主模型 --mmproj 编码器），引擎通过其 OpenAI 兼容
/v1/audio/transcriptions 端点转写。

Qwen3-ASR 不输出时间戳：整段音频返回一句，t_ms=0 / e_ms=录音时长。
"""
import atexit
import os
import re
import subprocess
import threading
import time

from . import Engine, register

# Qwen3-ASR 的输出头：language 标签 + asr_text 起始标记，llama-server 会原样带出来
_ASR_PREFIX = re.compile(r"^(?:language\s+\S+\s*)?<\|?asr_text\|?>\s*")

# 模型管理状态（下载进度 / llama-server 子进程），进程内共享
_state = {
    "dl": {"state": "idle", "file": "", "got": 0, "total": 0, "error": "", "repo": ""},
    "proc": None,
    "lock": threading.Lock(),
}


def model_dir():
    from .. import config, db
    d = db.get("llama_model_dir") or os.path.join(config.data_dir(), "models", "qwen3-asr")
    os.makedirs(d, exist_ok=True)
    return d


def scan_model():
    """扫描模型目录：主模型 = 最大的非 mmproj gguf；编码器 = mmproj*.gguf。"""
    d = model_dir()
    main = mm = None
    main_size = mm_size = 0
    try:
        for f in os.listdir(d):
            if not f.lower().endswith(".gguf"):
                continue
            p = os.path.join(d, f)
            sz = os.path.getsize(p)
            if f.lower().startswith("mmproj"):
                if sz > mm_size:
                    mm, mm_size = p, sz
            elif sz > main_size:
                main, main_size = p, sz
    except OSError:
        pass
    return ({"dir": d, "main": main, "mmproj": mm,
             "main_bytes": main_size, "mmproj_bytes": mm_size,
             "ready": bool(main and mm)} if (main or mm) else
            {"dir": d, "main": None, "mmproj": None,
             "main_bytes": 0, "mmproj_bytes": 0, "ready": False})


def download_state():
    s = dict(_state["dl"])
    if s["total"] > 0 and s["state"] == "downloading":
        s["progress"] = round(s["got"] / s["total"], 4)
    else:
        s["progress"] = 1.0 if s["state"] == "done" else 0.0
    return s


def start_download(repo: str, quant: str = ""):
    with _state["lock"]:
        if _state["dl"]["state"] in ("downloading",):
            raise RuntimeError("已有下载在进行")
        _state["dl"].update({"state": "downloading", "file": "", "got": 0,
                             "total": 0, "error": "", "repo": repo})
    threading.Thread(target=_download, args=(repo, quant), daemon=True).start()


def stop_download():
    _state["dl"]["state"] = "cancel"


def _download(repo: str, quant: str):
    import httpx
    from .. import db
    dl = _state["dl"]
    try:
        mirror = (db.get("hf_mirror") or "https://hf-mirror.com").rstrip("/")
        r = httpx.get("%s/api/models/%s" % (mirror, repo), timeout=30,
                      follow_redirects=True)
        r.raise_for_status()
        files = [f["rfilename"] for f in r.json().get("siblings", [])
                 if f["rfilename"].lower().endswith(".gguf")]
        mm = [f for f in files if os.path.basename(f).lower().startswith("mmproj")]
        mains = [f for f in files if f not in mm]
        if not mains:
            raise RuntimeError("仓库里没有 GGUF 模型文件")
        if quant:
            want = [f for f in mains if quant.lower() in f.lower()]
            mains = want or mains
        main = sorted(mains, key=len)[0]  # 文件名最短的通常是标准量化（非 -i1 变体）
        if not mm:
            raise RuntimeError("该仓库缺少音频编码器 mmproj-*.gguf，"
                               "请换用带 mmproj 的仓库（如 foryoung365/Qwen3-ASR-1.7B-Q4_K_M-GGUF）")
        mmf = sorted(mm, key=len)[0]

        d = model_dir()
        for name in (main, mmf):
            if dl["state"] == "cancel":
                raise RuntimeError("已取消")
            url = "%s/%s/resolve/main/%s" % (mirror, repo, name)
            dest = os.path.join(d, os.path.basename(name))
            _download_file(dl, url, dest)
        dl["state"] = "done"
        dl["file"] = ""
    except Exception as t:
        dl["state"] = "fail"
        dl["error"] = str(t)[:300]


def _download_file(dl, url, dest, attempts=3):
    """断点续传下载：.part 从已下字节继续；网络抖动重试。"""
    import httpx
    for i in range(attempts):
        if dl["state"] == "cancel":
            raise RuntimeError("已取消")
        tmp = dest + ".part"
        dl["file"] = os.path.basename(dest)
        dl["got"] = os.path.getsize(tmp) if os.path.isfile(tmp) else 0
        dl["total"] = 0
        try:
            headers = {"Range": "bytes=%d-" % dl["got"]} if dl["got"] > 0 else {}
            with httpx.stream("GET", url, timeout=30, follow_redirects=True,
                              headers=headers) as st:
                st.raise_for_status()
                cl = st.headers.get("content-length")
                if cl:
                    total = int(cl) + (dl["got"] if st.status_code == 206 else 0)
                    dl["total"] = total
                    if dl["got"] > 0 and dl["got"] == total:  # 已经下完过
                        os.replace(tmp, dest)
                        return
                mode = "ab" if (st.status_code == 206 and dl["got"] > 0) else "wb"
                if mode == "wb":
                    dl["got"] = 0
                with open(tmp, mode) as f:
                    for chunk in st.iter_bytes(1 << 20):
                        if dl["state"] == "cancel":
                            raise RuntimeError("已取消")
                        f.write(chunk)
                        dl["got"] += len(chunk)
            os.replace(tmp, dest)
            return
        except RuntimeError:
            raise
        except Exception:
            if i == attempts - 1:
                raise
            time.sleep(3)


# ------------------------------------------------------------ llama-server

def _port():
    from .. import db
    try:
        return int(db.get("llama_port") or 8849)
    except ValueError:
        return 8849


def _base():
    return "http://127.0.0.1:%d" % _port()


def server_status():
    proc = _state["proc"]
    return {"running": bool(proc and proc.poll() is None),
            "port": _port(), "healthy": _healthy()}


def _healthy(timeout=2.0):
    try:
        import httpx
        r = httpx.get(_base() + "/health", timeout=timeout)
        return r.status_code == 200
    except Exception:
        return False


def start_server():
    """启动 llama-server 子进程（已在跑则直接返回）。模型就绪是前提。"""
    from .. import db
    m = scan_model()
    if not m["ready"]:
        raise RuntimeError("模型未就绪：需要主模型 GGUF + mmproj 编码器（设置页可下载）")
    exe = (db.get("llama_server_path") or "").strip()
    if not exe:
        raise RuntimeError("未配置 llama-server 程序路径（设置页可填，"
                           "llama.cpp 发布包里的 llama-server.exe）")
    if not os.path.isfile(exe):
        raise RuntimeError("llama-server 路径不存在：" + exe)
    with _state["lock"]:
        if _state["proc"] and _state["proc"].poll() is None:
            return
        args = [exe, "-m", m["main"], "--mmproj", m["mmproj"],
                "--host", "127.0.0.1", "--port", str(_port()),
                "-c", "4096", "-ngl", "99"]
        log = open(os.path.join(model_dir(), "llama-server.log"), "ab")
        _state["proc"] = subprocess.Popen(args, stdout=log, stderr=log, cwd=model_dir())
        log.close()  # 子进程已继承句柄；父进程不关的话每次启停泄漏一个 fd
    for _ in range(240):  # 首次加载模型可能要几分钟
        if _healthy():
            return
        if _state["proc"].poll() is not None:
            raise RuntimeError("llama-server 启动即退出，详见 data/models/qwen3-asr/llama-server.log")
        time.sleep(1.0)
    raise RuntimeError("llama-server 健康检查超时（模型加载中？稍后重试）")


def stop_server():
    with _state["lock"]:
        p = _state["proc"]
        _state["proc"] = None
    if p and p.poll() is None:
        try:
            p.terminate()
            p.wait(timeout=10)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass


def _reap_on_exit():
    """atexit 兜底回收 llama-server：uvicorn 收到第二次 Ctrl+C 走 force 退出，
    会跳过应用的 lifespan 关闭（stop_server 不执行），子进程变孤儿占着几百
    MB 内存。正常关闭时 lifespan 已回收，这里是无害的空操作。"""
    try:
        stop_server()
    except Exception:
        pass


atexit.register(_reap_on_exit)


def to_wav16k(audio_path: str, record_id: str) -> str:
    """非 wav 音频（手机录音是 AAC m4a）先用 PyAV（libav）转 16k 单声道
    wav 放临时目录。llama-server 的音频加载用 miniaudio，只认 wav/mp3/flac，
    AAC 直接 400「Failed to load image or audio file」；whisper 引擎走 faster-whisper
    自带解码，不受此限。返回临时 wav 路径，调用方用完自行决定删留。"""
    import av

    from .. import config
    os.makedirs(config.temp_dir(), exist_ok=True)
    out_path = os.path.join(config.temp_dir(), record_id + ".wav")
    inp = out = None
    try:
        inp = av.open(audio_path)
        out = av.open(out_path, "w")
        stream = out.add_stream("pcm_s16le", rate=16000, layout="mono")
        resampler = av.AudioResampler(format="s16", layout="mono", rate=16000)
        for frame in inp.decode(audio=0):
            for rf in resampler.resample(frame):
                for packet in stream.encode(rf):
                    out.mux(packet)
        for packet in stream.encode(None):
            out.mux(packet)
    except Exception as e:
        raise RuntimeError("音频转码失败（%s）：%s" % (os.path.basename(audio_path), e))
    finally:
        for f in (inp, out):
            try:
                if f is not None:
                    f.close()
            except Exception:
                pass
    return out_path


class LlamaASR(Engine):
    id = "llama-local"
    name = "Qwen3-ASR（本地）"
    desc = "Qwen3-ASR-1.7B GGUF · llama.cpp · 中文多语种 · 整段无时间戳"

    def available(self):
        if not scan_model()["ready"]:
            return False
        from .. import db
        return _healthy() or bool((db.get("llama_server_path") or "").strip())

    def info(self):
        o = super().info()
        m = scan_model()
        o["model_ready"] = m["ready"]
        o["model_bytes"] = m["main_bytes"] + m["mmproj_bytes"]
        o["server"] = server_status()
        return o

    def transcribe(self, audio_path, record, on_progress=None, on_partial=None):
        import httpx
        if not _healthy():
            start_server()
        if on_progress:
            on_progress(0.15)
        # 非 wav 先转 16k wav（llama-server 的 miniaudio 不认 AAC/m4a）。
        # 转码产物在 data/tmp：成功即删；失败保留供排查，存储设置页可清理。
        send_path = audio_path
        wav = None
        if not audio_path.lower().endswith(".wav"):
            wav = to_wav16k(audio_path, record["id"])
            send_path = wav
        with open(send_path, "rb") as f:
            resp = httpx.post(
                _base() + "/v1/audio/transcriptions",
                files={"file": (os.path.basename(send_path), f)},
                data={"response_format": "json"},  # llama-server 只接受 json
                timeout=1800)
        if resp.status_code == 200 and wav:
            try:
                os.remove(wav)
            except OSError:
                pass
        if resp.status_code != 200:
            raise RuntimeError("llama-server 转写失败 HTTP %d：%s"
                               % (resp.status_code, resp.text[:200]))
        if on_progress:
            on_progress(0.95)
        text = ""
        segs_out = []
        try:
            data = resp.json()
            text = _ASR_PREFIX.sub("", (data.get("text") or "").strip())
            for s in data.get("segments") or []:
                t = _ASR_PREFIX.sub("", (s.get("text") or "").strip())
                if t:
                    segs_out.append({"t_ms": int(s.get("start", 0) * 1000),
                                     "e_ms": int(s.get("end", 0) * 1000),
                                     "text": t, "spk": -1})
        except Exception:
            text = _ASR_PREFIX.sub("", resp.text.strip())
        if not segs_out and text:
            # Qwen3-ASR 无时间戳：整段一句，铺满录音时长
            segs_out = [{"t_ms": 0, "e_ms": record["dur_ms"] or 0,
                         "text": text, "spk": -1}]
        if not segs_out:
            raise RuntimeError("转写结果为空")
        if on_partial:
            on_partial(list(segs_out))
        if on_progress:
            on_progress(1.0)
        return segs_out


register(LlamaASR())
