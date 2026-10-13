package com.pyla.ai.engine

/**
 * Orders independent menu observations and positive gameplay observations by CAPTURE time.
 * A menu classifier's unknown answer is absence of menu evidence, not a gameplay denial.
 * It can neither grant Match nor renew the last positively observed player's lifetime.
 */
class GameStateAuthority(private val maxAgeMs: Long = 3_000L) {
    data class Snapshot(val state: String?, val capturedAtMs: Long)
    private val menuGate = StateObservationGate(maxAgeMs)
    private var state: String? = null
    private var evidenceAt = 0L
    private var decisionAt = 0L
    private var priority = 0 // gameplay=1, gameplay denial=2, positive menu=3 (ties fail closed)
    private var lastMenuAt = 0L

    @Synchronized fun reset() {
        state = null; evidenceAt = 0L; decisionAt = 0L; priority = 0; lastMenuAt = 0L
        menuGate.reset()
    }

    @Synchronized fun invalidate(nowMs: Long) {
        state = "unknown"; evidenceAt = 0L
        decisionAt = maxOf(decisionAt, nowMs); priority = 3
        menuGate.reset()
    }

    @Synchronized fun snapshot(nowMs: Long): Snapshot {
        if (state != null && state != "unknown" && !fresh(evidenceAt, nowMs)) {
            state = "unknown"; evidenceAt = 0L
        }
        return Snapshot(state, evidenceAt)
    }

    @Synchronized fun menu(observed: String, capturedAtMs: Long, nowMs: Long): Snapshot {
        snapshot(nowMs)
        if (!fresh(capturedAtMs, nowMs) || capturedAtMs < lastMenuAt) return snapshot(nowMs)
        lastMenuAt = capturedAtMs
        val known = observed != "unknown" && observed != "match" &&
            RuntimeState.fromClassifier(observed) != RuntimeState.UNKNOWN
        val verified = menuGate.observe(if (known) observed else "unknown", capturedAtMs, nowMs)
        if (known && accepts(capturedAtMs, 3)) {
            decisionAt = capturedAtMs; priority = 3
            // One positive menu already revokes combat, but navigation still needs its fresh pair.
            state = if (verified) observed else "unknown"
            evidenceAt = if (verified) capturedAtMs else 0L
        } else if (!known && state != "match" && capturedAtMs >= decisionAt) {
            // Absence of menu evidence revokes old navigation, but never erases or renews Match.
            // Keep the ordering watermark: this observation is not a gameplay denial/grant.
            state = "unknown"; evidenceAt = 0L
        } else if (state == null) state = "unknown"
        return snapshot(nowMs)
    }

    /** Call with true ONLY after the real GameplayEvidenceGate admitted this exact capture. */
    @Synchronized fun gameplay(admitted: Boolean, capturedAtMs: Long, nowMs: Long): Snapshot {
        snapshot(nowMs)
        val rank = if (admitted) 1 else 2
        if (fresh(capturedAtMs, nowMs) && accepts(capturedAtMs, rank)) {
            decisionAt = capturedAtMs; priority = rank
            state = if (admitted) "match" else "unknown"
            evidenceAt = if (admitted) capturedAtMs else 0L
        }
        return snapshot(nowMs)
    }

    private fun accepts(at: Long, rank: Int) = at > decisionAt || at == decisionAt && rank >= priority
    private fun fresh(at: Long, now: Long) = at > 0L && at <= now && now - at <= maxAgeMs
}
