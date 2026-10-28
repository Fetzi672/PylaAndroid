package com.pyla.ai.diagnostics

import com.pyla.ai.diagnostics.PipelineTimingTelemetry.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Facade contract on the JVM source set: the Android clock stub is unavailable,
 * so the facade must stay silent, noexcept and never emit eagerly.
 */
class PipelineTimingTelemetryTest {
    @After
    fun cleanUp() {
        PipelineTimingTelemetry.reset()
    }

    @Test
    fun clockFailureIsReportedAndNeverThrows() {
        // android.os.SystemClock is an unmocked stub in JVM unit tests, so this exercises
        // the documented failure path rather than a device clock.
        assertEquals(-1L, PipelineTimingTelemetry.nowMs())
        assertEquals(-1L, PipelineTimingTelemetry.nowMs())
    }

    @Test
    fun everyApiIsNoexceptAndDoesNotEmitEagerly() {
        val before = DiagnosticLog.recentLines(DiagnosticLog.MAX_ENTRIES).size

        repeat(5) { PipelineTimingTelemetry.record(Stage.ENTITY_INFERENCE, 42) }
        assertEquals(-1L, PipelineTimingTelemetry.elapsed(Stage.LOOP_TOTAL, 0))
        PipelineTimingTelemetry.age(Stage.FRAME_AGE_START, 1)
        PipelineTimingTelemetry.model("entity", "cpu", "NNAPI", 4)
        PipelineTimingTelemetry.movementRequest()
        PipelineTimingTelemetry.interruption("unexpected text with spaces")
        PipelineTimingTelemetry.flush()

        assertEquals("facade emitted without a usable clock", before, DiagnosticLog.recentLines(DiagnosticLog.MAX_ENTRIES).size)
        PipelineTimingTelemetry.reset()
        assertEquals(before, DiagnosticLog.recentLines(DiagnosticLog.MAX_ENTRIES).size)
    }

    @Test
    fun stageAndInterruptionSchemasStayFixedAndSmall() {
        assertEquals(19, Stage.entries.size)
        val names = Stage.entries.map { it.name }.toSet()
        assertTrue(
            names.containsAll(
                listOf(
                    "ENTITY_PREPROCESS", "ENTITY_INFERENCE", "ENTITY_DECODE",
                    "TERRAIN_PREPROCESS", "TERRAIN_INFERENCE", "TERRAIN_DECODE",
                    "CLOSE_PREPROCESS", "CLOSE_INFERENCE", "CLOSE_DECODE",
                    "CONTROL_RECOGNITION", "PLAYSTYLE",
                    "FRAME_AGE_START", "FRAME_AGE_DECISION", "FRAME_AGE_GUARD", "FRAME_AGE_DISPATCH",
                    "MOVEMENT_REQUEST_GAP", "LOOP_TOTAL",
                ),
            ),
        )
        val reasons = PipelineTimingAccumulator.INTERRUPTION_REASONS.toSet()
        assertTrue(
            reasons.containsAll(
                listOf(
                    "NO_STRATEGY", "STRATEGY_FAILED", "PLAYER_MISSING", "PLAYER_AMBIGUOUS",
                    "PLAYER_BOX_INVALID", "INPUT_DISABLED", "FRAME_STALE", "STOP", "PAUSED",
                    "FOREGROUND", "GEOMETRY_CHANGED", "AUTHORITY_DENIED", "NONFINITE",
                    "CONTRACT_REJECTED", "STILL", "RELEASE_REQUEST", "APPLY_REVOKED",
                    "APPLY_EXCEPTION", "OTHER",
                ),
            ),
        )
        assertEquals(PipelineTimingAccumulator.INTERRUPTION_REASONS.size, reasons.size)
        assertTrue("interruption vocabulary must stay bounded", reasons.size <= 32)
        assertTrue("one summary line per stage must stay bounded", Stage.entries.size <= 32)
    }
}
