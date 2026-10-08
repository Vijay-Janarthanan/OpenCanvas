**Title:** Proposal: play the official music video as the canvas for songs that have no label canvas

## The problem

`CanvasRepository` asks Apple, Tidal, the community index and Spotify. For a large part of the catalogue — regional, indie and back-catalogue songs — all of them answer nothing, so the Now Playing screen shows only the still cover. Almost all of those songs have an official music video on YouTube.

## The proposal

Use that video as the canvas, **locked to the song**:

- it starts with the track, **jumps when the user seeks or skips**, and **never loops**
- it is shown only when it provably is the same recording (checked against the song's audio), so a lyric video, a live take, a karaoke upload or a poster with the song over it is not used
- when a song has both a label canvas and a matching music video, the music video is shown and the label sources are the fallback (open to discussion, see question 2)

## Demo

Android emulator and Windows desktop recordings: https://github.com/Vijay-Janarthanan/OpenCanvas#readme (the clips show the video starting from 0:00 and following two seeks).

## How it would work (no new dependency, no server)

- finds the video through YouTube's search results and ranks it on channel, title cues and length
- reads the audio frame-size tables from the headers of the song's and the video's MP4 (about 0.3 MB each) and aligns them, so no audio is downloaded or decoded
- stores one offset **and playback speed** per stretch of the song, because music videos are edits and film videos often run a few percent off the album track
- keeps the picture on the song's position with a small pure-Kotlin policy (seek, nudge, hide)
- the engine is a library of mine ([OpenCanvas](https://github.com/Vijay-Janarthanan/OpenCanvas), Apache-2.0, compatible with GPLv3); BitChord would vendor it and keep a thin adapter

## What I measured

On one Windows machine, empty cache: 16 songs in 6 languages all aligned; a playable stream after a median 0.55 s and the sync map after a median 0.95 s (cached: milliseconds). Not measured: physical phones, battery, 720p decode cost.

## Questions for the maintainers

1. Is this a direction you want in BitChord?
2. A synced music video currently wins over a label's short loop. Would you rather keep label canvases first?
3. It is large (about 1,300 hand-written lines across 25 files, plus ~4,000 vendored lines). Would you prefer it split — (1) a standalone fix, (2) the engine and adapter, (3) Android, (4) desktop?
4. The engine uses YouTube's own player endpoints; are you comfortable with that, and should it sit behind a setting?

The code is ready on my fork, based on `v1.8.1`: `Vijay-Janarthanan:pr/opencanvas-music-video-canvas` (three commits; app and desktop unit tests pass). I will open the pull request once you have had a look.
