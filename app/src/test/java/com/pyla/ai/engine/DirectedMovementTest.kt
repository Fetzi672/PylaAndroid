package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Policy tests for the directed movement planner.
 *
 * These assert the whole safety contract of [DirectedMovementPlanner] as a pure function of its
 * arguments: goal selection, engagement band, input validity and the wall/dot-product progress gate.
 * Every case is deterministic: no Android, no OpenCV, no clock, no RNG - a real wall probe is
 * injected as a pure lambda.
 */
class DirectedMovementTest {

    private val atk = 400.0
    private val p = 1000.0 to 1000.0
    private val hold = atk * DirectedMovementPlanner.HOLD_BAND_RATIO // 320
    private val free: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean = { _, _, _ -> false }
    private val blockedAll: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean = { _, _, _ -> true }

    private fun plan(
        player: Pair<Double, Double>? = p,
        enemies: List<Pair<Double, Double>> = emptyList(),
        allies: List<Pair<Double, Double>> = emptyList(),
        attackRange: Double = atk,
        followRange: Double = attackRange * DirectedMovementPlanner.FOLLOW_RANGE_RATIO,
        playerRadius: Double = 53.0,
        stepPx: Double = 54.0,
        maxInput: Double = DirectedMovementPlanner.JOYSTICK_MAX_MAGNITUDE,
        maxTargetRange: Double = 4096.0,
        wallProbe: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean = free,
    ) = DirectedMovementPlanner.plan(
        player, enemies, allies, attackRange, followRange, playerRadius, stepPx, maxInput,
        maxTargetRange, wallProbe,
    )

    private fun angleOf(v: Pair<Double, Double>): Double = atan2(v.second, v.first)

    // ---------------- direction ----------------

    @Test
    fun approachMovesStraightAtADistantEnemy() {
        val d = plan(enemies = listOf(1600.0 to 1000.0))
        assertEquals(MovementGoalKind.ENEMY, d.goalKind)
        assertEquals(MovementReason.APPROACH_TARGET, d.reason)
        assertEquals(MovementPhase.APPROACH, d.phase)
        val v = assertNotNull(d.vector).let { d.vector!! }
        assertEquals(75.0, v.first, 1e-6)
        assertEquals(0.0, v.second, 1e-9)
        assertTrue(v.first > 70.0)
    }

    @Test
    fun approachWorksInEveryDirectionWithoutCompassBias() {
        for (k in 0 until 16) {
            val a = 2.0 * PI * k / 16.0
            val d = plan(enemies = listOf(p.first + 900 * cos(a) to p.second + 900 * sin(a)))
            val v = d.vector
            assertNotNull("direction $k must produce input", v)
            assertEquals(cos(a), v!!.first / 75.0, 1e-6)
            assertEquals(sin(a), v.second / 75.0, 1e-6)
            assertEquals(atan2(sin(a), cos(a)), angleOf(v!!), 1e-6)
            assertEquals(DirectedMovementPlanner.JOYSTICK_MAX_MAGNITUDE, hypot(v.first, v.second), 1e-6)
        }
    }

    // ---------------- engagement band ----------------

    @Test
    fun enemyInsideHoldBandTriggersRetreat() {
        val d = plan(enemies = listOf(p.first + (hold - 50.0) to p.second))
        assertEquals(MovementReason.RETREAT_TARGET, d.reason)
        assertEquals(MovementPhase.RETREAT, d.phase)
        val v = d.vector!!
        assertTrue("must move away from the enemy", v.first < -70.0)
    }

    @Test
    fun retreatDirectionIsTheExactOppositeOfTheApproachDirection() {
        val far = plan(enemies = listOf(1700.0 to 1400.0)).vector!!
        val near = plan(enemies = listOf(1175.0 to 1100.0)).vector!!
        assertEquals(-far.first, near.first, 1e-6)
        assertEquals(-far.second, near.second, 1e-6)
    }

    @Test
    fun enemyInsideRangeButOutsideHoldBandHolds() {
        val d = plan(enemies = listOf(p.first + (hold + 40.0) to p.second))
        assertEquals(MovementReason.IN_RANGE, d.reason)
        assertEquals(MovementPhase.HOLD, d.phase)
        assertNull("holding must release the joystick", d.vector)
        assertTrue(d.releases)
    }

    @Test
    fun exactlyOnAttackRangeHoldsRatherThanClosing() {
        val d = plan(enemies = listOf(p.first + atk to p.second))
        assertEquals(MovementReason.IN_RANGE, d.reason)
        assertNull(d.vector)
    }

    @Test
    fun allyIsFollowedBeyondFollowRangeAndHeldInsideIt() {
        val follow = plan(allies = listOf(p.first + 900.0 to p.second))
        assertEquals(MovementGoalKind.ALLY, follow.goalKind)
        assertEquals(MovementReason.FOLLOW_TARGET, follow.reason)
        assertTrue(follow.vector!!.first > 70.0)

        val holdAlly = plan(allies = listOf(p.first + 100.0 to p.second))
        assertEquals(MovementReason.IN_RANGE, holdAlly.reason)
        assertNull(holdAlly.vector)
    }

    // ---------------- goal priority / determinism ----------------

    @Test
    fun enemyBeatsAllyEvenWhenTheAllyIsMuchCloser() {
        val d = plan(enemies = listOf(p.first + 380.0 to p.second), allies = listOf(p.first + 40.0 to p.second))
        assertEquals(MovementGoalKind.ENEMY, d.goalKind)
    }

    @Test
    fun nearestEnemyIsChosenOverFartherEnemies() {
        val d = plan(enemies = listOf(p.first + 900.0 to p.second, p.first + 800.0 to p.second + 1.0))
        assertEquals(atan2(1.0, 800.0), angleOf(d.vector!!), 1e-9)
    }

    @Test
    fun exactDistanceTieIsBrokenDeterministicallyAndStaysIdenticalOnRepeat() {
        val a = 1100.0 to 1000.0
        val b = 1000.0 to 1100.0
        val first = plan(enemies = listOf(a, b)).vector!!
        val swapped = plan(enemies = listOf(b, a)).vector!!
        // Deterministic tie-break: smallest x wins, so input order must not change the result.
        assertEquals(0.0, first.first, 1e-9)
        assertTrue("nearest tied enemy below player requires retreat upward", first.second < -70.0)
        assertEquals(first, swapped)
        repeat(50) { assertEquals(first, plan(enemies = listOf(a, b)).vector) }
    }

    @Test
    fun farEnemiesBeyondMaxTargetRangeAreIgnored() {
        val d = plan(
            enemies = listOf(p.first + 5000.0 to p.second, p.first + 800.0 to p.second),
            maxTargetRange = 1000.0,
        )
        assertEquals(atan2(0.0, 800.0), angleOf(d.vector!!), 1e-9)
        val none = plan(enemies = listOf(p.first + 5000.0 to p.second), maxTargetRange = 1000.0)
        assertEquals(MovementReason.NO_TARGET, none.reason)
    }

    @Test
    fun targetOnTopOfThePlayerIsRejectedInsteadOfGuessingADirection() {
        val d = plan(enemies = listOf(p))
        assertEquals(MovementReason.NO_TARGET, d.reason)
        assertNull(d.vector)
    }

    // ---------------- target loss: never a remembered vector ----------------

    @Test
    fun losingEveryTargetReleasesWithNoRememberedVector() {
        val moving = plan(enemies = listOf(1600.0 to 1000.0))
        assertNotNull(moving.vector)
        val lost = plan()
        assertEquals(MovementReason.NO_TARGET, lost.reason)
        assertEquals(MovementPhase.AWAIT_TARGET, lost.phase)
        assertNull(lost.goalKind)
        assertNull(lost.vector)
        repeat(100) { assertNull("no drift after target loss", plan().vector) }
    }

    @Test
    fun noTargetAndNoAllyReleasesImmediately() {
        val d = plan()
        assertTrue(d.releases)
        assertNull(d.goalKind)
        assertEquals(MovementPhase.AWAIT_TARGET, d.phase)
    }

    // ---------------- invalid inputs ----------------

    @Test
    fun missingOrNonFinitePlayerIsNoPlayerNotAMovement() {
        for (bad in listOf(null, p.first to Double.NaN, Double.POSITIVE_INFINITY to p.second)) {
            val d = plan(player = bad, enemies = listOf(1600.0 to 1000.0))
            assertEquals(MovementReason.NO_PLAYER, d.reason)
            assertEquals(MovementPhase.AWAIT_PLAYER, d.phase)
            assertNull(d.vector)
        }
    }

    @Test
    fun invalidGeometryParametersReleaseRatherThanClamp() {
        val cases = listOf<Pair<String, DirectedMovementDecision>>(
            "attackRange=0" to plan(enemies = listOf(1600.0 to 1000.0), attackRange = 0.0),
            "attackRange<0" to plan(enemies = listOf(1600.0 to 1000.0), attackRange = -5.0),
            "attackRange=NaN" to plan(enemies = listOf(1600.0 to 1000.0), attackRange = Double.NaN),
            "attackRange=Inf" to plan(enemies = listOf(1600.0 to 1000.0), attackRange = Double.POSITIVE_INFINITY),
            "followRange=0" to plan(enemies = listOf(1600.0 to 1000.0), followRange = 0.0),
            "followRange=NaN" to plan(enemies = listOf(1600.0 to 1000.0), followRange = Double.NaN),
            "stepPx=0" to plan(enemies = listOf(1600.0 to 1000.0), stepPx = 0.0),
            "stepPx=NaN" to plan(enemies = listOf(1600.0 to 1000.0), stepPx = Double.NaN),
            "maxInput=0" to plan(enemies = listOf(1600.0 to 1000.0), maxInput = 0.0),
            "maxInput>75" to plan(enemies = listOf(1600.0 to 1000.0), maxInput = 75.5),
            "maxInput=NaN" to plan(enemies = listOf(1600.0 to 1000.0), maxInput = Double.NaN),
            "maxTargetRange=0" to plan(enemies = listOf(1600.0 to 1000.0), maxTargetRange = 0.0),
            "maxTargetRange=Inf" to plan(enemies = listOf(1600.0 to 1000.0), maxTargetRange = Double.POSITIVE_INFINITY),
            "playerRadius=NaN" to plan(enemies = listOf(1600.0 to 1000.0), playerRadius = Double.NaN),
            "playerRadius<0" to plan(enemies = listOf(1600.0 to 1000.0), playerRadius = -1.0),
        )
        for ((label, d) in cases) {
            assertEquals(label, MovementReason.INVALID_INPUT, d.reason)
            assertEquals(label, MovementPhase.BLOCKED, d.phase)
            assertNull("$label must release", d.vector)
        }
    }

    @Test
    fun nonFiniteTargetsAreSkippedRatherThanPoisoningTheGoal() {
        for (bad in listOf(
            Double.NaN to p.second,
            p.first to Double.NEGATIVE_INFINITY,
            Double.POSITIVE_INFINITY to Double.NaN,
        )) {
            val d = plan(enemies = listOf(bad, 1600.0 to 1000.0))
            assertEquals(MovementReason.APPROACH_TARGET, d.reason)
            assertEquals(0.0, d.vector!!.second, 1e-9)
        }
        val onlyBad = plan(enemies = listOf(Double.NaN to p.second))
        assertEquals(MovementReason.NO_TARGET, onlyBad.reason)
        assertNull(onlyBad.vector)
    }

    @Test
    fun maxInputAtTheBoundIsAcceptedAndNeverExceedsIt() {
        val exact = plan(enemies = listOf(1600.0 to 1000.0), maxInput = DirectedMovementPlanner.JOYSTICK_MAX_MAGNITUDE)
        assertEquals(75.0, hypot(exact.vector!!.first, exact.vector.second), 1e-6)
        val smaller = plan(enemies = listOf(1600.0 to 1000.0), maxInput = 40.0)
        assertEquals(40.0, hypot(smaller.vector!!.first, smaller.vector.second), 1e-6)
    }

    @Test
    fun everyProducedVectorStaysFiniteAndInsideTheJoystickBound() {
        for (k in 0 until 64) {
            val a = 2.0 * PI * k / 64.0
            val forEnemy = plan(enemies = listOf(p.first + 120 * cos(a) to p.second + 120 * sin(a)))
            val vector = forEnemy.vector
            if (vector != null) {
                assertTrue(vector.first.isFinite() && vector.second.isFinite())
                assertTrue(hypot(vector.first, vector.second) <= DirectedMovementPlanner.JOYSTICK_MAX_MAGNITUDE + 1e-9)
            }
        }
    }

    // ---------------- wall avoidance and the progress gate ----------------

    @Test
    fun blockingOnlyTheIdealDirectionPicksAForwardDetourNotAWallFreeFallback() {
        val enemy = 1600.0 to 1000.0
        val idealAngle = atan2(enemy.second - p.second, enemy.first - p.first)
        // Block exactly the ideal direction; everything else is free.
        val onlyIdealBlocked: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean = { from, to, _ ->
            abs(atan2(to.second - from.second, to.first - from.first) - idealAngle) < 1e-9
        }
        val d = plan(enemies = listOf(enemy), wallProbe = onlyIdealBlocked)
        assertEquals(MovementReason.WALL_AVOIDED, d.reason)
        assertEquals(MovementPhase.AVOID, d.phase)
        val v = d.vector ?: error("a forward detour exists and must be used")
        val chosen = angleOf(v)
        assertEquals(idealAngle + PI / 4.0, chosen, 1e-9)
        // Positive progress toward the goal: the detour must still reduce the distance.
        assertTrue(cos(chosen - idealAngle) > 0.0)
        assertTrue(
            hypot(v.first, v.second) <= DirectedMovementPlanner.JOYSTICK_MAX_MAGNITUDE + 1e-6,
        )
    }

    @Test
    fun onlyBackwardsDirectionsFreeReleasesTheJoystick() {
        val enemy = 1600.0 to 1000.0
        val idealAngle = atan2(enemy.second - p.second, enemy.first - p.first)
        val onlyBackwardsFree: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean = { from, to, _ ->
            val a = atan2(to.second - from.second, to.first - from.first)
            // Free only when the candidate points away from the goal.
            cos(a - idealAngle) > 0.0
        }
        val d = plan(enemies = listOf(enemy), wallProbe = onlyBackwardsFree)
        assertEquals(MovementReason.ALL_PATHS_BLOCKED, d.reason)
        assertEquals(MovementPhase.BLOCKED, d.phase)
        assertNull("no deterministic wall-free fallback direction may be invented", d.vector)
    }

    @Test
    fun everyDirectionBlockedReleasesWithTheGoalStillReported() {
        val d = plan(enemies = listOf(1600.0 to 1000.0), wallProbe = blockedAll)
        assertEquals(MovementReason.ALL_PATHS_BLOCKED, d.reason)
        assertNull(d.vector)
        assertEquals(MovementGoalKind.ENEMY, d.goalKind)
    }

    @Test
    fun everyDirectionBlockedAlsoReleasesForRetreat() {
        val d = plan(enemies = listOf(p.first + 40.0 to p.second), wallProbe = blockedAll)
        assertEquals(MovementReason.ALL_PATHS_BLOCKED, d.reason)
        assertNull(d.vector)
    }

    @Test
    fun retreatDetourStillIncreasesDistanceFromTheEnemy() {
        val enemy = p.first + 60.0 to p.second
        val idealAngle = atan2(p.second - enemy.second, p.first - enemy.first)
        val onlyIdealBlocked: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean = { from, to, _ ->
            abs(atan2(to.second - from.second, to.first - from.first) - idealAngle) < 1e-9
        }
        val d = plan(enemies = listOf(enemy), wallProbe = onlyIdealBlocked)
        assertEquals(MovementReason.WALL_AVOIDED, d.reason)
        val v = d.vector!!
        assertTrue(cos(angleOf(v) - idealAngle) > 0.0)
    }

    @Test
    fun plannerIsAPureFunctionOfItsArguments() {
        val enemy = 1600.0 to 1200.0
        val probe: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean = { from, to, _ ->
            (from.first + to.first) < 2600.0
        }
        val a = plan(enemies = listOf(enemy), wallProbe = probe)
        val b = plan(enemies = listOf(enemy), wallProbe = probe)
        assertEquals(a, b)
        repeat(25) { assertEquals(a, plan(enemies = listOf(enemy), wallProbe = probe)) }
    }

}
