import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:open_canvas/open_canvas.dart';

/// Runs the language-neutral vectors in packages/conformance, the same file the Kotlin and
/// TypeScript implementations are checked against.
void main() {
  final vectors = jsonDecode(
    File('../conformance/sync-vectors.json').readAsStringSync(),
  ) as Map<String, dynamic>;

  for (final c in (vectors['cases'] as List<dynamic>).cast<Map<String, dynamic>>()) {
    test('vectors: ${c['name']}', () {
      final map = SyncMap((c['segments'] as List<dynamic>)
          .map((s) => SyncSegment.fromJson(s as Map<String, dynamic>)));
      final tolerance = (c['toleranceMs'] as num?)?.toInt();
      final policy = CanvasSyncPolicy(
        map,
        (c['videoDurationMs'] as num).toInt(),
        toleranceMs: tolerance ?? CanvasSyncDefaults.toleranceMs,
        hardSeekMs: (c['hardSeekMs'] as num?)?.toInt() ?? CanvasSyncDefaults.hardSeekMs,
      );

      for (final o in (c['offsetAt'] as List<dynamic>).cast<Map<String, dynamic>>()) {
        expect(map.offsetAt((o['songMs'] as num).toInt()), (o['expect'] as num?)?.toInt(),
            reason: 'offsetAt(${o['songMs']})');
      }
      for (final d in (c['decide'] as List<dynamic>).cast<Map<String, dynamic>>()) {
        final got = policy.decide(
          (d['songMs'] as num).toInt(),
          (d['videoMs'] as num).toInt(),
          songPlaying: d['playing'] as bool,
          videoReady: d['ready'] as bool,
        );
        final expected = d['expect'] as Map<String, dynamic>;
        final reason = 'decide(${d['songMs']}, ${d['videoMs']}, ${d['playing']}, ${d['ready']})';
        switch (expected['kind']) {
          case 'hidden':
            expect(got, isA<Hidden>(), reason: reason);
          case 'hold':
            expect(got, isA<Hold>(), reason: reason);
          case 'ended':
            expect(got, isA<Ended>(), reason: reason);
          case 'seek':
            expect(got, isA<SeekTo>(), reason: reason);
            expect((got as SeekTo).videoMs, (expected['videoMs'] as num).toInt(), reason: reason);
          case 'nudge':
            expect(got, isA<Nudge>(), reason: reason);
            expect((got as Nudge).speed, closeTo((expected['speed'] as num).toDouble(), 1e-4), reason: reason);
          default:
            fail('unknown action ${expected['kind']}');
        }
      }
    });
  }

  test('vectors: seek detection', () {
    final policy = CanvasSyncPolicy(SyncMap(const []), 0);
    for (final s in (vectors['seekDetected'] as List<dynamic>).cast<Map<String, dynamic>>()) {
      expect(
        policy.seekDetected(
          (s['prevSongMs'] as num).toInt(),
          (s['songMs'] as num).toInt(),
          (s['elapsedWallMs'] as num).toInt(),
          playing: s['playing'] as bool,
        ),
        s['expect'] as bool,
        reason: '$s',
      );
    }
  });
}
