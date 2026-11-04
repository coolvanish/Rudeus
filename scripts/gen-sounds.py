#!/usr/bin/env python3
"""Generate Rudeus's eight game sounds (T23, emotes in T32). Stdlib only, deterministic: re-running yields byte-identical files.

Usage: python3 scripts/gen-sounds.py androidApp/src/main/assets/sounds

Writes place/jump/land/fall/blocked.wav (events) and hello/roar/dance.wav (emotes) as 16-bit PCM mono 22050 Hz,
each <= 0.5 s, peak -3 dBFS, 5 ms fades. The sounds are original (synthesised here) and released CC0.
"""
import math
import os
import struct
import sys
import wave

RATE = 22050
PEAK = 10 ** (-3.0 / 20.0)  # -3 dBFS
FADE_S = 0.005
MAX_S = 0.5


def n_samples(seconds):
    return int(round(seconds * RATE))


def sine(phase):
    return math.sin(phase)


def triangle(phase):
    x = (phase / (2 * math.pi)) % 1.0
    return 4.0 * abs(x - 0.5) - 1.0


def square(phase):
    return 1.0 if (phase / (2 * math.pi)) % 1.0 < 0.5 else -1.0


def tone(seconds, f0, f1, wave_fn, env_fn, exp_sweep=False):
    """A sweep f0 -> f1 (linear or exponential in frequency) shaped by env_fn(t_norm)."""
    n = n_samples(seconds)
    out = []
    phase = 0.0
    for i in range(n):
        t = i / n
        f = f0 * (f1 / f0) ** t if exp_sweep else f0 + (f1 - f0) * t
        out.append(wave_fn(phase) * env_fn(t))
        phase += 2 * math.pi * f / RATE
    return out


class Lcg:
    """Fixed-seed linear congruential generator, so the noise burst is identical on every run and Python version."""

    def __init__(self, seed):
        self.state = seed & 0xFFFFFFFF

    def next(self):
        self.state = (1664525 * self.state + 1013904223) & 0xFFFFFFFF
        return self.state / 0xFFFFFFFF * 2.0 - 1.0


def place():
    # Two-note rising chime: 660 Hz then 880 Hz, 120 ms each, soft attack, gentle decay.
    def env(t):
        attack = min(1.0, t / 0.15)
        release = min(1.0, (1.0 - t) / 0.08)  # each note ends at zero, so the note change does not click
        return attack * release * math.exp(-2.5 * t)

    a = tone(0.12, 660, 660, sine, env)
    b = tone(0.12, 880, 880, sine, env)
    return a + b


def jump():
    # Upward sweep 300 -> 900 Hz, 180 ms, triangle wave.
    return tone(0.18, 300, 900, triangle, lambda t: 1.0 - 0.3 * t, exp_sweep=True)


def land():
    # Low thud: 120 Hz sine with fast exponential decay plus a 20 ms noise burst, 160 ms.
    n = n_samples(0.16)
    noise_n = n_samples(0.02)
    rng = Lcg(23)
    out = []
    for i in range(n):
        t = i / RATE
        thud = math.sin(2 * math.pi * 120 * t) * math.exp(-t / 0.04)
        burst = rng.next() * (1.0 - i / noise_n) * 0.5 if i < noise_n else 0.0
        out.append(thud + burst)
    return out


def fall():
    # Downward sweep 800 -> 200 Hz, 350 ms, quieter at the end.
    return tone(0.35, 800, 200, sine, lambda t: 1.0 - 0.75 * t, exp_sweep=True)


def blocked():
    # Short "bonk": square 220 Hz, 90 ms, hard decay. Mixed with its sine so it is less harsh for a child's ears.
    return tone(0.09, 220, 220, lambda p: 0.6 * square(p) + 0.4 * sine(p), lambda t: math.exp(-5.0 * t))


def hello():
    # Two rising chirps (T32 HELLO): 500 -> 900 Hz and 700 -> 1200 Hz, 110 ms each with a 40 ms rest, soft ends.
    def env(t):
        return min(1.0, t / 0.1) * min(1.0, (1.0 - t) / 0.15)

    chirp_a = tone(0.11, 500, 900, sine, env, exp_sweep=True)
    rest = [0.0] * n_samples(0.04)
    chirp_b = tone(0.11, 700, 1200, sine, env, exp_sweep=True)
    return chirp_a + rest + chirp_b


def roar():
    # Low noisy growl (T32 ROAR), 450 ms: 70 Hz square with a 140 Hz triangle on top and smoothed noise, 40 ms attack,
    # gentle decay, a 5 Hz wobble on the pitch so it is not a flat buzz.
    n = n_samples(0.45)
    rng = Lcg(97)
    out = []
    phase = 0.0
    smooth = 0.0
    for i in range(n):
        t = i / RATE
        f = 70.0 * (1.0 + 0.06 * math.sin(2 * math.pi * 5 * t))
        phase += 2 * math.pi * f / RATE
        smooth += 0.12 * (rng.next() - smooth)  # one-pole lowpass: a rumble, not hiss
        body = 0.5 * square(phase) + 0.3 * triangle(2 * phase) + 0.6 * smooth
        env = min(1.0, t / 0.04) * (1.0 - 0.6 * (i / n)) * min(1.0, (n - i) / n_samples(0.08))
        out.append(body * env)
    return out


def dance():
    # Three-note jingle (T32 DANCE): C5, E5, G5 (523, 659, 784 Hz), 130 ms each, triangle wave, each note fades out.
    def env(t):
        return min(1.0, t / 0.08) * min(1.0, (1.0 - t) / 0.2)

    return sum((tone(0.13, f, f, triangle, env) for f in (523.25, 659.25, 783.99)), [])


def finish(samples):
    """Normalise to -3 dBFS and apply 5 ms linear fades in and out."""
    fade = n_samples(FADE_S)
    n = len(samples)
    peak = max(abs(s) for s in samples) or 1.0
    out = []
    for i, s in enumerate(samples):
        g = 1.0
        if i < fade:
            g = i / fade
        elif i >= n - fade:
            g = (n - 1 - i) / fade
        out.append(s / peak * PEAK * g)
    return out


def write(path, samples):
    if len(samples) > n_samples(MAX_S):
        raise SystemExit(f"{path}: longer than {MAX_S} s")
    frames = b"".join(struct.pack("<h", int(round(s * 32767))) for s in samples)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(frames)


SOUNDS = {
    "place": place,
    "jump": jump,
    "land": land,
    "fall": fall,
    "blocked": blocked,
    "hello": hello,
    "roar": roar,
    "dance": dance,
}


def main(argv):
    if len(argv) != 2:
        print("usage: gen-sounds.py <out-dir>", file=sys.stderr)
        return 2
    out_dir = argv[1]
    os.makedirs(out_dir, exist_ok=True)
    total = 0
    for name, fn in SOUNDS.items():
        path = os.path.join(out_dir, name + ".wav")
        samples = finish(fn())
        write(path, samples)
        size = os.path.getsize(path)
        total += size
        print(f"{name}.wav  {len(samples) / RATE * 1000:.0f} ms  {size} bytes")
    print(f"total {total} bytes ({total / 1024:.1f} KB)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
