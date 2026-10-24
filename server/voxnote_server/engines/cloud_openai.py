"""云端引擎：任意 OpenAI 兼容的 /audio/transcriptions 端点。

覆盖 Groq Whisper、SiliconFlow SenseVoice 等兼容服务。
verbose_json 有时间戳时逐句落位；纯文本响应退化为整段一句。
"""
import json

from . import Engine, register


class CloudOpenAI(Engine):
    id = "cloud-openai"
    name = "云端 API"
    desc = "OpenAI 兼容 /audio/transcriptions · 按 Key 计费"

    def available(self):
        from .. import db
        return bool(db.get("cloud_asr_base") and db.get("cloud_asr_key"))

    def transcribe(self, audio_path, record, on_progress=None, on_partial=None):
        from .. import db
        import os
        import httpx

        base = (db.get("cloud_asr_base") or "").rstrip("/")
        key = db.get("cloud_asr_key") or ""
        model = db.get("cloud_asr_model") or "whisper-large-v3"
        if not base or not key:
            raise RuntimeError("云端引擎未配置 base / key")

        if on_progress:
            on_progress(0.1)
        with open(audio_path, "rb") as f:
            resp = httpx.post(
                base + "/audio/transcriptions",
                headers={"Authorization": "Bearer " + key},
                files={"file": (os.path.basename(audio_path), f)},
                data={"model": model, "response_format": "verbose_json"},
                timeout=600)
        if on_progress:
            on_progress(0.9)
        if resp.status_code != 200:
            raise RuntimeError("云端转写失败 HTTP %d：%s" % (resp.status_code, resp.text[:200]))

        out = []
        try:
            data = resp.json()
            for s in data.get("segments") or []:
                text = (s.get("text") or "").strip()
                if not text:
                    continue
                out.append({"t_ms": int(s.get("start", 0) * 1000),
                            "e_ms": int(s.get("end", 0) * 1000),
                            "text": text, "spk": -1})
            if not out and data.get("text"):
                out.append({"t_ms": 0, "e_ms": record["dur_ms"] or 0,
                            "text": data["text"].strip(), "spk": -1})
        except (json.JSONDecodeError, ValueError, AttributeError, TypeError):
            # AttributeError/TypeError：响应 JSON 不是 dict（如 list）时 data.get 直接炸
            out = [{"t_ms": 0, "e_ms": record["dur_ms"] or 0,
                    "text": resp.text.strip(), "spk": -1}]
        if on_progress:
            on_progress(1.0)
        return out


register(CloudOpenAI())
