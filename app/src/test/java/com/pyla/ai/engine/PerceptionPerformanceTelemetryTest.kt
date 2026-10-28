package com.pyla.ai.engine

import com.pyla.ai.diagnostics.DiagnosticLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused host tests for the perception performance telemetry formatter/primitive contract.
 *
 * These tests are pure JVM (no Android, OpenCV, ONNX or Gradle): they compile the real
 * PerceptionPerformanceTelemetry.kt together with the real DiagnosticLog.kt and assert that
 * bounded, sanitized, fixed-schema payloads are produced from malformed and unbounded input,
 * that the backend vocabulary stays closed, and that repeated observations stay rate-bounded.
 */
class PerceptionPerformanceTelemetryTest {

    @Before
    fun reset() {
        DiagnosticLog.resetForTesting()
    }

    private fun linesFor(kind: String): List<String> =
        DiagnosticLog.recentLines(200).filter { it.contains("|E|$kind ") }

    // ---- backend vocabulary -------------------------------------------------------------

    @Test
    fun backendVocabularyIsClosedAndCaseInsensitive() {
        assertEquals(PerceptionPerformanceTelemetry.Backend.NNAPI, PerceptionPerformanceTelemetry.backendOf("NNAPI"))
        assertEquals(PerceptionPerformanceTelemetry.Backend.NNAPI, PerceptionPerformanceTelemetry.backendOf(" nnapi "))
        assertEquals(PerceptionPerformanceTelemetry.Backend.XNNPACK, PerceptionPerformanceTelemetry.backendOf("Xnnpack"))
        assertEquals(PerceptionPerformanceTelemetry.Backend.CPU, PerceptionPerformanceTelemetry.backendOf("cpu"))
    }

    @Test
    fun unexpectedBackendStringsNeverEchoTheirContent() {
        val hostile = listOf(
            null,
            "",
            "   ",
            "/data/user/0/com.pyla.ai/files/model.onnx",
            "Bearer sk-abcdefghijklmnop",
            "C:\\models\\entity.onnx",
            "gpu:0#\u0000\ninjected=true",
            "NNAPI_EXTRA",
            "X".repeat(10_000),
        )
        for (raw in hostile) {
            val detail = PerceptionPerformanceTelemetry.modelDetail("entity", PerceptionPerformanceTelemetry.backendOf(raw), 4)
            assertTrue("detail=$detail", detail.contains("backend=UNKNOWN"))
            assertFalse(detail, detail.contains("Bearer"))
            assertFalse(detail, detail.contains("sk-"))
            assertFalse(detail, detail.contains("/data"))
            assertFalse(detail, detail.contains("injected"))
            assertFalse(detail, detail.contains("\n"))
            assertTrue(detail.length < 128)
        }
    }

    // ---- roles and thread counts --------------------------------------------------------

    @Test
    fun rolesAreMappedOntoStableTokens() {
        assertEquals("entity", PerceptionPerformanceTelemetry.roleOf(" Entity "))
        assertEquals("tile", PerceptionPerformanceTelemetry.roleOf("tile"))
        assertEquals("tile_centered", PerceptionPerformanceTelemetry.roleOf("tile_centered"))
        assertEquals("other", PerceptionPerformanceTelemetry.roleOf(null))
        assertEquals("other", PerceptionPerformanceTelemetry.roleOf("../../secrets.env"))
        assertEquals("other", PerceptionPerformanceTelemetry.roleOf("enetity"))
        assertEquals("other", PerceptionPerformanceTelemetry.roleOf("role=entity backend=CPU"))
    }

    @Test
    fun threadCountsStayBoundedAndPositive() {
        assertEquals(1, PerceptionPerformanceTelemetry.threadsOf(0))
        assertEquals(1, PerceptionPerformanceTelemetry.threadsOf(-12))
        assertEquals(4, PerceptionPerformanceTelemetry.threadsOf(4))
        assertEquals(
            PerceptionPerformanceTelemetry.MAX_REPORTED_THREADS,
            PerceptionPerformanceTelemetry.threadsOf(Int.MAX_VALUE),
        )
        val detail = PerceptionPerformanceTelemetry.modelDetail("tile", PerceptionPerformanceTelemetry.Backend.XNNPACK, -7)
        assertEquals("role=tile backend=XNNPACK threads=1 build=perception-perf-v1", detail)
    }

    @Test
    fun buildTagIsFixedAndPresentInModelPayloads() {
        val detail = PerceptionPerformanceTelemetry.modelDetail("entity", PerceptionPerformanceTelemetry.Backend.CPU, 2)
        assertTrue(detail, detail.contains("build=perception-perf-v1"))
        assertEquals("perception-perf-v1", PerceptionPerformanceTelemetry.BUILD_TAG)
    }

    // ---- duration and counter bounds ----------------------------------------------------

    @Test
    fun durationsAreNonNegativeAndSaturated() {
        assertEquals(0L, PerceptionPerformanceTelemetry.nanosToMicros(-1L))
        assertEquals(0L, PerceptionPerformanceTelemetry.nanosToMicros(Long.MIN_VALUE))
        assertEquals(0L, PerceptionPerformanceTelemetry.nanosToMicros(0L))
        assertEquals(1L, PerceptionPerformanceTelemetry.nanosToMicros(1_999L))
        assertEquals(1_500L, PerceptionPerformanceTelemetry.nanosToMicros(1_500_000L))
        assertEquals(
            PerceptionPerformanceTelemetry.MAX_REPORTED_NANOS / 1_000L,
            PerceptionPerformanceTelemetry.nanosToMicros(Long.MAX_VALUE),
        )
    }

    @Test
    fun countersAreClampedIntoRange() {
        assertEquals(0, PerceptionPerformanceTelemetry.countOf(-5))
        assertEquals(0, PerceptionPerformanceTelemetry.countOf(Int.MIN_VALUE))
        assertEquals(42, PerceptionPerformanceTelemetry.countOf(42))
        assertEquals(
            PerceptionPerformanceTelemetry.MAX_REPORTED_COUNT,
            PerceptionPerformanceTelemetry.countOf(Int.MAX_VALUE),
        )
    }

    // ---- attack phase payload -----------------------------------------------------------

    @Test
    fun absentTraceRendersOnlyAnAbsenceToken() {
        assertEquals("attack_trace=absent", PerceptionPerformanceTelemetry.attackDetail(null))
    }

    @Test
    fun presentTraceRendersFixedSchemaWithMicrosecondPhaseCosts() {
        val detail = PerceptionPerformanceTelemetry.attackDetail(
            PerceptionPerformanceTelemetry.AttackPhases(
                opaqueNanos = 4_000_000L,
                translucentNanos = 2_500_000L,
                coarseNanos = 900_000_000L,
                refineNanos = 12_000L,
                coarseCandidates = 27,
                refinementSeeds = 3,
                exhausted = true,
            ),
        )
        assertEquals(
            "attack_trace=present attack_op_us=4000 attack_tl_us=2500 attack_coarse_us=900000 " +
                "attack_refine_us=12 attack_candidates=27 attack_seeds=3 attack_exhausted=true",
            detail,
        )
    }

    @Test
    fun malformedTraceValuesDegradeWithoutNegativeOrUnboundedOutput() {
        val detail = PerceptionPerformanceTelemetry.attackDetail(
            PerceptionPerformanceTelemetry.AttackPhases(
                opaqueNanos = -1L,
                translucentNanos = Long.MIN_VALUE,
                coarseNanos = Long.MAX_VALUE,
                refineNanos = 0L,
                coarseCandidates = -1,
                refinementSeeds = Int.MAX_VALUE,
                exhausted = false,
            ),
        )
        assertTrue(detail, detail.contains("attack_op_us=0 "))
        assertTrue(detail, detail.contains("attack_tl_us=0 "))
        assertTrue(detail, detail.contains("attack_coarse_us=600000000 "))
        assertTrue(detail, detail.contains("attack_refine_us=0 "))
        assertTrue(detail, detail.contains("attack_candidates=0 "))
        assertTrue(detail, detail.contains("attack_seeds=1000000 "))
        assertTrue(detail, detail.contains("attack_exhausted=false"))
        assertFalse("negative microseconds leaked: $detail", Regex("=-\\d").containsMatchIn(detail))
        assertFalse(detail.contains("NaN"))
        assertFalse(detail.contains("Infinity"))
    }

    @Test
    fun absentTraceAfterPresentOneDoesNotCarryStaleValues() {
        val present = PerceptionPerformanceTelemetry.attackDetail(
            PerceptionPerformanceTelemetry.AttackPhases(10L, 20L, 30L, 40L, 4, 2, false),
        )
        val absent = PerceptionPerformanceTelemetry.attackDetail(null)
        assertEquals("attack_trace=absent", absent)
        assertNotEquals(present, absent)
        assertFalse(absent.contains("attack_op_us"))
        assertFalse(absent.contains("attack_candidates"))
    }

    @Test
    fun frameModelShapeIncludesBuildAndBothConfiguredBackends() {
        val detail = PerceptionPerformanceTelemetry.frameModelsDetail("NNAPI", 4, "tile_centered", "CPU", 2)
        assertEquals("build=perception-perf-v1 entity_backend=NNAPI entity_threads=4 wall_role=tile_centered wall_backend=CPU wall_threads=2", detail)
        val absent = PerceptionPerformanceTelemetry.frameModelsDetail("CPU", 1, null, null, null)
        assertTrue(absent.contains("wall_role=none wall_backend=NONE wall_threads=0"))
        val malformed = PerceptionPerformanceTelemetry.frameModelsDetail("private/path", Int.MAX_VALUE, "secret-role", "Bearer private-value", Int.MIN_VALUE)
        assertTrue(malformed.contains("entity_backend=UNKNOWN entity_threads=256"))
        assertTrue(malformed.contains("wall_role=other wall_backend=UNKNOWN wall_threads=1"))
        assertFalse(malformed.contains("private"))
        assertFalse(malformed.contains("secret"))
    }

    @Test
    fun retainedFrameStillIdentifiesRuntimeAfterStartupRowsAreEvicted() {
        PerceptionPerformanceTelemetry.recordModel("entity", "NNAPI", 4)
        repeat(DiagnosticLog.MAX_ENTRIES + 10) { DiagnosticLog.event("owned.filler", "n=$it") }
        assertTrue("the actual startup model row must have left the bounded ring", linesFor("perception.model").isEmpty())
        val models = PerceptionPerformanceTelemetry.frameModelsDetail("NNAPI", 4, "tile", "XNNPACK", 4)
        DiagnosticLog.sample("gameplay.frame_timing", "total_ms=10 attack_trace=absent $models")
        val row = DiagnosticLog.recentLines(1).single()
        assertTrue(row, row.contains("gameplay.frame_timing"))
        assertTrue(row, row.contains("build=perception-perf-v1"))
        assertTrue(row, row.contains("entity_backend=NNAPI entity_threads=4"))
        assertTrue(row, row.contains("wall_role=tile wall_backend=XNNPACK wall_threads=4"))
    }

    // ---- emission / budget behaviour ----------------------------------------------------

    @Test
    fun modelEventsAreEmittedForEntityAndTileWithSanitizedPayloads() {
        PerceptionPerformanceTelemetry.recordModel("entity", "NNAPI", 4)
        PerceptionPerformanceTelemetry.recordModel("tile", "XNNPACK", 4)
        val lines = linesFor("perception.model")
        assertEquals(lines.joinToString("\n"), 2, lines.size)
        assertTrue(lines[0], lines[0].endsWith("perception.model role=entity backend=NNAPI threads=4 build=perception-perf-v1"))
        assertTrue(lines[1], lines[1].endsWith("perception.model role=tile backend=XNNPACK threads=4 build=perception-perf-v1"))
    }

    @Test
    fun fallbackEventOnlyFiresOnAnActualProviderChange() {
        PerceptionPerformanceTelemetry.recordFallback("entity", "NNAPI", "NNAPI", 4)
        assertTrue(linesFor("perception.model_fallback").isEmpty())

        PerceptionPerformanceTelemetry.recordFallback("entity", "NNAPI", "CPU", 4)
        val lines = linesFor("perception.model_fallback")
        assertEquals(lines.joinToString("\n"), 1, lines.size)
        assertTrue(
            lines[0],
            lines[0].endsWith("perception.model_fallback role=entity previous=NNAPI current=CPU threads=4 build=perception-perf-v1"),
        )
    }

    @Test
    fun repeatedAttackObservationsStayRateBounded() {
        val phases = PerceptionPerformanceTelemetry.AttackPhases(1_000_000L, 2_000_000L, 3_000_000L, 4_000_000L, 9, 3, false)
        repeat(25) { PerceptionPerformanceTelemetry.recordAttack(phases) }
        val lines = linesFor("combat.attack_phases")
        assertEquals(lines.joinToString("\n"), 1, lines.size)
        assertTrue(lines[0], lines[0].contains("attack_trace=present attack_op_us=1000"))
    }

    @Test
    fun buildTagEventIsEmittedAtMostOnce() {
        repeat(5) { PerceptionPerformanceTelemetry.recordBuild() }
        val lines = linesFor("perception.build")
        // The one-shot guard is process-wide, so either the first call emitted exactly one line or a
        // previous test in this JVM already consumed it. Never more than one.
        assertTrue(lines.joinToString("\n"), lines.size <= 1)
        for (line in lines) assertTrue(line, line.contains("build=perception-perf-v1 schema=1"))
    }

    @Test
    fun telemetryNeverRegistersSecretPatternsInTheRing() {
        PerceptionPerformanceTelemetry.recordModel(
            "Bearer sk-abcdefghijklmnop",
            "password=hunter2",
            Int.MIN_VALUE,
        )
        PerceptionPerformanceTelemetry.recordModel("entity", "/data/user/0/com.pyla.ai/db.sqlite", 4)
        val text = DiagnosticLog.snapshot().text
        assertFalse(text, text.contains("hunter2"))
        assertFalse(text, text.contains("sk-abcdefghijklmnop"))
        assertFalse(text, text.contains("/data/user"))
        // Hostile inputs are mapped onto the closed vocabulary, so only generic tokens remain.
        assertTrue(text, text.contains("role=other backend=UNKNOWN threads=1"))
        assertTrue(text, text.contains("role=entity backend=UNKNOWN threads=4"))
    }
}
