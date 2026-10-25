"""本地引擎：Qwen3-ASR（ONNX）· sherpa-onnx 运行时 + silero VAD 分句。

与 llama-local 是同一个 Qwen3-ASR 模型家族的另一种跑法：先用 VAD 把录音切成
语音段，逐段过 ONNX 解码——每段自带句级起止时间（t_ms/e_ms，可作字幕导出），
长录音天然不受 llama-server 上下文限制。说话人分离不在此引擎（后续可再叠加
sherpa-onnx 的 diarization 模型）。

模型缺失时首次转写自动下载（约 1GB，进度体现在任务进度条 0.02→0.5）：
ASR 与 silero VAD 都走 hf-mirror（遵循 hf_mirror 设置），VAD 另有
sherpa-onnx 的 GitHub Release 兜底。

依赖（可选引擎惯例，不装则该引擎不可用）：pip install sherpa-onnx numpy
"""
import os
import threading
import time

from . import Engine, register
from .local_llama import _wav_info, to_wav16k

ASR_REPO = "csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
ASR_FILES = [
    "conv_frontend.onnx",
    "encoder.int8.onnx",
    "decoder.int8.onnx",
    "tokenizer/vocab.json",
    "tokenizer/merges.txt",
    "tokenizer/tokenizer_config.json",
]
# silero VAD v5（hf-mirror 有镜像，实测 sherpa-onnx 2.x 可用）；GitHub 官方
# release 的 v4 做兜底。两者 window_size 不同（576/512），引擎按模型自报取值。
VAD_NAME = "silero_vad.onnx"
VAD_FALLBACK_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
VAD_MIRROR_REPO = "deepghs/silero-vad-onnx"
RATE = 16000

# 运行时缓存（识别器 / VAD / 下载进度），进程内共享
_state = {
    "rec": None,
    "vad": None,
    "vad_cfg": None,
    "lock": threading.Lock(),
    "dl": {"state": "idle", "file": "", "got": 0, "total": 0, "error": ""},
}


def model_dir():
    from .. import config, db
    d = db.get("sherpa_model_dir") or os.path.join(config.data_dir(), "models", "qwen3-asr-sherpa")
    os.makedirs(d, exist_ok=True)
    return d


def scan_model():
    """模型完整性：ASR 的三件 ONNX + tokenizer 三件 + silero VAD。"""
    d = model_dir()
    total = 0
    missing = []
    for name in ASR_FILES + [VAD_NAME]:
        p = os.path.join(d, name)
        if os.path.isfile(p) and os.path.getsize(p) > 0:
            total += os.path.getsize(p)
        else:
            missing.append(name)
    return {"dir": d, "ready": not missing, "missing": missing, "bytes": total}


def download_state():
    return dict(_state["dl"])


def _mirror_base():
    from .. import db
    return (db.get("hf_mirror") or "https://hf-mirror.com").rstrip("/")


def _file_urls(name):
    """一个文件的下载地址（按优先级）：ASR 与 VAD 镜像走 hf_mirror 设置，
    VAD 另有 GitHub 官方 release 兜底。"""
    if name == VAD_NAME:
        return ["%s/%s/resolve/main/%s" % (_mirror_base(), VAD_MIRROR_REPO, name),
                VAD_FALLBACK_URL]
    return ["%s/%s/resolve/main/%s" % (_mirror_base(), ASR_REPO, name)]


def _download(dl, name):
    """带断点续传的单文件下载（.part 续传，网络抖动重试；多源按序回退）。"""
    import httpx
    dest = os.path.join(model_dir(), name)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    last_err = None
    for url in _file_urls(name):
        for attempt in range(3):
            tmp = dest + ".part"
            dl["file"] = name
            dl["got"] = os.path.getsize(tmp) if os.path.isfile(tmp) else 0
            dl["total"] = 0
            headers = {"Range": "bytes=%d-" % dl["got"]} if dl["got"] > 0 else {}
            try:
                with httpx.stream("GET", url, timeout=30, follow_redirects=True,
                                  headers=headers) as st:
                    st.raise_for_status()
                    cl = st.headers.get("content-length")
                    if cl:
                        total = int(cl) + (dl["got"] if st.status_code == 206 else 0)
                        dl["total"] = total
                        if dl["got"] > 0 and dl["got"] == total:
                            os.replace(tmp, dest)
                            return
                    mode = "ab" if (st.status_code == 206 and dl["got"] > 0) else "wb"
                    if mode == "wb":
                        dl["got"] = 0
                    with open(tmp, mode) as f:
                        for chunk in st.iter_bytes(1 << 20):
                            f.write(chunk)
                            dl["got"] += len(chunk)
                os.replace(tmp, dest)
                return
            except Exception as t:
                last_err = t
                time.sleep(3)
    raise RuntimeError("下载失败：%s" % last_err)


def ensure_models(on_progress=None):
    """模型齐全直接返回；缺什么下什么。下载进度映射到 0.02→0.5。"""
    m = scan_model()
    if m["ready"]:
        return
    with _state["lock"]:
        m = scan_model()   # 双检：并发触发只下一个
        if m["ready"]:
            return
        dl = _state["dl"]
        if dl["state"] == "downloading":
            raise RuntimeError("模型正在下载中，稍后重试")
        dl.update({"state": "downloading", "error": ""})
        try:
            missing = m["missing"]
            for i, name in enumerate(missing):
                _download(dl, name)
                if on_progress:
                    on_progress(0.02 + 0.48 * (i + 1) / len(missing))
            dl["state"] = "done"
            dl["file"] = ""
        except Exception as t:
            dl["state"] = "fail"
            dl["error"] = str(t)[:300]
            raise RuntimeError("模型下载失败（%s）：%s" % (dl["file"], dl["error"]))


def _runtime():
    """识别器 + VAD（懒加载，进程内缓存）。"""
    with _state["lock"]:
        if _state["rec"] is not None:
            return _state["rec"], _state["vad"], _state["vad_cfg"]
        import sherpa_onnx as so
        d = model_dir()
        rec = so.OfflineRecognizer.from_qwen3_asr(
            conv_frontend=os.path.join(d, "conv_frontend.onnx"),
            encoder=os.path.join(d, "encoder.int8.onnx"),
            decoder=os.path.join(d, "decoder.int8.onnx"),
            tokenizer=os.path.join(d, "tokenizer"),
            num_threads=4,
            max_total_len=2048,   # VAD 段 ≤20s 远用不满，留余量给长句输出
            max_new_tokens=512,
        )
        cfg = so.VadModelConfig()
        cfg.silero_vad.model = os.path.join(d, VAD_NAME)
        cfg.sample_rate = RATE
        vad = so.VadModel.create(cfg)
        _state.update({"rec": rec, "vad": vad, "vad_cfg": cfg})
        return rec, vad, cfg


def _read_f32(path):
    """16k 单声道 s16 wav → float32 归一化样本（sherpa 喂的是 -1..1）。"""
    import wave
    import numpy as np
    with wave.open(path, "rb") as w:
        raw = w.readframes(w.getnframes())
        assert w.getframerate() == RATE and w.getnchannels() == 1 and w.getsampwidth() == 2
    return np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0


def _vad_segments(vad, cfg, samples):
    """窗口推进 is_speech，静音处断句、说满上限强制断。返回样本区间列表。

    sherpa-onnx 2.x 的 Python API 只给底层判定（is_speech/reset），段累积
    自己做：语音中连续静音 ≥ min_silence 收段；单段 ≥ max_speech 强制收
    （字幕句长也舒服）；段长不足 min_speech 的丢弃（咳嗽/碰撞噪音）。
    """
    win = vad.window_size() if callable(vad.window_size) else vad.window_size
    sil = int((cfg.silero_vad.min_silence_duration or 0.5) * RATE)
    min_sp = int((cfg.silero_vad.min_speech_duration or 0.25) * RATE)
    max_sp = int((cfg.silero_vad.max_speech_duration or 20) * RATE)
    segs = []
    start = speech_end = None
    silence_run = 0
    for i in range(0, len(samples) - win + 1, win):
        if vad.is_speech(samples[i:i + win]):
            if start is None:
                start = i
            silence_run = 0
            speech_end = i + win
            if speech_end - start >= max_sp:
                segs.append((start, speech_end))
                start = speech_end = None
        elif start is not None:
            silence_run += win
            if silence_run >= sil:
                if speech_end - start >= min_sp:
                    segs.append((start, speech_end))
                start = speech_end = None
                silence_run = 0
    if start is not None and speech_end - start >= min_sp:
        segs.append((start, speech_end))
    return segs


class SherpaQwenASR(Engine):
    id = "sherpa-qwen"
    name = "Qwen3-ASR（句级·sherpa）"
    desc = "Qwen3-ASR-0.6B ONNX · sherpa-onnx + VAD · 句级时间戳，可作字幕"

    def available(self):
        try:
            import sherpa_onnx  # noqa: F401
            import numpy  # noqa: F401
        except Exception:
            return False
        return True   # 模型缺失会在首次转写时自动下载

    def info(self):
        o = super().info()
        m = scan_model()
        o["model_ready"] = m["ready"]
        o["model_bytes"] = m["bytes"] if m["ready"] else 0
        o["model_missing"] = len(m["missing"])
        o["download"] = download_state()
        return o

    def transcribe(self, audio_path, record, on_progress=None, on_partial=None):
        try:
            import sherpa_onnx  # noqa: F401
            import numpy  # noqa: F401
        except Exception:
            raise RuntimeError("需要安装依赖：pip install sherpa-onnx numpy")
        if on_progress:
            on_progress(0.02)
        ensure_models(on_progress)
        if on_progress:
            on_progress(0.52)
        rec, vad, cfg = _runtime()
        if on_progress:
            on_progress(0.56)
        # 统一到 16k 单声道 s16（sherpa 的特征提取按 16k 设计）
        info = _wav_info(audio_path)
        wav = None
        pcm_path = audio_path
        if not (info and info[0] == RATE and info[1] == 1 and info[2] == 2):
            wav = to_wav16k(audio_path, record["id"])
            pcm_path = wav
        samples = _read_f32(pcm_path)
        vad.reset()
        spans = _vad_segments(vad, cfg, samples)
        segs = []
        for idx, (a, b) in enumerate(spans):
            stream = rec.create_stream()
            stream.accept_waveform(RATE, samples[a:b])
            rec.decode_stream(stream)
            text = (stream.result.text or "").strip()
            if text:
                segs.append({"t_ms": int(a * 1000 / RATE),
                             "e_ms": int(b * 1000 / RATE),
                             "text": text, "spk": -1})
            if on_partial:
                on_partial(list(segs))   # 逐句回调：手机端实时看到稿子长出来
            if on_progress:
                on_progress(0.6 + 0.4 * (idx + 1) / max(1, len(spans)))
        if not segs:
            raise RuntimeError("转写结果为空")
        if wav:
            try:
                os.remove(wav)
            except OSError:
                pass
        return segs


register(SherpaQwenASR())
