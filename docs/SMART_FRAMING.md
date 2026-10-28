# Video discovery and subject-aware framing

The current Kotlin core source adds film-aware discovery, independent publisher evidence and local subject framing. These APIs are newer than the published 1.0.0 coordinates in the historical quickstart. Use the current source revision until a new library tag is published. Browser detection, TypeScript/Dart detector integrations and iOS are not included.

## Cosmotune host updates

Cosmotune uses a steady **4:5 portrait viewport**, controls clear of the picture and taller artist headers with a persistent still under motion artwork. Subject tracking is disabled in the current host (`SUBJECT_FRAMING = false`); the library policy below is not the app's active camera. The website comparison uses the same 4:5 target aspect to illustrate that separate policy; its detections are illustrated and its camera policy is the real compiled library policy.

The [Cosmotune 1.0.10 tag](https://github.com/Vijay-Janarthanan/cosmotune/blob/v1.0.10/docs/releases/1.0.10.md) adds unreadable frame-table recovery through another stream, avoidance of replayed footage and an eight-second video-search budget. MP4 recovery, alternate-stream retry and the search budget are now also in this repository. The forward-only map guard remains in the Cosmotune host. They are not in the published 1.0.0 library artifact. See the host's [import record](https://github.com/Vijay-Janarthanan/cosmotune/blob/v1.0.10/docs/OPENCANVAS_IMPORT.json).

Live sites: [OpenCanvas](https://opencanvas.shutterisland.in/), [GitHub documentation](https://vijay-janarthanan.github.io/OpenCanvas/), [Cosmotune](https://cosmotune.shutterisland.in/).

## Choose the right video

Pass film/album metadata when available. Search combines artist and film queries, handles joined title words and a bounded reversed two-word title variant, and deduplicates candidates. Automatic selection requires a verified artist/publisher signal plus relevant title/artist/film metadata. A title merely saying "official" is insufficient. Verification badges are evidence, not a guarantee of ownership.

Every candidate must still pass the motion/still-picture check and measured song-to-video synchronization. A fan dance can contain the original recording, so an audio match alone is insufficient. Old timing-only cached choices and unverified community maps cannot bypass the new selection policy. An explicitly supplied `videoId` remains the host's deliberate choice.

Pass the original catalogue title, including `(From "Film")` or pipe-separated movie fields. The engine extracts the film before stripping those decorations, preferring it to a compilation album. Search retries bounded alternatives after a soundtrack mismatch and rejects full-film uploads. Verified publisher evidence is required for automatic selection; unverified result lists are not a fallback.

Moving artwork can have substantial motion and bitrate. The automatic policy now considers keyframe irregularity with presentation/credited-artist evidence to reject regular-GOP posters as well. Automatic cache policy 3 rechecks older choices. This heuristic is conservative, not a visual classifier; it can decline a genuine clip, and it never establishes soundtrack identity by itself.

Live checks on 2026-10-26 aligned *Nilaave Vaa* with `xm9m6XTx89o` and *Andhi Mazhai* with `U7twY4U73Bw`. The animated INRECO *Germaniyin Senthen Malare* upload was rejected. Its tested film remaster reached only 3% alignment confidence against the stereo source, so a reliable replacement map was not established and artwork remains. Do not invent an offset or relax the recording threshold to make that case play.

```kotlin
OpenCanvas.resolveFlow(
    title = "Manasavacha", artist = "Unni Krishnan, Chitra", album = "Godavari",
    trackVideoId = "1R-gKCrq_HE", mode = OpenCanvasMode.FULL_SYNCED_VIDEO,
).collect { track -> /* Keep the existing measured-sync playback policy. */ }
```

## Follow the people in the picture

The library consumes **uncropped decoded frames supplied by the host**. It does not add a second video decoder or download the whole video for analysis. YuNet detects faces; YOLOX-Nano detects people/body context. The camera uses subject continuity, group bounds, a dead zone and bounded movement. It resets at scene cuts and seeks. Uncertain/no-person scenes and groups that cannot fit use the full frame.

```kotlin
val analysis = OpenCanvas.observeFrames(
    scope = playerScope, videoId = track.videoId,
    sourceAspect = decodedWidth.toFloat() / decodedHeight,
    targetAspect = viewWidth.toFloat() / viewHeight,
)
// On decoded frames. This copies a bounded sample before returning; the source buffer is reusable.
analysis.submitBgra(decodedBgra, decodedWidth, decodedHeight, presentationTimeMs / 1000.0)
// Android/source ARGB buffers:
analysis.submitArgb(argbPixels, width, height, presentationTimeUs / 1_000_000.0)
// Collect analysis.framing, then use shared geometry for your canvas or texture matrix:
val placement = videoViewport(sourceWidth, sourceHeight, viewWidth, viewHeight, decision)
// Close on video/viewport changes or disposal. The scope also cancels the worker.
analysis.close()
```

Use **video presentation time**, not audio time. Keep audio/video synchronization separate. Sample the whole source: detecting only the cropped image cannot recover an actor outside it. Start with full-frame presentation until analysis arrives. Pause/background hosts should stop submitting frames. Layout changes need a worker with the new aspect; debounce animated resize events.

The worker keeps one pending frame, initially samples at about 4 Hz, and lowers its sampling rate when inference is expensive. This bounds analysis pressure rather than delaying playback. The shared viewport helper supports both axes, actual source/container ratios and full-frame fit. `SmartFrameReframer.trajectory()` exports timestamped keyframes; trajectories are cached by video, source/target aspect and model/policy revision. Precomputed `OpenCanvasTrack.trajectory` values can also be rendered.

Android Java resources explicitly include `src/jvmSharedMain/resources`: AGP does not inherit this custom Kotlin source set automatically. The `verifyAndroidFramingResources` Gradle task inspects the built AAR and checks both model hashes.

The host supplies its platform ONNX Runtime artifact (JVM or Android); the core dependency is compile-only. The new framing models total about 3.9 MB. Their exact sources, SHA-256 values and MIT/Apache licence texts ship under `models/`. The previous UltraFace helper and its additional 1.2 MB model remain available in the full core artifact, but the new worker uses YuNet plus YOLOX-Nano.

## Validation and limits

On one Windows desktop, the OpenCV reference group produced 12 face detections and four person detections; warm combined median was about 24 ms. The existing UltraFace helper found 16 faces in about 24 ms on that image. These counts are a diagnostic comparison, not an accuracy study. Performance, thermal behavior and battery on physical Android devices remain unmeasured.

Live discovery tests found measured film-video matches for both reported Godavari songs and excluded the personal-channel dance upload. Search results and stream endpoints can change. Cases without reliable publisher evidence, a readable stream or a measured sync map keep the still artwork. A framing/model failure keeps the whole video frame visible.

Tests cover publisher evidence, legacy cache rejection, still uploads, edge subjects, group/full-body containment, cut/seek resets, crop boundaries, real model inference and framing-cache reuse. The website comparison supplies illustrated detection boxes to the **same camera policy compiled to JavaScript**; it does not run the native detectors in the browser.

[Current validation report and reproducible checks](VALIDATION_2026-10-23.md).

## Film abbreviation regression, 28 October 2026

Film context normalizes S/O and S O to Son of before searching, and publisher checks recognize the equivalent film names. A live automatic lookup for Ayyo Ayyo from M. Kumaran selected Think Tapes video `9SV5acMQlzg` and measured three sync segments with confidence 0.79357 against the playing recording `t9YyaNWhq8M`. The unrelated same-title Telugu songs remain subject to publisher/motion and audio alignment checks. The current source fix requires an updated app build; existing 1.0.10 installers are unchanged.
