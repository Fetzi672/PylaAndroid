package com.pyla.ai.engine

import com.pyla.ai.diagnostics.DiagnosticLog
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test


/** Actual WindowController rejection methods; no Android dispatch or screenshot is executed. */
class WindowFailureDiagnosticsTest {
    @Before fun reset() = DiagnosticLog.resetForTesting()
    private fun window(): WindowController {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val f = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val wc = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(f.get(null), WindowController::class.java) as WindowController
        WindowController::class.java.getDeclaredField("width").apply { isAccessible = true }.setInt(wc, 1280)
        WindowController::class.java.getDeclaredField("height").apply { isAccessible = true }.setInt(wc, 720)
        return wc
    }
    @Test fun invalidCoordinatesRejectBeforeGuardAndRecordActualReason() {
        var calls = 0
        window().pressCombatAt(Float.NaN, 10f) { calls++; true }
        assertEquals(0, calls)
        assertTrue(DiagnosticLog.snapshot().text.contains("phase=combat_tap reason=invalid_coordinates"))
    }
    @Test fun deniedGuardIsEvaluatedOnlyOnceAndRecordsDenial() {
        var calls = 0
        window().pressCombatAt(10f, 10f) { calls++; false }
        assertEquals(1, calls)
        assertTrue(DiagnosticLog.snapshot().text.contains("phase=combat_tap reason=authority_denied"))
    }
    @Test fun throwingGuardPreservesOriginalThrowableAndIsNotRelabelledAsFalse() {
        val original = IllegalStateException("private-guard-content")
        try { window().pressCombatAt(10f, 10f) { throw original }; fail("expected original exception") }
        catch (t: IllegalStateException) { assertSame(original, t) }
        assertFalse(DiagnosticLog.snapshot().text.contains("authority_denied"))
        assertFalse(DiagnosticLog.snapshot().text.contains("private-guard-content"))
    }
    @Test fun heldCombatInvalidGeometryDoesNotEvaluateAuthority() {
        var calls = 0
        window().holdCombatAt(-1f, 10f) { calls++; true }
        assertEquals(0, calls)
        assertTrue(DiagnosticLog.snapshot().text.contains("phase=combat_hold reason=invalid_coordinates"))
    }
}
