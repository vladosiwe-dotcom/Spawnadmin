"""Procedurally synthesizes the original sound effects for Heavenly Summoning.

Usage: python3 tools/synth_sounds.py <output_dir>
Writes mono 44.1 kHz WAV files, then encodes them to OGG Vorbis with ffmpeg.
Every sound is generated from sine partials, filtered noise and simple
convolution reverb, so the assets are fully original.
"""
import os
import subprocess
import sys
import wave

import numpy as np

SR = 44100
rng = np.random.default_rng(20261108)


def t_axis(seconds):
    return np.arange(int(SR * seconds)) / SR


def env_adsr(n, a, d, s, r):
    """Envelope with attack/decay/release given in seconds, sustain level s."""
    e = np.ones(n) * s
    ai, di, ri = int(a * SR), int(d * SR), int(r * SR)
    e[:ai] = np.linspace(0, 1, ai, endpoint=False)
    e[ai:ai + di] = np.linspace(1, s, di, endpoint=False)
    if ri > 0:
        e[-ri:] *= np.linspace(1, 0, ri) ** 2
    return e


def lowpass(x, cutoff):
    alpha = 1 - np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x)
    acc = 0.0
    for i in range(len(x)):
        acc += alpha * (x[i] - acc)
        y[i] = acc
    return y


def highpass(x, cutoff):
    return x - lowpass(x, cutoff)


def fft_convolve(x, ir):
    n = len(x) + len(ir) - 1
    size = 1 << (n - 1).bit_length()
    y = np.fft.irfft(np.fft.rfft(x, size) * np.fft.rfft(ir, size), size)[:n]
    return y


def reverb(x, seconds=3.0, mix=0.35, brightness=6000):
    n = int(seconds * SR)
    tail = rng.standard_normal(n) * np.exp(-np.arange(n) / SR * (6.9 / seconds))
    tail = lowpass(tail, brightness)
    wet = fft_convolve(x, tail)
    wet /= np.max(np.abs(wet)) + 1e-9
    dry = np.concatenate([x, np.zeros(len(wet) - len(x))])
    return dry * (1 - mix) + wet * mix * np.max(np.abs(x))


def normalize(x, peak=0.89):
    return x / (np.max(np.abs(x)) + 1e-9) * peak


def fade_tail(x, seconds=0.05):
    n = int(seconds * SR)
    x[-n:] *= np.linspace(1, 0, n)
    return x


def ping(t, freq, start, decay, amp):
    """A glass fragment hitting glass: a very short noisy click with a brief inharmonic body.
    Deliberately short and dull, so it reads as breaking glass rather than a bell."""
    tt = t - start
    on = tt >= 0
    out = np.zeros_like(t)
    tt = tt[on]
    d = min(decay, 0.03) * 0.5
    body = (np.sin(2 * np.pi * freq * tt) + 0.6 * np.sin(2 * np.pi * freq * 1.73 * tt)
            + 0.4 * np.sin(2 * np.pi * freq * 2.91 * tt)) * np.exp(-tt / d)
    click = rng.standard_normal(len(tt)) * np.exp(-tt / 0.002)
    out[on] = amp * (body * 0.6 + click * 0.8)
    return out


def horn():
    """Deep mystical horn: low brass-like body plus a shimmering choir halo."""
    dur = 4.6
    t = t_axis(dur)
    vib = 1 + 0.004 * np.sin(2 * np.pi * 4.8 * t) * np.clip(t - 0.8, 0, 1)
    f0 = 98.0 * (1 + 0.02 * np.clip(0.35 - t, 0, 0.35) / 0.35 * -1)  # slight scoop up
    phase = 2 * np.pi * np.cumsum(f0 * vib) / SR
    body = np.zeros_like(t)
    for k, a in enumerate([1.0, 0.75, 0.6, 0.42, 0.3, 0.22, 0.15, 0.1, 0.07], start=1):
        body += a * np.sin(k * phase + 0.3 * k)
    body = np.tanh(body * 0.9)
    body *= env_adsr(len(t), 0.45, 0.6, 0.8, 1.6)
    halo = np.zeros_like(t)
    for f, a in [(392.0, 0.28), (587.3, 0.2), (783.9, 0.16), (1174.7, 0.08)]:
        det = np.sin(2 * np.pi * f * 1.003 * t) + np.sin(2 * np.pi * f * 0.997 * t)
        halo += a * det * (0.6 + 0.4 * np.sin(2 * np.pi * 0.7 * t + f))
    halo *= env_adsr(len(t), 1.4, 0.8, 0.7, 1.8)
    breath = lowpass(rng.standard_normal(len(t)), 900) * env_adsr(len(t), 0.2, 0.5, 0.25, 1.4) * 0.6
    x = body + halo + breath
    return normalize(reverb(x, 3.5, 0.45, 5000))


def first_crack():
    """The very first crack: one quiet, dry tick of stressed glass and a faint creak after it."""
    dur = 2.5
    t = t_axis(dur)
    x = np.zeros_like(t)
    x += ping(t, 3800, 0.05, 0.02, 0.6)
    x += ping(t, 5200, 0.09, 0.02, 0.25)
    creak = lowpass(rng.standard_normal(len(t)), 600) * np.exp(-np.abs(t - 0.4) / 0.25) * 0.35
    x = highpass(x, 1200) + creak
    return normalize(reverb(x, 2.0, 0.35, 7000), 0.6)


def cracking():
    """Cracks spreading for ~12 s: sparse ticks at first, denser and louder, deep groans of the dome."""
    dur = 12.5
    t = t_axis(dur)
    x = np.zeros_like(t)
    count = 260
    for i in range(count):
        # slow start, accelerating (matches the crack-front clock)
        s = dur * 0.95 * (i / count) ** 0.45 + rng.uniform(-0.04, 0.04)
        s = max(0.0, s)
        loud = 0.15 + 0.85 * (s / dur) ** 1.5
        idx = int(s * SR)
        n = int(0.004 * SR)
        click = rng.standard_normal(n) * np.exp(-np.arange(n) / (0.0008 * SR))
        x[idx:idx + n] += click[: len(x) - idx] * rng.uniform(0.3, 1.0) * loud
        x += ping(t, rng.uniform(2200, 7000), s, 0.03, rng.uniform(0.05, 0.22) * loud)
    groan = lowpass(rng.standard_normal(len(t)), 90) * 3.0 * np.clip(t / dur, 0, 1) ** 2
    x = highpass(x, 900) + groan
    x *= env_adsr(len(t), 0.01, 0.1, 1.0, 0.5)
    return normalize(reverb(x, 2.4, 0.3, 9000))


def rift_open():
    """The sky opening: a vast, slow sub-bass swell with a breathing, airy roar."""
    dur = 7.0
    t = t_axis(dur)
    sub = (np.sin(2 * np.pi * 36 * t) * 0.8 + np.sin(2 * np.pi * 54.3 * t) * 0.4) * env_adsr(len(t), 2.0, 1.0, 0.9, 2.5)
    air = lowpass(rng.standard_normal(len(t)), 700) * env_adsr(len(t), 3.0, 0.5, 0.8, 2.5) * 0.7
    air *= 0.7 + 0.3 * np.sin(2 * np.pi * 0.35 * t)
    return normalize(reverb(sub + air, 4.0, 0.45, 2500))


def hand_appear():
    """Something enormous approaching: a powerful low swell with a dark, wide hum."""
    dur = 5.5
    t = t_axis(dur)
    f = 41.2 * (1 + 0.04 * t / dur)
    body = np.sin(2 * np.pi * np.cumsum(f) / SR) + 0.5 * np.sin(2 * np.pi * np.cumsum(f * 1.5) / SR)
    body = np.tanh(body * 1.4) * env_adsr(len(t), 2.5, 0.6, 0.85, 2.0)
    rumble = lowpass(rng.standard_normal(len(t)), 160) * env_adsr(len(t), 3.0, 0.5, 0.8, 1.8) * 1.6
    return normalize(reverb(body + rumble, 3.5, 0.4, 2000))


def grab_close():
    """Giant fingers snapping shut, then the heavy thud of the grip."""
    dur = 3.0
    t = t_axis(dur)
    x = np.zeros_like(t)
    for k, s in enumerate((0.0, 0.1, 0.2, 0.3, 0.45)):  # fingers, one after another
        tt = t - s
        on = tt >= 0
        whoosh = np.zeros_like(t)
        whoosh[on] = rng.standard_normal(on.sum()) * np.exp(-tt[on] / 0.08)
        x += lowpass(whoosh, 1200) * 0.5
    tt = t - 0.8
    on = tt >= 0
    thud = np.zeros_like(t)
    thud[on] = (np.sin(2 * np.pi * 48 * tt[on] * (1 - 0.25 * tt[on])) * np.exp(-tt[on] / 0.45) * 1.6
                + lowpass(rng.standard_normal(on.sum()), 400) * np.exp(-tt[on] / 0.1))
    return normalize(reverb(x + thud, 2.5, 0.35, 3000))


def lift_wind():
    """Pulled up into the sky: rising, rushing air."""
    dur = 3.4
    t = t_axis(dur)
    noise = rng.standard_normal(len(t))
    cut = 200 + 5000 * (t / dur) ** 2
    out = np.zeros_like(t)
    acc = 0.0
    for i in range(len(t)):
        acc += (1 - np.exp(-2 * np.pi * cut[i] / SR)) * (noise[i] - acc)
        out[i] = acc
    out *= (0.2 + 0.8 * (t / dur) ** 1.4) * env_adsr(len(t), 0.2, 0.1, 1.0, 0.25)
    return normalize(reverb(out, 1.5, 0.2, 9000))


def vanish_flash():
    """Gone into the light: a bright, wide swell that blooms and fades. No bells."""
    dur = 4.0
    t = t_axis(dur)
    x = np.zeros_like(t)
    for f in (146.8, 220.0, 293.7, 440.0, 587.3):
        x += np.sin(2 * np.pi * f * t + rng.uniform(0, 6)) * (0.6 if f < 300 else 0.25)
    x *= np.exp(-np.clip(t - 0.15, 0, None) / 1.2) * np.clip(t / 0.15, 0, 1)
    air = highpass(rng.standard_normal(len(t)), 3000) * np.exp(-t / 0.6) * 0.3
    return normalize(reverb(x + air, 3.5, 0.5, 6000))


def shatter():
    """Huge glass shatter: deep impact, noise burst, then a cloud of tinkles."""
    dur = 4.0
    t = t_axis(dur)
    boom = np.sin(2 * np.pi * 48 * t * (1 - 0.3 * t)) * np.exp(-t / 0.5) * 1.4
    burst = highpass(rng.standard_normal(len(t)), 2000) * np.exp(-t / 0.18)
    tink = np.zeros_like(t)
    for _ in range(220):
        s = rng.exponential(0.6)
        if s < dur - 0.2:
            tink += ping(t, rng.uniform(1800, 9000), s, rng.uniform(0.03, 0.25), rng.uniform(0.04, 0.2) * np.exp(-s / 1.5))
    x = boom + burst + tink
    return normalize(reverb(x, 3.0, 0.35, 8000))


def time_stop():
    """Reverse swell into a sudden halt, followed by a low frozen hum."""
    dur = 3.2
    t = t_axis(dur)
    swell_len = 0.9
    sw = t < swell_len
    swell = np.zeros_like(t)
    noise = lowpass(rng.standard_normal(int(swell_len * SR)), 3000)
    swell[sw] = noise * (t[sw] / swell_len) ** 3
    thump = np.zeros_like(t)
    after = t >= swell_len
    ta = t[after] - swell_len
    thump[after] = np.sin(2 * np.pi * 55 * ta) * np.exp(-ta / 0.25) * 1.5
    # A low frozen hum instead of a ringing tone.
    hum = np.zeros_like(t)
    hum[after] = (np.sin(2 * np.pi * 82.4 * ta) * 0.35 + np.sin(2 * np.pi * 123.5 * ta) * 0.15) * np.exp(-ta / 1.4)
    x = swell + thump + hum
    return normalize(reverb(x, 2.5, 0.35))


def grab_teleport():
    """Rising whoosh into a low swell and a white-out (no chime)."""
    dur = 3.0
    t = t_axis(dur)
    whoosh = rng.standard_normal(len(t))
    cut = 300 + 6000 * (t / dur) ** 2
    out = np.zeros_like(t)
    acc = 0.0
    for i in range(len(t)):
        alpha = 1 - np.exp(-2 * np.pi * cut[i] / SR)
        acc += alpha * (whoosh[i] - acc)
        out[i] = acc
    out *= (t / dur) ** 1.5 * 1.2
    # A low, dark swell instead of a chime.
    swell = np.zeros_like(t)
    for f in (73.4, 110.0, 146.8):
        swell += np.sin(2 * np.pi * f * t) * np.clip((t - 1.4) / 1.2, 0, 1) ** 2 * np.exp(-np.clip(t - 2.6, 0, None) / 0.8)
    x = out + swell * 0.5
    return normalize(reverb(x, 3.0, 0.4))


def restore():
    """The shattered sky reassembling: reversed tinkles converging into a soft chord."""
    dur = 10.0
    t = t_axis(dur)
    x = np.zeros_like(t)
    for _ in range(200):
        s = rng.uniform(0.2, 8.0)
        x += ping(t, rng.uniform(1500, 7000), s, rng.uniform(0.05, 0.3), rng.uniform(0.04, 0.16))
    rev = x[::-1].copy()
    pad = np.zeros_like(t)
    for f in (130.81, 164.81, 196.0, 261.63):
        pad += np.sin(2 * np.pi * f * t) * 0.25
    pad *= env_adsr(len(t), 5.0, 0.5, 0.8, 3.0)
    x = rev * env_adsr(len(t), 0.5, 0.2, 1.0, 1.0) + pad
    return normalize(reverb(x, 3.0, 0.4))


SOUNDS = {
    "heaven_horn": horn,
    "first_crack": first_crack,
    "rift_open": rift_open,
    "grab_close": grab_close,
    "lift_wind": lift_wind,
    "vanish_flash": vanish_flash,
    "time_stop": time_stop,
    "hand_appear": hand_appear,
    "grab_teleport": grab_teleport,
    "sky_restore": restore,
}


def write_wav(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    for name, fn in SOUNDS.items():
        x = fade_tail(fn())
        wav = os.path.join(out_dir, name + ".wav")
        write_wav(wav, x)
        ogg = os.path.join(out_dir, name + ".ogg")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "5", ogg], check=True)
        os.remove(wav)
        print(name, f"{len(x) / SR:.1f}s")


if __name__ == "__main__":
    main()
