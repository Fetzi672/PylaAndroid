package com.pyla.ai.pyla

import com.pyla.ai.diagnostics.DiagnosticLog
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real interpreter failures, not an inferred reconstruction of its statuses. */
class PlaystyleFailureDiagnosticsTest {
    @Before fun reset() { DiagnosticLog.resetForTesting(); Thread.interrupted() }
    private fun execute(body: String, context: Map<String, Any> = emptyMap(),
                        budget: PyExecutionBudget = PyExecutionBudget(clock = { 0L })): PylaRunResult =
        PylaScript.parseContent("private_test.pyla", body).runBoundedWith(context, budget)
    private fun requiresReason(reason: String) {
        assertTrue(DiagnosticLog.snapshot().text.contains("E|failure.playstyle phase=execute reason=$reason"))
    }

    @Test fun scriptRaiseIsIdentifiedWithoutLeakingItsValue() {
        val result = execute("raise ValueError('private-script-message')\n")
        assertEquals(PylaRunStatus.RUNTIME_ERROR, result.status)
        assertNull(result.movement)
        requiresReason("script_raise")
        assertFalse(DiagnosticLog.snapshot().text.contains("private-script-message"))
    }
    @Test fun missingVariableIsRuntimeErrorAndStillProducesNoMovement() {
        val result = execute("movement = missing_variable\n")
        assertEquals(PylaRunStatus.RUNTIME_ERROR, result.status)
        assertNull(result.movement)
        requiresReason("runtime_error")
    }
    @Test fun callbackFailureIsDistinctAndSecretSafe() {
        val result = execute("explode()\n", mapOf("explode" to NativeFn("explode") { _, _ ->
            throw SecurityException("private-native-message")
        }))
        assertEquals(PylaRunStatus.RUNTIME_ERROR, result.status)
        requiresReason("callback_exception")
        assertTrue(DiagnosticLog.snapshot().text.contains("exception=SecurityException"))
        assertFalse(DiagnosticLog.snapshot().text.contains("private-native-message"))
    }
    @Test fun budgetAndCancellationHaveDistinctReasonsAndUnchangedStatuses() {
        assertEquals(PylaRunStatus.BUDGET_EXCEEDED,
            execute("while True:\n    pass\n", budget = PyExecutionBudget(maxSteps = 5, clock = { 0L })).status)
        requiresReason("budget_steps")
        assertEquals(PylaRunStatus.CANCELLED,
            execute("movement = (1, 2)\n", budget = PyExecutionBudget(cancelled = { true }, clock = { 0L })).status)
        requiresReason("budget_cancelled")
    }
    @Test fun successfulExecutionAddsNoFailureAndKeepsMovement() {
        val result = execute("movement = (1, 2)\n")
        assertEquals(PylaRunStatus.SUCCESS, result.status)
        assertNotNull(result.movement)
        assertFalse(DiagnosticLog.snapshot().text.contains("E|failure.playstyle"))
    }
}
