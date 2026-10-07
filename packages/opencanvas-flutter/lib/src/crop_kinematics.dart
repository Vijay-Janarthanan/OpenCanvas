import 'dart:math' as math;

class CropKinematics {
  static double normalizedCropWidth({
    double sourceAspect = 16 / 9,
    double targetAspect = 9 / 16,
  }) {
    return (targetAspect / sourceAspect).clamp(0.1, 1.0);
  }

  static double clampFocalCenter(
    double rawCenterX, {
    double sourceAspect = 16 / 9,
    double targetAspect = 9 / 16,
  }) {
    final cropWidth = normalizedCropWidth(
      sourceAspect: sourceAspect,
      targetAspect: targetAspect,
    );
    final halfCrop = cropWidth / 2;
    return rawCenterX.clamp(halfCrop, 1.0 - halfCrop);
  }

  static double computeTranslationOffset({
    required double clampedCenterX,
    required double containerWidth,
    required double containerHeight,
    required double videoWidth,
    required double videoHeight,
  }) {
    if (videoHeight <= 0 || containerHeight <= 0) return 0.0;
    final scale = containerHeight / videoHeight;
    final scaledVideoWidth = videoWidth * scale;
    final focalPixelX = clampedCenterX * scaledVideoWidth;
    final containerCenterX = containerWidth / 2;

    return -(focalPixelX - containerCenterX);
  }
}
