"""Writes cmds.txt for make_sample.sh: the size of the pulse in the sample music video at every frame.

The picture is a square that swells with the loudness of the music (so a kick makes it jump), which
makes the video visibly follow the song. The loudness is read from the same edit the video's audio
gets: 5 s of silence, song 0..30 s, song 40..72 s, 3 s of silence.

    python make_video_cmds.py song.wav cmds.txt
"""
import array
import math
import sys
import wave

FPS = 25
W, H = 960, 540
EDIT = [(0.0, 5.0, None), (5.0, 35.0, 0.0), (35.0, 67.0, 40.0), (67.0, 70.0, None)]  # video from, to, song start


def load(path):
    with wave.open(path, "rb") as w:
        rate = w.getframerate()
        data = array.array("h")
        data.frombytes(w.readframes(w.getnframes()))
    return rate, data


def rms(data, rate, start_s, dur_s):
    a = max(0, int(start_s * rate))
    b = min(len(data), int((start_s + dur_s) * rate))
    if b <= a:
        return 0.0
    return math.sqrt(sum(v * v for v in data[a:b]) / (b - a)) / 32768.0


def song_time(t):
    for start, end, song in EDIT:
        if start <= t < end:
            return None if song is None else song + (t - start)
    return None


rate, data = load(sys.argv[1])
frames = int(70 * FPS)
levels = []
for i in range(frames):
    t = i / FPS
    s = song_time(t)
    levels.append(0.0 if s is None else rms(data, rate, s, 1.0 / FPS))
top = max(levels) or 1.0

lines = []
for i, level in enumerate(levels):
    t = i / FPS
    k = (level / top) ** 0.8
    outer = 70 + 400 * k
    for name, scale in (("a", 1.0), ("b", 0.66), ("c", 0.34)):
        side = max(8, round(outer * scale))
        x = round((W - side) / 2)
        y = round((H - side) / 2)
        lines.append(f"{t:.3f} drawbox@{name} x {x}, drawbox@{name} y {y}, drawbox@{name} w {side}, drawbox@{name} h {side};")

with open(sys.argv[2], "w") as f:
    f.write("\n".join(lines) + "\n")
print(f"{len(lines)} commands, peak rms {top:.3f}")
