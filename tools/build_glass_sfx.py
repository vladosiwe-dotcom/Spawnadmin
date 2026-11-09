"""Builds the mod's glass sounds from real CC0 recordings (tools/sfx_sources) plus synthesized
low end (boom, rumble, groan) for the scale of a breaking sky.

Usage: python3 tools/build_glass_sfx.py <output_dir>
"""
import os
import subprocess
import sys
import wave

import numpy as np
from scipy.signal import butter, lfilter, resample, fftconvolve

SR = 44100
HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "sfx_sources")
rng = np.random.default_rng(909)


def load(name):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", os.path.join(SRC, name), "-f", "s16le", "-ac", "1",
                          "-ar", str(SR), "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.int16) / 32768.0


BREAK = [load(f"bfh1_glass_breaking_0{i}.ogg") for i in range(1, 7)]
FALL = [load(f"bfh1_glass_falling_0{i}.ogg") for i in range(1, 5)]
HIT = [load(f"bfh1_glass_hit_0{i}.ogg") for i in range(1, 3)]
BIG = load("glass_breaking_tb.ogg")


def secs(s):
    return int(round(s * SR))


def lp(x, fc):
    b, a = butter(2, fc / (SR / 2), "low")
    return lfilter(b, a, x)


def hp(x, fc):
    b, a = butter(2, fc / (SR / 2), "high")
    return lfilter(b, a, x)


def pitch(x, factor):
    """Tape-style pitch/speed change: factor < 1 is lower and slower (bigger glass)."""
    return resample(x, max(8, int(len(x) / factor)))


def place(buf, sig, at, gain=1.0):
    i = secs(at)
    if i >= len(buf):
        return
    sig = sig[: len(buf) - i]
    buf[i:i + len(sig)] += sig * gain


def room(x, seconds, mix, bright=8000):
    n = secs(seconds)
    ir = lp(rng.standard_normal(n), bright) * np.exp(-np.arange(n) / SR * (6.9 / seconds))
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-9
    return x * (1 - mix) + fftconvolve(x, ir)[: len(x)] * mix * 1.4


def norm(x, peak=0.92):
    return x / (np.max(np.abs(x)) + 1e-9) * peak


def fade(x, s=0.3):
    n = secs(s)
    x[-n:] *= np.linspace(1, 0, n)
    return x


def boom(dur, f0=55, decay=0.9, gain=1.0):
    t = np.arange(secs(dur)) / SR
    return np.sin(2 * np.pi * np.cumsum(f0 * (1 - 0.35 * np.minimum(t, 1.5))) / SR) * np.exp(-t / decay) * gain


def glass_snap():
    """The sky snaps: a real hard glass crack, its splitting tail, a low thump, the sky's echo."""
    x = np.zeros(secs(3.0))
    place(x, HIT[0], 0.0, 1.0)
    place(x, BREAK[1], 0.025, 0.8)
    place(x, pitch(HIT[1], 0.7), 0.0, 0.6)          # the same crack, larger
    place(x, boom(0.8, 70, 0.18), 0.0, 0.9)
    return fade(norm(room(x, 2.6, 0.3)))


def sky_shatter():
    """The dome gives way: real shattering at several scales, a rain of falling glass, thunder."""
    dur = 8.0
    x = np.zeros(secs(dur))
    place(x, BIG, 0.0, 1.0)
    place(x, pitch(BIG, 0.62), 0.0, 0.85)           # a huge pane: lower, slower
    for i, b in enumerate(BREAK):
        place(x, pitch(b, rng.uniform(0.75, 1.1)), rng.uniform(0.02, 1.3), rng.uniform(0.4, 0.8))
    for i in range(36):                              # pieces raining down and smashing below
        at = 0.8 + rng.exponential(1.6)
        if at > dur - 0.8:
            continue
        f = FALL[rng.integers(len(FALL))] if rng.random() < 0.7 else BREAK[rng.integers(len(BREAK))]
        place(x, pitch(f, rng.uniform(0.85, 1.2)), at, rng.uniform(0.15, 0.5) * np.exp(-(at - 0.8) / 3.0))
    t = np.arange(len(x)) / SR
    rumble = lp(rng.standard_normal(len(x)), 120) * np.exp(-t / 2.4) * 3.0
    rumble *= 0.6 + 0.4 * np.abs(np.sin(2 * np.pi * 0.9 * t + 0.3))
    x = x + boom(dur, 52, 1.0, 1.2) + rumble
    return fade(norm(room(x, 3.0, 0.28)))


def glass_land(k):
    """A shard smashing on the ground: a real break plus its pieces skittering."""
    x = np.zeros(secs(1.8))
    place(x, pitch(BREAK[(2 * k) % 6], rng.uniform(0.8, 1.0)), 0.0, 1.0)
    place(x, FALL[k % 4], 0.12, 0.6)
    place(x, boom(0.3, 95, 0.06), 0.0, 0.5)
    return fade(norm(room(x, 0.8, 0.18)), 0.2)


def onset_slice(src, length):
    """A real crackle: the first milliseconds of a glass recording, faded out."""
    start = int(np.argmax(np.abs(src) > 0.05))
    s = src[start:start + secs(length)].copy()
    s *= np.exp(-np.arange(len(s)) / (len(s) * 0.35))
    return s


def crack_spread():
    """~12.5 s of cracks running through the dome, built from real glass crackle transients."""
    dur = 12.5
    x = np.zeros(secs(dur))
    sources = BREAK + HIT + FALL
    count = 240
    for i in range(count):
        at = dur * 0.96 * (i / count) ** 0.5 + rng.uniform(-0.03, 0.03)
        loud = 0.12 + 0.88 * (max(at, 0) / dur) ** 1.4
        src = sources[rng.integers(len(sources))]
        s = onset_slice(src, rng.uniform(0.03, 0.12))
        place(x, pitch(s, rng.uniform(0.8, 1.35)), max(0.0, at), loud * rng.uniform(0.4, 1.0))
    t = np.arange(len(x)) / SR
    groan = lp(rng.standard_normal(len(x)), 90) * 2.5 * (t / dur) ** 2
    x = x + groan
    return fade(norm(room(x, 2.2, 0.22), 0.88), 0.5)


def sky_restore():
    """The sky mends: real glass played backwards, converging, then a soft settle."""
    dur = 10.0
    x = np.zeros(secs(dur))
    for i in range(30):
        f = (BREAK + FALL)[rng.integers(10)]
        rev = pitch(f, rng.uniform(0.8, 1.1))[::-1]
        end = rng.uniform(1.5, 8.5)
        place(x, rev, max(0.0, end - len(rev) / SR), rng.uniform(0.2, 0.6))
    big = pitch(BIG, 0.7)[::-1]
    place(x, big, 8.6 - len(big) / SR, 0.9)
    return fade(norm(room(x, 3.0, 0.3)), 1.2)


def write(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    out = sys.argv[1]
    jobs = {"glass_snap": glass_snap, "sky_shatter": sky_shatter, "sky_crack": crack_spread,
            "sky_restore": sky_restore, "glass_land1": lambda: glass_land(0), "glass_land2": lambda: glass_land(1),
            "glass_land3": lambda: glass_land(2)}
    for name, fn in jobs.items():
        x = fn()
        wav = os.path.join(out, name + ".wav")
        write(wav, x)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "6",
                        os.path.join(out, name + ".ogg")], check=True)
        os.remove(wav)
        print(name, f"{len(x) / SR:.1f}s")


if __name__ == "__main__":
    main()
