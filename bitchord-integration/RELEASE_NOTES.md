# OpenCanvas demo builds (BitChord with the OpenCanvas music-video canvas)

Test builds of [BitChord](https://github.com/kushagrasinghx/BitChord) with the on-device OpenCanvas engine, so you can see
the music video behind a song, locked to it, without building anything. They are **not** official BitChord releases.

| File | For | Notes |
| :--- | :--- | :--- |
| `BitChord-OpenCanvas-windows-portable.zip` | Windows 10/11 x64 | unzip, run `BitChord.exe`; no installer; unsigned, so Windows SmartScreen may warn ("More info" → "Run anyway") |
| `BitChord-OpenCanvas-android-arm64-debug.apk` | most Android phones (arm64) | debug build; allow "install unknown apps" for your browser/file manager |
| `BitChord-OpenCanvas-emulator-x86_64-debug.apk` | Android emulator / x86 devices | |
| `SHA256SUMS.txt` | | verify with `sha256sum -c SHA256SUMS.txt` (or `certutil -hashfile <file> SHA256` on Windows) |

## What to try
1. Play a song that has no label motion artwork (regional or older songs work well) and open the full player.
2. The music video should start with the song. Seek in the song: the video should jump to the matching second.
3. Skip to the next song: its video should start at once.

If a song gets no video, that is expected when no video lines up with the song's audio (live cut, cover, different mix) or the
only matches are poster uploads; the still cover stays.

## Known limits
- Measured on an emulator and a desktop, not on many phones: please report how it runs on yours.
- First lookup of a song takes about a second; repeats are instant.
- Debug builds are larger and slower than release builds.
- It uses YouTube's player endpoints, which can change.

## Source and licence
BitChord is GPLv3. The source of these builds is the branch
[`Vijay-Janarthanan/BitChord@pr/opencanvas-music-video-canvas`](https://github.com/Vijay-Janarthanan/BitChord/tree/pr/opencanvas-music-video-canvas)
(pull request [kushagrasinghx/BitChord#646](https://github.com/kushagrasinghx/BitChord/pull/646)); the engine is
[OpenCanvas](https://github.com/Vijay-Janarthanan/OpenCanvas) (Apache-2.0).

Questions or a song that does not work? Open an issue: https://github.com/Vijay-Janarthanan/OpenCanvas/issues/new/choose
