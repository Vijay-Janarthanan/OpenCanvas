#!/usr/bin/env python3
"""OpenCanvas local stream-resolver daemon.

A tiny HTTP server that runs on the user's machine and helps the OpenCanvas
apps (Android emulator, desktop) show a YouTube music video behind a song.

Endpoints
---------
``GET /health``
    ``{"status": "ok"}``

``GET /resolve?v=<videoId> | q=<search>[&max_height=<n>][&max_width=<n>]``
    Resolves a direct, progressive video stream URL with yt-dlp.
    Response: ``{videoId, title, streamUrl, duration, width, height}``.
    Results are cached in memory for 30 minutes.

``GET /sync?track=<songVideoId>&video=<musicVideoId>[&refresh=1]``
    Aligns the audio of a song (for example the YouTube Music album audio) with
    the audio track of a music video, so a player can keep the video in sync with
    the song.  The answer is a *map* from song time to video time, because a
    music video is usually an edit of the song: it has an intro, repeats or drops
    bars, adds an outro.  Response::

        {"trackId", "videoId",
         "offsetMs",            # offset of the longest matched segment (simple clients)
         "confidence",          # 0..1: matched share of the song x mean match quality
         "coverage",            # share of the song with a trusted match, 0..1
         "segments": [{"songStartMs", "songEndMs", "offsetMs", "ncc"}, ...],
         "cached", "computeMs"}

``GET /sync/cache``
    Lists the pairs that already have a cached sync result.

Offset definition (this is a contract, tests pin it)
----------------------------------------------------
Inside a segment ``videoTimeMs = songTimeMs + offsetMs``.

``offsetMs`` is the position, measured on the video timeline, of the instant the
song would start if the segment extended back to song time 0.  A music video with a
22.5 s intro therefore yields ``offsetMs == +22500``: when the song is at 0 ms the
video must be at 22500 ms.  A negative offset means the video starts *later* than
the audio.  Song time outside every segment has no matching picture; players show
the still artwork there.

Alignment algorithm
-------------------
Both audio streams are decoded to mono 8 kHz float32.  The song is cut into
overlapping windows (10 s, hop 5 s); each window is cross-correlated with the
*whole* video audio by FFT and normalised by the energy of the matched slice (NCC).
The strongest few peaks of each window are candidate positions.

Pop songs repeat loops and choruses, so the best peak of a window is often a
*different repeat* of the same material.  The windows are therefore decided
together: a Viterbi pass picks, for every window, one candidate (or "no match"),
maximising the total match quality minus a penalty for every change of offset.
A repeat that would make the picture jump around loses to the chain that stays
continuous (the penalty grows with the size of the jump), and a genuine edit (an inserted bar, a dropped section) survives
because it is followed by many windows that agree with the new offset.

Consecutive windows with the same offset become a segment; the offset is the
median of their refined peaks (sub-sample precision).  Short gaps between segments
are split at their midpoint so the video keeps playing continuously through an
instrumental bridge.  Long gaps stay unmatched.

Only the standard library, numpy and yt-dlp are required (plus an ``ffmpeg``
executable on PATH).  Nothing is ever written inside the source tree: results
go to ``~/.opencanvas/sync`` and temporary audio to the system temp directory.
"""

from __future__ import annotations

import argparse
import http.client
import json
import logging
import math
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import FIRST_EXCEPTION, ThreadPoolExecutor, wait
from contextlib import contextmanager
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Callable, Iterator, Optional
from urllib.parse import parse_qs, urlparse

import numpy as np

try:  # yt-dlp is only needed for the network features
    import yt_dlp
except ImportError:  # pragma: no cover - reported lazily with a clear message
    yt_dlp = None  # type: ignore[assignment]

__version__ = "2.0.0"

log = logging.getLogger("opencanvas")

# --------------------------------------------------------------------------
# Configuration
# --------------------------------------------------------------------------

DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 18999
DEFAULT_CACHE_DIR = Path.home() / ".opencanvas" / "sync"

RESOLVE_TTL_S = 30 * 60  # /resolve in-memory cache lifetime (as before)
NEGATIVE_TTL_S = 10 * 60  # in-memory memo of "could not align" results

SAMPLE_RATE = 8000  # Hz, analysis rate (1 sample = 0.125 ms)
TRACK_MAX_SECONDS = 360.0  # only the first ~6 minutes of the song are fetched
VIDEO_MAX_SECONDS = 420.0  # video gets a little more room for an intro
MIN_AUDIO_SECONDS = 5.0

WINDOW_S = 10.0  # length of each song window that is matched against the video
HOP_S = 5.0  # spacing of the windows
MIN_WINDOW_S = 4.0
SILENCE_FLOOR_RMS = 0.003  # absolute RMS below which a window is "silent"
SILENCE_REL_RMS = 0.10  # ... or below this fraction of the whole-track RMS
MIN_LOCAL_RMS = 1e-3  # video slices quieter than this cannot produce a peak

CANDIDATES_PER_WINDOW = 6
CANDIDATE_MIN_NCC = 0.45  # a peak below this is not a candidate
CANDIDATE_SEPARATION_MS = 1500.0  # peaks closer than this are the same match
SAME_OFFSET_MS = 150.0  # offsets within this are "the same" (encodes differ slightly)
JUMP_BASE = 0.6  # cost of any change of offset between two consecutive windows ...
JUMP_PER_10S = 0.25  # ... plus this much per 10 s of change: real edits move the video by seconds,
JUMP_MAX = 3.0  # while a chorus repeating a minute away is a different place, not an edit
MIN_SEGMENT_WINDOWS = 2  # a segment shorter than this many windows is dropped
FILL_GAP_MAX_S = 45.0  # gaps up to this between two segments are split at the midpoint
FILL_EDGE_MAX_S = 20.0  # an unmatched head/tail up to this is absorbed by its neighbour
MIN_COVERAGE = 0.25  # below this share of the song matched, the answer is "could not align"
CONFIDENCE_FULL_COVERAGE = 0.6  # coverage at which the match quality alone sets the confidence

HTTP_CHUNK_BYTES = 10 * 1024 * 1024  # yt-dlp's http_chunk_size (googlevideo throttling)
HARD_BYTE_CAP = 48 * 1024 * 1024
NET_TIMEOUT_S = 15.0  # per socket operation
FFMPEG_TIMEOUT_S = 60.0
FETCH_DEADLINE_S = 75.0  # whole download of one stream
SYNC_DEADLINE_S = 120.0  # whole /sync computation
MAX_CONCURRENT_SYNCS = 2

VIDEO_ID_RE = re.compile(r"^[A-Za-z0-9_-]{11}$")
_ANSI_RE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")


# --------------------------------------------------------------------------
# Errors
# --------------------------------------------------------------------------


class ApiError(Exception):
    """An error that maps to an HTTP status and a JSON ``{"error": ...}`` body."""

    status = 500


class BadRequest(ApiError):
    status = 400


class AudioFetchError(ApiError):
    """Downloading or decoding audio failed (HTTP 502)."""

    status = 502


class ServerConfigError(ApiError):
    """The host is misconfigured, e.g. ffmpeg is missing (HTTP 500)."""

    status = 500


def _clean_message(exc: BaseException) -> str:
    text = _ANSI_RE.sub("", str(exc)).strip()
    text = re.sub(r"^ERROR:\s*", "", text)
    return text.splitlines()[0][:300] if text else exc.__class__.__name__


# --------------------------------------------------------------------------
# Alignment core (pure numpy, no I/O)
# --------------------------------------------------------------------------


def next_fast_len(n: int) -> int:
    """Smallest ``2**a * 3**b * 5**c >= n`` (fast FFT length)."""
    if n <= 6:
        return max(n, 1)
    best = 1 << (n - 1).bit_length()
    p5 = 1
    while p5 < best:
        p35 = p5
        while p35 < best:
            quotient = -(-n // p35)
            candidate = p35 * (1 << (quotient - 1).bit_length())
            best = min(best, candidate)
            p35 *= 3
        p5 *= 5
    return best


class VideoCorrelator:
    """Locates short probes inside one long signal by normalised cross-correlation.

    The spectrum and the sliding-window energies of the long signal are
    computed once, so each additional probe costs one forward and one inverse
    FFT.
    """

    def __init__(self, signal: np.ndarray) -> None:
        sig = np.asarray(signal, dtype=np.float64)
        if sig.ndim != 1 or sig.size < 2:
            raise ValueError("signal must be a 1-D array with at least 2 samples")
        sig = sig - sig.mean()
        self.size = int(sig.size)
        self._fft_len = next_fast_len(self.size)
        self._spectrum = np.fft.rfft(sig, self._fft_len)
        self._csum = np.concatenate(([0.0], np.cumsum(sig)))
        self._csum2 = np.concatenate(([0.0], np.cumsum(sig * sig)))

    def curve(self, probe: np.ndarray) -> Optional[np.ndarray]:
        """NCC of ``probe`` against every position of the long signal.

        ``curve[k]`` is the correlation when the first probe sample sits at
        sample ``k`` of the long signal.  Returns ``None`` if the probe is
        empty, flat, or longer than the signal.
        """
        p = np.asarray(probe, dtype=np.float64)
        length = int(p.size)
        if length < 2 or length > self.size:
            return None
        p = p - p.mean()
        probe_norm = math.sqrt(float(np.dot(p, p)))
        if probe_norm < 1e-9:
            return None

        lags = self.size - length + 1
        # c[k] = sum_n p[n] * v[n + k]; valid lags never wrap because
        # fft_len >= len(v) >= k + len(p).
        spec = self._spectrum * np.conj(np.fft.rfft(p, self._fft_len))
        corr = np.fft.irfft(spec, self._fft_len)[:lags]

        s1 = self._csum[length:] - self._csum[:lags]
        energy = self._csum2[length:] - self._csum2[:lags]
        s1 *= s1
        s1 /= length
        energy -= s1  # sum of squares of the mean-removed slice
        valid = energy > length * MIN_LOCAL_RMS**2
        np.maximum(energy, 0.0, out=energy)
        np.sqrt(energy, out=energy)
        energy *= probe_norm
        ncc = np.zeros(lags, dtype=np.float64)
        np.divide(corr, energy, out=ncc, where=valid)
        np.clip(ncc, -1.0, 1.0, out=ncc)
        return ncc

    def locate(self, probe: np.ndarray) -> Optional[tuple[float, float]]:
        """Return ``(position_in_samples, ncc)`` of the single best match of ``probe``.

        The position is where the first probe sample sits in the long signal,
        refined to sub-sample precision with a parabola through the peak.
        """
        ncc = self.curve(probe)
        if ncc is None:
            return None
        k = int(np.argmax(ncc))
        return k + parabolic_offset(ncc, k), float(ncc[k])


def parabolic_offset(values: np.ndarray, k: int) -> float:
    """Sub-sample position (-0.5..0.5) of the peak at index ``k``, from its two neighbours."""
    if 0 < k < len(values) - 1:
        a, b, c = values[k - 1], values[k], values[k + 1]
        curvature = a - 2.0 * b + c
        if curvature < 0.0:
            return max(-0.5, min(0.5, 0.5 * float(a - c) / float(curvature)))
    return 0.0


@dataclass(frozen=True)
class Candidate:
    """One place a song window could sit in the video."""

    offset_ms: float
    ncc: float


@dataclass
class Segment:
    """A stretch of the song that maps to the video with one constant offset."""

    song_start_ms: float
    song_end_ms: float
    offset_ms: float
    ncc: float
    matched: bool = True

    def to_json(self) -> dict[str, Any]:
        return {
            "songStartMs": int(round(self.song_start_ms)),
            "songEndMs": int(round(self.song_end_ms)),
            "offsetMs": int(round(self.offset_ms)),
            "ncc": round(self.ncc, 4),
        }


def window_starts(track_len: int, window: int, hop: int) -> list[int]:
    """Start samples of overlapping windows covering the track (the last one flush with the end)."""
    if track_len < window:
        return []
    starts = list(range(0, track_len - window + 1, hop))
    if starts[-1] != track_len - window and track_len - window - starts[-1] >= hop // 2:
        starts.append(track_len - window)
    return starts


def top_candidates(curve: np.ndarray, start: int, sample_rate: int) -> list[Candidate]:
    """The strongest distinct peaks of one window's NCC curve, as offsets."""
    work = curve.copy()
    separation = max(1, int(CANDIDATE_SEPARATION_MS * sample_rate / 1000.0))
    out: list[Candidate] = []
    for _ in range(CANDIDATES_PER_WINDOW):
        k = int(np.argmax(work))
        value = float(work[k])
        if value < CANDIDATE_MIN_NCC:
            break
        position = k + parabolic_offset(curve, k)
        out.append(Candidate((position - start) * 1000.0 / sample_rate, value))
        work[max(0, k - separation) : k + separation + 1] = -1.0
    return out


def jump_cost(delta_ms: float) -> float:
    """Cost of the picture jumping by ``delta_ms`` between two consecutive windows."""
    if delta_ms <= SAME_OFFSET_MS:
        return 0.0
    return min(JUMP_MAX, JUMP_BASE + JUMP_PER_10S * delta_ms / 10_000.0)


def cluster_hypotheses(windows: list[list[Candidate]]) -> list[float]:
    """Distinct offsets proposed by any window (peaks within ``SAME_OFFSET_MS`` are one)."""
    offsets = sorted(c.offset_ms for cands in windows for c in cands)
    hypotheses: list[float] = []
    group: list[float] = []
    for value in offsets:
        if group and value - group[0] > SAME_OFFSET_MS:
            hypotheses.append(float(np.median(group)))
            group = []
        group.append(value)
    if group:
        hypotheses.append(float(np.median(group)))
    return hypotheses


def window_rewards(windows: list[list[Candidate]], hypotheses: list[float]) -> np.ndarray:
    """``reward[i, h]``: how well window ``i`` supports offset ``h`` (0 when it does not)."""
    reward = np.zeros((len(windows), len(hypotheses)))
    offsets = np.asarray(hypotheses)
    for i, cands in enumerate(windows):
        for cand in cands:
            near = np.abs(offsets - cand.offset_ms) <= SAME_OFFSET_MS
            reward[i, near] = np.maximum(reward[i, near], cand.ncc - CANDIDATE_MIN_NCC)
    return reward


def choose_chain(reward: np.ndarray, hypotheses: list[float]) -> list[int]:
    """Viterbi over offset hypotheses: one per window, jumps charged, silence free.

    Every window picks one hypothesis; it earns that hypothesis' reward (zero when the
    window has no evidence for it, which just means "the picture carries on as it was").
    Changing hypothesis between consecutive windows costs :func:`jump_cost` - small edits
    are cheap, a hop to a chorus a minute away is not - so a repeat that would make the
    picture jump around loses to the chain that stays continuous.
    """
    windows, count = reward.shape
    if windows == 0 or count == 0:
        return []
    offsets = np.asarray(hypotheses)
    delta = np.abs(offsets[:, None] - offsets[None, :])
    cost = np.where(delta <= SAME_OFFSET_MS, 0.0, np.minimum(JUMP_MAX, JUMP_BASE + JUMP_PER_10S * delta / 10_000.0))
    score = np.zeros((windows, count))
    back = np.zeros((windows, count), dtype=np.int64)
    score[0] = reward[0]
    for i in range(1, windows):
        candidates = score[i - 1][:, None] - cost  # [previous, current]
        back[i] = np.argmax(candidates, axis=0)
        score[i] = candidates[back[i], np.arange(count)] + reward[i]
    chain = [0] * windows
    chain[-1] = int(np.argmax(score[-1]))
    for i in range(windows - 1, 0, -1):
        chain[i - 1] = int(back[i, chain[i]])
    return chain


def build_segments(
    starts: list[int],
    windows: list[list[Candidate]],
    chain: list[int],
    hypotheses: list[float],
    reward: np.ndarray,
    *,
    window: int,
    hop: int,
    track_len: int,
    sample_rate: int,
) -> tuple[list[Segment], float]:
    """Group the chain into segments, fill short gaps, return ``(segments, matched_ms)``."""
    ms = 1000.0 / sample_rate
    half_hop = hop / 2.0
    segments: list[Segment] = []
    matched_ms = 0.0
    i = 0
    while i < len(chain):
        j = i
        while j + 1 < len(chain) and chain[j + 1] == chain[i]:
            j += 1
        hyp = hypotheses[chain[i]]
        matched = [k for k in range(i, j + 1) if reward[k, chain[i]] > 0.0]
        # the picture only has to be trusted from the first window that supports the offset to the last
        runs: list[list[int]] = []
        for k in matched:  # split where support disappears for longer than FILL_GAP_MAX_S
            if runs and (starts[k] - starts[runs[-1][-1]]) * ms > FILL_GAP_MAX_S * 1000.0:
                runs.append([k])
            elif runs:
                runs[-1].append(k)
            else:
                runs.append([k])
        for run in runs:
            if len(run) < MIN_SEGMENT_WINDOWS:
                continue
            refined = []
            for k in run:
                near = [c for c in windows[k] if abs(c.offset_ms - hyp) <= SAME_OFFSET_MS]
                if near:
                    refined.append(max(near, key=lambda c: c.ncc))
            begin = starts[run[0]] + window / 2.0 - half_hop
            end = starts[run[-1]] + window / 2.0 + half_hop
            segments.append(
                Segment(
                    max(0.0, begin) * ms,
                    min(float(track_len), end) * ms,
                    float(np.median([c.offset_ms for c in refined])),
                    float(np.mean([c.ncc for c in refined])),
                )
            )
            matched_ms += len(run) * hop * ms
        i = j + 1
    segments.sort(key=lambda seg: seg.song_start_ms)

    # fill: split short gaps at their midpoint, absorb short heads and tails
    track_ms = track_len * ms
    for left, right in zip(segments, segments[1:]):
        if right.song_start_ms - left.song_end_ms <= FILL_GAP_MAX_S * 1000.0:
            mid = (left.song_end_ms + right.song_start_ms) / 2.0
            left.song_end_ms = right.song_start_ms = mid
    if segments:
        if segments[0].song_start_ms <= FILL_EDGE_MAX_S * 1000.0:
            segments[0].song_start_ms = 0.0
        if track_ms - segments[-1].song_end_ms <= FILL_EDGE_MAX_S * 1000.0:
            segments[-1].song_end_ms = track_ms
    return segments, matched_ms


def estimate_map(
    track: np.ndarray,
    video: np.ndarray,
    *,
    sample_rate: int = SAMPLE_RATE,
) -> dict[str, Any]:
    """Map song time to video time: ``videoTimeMs = songTimeMs + segment.offsetMs``.

    Args:
        track: Mono samples of the song audio (time 0 = start of the song).
        video: Mono samples of the music-video audio (time 0 = start of video).

    Returns:
        ``{"offsetMs", "confidence", "coverage", "segments"}`` and, when no trustworthy
        map was found, an ``"error"`` string (offset 0, confidence 0, no segments).
    """
    track = np.asarray(track, dtype=np.float32)
    video = np.asarray(video, dtype=np.float32)
    window = int(round(WINDOW_S * sample_rate))
    if track.size < window or video.size < window:
        window = min(track.size, video.size) // 2  # short media: half of the shorter signal
    if window < int(MIN_WINDOW_S * sample_rate):
        return _no_map("audio too short to align")
    # leave room for several windows: on a short song they shrink to a third of it
    window = min(window, max(int(MIN_WINDOW_S * sample_rate), track.size // 3))
    hop = max(1, int(round(HOP_S * sample_rate)))
    hop = min(hop, window)

    correlator = VideoCorrelator(video)
    track_rms = float(np.sqrt(np.mean(np.square(track, dtype=np.float64))))
    threshold = max(SILENCE_FLOOR_RMS, SILENCE_REL_RMS * track_rms)

    starts = window_starts(track.size, window, hop)
    candidates: list[list[Candidate]] = []
    loud = 0
    for start in starts:
        piece = track[start : start + window]
        if float(np.sqrt(np.mean(np.square(piece, dtype=np.float64)))) < threshold:
            candidates.append([])  # near silence: no information
            continue
        loud += 1
        curve = correlator.curve(piece)
        candidates.append([] if curve is None else top_candidates(curve, start, sample_rate))
    if loud == 0:
        return _no_map("track audio is silent")

    hypotheses = cluster_hypotheses(candidates)
    reward = window_rewards(candidates, hypotheses)
    chain = choose_chain(reward, hypotheses)
    segments, matched_ms = build_segments(
        starts, candidates, chain, hypotheses, reward,
        window=window, hop=hop, track_len=track.size, sample_rate=sample_rate,
    )
    track_ms = track.size * 1000.0 / sample_rate
    coverage = min(1.0, matched_ms / track_ms) if track_ms else 0.0
    if not segments or coverage < MIN_COVERAGE:
        return _no_map(
            f"could not align: only {coverage:.0%} of the song has a confident match in the video",
            coverage=coverage,
        )
    quality = float(np.average([seg.ncc for seg in segments], weights=[seg.song_end_ms - seg.song_start_ms for seg in segments]))
    confidence = max(0.0, min(1.0, quality * min(1.0, coverage / CONFIDENCE_FULL_COVERAGE)))
    longest = max(segments, key=lambda seg: seg.song_end_ms - seg.song_start_ms)
    return {
        "offsetMs": int(round(longest.offset_ms)),
        "confidence": round(confidence, 4),
        "coverage": round(coverage, 4),
        "segments": [seg.to_json() for seg in segments],
    }


def _no_map(message: str, coverage: float = 0.0) -> dict[str, Any]:
    return {"offsetMs": 0, "confidence": 0.0, "coverage": round(coverage, 4), "segments": [], "error": message}


# --------------------------------------------------------------------------
# Audio acquisition (yt-dlp + ffmpeg)
# --------------------------------------------------------------------------

AudioFetcher = Callable[[str, float], np.ndarray]


def find_ffmpeg() -> Optional[str]:
    return os.environ.get("OPENCANVAS_FFMPEG") or shutil.which("ffmpeg")


def _require_ytdlp() -> Any:
    if yt_dlp is None:
        raise ServerConfigError("yt-dlp is not installed (pip install -r requirements.txt)")
    return yt_dlp


def _watch_url(video_id: str) -> str:
    return f"https://www.youtube.com/watch?v={video_id}"


def _audio_ydl_opts(extra: Optional[dict[str, Any]] = None) -> dict[str, Any]:
    opts: dict[str, Any] = {
        "format": "bestaudio/best",
        "quiet": True,
        "no_warnings": True,
        "no_color": True,
        "noplaylist": True,
        "noprogress": True,
        "socket_timeout": NET_TIMEOUT_S,
        "retries": 2,
        "http_chunk_size": HTTP_CHUNK_BYTES,
    }
    opts.update(extra or {})
    return opts


def _byte_limit(info: dict[str, Any], max_seconds: float) -> int:
    """Bytes covering roughly the first ``max_seconds`` of a stream (with margin)."""
    size = info.get("filesize") or info.get("filesize_approx")
    duration = info.get("duration")
    bitrate_kbps = info.get("abr") or info.get("tbr")
    if size and duration and duration > max_seconds:
        wanted = size * (max_seconds / duration) * 1.12 + 128 * 1024
    elif size:
        wanted = size
    elif bitrate_kbps:
        wanted = bitrate_kbps * 1000 / 8 * max_seconds * 1.12 + 128 * 1024
    else:
        wanted = 16 * 1024 * 1024
    if size:
        wanted = min(wanted, size)
    return int(min(wanted, HARD_BYTE_CAP))


def _ranged_download(
    url: str,
    headers: dict[str, str],
    dest: Path,
    byte_limit: int,
    deadline: float,
) -> int:
    """Download the first ``byte_limit`` bytes of ``url`` in ``HTTP_CHUNK_BYTES`` ranges."""
    got = 0
    with open(dest, "wb") as out:
        while got < byte_limit:
            if time.monotonic() > deadline:
                raise AudioFetchError("download timed out")
            want = min(HTTP_CHUNK_BYTES, byte_limit - got)
            request = urllib.request.Request(
                url, headers={**headers, "Range": f"bytes={got}-{got + want - 1}"}
            )
            chunk = b""
            partial = False
            for attempt in (1, 2):
                try:
                    with urllib.request.urlopen(request, timeout=NET_TIMEOUT_S) as response:
                        partial = response.status == 206
                        chunk = response.read(want if not partial else None)
                    break
                except urllib.error.HTTPError as exc:
                    if exc.code == 416 and got > 0:  # range starts past the end: done
                        return got
                    if attempt == 2 or exc.code in (400, 401, 403, 404, 410):
                        raise AudioFetchError(f"download failed: HTTP {exc.code}") from exc
                except (urllib.error.URLError, OSError, http.client.HTTPException) as exc:
                    if attempt == 2:
                        raise AudioFetchError(f"download failed: {_clean_message(exc)}") from exc
            out.write(chunk)
            got += len(chunk)
            if not partial or len(chunk) < want:
                break  # whole file received, or the server ignored the range
    return got


def _download_audio(video_id: str, max_seconds: float, workdir: Path) -> Path:
    """Fetch the first ``max_seconds`` of the best audio of ``video_id`` into ``workdir``."""
    ytdlp = _require_ytdlp()
    deadline = time.monotonic() + FETCH_DEADLINE_S
    try:
        with ytdlp.YoutubeDL(_audio_ydl_opts()) as ydl:
            info = ydl.extract_info(_watch_url(video_id), download=False)
    except Exception as exc:  # yt-dlp raises many types
        raise AudioFetchError(f"yt-dlp could not resolve {video_id}: {_clean_message(exc)}") from exc
    if info is None:
        raise AudioFetchError(f"yt-dlp returned no data for {video_id}")
    if "entries" in info:
        entries = list(info["entries"] or [])
        if not entries:
            raise AudioFetchError(f"no media found for {video_id}")
        info = entries[0]

    url = info.get("url")
    if url and info.get("protocol") in ("https", "http"):
        dest = workdir / "audio.bin"
        headers = {str(k): str(v) for k, v in (info.get("http_headers") or {}).items()}
        try:
            got = _ranged_download(url, headers, dest, _byte_limit(info, max_seconds), deadline)
            if got > 0:
                return dest
        except AudioFetchError as exc:
            log.warning("ranged download of %s failed (%s); falling back to yt-dlp", video_id, exc)

    # Fallback: let yt-dlp fetch just the wanted section itself.
    opts = _audio_ydl_opts(
        {
            "outtmpl": str(workdir / "section.%(ext)s"),
            "download_ranges": ytdlp.utils.download_range_func(None, [(0, max_seconds)]),
        }
    )
    try:
        with ytdlp.YoutubeDL(opts) as ydl:
            ydl.download([_watch_url(video_id)])
    except Exception as exc:
        raise AudioFetchError(f"download of {video_id} failed: {_clean_message(exc)}") from exc
    files = sorted(workdir.glob("section.*"), key=lambda p: p.stat().st_size, reverse=True)
    if not files:
        raise AudioFetchError(f"download of {video_id} produced no file")
    return files[0]


def _decode_pcm(path: Path, max_seconds: float) -> np.ndarray:
    """Decode any audio file to mono 8 kHz float32 with ffmpeg."""
    ffmpeg = find_ffmpeg()
    if not ffmpeg:
        raise ServerConfigError("ffmpeg was not found on PATH (set OPENCANVAS_FFMPEG)")
    cmd = [
        ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error",
        "-i", str(path),
        "-t", f"{max_seconds:.3f}",
        "-vn", "-ac", "1", "-ar", str(SAMPLE_RATE), "-f", "f32le", "pipe:1",
    ]  # fmt: skip
    try:
        proc = subprocess.run(cmd, capture_output=True, timeout=FFMPEG_TIMEOUT_S, check=False)
    except subprocess.TimeoutExpired as exc:
        raise AudioFetchError("ffmpeg timed out while decoding audio") from exc
    except OSError as exc:
        raise ServerConfigError(f"cannot run ffmpeg: {_clean_message(exc)}") from exc
    raw = proc.stdout[: len(proc.stdout) // 4 * 4]
    samples = np.frombuffer(raw, dtype="<f4")
    if samples.size < MIN_AUDIO_SECONDS * SAMPLE_RATE:
        detail = _clean_message(Exception(proc.stderr.decode("utf-8", "replace")))
        raise AudioFetchError(f"could not decode audio ({detail})")
    return samples.astype(np.float32, copy=True)


def fetch_audio_8k(video_id: str, max_seconds: float) -> np.ndarray:
    """Download the first ``max_seconds`` of a video's audio as mono 8 kHz float32."""
    with tempfile.TemporaryDirectory(prefix="ocsync_") as tmp:
        path = _download_audio(video_id, max_seconds, Path(tmp))
        return _decode_pcm(path, max_seconds)


# --------------------------------------------------------------------------
# /sync service: caching, de-duplication, orchestration
# --------------------------------------------------------------------------


class SyncService:
    """Computes and caches sync offsets for (track, video) pairs.

    * results persist to ``<cache_dir>/<track>_<video>.json`` and in memory;
    * the same pair requested concurrently is computed once (per-pair lock);
    * at most ``max_concurrent`` computations run at the same time.
    """

    def __init__(
        self,
        cache_dir: Path,
        fetcher: AudioFetcher = fetch_audio_8k,
        *,
        max_concurrent: int = MAX_CONCURRENT_SYNCS,
        deadline_s: float = SYNC_DEADLINE_S,
    ) -> None:
        self.cache_dir = Path(cache_dir)
        self._fetcher = fetcher
        self._deadline_s = deadline_s
        self._slots = threading.BoundedSemaphore(max_concurrent)
        self._guard = threading.Lock()
        self._pair_locks: dict[tuple[str, str], list[Any]] = {}
        self._memory: dict[tuple[str, str], dict[str, Any]] = {}
        self._negative: dict[tuple[str, str], tuple[float, dict[str, Any]]] = {}

    # -- public API ---------------------------------------------------------

    def get(
        self,
        track: str,
        video: str,
        refresh: bool = False,
    ) -> dict[str, Any]:
        """Return the sync result for a pair, computing it if necessary."""
        key = (track, video)
        started = time.monotonic()
        with self._pair_lock(key):
            if not refresh:
                hit = self._lookup(key)
                if hit is not None:
                    return {**hit, "cached": True, "computeMs": 0}
            with self._slots:
                result = self._compute(track, video)
            result["cached"] = False
            result["computeMs"] = int((time.monotonic() - started) * 1000)
            self._store(key, result)
            return result

    def list_cached(self, limit: int = 500) -> list[dict[str, Any]]:
        """Summaries of the pairs persisted on disk."""
        entries: list[dict[str, Any]] = []
        try:
            files = sorted(self.cache_dir.glob("*.json"))
        except OSError:
            return entries
        for path in files[:limit]:
            data = self._read_file(path)
            if data is not None:
                entries.append(
                    {
                        "trackId": data["trackId"],
                        "videoId": data["videoId"],
                        "offsetMs": data["offsetMs"],
                        "confidence": data["confidence"],
                    }
                )
        return entries

    # -- internals ----------------------------------------------------------

    @contextmanager
    def _pair_lock(self, key: tuple[str, str]) -> Iterator[None]:
        with self._guard:
            entry = self._pair_locks.setdefault(key, [threading.Lock(), 0])
            entry[1] += 1
        try:
            with entry[0]:
                yield
        finally:
            with self._guard:
                entry[1] -= 1
                if entry[1] == 0:
                    self._pair_locks.pop(key, None)

    def _path(self, key: tuple[str, str]) -> Path:
        return self.cache_dir / f"{key[0]}_{key[1]}.json"

    def _lookup(self, key: tuple[str, str]) -> Optional[dict[str, Any]]:
        with self._guard:
            hit = self._memory.get(key)
            if hit is not None:
                return hit
            negative = self._negative.get(key)
            if negative is not None:
                if negative[0] > time.monotonic():
                    return negative[1]
                del self._negative[key]
        data = self._read_file(self._path(key))
        if data is not None and (data["trackId"], data["videoId"]) == key:
            with self._guard:
                self._memory[key] = data
            return data
        return None

    @staticmethod
    def _read_file(path: Path) -> Optional[dict[str, Any]]:
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            if (
                isinstance(data, dict)
                and isinstance(data["trackId"], str)
                and isinstance(data["videoId"], str)
                and isinstance(data["offsetMs"], int)
                and isinstance(data["confidence"], (int, float))
                # results written before the segment map existed were made by an older algorithm
                and isinstance(data.get("segments"), list)
                and "error" not in data
            ):
                data.pop("cached", None)
                return data
        except (OSError, ValueError, KeyError):
            pass
        return None

    def _store(self, key: tuple[str, str], result: dict[str, Any]) -> None:
        persistent = {k: v for k, v in result.items() if k != "cached"}
        if "error" in result:  # a failed alignment is only memoised briefly
            with self._guard:
                self._negative[key] = (time.monotonic() + NEGATIVE_TTL_S, persistent)
            return
        with self._guard:
            self._memory[key] = persistent
        try:
            self.cache_dir.mkdir(parents=True, exist_ok=True)
            final = self._path(key)
            tmp = final.with_name(f".{final.name}.{os.getpid()}.{threading.get_ident()}.tmp")
            tmp.write_text(json.dumps(persistent), encoding="utf-8")
            os.replace(tmp, final)
        except OSError as exc:
            log.warning("could not persist sync result: %s", exc)

    def _compute(self, track: str, video: str) -> dict[str, Any]:
        pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="sync-fetch")
        try:
            futures = {
                "track": pool.submit(self._fetcher, track, TRACK_MAX_SECONDS),
                "video": pool.submit(self._fetcher, video, VIDEO_MAX_SECONDS),
            }
            # Fail fast: if either download fails, do not wait for the other.
            _, pending = wait(futures.values(), timeout=self._deadline_s, return_when=FIRST_EXCEPTION)
            audio: dict[str, np.ndarray] = {}
            for name, future in futures.items():
                if not future.done():
                    if pending:
                        raise AudioFetchError(f"timed out fetching {name} audio")
                    continue
                try:
                    audio[name] = future.result()
                except ApiError as exc:
                    raise type(exc)(f"{name} audio: {exc}") from exc
            if len(audio) < 2:
                raise AudioFetchError("timed out fetching audio")
        finally:
            pool.shutdown(wait=False, cancel_futures=True)

        outcome = estimate_map(audio["track"], audio["video"])
        return {"trackId": track, "videoId": video, **outcome}


# --------------------------------------------------------------------------
# /resolve service (behaviour kept identical to the original daemon)
# --------------------------------------------------------------------------


def ytdlp_resolve(target: str, max_height: int, max_width: int, fallback_id: Optional[str]) -> dict[str, Any]:
    """Resolve a progressive video stream with yt-dlp (same format as before)."""
    ytdlp = _require_ytdlp()
    w_clause = f"[width<={max_width}]" if max_width > 0 else ""
    fmt = (
        f"bestvideo[height<={max_height}]{w_clause}[ext=mp4]"
        f"/bestvideo[height<={max_height}]{w_clause}"
        "/bestvideo[ext=mp4]/best[ext=mp4]/best"
    )
    opts = {"format": fmt, "quiet": True, "noplaylist": True, "no_warnings": True, "no_color": True}
    with ytdlp.YoutubeDL(opts) as ydl:
        info = ydl.extract_info(target, download=False)
        if "entries" in info:
            info = info["entries"][0]
        return {
            "videoId": info.get("id", fallback_id),
            "title": info.get("title", ""),
            "streamUrl": info.get("url"),
            "duration": info.get("duration", 0),
            "width": info.get("width", 1280),
            "height": info.get("height", max_height),
        }


class ResolveService:
    """30-minute in-memory cache around a resolver callable."""

    def __init__(self, resolver: Callable[[str, int, int, Optional[str]], dict[str, Any]] = ytdlp_resolve) -> None:
        self._resolver = resolver
        self._lock = threading.Lock()
        self._cache: dict[str, tuple[float, dict[str, Any]]] = {}

    def resolve(self, video_id: Optional[str], query: Optional[str], max_height: int, max_width: int) -> dict[str, Any]:
        target = _watch_url(video_id) if video_id else f"ytsearch1:{query}"
        key = f"{target}|{max_height}|{max_width}"
        now = time.time()
        with self._lock:
            hit = self._cache.get(key)
            if hit is not None and now - hit[0] < RESOLVE_TTL_S:
                return hit[1]
        data = self._resolver(target, max_height, max_width, video_id)
        with self._lock:
            if len(self._cache) > 512:
                self._cache = {k: v for k, v in self._cache.items() if now - v[0] < RESOLVE_TTL_S}
            self._cache[key] = (now, data)
        return data


# --------------------------------------------------------------------------
# HTTP layer
# --------------------------------------------------------------------------


def _first(params: dict[str, list[str]], name: str) -> Optional[str]:
    values = params.get(name)
    return values[0].strip() if values and values[0].strip() else None


def _int_param(params: dict[str, list[str]], name: str, default: int) -> int:
    raw = _first(params, name)
    if raw is None:
        return default
    try:
        return int(raw) or default
    except ValueError:
        raise BadRequest(f"'{name}' must be an integer") from None


class OpenCanvasServer(ThreadingHTTPServer):
    """Threaded server: slow /sync computations never block /resolve or /health."""

    daemon_threads = True
    # On Windows SO_REUSEADDR lets a second process bind a port that is already
    # in use, silently splitting the traffic; keep the bind exclusive there.
    allow_reuse_address = os.name != "nt"
    request_queue_size = 32

    def __init__(
        self,
        address: tuple[str, int],
        sync_service: SyncService,
        resolve_service: ResolveService,
    ) -> None:
        self.sync_service = sync_service
        self.resolve_service = resolve_service
        super().__init__(address, StreamHandler)


class StreamHandler(BaseHTTPRequestHandler):
    server: OpenCanvasServer  # type: ignore[assignment]
    server_version = f"OpenCanvasStreamServer/{__version__}"

    def do_OPTIONS(self) -> None:  # CORS preflight
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        route = parsed.path.rstrip("/") or "/"
        params = parse_qs(parsed.query)
        try:
            if route == "/health":
                self._send_body(b'{"status":"ok"}')  # byte-identical to the original daemon
            elif route == "/resolve":
                self._handle_resolve(params)
            elif route == "/sync":
                self._handle_sync(params)
            elif route == "/sync/cache":
                self._send_json({"entries": self.server.sync_service.list_cached()})
            else:
                self._send_json({"error": "Not Found"}, 404)
        except ApiError as exc:
            self._send_json({"error": str(exc)}, exc.status)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            log.debug("client went away: %s", self.path)
        except Exception:  # never leak a traceback to the client
            log.exception("unhandled error for %s", self.path)
            self._send_json({"error": "internal server error"}, 500)

    # -- routes -------------------------------------------------------------

    def _handle_resolve(self, params: dict[str, list[str]]) -> None:
        video_id = _first(params, "v")
        query = _first(params, "q")
        if not video_id and not query:
            raise BadRequest("Missing 'v' or 'q' parameter")
        max_height = _int_param(params, "max_height", 480)
        max_width = _int_param(params, "max_width", 0)
        try:
            data = self.server.resolve_service.resolve(video_id, query, max_height, max_width)
        except ApiError:
            raise
        except Exception as exc:
            log.warning("resolve failed: %s", _clean_message(exc))
            self._send_json({"error": _clean_message(exc)}, 500)
            return
        self._send_json(data)

    def _handle_sync(self, params: dict[str, list[str]]) -> None:
        track = _first(params, "track")
        video = _first(params, "video")
        if not track or not video:
            raise BadRequest("Missing 'track' and/or 'video' parameter")
        for name, value in (("track", track), ("video", video)):
            if not VIDEO_ID_RE.match(value):
                raise BadRequest(f"'{name}' is not a valid YouTube video id")
        refresh = (_first(params, "refresh") or "").lower() in ("1", "true", "yes")
        log.info("sync track=%s video=%s refresh=%s", track, video, refresh)
        result = self.server.sync_service.get(track, video, refresh)
        log.info(
            "sync done offsetMs=%s confidence=%s coverage=%s segments=%s cached=%s computeMs=%s",
            result.get("offsetMs"), result.get("confidence"), result.get("coverage"),
            len(result.get("segments", [])), result.get("cached"), result.get("computeMs"),
        )  # fmt: skip
        self._send_json(result)

    # -- helpers ------------------------------------------------------------

    def _send_json(self, data: Any, status: int = 200) -> None:
        self._send_body(json.dumps(data).encode("utf-8"), status)

    def _send_body(self, body: bytes, status: int = 200) -> None:
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
        log.info("%s - %s", self.client_address[0], format % args)


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------


def make_server(
    host: str,
    port: int,
    cache_dir: Path,
    *,
    fetcher: AudioFetcher = fetch_audio_8k,
    resolver: Callable[[str, int, int, Optional[str]], dict[str, Any]] = ytdlp_resolve,
) -> OpenCanvasServer:
    """Build a ready-to-serve server (``port=0`` picks a free port)."""
    return OpenCanvasServer(
        (host, port),
        SyncService(cache_dir, fetcher),
        ResolveService(resolver),
    )


def _env_port() -> int:
    try:
        return int(os.environ.get("OPENCANVAS_PORT", DEFAULT_PORT))
    except ValueError:
        return DEFAULT_PORT


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="OpenCanvas local stream-resolver daemon (/resolve, /sync, /health).",
    )
    parser.add_argument(
        "--host",
        default=os.environ.get("OPENCANVAS_HOST", DEFAULT_HOST),
        help=f"address to bind (env OPENCANVAS_HOST, default {DEFAULT_HOST})",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=_env_port(),
        help=f"TCP port (env OPENCANVAS_PORT, default {DEFAULT_PORT})",
    )
    parser.add_argument(
        "--cache-dir",
        type=Path,
        default=Path(os.environ.get("OPENCANVAS_CACHE_DIR", DEFAULT_CACHE_DIR)),
        help=f"directory for cached /sync results (env OPENCANVAS_CACHE_DIR, default {DEFAULT_CACHE_DIR})",
    )
    parser.add_argument("-v", "--verbose", action="store_true", help="debug logging")
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        stream=sys.stderr,
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
    )
    if yt_dlp is None:
        log.error("yt-dlp is not installed; run: pip install -r requirements.txt")
        return 2
    if not find_ffmpeg():
        log.warning("ffmpeg not found on PATH: /resolve works, /sync will fail until it is installed")
    try:
        server = make_server(args.host, args.port, args.cache_dir)
    except OSError as exc:
        log.error("cannot listen on %s:%s: %s", args.host, args.port, exc)
        return 1
    log.info("OpenCanvas stream server %s listening on http://%s:%d", __version__, args.host, args.port)
    log.info("sync cache: %s", args.cache_dir)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("shutting down")
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
