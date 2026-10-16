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

    @Synchronized fun reset() { pending = null; accepted = null; anchor = null; revision++ }

    @Synchronized fun current(nowMs: Long, geometry: Long): Evidence? = accepted?.takeIf {
        it.geometry == geometry && fresh(it.capturedAtMs, nowMs)
    }

    @Synchronized fun observe(point: AttackButtonPoint?, capturedAtMs: Long, nowMs: Long, geometry: Long): Evidence? {
        if (point == null || !valid(point) || !fresh(capturedAtMs, nowMs)) { reset(); return null }
        val prior = accepted ?: pending
        if (prior == null || prior.geometry != geometry || !fresh(prior.capturedAtMs, nowMs) || !consistent(anchor ?: prior.point, point)) {
            reset(); anchor = point; pending = Evidence(point, capturedAtMs, geometry, revision); return null
        }
        if (capturedAtMs < prior.capturedAtMs) { reset(); return null }
        if (capturedAtMs == prior.capturedAtMs) return accepted
        val value = Evidence(point, capturedAtMs, geometry, revision)
        accepted = value; pending = value
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
