package com.opencanvas.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencanvas.core.filter.CropKinematics
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasTrack

/**
 * Modern, hardware-accelerated Compose UI player for OpenCanvas.
 *
 * Dynamically reframes widescreen video into a vertical 9:16 portrait viewport
 * at 60 FPS using GPU graphicsLayer translation, keeping the primary artist
 * centered with zero re-encoding.
 */
@Composable
fun OpenCanvasPlayer(
    track: OpenCanvasTrack,
    modifier: Modifier = Modifier,
    isAudioPlaying: Boolean = true,
    currentAudioPositionMs: Long = 0L,
    showAttributionBadge: Boolean = true,
    videoContent: @Composable (currentTimeMs: Long, modifier: Modifier) -> Unit,
) {
    var playbackTimeMs by remember(track) {
        mutableLongStateOf(
            if (track.mode == OpenCanvasMode.LOOP_CANVAS) track.loopStartMs else 0L
        )
    }

    // High-precision frame clock loop for playback pacing
    LaunchedEffect(track, isAudioPlaying, currentAudioPositionMs) {
        if (track.mode == OpenCanvasMode.FULL_SYNCED_VIDEO) {
            // In full video mode, lock to the audio track player timestamp
            playbackTimeMs = (currentAudioPositionMs + track.audioOffsetMs).coerceAtLeast(0L)
        } else {
            // In loop canvas mode, loop smoothly within [loopStartMs, loopEndMs]
            val loopDuration = (track.loopEndMs - track.loopStartMs).coerceAtLeast(1000L)
            var lastNano = 0L
            while (true) {
                withFrameNanos { nowNano ->
                    if (lastNano != 0L && isAudioPlaying) {
                        val dtMs = (nowNano - lastNano) / 1_000_000L
                        val next = playbackTimeMs + dtMs
                        playbackTimeMs = if (next >= track.loopEndMs) {
                            track.loopStartMs + ((next - track.loopStartMs) % loopDuration)
                        } else {
                            next
                        }
                    }
                    lastNano = nowNano
                }
            }
        }
    }

    // Determine current focal center X [0.0f, 1.0f]
    val timestampSec = playbackTimeMs / 1000.0
    val rawFocalX = track.trajectory?.cropAt(timestampSec) ?: 0.5f
    val clampedFocalX = CropKinematics.clampFocalCenter(rawFocalX)

    // Smooth focal center animation to prevent any micro-jitter
    val animatedFocalX by animateFloatAsState(
        targetValue = clampedFocalX,
        animationSpec = tween(durationMillis = 80),
        label = "OpenCanvasFocalX"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color.Black)
    ) {
        val containerWidth = constraints.maxWidth.toFloat()
        val containerHeight = constraints.maxHeight.toFloat()

        // 16:9 source aspect ratio scaling
        val sourceAspect = 16f / 9f
        val videoHeight = containerHeight
        val videoWidth = videoHeight * sourceAspect

        val translationX = CropKinematics.computeTranslationOffset(
            clampedCenterX = animatedFocalX,
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
        )

        // Render video surface with hardware graphicsLayer translation
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.translationX = translationX
                }
        ) {
            videoContent(playbackTimeMs, Modifier.fillMaxSize())
        }

        // Attribution Badge
        if (showAttributionBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "OpenCanvas",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}
