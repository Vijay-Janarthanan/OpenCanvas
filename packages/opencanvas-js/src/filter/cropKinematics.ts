export class CropKinematics {
  static normalizedCropWidth(sourceAspect = 16 / 9, targetAspect = 9 / 16): number {
    return Math.min(Math.max(targetAspect / sourceAspect, 0.1), 1.0);
  }

  static clampFocalCenter(rawCenterX: number, sourceAspect = 16 / 9, targetAspect = 9 / 16): number {
    const cropWidth = CropKinematics.normalizedCropWidth(sourceAspect, targetAspect);
    const halfCrop = cropWidth / 2;
    return Math.min(Math.max(rawCenterX, halfCrop), 1.0 - halfCrop);
  }

  static computeTranslationOffset(
    clampedCenterX: number,
    containerWidth: number,
    containerHeight: number,
    videoWidth: number,
    videoHeight: number
  ): number {
    if (videoHeight <= 0 || containerHeight <= 0) return 0;
    const scale = containerHeight / videoHeight;
    const scaledVideoWidth = videoWidth * scale;
    const focalPixelX = clampedCenterX * scaledVideoWidth;
    const containerCenterX = containerWidth / 2;

    return -(focalPixelX - containerCenterX);
  }
}
