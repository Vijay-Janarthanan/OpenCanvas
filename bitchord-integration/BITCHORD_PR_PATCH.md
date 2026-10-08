# BitChord pull request: OpenCanvas — the official music video behind any song, locked to the song

This file is the text of the pull request to [kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord)
(base: the active version branch, `v1.8.1`) and of the proposal issue that goes with it, since BitChord's
contribution guide asks for substantial features to be discussed first. The integration code itself is in
the BitChord branch `pr/opencanvas-music-video-canvas` (three commits on top of `v1.8.1`); `OpenCanvasProvider.kt` and `sync_core.py` next to this
file are the two pieces another host needs.

---

## Issue (open first): Proposal: music-video canvas for songs without a label canvas

**Problem.** `CanvasRepository` asks Apple, Tidal, the community index and Spotify. For most regional, indie
and back-catalogue tracks every one of them answers nothing, and the Now Playing screen keeps the still cover.
Almost every one of those songs has an official music video on YouTube Music.

**Proposal.** Use that video as the canvas — full-screen, **locked to the song** (it follows seeks and skips,
never loops), only when it provably is the same recording. It runs on-device with no server of ours. I have it
working on Android and on desktop and would like to contribute it. It is a large change, so I would like to
agree the shape first (see "How to split it" below). Library: https://github.com/Vijay-Janarthanan/OpenCanvas

---

## Pull request

### Title

`feat(canvas): music-video canvas locked to the song, measured on-device (Android + desktop)`

### Summary

The music video is asked first and wins when it provably lines up with the song; label canvases (Apple, Tidal, Spotify, community) remain the fallback for songs without one.

Everything happens inside the app. There is no server, no helper process, no account and no API key. The sync is
measured from the **headers of two small MP4 files** (about 0.3 MB each) and a few milliseconds of arithmetic;
nothing is decoded or downloaded in full to find it.

### What the user sees

- A canvas for songs that had none, starting about half a second after the track starts when the map is cached
  and about a second after on first play; a sharper (720p+) stream replaces the first one a moment later.
- Seek in the song and the video lands on the right second (about 0.5 s on an emulator); skip and the next
  track's video starts at once.
- Settings: an OpenCanvas switch and a stream resolution choice (360p–1080p) on both platforms; the existing
  "animated canvas" switch and "canvas over cellular" guard apply to it too.

### How it works

| Stage | What happens |
| :--- | :--- |
| Find | structured YouTube search results ranked on channel (the credited artist's own), "official video/MV", length, verified badge; Shorts, lyric videos, live takes, fan edits and remixes rank out |
| Stream | 360p (itag 18) in ~0.2 s so the canvas can start, and a 720p+ stream from a second client a moment later; both asked for at once |
| Verify + map | frame-size table of the song's and the candidate's AAC track → loudness curves → FFT cross-correlation in 10 s windows + a Viterbi pass → one offset per stretch of the song (a map, not one number: music videos are edits) |
| Play | `CanvasSyncPolicy` (pure Kotlin) decides seek / nudge ≤ 5 % / hide every 250 ms, and immediately when the song jumps |
| Remember | the measured map is stored per (song, video) pair; a repeat play needs no measuring (0–25 ms) |

Reads from the host are **bounded ranges** (YouTube throttles open-ended downloads of adaptive streams to
~140 KiB/s; ranged reads run at line rate): 1 MiB chunks through ExoPlayer on Android
(`ChunkedDataSource` over the existing `CanvasCache`), a growing-range source on desktop.

### Measured (not claimed)

One Windows machine, home connection, empty cache, no community maps, final code
([manual tests](https://github.com/Vijay-Janarthanan/OpenCanvas/tree/main/packages/opencanvas-core/src/jvmTest/kotlin/com/opencanvas/core/manual)):

- 16 songs in 6 languages (2000–2022): **16/16 aligned**; stream playable after a median **550 ms**
  (451–688 ms); map ready after a median **954 ms** (596–1 688 ms); cached map 0–25 ms.
- The same 720p file: plain GET **132 s** (throttled), `yt-dlp` download ≈ 4 s, bounded ranges **1.8 s**,
  first bytes in 27 ms, a seek's first bytes in ≈ 106 ms.
- Android emulator (4 GB, software decode, 360p): the video position stays within ±150 ms of the map's target
  in **96 %** of settled samples (median +49 ms, p95 147 ms); after a seek the picture lands in ≈ 0.5 s; on a
  cold start while the emulator is busy loading, up to ≈ 1 s behind for the first ≈ 8 s.

**Not measured:** physical phones, 720p decode cost, battery. I would value a maintainer's device check.

### Changes in BitChord

- `shared/.../data/canvas/opencanvas/` — the engine, **vendored** (28 files, same `com.opencanvas.core.*`
  packages; refreshed with `bitchord-integration/sync_core.py` from the OpenCanvas repo). No new dependency:
  it uses OkHttp, coroutines and kotlinx-serialization, which BitChord already has.
- `shared/.../OpenCanvasProvider.kt` — the thin adapter: `configure`, `prefetch`, `search` returning a
  `CanvasArtwork` that now also carries the sync map, the video's duration and the headers its URL needs.
- `sharedUi/.../CanvasSync.kt`, `PlayerHost`, `PlayerState`, `NowPlayingScreen` — a per-track sync source the
  players attach to (a stack, so a screen leaving and another arriving never steal each other's state), and the
  early-show hook (below).
- `app/.../CanvasArtworkPlayer.kt` (+ `CanvasCache`) — ExoPlayer kept on the map: seek on a jump, ±5 % speed
  to absorb small drift, 1 MiB chunked reads, a short settle guard after a seek so loading is not aborted by the
  next correction.
- `desktopApp/.../DesktopCanvas*.kt` — the same for the FFmpeg-based desktop player.
- `CanvasRepository` / `DesktopCanvasClient` — see "Behaviour changes to existing code".
- `PlaybackService` — calls `CanvasRepository.prefetch(song)` when a track starts.
- `AppSettings`, `SettingsSheet`, `DesktopAppearanceSettings`, `DesktopApp` — the switch and the resolution choice.

### Behaviour changes to existing code (please read these)

1. **The music video is asked first.** A synced music video beats a label's short loop; the label sources run only when there is none (saving their requests). The lookup is not queued behind them.
2. **The music-video lookup starts when the track starts** (`prefetch`) and again, independent of the label
   lookup's lock, when a screen asks — it was queued behind the label chain.
3. **Bug fix in `firstHit` (both Android and desktop):** it used `runCatching { source() }`, which also swallows
   `CancellationException`. When the album name arrives, the player's effect restarts and cancels the first
   lookup — but the label chain kept running its blocking requests (~4 s) while holding the repository lock, so
   the real lookup waited behind it. It now stops at the next source (`ensureActive`) and rethrows cancellation.
   This fix stands on its own and I am happy to send it separately.
4. The library's sessions survive a collector that stops listening for 4 s, so a restarted request finds
   the work its predecessor began instead of starting again.

### Network use

Per song: the search page, two header reads (≈ 0.3 MB each) for the measurement — less when a map is cached —
and, only when the canvas is shown, the video itself. The existing guards apply: the canvas switch, and
"canvas over cellular" (a metered connection with it off does nothing, including the prefetch).

### Licensing

OpenCanvas is Apache-2.0, which is one-way compatible with BitChord's GPLv3; the Apache notice and the
attribution line (`Powered by OpenCanvas (Created by Vijay Janarthanan)`) are kept in the vendored files. I am
the author of OpenCanvas. The library uses YouTube's own player endpoints (the ones its apps use); that is
undocumented and could change, which is why the stream backend is pluggable — worth a maintainer's judgement.

### Tests and verification

- OpenCanvas core: 138 JVM tests, 0 failures (sync maths, MP4 parsing, ranking, retries, ranged source,
  end-to-end session against a local server with synthetic MP4 files, prefetch and restart behaviour).
- BitChord: `:app:testDevDebugUnitTest` (972 tests) and `:desktopApp:test` (341 tests, including the 15 canvas
  tests): 0 failures; `:app:installDevDebug` builds.
- Run by hand: Android emulator (Pixel 9 Pro image, 4 GB) and the Windows desktop app, with an empty cache, a
  persisted map, a restart, and two seeks; log lines and frame-matching numbers above.

### Size and how to split it

The hand-written diff is about 1 300 lines across 25 files; the vendored engine adds ≈ 4 000 lines in one
folder that is not meant to be reviewed line by line (its tests live in the OpenCanvas repo). If you prefer
smaller pieces: (1) the `firstHit` cancellation fix, (2) the vendored engine + provider, (3) Android
integration, (4) desktop integration. Tell me which order you like.

### Checklist (from CONTRIBUTING.md)

- [x] based on the active version branch
- [x] focused on one feature (the canvas), with the unrelated local build tweaks left out
- [x] tests for new logic live with the library; BitChord's unit tests all pass locally
- [x] no new third-party dependency
