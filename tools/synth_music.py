"""Original music and clock sounds for Heavenly Summoning, fully synthesized.

Usage: python3 tools/synth_music.py <output_dir>
Requires numpy + scipy and ffmpeg (libvorbis).

* clock_tick / clock_tock  – the hidden wait: a clock counting down the seconds
* hour_strike              – the hour has come: three strokes of a deep clock bell
* clock_halt               – time stops: the last tick, a tone that hangs and is cut off
* theme_summoning          – minimal score, 28 s, aligned to the event timeline
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

def celesta(f, dur=2.5, amp=1.0):
    """Struck glass/celesta: bright attack, inharmonic shimmer, long pure decay."""
    t = np.arange(secs(dur)) / SR
    x = np.sin(2 * np.pi * f * t) * np.exp(-t / 1.1)
    x += 0.35 * np.sin(2 * np.pi * f * 2.0 * 1.0015 * t) * np.exp(-t / 0.5)
    x += 0.25 * np.sin(2 * np.pi * f * 4.07 * t) * np.exp(-t / 0.12)
    x += 0.08 * np.sin(2 * np.pi * f * 6.9 * t) * np.exp(-t / 0.05)
    x *= env(len(t), 0.002, 0.05)
    return x * amp


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
    """The hour has come: three deep strokes, each answered by the room."""
    total = 8.5
    out = buf(total)
    for i, at in enumerate((0.0, 1.7, 3.4)):
        add(out, bell(hz("G2") * 2, 5.0, 1.0 - i * 0.08), at, pan=(-0.15, 0.15, 0.0)[i])
    out = reverb(out, 3.5, 0.35, 4000, seed=3)
    return normalize(out[: secs(total)].mean(axis=1), 0.9)


def clock_halt():
    """A last loud tick, then a tone that hangs in the air without decaying and is cut off."""
    total = 2.4
    x = np.zeros(secs(total))
    tk = tick(0.8) * 1.0
    x[: len(tk)] += tk
    t = np.arange(secs(1.6)) / SR
    hang = (np.sin(2 * np.pi * 1567.98 * t) * 0.25 + np.sin(2 * np.pi * 2349.3 * t) * 0.12) * env(len(t), 0.004, 0.0)
    i = secs(0.02)
    x[i:i + len(hang)] += hang
    x[i + len(hang):] = 0.0  # abrupt cut: time has stopped
    return normalize(x, 0.85)


# ------------------------------------------------------------------ theme (stereo, 28 s)

def theme():
    total = 28.0
    pre = buf(total)    # everything before the freeze; gated to silence when time stops
    post = buf(total)   # after the freeze
    freeze = 11.75

    # GLOW: the sky lights up. Drone and a first high note.
    add(pre, sine(hz("D2"), 12.0, 0.5, 2.0, 0.5), 0.0)
    pre[: secs(5)] += pad([hz("D3"), hz("A3"), hz("F4")], 5.0, 0.6, 1.8, 1.5)
    add(pre, celesta(hz("A5"), 3.0, 0.5), 0.6, 0.3)

    # CRACK: a sparse glass motif, the cracks running across the dome.
    motif = [(2.0, "D5", -0.4), (2.6, "F5", 0.2), (3.2, "A5", 0.5), (3.8, "E5", -0.2),
             (4.6, "D5", -0.3), (4.9, "F5", 0.0), (5.2, "A5", 0.3), (5.5, "C6", 0.6),
             (6.1, "G5", -0.5), (6.6, "A5", 0.4)]
    for at, n, pan in motif:
        add(pre, celesta(hz(n), 2.5, 0.42), at, pan)
    p = pad([hz("Bb2"), hz("F3"), hz("A3"), hz("D4")], 3.0, 0.55, 1.0, 1.0)
    pre[secs(4.3):secs(4.3) + len(p)] += p

    # SHATTER: tension, heartbeat pulses, rising swell.
    p = pad([hz("A2"), hz("E3"), hz("D4"), hz("G4")], 1.6, 0.8, 1.2, 0.2, cutoff=2400)
    pre[secs(6.8):secs(6.8) + len(p)] += p
    for at in (7.0, 7.5, 8.0):
        add(pre, sine(hz("D1"), 0.45, 0.9, 0.005, 0.35), at)
    swell = hp(RNG.standard_normal(secs(1.4)), 3000) * np.linspace(0, 1, secs(1.4)) ** 3 * 0.25
    add(pre, swell, 6.85)

    # FALL: the sky breaks, a wide chord and a cascade of falling notes.
    p = pad([hz("Bb1"), hz("F2"), hz("Bb2"), hz("D3"), hz("A3"), hz("C4"), hz("F4")], 3.6, 1.1, 0.05, 1.2, cutoff=2200)
    pre[secs(8.25):secs(8.25) + len(p)] += p
    scale = ["D", "E", "F", "G", "A", "Bb", "C"]
    at = 8.3
    step = 0.32
    for i in range(22):
        octave = 6 - i // 8
        n = scale[int(RNG.integers(0, len(scale)))] + str(octave)
        add(pre, celesta(hz(n), 2.0, 0.38), at, float(RNG.uniform(-0.8, 0.8)))
        at += step
        step *= 0.93

    pre = reverb(pre, 3.8, 0.42, 5500, seed=1)[: secs(total)]
    gate = np.ones(len(pre))
    g = secs(freeze)
    gate[g:g + secs(0.06)] = np.linspace(1, 0, secs(0.06))
    gate[g + secs(0.06):] = 0
    pre *= gate[:, None]

    # FREEZE: a single suspended tone, as if the sound itself stopped mid-air.
    frozen = (sine(hz("A5"), 1.6, 0.12, 0.01, 0.4) + sine(hz("E6"), 1.6, 0.06, 0.01, 0.4))
    add(post, frozen, freeze)

    # HAND: a choir rises, F major opening towards light.
    c = choir([hz("F3"), hz("A3"), hz("C4"), hz("F4")], 3.4, 1.4, 1.8, 0.8)
    post[secs(13.25):secs(13.25) + len(c)] += c
    add(post, sine(hz("F1"), 3.4, 0.45, 2.0, 0.8), 13.25)

    # GRAB + LIFT: rising arpeggio and choir to a bright D major at the teleport.
    c = choir([hz("A3"), hz("C#4"), hz("E4"), hz("A4")], 3.3, 1.5, 0.8, 0.4)
    post[secs(16.25):secs(16.25) + len(c)] += c
    arp = ["D5", "F#5", "A5", "D6", "E5", "A5", "C#6", "E6", "F#5", "A5", "D6", "F#6"]
    at = 16.3
    step = 0.3
    for i, n in enumerate(arp):
        add(post, celesta(hz(n), 1.8, 0.33), at, -0.6 + 1.2 * (i % 4) / 3)
        at += step
        step *= 0.94
    for n in ("D4", "F#4", "A4", "D5"):
        add(post, bell(hz(n), 4.0, 0.25), 19.5, 0.0)
    add(post, celesta(hz("D6"), 3.0, 0.5), 19.5, 0.0)
    c = choir([hz("D3"), hz("F#3"), hz("A3"), hz("D4")], 2.0, 1.2, 0.05, 1.6)
    post[secs(19.5):secs(19.5) + len(c)] += c

    # RESTORE: the motif returns reversed and in major, the sky mends.
    for at, n, pan in [(20.6, "A5", 0.4), (21.4, "F#5", -0.2), (22.2, "D5", 0.1), (23.1, "E5", -0.4),
                       (23.9, "F#5", 0.3), (25.0, "A4", 0.0), (25.8, "D5", -0.1)]:
        add(post, celesta(hz(n), 3.0, 0.4), at, pan)
    p = pad([hz("D3"), hz("A3"), hz("C#4"), hz("E4"), hz("F#4")], 7.6, 0.55, 2.5, 3.5, cutoff=1200)
    post[secs(20.2):secs(20.2) + len(p)] += p

    post = reverb(post, 4.5, 0.45, 5000, seed=2)[: secs(total)]
    out = pre + post
    out *= env(len(out), 0.01, 1.2)[:, None]
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
            n = mel[int(rng.integers(0, len(mel)))] + ("5" if rng.random() < 0.7 else "6")
            add(out, piano(hz(n), 4.0, 0.36, soft=0.015), t0 + pos, 0.3)
            if b >= len(chords) * 2:  # second cycle: a faint celesta shadow an octave up
                add(out, celesta(hz(n) * 2, 2.2, 0.06), t0 + pos + 0.02, 0.5)
        # A breath of strings under every chord change.
        if not second:
            p = pad([hz(n) for n in notes[1:4]], bar * 2 + 1.5, 0.18, 2.5, 2.5, cutoff=900)
            i = secs(t0)
            out[i:i + len(p)] += p[: len(out) - i]
    # A final unresolved high note.
    add(out, piano(hz("A5"), 6.0, 0.3), bars * bar + 0.2, 0.2)
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
