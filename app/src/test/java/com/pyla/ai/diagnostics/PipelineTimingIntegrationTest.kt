package com.pyla.ai.diagnostics

import org.junit.Assert.*
import org.junit.Test
import com.pyla.ai.diagnostics.PipelineTimingTelemetry.Stage

class PipelineTimingIntegrationTest {
    @Test fun realAdmissionAndGuardReasonsDoNotCollapseIntoOther() {
        val reasons = listOf("STATE_MENU_REVOKED", "STALE_FRAME", "FUTURE_FRAME", "SAME_FRAME_REPEATED", "AWAITING_SECOND_OBSERVATION", "CLOSED", "ISSUING_THREAD_INTERRUPTED", "GENERATION_CHANGED")
        val lines = mutableListOf<Pair<String,String>>()
        val a = PipelineTimingAccumulator({100L}) { k,v -> lines.add(k to v) }
        reasons.forEach(a::interruption); a.flush()
        val detail = lines.single { it.first == "timing.interruptions" }.second
        reasons.forEach { assertTrue(detail, detail.contains("$it=1")) }
        assertFalse(detail.contains("OTHER="))
    }

    @Test fun oldCaptureAndFailedResetClockCannotContaminateNextRun() {
        var now = 1000L; var broken = false
        val lines = mutableListOf<Pair<String,String>>()
        val a = PipelineTimingAccumulator({if (broken) error("synthetic clock failure") else now}) { k,v -> lines.add(k to v) }
        a.reset(); a.age(Stage.FRAME_AGE_START, 900); a.flush()
        assertTrue(lines.isEmpty())
        broken = true; a.reset(); broken = false; now = 2000L
        assertEquals(-1L, a.elapsed(Stage.ENTITY_INFERENCE, 900))
        a.age(Stage.FRAME_AGE_START, 900); a.flush(); assertTrue(lines.isEmpty())
        a.reset(); now = 2010L; assertEquals(10L, a.elapsed(Stage.ENTITY_INFERENCE, 2000))
    }

    @Test fun bothModelRolesSurviveUntilTheSameSummary() {
        val lines = mutableListOf<Pair<String,String>>()
        val a = PipelineTimingAccumulator({100L}) { k,v -> lines.add(k to v) }
        a.model("entity", "NNAPI", "XNNPACK", 4)
        a.model("tile", "NNAPI", "NNAPI", 4)
        a.flush()
        assertTrue(lines.any { it.first == "timing.model" && it.second.contains("role=entity ") })
        assertTrue(lines.any { it.first == "timing.model" && it.second.contains("role=tile ") })
    }

    @Test fun aNewWindowContainsOnlyItsOwnSamplesAndKeepsLifetimeTotal() {
        var now = 100L
        val lines = mutableListOf<Pair<String,String>>()
        val a = PipelineTimingAccumulator({now}) { k,v -> lines.add(k to v) }
        a.record(Stage.ENTITY_INFERENCE, 900)
        a.flush()
        lines.clear(); now += 5000
        a.record(Stage.ENTITY_INFERENCE, 1)
        val line = lines.single { it.first == "timing.entity_inference" }.second
        assertTrue(line, line.contains("p50=1ms"))
        assertTrue(line, line.contains("p95=1ms"))
        assertTrue(line, line.contains("max=1ms"))
        assertTrue(line, line.contains("n_sample=1"))
        assertTrue(line, line.contains("n_total=2"))
    }

    @Test fun completeFixedReasonSummaryFitsActualLoggerLimit() {
        val lines = mutableListOf<Pair<String,String>>()
        val a = PipelineTimingAccumulator({100L}) { k,v -> lines.add(k to v) }
        PipelineTimingAccumulator.INTERRUPTION_REASONS.forEach(a::interruption)
        a.flush()
        val line = lines.single { it.first == "timing.interruptions" }.second
        assertTrue(line.length <= DiagnosticLog.MAX_MESSAGE_CHARS)
        PipelineTimingAccumulator.INTERRUPTION_REASONS.forEach { assertTrue(line.contains("$it=1")) }
    }
}
