import 'package:flutter/material.dart';
import 'models.dart';
import 'crop_kinematics.dart';

/// Flutter widget for rendering OpenCanvas with 60 FPS hardware matrix transforms.
class OpenCanvasPlayer extends StatefulWidget {
  final OpenCanvasTrack track;
  final Widget child;
  final bool isAudioPlaying;
  final int currentAudioPositionMs;
  final bool showAttribution;

  const OpenCanvasPlayer({
    super.key,
    required this.track,
    required this.child,
    this.isAudioPlaying = true,
    this.currentAudioPositionMs = 0,
    this.showAttribution = true,
  });

  @override
  State<OpenCanvasPlayer> createState() => _OpenCanvasPlayerState();
}

class _OpenCanvasPlayerState extends State<OpenCanvasPlayer>
    with SingleTickerProviderStateMixin {
  late AnimationController _controller;
  double _focalX = 0.5;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 16),
    )..addListener(_tick);

    if (widget.isAudioPlaying) {
      _controller.repeat();
    }
  }

  void _tick() {
    if (!mounted || !widget.isAudioPlaying) return;

    final tSec = widget.track.mode == OpenCanvasMode.fullSyncedVideo
        ? (widget.currentAudioPositionMs + widget.track.audioOffsetMs) / 1000.0
        : (widget.track.loopStartMs / 1000.0);

    final rawX = widget.track.trajectory?.cropAt(tSec) ?? 0.5;
    final clamped = CropKinematics.clampFocalCenter(rawX);

    if ((_focalX - clamped).abs() > 0.001) {
      setState(() {
        _focalX = clamped;
      });
    }
  }

  @override
  void didUpdateWidget(covariant OpenCanvasPlayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.isAudioPlaying != oldWidget.isAudioPlaying) {
      if (widget.isAudioPlaying) {
        _controller.repeat();
      } else {
        _controller.stop();
      }
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final containerW = constraints.maxWidth;
        final containerH = constraints.maxHeight;
        const sourceAspect = 16 / 9;
        final videoW = containerH * sourceAspect;
        final translationX = CropKinematics.computeTranslationOffset(
          clampedCenterX: _focalX,
          containerWidth: containerW,
          containerHeight: containerH,
          videoWidth: videoW,
          videoHeight: containerH,
        );

        return ClipRect(
          child: Container(
            color: Colors.black,
            width: containerW,
            height: containerH,
            child: Stack(
              fit: StackFit.expand,
              children: [
                Transform.translate(
                  offset: Offset(translationX, 0),
                  child: OverflowBox(
                    maxWidth: videoW,
                    maxHeight: containerH,
                    minWidth: videoW,
                    minHeight: containerH,
                    alignment: Alignment.centerLeft,
                    child: widget.child,
                  ),
                ),
                if (widget.showAttribution)
                  Positioned(
                    bottom: 8,
                    right: 8,
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 6, vertical: 2),
                      decoration: BoxDecoration(
                        color: Colors.black.withOpacity(0.6),
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: const Text(
                        'OpenCanvas',
                        style: TextStyle(
                          color: Colors.white70,
                          fontSize: 10,
                        ),
                      ),
                    ),
                  ),
              ],
            ),
          ),
        );
      },
    );
  }
}
