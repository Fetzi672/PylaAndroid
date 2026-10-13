package com.pyla.ai.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Deterministic, evidence-bound movement planning for gameplay.
 *
 * Contract (pure, no Android/OpenCV/random/time dependencies):
 *  - Every decision comes from the *current* frame: the freshly detected player position and the
 *    currently detected goal entity. There is no remembered vector, no idle timer, no RNG and no
 *    blind/idle wander path in this file, and none is reachable from here.
 *  - Goal selection: nearest valid detected enemy, otherwise nearest valid detected ally-follow
 *    target. "Valid" means finite coordinates, distinct from the player, and within a finite
 *    maximum engagement distance.
 *  - Engagement: deterministic approach / retreat / range-hold derived from the brawler attack range
 *    (approach beyond range, retreat inside the hold band, hold inside the band), and ally follow.
 *  - Wall safety: the ideal direction is probed with a swept-circle check; when it is blocked, a
 *    fixed, ordered rotation (never a shuffled one) yields the first clear alternative that still
 *    serves the same goal. Blocked nearest goals may yield to another current goal. A lateral detour requires a
 *    complete collision-checked two-segment route making net progress; only its first step is
 *    emitted and the next capture replans. If no such route exists the joystick is released.
 *  - Input validity: the produced vector is always finite and inside the joystick magnitude bound,
 *    or null (release).
 *
 * Diagnostics carry only the fixed reason vocabulary - never coordinates, OCR text or identifiers.
 */
enum class MovementGoalKind { ENEMY, ALLY }

/** Fixed diagnostic vocabulary for directed movement. */
enum class MovementReason {
    NO_PLAYER,
    NO_TARGET,
    IN_RANGE,
    APPROACH_TARGET,
    RETREAT_TARGET,
    FOLLOW_TARGET,
    WALL_AVOIDED,
    ALL_PATHS_BLOCKED,
    INVALID_INPUT,
}

enum class MovementPhase {
    AWAIT_PLAYER, AWAIT_TARGET, APPROACH, RETREAT, FOLLOW, HOLD, BLOCKED, AVOID,
}

data class DirectedMovementDecision(
    /** null means: release the joystick, do not send movement input. */
    val vector: Pair<Double, Double>?,
    val phase: MovementPhase,
    val reason: MovementReason,
    val goalKind: MovementGoalKind?,
) {
    val releases: Boolean get() = vector == null
}

object DirectedMovementPlanner {

    /** Joystick magnitude bound shared with the input layer (PylaUtils.JOYSTICK_RADIUS). */
    const val JOYSTICK_MAX_MAGNITUDE = 75.0

    /** Hold band below the attack range where the bot stops closing distance. */
    const val HOLD_BAND_RATIO = 0.8

    /** Ally follow distance defaults to just outside the attack range. */
    const val FOLLOW_RANGE_RATIO = 1.25

    private const val MIN_SEPARATION = 1.0

    /**
     * Deterministic probe offsets in radians, tried in this exact order. Zero comes first (the ideal
     * direction), then alternating +/- steps, so the alternative chosen for a given geometry is a
     * pure function of the inputs.
     */
    private val PROBE_OFFSETS = listOf(
        0.0,
        PI / 4.0, -PI / 4.0,
        PI / 2.0, -PI / 2.0,
        3.0 * PI / 4.0, -3.0 * PI / 4.0,
        PI,
    )

    /**
     * @param wallProbe swept-circle collision test: (from, to, radius) -> true when blocked.
     *                   Injected so the planner stays pure and unit-testable on the JVM.
     */
    fun plan(
        player: Pair<Double, Double>?,
        enemies: List<Pair<Double, Double>> = emptyList(),
        allies: List<Pair<Double, Double>> = emptyList(),
        attackRange: Double,
        followRange: Double = attackRange * FOLLOW_RANGE_RATIO,
        playerRadius: Double = 53.0,
        stepPx: Double = 54.0,
        maxInput: Double = JOYSTICK_MAX_MAGNITUDE,
        maxTargetRange: Double = 4096.0,
        wallProbe: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean = { _, _, _ -> false },
    ): DirectedMovementDecision {
        val origin = player
        if (origin == null || !finite(origin) || !finiteNumber(attackRange) || attackRange <= 0.0 ||
            !finiteNumber(followRange) || followRange <= 0.0 || !finiteNumber(stepPx) || stepPx <= 0.0 ||
            !finiteNumber(maxInput) || maxInput <= 0.0 || maxInput > JOYSTICK_MAX_MAGNITUDE ||
            !finiteNumber(maxTargetRange) || maxTargetRange <= 0.0 || !finiteNumber(playerRadius) || playerRadius < 0.0
        ) {
            val reason = if (origin == null || !finite(origin)) MovementReason.NO_PLAYER else MovementReason.INVALID_INPUT
            val phase = if (reason == MovementReason.NO_PLAYER) MovementPhase.AWAIT_PLAYER else MovementPhase.BLOCKED
            return DirectedMovementDecision(null, phase, reason, null)
        }

        val goals = orderedValid(origin, enemies, maxTargetRange).map { it to MovementGoalKind.ENEMY } +
            orderedValid(origin, allies, maxTargetRange).map { it to MovementGoalKind.ALLY }
        if (goals.isEmpty()) return DirectedMovementDecision(null, MovementPhase.AWAIT_TARGET, MovementReason.NO_TARGET, null)
        // Preserve the ordinary nearest-goal decision. Only a genuinely blocked plan tries the
        // next currently observed goal; losing every goal still releases, without stored intent.
        for ((target, kind) in goals) {
            val decision = planGoal(origin, target, kind, attackRange, followRange, playerRadius, stepPx, maxInput, wallProbe, false)
            if (decision.reason != MovementReason.ALL_PATHS_BLOCKED) return decision
        }
        // Bounded two-segment lookahead: a lateral first step is authorized only by a fully
        // collision-checked second segment that makes net progress for that SAME current goal.
        for ((target, kind) in goals) {
            val decision = planGoal(origin, target, kind, attackRange, followRange, playerRadius, stepPx, maxInput, wallProbe, true)
            if (decision.reason != MovementReason.ALL_PATHS_BLOCKED) return decision
        }
        return DirectedMovementDecision(null, MovementPhase.BLOCKED, MovementReason.ALL_PATHS_BLOCKED, goals.first().second)
    }

    private fun planGoal(
        origin: Pair<Double, Double>, target: Pair<Double, Double>, kind: MovementGoalKind,
        attackRange: Double, followRange: Double, playerRadius: Double, stepPx: Double, maxInput: Double,
        wallProbe: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean,
        lookAhead: Boolean,
    ): DirectedMovementDecision {
        val distance = dist(origin, target)
        val holdBand = attackRange * HOLD_BAND_RATIO

        val intent: Intent = when {
            kind == MovementGoalKind.ENEMY && distance > attackRange ->
                Intent(away = false, phase = MovementPhase.APPROACH, reason = MovementReason.APPROACH_TARGET)
            kind == MovementGoalKind.ENEMY && distance < holdBand ->
                Intent(away = true, phase = MovementPhase.RETREAT, reason = MovementReason.RETREAT_TARGET)
            kind == MovementGoalKind.ALLY && distance > followRange ->
                Intent(away = false, phase = MovementPhase.FOLLOW, reason = MovementReason.FOLLOW_TARGET)
            else -> return DirectedMovementDecision(null, MovementPhase.HOLD, MovementReason.IN_RANGE, kind)
        }

        val ideal: Pair<Double, Double> = if (intent.away) {
            (origin.first - target.first) to (origin.second - target.second)
        } else {
            (target.first - origin.first) to (target.second - origin.second)
        }
        val magnitude = hypot(ideal.first, ideal.second)
        if (!finiteNumber(magnitude) || magnitude < MIN_SEPARATION) {
            // Degenerate geometry: hold instead of guessing a direction.
            return DirectedMovementDecision(null, MovementPhase.HOLD, MovementReason.IN_RANGE, kind)
        }
        val idealAngle = kotlin.math.atan2(ideal.second, ideal.first)

        for (offset in PROBE_OFFSETS) {
            val angle = idealAngle + offset
            val ux = cos(angle); val uy = sin(angle)
            if (!finiteNumber(ux) || !finiteNumber(uy)) continue
            val candidate = (ux * maxInput) to (uy * maxInput)
            if (!finite(candidate) || hypot(candidate.first, candidate.second) > maxInput + 1e-6) continue
            val end = (origin.first + ux * stepPx) to (origin.second + uy * stepPx)
            if (!finite(end)) continue
            // A free direction is insufficient: every detour must still serve the observed goal.
            if (ux * ideal.first + uy * ideal.second <= 1e-6) continue
            val endDistance = dist(end, target)
            if (intent.away && endDistance <= distance || !intent.away && endDistance >= distance) continue
            if (wallProbe(origin, end, playerRadius)) continue
            val avoided = offset != 0.0
            return DirectedMovementDecision(
                vector = candidate,
                phase = if (avoided) MovementPhase.AVOID else intent.phase,
                reason = if (avoided) MovementReason.WALL_AVOIDED else intent.reason,
                goalKind = kind,
            )
        }
        if (lookAhead) {
            for (side in listOf(PI / 2.0, -PI / 2.0)) {
                val angle = idealAngle + side
                val ux = cos(angle); val uy = sin(angle)
                val mid = (origin.first + ux * stepPx) to (origin.second + uy * stepPx)
                if (!finite(mid) || wallProbe(origin, mid, playerRadius)) continue
                val nextIdeal = if (intent.away) (mid.first - target.first) to (mid.second - target.second)
                    else (target.first - mid.first) to (target.second - mid.second)
                val nextAngle = kotlin.math.atan2(nextIdeal.second, nextIdeal.first)
                for (turn in listOf(0.0, PI / 4.0, -PI / 4.0)) {
                    val end = (mid.first + cos(nextAngle + turn) * stepPx) to
                        (mid.second + sin(nextAngle + turn) * stepPx)
                    if (!finite(end)) continue
                    val endDistance = dist(end, target)
                    if (!finiteNumber(endDistance) ||
                        (if (intent.away) endDistance <= distance else endDistance >= distance)) continue
                    if (wallProbe(mid, end, playerRadius)) continue
                    return DirectedMovementDecision((ux * maxInput) to (uy * maxInput),
                        MovementPhase.AVOID, MovementReason.WALL_AVOIDED, kind)
                }
            }
        }
        return DirectedMovementDecision(
            vector = null, phase = MovementPhase.BLOCKED,
            reason = MovementReason.ALL_PATHS_BLOCKED, goalKind = kind,
        )
    }

    private data class Intent(val away: Boolean, val phase: MovementPhase, val reason: MovementReason)

    private fun orderedValid(
        origin: Pair<Double, Double>, candidates: List<Pair<Double, Double>>, maxTargetRange: Double,
    ): List<Pair<Double, Double>> = candidates.asSequence().filter { finite(it) }
        .filter { val d = dist(origin, it); finiteNumber(d) && d >= MIN_SEPARATION && d <= maxTargetRange }
        .distinct().sortedWith(compareBy<Pair<Double, Double>> { dist(origin, it) }.thenBy { it.first }.thenBy { it.second })
        // Bound collision-probe work even if a faulty detector produces a huge list.
        .take(16).toList()

    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
        hypot(a.first - b.first, a.second - b.second)

    private fun finite(p: Pair<Double, Double>): Boolean = finiteNumber(p.first) && finiteNumber(p.second)

    private fun finiteNumber(v: Double): Boolean = !v.isNaN() && !v.isInfinite()
}