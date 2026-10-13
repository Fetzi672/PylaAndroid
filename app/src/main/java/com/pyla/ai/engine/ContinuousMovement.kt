package com.pyla.ai.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class ContinuousMovementMode { ROAM, APPROACH, RETREAT, STRAFE, FOLLOW, DETOUR, ESCAPE, DENIED }

data class ContinuousMovementDecision(val vector: Pair<Double, Double>?, val mode: ContinuousMovementMode)

/**
 * Stateful but pure movement intent policy: explicit time, no random or platform dependencies.
 * wallProbe returns true for a blocked swept segment. DETOUR certifies only the emitted segment,
 * not a route to a target. ESCAPE deliberately does NOT certify wall clearance or physical progress.
 * The caller must reset on run, geometry, or lifecycle changes and supply scene-level progress;
 * stable screen coordinates alone are never evidence of a stuck player.
 *
 * Work is bounded: the first 16 detections total (enemies first), at most 8 segment probes.
 * Coordinates are bounded to +/-1e9 and positive geometry to 1e6 (radius may be zero).
 * Invalid detections are ignored; invalid player/geometry/time denies and clears intent history.
 * Replayed captures preserve the last heading without advancing timers; they recheck its segment.
 * After denial, replay cannot resurrect the cleared intent. Only reset clears the capture watermark.
 */
class ContinuousMovementPlanner {
    private var lastCapture = 0L
    private var lastNow = 0L
    private var last = denied()
    private var roamStart: Long? = null
    private var roamAngle = 0.0
    private var detourStart: Long? = null
    private var detourAngle = 0.0
    private var escapeStart: Long? = null
    private var escapeAngle = 0.0
    private var escapeForNonprogress = false
    private var noProgressStart: Long? = null
    private var side = 1.0

    @Synchronized
    fun reset() {
        lastCapture = 0L
        lastNow = 0L
        clearIntent()
    }

    private fun clearIntent() {
        last = denied()
        roamStart = null
        roamAngle = 0.0
        detourStart = null
        detourAngle = 0.0
        escapeStart = null
        escapeAngle = 0.0
        escapeForNonprogress = false
        noProgressStart = null
        side = 1.0
    }

    @Synchronized
    fun plan(
        player: Pair<Double, Double>?,
        enemies: List<Pair<Double, Double>>,
        allies: List<Pair<Double, Double>>,
        attackRange: Double,
        playerRadius: Double,
        stepPx: Double,
        capturedAtMs: Long,
        nowMs: Long,
        progressObserved: Boolean? = null,
        wallProbe: (Pair<Double, Double>, Pair<Double, Double>, Double) -> Boolean,
    ): ContinuousMovementDecision {
        if (player == null || !pointValid(player) || !positiveGeometry(attackRange) ||
            !positiveGeometry(stepPx) || !playerRadius.isFinite() || playerRadius < 0.0 ||
            playerRadius > 1e6 || capturedAtMs <= 0L || nowMs <= 0L || capturedAtMs > nowMs ||
            nowMs - capturedAtMs > 3000L || nowMs < lastNow
        ) {
            clearIntent()
            return denied()
        }
        fun blocked(angle: Double): Boolean = wallProbe(player,
            (player.first + cos(angle) * stepPx) to (player.second + sin(angle) * stepPx), playerRadius)
        if (capturedAtMs <= lastCapture) {
            val vector = last.vector ?: return denied()
            // Never advance state, change heading, or authorize an unchecked DETOUR on replay.
            return if (last.mode != ContinuousMovementMode.ESCAPE && blocked(atan2(vector.second, vector.first)))
                last.copy(mode = ContinuousMovementMode.ESCAPE) else last
        }
        lastCapture = capturedAtMs
        lastNow = nowMs

        if (roamStart == null) roamStart = nowMs
        if (nowMs - roamStart!! >= 1200L) {
            roamAngle = turn(roamAngle + PI / 2.0)
            roamStart = nowMs
        }
        // Bound inspection, not just the sorted result size. Coincident targets have a defined axis.
        val enemyCount = minOf(16, enemies.size)
        fun nearest(points: List<Pair<Double, Double>>, count: Int): Pair<Double, Double>? =
            points.asSequence().take(count).filter(::pointValid).minWithOrNull(
                compareBy<Pair<Double, Double>> { hypot(it.first - player.first, it.second - player.second) }
                    .thenBy { it.first }.thenBy { it.second })
        val enemy = nearest(enemies, enemyCount)
        val ally = if (enemy == null) nearest(allies, 16 - enemyCount) else null
        val target = enemy ?: ally
        val distance = target?.let { hypot(it.first - player.first, it.second - player.second) } ?: 0.0
        val bearing = target?.let { atan2(it.second - player.second, it.first - player.first) } ?: roamAngle
        val mode: ContinuousMovementMode
        val goal: Double
        when {
            target == null -> { mode = ContinuousMovementMode.ROAM; goal = roamAngle }
            enemy != null && distance > attackRange -> { mode = ContinuousMovementMode.APPROACH; goal = bearing }
            enemy != null && distance < attackRange * 0.8 -> { mode = ContinuousMovementMode.RETREAT; goal = bearing + PI }
            enemy != null -> { mode = ContinuousMovementMode.STRAFE; goal = bearing + side * PI / 2.0 }
            distance > attackRange * 1.25 -> { mode = ContinuousMovementMode.FOLLOW; goal = bearing }
            else -> { mode = ContinuousMovementMode.STRAFE; goal = bearing + side * PI / 2.0 }
        }
        when (progressObserved) {
            false -> if (noProgressStart == null) noProgressStart = nowMs
            true, null -> noProgressStart = null
        }
        val stuck = noProgressStart?.let { nowMs - it >= 2000L } == true
        if (stuck) {
            side = -side
            escapeAngle = turn((last.vector?.let { atan2(it.second, it.first) } ?: goal) + side * PI / 2.0)
            escapeStart = nowMs
            escapeForNonprogress = true
            noProgressStart = nowMs
            detourStart = null
        }
        if (progressObserved == true && escapeForNonprogress) escapeStart = null
        if (escapeStart != null && nowMs - escapeStart!! >= 600L) {
            escapeAngle = turn(escapeAngle + PI / 2.0)
            escapeStart = nowMs
        }
        if (detourStart != null && nowMs - detourStart!! >= 1200L) detourStart = null

        val preferred = when {
            escapeStart != null -> escapeAngle
            detourStart != null -> detourAngle
            else -> goal
        }
        // Eight unique directions. Persistent legs win until expiry, even when the goal moves.
        val offsets = doubleArrayOf(0.0, side * PI / 2.0, -side * PI / 2.0, PI,
            side * PI / 4.0, -side * PI / 4.0, side * 3.0 * PI / 4.0, -side * 3.0 * PI / 4.0)
        for ((index, offset) in offsets.withIndex()) {
            val angle = turn(preferred + offset)
            if (blocked(angle)) continue
            val chosenMode = when {
                escapeStart != null -> ContinuousMovementMode.ESCAPE
                index != 0 || detourStart != null -> ContinuousMovementMode.DETOUR
                else -> mode
            }
            if (chosenMode == ContinuousMovementMode.DETOUR) {
                if (detourStart == null) detourStart = nowMs
                detourAngle = angle
            }
            // A clear escape attempt is still a bounded leg, not a permanent replacement goal.
            if (chosenMode == ContinuousMovementMode.ESCAPE && !stuck) escapeStart = null
            last = decision(angle, chosenMode)
            return last
        }
        detourStart = null
        escapeForNonprogress = false
        if (escapeStart == null) {
            escapeAngle = turn(preferred)
            escapeStart = nowMs
        }
        last = decision(escapeAngle, ContinuousMovementMode.ESCAPE)
        return last
    }

    private fun pointValid(p: Pair<Double, Double>) =
        p.first.isFinite() && p.second.isFinite() && abs(p.first) <= 1e9 && abs(p.second) <= 1e9
    private fun positiveGeometry(v: Double) = v.isFinite() && v > 0.0 && v <= 1e6
    private fun turn(angle: Double) = angle % (2.0 * PI)
    private fun decision(angle: Double, mode: ContinuousMovementMode): ContinuousMovementDecision {
        // A little below the hard bound avoids floating-point norm rounding above 75.
        return ContinuousMovementDecision((cos(angle) * 74.0) to (sin(angle) * 74.0), mode)
    }
    private fun denied() = ContinuousMovementDecision(null, ContinuousMovementMode.DENIED)
}
