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
        # Windows 上 pip 轮子不带 CUDA 运行库（cublas/cudnn），GPU 加载失败后
        # 记住并降级 CPU，后续任务不再反复尝试。
        self._cpu_fallback = False

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
        if device == "auto" and not self._cpu_fallback:
            try:
                import ctranslate2
                device = "cuda" if ctranslate2.get_cuda_device_count() > 0 else "cpu"
            except Exception:
                device = "cpu"
        if self._cpu_fallback:
            device = "cpu"
        if compute == "auto":
            compute = "float16" if device == "cuda" else "int8"
        key = (name, device, compute)
        with self._lock:
            if self._model is not None and self._key == key:
                return self._model
            from faster_whisper import WhisperModel
            try:
                self._model = WhisperModel(name, device=device, compute_type=compute)
            except Exception as t:
                low = str(t).lower()
                if device == "cuda" and ("cublas" in low or "cudnn" in low or "cuda" in low):
                    self._cpu_fallback = True
                    device, compute = "cpu", "int8"
                    key = (name, device, compute)
                    self._model = WhisperModel(name, device=device, compute_type=compute)
                else:
                    raise
            self._key = key
            return self._model

    def transcribe(self, audio_path, record, on_progress=None, on_partial=None):
        # cublas/cudnn 缺失在 Windows 上常发生在首次 encode 而不是模型加载，
        # 所以整个转写调用都要包在回退逻辑里：失败一次 → 降 CPU 重试。
        try:
            return self._run(audio_path, record, on_progress, on_partial)
        except Exception as t:
            low = str(t).lower()
            if not self._cpu_fallback and ("cublas" in low or "cudnn" in low or "cuda" in low):
                self._cpu_fallback = True
                with self._lock:
                    self._model = None
                    self._key = None
                return self._run(audio_path, record, on_progress, on_partial)
            raise

    def _run(self, audio_path, record, on_progress=None, on_partial=None):
        from .. import db
        model = self._get_model()
        lang = db.get("whisper_language", "zh")
        segments, info = model.transcribe(
            audio_path, language=None if lang == "auto" else lang, beam_size=5,
            vad_filter=True, vad_parameters={"min_silence_duration_ms": 500})
        total = float(info.duration) or 0.0
        out = []
        n = 0
        for s in segments:
            text = (s.text or "").strip()
            if not text:
                continue
            out.append({"t_ms": int(s.start * 1000), "e_ms": int(s.end * 1000),
                        "text": text, "spk": -1})
            if on_progress and total > 0:
                on_progress(min(0.99, s.end / total))
            n += 1
            # 每 8 句回调一次增量结果，队列侧节流落库（手机端实时可见）
            if on_partial and n % 8 == 0:
                on_partial(list(out))
        if on_partial and out:
            on_partial(list(out))
        if on_progress:
            on_progress(1.0)
        return out


register(WhisperLocal())
