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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import com.opencanvas.core.filter.FramingDecision
import com.opencanvas.core.filter.videoViewport
import kotlin.math.roundToInt
import com.opencanvas.core.filter.CropKinematics
import com.opencanvas.core.models.OpenCanvasMode
import com.opencanvas.core.models.OpenCanvasTrack
import com.opencanvas.core.models.SyncStage
import com.opencanvas.core.sync.CanvasSyncPolicy

/**
 * Modern, hardware-accelerated Compose UI player for OpenCanvas.
 *
 * Dynamically reframes widescreen video into a vertical 9:16 portrait viewport
 * at 60 FPS using GPU graphicsLayer translation, keeping the primary artist
 * centered with zero re-encoding.
 *
 * A [OpenCanvasMode.FULL_SYNCED_VIDEO] track follows the song: [videoContent] is handed the video
 * position that belongs to [currentAudioPositionMs] through the track's song-to-video map
 * ([OpenCanvasTrack.syncMap]), so a seek in the song moves the picture to the right second, and
 * the picture is hidden where the map has no matching scene or the track has no measured map. The
 * host keeps its video player at that position (see [CanvasSyncPolicy] for the drift rules).
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
    val policy = remember(track) { CanvasSyncPolicy(track.syncMap(), track.videoDurationMs) }
    val synced = track.mode == OpenCanvasMode.FULL_SYNCED_VIDEO
    var playbackTimeMs by remember(track) {
        mutableLongStateOf(
            if (track.mode == OpenCanvasMode.LOOP_CANVAS) track.loopStartMs else 0L
        )
    }

    // High-precision frame clock loop for playback pacing
    LaunchedEffect(track, isAudioPlaying, currentAudioPositionMs) {
        if (track.mode == OpenCanvasMode.FULL_SYNCED_VIDEO) {
            // In full video mode the picture follows the song through the measured map
            playbackTimeMs = policy.targetVideoMs(currentAudioPositionMs).coerceAtLeast(0L)
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
    val framing = track.trajectory?.frameAt(timestampSec)
    val rawFocalX = framing?.cx ?: .5f
    val clampedFocalX = rawFocalX.coerceIn(0f, 1f)

    // Smooth focal center animation to prevent any micro-jitter
    val animatedFocalX by animateFloatAsState(
        targetValue = clampedFocalX,
        animationSpec = tween(durationMillis = if (framing?.isSceneCut == true) 0 else 180),
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

        val viewport = videoViewport(track.sourceAspectRatio, 1f, containerWidth, containerHeight,
            FramingDecision(animatedFocalX, framing?.cy ?: .5f, framing?.fitFullFrame ?: true))

        // A synced video is only shown while the map says this stretch of the song has its picture
        val pictureVisible = !synced ||
            (track.syncStage == SyncStage.MEASURED && policy.isVisible(currentAudioPositionMs))

        // Render video surface with hardware graphicsLayer translation
        Layout(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = if (pictureVisible) 1f else 0f
                },
            content = { videoContent(playbackTimeMs, Modifier.fillMaxSize()) },
        ) { children, constraints ->
            val child = children.single().measure(Constraints.fixed(viewport.width.roundToInt().coerceAtLeast(1),
                viewport.height.roundToInt().coerceAtLeast(1)))
            layout(constraints.maxWidth, constraints.maxHeight) {
                child.place(viewport.left.roundToInt(), viewport.top.roundToInt())
            }
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
