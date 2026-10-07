# OpenCanvas stream server (optional reference tool)

> **No app needs this.** The OpenCanvas library resolves streams and measures the song-to-video map
> itself, inside the app, on every platform (from the headers of two small MP4 files; see the main
> [README](../../README.md)). This daemon is the first implementation of the alignment — it decodes both
> audio streams — and it stays in the repository as an independent reference: the library's on-device
> aligner was checked against it (`AlignerParityManualTest`), and it is a convenient way to build maps in
> bulk for `opencanvas-db`.

A small HTTP daemon that runs **on your own machine** and offers two endpoints:

1. **`/resolve`** - turns a YouTube video id into a direct, progressive video stream URL (with
   [yt-dlp](https://github.com/yt-dlp/yt-dlp)), at the resolution you ask for.
2. **`/sync`** - works out how a song's timeline maps onto its music video's, so a player can keep
   the video at the song's position, jump when you seek or skip, and never loop.

Nothing is sent anywhere except to YouTube itself; results are cached under `~/.opencanvas/sync`.

## Why `/sync` exists

A music video is almost never the album track with pictures added. It has an intro, and very often it
is an *edit*: a bar repeated, a bar dropped, a longer outro. Measured on the official *Blinding Lights*
video, the song starts 22.6 s into the video for the first two minutes, and from the bridge on the
video is a further 2.6 s ahead (+25.2 s). One constant offset would be wrong for the last third of the
song, and the lyrics, the audio and the picture would drift apart.

So `/sync` returns a **map**: a list of segments, each with its own offset, and nothing for the parts
of the song that have no matching picture (the player shows the still cover there).

## Install and run

```bash
pip install -r requirements.txt     # yt-dlp, numpy
# ffmpeg must be on your PATH (https://ffmpeg.org/download.html)
python server.py                    # listens on 0.0.0.0:18999
```

Options: `--host`, `--port`, `--cache-dir` (or `OPENCANVAS_HOST`, `OPENCANVAS_PORT`,
`OPENCANVAS_CACHE_DIR`).

By default it listens on port 18999 (on this machine: `http://127.0.0.1:18999`; the Android emulator
reaches it as `http://10.0.2.2:18999`).

## Endpoints

### `GET /health`

```json
{"status": "ok"}
```

### `GET /resolve?v=<videoId>|q=<search>[&max_height=<n>][&max_width=<n>]`

```json
{"videoId": "4NRXx6U8ABQ", "title": "The Weeknd - Blinding Lights (Official Video)",
 "streamUrl": "https://...googlevideo.com/videoplayback?...", "duration": 263, "width": 1280, "height": 720}
```

Cached in memory for 30 minutes. Errors are JSON with HTTP 500.

### `GET /sync?track=<songId>&video=<musicVideoId>[&refresh=1]`

`track` is the YouTube (Music) id of the audio the player is playing; `video` is the id of the music
video. The first call for a pair downloads both audio streams and aligns them (about 10 s); every
later call is served from the cache in a few milliseconds.

```json
{
  "trackId": "J7p4bzqLvCw", "videoId": "4NRXx6U8ABQ",
  "offsetMs": 22612,
  "confidence": 0.8484,
  "coverage": 0.6201,
  "segments": [
    {"songStartMs": 0,      "songEndMs": 127500, "offsetMs": 22612, "ncc": 0.8331},
    {"songStartMs": 127500, "songEndMs": 201573, "offsetMs": 25198, "ncc": 0.8747}
  ],
  "cached": false, "computeMs": 9702
}
```

* **Inside a segment, `videoTimeMs = songTimeMs + offsetMs`.** `offsetMs` is where, on the video's
  timeline, the song would start if the segment reached back to song time 0: a video with a 22.5 s
  intro gives `+22500`, a video that starts after the audio does gives a negative number.
* Song time outside every segment has **no matching picture** - show the still artwork.
* `offsetMs` (top level) is the longest segment's offset, for clients that can only use one number.
* `confidence` is the matched share of the song times the mean match quality, `0..1`. Treat
  anything under **0.4** as "not alignable" (a live cut, a cover, a different mix).
* `coverage` is the share of the song that has a trusted match.
* On failure the response has an `error` string, `offsetMs: 0`, `confidence: 0` and no segments.

`GET /sync/cache` lists the pairs already cached.

## How the alignment works

Both audio streams are decoded to mono 8 kHz. The song is cut into overlapping 10 s windows (hop 5 s);
each window is cross-correlated with the whole video audio (FFT, normalised by the energy of the
matched slice) and its strongest few peaks become *candidate* positions.

Pop songs repeat loops and choruses, so the best peak of a window is often a *different repeat* of the
same material. The windows are therefore decided together: a Viterbi pass picks one offset per window,
maximising the total match quality minus a penalty for every change of offset - and the penalty grows
with the size of the change, because real edits move a video by seconds, while a chorus a minute away
is a different place, not an edit. A repeat that would make the picture jump around loses to the chain
that stays continuous; a genuine edit survives, because many windows agree with the new offset.

Consecutive windows with one offset become a segment (offset = median of their refined peaks, sample
precision). A short gap between two segments is split at its midpoint so the video keeps playing
through an instrumental bridge; a long gap stays unmatched.

## Tests

```bash
python -m unittest              # 43 tests, no network: synthetic songs with known intros, edits and loops
python manual_check.py          # real network: Blinding Lights against its official video
```

## Privacy and network exposure

The daemon only talks to YouTube (to fetch the streams and the two audio files). It listens on all
interfaces (`0.0.0.0`) unless told otherwise; run `python server.py --host 127.0.0.1` if you do not
want other machines to reach it - the Android emulator's `10.0.2.2` still does, because that address is
the emulator's alias for your computer's own loopback.
