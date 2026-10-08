import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { SyncMap } from '../dist/sync/syncMap.js';
import { CanvasSyncPolicy } from '../dist/sync/canvasSyncPolicy.js';

const vectors = JSON.parse(readFileSync(new URL('../../conformance/sync-vectors.json', import.meta.url), 'utf8'));

for (const c of vectors.cases) {
  test(`vectors: ${c.name}`, () => {
    const map = new SyncMap(c.segments);
    const policy = new CanvasSyncPolicy(map, c.videoDurationMs, c.toleranceMs, c.hardSeekMs);
    for (const { songMs, expect } of c.offsetAt) assert.equal(map.offsetAt(songMs), expect, `offsetAt(${songMs})`);
    for (const d of c.decide) {
      const got = policy.decide(d.songMs, d.videoMs, d.playing, d.ready);
      assert.equal(got.kind, d.expect.kind, `decide(${d.songMs}, ${d.videoMs}, ${d.playing}, ${d.ready})`);
      if (d.expect.videoMs !== undefined) assert.equal(got.videoMs, d.expect.videoMs);
      if (d.expect.speed !== undefined) assert.ok(Math.abs(got.speed - d.expect.speed) < 1e-4);
    }
  });
}

test('vectors: seek detection', () => {
  const policy = new CanvasSyncPolicy(new SyncMap([]), 0);
  for (const s of vectors.seekDetected) {
    assert.equal(policy.seekDetected(s.prevSongMs, s.songMs, s.elapsedWallMs, s.playing), s.expect, JSON.stringify(s));
  }
});
