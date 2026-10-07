# Contributing to OpenCanvas

Thank you for your interest. OpenCanvas is a one-person project, so a clear issue or a small, tested pull request is the most useful thing you can send.

## Repository layout

```
OpenCanvas/
├── packages/
│   ├── opencanvas-core/        # Kotlin Multiplatform engine: search, ranking, stream resolution, sync, policy
│   │   └── src/{commonMain,jvmSharedMain,jvmMain,commonTest,jvmTest}
│   ├── opencanvas-compose/     # Compose player that follows the sync map
│   ├── opencanvas-js/          # TypeScript: crop-kinematics / filter ports and a React view (no resolver)
│   └── opencanvas-flutter/     # Dart: the same ports and a view (no resolver)
├── bitchord-integration/       # how BitChord (the first host) uses the library, and the upstream PR text
├── opencanvas-db/              # community data served through jsDelivr
├── tools/opencanvas-stream-server/   # optional Python reference tool; no app needs it
├── docs/                       # the documentation site (GitHub Pages)
└── assets/                     # demo GIFs and videos
```

Source sets of `opencanvas-core`: `commonMain` is platform-free (sync maths, MP4 parsing, policy, models); `jvmSharedMain` is shared by Android and the desktop JVM (everything that touches the network: search, InnerTube backends, ranged reads, sessions, caches); `jvmMain` is desktop-only (`YtDlpBackend`). Keep the sync path in `commonMain` / `jvmSharedMain`: **one code path for every platform** is a design rule, not an accident.

## Setup

- JDK 17 (Android Studio's bundled JBR works: set `JAVA_HOME` to it)
- Nothing else for the unit tests. The manual benchmarks need network access, and `yt-dlp` on the `PATH` (only to look up each song's YouTube Music id, the id a player would already have).
- Node.js 16+ for `packages/opencanvas-js`, the Flutter SDK for `packages/opencanvas-flutter`, if you touch those.

## Build and test

From the repository root:

```bash
./gradlew :packages:opencanvas-core:jvmTest          # 138 tests; the manual ones are skipped unless asked for
./gradlew :packages:opencanvas-compose:compileKotlinJvm
```

The manual end-to-end checks hit the real network and write a report under `packages/opencanvas-core/build/reports/`:

```bash
OPENCANVAS_MANUAL=1 ./gradlew :packages:opencanvas-core:jvmTest --tests "*GlobalMatrixManualTest*"      # 16 songs, cold cache
OPENCANVAS_MANUAL=1 ./gradlew :packages:opencanvas-core:jvmTest --tests "*StreamBenchmarkManualTest*"   # stream start and seek
```

If you change anything that affects speed or accuracy (search ranking, the aligner, the stream backends), run both and put the numbers in the pull request. Please do not add performance claims to the README or the docs that you have not measured.

```bash
cd packages/opencanvas-js && npm install && npm test
cd packages/opencanvas-flutter && flutter test
```

## Tests we like

- A new bug gets a test that fails before the fix. The sync engine is tested end to end against a local server and synthetic MP4 files (`CanvasSessionTest`), so most behaviour can be tested without YouTube.
- Ranking changes go in `MusicVideoSearchTest` with the real titles that went wrong.
- Wording in tests describes behaviour ("a clip is never the music video"), not implementation.

## Keeping BitChord in step

BitChord vendors the core by copying `commonMain` and `jvmSharedMain` into its `shared` module. After a change to the core, run the helper from `bitchord-integration/`:

```bash
python bitchord-integration/sync_core.py /path/to/BitChord
```

and build both BitChord targets (`:app:installDevDebug`, `:desktopApp:compileKotlin`).

## Commits and pull requests

```
type(scope): brief summary

Optional longer description: what changed and why.
```

Types: `feat`, `fix`, `docs`, `refactor`, `perf`, `test`, `chore`. Examples: `feat(core): measure the song-to-video map from MP4 frame sizes`, `perf(stream): read the sharp stream in bounded ranges`, `docs: add measured cold-start table`.

Pull-request checklist:

- [ ] `./gradlew :packages:opencanvas-core:jvmTest` passes
- [ ] new behaviour has a test
- [ ] public API changes are described in the pull request
- [ ] README / docs updated when behaviour or numbers changed (measured numbers only)

## Reporting issues

Use the issue templates (bug report, feature request, question). For a song that gets no canvas, include the title, artist and the log lines from `OpenCanvasConfig.log` — they say which stage rejected it (not readable, still picture, could not align).

## Roadmap

Live: on-device sync, two-tier streams, ranged progressive playback, the position-locked policy, map cache and prefetch, the BitChord integration (Android and desktop).

Open, in rough order of usefulness:

- measuring on physical phones (720p decode cost, battery)
- publishing community maps for popular songs (`opencanvas-db/sync/v1`)
- an iOS port of the HTTP/storage parts of the engine
- a sample Compose app for `opencanvas-compose`

## Code of conduct

Be respectful and constructive.

## Questions?

- GitHub Issues: [open one](https://github.com/Vijay-Janarthanan/OpenCanvas/issues/new/choose)
- Email: vijaybfriendly@gmail.com
