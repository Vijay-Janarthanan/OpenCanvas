import test from 'node:test';
import assert from 'node:assert/strict';
import { OneEuroFilter } from '../dist/filter/oneEuroFilter.js';
import { CropKinematics } from '../dist/filter/cropKinematics.js';
import { SceneCutDetector } from '../dist/filter/sceneCutDetector.js';

test('1-Euro Filter reduces jitter', () => {
  const filter = new OneEuroFilter(1.0, 0.007);
  let t = 0.0;
  const rawValues = [0.50, 0.52, 0.48, 0.53, 0.47, 0.51, 0.49];
  const filtered = [];

  for (const v of rawValues) {
    filtered.push(filter.filter(v, t));
    t += 0.033;
  }

  const maxDev = Math.max(...filtered.map(x => Math.abs(x - 0.50)));
  assert.ok(maxDev < 0.025, `Max deviation should be small: ${maxDev}`);
});

test('CropKinematics clamps correctly', () => {
  const normWidth = CropKinematics.normalizedCropWidth(16 / 9, 9 / 16);
  assert.ok(Math.abs(normWidth - (81 / 256)) < 0.001);

  const halfCrop = normWidth / 2;
  const clamped = CropKinematics.clampFocalCenter(0.0, 16 / 9, 9 / 16);
  assert.ok(Math.abs(clamped - halfCrop) < 0.001);
});

test('SceneCutDetector detects sudden jumps', () => {
  const detector = new SceneCutDetector(0.25);
  assert.equal(detector.checkCenterJump(0.50), false);
  assert.equal(detector.checkCenterJump(0.55), false);
  assert.equal(detector.checkCenterJump(0.85), true); // Cut detected!
});
