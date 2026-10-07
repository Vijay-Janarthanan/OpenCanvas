import 'package:flutter_test/flutter_test.dart';
import 'package:open_canvas/open_canvas.dart';

void main() {
  test('CropKinematics clamps correctly in Dart', () {
    final normWidth = CropKinematics.normalizedCropWidth(
      sourceAspect: 16 / 9,
      targetAspect: 9 / 16,
    );
    expect((normWidth - (81 / 256)).abs() < 0.001, true);

    final halfCrop = normWidth / 2;
    final clamped = CropKinematics.clampFocalCenter(
      0.0,
      sourceAspect: 16 / 9,
      targetAspect: 9 / 16,
    );
    expect((clamped - halfCrop).abs() < 0.001, true);
  });

  test('CropTrajectory interpolates correctly', () {
    const trajectory = CropTrajectory(
      videoId: 'test_vid',
      keyframes: [
        CropKeyframe(t: 0.0, cx: 0.30),
        CropKeyframe(t: 1.0, cx: 0.40),
        CropKeyframe(t: 2.0, cx: 0.80, isSceneCut: true),
      ],
    );

    final mid = trajectory.cropAt(0.5);
    expect(mid >= 0.30 && mid <= 0.40, true);

    final atCut = trajectory.cropAt(2.0);
    expect(atCut, 0.80);
  });
}
