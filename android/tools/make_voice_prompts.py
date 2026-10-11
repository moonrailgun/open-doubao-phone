#!/usr/bin/env python3
"""Synthesize bundled fixed prompts as mono PCM16 24 kHz WAV. Existing clips are kept unless --overwrite is set.

TTS_TOKEN=... python3 tools/make_voice_prompts.py https://<gateway>/v1/audio/speech
"""
import argparse
import array
import json
import os
import pathlib
import urllib.request
import wave

OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/voice/prompts"
PROMPTS = json.loads((OUT / "prompts.json").read_text())
RATE = 24_000

parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
parser.add_argument("endpoint")
parser.add_argument("--model", default="elevenlabs/eleven_v3")
parser.add_argument("--voice", default="bhJUNIXWQQ94l8eI2VUf")
parser.add_argument("--overwrite", action="store_true", help="regenerate existing clips too")
args = parser.parse_args()
token = os.environ["TTS_TOKEN"]


def synthesize(text):
    body = {"model": args.model, "voice": args.voice, "input": text, "stream": True, "stream_format": "audio",
            "response_format": "pcm", "provider_options": {"elevenlabs": {"output_format": "pcm_24000"}}}
    request = urllib.request.Request(args.endpoint, json.dumps(body).encode(), method="POST",
                                     headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        kind = response.headers.get_content_type()
        assert kind in {"audio/pcm", "audio/x-pcm", "application/octet-stream"}, kind
        data = response.read()
    assert data and len(data) % 2 == 0, f"bad PCM for {text}: {len(data)} bytes"
    return data


def trim(pcm):
    """Cut leading and trailing silence so the reply starts right after the wake word."""
    samples = array.array("h", pcm)
    window = RATE // 100  # 10 ms, so a lone click in the tail does not count as speech
    loud = [i for i in range(0, len(samples), window)
            if (sum(x * x for x in samples[i:i + window]) / len(samples[i:i + window])) ** 0.5 > 300]
    assert loud, "prompt is silent"
    start = max(0, loud[0] - RATE // 50)
    end = min(len(samples), loud[-1] + window + RATE // 10)
    return samples[start:end].tobytes()


OUT.mkdir(parents=True, exist_ok=True)
for name, text in PROMPTS.items():
    assert name and all(c in "abcdefghijklmnopqrstuvwxyz0123456789-" for c in name), name
    assert isinstance(text, str) and text.strip(), name
    target = OUT / f"{name}.wav"
    if target.exists() and not args.overwrite:
        with wave.open(str(target), "rb") as file:
            assert (file.getnchannels(), file.getsampwidth(), file.getframerate()) == (1, 2, RATE), name
            assert file.getnframes() > 0, name
        continue
    pcm = trim(synthesize(text))
    temporary = target.with_suffix(".wav.tmp")
    with wave.open(str(temporary), "wb") as file:
        file.setnchannels(1)
        file.setsampwidth(2)
        file.setframerate(RATE)
        file.writeframes(pcm)
    temporary.replace(target)
    print(f"{name}.wav  {text}  {len(pcm) / 2 / RATE:.2f}s")
