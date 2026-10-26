"""假 OpenAI 兼容语音转文字引擎（仅供开发/测试）。

用途：
  - 让库房的 OpenAI 兼容适配器、端到端脚本、契约测试有一个**确定性**的依赖，
    不依赖真模型、不联网、不下载。
  - 实现最小可用的标准接口：
        GET  /health
        GET  /v1/models
        POST /v1/audio/transcriptions
    支持 response_format: json / text / srt / vtt / verbose_json。

用法：
    python scripts/fake_engine.py --port 8801
    python scripts/fake_engine.py --port 8801 --fail-mode 500

输出是确定性的：按音频时长切成约 2.5 秒一段，每段一句 "这是第 N 句测试转写。"
（language 非中文时用英文）。任何文件都能处理，不校验格式。
"""
import argparse
import math
import wave
from io import BytesIO

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import PlainTextResponse

app = FastAPI(title="voxnote fake stt engine", version="0.1.0")

STATE = {"fail_mode": "none", "requests": 0}


def _duration_seconds(data: bytes) -> float:
    """尽量从 wav 头读时长；失败就按大小粗估（16k/16bit/mono 约 32KB/s）。"""
    try:
        with wave.open(BytesIO(data), "rb") as w:
            frames = w.getnframes()
            rate = w.getframerate() or 16000
            return max(0.1, frames / float(rate))
    except Exception:
        return max(0.5, min(600.0, len(data) / 32000.0))


def _sentences(duration: float, language: str):
    count = max(1, int(math.ceil(duration / 2.5)))
    step = duration / count
    out = []
    for i in range(count):
        if language.lower().startswith("zh"):
            text = "这是第 %d 句测试转写。" % (i + 1)
        else:
            text = "This is test sentence %d." % (i + 1)
        out.append({
            "id": i,
            "seek": int(i * step * 1000),
            "start": round(i * step, 2),
            "end": round(min(duration, (i + 1) * step), 2),
            "text": text,
            "tokens": [],
            "temperature": 0.0,
            "avg_logprob": -0.1,
            "compression_ratio": 1.0,
            "no_speech_prob": 0.01,
        })
    return out


def _fmt_ts(seconds: float, comma: bool = False) -> str:
    ms = int(round(seconds * 1000))
    h, rem = divmod(ms, 3600000)
    m, rem = divmod(rem, 60000)
    s, ms = divmod(rem, 1000)
    sep = "," if comma else "."
    return "%02d:%02d:%02d%s%03d" % (h, m, s, sep, ms)


def _srt(segments) -> str:
    blocks = []
    for i, seg in enumerate(segments, 1):
        blocks.append("%d\n%s --> %s\n%s\n" % (
            i, _fmt_ts(seg["start"], True), _fmt_ts(seg["end"], True), seg["text"]))
    return "\n".join(blocks)


def _vtt(segments) -> str:
    blocks = ["WEBVTT\n"]
    for seg in segments:
        blocks.append("%s --> %s\n%s\n" % (
            _fmt_ts(seg["start"]), _fmt_ts(seg["end"]), seg["text"]))
    return "\n".join(blocks)


@app.get("/health")
def health():
    return {"ok": True, "engine": "fake", "model": "fake-whisper", "fail_mode": STATE["fail_mode"]}


@app.get("/v1/models")
def models():
    return {"object": "list", "data": [{"id": "fake-whisper", "object": "model"}]}


@app.post("/v1/audio/transcriptions")
async def transcriptions(
    file: UploadFile = File(...),
    model: str = Form("fake-whisper"),
    language: str = Form("zh"),
    response_format: str = Form("json"),
    temperature: float = Form(0.0),
):
    STATE["requests"] += 1
    if STATE["fail_mode"] == "500":
        raise HTTPException(status_code=500, detail="fake engine failure")

    data = await file.read()
    duration = _duration_seconds(data)
    segments = _sentences(duration, language)
    text = "".join(seg["text"] for seg in segments)

    print("[fake-engine] %s model=%s lang=%s fmt=%s dur=%.1fs" % (
        file.filename, model, language, response_format, duration), flush=True)

    if response_format == "text":
        return PlainTextResponse(text)
    if response_format == "srt":
        return PlainTextResponse(_srt(segments))
    if response_format == "vtt":
        return PlainTextResponse(_vtt(segments))
    if response_format == "verbose_json":
        return {
            "task": "transcribe",
            "language": language,
            "duration": round(duration, 2),
            "text": text,
            "segments": segments,
            "words": [],
        }
    return {"text": text}


def main():
    ap = argparse.ArgumentParser(description="voxnote fake OpenAI-compatible STT engine")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8801)
    ap.add_argument("--fail-mode", choices=["none", "500"], default="none")
    args = ap.parse_args()
    STATE["fail_mode"] = args.fail_mode
    import uvicorn
    print("[fake-engine] listening on http://%s:%d (fail-mode=%s)" % (
        args.host, args.port, args.fail_mode), flush=True)
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
