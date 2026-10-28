package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Source-wiring checks only. Native callback timings are not physical game-effect proof. */
class PipelineDiagnosticWiringTest {
    private fun source(name: String): String = sequenceOf(
        File("src/main/java/com/pyla/ai/$name.kt"), File("app/src/main/java/com/pyla/ai/$name.kt")
    ).first { it.isFile }.readText()

    @Test fun originalCaptureTimestampFlowsToThreeObservationBoundaries() {
        val s = source("engine/Play")
        for (stage in listOf("START", "DECISION", "DISPATCH"))
            assertTrue(s.contains("PipelineTimingTelemetry.age(Stage.FRAME_AGE_$stage, capturedAtMs)"))
        assertTrue(s.contains("classificationCapturedAtMs = capturedAtMs"))
        assertTrue(s.contains("gameplayEvidence.observe(statePermitted, playerBoxes, boxValid, capturedAtMs, nowMs)"))
        assertFalse(s.contains("capturedAtMs = PipelineTimingTelemetry"))
    }

    @Test fun strategyAndControlTimingUsesFinallyWithoutReplacingOutcomes() {
        val s = source("engine/Play")
        assertTrue(s.contains("val outcome = try { runPlaystyle(brawler, data, frame) } finally"))
        assertTrue(s.contains("elapsed(Stage.PLAYSTYLE, diagnosticScriptStarted)"))
        assertTrue(s.contains("elapsed(Stage.CONTROL_RECOGNITION, diagnosticControlStarted)"))
        assertTrue(s.contains("elapsed(Stage.LOOP_TOTAL, diagnosticLoopStarted)"))
        assertTrue(s.contains("applyPlaystyleOutcome(outcome, frameGeneration, issuingThread)"))
    }

    @Test fun movementObservationDoesNotReplaceOriginalGuardOrQueue() {
        val s = source("engine/WindowController")
        val body = s.substringAfter("fun moveGuarded(").substringBefore("fun releaseMovement()")
        assertEquals(1, Regex("guard\\(\\)").findAll(body).count())
        assertTrue(body.contains("PipelineTimingTelemetry.movementRequest()"))
        assertTrue(body.contains("input.joystickMoveGuarded(sx, sy, guard)"))
        assertTrue(s.contains("PipelineTimingTelemetry.interruption(\"RELEASE_REQUEST\")"))
    }

    @Test fun nativeTimingWrapsActualDispatchAndBothOriginalCallbacks() {
        val s = source("input/InputService")
        assertEquals(2, Regex("elapsed\\(Stage.INPUT_CALLBACK, diagnosticNativeStarted\\)").findAll(s).count())
        assertTrue(s.contains("dispatchGesture(gesture, object : GestureResultCallback()"))
        assertTrue(s.contains("elapsed(Stage.INPUT_SUBMISSION, diagnosticNativeStarted)"))
        for (reason in listOf("ANDROID_CANCELLED", "ANDROID_REJECTED", "ANDROID_DISPATCH_EXCEPTION"))
            assertTrue(s.contains("interruption(\"$reason\")"))
    }

    @Test fun stopAndPauseSummariesFollowRevocationAndRelease() {
        val s = source("engine/BotEngine")
        val stop = s.substringAfter("fun stop() {").substringBefore("private fun markMainWorkerSettled")
        assertTrue(stop.indexOf("sessionAuthority.close()") < stop.indexOf("PipelineTimingTelemetry.flush()"))
        assertTrue(stop.indexOf("windowController.releaseMovement()") < stop.indexOf("PipelineTimingTelemetry.flush()"))
        assertTrue(stop.contains("if (owner && firstStop) {\n            PipelineTimingTelemetry.interruption(\"STOP\")"))
        val pause = s.substringAfter("fun setPaused(p: Boolean)").substringBefore("fun isPaused()")
        assertTrue(pause.indexOf("sessionAuthority.invalidate()") < pause.indexOf("PipelineTimingTelemetry.flush()"))
        assertTrue(pause.indexOf("windowController.releaseMovement()") < pause.indexOf("PipelineTimingTelemetry.flush()"))
    }
}
