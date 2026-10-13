package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure policy tests for [RepeatedAttackPolicy] and its engagement-radius helper.
 *
 * No Android, no OpenCV, no clock and no window: every case drives the state machine with explicit
 * timestamps, so each assertion below is a total function of its arguments. Covers the four value
 * thresholds that must not drift (engagement floor, cadence, evidence age, charge duration), the
 * invalid-evidence denials, monotonic capture freshness, cadence survival across a target dropout,
 * and release idempotence.
 */
class RepeatedAttackPolicyTest {

    @Test fun completedChargeKeepsReleaseToNextDownCadence() {
        val p = RepeatedAttackPolicy()
        assertEquals(BasicAttackAction.HOLD, p.step(10000, 10000, true, 1.0).action)
        assertEquals(BasicAttackAction.RELEASE, p.step(11000, 11000, true, 1.0).action)
        assertEquals(BasicAttackAction.NONE, p.step(11100, 11100, true, 1.0).action)
        assertEquals(BasicAttackAction.HOLD, p.step(11300, 11300, true, 1.0).action)
    }

    @Test fun overflowingSquaredDistancesDoNotBecomeInRange() {
        assertFalse(RepeatedAttackPolicy.isWithinEngagement(0.0, 0.0, 1e250, 0.0, 1e200))
    }

    private val tile = 54.0
    private val base = 10_000L

    private fun policy(maxAge: Long = 3_000L, cadence: Long = 300L) =
        RepeatedAttackPolicy(maxAge, cadence)

    // ---------------- engagement radius ----------------

    @Test
    fun engagementRadiusKeepsLongerCatalogRange() {
        // Catalog range far beyond the 6-tile floor must never be shortened.
        assertEquals(
            900.0,
            RepeatedAttackPolicy.minEngagementRadiusPx(900.0, tile),
            1e-9,
        )
    }

    @Test
    fun engagementRadiusUsesSixTileFloorForShortCatalogRange() {
        assertEquals(6.0 * tile, RepeatedAttackPolicy.minEngagementRadiusPx(120.0, tile), 1e-9)
    }

    @Test
    fun engagementRadiusFallsBackToFloorForMissingOrInvalidCatalogRange() {
        val floor = 6.0 * tile
        assertEquals(floor, RepeatedAttackPolicy.minEngagementRadiusPx(0.0, tile), 1e-9)
        assertEquals(floor, RepeatedAttackPolicy.minEngagementRadiusPx(-50.0, tile), 1e-9)
        assertEquals(floor, RepeatedAttackPolicy.minEngagementRadiusPx(Double.NaN, tile), 1e-9)
        assertEquals(floor, RepeatedAttackPolicy.minEngagementRadiusPx(Double.POSITIVE_INFINITY, tile), 1e-9)
    }

    @Test
    fun engagementRadiusFloorIsSixTilesAndScalesWithTileSize() {
        assertEquals(324.0, RepeatedAttackPolicy.ENGAGEMENT_TILE_FLOOR * tile, 1e-9)
        assertEquals(6.0 * 108.0, RepeatedAttackPolicy.minEngagementRadiusPx(0.0, 108.0), 1e-9)
    }

    @Test
    fun withinEngagementUsesSameHitCircleDistanceAsMovement() {
        val r = RepeatedAttackPolicy.minEngagementRadiusPx(300.0, tile)
        // Exactly at the radius is inside (inclusive), just beyond is not.
        assertTrue(RepeatedAttackPolicy.isWithinEngagement(0.0, 0.0, r, 0.0, r))
        assertFalse(RepeatedAttackPolicy.isWithinEngagement(0.0, 0.0, r + 0.001, 0.0, r))
        // Mid-range: 300px with a 324px floor radius is engaged.
        assertTrue(RepeatedAttackPolicy.isWithinEngagement(0.0, 0.0, 300.0, 0.0, r))
    }

    @Test
    fun withinEngagementRejectsNonFiniteGeometry() {
        val r = 324.0
        assertFalse(RepeatedAttackPolicy.isWithinEngagement(Double.NaN, 0.0, 10.0, 0.0, r))
        assertFalse(RepeatedAttackPolicy.isWithinEngagement(0.0, Double.NaN, 10.0, 0.0, r))
        assertFalse(RepeatedAttackPolicy.isWithinEngagement(0.0, 0.0, Double.NaN, 0.0, r))
        assertFalse(RepeatedAttackPolicy.isWithinEngagement(0.0, 0.0, 0.0, Double.POSITIVE_INFINITY, r))
        assertFalse(RepeatedAttackPolicy.isWithinEngagement(0.0, 0.0, 0.0, 0.0, 0.0))
        assertFalse(RepeatedAttackPolicy.isWithinEngagement(0.0, 0.0, 0.0, 0.0, Double.NaN))
    }

    // ---------------- tap admission ----------------

    @Test
    fun freshTargetInRangeTapsRepeatedly() {
        val p = policy()
        val d1 = p.step(base, base, true, 0.0)
        assertEquals(BasicAttackAction.TAP, d1.action)
        assertFalse(d1.holding)

        // A new frame 400ms later is a second, distinct, still-fresh capture -> another attack.
        val d2 = p.step(base + 400, base + 400, true, 0.0)
        assertEquals(BasicAttackAction.TAP, d2.action)
        assertTrue(p.decisionCount >= 2)
    }

    @Test
    fun noTargetDeniesAndReleasesNothingWhenIdle() {
        val p = policy()
        val d = p.step(base, base, false, 0.0)
        assertEquals(BasicAttackAction.NONE, d.action)
        assertEquals(BasicAttackDenial.NO_TARGET, d.denial)
        assertFalse(p.holding)
    }

    // ---------------- freshness / timestamps ----------------

    @Test
    fun repeatedSameCaptureTimestampIsNotAFreshAttack() {
        val p = policy()
        assertEquals(BasicAttackAction.TAP, p.step(base, base, true, 0.0).action)
        // Same capture seen again on a later tick: not newer evidence, no second attack.
        val d = p.step(base + 100, base, true, 0.0)
        assertEquals(BasicAttackAction.NONE, d.action)
        assertEquals(BasicAttackDenial.NO_FRESH_EVIDENCE, d.denial)
    }

    @Test
    fun staleFrameDeniedAndReleasesHeldButton() {
        val p = policy()
        // Start a charge on fresh evidence.
        assertEquals(BasicAttackAction.HOLD, p.step(base, base, true, 1.0).action)
        assertTrue(p.holding)
        // A frame older than the window must not authorize anything and must let the button up.
        val d = p.step(base + 5_000, base - 1, true, 1.0)
        assertEquals(BasicAttackAction.RELEASE, d.action)
        assertEquals(BasicAttackDenial.STALE_FRAME, d.denial)
        assertFalse(p.holding)
    }

    @Test
    fun futureFrameDenied() {
        val p = policy()
        val d = p.step(base, base + 50, true, 0.0)
        assertEquals(BasicAttackAction.NONE, d.action)
        assertEquals(BasicAttackDenial.FUTURE_FRAME, d.denial)
    }

    @Test
    fun missingCaptureTimestampDenied() {
        val p = policy()
        val d = p.step(base, 0L, true, 0.0)
        assertEquals(BasicAttackAction.NONE, d.action)
        assertEquals(BasicAttackDenial.INVALID_FRAME, d.denial)
    }

    // ---------------- cadence ----------------

    @Test
    fun cadenceBlocksSecondAttackInsideMinimumInterval() {
        val p = policy(cadence = 300L)
        assertEquals(BasicAttackAction.TAP, p.step(base, base, true, 0.0).action)
        // A genuinely newer frame, but only 100ms later: rate limited, no burst.
        val d = p.step(base + 100, base + 100, true, 0.0)
        assertEquals(BasicAttackAction.NONE, d.action)
        assertEquals(BasicAttackDenial.CADENCE, d.denial)
    }

    @Test
    fun cadenceSurvivesTargetDropoutSoReacquisitionCannotBurst() {
        val p = policy(cadence = 300L)
        assertEquals(BasicAttackAction.TAP, p.step(base, base, true, 0.0).action)
        // Transient loss of target: denied but the cadence is NOT reset.
        p.step(base + 100, base + 100, false, 0.0)
        // Re-acquire immediately: still inside the interval, so still rate limited.
        val d = p.step(base + 150, base + 150, true, 0.0)
        assertEquals(BasicAttackAction.NONE, d.action)
        assertEquals(BasicAttackDenial.CADENCE, d.denial)
    }

    @Test
    fun longGapProducesExactlyOneTapNoCatchUpBurst() {
        val p = policy(cadence = 300L)
        assertEquals(BasicAttackAction.TAP, p.step(base, base, true, 0.0).action)
        // After a very long gap the very first frame fires, but subsequent same-instant frames do not.
        val gap = base + 10_000
        assertEquals(BasicAttackAction.TAP, p.step(gap, gap, true, 0.0).action)
        val d = p.step(gap, gap, true, 0.0)
        assertEquals(BasicAttackAction.NONE, d.action) // no catch-up burst on identical capture
    }

    // ---------------- charge hold + release ----------------

    @Test
    fun catalogHoldIssuesHoldThenNoNewDownWhileCharging() {
        val p = policy()
        assertEquals(BasicAttackAction.HOLD, p.step(base, base, true, 1.5).action)
        assertTrue(p.holding)
        // Mid-charge on a newer fresh frame: still charging, no second DOWN.
        val d = p.step(base + 200, base + 200, true, 1.5)
        assertEquals(BasicAttackAction.NONE, d.action)
        assertEquals(BasicAttackDenial.CHARGING, d.denial)
        assertTrue(p.holding)
    }

    @Test
    fun chargeReleasesWhenElapsedDurationReached() {
        val p = policy()
        p.step(base, base, true, 1.0) // HOLD at base
        val d = p.step(base + 1_000, base + 1_000, true, 1.0) // elapsed == duration
        assertEquals(BasicAttackAction.RELEASE, d.action)
        assertFalse(p.holding)
    }

    @Test
    fun releaseIsIdempotentNoSecondReleaseNoNewDown() {
        val p = policy()
        p.step(base, base, true, 1.0) // HOLD
        val r1 = p.revoke()
        assertEquals(BasicAttackAction.RELEASE, r1.action)
        assertFalse(p.holding)
        // Second revoke with nothing held: no second RELEASE.
        val r2 = p.revoke()
        assertEquals(BasicAttackAction.NONE, r2.action)
        assertFalse(p.holding)
    }

    @Test
    fun unknownCatalogFallsBackToTapNotHold() {
        val p = policy()
        // Non-finite / negative holdSeconds (unknown brawler) must degrade to a tap, never hold forever.
        assertEquals(BasicAttackAction.TAP, p.step(base, base, true, Double.NaN).action)
        assertFalse(p.holding)
        val p2 = policy()
        assertEquals(BasicAttackAction.TAP, p2.step(base, base, true, -1.0).action)
        assertFalse(p2.holding)
    }

    @Test
    fun holdingSurvivesOrdinaryConsecutiveFreshFramesUntilChargeDone() {
        // A normal run of distinct fresh timestamps must NOT invalidate a healthy charge.
        val p = policy()
        p.step(base, base, true, 1.5)
        var t = base + 100
        while (t < base + 1_500) {
            val d = p.step(t, t, true, 1.5)
            assertEquals(BasicAttackAction.NONE, d.action)
            assertEquals(BasicAttackDenial.CHARGING, d.denial)
            assertTrue(p.holding)
            t += 100
        }
        val done = p.step(t, t, true, 1.5)
        assertEquals(BasicAttackAction.RELEASE, done.action)
        assertFalse(p.holding)
    }
}