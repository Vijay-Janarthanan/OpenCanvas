# Validation: 23 October 2026

These are source-preview checks on one Windows desktop, not a release qualification for every platform.

## Discovery and synchronization

The bounded-search run aligned all 16 entries below. The manual matrix uses a YouTube Music/Topic result when available, otherwise its first song result; some references are the music video itself. It is a transport/alignment smoke test, not 16 independent album-master comparisons or an ownership/accuracy study. YouTube results and playable endpoints can change.

Both separately reported film tracks were tested against their original audio IDs with an empty cache and no community map:

| Song | Song audio | Film video | Measured confidence |
|---|---|---|---|
| Manasavacha — Godavari | `1R-gKCrq_HE` | `F6dRgKSHdmk` | 0.668 |
| Tippulu Tappulu — Godavari | `ZDAT8DZi0jo` | `1p1PwJrxEPA` | 0.496 |

The personal-channel dance upload `RRIYowOPF6s` is rejected by the publisher policy. Verified-channel badges are evidence, not proof; title/film relevance, motion and measured sync remain separate checks. No suitable match leaves still artwork.

## Local subject framing

- YuNet plus YOLOX-Nano, pinned models and licences: about 3.9 MB combined.
- Official OpenCV reference group: 12 faces and four people; warm combined median about 24 ms on this desktop. This is one diagnostic image, not an accuracy or battery benchmark.
- Cosmotune decoded 16 actual frames from `1p1PwJrxEPA`, spanning 30–33 s plus 60, 120 and 160 s. Positive face/person detections were present; the 60 s edge actor was outside the fixed center crop and inside the subject crop. Group scenes used full-frame fit. The diagnostic deliberately runs detection twice per frame, so its timings are not production inference timings.
- Policy checks cover edge subjects, full bodies, groups, detector flicker, no-person scenes, cuts, seeks and viewport boundaries. A short wide-composition hold avoids alternating fit/crop when a body temporarily disappears from detection.
- Browser comparison uses illustrated detections with the same Kotlin camera policy compiled to JavaScript. It does not run the native detectors.
- The Android AAR archive check confirms both pinned model hashes; this catches missing resource packaging that compilation/JVM tests cannot detect.
- Android host compilation/unit checks are available; real-device texture capture, GPU behavior, dropped frames, thermal load and battery remain unverified. Linux shares the desktop JVM renderer, but this run does not establish physical Linux playback.

## Reproduce

Run core JVM tests and Android/Compose compilation with Gradle. Set `OPENCANVAS_LIVE=true` for `FilmVideoManualTest`, `OPENCANVAS_MANUAL=1` for `GlobalMatrixManualTest` / `StreamBenchmarkManualTest`, and run `node packages/conformance/framing-web.test.mjs` after building the browser bundle. In Cosmotune, set `VIDEO_LIVE=true` for `CanvasFramingLiveTest`; its local report and before/after PNGs are generated under `desktopApp/build/reports/canvas-framing/`.

## Multilingual smoke-test output

16 songs, 16 aligned (confidence >= 0.4).

| Song | Language | Video | Video length | Offset (s) | Confidence | Video ready | Map ready | Result |
|---|---|---|---|---|---|---|---|---|
| The Weeknd - Blinding Lights | English | 4NRXx6U8ABQ | 262s | +22.10 | 0.60 | 1.0s | 2.1s | aligned |
| The Kid LAROI - STAY | English | kTJczUoc26U | 157s | +19.36 | 0.82 | 0.9s | 1.5s | aligned |
| Harry Styles - As It Was | English | H5v3kku4y6Q | 165s | +0.00 | 1.00 | 0.7s | 0.9s | aligned |
| Ed Sheeran - Shape of You | English | JGwWNGJdvx8 | 263s | +0.00 | 1.00 | 1.0s | 1.1s | aligned |
| Dua Lipa - Levitating | English | TUVcZfQe-Kw | 230s | +0.00 | 1.00 | 0.7s | 1.0s | aligned |
| Imagine Dragons - Believer | English | 7wtfhZwyrcc | 216s | -0.36 | 0.63 | 0.8s | 2.0s | aligned |
| Rema - Calm Down | English / Afrobeats | CQLsdm1ZYAw | 219s | +0.00 | 1.00 | 0.8s | 2.0s | aligned |
| Coldplay - Yellow | English (2000) | yKNxeF4KMsY | 272s | +0.00 | 1.00 | 0.7s | 0.9s | aligned |
| Arijit Singh - Tum Hi Ho | Hindi | WWZxDA81JFk | 170s | -51.42 | 0.61 | 0.9s | 2.4s | aligned |
| Badshah - Kala Chashma | Hindi / Punjabi | YvWKPU2mcLw | 198s | +16.92 | 0.68 | 1.2s | 2.4s | aligned |
| Dhanush - Why This Kolaveri Di | Tamil | YR12Z8f1Dh8 | 248s | +0.00 | 1.00 | 0.7s | 0.9s | aligned |
| Anirudh Ravichander - Vaathi Coming | Tamil | nIYZ4nbWq7w | 256s | -0.00 | 0.97 | 0.9s | 1.2s | aligned |
| Armaan Malik - Butta Bomma | Telugu | 2mDCVzruYzQ | 193s | +0.97 | 0.78 | 0.9s | 2.2s | aligned |
| BTS - Dynamite | Korean | gdZLi9oWNZg | 223s | +22.80 | 0.86 | 0.8s | 1.0s | aligned |
| Luis Fonsi - Despacito | Spanish | kJQP7kiw5Fk | 281s | +0.00 | 1.00 | 1.2s | 1.4s | aligned |
| PSY - Gangnam Style | Korean (2012) | 9bZkp7q19f0 | 252s | +3.56 | 0.44 | 0.8s | 1.9s | aligned |
