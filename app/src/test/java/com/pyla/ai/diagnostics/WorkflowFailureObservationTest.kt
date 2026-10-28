package com.pyla.ai.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests of the DiagnosticLog.failure/terminal *observation contract* only.
 *
 * They exercise the real production source (no Android framework needed) and are NOT device
 * runtime, gameplay, OCR or game-effect evidence.
 */
class WorkflowFailureObservationTest {

    @Before
    fun setUp() {
        DiagnosticLog.resetForTesting()
    }

    private fun lines(): List<String> = DiagnosticLog.recentLines(limit = 500)

    @Test
    fun `failure keeps fixed kind phase reason and exception types but never messages`() {
        val secret = "token=abcd1234 password=hunter2"
        DiagnosticLog.failure(
            "capture", "start", "start_threw",
            IllegalStateException(secret, RuntimeException(secret)),
        )
        val line = lines().last()
        assertTrue(line, line.contains("E|failure.capture"))
        assertTrue(line, line.contains("phase=start"))
        assertTrue(line, line.contains("reason=start_threw"))
        assertTrue(line, line.contains("IllegalStateException <- RuntimeException"))
        assertFalse("throwable message must never be retained", line.contains("hunter2") || line.contains("abcd1234"))
    }

    @Test
    fun `identical failures fold inside the window while a distinct reason still emits`() {
        repeat(3) { DiagnosticLog.failure("engine", "main_loop", "unhandled") }
        assertEquals("identical failures must fold to one line", 1, lines().size)
        DiagnosticLog.failure("engine", "main_loop", "push_targets_reached")
        assertEquals(2, lines().size)
        assertTrue(lines().last().contains("reason=push_targets_reached"))
    }

    @Test
    fun `terminal never folds so every completion stays visible`() {
        DiagnosticLog.terminal("engine", "stop", "requested", "run_for_minutes")
        DiagnosticLog.terminal("engine", "stop", "requested", "run_for_minutes")
        assertEquals(2, lines().size)
    }

    @Test
    fun `failure text stays single line and bounded`() {
        DiagnosticLog.failure("prompt", "phase\nwith\nnewlines", "x".repeat(5_000))
        val line = lines().last()
        assertFalse("newlines must be folded out", line.contains("\n"))
        assertTrue("reason must be truncated", line.length < 500)
    }
}
