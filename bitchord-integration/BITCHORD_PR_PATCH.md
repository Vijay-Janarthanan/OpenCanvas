# BitChord pull request: OpenCanvas — the official music video behind any song, locked to the song

Text of the proposal issue and the pull request to [kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord),
following its CONTRIBUTING guide (substantial features are discussed in an issue first; PRs are based on the
active version branch, `v1.8.1`). Branch: `Vijay-Janarthanan:pr/opencanvas-music-video-canvas` (three commits on `v1.8.1`).
`OpenCanvasProvider.kt` and `sync_core.py` next to this file are the two pieces another host needs.

---

## Issue (open first): Proposal: music-video canvas for songs without a label canvas

**Problem.** `CanvasRepository` asks Apple, Tidal, the community index and Spotify. For most regional, indie and
back-catalogue tracks every one of them answers nothing, and the Now Playing screen keeps the still cover — yet
almost every one of those songs has an official music video on YouTube.

**Proposal.** Use that video as the canvas: full-screen, **locked to the song** (it follows seeks and skips, never
loops), only when it provably is the same recording. It runs on-device with no server of ours and works on Android
and desktop. It is a large change, so I would like to agree the shape first (see "Size and how to split it").
Library: https://github.com/Vijay-Janarthanan/OpenCanvas

---

## Pull request

**Title:** `feat(canvas): music-video canvas locked to the song, measured on-device (Android + desktop)`

### Summary

BitChord looks for the artist's **official music video**, checks from the audio that it really is the same recording,
measures how the song's timeline maps onto the video's, and plays it behind the player **at the song's position**:
it jumps when the user seeks, starts with the next track, never loops, and is hidden in stretches the video has no
scene for. A video that lines up wins over a label's short loop; the label sources remain the fallback for songs
without one.

Everything happens inside the app: no server, no helper process, no account, no API key. The sync is measured from the
**headers of two small MP4 files** (about 0.3 MB each) and a few milliseconds of arithmetic; nothing is decoded.

### How it works

| Stage | What happens |
| :--- | :--- |
| Find | structured YouTube search results ranked on channel, "official video / video song", length, verified badge; Shorts, lyric videos, live takes, fan edits, remixes, karaoke and "full song" audio posters rank out |
| Stream | 360p in ~0.2 s so the canvas can start, and a 720p+ stream a moment later; both asked for at once |
| Verify + map | frame-size table of the song's and the candidate's AAC track → loudness curves → FFT cross-correlation in 10 s windows + a Viterbi pass → one offset **and speed** per stretch of the song (music videos are edits, and film videos often run 4–8 % off the album track) |
| Reject | a video whose frames are almost all tiny (a static poster with the song over it) is not a music video |
| Play | `CanvasSyncPolicy` (pure Kotlin) decides seek / nudge / hide every 250 ms and immediately when the song jumps |
| Remember | the measured map is stored per (song, video) pair; a repeat play needs no measuring (0–25 ms) |

Reads from the host are **bounded ranges** (YouTube throttles open-ended downloads of adaptive streams to ~140 KiB/s):
1 MiB chunks through ExoPlayer on Android (over the existing `CanvasCache`), a growing-range source on desktop.

### Measured (not claimed)

One Windows machine, home connection, empty cache, final engine
([manual tests](https://github.com/Vijay-Janarthanan/OpenCanvas/tree/main/packages/opencanvas-core/src/jvmTest/kotlin/com/opencanvas/core/manual)):

- 16 songs in 6 languages: **16/16 aligned**; stream playable after a median **550 ms**; map ready after a median
  **954 ms**; cached map 0–25 ms.
- The same 720p file: plain GET **132 s** (throttled), `yt-dlp` download ≈ 4 s, bounded ranges **1.8 s**; a seek's first
  bytes in ≈ 106 ms.
- Android emulator: video position within ±150 ms of the map's target in **96 %** of settled samples.
- Checked by hand on the Windows desktop app with Hindi, Tamil and Telugu film songs, including videos at film speed
  (0.92 and 0.96 of the album track) and songs whose first search result is a poster upload.

**Not measured:** physical phones, battery, 720p decode cost. I would value a maintainer's device check.

### Changes in BitChord

- `shared/.../data/canvas/opencanvas/` — the engine, **vendored** (28 files, same `com.opencanvas.core.*` packages;
  refreshed with `bitchord-integration/sync_core.py` from the OpenCanvas repo). No new dependency.
- `shared/.../OpenCanvasProvider.kt` — the adapter: `configure`, `prefetch`, `search` returning a `CanvasArtwork` that
  now also carries the sync map, the video's duration and the headers its URL needs.
- `sharedUi/.../CanvasSync.kt`, `PlayerHost`, `PlayerState`, `NowPlayingScreen` — a per-track sync source the players
  attach to, and the song clock that paces the picture (carries on by wall time if frames stop).
- `app/.../CanvasArtworkPlayer.kt` (+ `CanvasCache`), `desktopApp/.../DesktopCanvas*.kt` — the two players kept on the map.
- `AppSettings`, `SettingsSheet`, `DesktopAppearanceSettings`, `DesktopApp` — an OpenCanvas switch and a stream
  resolution choice (360p–1080p); the existing "animated canvas" switch and "canvas over cellular" guard apply too.
- `PlaybackService` — calls `CanvasRepository.prefetch(song)` when a track starts.

### Behaviour changes to existing code (please read)

1. **A synced music video wins over a label's loop.** The label sources are asked only when there is no such video
   (saving their requests). The video lookup starts when the track starts and is not queued behind the label chain.
2. **Bug fix in `firstHit` (Android and desktop):** it used `runCatching { source() }`, which also swallows
   `CancellationException`. When the album name arrives, the player's effect restarts and cancels the first lookup,
   but the label chain kept running its blocking requests (~4 s) while holding the repository lock, so the real lookup
   waited behind it. It now stops at the next source and rethrows cancellation. This fix stands alone and I am happy
   to send it separately.
3. The engine's sessions survive a collector that stops listening for 4 s, so a restarted request finds the work its
   predecessor began.

### Network use

Per song: the search page, two header reads (≈ 0.3 MB each) for the measurement (none when a map is cached) and, only
when the canvas is shown, the video itself. Existing guards apply: the canvas switch and "canvas over cellular"
(a metered connection with it off does nothing, including the prefetch).

### Licensing

OpenCanvas is Apache-2.0, one-way compatible with BitChord's GPLv3; the notice and attribution line are kept in the
vendored files. I am the author of OpenCanvas. The engine uses YouTube's own player endpoints (the ones its apps use);
that is undocumented and could change, which is why the stream backend is pluggable — worth a maintainer's judgement.

### Tests and verification

- OpenCanvas core: 140+ JVM tests, 0 failures (sync maths, MP4 parsing, ranking, film-speed alignment, retries, the
  ranged source, an end-to-end session against a local server with synthetic MP4 files).
- BitChord on this branch: `:app:testDevDebugUnitTest` (972 tests) and `:desktopApp:test` (335 tests): 0 failures.
- Run by hand: Android emulator (Pixel 9 Pro image) and the Windows desktop app.

### Size and how to split it

About 1 300 hand-written lines across 25 files; the vendored engine adds ≈ 4 000 lines in one folder that is not meant
to be reviewed line by line (its tests live in the OpenCanvas repo). If you prefer smaller pieces: (1) the `firstHit`
cancellation fix, (2) the vendored engine + provider, (3) Android integration, (4) desktop integration.

### Checklist (CONTRIBUTING.md)

- [x] based on the active version branch (`v1.8.1`)
- [x] focused on one feature, local build tweaks left out
- [x] tests for new logic live with the library; BitChord's unit tests pass locally
- [x] no new third-party dependency
- [x] GPLv3-compatible licensing noted
