package com.pyla.ai.engine

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Source wiring only; behavior and JNI paths have separate gates. */
class ChaosContinuationWiringTest {
    private fun source(name: String): String = listOf(File("src/main/java/com/pyla/ai/engine/$name.kt"),
        File("app/src/main/java/com/pyla/ai/engine/$name.kt")).first { it.isFile }.readText()
    private fun method(body: String, start: String, end: String): String =
        body.substring(body.indexOf(start), body.indexOf(end, body.indexOf(start)))

    @Test fun pauseAndResumeInvalidateChaosContinuation() {
        assertTrue(method(source("BotEngine"), "fun setPaused(", "fun isPaused(")
            .contains("PhoneVisualCues.revokeChaosContinuation()"))
    }
    @Test fun stopInvalidatesChaosContinuation() {
        assertTrue(method(source("BotEngine"), "fun stop()", "private fun markMainWorkerSettled")
            .contains("PhoneVisualCues.revokeChaosContinuation()"))
    }
    @Test fun sessionStartInvalidatesChaosContinuation() {
        assertTrue(method(source("BotEngine"), "fun start()", "private fun bootstrapAndRun")
            .contains("PhoneVisualCues.revokeChaosContinuation()"))
    }
    @Test fun enginePassesGenerationAndActualCaptureTime() {
        val e = source("BotEngine")
        assertTrue(e.contains("classifyAdmitted(mat, chaosGeneration, copied.capturedAtMs)"))
        assertTrue(e.contains("classifyAdmitted(currentMat, chaosGeneration, frame.capturedAtMs)"))
        val wrapper = method(e, "private fun classifyAdmitted(", "private lateinit var windowController")
        assertTrue(wrapper.contains("StateFinder.getStateObservation(mat, chaosGeneration, capturedAtMs)"))
        assertFalse(wrapper.contains("capturedAtMs = SystemClock"))
        assertTrue(e.indexOf("val chaosGeneration = PhoneVisualCues.chaosObservationGeneration()") <
            e.indexOf("val copied = capture.copyLatestFrame(stateFrameBuffer)"))
    }
    @Test fun classifierThreadsCaptureMetadataAndUnboundCallsAreReadOnly() {
        val s = source("StateFinder")
        assertTrue(s.contains("chaosGeneration: Long? = null"))
        assertTrue(s.contains("PhoneVisualCues.isChaosDrop(screenshot, chaosGeneration = chaosGeneration, capturedAtMs = capturedAtMs)"))
        assertTrue(s.contains("PhoneVisualCues.revokeChaosContinuation(chaosGeneration)"))
        val v = source("PhoneVisualCues")
        assertTrue(v.contains("chaosGeneration == null || chaosGate.arm("))
        assertTrue(v.contains("chaosGeneration != null && chaosGate.continues("))
    }
    @Test fun otherFrameProducersPassTheOriginalTimestamp() {
        val s = source("StageManager")
        assertTrue(s.contains("StateFinder.getState(mat, chaosGeneration, captured.capturedAtMs)"))
        assertTrue(s.contains("StateFinder.getState(actual, chaosGeneration, captured.capturedAtMs)"))
        assertTrue(source("LobbyAutomation").contains("StateFinder.getState(mat, chaosGeneration, frame.capturedAtMs)"))
        val p = source("Play")
        assertTrue(p.contains("classificationCapturedAtMs = capturedAtMs"))
        assertTrue(p.contains("StateFinder.getStateObservation(it, classificationGeneration, classificationCapturedAtMs)"))
    }
    @Test fun lifecycleRevocationIsAtomicWithTheOwnerCheck() {
        val engine = source("BotEngine")
        val guarded = Regex("synchronized\\(sessionOwnershipLock\\) \\{\\s+if \\(instance === this\\) PhoneVisualCues\\.revokeChaosContinuation\\(\\)")
        assertEquals("Start, Stop and Pause/Resume must serialize revocation with replacement", 3, guarded.findAll(engine).count())
    }
    @Test fun globalTimestampsAreReplacedByTheGateAndRevocationClock() {
        val v = source("PhoneVisualCues")
        assertFalse(v.contains("private var chaosAt"))
        assertTrue(v.contains("chaosGate.arm(chaosGeneration, nowMs, size(image), capturedAtMs)"))
        assertTrue(v.contains("chaosGate.continues(chaosGeneration, nowMs, size(image), capturedAtMs)"))
        assertTrue(v.contains("chaosGate.invalidate(SystemClock.elapsedRealtime(), expectedGeneration)"))
    }
}
