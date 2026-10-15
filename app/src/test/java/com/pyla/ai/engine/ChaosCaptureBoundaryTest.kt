package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test

/** Actual gate behavior, including cached pre-lifecycle captures; not a native input test. */
class ChaosCaptureBoundaryTest {
    @Test fun cachedPrePauseFaceCannotRearmUnderAResumedGeneration() {
        val gate = ChaosContinuationGate()
        gate.invalidate(2000)
        gate.invalidate(2200)
        val g = gate.observationGeneration()
        assertFalse(gate.arm(g, 2300, 1, capturedAtMs = 1900))
        assertFalse(gate.continues(g, 2400, 1))
        assertTrue(gate.arm(g, 2500, 1, capturedAtMs = 2300))
        assertTrue(gate.continues(g, 2600, 1))
    }
    @Test fun captureAtTheRevocationMillisecondIsConservativelyDenied() {
        val gate = ChaosContinuationGate()
        gate.invalidate(2000)
        assertFalse(gate.arm(gate.observationGeneration(), 2001, 1, 2000))
        assertTrue(gate.arm(gate.observationGeneration(), 2002, 1, 2001))
    }
    @Test fun futureOrExpiredFaceCapturesCannotArm() {
        val gate = ChaosContinuationGate()
        val g = gate.observationGeneration()
        assertFalse(gate.arm(g, 5000, 1, 5001))
        assertFalse(gate.arm(g, 5000, 1, 1999))
        assertFalse(gate.arm(g, 5000, 1, 0))
        assertTrue(gate.arm(g, 5000, 1, 2000))
    }
    @Test fun readingTheSameCachedFaceDoesNotRenewItsTtl() {
        val gate = ChaosContinuationGate()
        val g = gate.observationGeneration()
        assertTrue(gate.arm(g, 1000, 1, 1000))
        assertTrue(gate.arm(g, 3000, 1, 1000))
        assertFalse(gate.continues(g, 6001, 1, 6001))
    }
    @Test fun olderFacesCannotRollBackNewerEvidence() {
        val gate = ChaosContinuationGate()
        val g = gate.observationGeneration()
        assertTrue(gate.arm(g, 2000, 1, 2000))
        assertFalse(gate.arm(g, 2200, 1, 1000))
        assertTrue(gate.continues(g, 7000, 1))
    }
    @Test fun oldMenuCompletionCannotRevokeTheNewGeneration() {
        val gate = ChaosContinuationGate()
        val old = gate.observationGeneration()
        gate.invalidate(2000)
        val current = gate.observationGeneration()
        assertTrue(gate.arm(current, 3000, 1))
        gate.invalidate(4000, expectedGeneration = old)
        assertEquals(current, gate.observationGeneration())
        assertTrue(gate.continues(current, 4100, 1))
    }
    @Test fun staleOrFutureOrOutOfOrderCoinsNeverContinue() {
        val gate = ChaosContinuationGate()
        val g = gate.observationGeneration()
        assertTrue(gate.arm(g, 4000, 1, 4000))
        assertFalse(gate.continues(g, 5000, 1, 5001))
        assertFalse(gate.continues(g, 8000, 1, 4999))
        assertFalse(gate.continues(g, 5000, 1, 3999))
        assertTrue(gate.continues(g, 5000, 1, 4500))
    }
}
