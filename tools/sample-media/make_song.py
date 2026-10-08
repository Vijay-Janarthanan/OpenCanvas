"""Synthesises "Sample Song", the original 72-second track the browser demo plays.

Written for this repository, so it can ship with it: no recording, no sample, no licence to carry.
It has distinct sections (sparse intro, groove, full band, breakdown, drop) because a track that
repeats one bar gives an alignment nothing to tell its positions apart.

    python make_song.py song.wav
"""
import array
import math
import random
import struct
import sys
import wave

RATE = 32000
BPM = 120.0
BEAT = 60.0 / BPM
BAR = BEAT * 4
BARS = 36
N = int(RATE * BAR * BARS)

rng = random.Random(7)
mix = [0.0] * N


def add(start_s, samples, gain=1.0):
    i0 = int(start_s * RATE)
    for k, v in enumerate(samples):
        j = i0 + k
        if j >= N:
            break
        mix[j] += v * gain


def env(n, attack, release):
    a = max(1, int(attack * RATE))
    r = max(1, int(release * RATE))
    out = []
    for k in range(n):
        e = min(1.0, k / a)
        e *= min(1.0, (n - k) / r)
        out.append(e)
    return out


def tone(freq, dur, kind="saw", attack=0.005, release=0.05):
    n = int(dur * RATE)
    e = env(n, attack, release)
    out = []
    for k in range(n):
        ph = (freq * k / RATE) % 1.0
        if kind == "sine":
            v = math.sin(2 * math.pi * ph)
        elif kind == "square":
            v = 1.0 if ph < 0.5 else -1.0
        else:
            v = 2.0 * ph - 1.0
        out.append(v * e[k])
    return out


def kick():
    n = int(0.28 * RATE)
    out, ph = [], 0.0
    for k in range(n):
        t = k / RATE
        f = 48 + 120 * math.exp(-t * 28)
        ph += f / RATE
        out.append(math.sin(2 * math.pi * ph) * math.exp(-t * 11))
    return out


def noise_burst(dur, decay, tilt=0.0):
    n = int(dur * RATE)
    out, last = [], 0.0
    for k in range(n):
        x = rng.uniform(-1, 1)
        y = x - tilt * last
        last = x
        out.append(y * math.exp(-k / RATE * decay))
    return out


def hz(midi):
    return 440.0 * 2 ** ((midi - 69) / 12.0)


# Am - F - C - G, one chord per bar pair
PROGRESSION = [(57, [57, 60, 64]), (53, [53, 57, 60]), (48, [48, 52, 55]), (55, [55, 59, 62])]
SCALE = [69, 72, 74, 76, 79, 81, 84]  # A minor pentatonic, for the lead

for bar in range(BARS):
    t0 = bar * BAR
    root, chord = PROGRESSION[(bar // 2) % 4]
    section = (
        "intro" if bar < 4 else
        "groove" if bar < 12 else
        "full" if bar < 20 else
        "break" if bar < 24 else
        "drop"
    )
    # pad: always, swells in the breakdown
    for note in chord:
        add(t0, tone(hz(note + 12), BAR, "saw", 0.4, 0.5), 0.035 if section != "break" else 0.06)
    # hats
    if section != "break":
        for e in range(8):
            if section == "intro" and e % 2:
                continue
            add(t0 + e * BEAT / 2, noise_burst(0.05, 90, 0.9), 0.10 if e % 2 else 0.06)
    # kick and bass
    if section in ("groove", "full", "drop"):
        for b in range(4):
            add(t0 + b * BEAT, kick(), 0.55)
        for e in range(8):
            if e % 2 == 0 or section == "drop":
                add(t0 + e * BEAT / 2, tone(hz(root - 12), BEAT / 2 * 0.9, "square", 0.004, 0.06), 0.10)
    # snare
    if section in ("full", "drop"):
        for b in (1, 3):
            add(t0 + b * BEAT, noise_burst(0.16, 28, 0.2), 0.22)
    # arpeggio
    if section in ("full", "drop"):
        order = list(chord) + [chord[1] + 12]
        for e in range(8):
            add(t0 + e * BEAT / 2, tone(hz(order[(e + bar) % len(order)] + 12), BEAT / 2 * 0.8, "saw", 0.003, 0.08), 0.06)
    # lead, different every bar
    if section == "drop":
        pos = 0.0
        while pos < BAR - 0.01:
            dur = rng.choice([BEAT / 2, BEAT / 2, BEAT, BEAT * 1.5])
            dur = min(dur, BAR - pos)
            if rng.random() < 0.82:
                add(t0 + pos, tone(hz(rng.choice(SCALE)), dur * 0.92, "square", 0.01, 0.1), 0.07)
            pos += dur
    # riser in the last bar of the breakdown
    if bar == 23:
        n = int(BAR * RATE)
        add(t0, [rng.uniform(-1, 1) * (k / n) ** 2 for k in range(n)], 0.18)

peak = max(abs(v) for v in mix) or 1.0
scale = 0.89 / peak
samples = array.array("h", (int(max(-1.0, min(1.0, v * scale)) * 32767) for v in mix))

out = sys.argv[1] if len(sys.argv) > 1 else "song.wav"
with wave.open(out, "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(RATE)
    w.writeframes(samples.tobytes())
print(f"{out}: {BARS * BAR:.0f} s")
