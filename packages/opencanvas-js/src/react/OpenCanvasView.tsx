import React, { useEffect, useState, useRef } from 'react';
import { OpenCanvasTrack, OpenCanvasMode } from '../models/types.js';
import { CropKinematics } from '../filter/cropKinematics.js';

export interface OpenCanvasViewProps {
  track: OpenCanvasTrack;
  isAudioPlaying?: boolean;
  currentAudioPositionMs?: number;
  className?: string;
  style?: React.CSSProperties;
  showAttribution?: boolean;
}

/**
 * Universal React / Web component for rendering OpenCanvas with 60 FPS hardware CSS transforms.
 */
export const OpenCanvasView: React.FC<OpenCanvasViewProps> = ({
  track,
  isAudioPlaying = true,
  currentAudioPositionMs = 0,
  className = '',
  style = {},
  showAttribution = true,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [focalX, setFocalX] = useState<number>(0.5);

  useEffect(() => {
    let animId: number;
    let startEpoch = performance.now();
    let currentMs = track.mode === OpenCanvasMode.LOOP_CANVAS ? track.loopStartMs : 0;
    const loopDuration = Math.max(track.loopEndMs - track.loopStartMs, 1000);

    const updateLoop = () => {
      if (isAudioPlaying) {
        if (track.mode === OpenCanvasMode.FULL_SYNCED_VIDEO) {
          currentMs = (currentAudioPositionMs + (track.audioOffsetMs || 0));
        } else {
          const now = performance.now();
          const dt = now - startEpoch;
          startEpoch = now;
          currentMs += dt;
          if (currentMs >= track.loopEndMs) {
            currentMs = track.loopStartMs + ((currentMs - track.loopStartMs) % loopDuration);
          }
        }

        const tSec = currentMs / 1000.0;
        let targetX = 0.5;
        if (track.trajectory && track.trajectory.keyframes.length > 0) {
          // Simple interpolation across keyframes
          const kf = track.trajectory.keyframes;
          if (tSec <= kf[0].t) targetX = kf[0].cx;
          else if (tSec >= kf[kf.length - 1].t) targetX = kf[kf.length - 1].cx;
          else {
            for (let i = 0; i < kf.length - 1; i++) {
              if (tSec >= kf[i].t && tSec <= kf[i + 1].t) {
                const progress = (tSec - kf[i].t) / (kf[i + 1].t - kf[i].t);
                targetX = kf[i].cx + (kf[i + 1].cx - kf[i].cx) * progress;
                break;
              }
            }
          }
        }
        setFocalX(CropKinematics.clampFocalCenter(targetX));
      }
      animId = requestAnimationFrame(updateLoop);
    };

    animId = requestAnimationFrame(updateLoop);
    return () => cancelAnimationFrame(animId);
  }, [track, isAudioPlaying, currentAudioPositionMs]);

  // Viewport calculation
  const containerW = containerRef.current?.clientWidth || 360;
  const containerH = containerRef.current?.clientHeight || 640;
  const sourceAspect = 16 / 9;
  const videoW = containerH * sourceAspect;
  const translationX = CropKinematics.computeTranslationOffset(focalX, containerW, containerH, videoW, containerH);

  return (
    <div
      ref={containerRef}
      className={className}
      style={{
        position: 'relative',
        width: '100%',
        height: '100%',
        overflow: 'hidden',
        backgroundColor: '#000',
        ...style,
      }}
    >
      <div
        style={{
          width: '100%',
          height: '100%',
          transform: `translate3d(${translationX}px, 0px, 0px)`,
          transition: 'transform 80ms ease-out',
        }}
      >
        <video
          src={track.videoStreamUrl}
          autoPlay
          muted
          loop={track.mode === OpenCanvasMode.LOOP_CANVAS}
          playsInline
          style={{
            height: '100%',
            width: `${videoW}px`,
            maxWidth: 'none',
            objectFit: 'cover',
          }}
        />
      </div>

      {showAttribution && (
        <div
          style={{
            position: 'absolute',
            bottom: 8,
            right: 8,
            background: 'rgba(0,0,0,0.6)',
            borderRadius: 4,
            padding: '2px 6px',
            fontSize: 10,
            color: 'rgba(255,255,255,0.85)',
            pointerEvents: 'none',
          }}
        >
          OpenCanvas
        </div>
      )}
    </div>
  );
};
