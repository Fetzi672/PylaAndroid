package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test

/** Diagnostic labels describe the existing decision; they grant no authority. */
class AttackAdmissionDiagnosticsTest {
    private val point = AttackButtonPoint(200f, 150f, 40f)
    private val gate = AttackButtonEvidenceGate(3000L)

    @Test fun missingAndInvalidAreDistinct() {
        assertNull(gate.observe(null, 100, 100, 1))
        assertEquals(AttackButtonEvidenceGate.Observation.MISSING_POINT, gate.lastObservation())
        assertNull(gate.observe(point.copy(x = Float.NaN), 100, 100, 1))
        assertEquals(AttackButtonEvidenceGate.Observation.INVALID_POINT, gate.lastObservation())
    }
    @Test fun staleFutureAndNonpositiveFramesRemainDenied() {
        assertNull(gate.observe(point, 1, 4000, 1))
        assertEquals(AttackButtonEvidenceGate.Observation.FRAME_STALE, gate.lastObservation())
        assertNull(gate.observe(point, 200, 100, 1))
        assertEquals(AttackButtonEvidenceGate.Observation.INVALID_CAPTURE_TIME, gate.lastObservation())
        assertNull(gate.observe(point, 0, 100, 1))
        assertEquals(AttackButtonEvidenceGate.Observation.INVALID_CAPTURE_TIME, gate.lastObservation())
    }
    @Test fun twoDistinctFreshFramesAreStillRequired() {
        assertNull(gate.observe(point, 100, 100, 1))
        assertEquals(AttackButtonEvidenceGate.Observation.WAITING_SECOND_OBSERVATION, gate.lastObservation())
        assertNull(gate.observe(point, 100, 101, 1))
        assertEquals(AttackButtonEvidenceGate.Observation.REPEATED_FRAME, gate.lastObservation())
        assertNotNull(gate.observe(point, 102, 102, 1))
        assertEquals(AttackButtonEvidenceGate.Observation.CONFIRMED, gate.lastObservation())
    }
    @Test fun changedGeometryAndExpiredPriorRequireReconfirmation() {
        gate.observe(point, 100, 100, 1)
        assertNull(gate.observe(point, 101, 101, 2))
        assertEquals(AttackButtonEvidenceGate.Observation.GEOMETRY_CHANGED, gate.lastObservation())
        assertNull(gate.observe(point, 4000, 4000, 2))
        assertEquals(AttackButtonEvidenceGate.Observation.PRIOR_EXPIRED, gate.lastObservation())
    }
    @Test fun centerAndRadiusInstabilityAreDistinctAndRemainDenied() {
        gate.observe(point, 100, 100, 1)
        assertNull(gate.observe(point.copy(x = 210f), 101, 101, 1))
        assertEquals(AttackButtonEvidenceGate.Observation.CENTER_CHANGED, gate.lastObservation())
        gate.reset()
        gate.observe(point, 100, 100, 1)
        assertNull(gate.observe(point.copy(radius = 60f), 101, 101, 1))
        assertEquals(AttackButtonEvidenceGate.Observation.RADIUS_CHANGED, gate.lastObservation())
    }
    @Test fun olderFramesAndResetNeverKeepAuthority() {
        gate.observe(point, 100, 100, 1)
        gate.observe(point, 102, 102, 1)
        assertNull(gate.observe(point, 101, 103, 1))
        assertEquals(AttackButtonEvidenceGate.Observation.OUT_OF_ORDER, gate.lastObservation())
        assertNull(gate.current(103, 1))
        gate.reset()
        assertEquals(AttackButtonEvidenceGate.Observation.RESET, gate.lastObservation())
    }
    @Test fun inspectingDiagnosticsCannotRefreshOrChangeEvidence() {
        gate.observe(point, 100, 100, 1)
        val evidence = gate.observe(point, 101, 101, 1)
        repeat(100) { assertEquals(AttackButtonEvidenceGate.Observation.CONFIRMED, gate.lastObservation()) }
        assertEquals(evidence, gate.current(3101, 1))
        assertNull(gate.current(3102, 1))
        assertNull(gate.current(101, 2))
    }
}
