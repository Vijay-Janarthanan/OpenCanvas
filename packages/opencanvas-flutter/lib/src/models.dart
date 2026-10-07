enum OpenCanvasMode {
  loopCanvas,
  fullSyncedVideo,
}

class CropKeyframe {
  final double t;
  final double cx;
  final double cy;
  final bool isSceneCut;

  const CropKeyframe({
    required this.t,
    required this.cx,
    this.cy = 0.5,
    this.isSceneCut = false,
  });
}

class CropTrajectory {
  final String videoId;
  final List<CropKeyframe> keyframes;

  const CropTrajectory({
    required this.videoId,
    this.keyframes = const [],
  });

  double cropAt(double timestampSec) {
    if (keyframes.isEmpty) return 0.5;
    if (timestampSec <= keyframes.first.t) return keyframes.first.cx;
    if (timestampSec >= keyframes.last.t) return keyframes.last.cx;

    for (int i = 0; i < keyframes.length - 1; i++) {
      if (timestampSec >= keyframes[i].t && timestampSec <= keyframes[i + 1].t) {
        if (keyframes[i + 1].isSceneCut) {
          return keyframes[i].cx;
        }
        final progress = (timestampSec - keyframes[i].t) / (keyframes[i + 1].t - keyframes[i].t);
        final smooth = progress * progress * (3.0 - 2.0 * progress);
        return keyframes[i].cx + (keyframes[i + 1].cx - keyframes[i].cx) * smooth;
      }
    }
    return 0.5;
  }
}

class OpenCanvasTrack {
  final String videoId;
  final String videoStreamUrl;
  final String title;
  final String artist;
  final OpenCanvasMode mode;
  final int loopStartMs;
  final int loopEndMs;
  final int audioOffsetMs;
  final CropTrajectory? trajectory;
  final double targetAspectRatio;
  final String source;

  const OpenCanvasTrack({
    required this.videoId,
    required this.videoStreamUrl,
    required this.title,
    required this.artist,
    this.mode = OpenCanvasMode.loopCanvas,
    this.loopStartMs = 0,
    this.loopEndMs = 10000,
    this.audioOffsetMs = 0,
    this.trajectory,
    this.targetAspectRatio = 9 / 16,
    this.source = 'OpenCanvas',
  });
}
