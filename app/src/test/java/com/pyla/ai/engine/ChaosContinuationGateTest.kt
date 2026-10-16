package com.pyla.ai.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral, deterministic JVM regression tests for the pure [ChaosContinuationGate].
 *
 * The gate owns the process-global "Chaosdrop face -> reward-coins dismissal" continuation: the
 * 5 s TTL `star_drop_regular` admission used by `StateFinder.classifyState` ->
 * `StageManager.clickStarDrop`. These tests exercise the REAL production gate only, with no
 * Android/OpenCV/emulator/device dependency; they are NOT live-gameplay/native evidence.
 *
 * Coverage: TTL/geometry/freshness boundaries, positive control, and — the Codex P2 finding —
 * lifecycle revocation. A Pause, a Stop or a session replacement must invalidate the evidence, and
 * recognition already in flight when that invalidation lands must not be able to re-arm it.
 */
class ChaosContinuationGateTest {

    /** Geometry identity stand-in for `PhoneVisualCues.size(image)`: (cols << 32) | rows. */
    private fun geom(w: Int, h: Int): Long = (w.toLong() shl 32) or h.toLong()

    // -------------------------------------------------------------------------------------------
    // Positive control
    // -------------------------------------------------------------------------------------------

    @Test
    fun faceThenCoinsWithinTtlOnSameGenerationContinues() {
        val gate = ChaosContinuationGate()
        val g = gate.observationGeneration()
        assertTrue("a positive face observation arms the continuation", gate.arm(g, 1_000L, geom(1920, 1080)))
        assertTrue("coins within TTL, same generation and geometry continue", gate.continues(g, 3_000L, geom(1920, 1080)))
    }

    @Test
    fun coinsAtExactTtlBoundaryContinues() {
        val gate = ChaosContinuationGate()
        val g = gate.observationGeneration()
        assertTrue(gate.arm(g, 1_000L, geom(1920, 1080)))
        assertTrue("exactly 5000 ms old is still admitted", gate.continues(g, 6_000L, geom(1920, 1080)))
    }

    @Test
    fun coinsAfterTtlDenied() {
        val gate = ChaosContinuationGate()
        val g = gate.observationGeneration()
        assertTrue(gate.arm(g, 1_000L, geom(1920, 1080)))
        assertFalse("5001 ms old is expired", gate.continues(g, 6_001L, geom(1920, 1080)))
    }

    @Test
    fun coinsBeforeObservationDenied() {
        val gate = ChaosContinuationGate()
        val g = gate.observationGeneration()
        assertTrue(gate.arm(g, 1_000L, geom(1920, 1080)))
        assertFalse("a clock regression must not be treated as fresh", gate.continues(g, 999L, geom(1920, 1080)))
    }

    @Test
    fun geometryChangeDenied() {
        val gate = ChaosContinuationGate()
        val g = gate.observationGeneration()
        assertTrue(gate.arm(g, 1_000L, geom(1920, 1080)))
        assertFalse("a different capture geometry must not continue", gate.continues(g, 2_000L, geom(1080, 1920)))
    }

    @Test
    fun coinsWithoutFaceEvidenceDenied() {
        val gate = ChaosContinuationGate()
        val g = gate.observationGeneration()
        assertFalse("no face evidence means no coins continuation", gate.continues(g, 2_000L, geom(1920, 1080)))
    }

    // -------------------------------------------------------------------------------------------
    // Lifecycle revocation within the TTL: Pause -> Resume
    // -------------------------------------------------------------------------------------------

    /**
     * The finding's exact scenario: the face is observed, the bot is paused and resumed, and a
     * recognition that was already in flight when the pause landed re-arms the global evidence.
     * The coins frame arriving on the resumed session inside the TTL must NOT be admitted.
     */
    @Test
    fun inFlightFaceObservationLandedDuringPauseCannotAuthoriseCoins() {
        val gate = ChaosContinuationGate()
        val observedGeneration = gate.observationGeneration() // captured BEFORE the face frame capture

        gate.invalidate() // Pause while the face recognition is still in flight
        assertFalse(
            "an in-flight face observation must not re-arm the continuation after a Pause",
            gate.arm(observedGeneration, 1_500L, geom(1920, 1080)),
        )
        gate.invalidate() // Resume

        val coinsGeneration = gate.observationGeneration()
        assertFalse(
            "the coins frame on the resumed session must not consume paused evidence",
            gate.continues(coinsGeneration, 3_000L, geom(1920, 1080)),
        )
    }

    @Test
    fun evidenceObservedBeforePauseIsNotReusableAfterResume() {
        val gate = ChaosContinuationGate()
        val observedGeneration = gate.observationGeneration()
        assertTrue(gate.arm(observedGeneration, 1_000L, geom(1920, 1080))) // face observed while live
        gate.invalidate() // Pause
        gate.invalidate() // Resume
        assertFalse(
            "evidence armed before a Pause->Resume must not be reused",
            gate.continues(observedGeneration, 2_000L, geom(1920, 1080)),
        )
    }

    // -------------------------------------------------------------------------------------------
    // Lifecycle revocation within the TTL: Stop / session replacement
    // -------------------------------------------------------------------------------------------

    @Test
    fun stopThenInFlightFaceCannotAuthoriseCoins() {
        val gate = ChaosContinuationGate()
        val observedGeneration = gate.observationGeneration()
        gate.invalidate() // Stop
        assertFalse(
            "in-flight recognition after a Stop must not re-arm",
            gate.arm(observedGeneration, 1_200L, geom(1920, 1080)),
        )
        assertFalse(gate.continues(observedGeneration, 2_000L, geom(1920, 1080)))
    }

    @Test
    fun oldSessionFaceObservationCannotRearmUnderNewSession() {
        val gate = ChaosContinuationGate()
        val oldSessionGeneration = gate.observationGeneration()
        gate.invalidate() // Stop of the old session
        gate.invalidate() // Session replacement/start of the new session
        assertFalse(
            "an old-session in-flight face observation must not arm under the new session",
            gate.arm(oldSessionGeneration, 1_500L, geom(1920, 1080)),
        )
        val newSessionGeneration = gate.observationGeneration()
        assertFalse(
            "the new session must not inherit the old session's evidence",
            gate.continues(newSessionGeneration, 2_000L, geom(1920, 1080)),
        )
    }

    // -------------------------------------------------------------------------------------------
    // Recovery: advancing the generation must never permanently poison a fresh observation
    // -------------------------------------------------------------------------------------------

    @Test
    fun freshObservationAfterInvalidationRecovers() {
        val gate = ChaosContinuationGate()
        gate.invalidate()
        val g = gate.observationGeneration()
        assertTrue("a genuinely new observation after invalidation arms normally", gate.arm(g, 5_000L, geom(1920, 1080)))
        assertTrue("and its own coins continuation is admitted", gate.continues(g, 6_000L, geom(1920, 1080)))
    }

    @Test
    fun generationAdvancesMonotonicallyAcrossInvalidations() {
        val gate = ChaosContinuationGate()
        val g0 = gate.observationGeneration()
        gate.invalidate()
        val g1 = gate.observationGeneration()
        gate.invalidate()
        val g2 = gate.observationGeneration()
        assertTrue("generation must advance on each invalidation", g1 > g0)
        assertTrue(g2 > g1)
    }
}
