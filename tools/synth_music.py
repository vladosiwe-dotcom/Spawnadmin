"""Original music and clock sounds for Heavenly Summoning, fully synthesized.

Usage: python3 tools/synth_music.py <output_dir>
Requires numpy + scipy and ffmpeg (libvorbis).

* clock_tick / clock_tock  – the hidden wait: a clock counting down the seconds
* hour_strike              – the hour has come: three deep, dark strokes of a tower clock
* clock_halt               – time stops: the last tick and a wooden thud, then silence
* theme_summoning          – score, 33 s, aligned to the event timeline (no bells or chimes)
* music_admin_realm        – quiet ambient piano for the administrator's realm (~85 s)

Timeline of the theme (seconds after the sky starts to glow, matches SkyTimeline):
GLOW 0, CRACK 2, SHATTER 7, FALL 8.25, FREEZE 11.75, HAND 13.25, GRAB 16.25,
LIFT 17.25, TELEPORT 19.5, RESTORE 20 .. 27.
"""
import os
import subprocess
import sys
import wave

import numpy as np
from scipy.signal import butter, fftconvolve, lfilter

SR = 44100
RNG = np.random.default_rng(1108)


# ------------------------------------------------------------------ helpers

def secs(n):
    return int(round(n * SR))


def midi(m):
    return 440.0 * 2 ** ((m - 69) / 12.0)


NOTE = {"C": 0, "C#": 1, "Db": 1, "D": 2, "D#": 3, "Eb": 3, "E": 4, "F": 5, "F#": 6, "Gb": 6,
        "G": 7, "G#": 8, "Ab": 8, "A": 9, "A#": 10, "Bb": 10, "B": 11}


def hz(name):
    """'F#4' -> frequency."""
    pitch, octave = name[:-1], int(name[-1])
    return midi(12 * (octave + 1) + NOTE[pitch])


def buf(seconds, stereo=True):
    return np.zeros((secs(seconds), 2)) if stereo else np.zeros(secs(seconds))


def add(dst, sig, at, pan=0.0, gain=1.0):
    """Mix a mono signal into a stereo buffer at time `at` with equal-power pan (-1..1)."""
    i = secs(at)
    if i >= len(dst):
        return
    sig = sig[: len(dst) - i] * gain
    a = (pan + 1) * np.pi / 4
    dst[i:i + len(sig), 0] += sig * np.cos(a)
    dst[i:i + len(sig), 1] += sig * np.sin(a)


def lp(x, cutoff, order=2):
    b, a = butter(order, cutoff / (SR / 2), "low")
    return lfilter(b, a, x, axis=0)


def hp(x, cutoff, order=2):
    b, a = butter(order, cutoff / (SR / 2), "high")
    return lfilter(b, a, x, axis=0)


def env(n, attack, release, total=None):
    e = np.ones(n)
    a, r = secs(attack), secs(release)
    if a > 0:
        e[:a] = np.linspace(0, 1, a) ** 1.5
    if r > 0:
        e[-r:] *= np.linspace(1, 0, r) ** 1.5
    return e


def reverb(x, seconds=4.0, mix=0.4, bright=5000, seed=0):
    """Stereo convolution reverb with a decorrelated noise tail per channel."""
    rng = np.random.default_rng(seed)
    n = secs(seconds)
    decay = np.exp(-np.arange(n) / SR * (6.9 / seconds))
    out = np.zeros((len(x) + n - 1, 2))
    for ch in range(2):
        ir = lp(rng.standard_normal(n) * decay, bright)
        ir /= np.sqrt(np.sum(ir ** 2)) + 1e-9
        out[:, ch] = fftconvolve(x[:, ch], ir)
    dry = np.zeros_like(out)
    dry[: len(x)] = x
    return dry * (1 - mix) + out * mix * 1.6


def normalize(x, peak):
    return x / (np.max(np.abs(x)) + 1e-9) * peak


# ------------------------------------------------------------------ instruments

def strings(freqs, dur, amp=1.0, attack=0.6, release=1.0, bright=2600, vibrato=0.0025, voices=4):
    """Bowed string section: detuned band-limited saws, delayed vibrato, bow noise, body resonances."""
    t = np.arange(secs(dur)) / SR
    out = np.zeros((len(t), 2))
    for f in freqs:
        for v in range(voices):
            ch = v % 2
            det = 1 + (v - (voices - 1) / 2) * 0.0018
            onset = np.clip((t - 0.25) / 0.6, 0, 1)
            vib = 1 + vibrato * onset * np.sin(2 * np.pi * (5.2 + 0.3 * v) * t + v * 1.7)
            phase = 2 * np.pi * np.cumsum(f * det * vib) / SR + RNG.uniform(0, 2 * np.pi)
            saw = np.zeros_like(t)
            for k in range(1, 24):
                if f * k > 9000:
                    break
                saw += np.sin(k * phase) / k
            out[:, ch] += saw
        bow = lp(hp(RNG.standard_normal(len(t)), f * 2), f * 8) * 0.04
        out += bow[:, None]
    # Body: a warm low-mid bump and a softened top, like wood rather than a synth.
    b1, a1 = butter(2, [250 / (SR / 2), 1200 / (SR / 2)], "band")
    out = lp(out, bright) + lfilter(b1, a1, out, axis=0) * 0.6
    e = env(len(t), attack, release)
    return out * e[:, None] * amp / max(1, len(freqs) * voices)


def spiccato(f, amp=1.0):
    """Short bouncing bow stroke."""
    x = strings([f], 0.22, amp, 0.008, 0.12, bright=3200, vibrato=0.0, voices=2)
    return x.mean(axis=1)


def pizz(f, dur=1.2, amp=1.0):
    """Plucked string (Karplus-Strong): woody, round, decays fast. Not a bell."""
    n = secs(dur)
    period = max(2, int(round(SR / f)))
    excite = np.zeros(n)
    excite[:period] = lp(RNG.standard_normal(period), min(4000, f * 10))
    a = np.zeros(period + 2)
    a[0] = 1.0
    a[period] = -0.497
    a[period + 1] = -0.497
    y = lfilter([1.0], a, excite)
    y = lp(y, 2500)
    return y * env(n, 0.002, 0.1) * amp / (np.max(np.abs(y)) + 1e-9)


def piano(f, dur=4.0, amp=1.0, soft=0.012):
    """Soft felt piano: gentle hammer, warm partials, slow decay."""
    t = np.arange(secs(dur)) / SR
    x = np.zeros_like(t)
    for k, (a, d) in enumerate([(1.0, 3.2), (0.42, 1.8), (0.18, 1.0), (0.08, 0.6), (0.04, 0.35)], start=1):
        x += a * np.sin(2 * np.pi * f * k * (1 + 0.0004 * k * k) * t) * np.exp(-t / d)
    x = lp(x, 2600)
    x *= env(len(t), soft, 0.3)
    return x * amp


def pad(freqs, dur, amp=1.0, attack=1.5, release=2.0, cutoff=1400, detune=0.004):
    """Warm string pad: band-limited saws, detuned left/right, low-passed."""
    t = np.arange(secs(dur)) / SR
    out = np.zeros((len(t), 2))
    for f in freqs:
        for ch, d in ((0, 1 - detune), (1, 1 + detune)):
            ph = RNG.uniform(0, 2 * np.pi)
            saw = np.zeros_like(t)
            for k in range(1, 9):
                saw += np.sin(2 * np.pi * f * d * k * t + ph * k) / k
            out[:, ch] += saw
    out = lp(out, cutoff)
    e = env(len(t), attack, release)
    return out * e[:, None] * amp / max(1, len(freqs))


def choir(freqs, dur, amp=1.0, attack=1.8, release=1.5):
    """'Aah' choir: harmonics weighted by vowel formants, gentle vibrato, ensemble detune."""
    t = np.arange(secs(dur)) / SR
    formants = [(800, 90), (1150, 110), (2900, 160)]
    out = np.zeros((len(t), 2))
    for f in freqs:
        for voice in range(3):
            ch = voice % 2
            vib = 1 + 0.005 * np.sin(2 * np.pi * (4.8 + voice * 0.4) * t + voice)
            f0 = f * (1 + (voice - 1) * 0.003)
            phase = 2 * np.pi * np.cumsum(f0 * vib) / SR
            for k in range(1, 30):
                fk = f0 * k
                if fk > 5000:
                    break
                w = sum(np.exp(-((fk - fc) / bw) ** 2 / 2) * g for (fc, bw), g in zip(formants, (1.0, 0.7, 0.25)))
                w += 0.15 / k
                out[:, ch] += w * np.sin(k * phase)
    e = env(len(t), attack, release)
    return lp(out, 4000) * e[:, None] * amp / max(1, len(freqs) * 3)


def sine(f, dur, amp=1.0, attack=1.0, release=1.0):
    t = np.arange(secs(dur)) / SR
    return np.sin(2 * np.pi * f * t) * env(len(t), attack, release) * amp


def bell(f, dur, amp=1.0):
    """Tuned clock bell: hum, prime, minor third, fifth, nominal and upper partials."""
    t = np.arange(secs(dur)) / SR
    parts = [(0.5, 0.55, 7.0), (1.0, 1.0, 5.0), (1.19, 0.5, 3.5), (1.5, 0.35, 2.8), (2.0, 0.6, 2.2),
             (2.51, 0.25, 1.3), (3.01, 0.18, 0.9), (4.17, 0.1, 0.5)]
    x = np.zeros_like(t)
    for ratio, a, d in parts:
        beat = 1 + 0.003 * np.sin(2 * np.pi * (0.7 + ratio * 0.3) * t)
        x += a * np.sin(2 * np.pi * f * ratio * t) * np.exp(-t / d) * beat
    strike = hp(RNG.standard_normal(len(t)), 2500) * np.exp(-t / 0.012) * 0.6
    return (x + strike) * env(len(t), 0.001, 0.2) * amp


# ------------------------------------------------------------------ clock sounds (mono)

def tick(pitch=1.0):
    t = np.arange(secs(0.3)) / SR
    click = hp(RNG.standard_normal(len(t)), 3000) * np.exp(-t / 0.0015)
    body = np.zeros_like(t)
    for f, a, d in ((2400, 0.6, 0.025), (1250, 0.45, 0.04), (620, 0.35, 0.03)):
        body += a * np.sin(2 * np.pi * f * pitch * t) * np.exp(-t / d)
    x = click * 0.8 + body
    room = fftconvolve(x, lp(RNG.standard_normal(secs(0.4)), 4000) * np.exp(-np.arange(secs(0.4)) / SR * 17))[: len(x)]
    return normalize(x + room * 0.02, 0.85)


def hour_strike():
    """The hour has come: three deep, dark strokes of a tower clock (low and filtered, no tinkle)."""
    total = 8.5
    out = buf(total)
    for i, at in enumerate((0.0, 1.7, 3.4)):
        add(out, lp(bell(hz("G2"), 5.0, 1.0 - i * 0.08), 1400), at, pan=(-0.15, 0.15, 0.0)[i])
    out = reverb(out, 3.5, 0.35, 4000, seed=3)
    return normalize(out[: secs(total)].mean(axis=1), 0.9)


def clock_halt():
    """The clock stops: a last heavy tick, a low wooden thud of the mechanism, then dead silence."""
    total = 1.6
    x = np.zeros(secs(total))
    tk = tick(0.7)
    x[: len(tk)] += tk
    t = np.arange(secs(0.9)) / SR
    thud = (np.sin(2 * np.pi * 110 * t) * 0.6 + np.sin(2 * np.pi * 165 * t) * 0.25) * np.exp(-t / 0.18)
    i = secs(0.03)
    x[i:i + len(thud)] += lp(thud, 800)
    return normalize(x, 0.85)


# ------------------------------------------------------------------ theme (stereo, 33 s)

def warped_piano(f, dur=1.8, amp=1.0, warp=0.0):
    """Felt piano heard through a worn tape: the pitch sags and wobbles as `warp` grows."""
    t = np.arange(secs(dur)) / SR
    wow = 1.0 - warp * (1 - np.exp(-t / 0.5)) + warp * 0.4 * np.sin(2 * np.pi * 0.9 * t)
    x = np.zeros_like(t)
    for k, (a, d) in enumerate([(1.0, 2.6), (0.4, 1.4), (0.15, 0.8), (0.06, 0.4)], start=1):
        x += a * np.sin(2 * np.pi * np.cumsum(f * k * wow) / SR) * np.exp(-t / d)
    return lp(x, 2400) * env(len(t), 0.012, 0.25) * amp


def tape_stop(x, at, length):
    """Everything after `at` slows to a halt over `length` seconds, like a tape machine losing power."""
    i0, n = secs(at), secs(length)
    t = np.arange(n) / n
    rate = (1 - t) ** 1.6
    pos = i0 + np.cumsum(rate)
    out = x.copy()
    for ch in range(x.shape[1]):
        out[i0:i0 + n, ch] = np.interp(pos, np.arange(len(x)), x[:, ch]) * (1 - t ** 3)
    out[i0 + n:] = 0
    return out


def drum(f0=72.0, dur=0.9, amp=1.0):
    t = np.arange(secs(dur)) / SR
    f = f0 * (0.6 + 0.4 * np.exp(-t / 0.08))
    x = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-t / 0.28)
    x += lp(RNG.standard_normal(len(t)), 900) * np.exp(-t / 0.02) * 0.5
    return x * amp


def shepard(dur, start_rate=0.15, end_rate=1.2, amp=1.0):
    """Endlessly rising tone: octave-spaced partials gliding up under a fixed bell-shaped envelope."""
    t = np.arange(secs(dur)) / SR
    rate = start_rate + (end_rate - start_rate) * (t / dur) ** 2
    pos = np.cumsum(rate) / SR  # octaves travelled
    out = np.zeros_like(t)
    for k in range(7):
        octv = (k + pos) % 7.0
        f = 55.0 * 2 ** octv
        w = np.exp(-((octv - 3.5) / 1.4) ** 2)
        out += w * np.sin(2 * np.pi * np.cumsum(f) / SR)
    return out * env(len(t), 0.4, 0.15) * amp


def theme():
    """43 s score, aligned to SkyTimeline (seconds after the first crack):
    PRESS 0, FIRST_CRACK (snap) 3, CONNECT 10, CHIPS 12.5, FRAGMENTS 13.75, FALL 15, FREEZE 21, HAND 22.5,
    GRAB 26.5, GRIP 27.3, LIFT 28, TELEPORT 31, RESTORE 32 .. 42.
    No bells, chimes, celesta or music box: piano, strings, plucks, choir, drums only."""
    total = 43.0
    pre = buf(total)
    post = buf(total)
    FREEZE = 21.0

    # FIRST_CRACK: an innocent piano tune over hushed strings, as if nothing could go wrong.
    tune = ["E4", "G4", "C5", "B4", "G4", "A4", "G4", "E4", "D4", "E4", "G4", "C5", "D5", "B4", "G4", "C5",
            "E4", "G4", "C5", "B4", "A4", "G4"]
    beat = 0.5
    for i, n in enumerate(tune):
        at = 0.4 + i * beat
        warp = max(0.0, (at - 3.0) / 8.0) * 0.06  # from SPREAD on, the tape starts to sag
        if i in (9, 17):
            n = {9: "F#4", 17: "F#4"}[i]  # wrong notes creep in
        add(pre, warped_piano(hz(n), 2.0, 0.45, warp), at, -0.2 + 0.4 * (i % 2))
    s0 = strings([hz("C3"), hz("G3"), hz("E4")], 6.0, 0.3, 2.0, 2.0, bright=1700)
    pre[:len(s0)] += s0

    # SPREAD -> CONNECT: a cello line creeping up chromatically, plucks in an uneasy 5/8 that thicken.
    for i, n in enumerate(["D2", "Eb2", "E2", "F2", "F#2"]):
        c = strings([hz(n), hz(n) * 2], 2.4, 0.5 + 0.08 * i, 0.4, 0.6, bright=1500)
        i0 = secs(3.0 + i * 1.9)
        pre[i0:i0 + len(c)] += c
    at = 6.0
    k = 0
    while at < 13.2:
        add(pre, pizz(hz(["D3", "A3", "Eb3", "Bb3", "F3"][k % 5]), 1.0, 0.25 + 0.03 * k), at, float(RNG.uniform(-0.8, 0.8)))
        at += [0.34, 0.34, 0.5, 0.34, 0.5][k % 5] * max(0.45, 1 - k * 0.025)
        k += 1
    # CHIPS: a tension swell.
    c = strings([hz("A2"), hz("D3"), hz("Eb3"), hz("G#3")], 1.3, 0.8, 1.0, 0.05, bright=2600)
    pre[secs(12.5):secs(12.5) + len(c)] += c

    pre = reverb(pre, 3.0, 0.36, 5000, seed=11)[: secs(total)]
    # FRAGMENTS: sudden silence...
    pre[secs(13.75):] = 0
    # ...then a reversed piano chord sucking everything in.
    rev = buf(1.0)
    for n in ("D3", "A3", "Eb4", "G4", "D5"):
        add(rev, piano(hz(n), 1.0, 0.5), 0.0, 0.0)
    rev = reverb(rev, 1.6, 0.5, 4500, seed=12)[: secs(1.0)][::-1]
    pre[secs(14.0):secs(14.0) + len(rev)] += rev * 1.5

    # FALL: the hit. A dissonant string cluster, a sub boom, rushing string runs falling down a
    # whole-tone scale over a 7/8 drum, for the six seconds of falling glass.
    hit = buf(6.5)
    hit += strings([hz("D2"), hz("A2"), hz("D3"), hz("Eb3"), hz("F3"), hz("A3"), hz("Eb4")], 6.5, 1.5, 0.02, 2.0, bright=2800)
    add(hit, sine(hz("D1"), 2.5, 1.2, 0.002, 2.0), 0.0)
    whole = ["C", "D", "E", "F#", "G#", "A#"]
    at, step = 0.05, 0.12
    for i in range(48):
        octv = max(3, 5 - i // 7)
        n = whole[(len(whole) - 1 - i) % len(whole)] + str(octv)
        add(hit, spiccato(hz(n), 0.6), at, float(np.sin(i * 1.7)) * 0.7)
        at += step * (1 + 0.5 * np.sin(i * 0.9))
        if at > 5.8:
            break
    pattern = [0, 0.43, 0.86, 1.07, 1.5, 1.71, 1.93]  # 7/8 accents
    for bar_i in range(3):
        for k2, off in enumerate(pattern):
            add(hit, drum(72.0 if k2 in (0, 3) else 98.0, 0.9, 0.75 if k2 in (0, 3) else 0.35), 0.3 + bar_i * 2.0 + off, 0.0)
    hit = reverb(hit, 3.5, 0.35, 4500, seed=13)[: secs(6.5)]
    i0 = secs(15.0)
    pre[i0:i0 + len(hit)] += hit

    # FREEZE: the whole score winds down like a tape losing power.
    pre = tape_stop(pre, FREEZE - 0.15, 0.75)
    c = strings([hz("E2")], 1.6, 0.35, 0.3, 0.5, bright=900, vibrato=0.0)
    post[secs(FREEZE + 0.4):secs(FREEZE + 0.4) + len(c)] += c

    # HAND: the choir enters in a far, warm key (D minor -> Db major), strings beneath.
    c = choir([hz("Db3"), hz("F3"), hz("Ab3"), hz("Db4")], 4.2, 1.5, 2.0, 0.3)
    post[secs(22.5):secs(22.5) + len(c)] += c
    c = strings([hz("Db2"), hz("Ab2"), hz("F3")], 4.2, 0.6, 2.0, 0.3, bright=1600)
    post[secs(22.5):secs(22.5) + len(c)] += c
    # GRIP: an abrupt lift by a semitone, to D major, on a timpani stroke.
    c = choir([hz("D3"), hz("F#3"), hz("A3"), hz("D4"), hz("F#4")], 1.2, 1.7, 0.03, 0.4)
    post[secs(27.3):secs(27.3) + len(c)] += c
    add(post, drum(55.0, 1.2, 1.0), 27.3, 0.0)
    # LIFT: an endless rise.
    sh = shepard(3.0, 0.4, 2.2, 0.2)
    add(post, sh, 28.0, -0.2)
    add(post, sh * 0.8, 28.02, 0.2)

    # TELEPORT: a breath of silence, then the whole ensemble blooms (no bells).
    post[secs(31.0):secs(31.25)] *= np.linspace(1, 0, secs(0.25))[:, None]
    c = strings([hz("D2"), hz("A2"), hz("D3"), hz("F#3"), hz("A3"), hz("D4")], 3.5, 1.4, 0.15, 2.5, bright=3000)
    post[secs(31.25):secs(31.25) + len(c)] += c
    c = choir([hz("D3"), hz("A3"), hz("F#4")], 3.0, 1.1, 0.2, 2.0)
    post[secs(31.25):secs(31.25) + len(c)] += c
    for k2 in range(10):
        add(post, drum(62.0, 0.6, 0.15 + 0.05 * k2), 30.65 + k2 * 0.06, 0.0)

    # RESTORE: the piano tune again, first backwards, then forwards in a new key.
    box = buf(5.0)
    for i, n in enumerate(tune[:14]):
        add(box, piano(hz(n), 1.8, 0.45), i * 0.33, -0.2 + 0.4 * (i % 2))
    box = reverb(box, 2.5, 0.4, 4500, seed=14)[: secs(5.0)]
    post[secs(32.5):secs(32.5) + len(box)] += box[::-1] * 0.9
    for i, n in enumerate(["F#4", "A#4", "C#5", "F#5", "F5", "C#5", "A#4", "F#4"]):
        add(post, piano(hz(n), 2.4, 0.42), 37.8 + i * 0.45, -0.2 + 0.4 * (i % 2))
    c = strings([hz("F#2"), hz("C#3"), hz("A#3"), hz("F#4")], 5.5, 0.5, 1.5, 3.0, bright=1500)
    post[secs(37.5):secs(37.5) + len(c)] += c

    post = reverb(post, 4.0, 0.42, 4500, seed=15)[: secs(total)]
    out = pre + post
    out *= env(len(out), 0.6, 1.5)[:, None]
    return normalize(out, 0.8)


# ------------------------------------------------------------------ realm music (stereo)

def realm_music():
    """Quiet ambient piano for the administrator's realm: slow, sparse, lots of air."""
    bpm = 52
    beat = 60.0 / bpm
    bar = 4 * beat
    chords = [
        (["D2", "A2", "E3", "F#3", "C#4"], ["D", "E", "F#", "A", "C#"]),
        (["B1", "F#2", "D3", "E3", "A3"], ["B", "D", "E", "F#", "A"]),
        (["G1", "D2", "B2", "C#3", "F#3"], ["G", "B", "C#", "D", "F#"]),
        (["A1", "E2", "B2", "D3", "E3"], ["A", "B", "D", "E"]),
    ]
    cycles = 2
    bars = len(chords) * 2 * cycles
    total = bars * bar + 7.0
    out = buf(total)
    rng = np.random.default_rng(52)
    for b in range(bars):
        notes, mel = chords[(b // 2) % len(chords)]
        t0 = b * bar
        second = b % 2 == 1
        # Left hand: a low root, then the rest of the chord rolled gently upwards.
        add(out, piano(hz(notes[0]), 5.0, 0.55), t0, -0.25)
        for i, n in enumerate(notes[1:]):
            add(out, piano(hz(n), 4.5, 0.32, soft=0.02), t0 + beat * (1.0 if second else 0.5) + i * 0.11, -0.1 + 0.08 * i)
        # Right hand: one or two sparse notes, often on off-beats, sometimes silence.
        k = int(rng.integers(0, 3)) if b >= 2 else 1
        for _ in range(k):
            pos = beat * float(rng.choice([1.5, 2.0, 2.5, 3.0, 3.5]))
            n = mel[int(rng.integers(0, len(mel)))] + ("4" if rng.random() < 0.6 else "5")
            add(out, piano(hz(n), 4.0, 0.36, soft=0.015), t0 + pos, 0.3)
        # A breath of strings under every chord change.
        if not second:
            p = strings([hz(n) for n in notes[1:4]], bar * 2 + 1.5, 0.22, 2.5, 2.5, bright=1100, vibrato=0.0015)
            i = secs(t0)
            out[i:i + len(p)] += p[: len(out) - i]
    # A final unresolved note.
    add(out, piano(hz("A4"), 6.0, 0.3), bars * bar + 0.2, 0.2)
    air = lp(hp(RNG.standard_normal((len(out), 2)), 2000), 7000) * 0.004
    out = reverb(out + air, 5.0, 0.5, 4200, seed=7)[: len(out)]
    out *= env(len(out), 1.5, 5.0)[:, None]
    return normalize(out, 0.55)


# ------------------------------------------------------------------ output

def write(path_wav, x):
    stereo = x.ndim == 2
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with wave.open(path_wav, "wb") as w:
        w.setnchannels(2 if stereo else 1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    jobs = {
        "clock_tick": (lambda: tick(1.0), 5),
        "clock_tock": (lambda: tick(0.82), 5),
        "hour_strike": (hour_strike, 5),
        "clock_halt": (clock_halt, 5),
        "theme_summoning": (theme, 4),
        "music_admin_realm": (realm_music, 3),
    }
    for name, (fn, quality) in jobs.items():
        x = fn()
        wav = os.path.join(out_dir, name + ".wav")
        write(wav, x)
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", str(quality),
                        os.path.join(out_dir, name + ".ogg")], check=True)
        os.remove(wav)
        print(f"{name}: {len(x) / SR:.1f}s {'stereo' if x.ndim == 2 else 'mono'}")


if __name__ == "__main__":
    main()
