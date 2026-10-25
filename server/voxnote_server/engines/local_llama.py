"""本地引擎：Qwen3-ASR-1.7B（GGUF）· 由 llama.cpp 的 llama-server 提供推理。

模型与音频编码器（mmproj）分开下载到 data/models/qwen3-asr/，来源可选
HuggingFace 量化仓库（默认走 hf-mirror.com 镜像，国内可达）。下载完启动
llama-server 子进程（-m 主模型 --mmproj 编码器），引擎通过其 OpenAI 兼容
/v1/audio/transcriptions 端点转写。

Qwen3-ASR 不输出时间戳：整段音频返回一句。长音频按静音边界切段逐块转写
（llama-server 的上下文装不下整段，见 _AUDIO_TOK_PER_SEC），段落时间戳
就是各块的时间范围，粒度比分块大小粗。
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

# ------------------------------------------------------------ 长音频分段转写
# llama-server 把音频按 token 计入上下文（实测 Qwen3-ASR ≈ 13 token/s），
# 装不下就 400 exceed_context_size_error：4096 只够约 4 分钟；一小时的录音
# 调大 ctx 也装不下（KV 缓存随 ctx 暴涨）。做法：整段先试，超限错误里带着
# 实测 n_prompt_tokens / n_ctx——据此反推本机装得下的单块秒数，按静音边界
# 切段逐块转写拼接；学到的预算记在 _state，后续长音频直接走分段。
_CTX_SPAWN = 8192             # 启动上下文：4096 只够 ~4 分钟；8192 约 9 分半/块
_AUDIO_TOK_PER_SEC = 13.0     # 实测均值；超限时用错误里的真实值重算
_CTX_RESERVE = 700            # 输出文本 + 提示模板的余量（token）
_MIN_CHUNK_SEC = 30           # 自适应缩块下限，更小说明 ctx 异常
_BOUNDARY_SEARCH_SEC = 10.0   # 切点在目标位置 ±10s 内找最安静的 300ms


class _ContextExceeded(Exception):
    def __init__(self, n_prompt, n_ctx):
        Exception.__init__(self, "prompt %d tokens > ctx %d" % (n_prompt, n_ctx))
        self.n_prompt = n_prompt
        self.n_ctx = n_ctx


def _parse_exceed(resp):
    """llama-server 的超上下文 400 → _ContextExceeded（带实测 token 数），其余 None。"""
    if resp.status_code != 400:
        return None
    try:
        e = resp.json().get("error") or {}
        if e.get("type") == "exceed_context_size_error" \
                or "exceeds the available context size" in str(e.get("message", "")):
            return _ContextExceeded(int(e.get("n_prompt_tokens") or 0),
                                    int(e.get("n_ctx") or 0))
    except Exception:
        pass
    return None


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
                "-c", str(_CTX_SPAWN), "-ngl", "99"]
        log = open(os.path.join(model_dir(), "llama-server.log"), "ab")
        _state["proc"] = subprocess.Popen(args, stdout=log, stderr=log, cwd=model_dir())
        log.close()  # 子进程已继承句柄；父进程不关的话每次启停泄漏一个 fd
        _state.pop("chunk_sec", None)  # 新进程的上下文可能变了，重新学
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


# ------------------------------------------------------------ wav 分段工具（纯 stdlib）

def _wav_info(path):
    """(rate, ch, width, frames)；不是标准 PCM wav 返回 None。"""
    import wave
    try:
        with wave.open(path, "rb") as w:
            return (w.getframerate(), w.getnchannels(), w.getsampwidth(), w.getnframes())
    except Exception:
        return None


def _read_wav_frames(path, f0, f1):
    import wave
    with wave.open(path, "rb") as w:
        w.setpos(f0)
        return w.readframes(f1 - f0)


def _wrap_wav(pcm, info):
    """PCM 包回 wav（内存里），供分块 POST。"""
    import io
    import wave
    rate, ch, width = info[0], info[1], info[2]
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(ch)
        w.setsampwidth(width)
        w.setframerate(rate)
        w.writeframes(pcm)
    buf.seek(0)
    return buf


def _quiet_boundary(path, info, target_f, search_f):
    """切点取 target ±search 内能量最低的 300ms 窗的起点，尽量避免切在词中间。
    只认 s16（自转码产物与绝大多数录音都是）；其它采样宽度直接用 target。"""
    rate, _ch, width, frames = info
    if width != 2:
        return min(max(target_f, 0), frames)
    import array
    import wave
    win_f = max(1, int(rate * 0.3))
    step_f = max(1, int(rate * 0.1))
    lo = max(0, target_f - search_f)
    hi = min(frames - win_f, target_f + search_f)
    if hi <= lo:
        return min(max(target_f, 0), max(0, frames - win_f))
    best_f, best_e = target_f, None
    with wave.open(path, "rb") as w:
        f = lo
        while f <= hi:
            w.setpos(f)
            a = array.array("h")
            a.frombytes(w.readframes(win_f))
            e = sum(v * v for v in a)
            if best_e is None or e < best_e:
                best_e, best_f = e, f
            f += step_f
    return best_f


def _plan_cuts(path, info, chunk_sec):
    """按 chunk_sec 目标切段，返回切点帧号列表（相邻切点之间是一块）。"""
    rate, _ch, _w, frames = info
    step_f = int(chunk_sec * rate)
    guard_f = int(_MIN_CHUNK_SEC * rate)
    search_f = int(_BOUNDARY_SEARCH_SEC * rate)
    cuts, last = [], 0
    while frames - last > step_f:
        cut = _quiet_boundary(path, info, last + step_f, search_f)
        cut = max(min(cut, frames - guard_f), last + guard_f)
        cuts.append(cut)
        last = cut
    return cuts


def _budget_sec_from(n_prompt, n_ctx, dur_sec):
    """超限错误 → 本机装得下的单块秒数（记进 _state 供后续长音频直走分段）。"""
    tok_per_sec = (n_prompt / dur_sec) if n_prompt > 0 and dur_sec > 0 else _AUDIO_TOK_PER_SEC
    sec = int((n_ctx - _CTX_RESERVE) / tok_per_sec - _BOUNDARY_SEARCH_SEC)
    sec = max(sec, _MIN_CHUNK_SEC)
    _state["chunk_sec"] = sec
    return sec


class LlamaASR(Engine):
    id = "llama-local"
    name = "Qwen3-ASR（本地）"
    desc = "Qwen3-ASR-1.7B GGUF · llama.cpp · 中文多语种 · 长音频自动分段"

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
        info = _wav_info(send_path)
        dur_sec = info[3] / float(info[0]) if info and info[0] else 0
        budget = _state.get("chunk_sec")
        if info and budget and dur_sec > budget:
            # 学过的预算说整段装不下：直接分段，省一次注定失败的整段请求
            segs = self._transcribe_chunked(send_path, info, budget,
                                            on_progress, on_partial)
        else:
            try:
                segs = self._transcribe_one(send_path, record)
            except _ContextExceeded as exc:
                if info is None:
                    raise RuntimeError(
                        "llama-server 上下文装不下这段音频（%d token > %d），"
                        "且无法分段：不是可解析的 PCM wav" % (exc.n_prompt, exc.n_ctx))
                segs = self._transcribe_chunked(
                    send_path, info,
                    _budget_sec_from(exc.n_prompt, exc.n_ctx, dur_sec),
                    on_progress, on_partial)
            else:
                if on_progress:
                    on_progress(0.95)
                if on_partial:
                    on_partial(list(segs))
        if wav:
            try:
                os.remove(wav)
            except OSError:
                pass
        if on_progress:
            on_progress(1.0)
        return segs

    def _transcribe_one(self, path, record):
        """整段一次请求（短音频主路径；超上下文抛 _ContextExceeded）。"""
        import httpx
        with open(path, "rb") as f:
            resp = httpx.post(
                _base() + "/v1/audio/transcriptions",
                files={"file": (os.path.basename(path), f)},
                data={"response_format": "json"},  # llama-server 只接受 json
                timeout=1800)
        exc = _parse_exceed(resp)
        if exc is not None:
            raise exc
        if resp.status_code != 200:
            raise RuntimeError("llama-server 转写失败 HTTP %d：%s"
                               % (resp.status_code, resp.text[:200]))
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
        return segs_out

    def _transcribe_chunked(self, path, info, chunk_sec, on_progress, on_partial):
        """按静音边界切段逐块转写；某块仍超限时用错误里的实测值缩块重排。"""
        import httpx
        rate = info[0]
        frames = info[3]
        while True:
            bounds = [0] + _plan_cuts(path, info, chunk_sec) + [frames]
            spans = list(zip(bounds[:-1], bounds[1:]))
            segs = []
            exceeded = None
            for i, (f0, f1) in enumerate(spans):
                resp = httpx.post(
                    _base() + "/v1/audio/transcriptions",
                    files={"file": ("chunk_%03d.wav" % i,
                                    _wrap_wav(_read_wav_frames(path, f0, f1), info))},
                    data={"response_format": "json"},
                    timeout=1800)
                exceeded = _parse_exceed(resp)
                if exceeded is not None:
                    break
                if resp.status_code != 200:
                    raise RuntimeError("llama-server 转写失败 HTTP %d：%s"
                                       % (resp.status_code, resp.text[:200]))
                text = ""
                try:
                    text = _ASR_PREFIX.sub("", ((resp.json().get("text") or "").strip()))
                except Exception:
                    pass
                if text:
                    # 每块一句：时间戳就是块的时间范围（模型无时间戳，粒度=块大小）
                    segs.append({"t_ms": int(f0 * 1000 / rate),
                                 "e_ms": int(f1 * 1000 / rate),
                                 "text": text, "spk": -1})
                if on_progress:
                    on_progress(0.15 + 0.85 * (i + 1) / len(spans))
                if on_partial:
                    on_partial(list(segs))   # 手机端逐块看到稿子长出来
            if exceeded is None:
                if not segs:
                    raise RuntimeError("转写结果为空")
                return segs
            # 这块仍超限（预算过时 / 上下文变了）：按实测值缩块，整条时间线重排
            new_sec = _budget_sec_from(exceeded.n_prompt, exceeded.n_ctx,
                                       (f1 - f0) / float(rate))
            if new_sec >= chunk_sec:
                raise RuntimeError("llama-server 上下文装不下 %d 秒的音频块（ctx=%d）"
                                   % (int((f1 - f0) / rate), exceeded.n_ctx))
            chunk_sec = new_sec


register(LlamaASR())
