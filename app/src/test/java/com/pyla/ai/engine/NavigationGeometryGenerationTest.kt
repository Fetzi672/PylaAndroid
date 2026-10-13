package com.pyla.ai.engine

import com.pyla.ai.capture.InputCoordinates
import org.junit.Assert.*
import org.junit.Test

class NavigationGeometryGenerationTest {
    @Test fun sameCaptureGeometryDoesNotInvalidateControl() {
        val w = InputCoordinates.captureWidth; val h = InputCoordinates.captureHeight
        val epoch = InputCoordinates.geometryGeneration
        InputCoordinates.setCaptureSize(w, h)
        assertEquals(epoch, InputCoordinates.geometryGeneration)
    }
    @Test fun rotationAndRestoreStillInvalidateOldControl() {
        val w = InputCoordinates.captureWidth; val h = InputCoordinates.captureHeight
        val epoch = InputCoordinates.geometryGeneration
        try {
            InputCoordinates.setCaptureSize(w + 1, h + 1)
            InputCoordinates.setCaptureSize(w, h)
            assertTrue(InputCoordinates.geometryGeneration > epoch)
        } finally { InputCoordinates.setCaptureSize(w, h) }
    }
    @Test fun screenGeometryChangeAlsoInvalidatesCaptureBasedControl() {
        InputCoordinates.setScreenSize(1560, 720)
        val epoch = InputCoordinates.geometryGeneration
        InputCoordinates.setScreenSize(720, 1560)
        InputCoordinates.setScreenSize(1560, 720)
        assertTrue(InputCoordinates.geometryGeneration > epoch)
        val restored = InputCoordinates.geometryGeneration
        InputCoordinates.setScreenSize(1560, 720)
        assertEquals(restored, InputCoordinates.geometryGeneration)
    }
}
