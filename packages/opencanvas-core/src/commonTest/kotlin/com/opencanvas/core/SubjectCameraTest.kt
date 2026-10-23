package com.opencanvas.core

import com.opencanvas.core.filter.SubjectCamera
import com.opencanvas.core.models.*
import kotlin.test.*

class SubjectCameraTest {
    private fun face(x: Float) = DetectionBox(x - .04f, .2f, x + .04f, .4f, .95f)
    @Test fun actorAtTheEdgeMovesTheCropAwayFromTheCenter() {
        val frame = SubjectCamera(16f/9, 9f/16).update(listOf(face(.8f)), emptyList(), 0.0)
        assertFalse(frame.fitFullFrame)
        assertTrue(frame.cx > .7)
    }
    @Test fun twoActorsAtOppositeEdgesAreBothKeptInTheFullFrame() {
        val frame = SubjectCamera(16f/9, 9f/16).update(listOf(face(.15f), face(.85f)), emptyList(), 0.0)
        assertTrue(frame.fitFullFrame)
    }
    @Test fun noDetectionKeepsTheWholeSceneVisible() {
        assertTrue(SubjectCamera(16f/9, 9f/16).update(emptyList(), emptyList(), 0.0).fitFullFrame)
    }
    @Test fun cameraMovementIsBoundedWithinAShotAndResetsAtCutsAndSeeks() {
        val camera = SubjectCamera(16f/9, 9f/16)
        val first = camera.update(listOf(face(.2f)), emptyList(), 1.0)
        val moving = camera.update(listOf(face(.8f)), emptyList(), 1.25)
        assertTrue(moving.cx - first.cx <= .163f)
        val cut = camera.update(listOf(face(.8f)), emptyList(), 1.5, sceneCut = true)
        assertTrue(cut.sceneCut && cut.cx > .7f)
        val seek = camera.update(listOf(face(.2f)), emptyList(), .5)
        assertTrue(seek.sceneCut && seek.cx < .3f)
    }
    @Test fun transientBodyLossDoesNotAlternateFitAndCrop() {
        val camera = SubjectCamera(16f/9, 9f/16)
        val body = DetectionBox(.1f, .02f, .5f, .98f, .9f)
        assertTrue(camera.update(listOf(face(.3f)), listOf(body), 0.0).fitFullFrame)
        assertTrue(camera.update(listOf(face(.3f)), emptyList(), .25).fitFullFrame)
        assertTrue(camera.update(listOf(face(.3f)), emptyList(), .75).fitFullFrame)
        assertFalse(camera.update(listOf(face(.3f)), emptyList(), 1.1).fitFullFrame)
        camera.update(listOf(face(.3f)), listOf(body), 1.25)
        assertFalse(camera.update(listOf(face(.8f)), emptyList(), 1.5, sceneCut = true).fitFullFrame)
    }
    @Test fun fullBodyThatWouldBeCutOffUsesContain() {
        val body = DetectionBox(.1f, .02f, .5f, .98f, .9f)
        assertTrue(SubjectCamera(16f/9, 9f/16).update(listOf(face(.3f)), listOf(body), 0.0).fitFullFrame)
    }
    @Test fun trajectoryDoesNotPanAcrossShotBoundaries() {
        val path = CropTrajectory("video", listOf(CropKeyframe(0.0, .2f), CropKeyframe(1.0, .8f, isSceneCut = true)))
        assertEquals(.2f, path.frameAt(.9).cx)
        assertEquals(.8f, path.frameAt(1.0).cx)
    }
}
