"""生成测试用音频夹具（不提交音频文件，运行时生成）。

用法：
    python scripts/make_fixture_audio.py --out .tmp/fixtures
    python scripts/make_fixture_audio.py --out .tmp/fixtures --seconds 6

生成：
    tone-4s.wav      16k mono 16bit，220Hz 正弦 + 少量噪声
    silence-3s.wav   16k mono 16bit，静音
"""
import argparse
import math
import random
import struct
import wave
from pathlib import Path


def gen_wav(path: Path, seconds: float, silent: bool = False):
    path.parent.mkdir(parents=True, exist_ok=True)
    rate = 16000
    rng = random.Random(7)
    frames = bytearray()
    total = int(rate * seconds)
    for i in range(total):
        if silent:
            v = 0
        else:
            v = int(800 * math.sin(2 * math.pi * 220 * i / rate) + rng.randint(-80, 80))
            v = max(-32768, min(32767, v))
        frames += struct.pack("<h", v)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(bytes(frames))
    return path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=".tmp/fixtures")
    ap.add_argument("--seconds", type=float, default=4.0)
    args = ap.parse_args()

    out = Path(args.out)
    tone = gen_wav(out / "tone-4s.wav", max(0.5, args.seconds))
    silence = gen_wav(out / "silence-3s.wav", 3.0, silent=True)
    for p in (tone, silence):
        print(p.resolve())


if __name__ == "__main__":
    main()
