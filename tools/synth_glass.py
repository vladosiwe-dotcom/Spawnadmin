"""Glass sound effects built from physical-ish modal synthesis (no sine 'pings', no bells).

Each glass fragment hit = a sub-millisecond noise impulse driving a bank of narrow resonators
(2.5-11 kHz, high Q, fast decay), plus a broadband click. Thousands of these, timed like real
breaking glass, give a crisp, clear shatter.

Usage: python3 tools/synth_glass.py <output_dir>
"""
import os
import subprocess
import sys
import wave

import numpy as np
from scipy.signal import butter, iirpeak, lfilter, fftconvolve

SR = 44100
rng = np.random.default_rng(424242)


def secs(s):
    return int(round(s * SR))


def lp(x, fc, order=2):
    b, a = butter(order, fc / (SR / 2), "low")
    return lfilter(b, a, x)


def hp(x, fc, order=2):
    b, a = butter(order, fc / (SR / 2), "high")
    return lfilter(b, a, x)


def hit(amp=1.0, bright=1.0, length=0.14):
    """One fragment striking or snapping: crisp, glassy, short."""
    n = secs(length)
    imp = np.zeros(n)
    k = max(2, int(SR * 0.0004))
    imp[:k] = rng.standard_normal(k)
    out = np.zeros(n)
    for _ in range(4):
        f = rng.uniform(2500, 11000) * (0.8 + 0.4 * bright)
        f = min(f, SR / 2 - 500)
        q = rng.uniform(25, 70)
        b, a = iirpeak(f / (SR / 2), q)
        out += lfilter(b, a, imp) * rng.uniform(0.4, 1.0)
    click = hp(rng.standard_normal(n), 4000) * np.exp(-np.arange(n) / (SR * 0.0012))
    env = np.exp(-np.arange(n) / (SR * length * 0.25))
    return (out * 6 + click * 0.6) * env * amp


def place(buf, sig, at):
    i = secs(at)
    if i >= len(buf):
        return
    sig = sig[: len(buf) - i]
    buf[i:i + len(sig)] += sig


def room(x, seconds=1.5, mix=0.25, bright=7000):
    n = secs(seconds)
    ir = lp(rng.standard_normal(n), bright) * np.exp(-np.arange(n) / SR * (6.9 / seconds))
    ir /= np.sqrt(np.sum(ir ** 2)) + 1e-9
    wet = fftconvolve(x, ir)[: len(x)]
    return x * (1 - mix) + wet * mix * 1.5


def norm(x, peak=0.9):
    return x / (np.max(np.abs(x)) + 1e-9) * peak


def glass_press():
    """Glass under enormous load: stick-slip creaks rising in pitch, a deep groan, first ticks."""
    dur = 3.6
    x = np.zeros(secs(dur))
    t = 0.05
    while t < dur - 0.4:
        p = t / dur
        rate = 25 + 140 * p ** 1.5            # stick-slip events per second
        f = 500 + 1600 * p                     # creak pitch rises as the load grows
        n = secs(0.02)
        imp = np.zeros(n)
        imp[0] = 1.0
        b, a = iirpeak(f / (SR / 2), 18)
        creak = lfilter(b, a, imp) * (0.2 + 0.8 * p)
        place(x, creak, t)
        t += 1.0 / rate * rng.uniform(0.6, 1.4)
    tt = np.arange(len(x)) / SR
    groan = lp(rng.standard_normal(len(x)), 110) * (0.4 + 0.6 * (tt / dur) ** 2) * (0.7 + 0.3 * np.sin(2 * np.pi * 0.8 * tt))
    x = x * 3 + groan * 4
    for at in (2.6, 3.0, 3.25, 3.4):          # the first tiny ticks just before it gives
        place(x, hit(0.25, 0.8, 0.06), at)
    x *= np.minimum(1, tt / 0.4)
    return norm(room(x, 1.8, 0.3, 5000), 0.7)


def glass_snap():
    """The sky snaps: one hard, bright crack, a burst of splitting, a dull thump, the echo."""
    dur = 3.0
    x = np.zeros(secs(dur))
    place(x, hit(3.0, 1.2, 0.25), 0.0)
    for i in range(26):                        # cracks racing outward in a fraction of a second
        place(x, hit(rng.uniform(0.3, 1.0) * (1 - i / 30), rng.uniform(0.8, 1.2), 0.1), 0.004 + rng.exponential(0.06))
    tt = np.arange(len(x)) / SR
    thump = np.sin(2 * np.pi * 70 * tt * (1 - 0.2 * tt)) * np.exp(-tt / 0.18) * 1.2
    crackle = hp(rng.standard_normal(len(x)), 3000) * np.exp(-tt / 0.03) * 1.5
    x = x + thump + crackle
    return norm(room(x, 2.6, 0.35, 8000), 0.95)


def crack_spread():
    """~12 s of cracks running through the dome: sparse at first, denser, louder, with groans."""
    dur = 12.5
    x = np.zeros(secs(dur))
    count = 420
    for i in range(count):
        at = dur * 0.96 * (i / count) ** 0.5 + rng.uniform(-0.03, 0.03)
        loud = 0.15 + 0.85 * (max(at, 0) / dur) ** 1.4
        place(x, hit(rng.uniform(0.2, 0.8) * loud, rng.uniform(0.6, 1.1), rng.uniform(0.04, 0.12)), max(0, at))
    tt = np.arange(len(x)) / SR
    groan = lp(rng.standard_normal(len(x)), 90) * 5 * (tt / dur) ** 2
    x = x + groan
    x *= np.minimum(1, (dur - tt) / 0.4)
    return norm(room(x, 2.2, 0.28, 9000), 0.85)


def sky_shatter():
    """The dome gives way: a huge boom and rolling thunder of glass, thousands of fragments."""
    dur = 8.0
    x = np.zeros(secs(dur))
    tt = np.arange(len(x)) / SR
    boom = np.sin(2 * np.pi * np.cumsum(55 * (1 - 0.35 * np.minimum(tt, 1.5))) / SR) * np.exp(-tt / 0.9) * 2.0
    rumble = lp(rng.standard_normal(len(x)), 140) * np.exp(-tt / 2.4) * 6
    rumble *= 0.6 + 0.4 * np.abs(np.sin(2 * np.pi * 0.9 * tt + 0.3))  # rolling, like thunder
    place(x, hit(4.0, 1.3, 0.3), 0.0)
    for i in range(1400):                      # the shatter: dense at once, then a long rain of pieces
        at = rng.exponential(0.9)
        if at > dur - 0.3:
            continue
        amp = rng.uniform(0.2, 1.0) * (0.35 + 0.65 * np.exp(-at / 1.6))
        place(x, hit(amp, rng.uniform(0.7, 1.25), rng.uniform(0.05, 0.16)), at)
    burst = hp(rng.standard_normal(len(x)), 2500) * np.exp(-tt / 0.25) * 2
    x = x * 0.7 + boom + rumble + burst
    return norm(room(x, 3.0, 0.3, 7000), 0.95)


def glass_land(seed):
    """A shard smashing on the ground: a crunching impact and its pieces skittering."""
    local = np.random.default_rng(seed)
    global rng
    rng = local
    dur = 1.6
    x = np.zeros(secs(dur))
    tt = np.arange(len(x)) / SR
    x += np.sin(2 * np.pi * 95 * tt) * np.exp(-tt / 0.05) * 0.8
    place(x, hit(2.0, 1.0, 0.18), 0.0)
    for _ in range(45):
        place(x, hit(local.uniform(0.2, 0.9), local.uniform(0.7, 1.2), local.uniform(0.04, 0.1)), local.exponential(0.06))
    for _ in range(12):                        # little pieces bouncing away
        place(x, hit(local.uniform(0.05, 0.25), 1.1, 0.05), 0.2 + local.exponential(0.35))
    return norm(room(x, 0.8, 0.2, 8000), 0.9)


def write(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    out = sys.argv[1]
    jobs = {"glass_press": glass_press, "glass_snap": glass_snap, "sky_crack": crack_spread,
            "sky_shatter": sky_shatter, "glass_land1": lambda: glass_land(1), "glass_land2": lambda: glass_land(2),
            "glass_land3": lambda: glass_land(3)}
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
