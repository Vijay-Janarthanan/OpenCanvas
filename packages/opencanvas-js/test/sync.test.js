import test from 'node:test';
import assert from 'node:assert/strict';
import { SyncMap } from '../dist/sync/syncMap.js';
import { CanvasSyncPolicy } from '../dist/sync/canvasSyncPolicy.js';

// the map measured for Kendrick Lamar & SZA - luther: an edited video, three stretches
const luther = SyncMap.fromJson({
  segments: [
    { songStartMs: 0, songEndMs: 32500, offsetMs: 12891, ncc: 0.959 },
    { songStartMs: 32500, songEndMs: 82500, offsetMs: 37975, ncc: 0.9734 },
    { songStartMs: 82500, songEndMs: 177599, offsetMs: 76916, ncc: 0.9905 },
  ],
});

test('offset follows the segment in force', () => {
  assert.equal(luther.offsetAt(0), 12891);
  assert.equal(luther.offsetAt(32499), 12891);
  assert.equal(luther.offsetAt(32500), 37975);
  assert.equal(luther.offsetAt(177598), 76916);
  assert.equal(luther.offsetAt(177599), null);
  assert.equal(luther.primaryOffsetMs, 76916);
});

test('a gap has no picture and the nearest offset', () => {
  const gap = new SyncMap([
    { songStartMs: 0, songEndMs: 10000, offsetMs: 1000 },
    { songStartMs: 20000, songEndMs: 30000, offsetMs: 5000 },
  ]);
  assert.equal(gap.offsetAt(15000), null);
  assert.equal(gap.covers(15000), false);
  assert.equal(gap.offsetNear(11000), 1000);
  assert.equal(gap.offsetNear(19000), 5000);
});

test('a segment with a rate drifts by (rate - 1) per song millisecond', () => {
  const m = new SyncMap([{ songStartMs: 0, songEndMs: 100000, offsetMs: 0, rate: 0.9225 }]);
  assert.equal(m.offsetAt(0), 0);
  assert.equal(m.offsetAt(100000 - 1), Math.round((0.9225 - 1) * 99999));
  assert.equal(m.rateAt(50000), 0.9225);
});

test('overlapping input is trimmed, empty input stays empty', () => {
  const m = new SyncMap([
    { songStartMs: 0, songEndMs: 10000, offsetMs: 100 },
    { songStartMs: 5000, songEndMs: 15000, offsetMs: 200 },
  ]);
  assert.deepEqual(m.segments.map((s) => [s.songStartMs, s.songEndMs]), [[0, 10000], [10000, 15000]]);
  assert.equal(SyncMap.fromJson({}).isEmpty, true);
  assert.equal(SyncMap.fromJson({ offsetMs: 44 }).offsetAt(1234), 44);
});

test('policy: hidden, ended, hold, seek, nudge', () => {
  const policy = new CanvasSyncPolicy(luther, 300000);
  assert.deepEqual(policy.decide(200000, 0, true, true), { kind: 'hidden' });                    // past the map
  assert.deepEqual(new CanvasSyncPolicy(SyncMap.constant(-500), 1000).decide(100, 0, true, true), { kind: 'hidden' }); // before the video
  assert.deepEqual(new CanvasSyncPolicy(SyncMap.constant(10000), 5000).decide(0, 0, true, true), { kind: 'ended' });
  assert.deepEqual(policy.decide(1000, 0, true, false), { kind: 'hold' });
  assert.deepEqual(policy.decide(1000, 13891, true, true), { kind: 'nudge', speed: 1 });          // on target
  assert.deepEqual(policy.decide(1000, 14891, true, true), { kind: 'seek', videoMs: 13891 });     // 1 s ahead
  const slow = policy.decide(1000, 13891 + 400, true, true);                                    // 400 ms ahead: slow down
  assert.equal(slow.kind, 'nudge');
  assert.ok(slow.speed < 1 && slow.speed >= 0.95);
  assert.deepEqual(policy.decide(1000, 0, false, true), { kind: 'seek', videoMs: 13891 });        // paused: put it in place
  assert.deepEqual(policy.decide(1000, 13900, false, true), { kind: 'hold' });
});

test('policy crosses an edit point with a hard seek', () => {
  const policy = new CanvasSyncPolicy(luther, 300000);
  const justBefore = 32400 + 12891;
  assert.deepEqual(policy.decide(32500, justBefore, true, true), { kind: 'seek', videoMs: 32500 + 37975 });
});

test('seek detection ignores ordinary progress', () => {
  const policy = new CanvasSyncPolicy(luther, 300000);
  assert.equal(policy.seekDetected(10000, 10260, 250, true), false);
  assert.equal(policy.seekDetected(10000, 60000, 250, true), true);
  assert.equal(policy.seekDetected(10000, 12500, 250, false), true);
});

test('policy rejects a hard-seek limit below the tolerance', () => {
  assert.throws(() => new CanvasSyncPolicy(luther, 1000, 300, 100), RangeError);
});
