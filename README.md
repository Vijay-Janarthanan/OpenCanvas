# 🎬 OpenCanvas

> **Dynamic, AI-reframed vertical video Canvas for any song in the world — zero backend, 100% client-side, runs everywhere.**  
> *Created by Vijay • Licensed under Apache 2.0*

[![Kotlin Multiplatform](https://img.shields.io/badge/Kotlin-Multiplatform-purple.svg)](https://kotlinlang.org/)
[![Compose Multiplatform](https://img.shields.io/badge/Compose-Multiplatform-blue.svg)](https://www.jetbrains.com/lp/compose-multiplatform/)
[![React Native](https://img.shields.io/badge/React%20Native-Supported-61dafb.svg)](https://reactnative.dev/)
[![Flutter](https://img.shields.io/badge/Flutter-Ready-02569B.svg)](https://flutter.dev/)
[![Zero Backend](https://img.shields.io/badge/Backend-Zero%20Servers-success.svg)]()
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

<div align="center">
  <img src="assets/opencanvas_demo.gif" width="340" alt="OpenCanvas Live Demo in BitChord" style="border-radius: 16px; box-shadow: 0 8px 24px rgba(0,0,0,0.3);" />
  <br />
  <p><em><b>Live OpenCanvas in action:</b> Dynamically streaming and vertically reframing YouTube music videos behind playback with zero servers.</em></p>
  <p>
    <a href="assets/opencanvas_android_showcase.mp4"><b>▶️ Watch Full HD Video with Audio (MP4)</b></a>
  </p>
</div>

---

## 🌟 What is OpenCanvas?

Spotify's **Canvas** (the short looping video that plays behind the song on the "Now Playing" screen) drives a **145% boost in track shares** and **20% more playlist additions**. However, **less than 5% of all streaming songs have a Canvas**, because Spotify requires verified artists to manually shoot and upload vertical clips. Open-source players like **BitChord** fail on over 95% of tracks because Apple Music and Tidal have very limited motion artwork, and Spotify requires user session cookies.

**OpenCanvas changes this universally:**
1. **Any Song in the World**: Dynamically matches any audio track to its Official Music Video (OMV).
2. **Dual Playback Modes**:
   - **Mode A: Looping Canvas (Spotify-Style)**: Pinpoints the 8–12 second visual chorus climax using YouTube's **"Most Replayed"** heatmap.
   - **Mode B: Full Synced Music Video**: Streams the entire music video live from start to finish, reframing on the fly into vertical 9:16 portrait and locked to audio playback!
3. **Smart Subject-Centric Reframing**: An ultra-lightweight on-device AI detector (**UltraFace-slim**, 1.1 MB ONNX) samples frames at a lightweight cadence and applies a **1-Euro Filter** with instant $0\text{ ms}$ scene-cut snapping to smoothly track the lead singer.
4. **Zero-Reencode Hardware Viewport Crop**: Streams the video directly without re-encoding, using GPU hardware-accelerated matrix transforms (Compose `graphicsLayer`, Android `TextureView`, CSS transforms) for 60 FPS silky-smooth motion with zero battery drain.
5. **Zero Hosting Costs**: Precomputed crop paths (< 1 KB JSON) are cached locally and shared through a free global GitHub + jsDelivr CDN (`opencanvas-db`).

---

## 📦 Package Matrix

OpenCanvas is architected as a modular ecosystem so developers can add a single dependency to their platform of choice:

| Package | Ecosystem | Target Platforms | Status |
| :--- | :--- | :--- | :--- |
| **`opencanvas-core`** | Kotlin Multiplatform | Android, JVM Desktop (Windows/Linux/macOS) | ✅ Tested & Ready |
| **`opencanvas-compose`** | Compose Multiplatform | Android & Desktop UI (BitChord ready) | ✅ Tested & Ready |
| **`@opencanvas/core`** | TypeScript / Node | React Native, React Web, Electron | ✅ Tested & Ready |
| **`open_canvas`** | Flutter (Dart) | Android, iOS, Web, Desktop | ✅ Tested & Ready |
| **`opencanvas-db`** | Static JSON Registry | Global jsDelivr CDN (< 1 KB trajectories) | ✅ Schema & Samples |

---

## 🚀 Quickstart Guides

### 1. Kotlin & Compose Multiplatform (Android & Desktop / BitChord)

Add the dependency to your `build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.opencanvas:opencanvas-core:1.0.0")
    implementation("com.opencanvas:opencanvas-compose:1.0.0")
}
```

Resolve and play with one line of code:
```kotlin
// 1. Resolve canvas (Mode A: Loop or Mode B: Full Synced Video)
val canvasTrack = OpenCanvas.resolve(
    title = "Blinding Lights",
    artist = "The Weeknd",
    mode = OpenCanvasMode.LOOP_CANVAS // or OpenCanvasMode.FULL_SYNCED_VIDEO
)

// 2. Render in Compose with hardware viewport reframing
if (canvasTrack != null) {
    OpenCanvasPlayer(
        track = canvasTrack,
        modifier = Modifier.fillMaxSize(),
        isAudioPlaying = isPlaying,
        currentAudioPositionMs = playbackPositionMs,
    ) { currentTimeMs, videoModifier ->
        // Mount your standard ExoPlayer (Android) or JavaCV/FFmpeg (Desktop) surface
        VideoSurface(modifier = videoModifier)
    }
}
```

---

### 2. React Native & Web (`@opencanvas/core`)

Install via npm:
```bash
npm install @opencanvas/core
```

Use in React Native or Web:
```tsx
import React from 'react';
import { OpenCanvasView, OpenCanvasMode } from '@opencanvas/core';

export const NowPlayingScreen = () => {
  const track = {
    videoId: "4NRXx6U8ABQ",
    videoStreamUrl: "https://example.com/stream.mp4",
    title: "Blinding Lights",
    artist: "The Weeknd",
    mode: OpenCanvasMode.LOOP_CANVAS,
    loopStartMs: 40000,
    loopEndMs: 50000,
    source: "OpenCanvas by Vijay",
  };

  return (
    <div style={{ width: '100vw', height: '100vh' }}>
      <OpenCanvasView track={track} isAudioPlaying={true} />
    </div>
  );
};
```

---

### 3. Flutter (`open_canvas`)

Add to `pubspec.yaml`:
```yaml
dependencies:
  open_canvas: ^1.0.0
```

Use in Flutter:
```dart
import 'package:flutter/material.dart';
import 'package:open_canvas/open_canvas.dart';

Widget buildCanvas(OpenCanvasTrack track) {
  return OpenCanvasPlayer(
    track: track,
    isAudioPlaying: true,
    child: VideoPlayer(controller),
  );
}
```

---

## 🔌 BitChord Integration

OpenCanvas is designed as a drop-in fallback provider for the open-source **BitChord** player (`kushagrasinghx/BitChord`).

In `bitchord-integration/`:
- [`OpenCanvasProvider.kt`](bitchord-integration/OpenCanvasProvider.kt): Bridges OpenCanvas into BitChord's `CanvasArtwork` interface.
- [`BITCHORD_PR_PATCH.md`](bitchord-integration/BITCHORD_PR_PATCH.md): Ready-to-submit Pull Request documentation with full git diffs.

---

## ⚙️ How It Works Under the Hood

```
[Now Playing Song] (Title + Artist)
       │
       ▼
┌────────────────────────────────────────────────────────┐
│ 1. Official Music Video Matcher (InnerTube API)        │
│ - Discerns OMV vs Art Track (ATV) / Audio-only         │
│ - Filters out lyric videos, reactions, covers, live    │
└────────────────────────────────────────────────────────┘
       │
       ▼
┌────────────────────────────────────────────────────────┐
│ 2. Visual Climax Selector (Heatmap Analysis)           │
│ - Reads YouTube "Most Replayed" markers directly       │
│ - Pinpoints the 8–12s chorus hook with ZERO audio DSP  │
└────────────────────────────────────────────────────────┘
       │
       ▼
┌────────────────────────────────────────────────────────┐
│ 3. On-Device Keyframe AI Tracking (UltraFace-slim)     │
│ - Samples tiny preview frames (192px) at 3-4 FPS       │
│ - Runs 1.1 MB ONNX model (< 12ms per frame on CPU)     │
│ - Identifies lead singer via IoU trajectory matching   │
└────────────────────────────────────────────────────────┘
       │
       ▼
┌────────────────────────────────────────────────────────┐
│ 4. 1-Euro Filter Stabilizer & Scene-Cut Snapper        │
│ - Eliminates micro-jitters with adaptive low-pass math │
│ - Instant 0ms snap at camera cuts (no whip-panning)    │
└────────────────────────────────────────────────────────┘
       │
       ▼
┌────────────────────────────────────────────────────────┐
│ 5. GPU Viewport Crop Render (60 FPS, Zero Re-encode)   │
│ - Directly transforms video matrix in hardware         │
│ - 0 ms pre-render delay, 0% high CPU spikes, 0 battery │
└────────────────────────────────────────────────────────┘
```

---

## ⚡ Performance Benchmarks

| Metric | Server Re-Encoding (YOLOv8 + FFmpeg) | OpenCanvas (Client-Side Transform) | Improvement |
| :--- | :--- | :--- | :--- |
| **Startup Delay** | 18–35 seconds | **< 250 milliseconds** | **100x Faster** |
| **Network Data** | 45 MB – 80 MB download | **Direct stream (low bitrate)** | **60% Savings** |
| **Battery Drain** | High (transcoding video) | **Zero (native hardware decoder)** | **Negligible** |
| **Server Cost** | $0.002 per track lookup | **$0.00 (Zero hosting cost)** | **100% Free** |
| **Storage Required** | 3 MB video per song | **< 1 KB metadata per song** | **3,000x Smaller** |

---

## 📄 License & Attribution

OpenCanvas is distributed under the **Apache License 2.0**.

**Attribution Notice**:  
OpenCanvas was created and architected by **Vijay Janarthanan** (<vijaybfriendly@gmail.com>).  
All forks, distributions, and commercial uses must retain the copyright notice and credit:  
`Powered by OpenCanvas (Created by Vijay Janarthanan <vijaybfriendly@gmail.com>)`

