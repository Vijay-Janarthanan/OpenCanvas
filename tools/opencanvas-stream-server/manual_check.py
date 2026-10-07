#!/usr/bin/env python3
"""Manual end-to-end check of GET /sync against the real YouTube.

Not part of the unit tests (it needs network access, ffmpeg and a running server).
Start the daemon first::

    python server.py --port 18999

then run::

    python manual_check.py

Defaults: track J7p4bzqLvCw (The Weeknd - Blinding Lights, the YouTube Music album audio,
~200 s) against the official video 4NRXx6U8ABQ (262.5 s).  That video is an *edit* of the
song, so the map has two segments: the song starts 22.6 s into the video, and from about
the bridge on the video is a further 2.6 s ahead (+25.2 s).  The check passes when the first
segment's offset is within the tolerance of --expected and the second call is served from the
cache.

Other pairs can be checked with --track/--video; without --expected the script only prints
the map.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

DEFAULT_TRACK = "J7p4bzqLvCw"
DEFAULT_VIDEO = "4NRXx6U8ABQ"
DEFAULT_EXPECTED_MS = 22612
DEFAULT_TOLERANCE_MS = 150


def call(url: str, timeout: float) -> tuple[int, dict, float]:
    started = time.monotonic()
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            status, body = response.status, response.read()
    except urllib.error.HTTPError as exc:
        status, body = exc.code, exc.read()
    elapsed = time.monotonic() - started
    return status, json.loads(body.decode("utf-8")), elapsed


def print_map(result: dict) -> None:
    for seg in result.get("segments", []):
        print(
            f"  song {seg['songStartMs'] / 1000:7.2f} - {seg['songEndMs'] / 1000:7.2f} s"
            f"   offset {seg['offsetMs'] / 1000:+8.3f} s   ncc {seg['ncc']:.2f}"
        )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default=os.environ.get("OPENCANVAS_URL", "http://127.0.0.1:18999"))
    parser.add_argument("--track", default=DEFAULT_TRACK, help="YouTube (Music) id of the song audio")
    parser.add_argument("--video", default=DEFAULT_VIDEO, help="YouTube id of the music video")
    parser.add_argument("--expected", type=int, default=None,
                        help=f"expected offsetMs of the first segment (default {DEFAULT_EXPECTED_MS} for the default pair)")
    parser.add_argument("--tolerance", type=int, default=DEFAULT_TOLERANCE_MS, help="allowed error in ms")
    parser.add_argument("--refresh", action="store_true", help="ignore any cached result for the first call")
    parser.add_argument("--timeout", type=float, default=120.0)
    args = parser.parse_args()
    expected = args.expected
    if expected is None and (args.track, args.video) == (DEFAULT_TRACK, DEFAULT_VIDEO):
        expected = DEFAULT_EXPECTED_MS

    url = f"{args.base}/sync?track={args.track}&video={args.video}"
    first_url = url + ("&refresh=1" if args.refresh else "")
    print(f"GET {first_url}")
    status, first, elapsed = call(first_url, args.timeout)
    print(f"-> HTTP {status} in {elapsed:.1f} s wall clock")
    print(f"   confidence {first.get('confidence')}  coverage {first.get('coverage')}  computeMs {first.get('computeMs')}")
    print_map(first)
    if status != 200 or "error" in first:
        print(f"FAIL: /sync did not return a usable map: {first.get('error')}", file=sys.stderr)
        return 1

    print(f"\nGET {url}  (second call, should be cached)")
    status2, second, elapsed2 = call(url, args.timeout)
    cached = status2 == 200 and second.get("cached") is True and second.get("segments") == first.get("segments")
    print(f"-> HTTP {status2} in {elapsed2 * 1000:.0f} ms, cached={second.get('cached')}")

    ok = cached
    if expected is not None:
        got = first["segments"][0]["offsetMs"]
        error = got - expected
        within = abs(error) <= args.tolerance
        print(f"\nfirst segment offset {got} ms (expected {expected} +/- {args.tolerance}, error {error:+d} ms)")
        ok = ok and within
    print("PASS" if ok else "FAIL")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
