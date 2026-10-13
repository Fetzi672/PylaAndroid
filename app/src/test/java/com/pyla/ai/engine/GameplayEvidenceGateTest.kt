package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Policy tests for the gameplay evidence gate.
 *
 * The gate is the only thing that authorizes gameplay input, so it is tested as a total function of
 * its arguments: a classifier answer, the number of player boxes, whether that box is valid and the
 * capture timestamp of the frame both sides refer to.
 */
class GameplayEvidenceGateTest {

    private val maxAge = 3_000L

    private fun admit(
        gate: GameplayEvidenceGate,
        capturedAtMs: Long,
        nowMs: Long = capturedAtMs,
        statePermitted: Boolean = true,
        playerBoxes: Int = 1,
        boxValid: Boolean = true,
    ) = gate.observe(statePermitted, playerBoxes, boxValid, capturedAtMs, nowMs)

    // ---------------- two distinct fresh observations ----------------

    @Test
    fun firstObservationAloneIsNeverAdmission() {
        val g = GameplayEvidenceGate(maxAge)
        val d = admit(g, 1_000L, nowMs = 1_000L)
        assertFalse(d.admitted)
        assertEquals(GameEvidenceReason.AWAITING_SECOND_OBSERVATION, d.reason)
        assertEquals(1, d.observations)
        assertEquals(1, d.needsFreshFrames)
    }

    @Test
    fun twoDistinctFreshPlayerFramesAdmit() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_000L, 1_000L).admitted)
        val d = admit(g, 1_250L, 1_250L)
        assertTrue(d.admitted)
        assertEquals(GameEvidenceReason.ADMITTED, d.reason)
        assertEquals(2, d.observations)
        assertEquals(0, d.needsFreshFrames)
    }

    @Test
    fun repeatingTheSameCaptureTimestampIsNotASecondObservation() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_000L, 1_000L).admitted)
        val d = admit(g, 1_000L, 1_000L)
        assertFalse(d.admitted)
        assertEquals(GameEvidenceReason.SAME_FRAME_REPEATED, d.reason)
        assertEquals(1, d.observations)
        // The pair is still completable with a genuinely new frame.
        assertTrue(admit(g, 1_100L, 1_100L).admitted)
    }

    @Test
    fun anOlderTimestampAfterAPartialPairDoesNotCountAsNewerEvidence() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_500L, 1_500L).admitted)
        val back = admit(g, 1_400L, 1_500L)
        assertFalse(back.admitted)
        assertEquals(GameEvidenceReason.SAME_FRAME_REPEATED, back.reason)
        assertTrue(admit(g, 1_600L, 1_600L).admitted)
    }

    @Test
    fun admissionIsRetainedForEveryLaterFreshFrame() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 10_000L, 10_000L).admitted)
        assertTrue(admit(g, 10_250L, 10_250L).admitted)
        // Rolling gameplay frames must not have to re-prove the pair every frame.
        var nowMs = 10_250L
        for (i in 3..40) {
            nowMs += 250L
            val d = admit(g, nowMs, nowMs)
            assertTrue("frame $i must stay admitted", d.admitted)
            assertEquals(GameEvidenceReason.ADMITTED, d.reason)
        }
    }

    @Test
    fun resetRequiresANewPair() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_000L, 1_000L).admitted)
        assertTrue(admit(g, 1_250L, 1_250L).admitted)
        g.reset()
        assertFalse(admit(g, 1_500L, 1_500L).admitted)
        assertEquals(GameEvidenceReason.ADMITTED, admit(g, 1_750L, 1_750L).reason)
        assertTrue(admit(g, 2_000L, 2_000L).admitted)
    }

    // ---------------- freshness ----------------

    @Test
    fun staleCaptureIsNotEvidence() {
        val g = GameplayEvidenceGate(maxAge)
        val d = admit(g, 1_000L, nowMs = 1_000L + maxAge + 1)
        assertFalse(d.admitted)
        assertEquals(GameEvidenceReason.STALE_FRAME, d.reason)
    }

    @Test
    fun aFrameThatGoesStaleInvalidatesTheAccumulatedPair() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_000L, 1_000L).admitted)
        val d = admit(g, 1_250L, nowMs = 1_250L + maxAge + 1)
        assertFalse(d.admitted)
        assertEquals(GameEvidenceReason.STALE_FRAME, d.reason)
        assertEquals(0, d.observations)
    }

    @Test
    fun futureCaptureTimestampIsNotEvidence() {
        val g = GameplayEvidenceGate(maxAge)
        val d = admit(g, 2_000L, nowMs = 1_000L)
        assertFalse(d.admitted)
        assertEquals(GameEvidenceReason.FUTURE_FRAME, d.reason)
    }

    @Test
    fun missingOrAbsentCaptureTimestampIsNotEvidence() {
        for (bad in listOf(0L, -1L, -12345L)) {
            val g = GameplayEvidenceGate(maxAge)
            val d = admit(g, bad, nowMs = 5_000L)
            assertFalse("$bad must not be evidence", d.admitted)
            assertEquals(GameEvidenceReason.STALE_FRAME, d.reason)
        }
    }

    @Test
    fun ageExactlyAtTheLimitIsStillAcceptable() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_000L, 1_000L).admitted)
        assertTrue(admit(g, 2_000L, nowMs = 1_000L + maxAge).admitted)
    }

    @Test fun previousObservationMustStillBeFreshWhenSecondArrives() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1000L, 1000L).admitted)
        assertFalse(admit(g, 2000L, 5000L).admitted)
        assertEquals(1, g.observationCount)
        assertFalse(admit(g, 5100L, 5100L).admitted)
        assertTrue(admit(g, 5200L, 5200L).admitted)
    }

    // ---------------- player evidence ----------------

    @Test
    fun missingPlayerIsNeverEvidenceAndInvalidatesAPair() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_000L, 1_000L).admitted)
        val d = admit(g, 1_250L, 1_250L, playerBoxes = 0)
        assertFalse(d.admitted)
        assertEquals(GameEvidenceReason.PLAYER_MISSING, d.reason)
        assertEquals(2, d.needsFreshFrames)
    }

    @Test
    fun ambiguousPlayerDetectionIsNeverEvidenceAndInvalidatesAPair() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_000L, 1_000L).admitted)
        val d = admit(g, 1_250L, 1_250L, playerBoxes = 2)
        assertFalse(d.admitted)
        assertEquals(GameEvidenceReason.PLAYER_AMBIGUOUS, d.reason)
    }

    @Test
    fun invalidPlayerBoxIsNeverEvidenceAndInvalidatesAPair() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_000L, 1_000L).admitted)
        val d = admit(g, 1_250L, 1_250L, boxValid = false)
        assertFalse(d.admitted)
        assertEquals(GameEvidenceReason.PLAYER_BOX_INVALID, d.reason)
    }

    @Test
    fun aSingleBadFrameAfterAdmissionRevokesUntilAFreshPairIsBuilt() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_000L, 1_000L).admitted)
        assertTrue(admit(g, 1_250L, 1_250L).admitted)
        val gap = admit(g, 1_500L, 1_500L, playerBoxes = 0)
        assertFalse(gap.admitted)
        assertEquals(GameEvidenceReason.PLAYER_MISSING, gap.reason)
        assertFalse("one good frame after the gap is not enough", admit(g, 1_750L, 1_750L).admitted)
        assertTrue(admit(g, 2_000L, 2_000L).admitted)
    }

    // ---------------- menu screens ----------------

    @Test
    fun knownMenuScreenRevokesImmediatelyAndClearsThePair() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_000L, 1_000L).admitted)
        val menu = g.observeMenuScreen()
        assertFalse(menu.admitted)
        assertEquals(GameEvidenceReason.STATE_MENU_REVOKED, menu.reason)
        assertEquals(0, menu.observations)
        assertFalse("menu must not be laundered into admission", admit(g, 1_250L, 1_250L).admitted)
    }

    @Test
    fun menuRevocationOverridesPerfectPlayerAndFrameEvidence() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_000L, 1_000L).admitted)
        assertTrue(admit(g, 1_250L, 1_250L).admitted)
        val d = admit(g, 1_500L, 1_500L, statePermitted = false)
        assertFalse(d.admitted)
        assertEquals(GameEvidenceReason.STATE_MENU_REVOKED, d.reason)
    }

    @Test
    fun returningFromAMenuNeedsTwoFreshObservationsAgain() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_000L, 1_000L).admitted)
        assertTrue(admit(g, 1_250L, 1_250L).admitted)
        assertFalse(admit(g, 2_000L, 2_000L, statePermitted = false).admitted)
        val first = admit(g, 2_250L, 2_250L)
        assertFalse(first.admitted)
        assertEquals(GameEvidenceReason.AWAITING_SECOND_OBSERVATION, first.reason)
        assertTrue(admit(g, 2_500L, 2_500L).admitted)
    }

    @Test
    fun aClassifierMatchAloneNeverAdmitsWithoutPositivePlayerEvidence() {
        val g = GameplayEvidenceGate(maxAge)
        assertFalse(admit(g, 1_000L, 1_000L, playerBoxes = 0).admitted)
        assertFalse(admit(g, 1_250L, 1_250L, playerBoxes = 0).admitted)
        assertFalse(g.observationCount > 0)
    }

    // ---------------- box geometry ----------------

    @Test
    fun fullyInsideFrameBoxesAreValid() {
        val box = floatArrayOf(100f, 200f, 300f, 400f)
        assertTrue(GameplayEvidenceGate.validPlayerBox(box, 1920, 1080))
        assertTrue(GameplayEvidenceGate.validPlayerBox(floatArrayOf(0f, 0f, 1920f, 1080f), 1920, 1080))
        assertTrue(GameplayEvidenceGate.validPlayerBox(floatArrayOf(1919.5f, 0f, 1920f, 1080f), 1920, 1080))
    }

    @Test
    fun boxesPartlyOutsideTheFrameAreInvalid() {
        val w = 1920; val h = 1080
        assertFalse(GameplayEvidenceGate.validPlayerBox(floatArrayOf(-1f, 200f, 300f, 400f), w, h))
        assertFalse(GameplayEvidenceGate.validPlayerBox(floatArrayOf(100f, -0.5f, 300f, 400f), w, h))
        assertFalse(GameplayEvidenceGate.validPlayerBox(floatArrayOf(100f, 200f, 1920.5f, 400f), w, h))
        assertFalse(GameplayEvidenceGate.validPlayerBox(floatArrayOf(100f, 200f, 300f, 1080.5f), w, h))
        // A box that straddles a frame edge is not evidence of a fully visible player.
        assertFalse(GameplayEvidenceGate.validPlayerBox(floatArrayOf(1800f, 200f, 2400f, 400f), w, h))
    }

    @Test
    fun degenerateOrNonFiniteBoxesAreInvalid() {
        assertFalse(GameplayEvidenceGate.validPlayerBox(null, 1920, 1080))
        assertFalse(GameplayEvidenceGate.validPlayerBox(FloatArray(0), 1920, 1080))
        assertFalse(GameplayEvidenceGate.validPlayerBox(floatArrayOf(10f, 10f, 20f), 1920, 1080))
        assertFalse(GameplayEvidenceGate.validPlayerBox(floatArrayOf(300f, 200f, 100f, 400f), 1920, 1080))
        assertFalse(GameplayEvidenceGate.validPlayerBox(floatArrayOf(100f, 400f, 300f, 200f), 1920, 1080))
        assertFalse(GameplayEvidenceGate.validPlayerBox(floatArrayOf(100f, 200f, 300f, 200f), 1920, 1080))
        assertFalse(GameplayEvidenceGate.validPlayerBox(floatArrayOf(Float.NaN, 200f, 300f, 400f), 1920, 1080))
        assertFalse(GameplayEvidenceGate.validPlayerBox(floatArrayOf(100f, 200f, 300f, Float.POSITIVE_INFINITY), 1920, 1080))
        assertFalse(GameplayEvidenceGate.validPlayerBox(floatArrayOf(100f, Float.NaN, 300f, 400f), 1920, 1080))
    }

    @Test
    fun zeroSizedOrUnknownFramesInvalidateEveryBox() {
        val box = floatArrayOf(10f, 10f, 20f, 20f)
        assertFalse(GameplayEvidenceGate.validPlayerBox(box, 0, 1080))
        assertFalse(GameplayEvidenceGate.validPlayerBox(box, 1920, 0))
        assertFalse(GameplayEvidenceGate.validPlayerBox(box, -1, -1))
    }

    @Test
    fun boxesAreValidatedAgainstTheFrameTheyWereDetectedIn() {
        // A detection that is valid for a 1920x1080 frame but not for the frame it landed in.
        val box = floatArrayOf(1500f, 500f, 1800f, 700f)
        assertTrue(GameplayEvidenceGate.validPlayerBox(box, 1920, 1080))
        assertFalse(GameplayEvidenceGate.validPlayerBox(box, 1280, 720))
    }

    @Test
    fun observationCountReflectsTheAccumulatedPairOnly() {
        val g = GameplayEvidenceGate(maxAge)
        assertEquals(0, g.observationCount)
        admit(g, 5_000L, 5_000L)
        assertEquals(1, g.observationCount)
        assertEquals(5_000L, g.lastEvidenceFrameMs)
        admit(g, 5_250L, 5_250L)
        assertEquals(2, g.observationCount)
        g.observeMenuScreen()
        assertEquals(0, g.observationCount)
        assertEquals(0L, g.lastEvidenceFrameMs)
    }
}