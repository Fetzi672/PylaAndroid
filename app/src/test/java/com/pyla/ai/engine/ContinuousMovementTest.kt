package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class ContinuousMovementTest {
    private val origin = 100.0 to 100.0
    private val far = listOf(400.0 to 100.0)
    private fun move(
        p: ContinuousMovementPlanner = ContinuousMovementPlanner(), time: Long = 1000L,
        enemies: List<Pair<Double, Double>> = emptyList(), allies: List<Pair<Double, Double>> = emptyList(),
        player: Pair<Double, Double>? = origin, range: Double = 100.0, radius: Double = 10.0,
        step: Double = 20.0, capture: Long = time, progress: Boolean? = null,
        probe: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean = { _, _, _ -> false },
    ) = p.plan(player, enemies, allies, range, radius, step, capture, time, progress, probe)

    private fun active(d: ContinuousMovementDecision) {
        assertNotEquals(ContinuousMovementMode.DENIED, d.mode)
        val v = requireNotNull(d.vector)
        assertTrue(v.first.isFinite() && v.second.isFinite())
        assertTrue(hypot(v.first, v.second) > 0.0)
        assertTrue(hypot(v.first, v.second) <= 75.0)
    }
    private fun denied(d: ContinuousMovementDecision) {
        assertEquals(ContinuousMovementMode.DENIED, d.mode)
        assertNull(d.vector)
    }
    private fun sameHeading(a: ContinuousMovementDecision, b: ContinuousMovementDecision) {
        assertEquals(a.vector!!.first, b.vector!!.first, 1e-8)
        assertEquals(a.vector!!.second, b.vector!!.second, 1e-8)
    }
    private fun differentHeading(a: ContinuousMovementDecision, b: ContinuousMovementDecision) {
        assertTrue(hypot(a.vector!!.first - b.vector!!.first, a.vector!!.second - b.vector!!.second) > 1.0)
    }

    @Test fun noTargetRoamsWithoutGoalAndChangesLeg() {
        val p = ContinuousMovementPlanner()
        val a = move(p); active(a); assertEquals(ContinuousMovementMode.ROAM, a.mode)
        sameHeading(a, move(p, 2199))
        differentHeading(a, move(p, 2200))
    }

    @Test fun inRangeStrafesAndCloseAllyNeverHolds() {
        val inRange = move(enemies = listOf(190.0 to 100.0))
        active(inRange); assertEquals(ContinuousMovementMode.STRAFE, inRange.mode)
        assertEquals(0.0, inRange.vector!!.first, 1e-8)
        val close = move(allies = listOf(101.0 to 100.0))
        active(close); assertEquals(ContinuousMovementMode.STRAFE, close.mode)
        active(move(allies = listOf(origin)))
    }

    @Test fun approachRetreatFollowAndCoincidentEnemy() {
        val a = move(enemies = far)
        assertEquals(ContinuousMovementMode.APPROACH, a.mode); assertTrue(a.vector!!.first > 0)
        val r = move(enemies = listOf(120.0 to 100.0))
        assertEquals(ContinuousMovementMode.RETREAT, r.mode); assertTrue(r.vector!!.first < 0)
        val f = move(allies = far)
        assertEquals(ContinuousMovementMode.FOLLOW, f.mode); active(f)
        active(move(enemies = listOf(origin)))
        assertEquals(ContinuousMovementMode.RETREAT, move(enemies = listOf(origin), allies = far).mode)
    }

    @Test fun allBlockedExplicitEscapeRotatesAtBoundedIntervals() {
        val p = ContinuousMovementPlanner()
        var count = 0
        val wall: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean = { _, _, _ -> count++; true }
        val a = move(p, enemies = far, probe = wall)
        assertEquals(8, count); active(a); assertEquals(ContinuousMovementMode.ESCAPE, a.mode)
        sameHeading(a, move(p, 1599, enemies = far, probe = wall))
        val b = move(p, 1600, enemies = far, probe = wall)
        differentHeading(a, b); assertEquals(ContinuousMovementMode.ESCAPE, b.mode)
        differentHeading(b, move(p, 2200, enemies = far, probe = wall))
        assertEquals(32, count)
    }

    @Test fun everyDetourSegmentIsProbedAndLegIsStableThenReturnsToGoal() {
        val p = ContinuousMovementPlanner()
        val checked = mutableListOf<Pair<Double, Double>>()
        fun checkedMove(t: Long, blockForward: Boolean): ContinuousMovementDecision {
            checked.clear()
            val d = move(p, t, enemies = far, probe = { from, to, radius ->
                assertEquals(origin, from); assertEquals(10.0, radius, 0.0)
                assertEquals(20.0, hypot(to.first - from.first, to.second - from.second), 1e-8)
                if (blockForward && to.first > from.first + 1.0) true else { checked.add(to); false }
            })
            val v = d.vector!!
            val end = (origin.first + v.first / 74.0 * 20.0) to (origin.second + v.second / 74.0 * 20.0)
            assertTrue(checked.any { hypot(it.first - end.first, it.second - end.second) < 1e-8 })
            return d
        }
        val a = checkedMove(1000, true)
        assertEquals(ContinuousMovementMode.DETOUR, a.mode)
        sameHeading(a, checkedMove(1100, false))
        sameHeading(a, checkedMove(2199, false))
        val goal = checkedMove(2200, false)
        assertEquals(ContinuousMovementMode.APPROACH, goal.mode); assertTrue(goal.vector!!.first > 0)
    }

    @Test fun wallAdjacentCanChooseOppositeDirectionAwayFromGoal() {
        val d = move(enemies = far, probe = { from, to, _ ->
            !(to.first < from.first - 19.0 && abs(to.second - from.second) < 1.0)
        })
        active(d); assertEquals(ContinuousMovementMode.DETOUR, d.mode)
        assertTrue(d.vector!!.first < -70.0)
    }

    @Test fun changedTargetUsesCurrentGoalButDoesNotFlipActiveDetour() {
        val p = ContinuousMovementPlanner()
        val left = listOf(-300.0 to 100.0)
        val a = move(p, enemies = far)
        val b = move(p, 1001, enemies = left)
        differentHeading(a, b); assertTrue(b.vector!!.first < 0)
        p.reset()
        val detour = move(p, enemies = far, probe = { from, to, _ -> to.first > from.first + 1.0 })
        sameHeading(detour, move(p, 1100, enemies = left))
        val resumed = move(p, 2200, enemies = left)
        assertEquals(ContinuousMovementMode.APPROACH, resumed.mode); assertTrue(resumed.vector!!.first < 0)
    }

    @Test fun explicitNonprogressTriggersAtTwoSecondsAndAlternates() {
        val p = ContinuousMovementPlanner()
        val a = move(p, enemies = far, progress = false)
        sameHeading(a, move(p, 2999, enemies = far, progress = false))
        val stuck = move(p, 3000, enemies = far, progress = false)
        assertEquals(ContinuousMovementMode.ESCAPE, stuck.mode); differentHeading(a, stuck)
        move(p, 3001, enemies = far, progress = false)
        val normal = move(p, 3002, enemies = far, progress = false)
        assertEquals(ContinuousMovementMode.APPROACH, normal.mode)
        val again = move(p, 5000, enemies = far, progress = false)
        assertEquals(ContinuousMovementMode.ESCAPE, again.mode)
        assertTrue(stuck.vector!!.second * again.vector!!.second < 0.0)
    }

    @Test fun progressTrueResetsEvidenceAndStablePlayerIsNotStuck() {
        val p = ContinuousMovementPlanner()
        move(p, enemies = far, progress = false)
        move(p, 2900, enemies = far, progress = true)
        assertEquals(ContinuousMovementMode.APPROACH, move(p, 3000, enemies = far, progress = false).mode)
        assertEquals(ContinuousMovementMode.APPROACH, move(p, 4999, enemies = far, progress = false).mode)
        assertEquals(ContinuousMovementMode.ESCAPE, move(p, 5000, enemies = far, progress = false).mode)
        assertEquals(ContinuousMovementMode.APPROACH, move(p, 5001, enemies = far, progress = true).mode)
        for (t in 6000L..16000L step 1000L) {
            assertEquals(ContinuousMovementMode.APPROACH, move(p, t, enemies = far).mode)
        }
    }

    @Test fun duplicateAndOutOfOrderDoNotAdvanceLegOrProgressEvidence() {
        val p = ContinuousMovementPlanner()
        val a = move(p, enemies = far, progress = false)
        sameHeading(a, move(p, 2900, capture = 1000, enemies = far, progress = true))
        sameHeading(a, move(p, 2950, capture = 999, enemies = far, progress = true))
        val stuck = move(p, 3000, enemies = far, progress = false)
        assertEquals(ContinuousMovementMode.ESCAPE, stuck.mode)
        sameHeading(stuck, move(p, 4000, capture = 3000, enemies = far, progress = false))
        val q = ContinuousMovementPlanner()
        val initial = move(q, probe = { _, _, _ -> true })
        sameHeading(initial, move(q, 2200, capture = 1000, probe = { _, _, _ -> true }))
        differentHeading(initial, move(q, 2201, probe = { _, _, _ -> true }))
    }

    @Test fun replayRechecksDetourAndDoesNotClaimClearWhenNowBlocked() {
        val p = ContinuousMovementPlanner()
        val a = move(p, enemies = far, probe = { from, to, _ -> to.first > from.first + 1 })
        var probes = 0
        val b = move(p, 1001, capture = 1000, enemies = far, probe = { _, _, _ -> probes++; true })
        sameHeading(a, b); assertEquals(1, probes); assertEquals(ContinuousMovementMode.ESCAPE, b.mode)
    }

    @Test fun invalidPlayerGeometryAndTimeDenyWithoutProbe() {
        val nan = Double.NaN
        val inf = Double.POSITIVE_INFINITY
        val wall: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean = { _, _, _ -> fail("invalid frame probed"); false }
        for (player in listOf(null, nan to 1.0, 1.0 to inf, 1e10 to 0.0)) denied(move(player = player, probe = wall))
        for (v in listOf(nan, inf, -1.0, 0.0, 1e7)) {
            denied(move(range = v, probe = wall)); denied(move(step = v, probe = wall))
        }
        for (v in listOf(nan, inf, -1.0, 1e7)) denied(move(radius = v, probe = wall))
        active(move(radius = 0.0))
        denied(move(time = 5001, capture = 2000, probe = wall))
        active(move(time = 5000, capture = 2000))
        denied(move(time = 1000, capture = 1001, probe = wall))
        denied(move(time = 0, probe = wall)); denied(move(time = -1, probe = wall))
        denied(move(time = Long.MAX_VALUE, capture = 1, probe = wall))
        denied(move(time = Long.MAX_VALUE, capture = Long.MIN_VALUE, probe = wall))
        active(move(time = Long.MAX_VALUE, capture = Long.MAX_VALUE))
        val p = ContinuousMovementPlanner(); move(p, 5000)
        denied(move(p, 4999, probe = wall))
    }

    @Test fun invalidFrameClearsCachedIntentAndResetClearsWatermarkAndLegs() {
        val p = ContinuousMovementPlanner()
        move(p, enemies = far, probe = { from, to, _ -> to.first > from.first + 1 })
        denied(move(p, 1100, player = null))
        denied(move(p, 1200, capture = 1000, enemies = far))
        assertEquals(ContinuousMovementMode.APPROACH, move(p, 1201, enemies = far).mode)
        move(p, 4000)
        p.reset()
        assertEquals(move(), move(p))
        p.reset()
        val wall: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean = { _, _, _ -> true }
        val a = move(p, probe = wall)
        differentHeading(a, move(p, 1600, probe = wall))
        p.reset(); assertEquals(a, move(p, probe = wall))
    }

    @Test fun invalidDetectionsIgnoredAndOnlyFirstSixteenInspected() {
        val bounded = object : AbstractList<Pair<Double, Double>>() {
            override val size = Int.MAX_VALUE
            override fun get(index: Int): Pair<Double, Double> {
                assertTrue("unbounded detection access $index", index < 16)
                return Double.NaN to 0.0
            }
        }
        var probes = 0
        val d = move(enemies = bounded, allies = bounded, probe = { _, _, _ -> probes++; true })
        active(d); assertEquals(8, probes)
        assertEquals(ContinuousMovementMode.ROAM, move(enemies = listOf(Double.NaN to 2.0, 1e10 to 0.0)).mode)
        assertEquals(ContinuousMovementMode.APPROACH, move(enemies = listOf(Double.NaN to 2.0) + far).mode)
    }

    @Test fun allBlockedRotationSurvivesPositiveProgressAndClearRouteResumesGoal() {
        val p = ContinuousMovementPlanner()
        val wall: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean = { _, _, _ -> true }
        val a = move(p, enemies = far, progress = true, probe = wall)
        val b = move(p, 1600, enemies = far, progress = true, probe = wall)
        differentHeading(a, b)
        assertEquals(ContinuousMovementMode.ESCAPE, b.mode)
        active(move(p, 1700, enemies = far, progress = true))
        assertEquals(ContinuousMovementMode.APPROACH, move(p, 1701, enemies = far, progress = true).mode)
    }

    @Test fun staleFutureAndNanFramesCannotAuthorizeCachedVector() {
        for (bad in 0..2) {
            val p = ContinuousMovementPlanner()
            active(move(p))
            val d = when (bad) {
                0 -> move(p, 5001, capture = 1000)
                1 -> move(p, 1100, capture = 1101)
                else -> move(p, 1100, player = Double.NaN to 0.0)
            }
            denied(d)
            denied(move(p, 1200, capture = 1000))
            active(move(p, 1201))
        }
    }

    @Test fun timersNearLongMaxDoNotOverflowAndNullProgressBreaksEvidence() {
        val p = ContinuousMovementPlanner()
        val a = move(p, Long.MAX_VALUE - 2000, enemies = far, progress = false)
        sameHeading(a, move(p, Long.MAX_VALUE - 1, enemies = far, progress = false))
        assertEquals(ContinuousMovementMode.ESCAPE, move(p, Long.MAX_VALUE, enemies = far, progress = false).mode)
        p.reset()
        move(p, 1000, enemies = far, progress = false)
        move(p, 2999, enemies = far)
        assertEquals(ContinuousMovementMode.APPROACH, move(p, 3000, enemies = far, progress = false).mode)
    }

    @Test fun resetRestoresSideAndClearsNonprogressEvidence() {
        val p = ContinuousMovementPlanner()
        move(p, enemies = far, progress = false)
        move(p, 3000, enemies = far, progress = false)
        p.reset()
        assertEquals(move(enemies = listOf(190.0 to 100.0)), move(p, enemies = listOf(190.0 to 100.0)))
        assertEquals(ContinuousMovementMode.APPROACH, move(p, 4000, enemies = far, progress = false).mode)
    }

    @Test fun deterministicBoundedLongSequenceWithoutRandomOrAmbientTime() {
        val a = ContinuousMovementPlanner(); val b = ContinuousMovementPlanner()
        for (i in 1..1000) {
            var countA = 0; var countB = 0
            val time = i.toLong() * 137
            val targets = if (i % 3 == 0) emptyList() else listOf((100 + i % 200).toDouble() to 130.0)
            val da = move(a, time, enemies = targets, progress = i % 31 == 0, probe = { from, to, _ ->
                countA++; i % 7 == 0 || (i % 5 == 0 && to.first > from.first)
            })
            val db = move(b, time, enemies = targets, progress = i % 31 == 0, probe = { from, to, _ ->
                countB++; i % 7 == 0 || (i % 5 == 0 && to.first > from.first)
            })
            active(da); assertEquals(da, db); assertEquals(countA, countB); assertTrue(countA in 1..8)
        }
    }
}
