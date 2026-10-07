"""Offline tests for the OpenCanvas stream server (no network, no ffmpeg).

Run with:  python -m unittest

The alignment core is tested on synthetic signals.  The HTTP layer is tested
against a real server on an ephemeral port with stubbed audio fetching and
stubbed yt-dlp resolution.  Real YouTube checks live in ``manual_check.py``.
"""

from __future__ import annotations

import json
import logging
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Optional

import numpy as np

import server
from server import (
    AudioFetchError,
    SAMPLE_RATE,
    VideoCorrelator,
    choose_chain,
    cluster_hypotheses,
    estimate_map,
    window_rewards,
    make_server,
    next_fast_len,
    window_starts,
)

SR = SAMPLE_RATE
TOLERANCE_MS = 2


def setUpModule() -> None:
    logging.disable(logging.CRITICAL)  # keep test output clean


def tearDownModule() -> None:
    logging.disable(logging.NOTSET)


# --------------------------------------------------------------------------
# Synthetic signals
# --------------------------------------------------------------------------


def music_like(seconds: float, seed: int, rms: float = 0.1) -> np.ndarray:
    """Non-repeating "music": decaying harmonic notes at random pitches plus noise."""
    rng = np.random.default_rng(seed)
    n = int(seconds * SR)
    out = np.zeros(n)
    pos = 0
    while pos < n:
        length = int(rng.uniform(0.15, 0.5) * SR)
        span = min(length * 2, n - pos)  # notes ring into the next one
        t = np.arange(span) / SR
        for _ in range(int(rng.integers(1, 4))):
            f0 = rng.uniform(110.0, 900.0)
            env = np.exp(-t * rng.uniform(3.0, 9.0))
            note = np.zeros(span)
            for harmonic in range(1, 5):
                if f0 * harmonic < 3800:
                    note += np.sin(2 * np.pi * f0 * harmonic * t + rng.uniform(0, 6.28)) / harmonic
            out[pos : pos + span] += env * note
        pos += length
    out += 0.05 * rng.standard_normal(n) * np.abs(out).max()
    out *= rms / np.sqrt(np.mean(out**2))
    return out.astype(np.float32)


def make_video(
    track: np.ndarray,
    *,
    offset_ms: float,
    gain: float = 1.0,
    noise: float = 0.0,
    seed: int = 99,
    tail_s: float = 8.0,
) -> np.ndarray:
    """Build a "video" audio where track time ``t`` appears at video time ``t + offset``."""
    rng = np.random.default_rng(seed)
    shift = int(round(offset_ms * SR / 1000.0))
    if shift >= 0:
        intro = music_like(shift / SR, seed + 1)[:shift] if shift else np.zeros(0, np.float32)
        body = track
        video = np.concatenate([intro, body])
    else:
        video = track[-shift:]  # the video starts later than the audio
    outro = music_like(tail_s, seed + 2)
    video = np.concatenate([video * gain, outro])
    if noise:
        video = video + noise * rng.standard_normal(video.size)
    return video.astype(np.float32)


# --------------------------------------------------------------------------
# Alignment core
# --------------------------------------------------------------------------


def loop_song(loops: int = 12, loop_s: float = 8.0, vocals_rms: float = 0.04) -> np.ndarray:
    """A song made of one identical loop repeated, with non-repeating "vocals" on top.

    Every repeat of the loop matches every other one well, so a window cut from it
    correlates with the video at many places, one loop apart.
    """
    loop = music_like(loop_s, seed=5, rms=0.1)
    vocals = music_like(loops * loop_s, seed=6, rms=vocals_rms)
    return (np.tile(loop, loops) + vocals).astype(np.float32)


def video_with_edit(track: np.ndarray, *, intro_s: float, edit_at_s: float, inserted_s: float,
                    seed: int = 41) -> np.ndarray:
    """A video: intro, the song, ``inserted_s`` of other music inserted at ``edit_at_s``, outro."""
    cut = int(edit_at_s * SR)
    return np.concatenate([
        music_like(intro_s, seed),
        track[:cut],
        music_like(inserted_s, seed + 1),
        track[cut:],
        music_like(6.0, seed + 2),
    ]).astype(np.float32)


class AlignmentTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.track = music_like(70.0, seed=1)

    def assertSingleSegment(self, result: dict[str, Any], expected_ms: float) -> None:
        self.assertNotIn("error", result, result)
        self.assertEqual(len(result["segments"]), 1, result)
        self.assertLessEqual(abs(result["offsetMs"] - expected_ms), TOLERANCE_MS, result)
        self.assertLessEqual(abs(result["segments"][0]["offsetMs"] - expected_ms), TOLERANCE_MS, result)
        self.assertIsInstance(result["offsetMs"], int)
        self.assertEqual(result["segments"][0]["songStartMs"], 0)
        self.assertAlmostEqual(result["segments"][0]["songEndMs"], len(self.track) / SR * 1000, delta=20)

    def test_definition_positive_offset_means_video_has_intro(self) -> None:
        video = make_video(self.track, offset_ms=22500)
        # videoTime = songTime + offset: song t=30 s sits at video 52.5 s.
        v_idx, t_idx = int((30_000 + 22_500) * SR / 1000), int(30_000 * SR / 1000)
        np.testing.assert_allclose(video[v_idx : v_idx + 100], self.track[t_idx : t_idx + 100])
        result = estimate_map(self.track, video)
        self.assertSingleSegment(result, +22500)
        self.assertGreater(result["confidence"], 0.9)
        self.assertGreater(result["coverage"], 0.9)

    def test_negative_offset_video_starts_later_than_audio(self) -> None:
        self.assertSingleSegment(estimate_map(self.track, make_video(self.track, offset_ms=-7300)), -7300)

    def test_zero_offset(self) -> None:
        self.assertSingleSegment(estimate_map(self.track, make_video(self.track, offset_ms=0)), 0)

    def test_gain_and_noise_differences(self) -> None:
        result = estimate_map(self.track, make_video(self.track, offset_ms=12345, gain=0.37, noise=0.03))
        self.assertSingleSegment(result, 12345)
        self.assertGreater(result["confidence"], 0.6)

    def test_lossy_looking_encode(self) -> None:
        video = make_video(self.track, offset_ms=4000)
        smooth = np.convolve(video, np.ones(3) / 3, mode="same").astype(np.float32)
        self.assertSingleSegment(estimate_map(self.track, np.tanh(3.0 * smooth) / 3.0), 4000)

    def test_sub_millisecond_shift_rounds_to_integer_ms(self) -> None:
        shift = int(22.5 * SR) + 3  # 22500.375 ms
        video = np.concatenate([music_like(shift / SR, 5)[:shift], self.track, music_like(5, 6)])
        self.assertSingleSegment(estimate_map(self.track, video), 22500.375)

    def test_other_music_mixed_over_the_song(self) -> None:
        other = music_like(90.0, seed=77, rms=0.1)
        video = make_video(self.track, offset_ms=9000, gain=0.6)
        self.assertSingleSegment(estimate_map(self.track, video + other[: video.size] * 0.6), 9000)

    def test_an_edit_in_the_middle_gives_two_segments(self) -> None:
        # intro 7 s, then the song; 2.5 s of other music is inserted 40 s into the song
        video = video_with_edit(self.track, intro_s=7.0, edit_at_s=40.0, inserted_s=2.5)
        result = estimate_map(self.track, video)
        self.assertNotIn("error", result, result)
        first, second = result["segments"]
        self.assertLessEqual(abs(first["offsetMs"] - 7000), TOLERANCE_MS, result)
        self.assertLessEqual(abs(second["offsetMs"] - 9500), TOLERANCE_MS, result)
        self.assertEqual(first["songStartMs"], 0)
        self.assertEqual(first["songEndMs"], second["songStartMs"])  # continuous: no hole
        self.assertAlmostEqual(first["songEndMs"], 40_000, delta=8_000)  # the cut is near the edit
        self.assertAlmostEqual(second["songEndMs"], 70_000, delta=20)

    def test_a_chorus_repeating_elsewhere_does_not_pull_the_picture_around(self) -> None:
        song = loop_song()
        video = make_video(song, offset_ms=22500, gain=0.8, noise=0.01)
        result = estimate_map(song, video)
        self.assertNotIn("error", result, result)
        self.assertEqual(len(result["segments"]), 1, result)
        self.assertLessEqual(abs(result["offsetMs"] - 22500), TOLERANCE_MS, result)

    def test_unrelated_audio_has_no_map(self) -> None:
        result = estimate_map(self.track, music_like(80.0, seed=1234))
        self.assertEqual((result["confidence"], result["offsetMs"], result["segments"]), (0.0, 0, []))
        self.assertIn("error", result)

    def test_white_noise_has_no_map(self) -> None:
        noise = np.random.default_rng(3).standard_normal(80 * SR).astype(np.float32) * 0.1
        result = estimate_map(self.track, noise)
        self.assertEqual(result["confidence"], 0.0)
        self.assertIn("error", result)

    def test_silent_video_does_not_crash(self) -> None:
        result = estimate_map(self.track, np.zeros(80 * SR, np.float32))
        self.assertEqual(result["confidence"], 0.0)
        self.assertIn("error", result)

    def test_silent_track(self) -> None:
        result = estimate_map(np.zeros(60 * SR, np.float32), make_video(self.track, offset_ms=0))
        self.assertEqual((result["confidence"], result["segments"]), (0.0, []))
        self.assertIn("error", result)

    def test_silent_stretch_in_the_song_is_bridged(self) -> None:
        track = self.track.copy()
        track[int(25 * SR) : int(40 * SR)] = 0
        video = make_video(self.track, offset_ms=6000)
        self.assertSingleSegment(estimate_map(track, video), 6000)

    def test_too_short_audio(self) -> None:
        result = estimate_map(np.ones(100, np.float32), np.ones(100, np.float32))
        self.assertEqual(result["confidence"], 0.0)
        self.assertIn("error", result)

    def test_short_track(self) -> None:
        short = music_like(12.0, seed=21)
        result = estimate_map(short, make_video(short, offset_ms=2500, tail_s=3.0))
        self.assertNotIn("error", result, result)
        self.assertLessEqual(abs(result["offsetMs"] - 2500), TOLERANCE_MS, result)

    def test_json_shape(self) -> None:
        result = estimate_map(self.track, make_video(self.track, offset_ms=5000))
        self.assertEqual(set(result), {"offsetMs", "confidence", "coverage", "segments"})
        self.assertEqual(set(result["segments"][0]), {"songStartMs", "songEndMs", "offsetMs", "ncc"})
        json.dumps(result)  # serialisable as is


class ChainTests(unittest.TestCase):
    """The Viterbi pass in isolation: jumps cost, silence is free, big jumps cost more."""

    @staticmethod
    def chain(evidence: list[dict[float, float]]) -> list[float]:
        from server import Candidate

        windows = [[Candidate(off, ncc) for off, ncc in w.items()] for w in evidence]
        hypotheses = cluster_hypotheses(windows)
        reward = window_rewards(windows, hypotheses)
        return [hypotheses[i] for i in choose_chain(reward, hypotheses)]

    def test_consistent_offset_beats_louder_scattered_repeats(self) -> None:
        evidence = [{22000.0: 0.7, 90000.0: 0.95}, {22000.0: 0.7, -40000.0: 0.95}, {22000.0: 0.7, 120000.0: 0.95},
                    {22000.0: 0.7, 60000.0: 0.95}]
        self.assertEqual(self.chain(evidence), [22000.0] * 4)

    def test_a_small_edit_is_followed_when_many_windows_agree(self) -> None:
        evidence = [{22000.0: 0.8}] * 5 + [{24600.0: 0.8}] * 6
        chain = self.chain(evidence)
        self.assertEqual(chain, [22000.0] * 5 + [24600.0] * 6)

    def test_a_silent_window_carries_the_offset_on(self) -> None:
        evidence = [{22000.0: 0.8}, {22000.0: 0.8}, {}, {}, {22000.0: 0.8}]
        self.assertEqual(self.chain(evidence), [22000.0] * 5)

    def test_one_lone_window_is_not_worth_a_jump(self) -> None:
        evidence = [{22000.0: 0.8}] * 4 + [{70000.0: 0.9}] + [{22000.0: 0.8}] * 4
        self.assertEqual(set(self.chain(evidence)), {22000.0})

    def test_windows_start_flush_with_the_end(self) -> None:
        starts = window_starts(100 * SR, 10 * SR, 5 * SR)
        self.assertEqual(starts[0], 0)
        self.assertEqual(starts[-1], 90 * SR)
        self.assertEqual(window_starts(5 * SR, 10 * SR, 5 * SR), [])


class HelperTests(unittest.TestCase):
    def test_byte_limit(self) -> None:
        info = {"filesize": 4_000_000, "duration": 400.0}
        self.assertLess(server._byte_limit(info, 360.0), 4_000_000 + 1)
        long_info = {"filesize": 40_000_000, "duration": 1200.0}
        limit = server._byte_limit(long_info, 360.0)
        self.assertGreater(limit, 40_000_000 * 0.3)
        self.assertLess(limit, 40_000_000 * 0.4)
        self.assertEqual(server._byte_limit({"filesize": 1_000, "duration": 5.0}, 360.0), 1_000)
        self.assertGreater(server._byte_limit({"abr": 128.0}, 360.0), 128_000 / 8 * 360)

    def test_cli_defaults_and_flags(self) -> None:
        import os

        saved = {k: os.environ.pop(k, None) for k in ("OPENCANVAS_HOST", "OPENCANVAS_PORT", "OPENCANVAS_CACHE_DIR")}
        try:
            args = server.build_parser().parse_args([])
            self.assertEqual((args.host, args.port), ("0.0.0.0", 18999))
            self.assertEqual(args.cache_dir, Path.home() / ".opencanvas" / "sync")
            args = server.build_parser().parse_args(["--host", "127.0.0.1", "--port", "19001", "--cache-dir", "x"])
            self.assertEqual((args.host, args.port, args.cache_dir), ("127.0.0.1", 19001, Path("x")))
            os.environ["OPENCANVAS_PORT"] = "19555"
            self.assertEqual(server.build_parser().parse_args([]).port, 19555)
        finally:
            for key, value in saved.items():
                os.environ.pop(key, None)
                if value is not None:
                    os.environ[key] = value


# --------------------------------------------------------------------------
# HTTP layer (stubbed audio and resolver)
# --------------------------------------------------------------------------

TRACK_ID = "AAAAAAAAAAA"
VIDEO_ID = "BBBBBBBBBBB"
OTHER_ID = "CCCCCCCCCCC"
BROKEN_ID = "DDDDDDDDDDD"


class StubFetcher:
    """Stands in for yt-dlp + ffmpeg; counts calls and can be slowed down."""

    _audio: Optional[dict[str, np.ndarray]] = None

    def __init__(self) -> None:
        if StubFetcher._audio is None:  # built once, shared by all tests
            track = music_like(40.0, seed=11)
            StubFetcher._audio = {
                TRACK_ID: track,
                VIDEO_ID: make_video(track, offset_ms=22500, gain=0.7, noise=0.01),
                OTHER_ID: music_like(70.0, seed=4321),
            }
        self.audio = StubFetcher._audio
        self.calls: list[str] = []
        self.delay = 0.0
        self._lock = threading.Lock()

    def __call__(self, video_id: str, max_seconds: float) -> np.ndarray:
        with self._lock:
            self.calls.append(video_id)
        time.sleep(self.delay)
        if video_id == BROKEN_ID:
            raise AudioFetchError("yt-dlp could not resolve DDDDDDDDDDD: Video unavailable")
        return self.audio[video_id]


class StubResolver:
    def __init__(self) -> None:
        self.calls: list[tuple[str, int, int, Optional[str]]] = []

    def __call__(self, target: str, max_height: int, max_width: int, fallback_id: Optional[str]) -> dict[str, Any]:
        self.calls.append((target, max_height, max_width, fallback_id))
        if "boom" in target:
            raise RuntimeError("\x1b[0;31mERROR:\x1b[0m Video unavailable\nsecond line")
        return {
            "videoId": fallback_id or "found0000id",
            "title": "Stub",
            "streamUrl": "https://example.invalid/stream",
            "duration": 262,
            "width": 1280,
            "height": max_height,
        }


class HttpTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.cache_dir = Path(self._tmp.name) / "sync"
        self.fetcher = StubFetcher()
        self.resolver = StubResolver()
        self.start_server()

    def start_server(self) -> None:
        self.httpd = make_server("127.0.0.1", 0, self.cache_dir, fetcher=self.fetcher, resolver=self.resolver)
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        self.thread = threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)
        self.thread.start()

    def stop_server(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=5)

    def tearDown(self) -> None:
        self.stop_server()
        self._tmp.cleanup()

    def get(self, path: str) -> tuple[int, Any, dict[str, str]]:
        try:
            with urllib.request.urlopen(self.base + path, timeout=30) as response:
                body, status, headers = response.read(), response.status, dict(response.headers)
        except urllib.error.HTTPError as exc:
            body, status, headers = exc.read(), exc.code, dict(exc.headers)
        text = body.decode("utf-8")
        self.assertNotIn("Traceback", text)
        return status, json.loads(text), headers

    def sync_path(self, track: str = TRACK_ID, video: str = VIDEO_ID, extra: str = "") -> str:
        return f"/sync?track={track}&video={video}{extra}"

    # -- /health, errors ------------------------------------------------------

    def test_health(self) -> None:
        with urllib.request.urlopen(self.base + "/health", timeout=10) as response:
            self.assertEqual(response.read(), b'{"status":"ok"}')  # same bytes as the original daemon
        status, body, headers = self.get("/health")
        self.assertEqual(status, 200)
        self.assertEqual(body, {"status": "ok"})
        self.assertEqual(headers["Access-Control-Allow-Origin"], "*")
        self.assertEqual(headers["Content-Type"], "application/json")

    def test_unknown_path_is_404_json(self) -> None:
        status, body, _ = self.get("/nope")
        self.assertEqual(status, 404)
        self.assertIn("error", body)

    def test_options_preflight(self) -> None:
        request = urllib.request.Request(self.base + "/sync", method="OPTIONS")
        with urllib.request.urlopen(request, timeout=10) as response:
            self.assertEqual(response.status, 204)
            self.assertEqual(response.headers["Access-Control-Allow-Origin"], "*")

    # -- /sync parameter validation -------------------------------------------

    def test_sync_missing_params(self) -> None:
        for path in ("/sync", f"/sync?track={TRACK_ID}", f"/sync?video={VIDEO_ID}", "/sync?track=&video="):
            status, body, headers = self.get(path)
            self.assertEqual(status, 400, path)
            self.assertIn("error", body)
            self.assertEqual(headers["Access-Control-Allow-Origin"], "*")
        self.assertEqual(self.fetcher.calls, [])

    def test_sync_rejects_malformed_ids(self) -> None:
        for bad in ("short", "waytoolongvideoid", "../../etc/pa", "abc%2Fdefghij", "has%20space!!"):
            status, body, _ = self.get(self.sync_path(track=bad))
            self.assertEqual(status, 400, bad)
            self.assertIn("track", body["error"])
            status, body, _ = self.get(self.sync_path(video=bad))
            self.assertEqual(status, 400, bad)
            self.assertIn("video", body["error"])
        self.assertEqual(self.fetcher.calls, [])
        self.assertFalse(self.cache_dir.exists())

    # -- /sync behaviour -------------------------------------------------------

    def test_sync_computes_then_serves_from_cache(self) -> None:
        status, first, headers = self.get(self.sync_path())
        self.assertEqual(status, 200)
        self.assertEqual(headers["Access-Control-Allow-Origin"], "*")
        self.assertEqual(
            set(first),
            {"trackId", "videoId", "offsetMs", "confidence", "coverage", "segments", "cached", "computeMs"},
        )
        self.assertEqual((first["trackId"], first["videoId"]), (TRACK_ID, VIDEO_ID))
        self.assertLessEqual(abs(first["offsetMs"] - 22500), TOLERANCE_MS)
        self.assertGreater(first["confidence"], 0.5)
        self.assertLessEqual(first["confidence"], 1.0)
        self.assertIs(first["cached"], False)
        self.assertIsInstance(first["computeMs"], int)
        self.assertEqual(len(first["segments"]), 1)
        self.assertCountEqual(self.fetcher.calls, [TRACK_ID, VIDEO_ID])

        status, second, _ = self.get(self.sync_path())
        self.assertEqual(status, 200)
        self.assertIs(second["cached"], True)
        self.assertEqual(second["offsetMs"], first["offsetMs"])
        self.assertEqual(second["segments"], first["segments"])
        self.assertEqual(len(self.fetcher.calls), 2, "cache hit must not fetch audio")

    def test_sync_persists_to_cache_dir_and_survives_restart(self) -> None:
        _, first, _ = self.get(self.sync_path())
        path = self.cache_dir / f"{TRACK_ID}_{VIDEO_ID}.json"
        self.assertTrue(path.is_file())
        stored = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(stored["offsetMs"], first["offsetMs"])
        self.assertEqual([p.name for p in self.cache_dir.iterdir()], [path.name])  # no temp leftovers

        self.stop_server()
        calls_before = len(self.fetcher.calls)
        self.start_server()  # fresh process state, same cache dir
        _, again, _ = self.get(self.sync_path())
        self.assertIs(again["cached"], True)
        self.assertEqual(again["offsetMs"], first["offsetMs"])
        self.assertEqual(len(self.fetcher.calls), calls_before)

    def test_sync_ignores_corrupt_cache_file(self) -> None:
        self.cache_dir.mkdir(parents=True)
        (self.cache_dir / f"{TRACK_ID}_{VIDEO_ID}.json").write_text("{not json", encoding="utf-8")
        status, body, _ = self.get(self.sync_path())
        self.assertEqual(status, 200)
        self.assertIs(body["cached"], False)
        self.assertLessEqual(abs(body["offsetMs"] - 22500), TOLERANCE_MS)

    def test_sync_refresh_recomputes(self) -> None:
        self.get(self.sync_path())
        _, body, _ = self.get(self.sync_path(extra="&refresh=1"))
        self.assertIs(body["cached"], False)
        self.assertEqual(len(self.fetcher.calls), 4)

    def test_same_pair_concurrently_is_computed_once(self) -> None:
        self.fetcher.delay = 0.4
        results: list[dict[str, Any]] = []
        lock = threading.Lock()

        def worker() -> None:
            _, body, _ = self.get(self.sync_path())
            with lock:
                results.append(body)

        threads = [threading.Thread(target=worker) for _ in range(6)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=60)
        self.assertEqual(len(results), 6)
        self.assertEqual(len(self.fetcher.calls), 2, self.fetcher.calls)
        self.assertEqual(sum(1 for r in results if not r["cached"]), 1)
        self.assertEqual(len({r["offsetMs"] for r in results}), 1)

    def test_health_stays_responsive_during_slow_sync(self) -> None:
        self.fetcher.delay = 1.0
        worker = threading.Thread(target=lambda: self.get(self.sync_path()), daemon=True)
        worker.start()
        time.sleep(0.2)
        started = time.monotonic()
        status, body, _ = self.get("/health")
        self.assertEqual((status, body), (200, {"status": "ok"}))
        self.assertLess(time.monotonic() - started, 0.8)
        worker.join(timeout=60)

    def test_fetch_failure_is_502_json_and_not_cached(self) -> None:
        status, body, _ = self.get(self.sync_path(video=BROKEN_ID))
        self.assertEqual(status, 502)
        self.assertIn("Video unavailable", body["error"])
        self.assertIn("video", body["error"])
        self.assertFalse(list(self.cache_dir.glob("*.json")) if self.cache_dir.exists() else [])
        calls = len(self.fetcher.calls)
        status, _, _ = self.get(self.sync_path(video=BROKEN_ID))
        self.assertEqual(status, 502)
        self.assertGreater(len(self.fetcher.calls), calls, "failures must be retried")

    def test_unrelated_pair_reports_zero_confidence_and_is_not_persisted(self) -> None:
        status, body, _ = self.get(self.sync_path(video=OTHER_ID))
        self.assertEqual(status, 200)
        self.assertEqual((body["offsetMs"], body["confidence"]), (0, 0.0))
        self.assertIn("error", body)
        self.assertFalse(list(self.cache_dir.glob("*.json")) if self.cache_dir.exists() else [])

    def test_sync_cache_listing(self) -> None:
        status, body, _ = self.get("/sync/cache")
        self.assertEqual((status, body), (200, {"entries": []}))
        _, first, _ = self.get(self.sync_path())
        status, body, _ = self.get("/sync/cache")
        self.assertEqual(status, 200)
        self.assertEqual(
            body["entries"],
            [
                {
                    "trackId": TRACK_ID,
                    "videoId": VIDEO_ID,
                    "offsetMs": first["offsetMs"],
                    "confidence": first["confidence"],
                }
            ],
        )

    # -- /resolve (stubbed yt-dlp) ---------------------------------------------

    def test_resolve_requires_v_or_q(self) -> None:
        status, body, _ = self.get("/resolve")
        self.assertEqual(status, 400)
        self.assertIn("Missing", body["error"])
        status, _, _ = self.get("/resolve?max_height=720")
        self.assertEqual(status, 400)

    def test_resolve_shape_defaults_and_cache(self) -> None:
        status, body, headers = self.get("/resolve?v=4NRXx6U8ABQ")
        self.assertEqual(status, 200)
        self.assertEqual(list(body), ["videoId", "title", "streamUrl", "duration", "width", "height"])
        self.assertEqual(body["height"], 480)  # default max_height
        self.assertEqual(headers["Access-Control-Allow-Origin"], "*")
        self.assertEqual(self.resolver.calls[0], ("https://www.youtube.com/watch?v=4NRXx6U8ABQ", 480, 0, "4NRXx6U8ABQ"))
        self.get("/resolve?v=4NRXx6U8ABQ")
        self.assertEqual(len(self.resolver.calls), 1, "second call is served from the 30 min cache")
        self.get("/resolve?v=4NRXx6U8ABQ&max_height=720&max_width=0")
        self.assertEqual(self.resolver.calls[-1][1:3], (720, 0))
        self.get("/resolve?v=4NRXx6U8ABQ&max_height=0&max_width=640")  # 0 -> default 480, as before
        self.assertEqual(self.resolver.calls[-1][1:3], (480, 640))

    def test_resolve_search_query(self) -> None:
        self.get("/resolve?q=blinding+lights")
        self.assertEqual(self.resolver.calls[0][0], "ytsearch1:blinding lights")

    def test_resolve_error_is_500_json_without_ansi(self) -> None:
        status, body, _ = self.get("/resolve?v=boom")
        self.assertEqual(status, 500)
        self.assertEqual(body, {"error": "Video unavailable"})

    def test_resolve_rejects_non_integer_dimensions(self) -> None:
        status, body, _ = self.get("/resolve?v=abc&max_height=tall")
        self.assertEqual(status, 400)
        self.assertIn("max_height", body["error"])
        self.assertEqual(self.resolver.calls, [])


if __name__ == "__main__":
    unittest.main()
