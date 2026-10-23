"""本地引擎：faster-whisper（CTranslate2，GPU/CPU 自适应）。

模型按 (model, device, compute) 缓存进程级单例；换模型即换实例。
进度按已转写音频时长 / 总时长估算。
"""
import threading

from . import Engine, register


class WhisperLocal(Engine):
    id = "whisper-local"
    name = "Whisper（本地）"
    desc = "faster-whisper · GPU 优先，回退 CPU · 中文标点"

    def __init__(self):
        self._lock = threading.Lock()
        self._model = None
        self._key = None

    def available(self):
        try:
            import faster_whisper  # noqa: F401
            return True
        except Exception:
            return False

    def _get_model(self):
        from .. import db
        name = db.get("whisper_model", "small")
        device = db.get("whisper_device", "auto")
        compute = db.get("whisper_compute", "auto")
        if device == "auto":
            try:
                import ctranslate2
                device = "cuda" if ctranslate2.get_cuda_device_count() > 0 else "cpu"
            except Exception:
                device = "cpu"
        if compute == "auto":
            compute = "float16" if device == "cuda" else "int8"
        key = (name, device, compute)
        with self._lock:
            if self._model is not None and self._key == key:
                return self._model
            from faster_whisper import WhisperModel
            self._model = WhisperModel(name, device=device, compute_type=compute)
            self._key = key
            return self._model

    def transcribe(self, audio_path, record, on_progress=None):
        model = self._get_model()
        segments, info = model.transcribe(
            audio_path, language=None, beam_size=5,
            vad_filter=True, vad_parameters={"min_silence_duration_ms": 500})
        total = float(info.duration) or 0.0
        out = []
        for s in segments:
            text = (s.text or "").strip()
            if not text:
                continue
            out.append({"t_ms": int(s.start * 1000), "e_ms": int(s.end * 1000),
                        "text": text, "spk": -1})
            if on_progress and total > 0:
                on_progress(min(0.99, s.end / total))
        if on_progress:
            on_progress(1.0)
        return out


register(WhisperLocal())
