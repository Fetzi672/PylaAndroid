package com.pyla.ai.diagnostics

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WorkflowDiagnosticLogTest {
    @Before fun reset() = DiagnosticLog.resetForTesting()

    @Test fun failureRetainsStageReasonAndTypeButNotThrowableContents() {
        DiagnosticLog.failure("selection", "grid_scan", "ocr_exception",
            IllegalStateException("private-ocr-text", IllegalArgumentException("private-token-value")))
        val text = DiagnosticLog.snapshot().text
        assertTrue(text.contains("E|failure.selection phase=grid_scan reason=ocr_exception"))
        assertTrue(text.contains("exception=IllegalStateException <- IllegalArgumentException"))
        assertFalse(text.contains("private-ocr-text"))
        assertFalse(text.contains("private-token-value"))
    }

    @Test fun repeatedTerminalOutcomesAreNeverFolded() {
        repeat(3) { DiagnosticLog.terminal("selection", "verify_grid", "stuck", "grid_phase_timeout") }
        val lines = DiagnosticLog.recentLines()
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.contains("E|terminal.selection") })
    }

    @Test fun repeatedFailureIsBoundedAndDistinctReasonIsRetained() {
        repeat(1000) { DiagnosticLog.failure("capture", "frame", "missing") }
        DiagnosticLog.failure("capture", "frame", "stale")
        assertEquals(2, DiagnosticLog.recentLines().size)
    }

    @Test fun snapshotDisclosesDiagnosticRevisionAndEvictedAndFoldedEntries() {
        repeat(20) { DiagnosticLog.failure("capture", "frame", "missing") }
        repeat(3000) { DiagnosticLog.terminal("selection", "verify_grid", "stuck", "reason_$it") }
        val snapshot = DiagnosticLog.snapshot()
        assertTrue(snapshot.text.contains("# diagnostic_revision=flow_logs_v1\n"))
        val headers = snapshot.text.lineSequence().filter { it.startsWith("# ") }.toList()
        val dropped = headers.single { it.startsWith("# dropped_entries=") }.substringAfter('=').toLong()
        val folded = headers.single { it.startsWith("# folded_entries=") }.substringAfter('=').toLong()
        assertTrue(dropped > 0)
        assertEquals(19L, folded)
        val rows = snapshot.text.lineSequence().filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        val count = headers.single { it.startsWith("# entries=") }.substringAfter('=').toInt()
        assertEquals(rows.size, count)
        assertTrue(snapshot.byteCount <= DiagnosticLog.MAX_BYTES)
        val sequences = rows.map { it.substringBefore('|').toLong() }
        assertEquals(sequences.sorted(), sequences)
        assertEquals(sequences.size, sequences.toSet().size)
    }

    @Test fun failureFieldsAreSanitizedAndHugeFieldsRemainBounded() {
        val secret = "synthetic-hidden-value"
        DiagnosticLog.failure("token=$secret", "password=$secret", "Authorization: Bearer $secret",
            RuntimeException(secret))
        DiagnosticLog.terminal("x".repeat(100_000), "y".repeat(100_000), "z".repeat(100_000), "r".repeat(100_000))
        val snapshot = DiagnosticLog.snapshot()
        assertFalse(snapshot.text.contains(secret))
        assertTrue(snapshot.byteCount <= DiagnosticLog.MAX_BYTES)
    }

    @Test fun countersResetAndFrozenSnapshotsDoNotChange() {
        repeat(3) { DiagnosticLog.failure("capture", "frame", "missing") }
        val prior = DiagnosticLog.snapshot()
        assertTrue(prior.text.contains("# folded_entries=2\n"))
        DiagnosticLog.resetForTesting()
        assertTrue(DiagnosticLog.snapshot().text.contains("# folded_entries=0\n"))
        assertTrue(prior.text.contains("# folded_entries=2\n"))
    }
}
