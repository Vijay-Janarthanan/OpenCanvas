package com.opencanvas.core

import com.opencanvas.core.filter.*
import kotlin.test.*

class VideoViewportTest {
    @Test fun offCenterSubjectMovesSourceWithoutExposingBorders() {
        val bounds = videoViewport(1920f, 1080f, 300f, 600f, FramingDecision(.8f, .5f, false))
        assertTrue(bounds.left < -500)
        assertTrue(bounds.left <= 0 && bounds.left + bounds.width >= 300)
    }
    @Test fun fullFrameFallbackContainsEveryPixel() {
        val bounds = videoViewport(1920f, 1080f, 300f, 600f, FramingDecision())
        assertEquals(300f, bounds.width)
        assertEquals(168.75f, bounds.height)
        assertTrue(bounds.top > 0)
    }
    @Test fun actualAspectAndPortraitTopAreRespected() {
        val bounds = videoViewport(1080f, 1920f, 600f, 300f, FramingDecision(.5f, .5f, false), true, true)
        assertEquals(0f, bounds.top)
        assertEquals(300f, bounds.height)
    }
    @Test fun invalidDimensionsDoNotProduceInvalidTransforms() {
        assertEquals(0f, videoViewport(0f, 0f, 300f, 600f, FramingDecision()).width)
    }
}
