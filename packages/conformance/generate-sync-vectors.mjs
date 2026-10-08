// Writes sync-vectors.json: the inputs below are written by hand, the expected answers come from the
// TypeScript port and are then checked by every other implementation (Kotlin, Dart, ...).
//
//   cd packages/opencanvas-js && npm run build && node ../conformance/generate-sync-vectors.mjs
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'opencanvas-js', 'dist', 'sync');
const { SyncMap } = require(join(root, 'syncMap.js'));
const { CanvasSyncPolicy } = require(join(root, 'canvasSyncPolicy.js'));

const luther = [
  { songStartMs: 0, songEndMs: 32500, offsetMs: 12891 },
  { songStartMs: 32500, songEndMs: 82500, offsetMs: 37975 },
  { songStartMs: 82500, songEndMs: 177599, offsetMs: 76916 },
];

const cases = [
  {
    name: 'one constant offset (the video starts 9.9 s before the song)',
    segments: [{ songStartMs: 0, songEndMs: 174000, offsetMs: 9910 }],
    videoDurationMs: 240000,
    offsetAt: [0, 1, 100000, 173999, 174000, 500000],
    decide: [
      [0, 9910, true, true], [0, 9910, false, true], [5000, 14910, true, true], [5000, 14910 + 150, true, true],
      [5000, 14910 + 151, true, true], [5000, 14910 - 400, true, true], [5000, 14910 + 500, true, true],
      [5000, 14910 + 501, true, true], [5000, 0, false, true], [5000, 14950, false, true], [5000, 0, true, false],
      [174000, 0, true, true],
    ],
  },
  {
    name: 'an edited video: three stretches, a hard seek at every edit point',
    segments: luther,
    videoDurationMs: 300000,
    offsetAt: [0, 32499, 32500, 82499, 82500, 177598, 177599],
    decide: [
      [1000, 13891, true, true], [32500, 32400 + 12891, true, true], [82500, 82400 + 37975, true, true],
      [100000, 176916, true, true], [177599, 0, true, true], [200000, 0, true, true],
    ],
  },
  {
    name: 'a gap in the map: no matching picture there',
    segments: [
      { songStartMs: 0, songEndMs: 10000, offsetMs: 1000 },
      { songStartMs: 20000, songEndMs: 30000, offsetMs: 5000 },
    ],
    videoDurationMs: 100000,
    offsetAt: [0, 9999, 10000, 15000, 19999, 20000, 29999, 30000],
    decide: [[5000, 6000, true, true], [15000, 16000, true, true], [25000, 30000, true, true], [25000, 30500, false, true]],
  },
  {
    name: 'a video that starts after the song (negative offset): hidden until it begins',
    segments: [{ songStartMs: 0, songEndMs: 200000, offsetMs: -311 }],
    videoDurationMs: 210000,
    offsetAt: [0, 311, 312, 100000],
    decide: [[0, 0, true, true], [310, 0, true, true], [311, 0, true, true], [312, 1, true, true], [1000, 689, true, true]],
  },
  {
    name: 'a video shorter than the song never loops',
    segments: [{ songStartMs: 0, songEndMs: 300000, offsetMs: 0 }],
    videoDurationMs: 100000,
    offsetAt: [0, 99999, 100000],
    decide: [[99999, 99999, true, true], [100000, 99999, true, true], [250000, 99999, true, true]],
  },
  {
    name: 'unknown video length (0): only the start boundary applies',
    segments: [{ songStartMs: 0, songEndMs: 300000, offsetMs: 2000 }],
    videoDurationMs: 0,
    offsetAt: [0, 1000],
    decide: [[250000, 252000, true, true]],
  },
  {
    name: 'a film video at 0.9225 of the album track speed',
    segments: [{ songStartMs: 0, songEndMs: 240000, offsetMs: 500, rate: 0.9225 }],
    videoDurationMs: 300000,
    offsetAt: [0, 60000, 239999],
    decide: [[0, 500, true, true], [60000, Math.round(60000 + 500 + (0.9225 - 1) * 60000), true, true], [60000, 56500, true, true]],
  },
  {
    name: 'a tighter tolerance and hard-seek limit',
    segments: [{ songStartMs: 0, songEndMs: 100000, offsetMs: 0 }],
    videoDurationMs: 100000,
    toleranceMs: 300,
    hardSeekMs: 300,
    offsetAt: [0],
    decide: [[10000, 10250, true, true], [10000, 10300, true, true], [10000, 10301, true, true], [10000, 9699, true, true]],
  },
  {
    name: 'overlapping input is trimmed (the later segment starts where the earlier one ends)',
    segments: [
      { songStartMs: 0, songEndMs: 10000, offsetMs: 100 },
      { songStartMs: 5000, songEndMs: 15000, offsetMs: 200 },
    ],
    videoDurationMs: 100000,
    offsetAt: [0, 4999, 5000, 9999, 10000, 14999, 15000],
    decide: [],
  },
];

const seekDetected = [
  [10000, 10250, 250, true],
  [10000, 11000, 250, true],
  [10000, 11251, 250, true],
  [10000, 10000, 250, false],
  [10000, 12500, 250, false],
  [10000, 9000, 250, true],
  [60000, 10000, 250, true],
  [10000, 10260, -5, true],
];

const out = {
  description:
    'Language-neutral test vectors for SyncMap and CanvasSyncPolicy. decide() uses toleranceMs 150 and hardSeekMs 500 unless a case sets them. Expected speeds are compared to 1e-4.',
  version: 1,
  cases: cases.map((c) => {
    const map = new SyncMap(c.segments);
    const policy = new CanvasSyncPolicy(map, c.videoDurationMs, c.toleranceMs, c.hardSeekMs);
    return {
      name: c.name,
      segments: c.segments,
      videoDurationMs: c.videoDurationMs,
      ...(c.toleranceMs === undefined ? {} : { toleranceMs: c.toleranceMs, hardSeekMs: c.hardSeekMs }),
      offsetAt: c.offsetAt.map((songMs) => ({ songMs, expect: map.offsetAt(songMs) })),
      decide: c.decide.map(([songMs, videoMs, playing, ready]) => ({ songMs, videoMs, playing, ready, expect: policy.decide(songMs, videoMs, playing, ready) })),
    };
  }),
  seekDetected: (() => {
    const policy = new CanvasSyncPolicy(new SyncMap([]), 0);
    return seekDetected.map(([prevSongMs, songMs, elapsedWallMs, playing]) => ({ prevSongMs, songMs, elapsedWallMs, playing, expect: policy.seekDetected(prevSongMs, songMs, elapsedWallMs, playing) }));
  })(),
};

writeFileSync(join(dirname(fileURLToPath(import.meta.url)), 'sync-vectors.json'), JSON.stringify(out, null, 1) + '\n');
console.log(`${out.cases.length} cases, ${out.cases.reduce((n, c) => n + c.offsetAt.length + c.decide.length, 0) + out.seekDetected.length} checks`);
