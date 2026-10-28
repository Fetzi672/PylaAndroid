package com.pyla.ai.engine

import kotlin.math.hypot
import kotlin.math.max

/** Fresh visual authority for one stationary auto-aim control. No clocks or Android calls inside. */
class AttackButtonEvidenceGate(private val maxAgeMs: Long) {
    data class Evidence(val point: AttackButtonPoint, val capturedAtMs: Long, val geometry: Long, val revision: Long)
    private var pending: Evidence? = null
    private var accepted: Evidence? = null
    private var revision = 0L
    private var anchor: AttackButtonPoint? = null

    /** Observer-only result of the last observation. Never read by an authority decision. */
    enum class Observation {
        RESET, MISSING_POINT, INVALID_POINT, INVALID_CAPTURE_TIME, FRAME_STALE,
        WAITING_SECOND_OBSERVATION, GEOMETRY_CHANGED, PRIOR_EXPIRED,
        CENTER_CHANGED, RADIUS_CHANGED, OUT_OF_ORDER, REPEATED_FRAME, CONFIRMED,
    }
    private var observation = Observation.RESET
    @Synchronized fun lastObservation(): Observation = observation

    @Synchronized fun reset() {
        pending = null; accepted = null; anchor = null; revision++
        observation = Observation.RESET
    }

    @Synchronized fun current(nowMs: Long, geometry: Long): Evidence? = accepted?.takeIf {
        it.geometry == geometry && fresh(it.capturedAtMs, nowMs)
    }

    @Synchronized fun observe(point: AttackButtonPoint?, capturedAtMs: Long, nowMs: Long, geometry: Long): Evidence? {
        if (point == null || !valid(point) || !fresh(capturedAtMs, nowMs)) {
            reset()
            observation = when {
                point == null -> Observation.MISSING_POINT
                !valid(point) -> Observation.INVALID_POINT
                capturedAtMs <= 0L || capturedAtMs > nowMs -> Observation.INVALID_CAPTURE_TIME
                else -> Observation.FRAME_STALE
            }
            return null
        }
        val prior = accepted ?: pending
        if (prior == null || prior.geometry != geometry || !fresh(prior.capturedAtMs, nowMs) || !consistent(anchor ?: prior.point, point)) {
            // Classify only an already-taken denial branch. Original predicates/order are unchanged.
            val reference = anchor ?: prior?.point
            val reason = when {
                prior == null -> Observation.WAITING_SECOND_OBSERVATION
                prior.geometry != geometry -> Observation.GEOMETRY_CHANGED
                !fresh(prior.capturedAtMs, nowMs) -> Observation.PRIOR_EXPIRED
                reference != null && hypot((reference.x - point.x).toDouble(), (reference.y - point.y).toDouble()) >
                    max(2.0, minOf(reference.radius, point.radius) * 0.10) -> Observation.CENTER_CHANGED
                else -> Observation.RADIUS_CHANGED
            }
            reset(); anchor = point; pending = Evidence(point, capturedAtMs, geometry, revision)
            observation = reason
            return null
        }
        if (capturedAtMs < prior.capturedAtMs) { reset(); observation = Observation.OUT_OF_ORDER; return null }
        if (capturedAtMs == prior.capturedAtMs) { observation = Observation.REPEATED_FRAME; return accepted }
        val value = Evidence(point, capturedAtMs, geometry, revision)
        accepted = value; pending = value
        observation = Observation.CONFIRMED
        return value
    }

    /** A changed center must be reconfirmed, never used to drag an already held pointer. */
    private fun consistent(a: AttackButtonPoint, b: AttackButtonPoint): Boolean =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) <= max(2.0, minOf(a.radius, b.radius) * 0.10) &&
            max(a.radius, b.radius) <= minOf(a.radius, b.radius) * 1.2f
    private fun valid(p: AttackButtonPoint) = p.x.isFinite() && p.y.isFinite() && p.radius.isFinite() &&
        p.x >= 0f && p.y >= 0f && p.radius > 0f
    private fun fresh(capturedAtMs: Long, nowMs: Long) = capturedAtMs > 0L && capturedAtMs <= nowMs &&
        nowMs - capturedAtMs <= maxAgeMs
}
