"""Cinematic sounds of Heavenly Summoning: the horn, the opening hit, the riser into the snap and
the pull that drags a runaway target back.

The horn layers a real CC0 horn recording (tools/sfx_sources/horn_theircoming3.ogg) under a
synthesized brass voice whose brightness follows its loudness, a sub octave, a low choir and a
large hall. Everything else is synthesized. No bells or chimes anywhere.

Usage: python3 tools/build_cine_sfx.py <output_dir>
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
rng = np.random.default_rng(4242)


def load(name):
    raw = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", os.path.join(SRC, name), "-f", "s16le", "-ac", "1",
                          "-ar", str(SR), "-"], capture_output=True, check=True).stdout
    return np.frombuffer(raw, np.int16) / 32768.0


def secs(s):
    return int(round(s * SR))


def lp(x, fc, order=2):
    b, a = butter(order, fc / (SR / 2), "low")
    return lfilter(b, a, x)


def hp(x, fc, order=2):
    b, a = butter(order, fc / (SR / 2), "high")
    return lfilter(b, a, x)


def bp(x, lo, hi):
    b, a = butter(2, [lo / (SR / 2), hi / (SR / 2)], "band")
    return lfilter(b, a, x)


def place(buf, sig, at, gain=1.0):
    i = secs(at)
    if i >= len(buf):
        return
    sig = sig[: len(buf) - i]
    buf[i:i + len(sig)] += sig * gain


def hall(x, seconds, mix, bright=6000, predelay=0.05):
    n = secs(seconds)
    tail = rng.standard_normal(n) * np.exp(-np.arange(n) / SR * (6.9 / seconds))
    tail = lp(tail, bright)
    tail = np.concatenate([np.zeros(secs(predelay)), tail])
    wet = fftconvolve(x, tail)[: len(x)]
    wet *= np.max(np.abs(x)) / (np.max(np.abs(wet)) + 1e-9)
    return x * (1 - mix) + wet * mix


def interp_curve(points, n):
    """Piecewise-linear curve through (time, value) points, sampled at n samples."""
    t = np.arange(n) / SR
    ts, vs = zip(*points)
    return np.interp(t, ts, vs)


def normalize(x, peak=0.9):
    return x / (np.max(np.abs(x)) + 1e-9) * peak


def fades(x, fade_in=0.004, fade_out=0.08):
    x = x.copy()
    a, b = secs(fade_in), secs(fade_out)
    if a:
        x[:a] *= np.linspace(0, 1, a)
    if b:
        x[-b:] *= np.linspace(1, 0, b)
    return x


def brass(f0, env, max_hz=9000):
    """Band-limited brass voice: the louder it plays, the brighter (more harmonics) it gets."""
    phase = 2 * np.pi * np.cumsum(f0) / SR
    cutoff = 280 + 3200 * env ** 1.6
    out = np.zeros(len(f0))
    kmax = int(max_hz / f0.min())
    for k in range(1, kmax + 1):
        amp = (1.0 / k) * np.exp(-(k * f0) / cutoff)
        amp[k * f0 > max_hz] = 0
        out += amp * np.sin(k * phase + 0.21 * k * k)
    # lip buzz: a touch of roughness at the loudest moments
    out = np.tanh(out * (1.0 + 1.8 * env)) / np.tanh(1.0 + 1.8 * env + 1e-9)
    return out * env


# ---------------------------------------------------------------------------- horn

def heaven_horn():
    """Two long calls: a low, swelling one and a second a fifth higher that rises at its end."""
    dur = 11.5
    n = secs(dur)
    D2, G2, A2, D3 = 73.42, 98.0, 110.0, 146.83
    f0 = interp_curve([(0, D2 * 0.92), (0.28, D2), (3.55, D2 * 0.99), (3.75, G2), (3.95, A2), (6.7, A2),
                       (7.25, D3 * 0.98), (7.5, D3), (dur, D3)], n)
    t = np.arange(n) / SR
    vib = 1 + 0.0055 * np.sin(2 * np.pi * 5.1 * t) * np.clip((t - 1.0) / 1.2, 0, 1) * (t < 3.5) \
            + 0.0065 * np.sin(2 * np.pi * 5.4 * t + 1) * np.clip((t - 5.0) / 1.0, 0, 1)
    f0 = f0 * vib
    env = interp_curve([(0, 0), (0.12, 0.35), (0.5, 0.62), (2.2, 0.8), (3.35, 0.72), (3.6, 0.12), (3.85, 0.1),
                        (4.1, 0.55), (5.5, 0.78), (6.8, 1.0), (7.5, 1.0), (8.1, 0.85), (8.7, 0.0), (dur, 0)], n)
    voice = brass(f0, env)
    # Second horn a hair detuned for width and power.
    voice += 0.55 * brass(f0 * 1.004, env * 0.92)
    sub = np.sin(2 * np.pi * np.cumsum(f0 * 0.5) / SR) * env ** 1.3 * 0.55

    # The real horn under the synthesized one.
    rec = hp(lp(load("horn_theircoming3.ogg"), 2600), 40)
    rec = rec / (np.max(np.abs(rec)) + 1e-9)
    real = np.zeros(n)
    first = rec[: secs(3.5)].copy()
    first[-secs(0.4):] *= np.linspace(1, 0, secs(0.4))
    first[: secs(0.15)] *= np.linspace(0, 1, secs(0.15))
    place(real, first, 0.05, 0.55)
    up = resample(rec, int(len(rec) / (A2 / 72.0)))[: secs(4.4)]
    up[: secs(0.25)] *= np.linspace(0, 1, secs(0.25))
    up[-secs(0.6):] *= np.linspace(1, 0, secs(0.6))
    place(real, up, 3.95, 0.5)

    # A low choir ("aah") swells under the second call: saws through two vowel formants.
    choir = np.zeros(n)
    for f, a in ((D3, 0.5), (A2 * 2, 0.4), (D3 * 2, 0.25)):
        ph = np.cumsum(f * (1 + 0.003 * np.sin(2 * np.pi * (0.3 + f / 900) * t))) / SR
        saw = 2 * (ph % 1.0) - 1
        choir += a * (bp(saw, 600, 900) + 0.6 * bp(saw, 1000, 1300))
    choir *= interp_curve([(0, 0), (4.2, 0), (6.6, 0.7), (8.0, 0.8), (9.3, 0), (dur, 0)], n)

    breath = bp(rng.standard_normal(n), 300, 2500) * interp_curve(
        [(0, 0), (0.05, 0.25), (0.4, 0.06), (3.55, 0.06), (3.7, 0.3), (3.95, 0.05), (8.6, 0.05), (8.75, 0.15), (9.2, 0), (dur, 0)], n)

    x = voice + sub + real + 0.35 * choir + 0.4 * breath
    x = hp(x, 28)
    # A mountain echo and a large hall.
    echo = np.zeros(n)
    place(echo, lp(x, 1800), 0.42, 0.22)
    place(echo, lp(x, 1200), 0.95, 0.1)
    x = hall(x + echo, 4.5, 0.42, bright=4200, predelay=0.06)
    return fades(normalize(x, 0.92), 0.002, 1.2)


# ---------------------------------------------------------------------------- opening hit

def cine_hit():
    """The world dims: a deep, wide brass hit with a falling sub and a soft impact."""
    dur = 6.0
    n = secs(dur)
    t = np.arange(n) / SR
    env = interp_curve([(0, 0), (0.03, 1.0), (0.6, 0.75), (2.5, 0.35), (5.0, 0.0), (dur, 0)], n)
    brightness = interp_curve([(0, 0.95), (0.4, 0.7), (3.0, 0.25), (dur, 0.1)], n)
    x = np.zeros(n)
    for f, a in ((36.71, 0.8), (55.0, 0.6), (73.42, 0.55), (82.41, 0.25)):
        f0 = np.full(n, f) * (1 + 0.002 * np.sin(2 * np.pi * 0.7 * t + f))
        x += a * brass(f0, env * brightness)
    sub_f = interp_curve([(0, 62), (0.5, 44), (dur, 33)], n)
    x += 0.9 * np.sin(2 * np.pi * np.cumsum(sub_f) / SR) * interp_curve([(0, 0), (0.01, 1), (1.5, 0.5), (4.5, 0), (dur, 0)], n)
    thud = lp(rng.standard_normal(n), 180) * np.exp(-t / 0.12) * 3.0
    air = bp(rng.standard_normal(n), 200, 1200) * np.exp(-t / 0.9) * 0.25
    x = hp(x + thud + air, 22)
    x = hall(x, 4.0, 0.35, bright=2500, predelay=0.03)
    return fades(normalize(x, 0.95), 0.001, 0.6)


# ---------------------------------------------------------------------------- riser

def cine_riser():
    """A reversed swell, 2.5 s, that ends exactly where the snap begins."""
    dur = 2.5
    n = secs(dur)
    t = np.arange(n) / SR
    # Reversed glass resonance: a struck pane smeared by a hall, played backwards.
    hit = load("bfh1_glass_hit_01.ogg")
    hit = resample(hit, int(len(hit) / 0.6))
    body = np.zeros(n)
    place(body, hit, 0.0, 1.0)
    body = hall(body, 2.4, 0.85, bright=7000, predelay=0.0)[::-1]
    # Rising air and a low tone climbing a fourth into the snap.
    air = bp(rng.standard_normal(n), 900, 7000) * (t / dur) ** 3
    tone_f = 55 * 2 ** (5 / 12 * (t / dur) ** 2)
    tone = np.sin(2 * np.pi * np.cumsum(tone_f) / SR) * (t / dur) ** 2 * 0.6
    tone += 0.3 * np.sin(2 * np.pi * np.cumsum(tone_f * 2.01) / SR) * (t / dur) ** 3
    x = normalize(body) * 0.8 + air * 0.5 + tone
    # Sucked into silence for the last few milliseconds: the snap hits into a vacuum.
    x[-secs(0.03):] *= np.linspace(1, 0, secs(0.03))
    return fades(normalize(x, 0.85), 0.05, 0.0)


# ---------------------------------------------------------------------------- pull

def leash_pull():
    """Something unseen drags the runaway back: a reversed boom, a stretched low groan, a rush of air."""
    dur = 1.8
    n = secs(dur)
    t = np.arange(n) / SR
    boom = lp(rng.standard_normal(secs(1.2)), 220) * np.exp(-np.arange(secs(1.2)) / SR / 0.25)
    boom = hall(boom, 0.9, 0.5, bright=1500)[::-1]
    x = np.zeros(n)
    place(x, boom / (np.max(np.abs(boom)) + 1e-9), 0.0, 0.9)
    f = interp_curve([(0, 180), (0.7, 90), (1.4, 55), (dur, 50)], n)
    wob = 1 + 0.06 * np.sin(2 * np.pi * 7 * t)
    groan = np.tanh(2.5 * np.sin(2 * np.pi * np.cumsum(f * wob) / SR)) * interp_curve([(0, 0), (0.6, 0.5), (1.2, 0.7), (dur, 0)], n)
    x += lp(groan, 900) * 0.6
    sweep = np.zeros(n)
    noise = rng.standard_normal(n)
    for i, (lo, hi) in enumerate(((2400, 4800), (1400, 2800), (700, 1500), (350, 800))):
        seg = bp(noise, lo, hi) * np.exp(-((t - 0.5 - i * 0.25) / 0.22) ** 2)
        sweep += seg
    x += sweep * 0.8
    x = hall(hp(x, 30), 1.2, 0.25, bright=3000)
    return fades(normalize(x, 0.9), 0.01, 0.2)


SOUNDS = {
    "heaven_horn": heaven_horn,
    "cine_hit": cine_hit,
    "cine_riser": cine_riser,
    "leash_pull": leash_pull,
}


def write_ogg(out_dir, name, x):
    wav = os.path.join(out_dir, name + ".wav")
    with wave.open(wav, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype(np.int16).tobytes())
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "6",
                    os.path.join(out_dir, name + ".ogg")], check=True)
    os.remove(wav)


def main():
    out_dir = sys.argv[1]
    only = set(sys.argv[2:])
    for name, fn in SOUNDS.items():
        if only and name not in only:
            continue
        x = fn()
        write_ogg(out_dir, name, x)
        print(name, f"{len(x) / SR:.1f}s")


if __name__ == "__main__":
    main()
