package com.pyla.ai.engine

/**
 * Pure, Android/OpenCV-free lifecycle gate for the process-global "Chaosdrop face -> reward-coins
 * dismissal" continuation.
 *
 * Observed Chaosdrop artwork authorises dismissal of the immediately following reward-coins screen
 * for a bounded TTL. That continuation is *evidence*, not a gesture, so it must be bound to the same
 * lifecycle boundary as the action that consumes it (the `star_drop_regular` navigation). A Pause, a
 * Stop or a session replacement must invalidate the evidence, and recognition already in flight when
 * that invalidation lands must not be able to re-arm it afterwards.
 *
 * The gate therefore keeps a monotonic [generation]. Callers capture [observationGeneration] BEFORE
 * the frame is captured, thread that value through recognition, and:
 *  * arm the face evidence via [arm], which is refused unless the captured generation is still
 *    current, so an in-flight recognition whose observation predates an invalidation cannot re-arm,
 *  * consult the coins continuation via [continues], which requires the SAME captured generation,
 *    the SAME geometry identity, positive freshness on one clock and the unchanged TTL.
 *
 * [invalidate] drops the evidence and advances the generation. Advancing is monotonic, so an
 * invalidation can never permanently poison a later, genuinely new observation: a fresh observation
 * captured after the advancing call arms normally (see `freshObservationAfterInvalidationRecovers`).
 * This also stops a cross-owner teardown from poisoning a newer session's own evidence, because the
 * newer session captured a strictly newer generation.
 */
internal class ChaosContinuationGate(private val ttlMs: Long = 5_000L) {

    /** The face observation that arms the continuation, bound to the lifecycle generation. */
    data class Evidence(val generation: Long, val atMs: Long, val geometry: Long)

    private var generation = 0L
    private var evidence: Evidence? = null
    private var invalidatedAtMs = 0L

    /**
     * Lifecycle generation to capture BEFORE the frame capture whose observation may arm evidence.
     * A Pause/Stop/session replacement between this read and [arm] is what makes the write fail.
     */
    @Synchronized fun observationGeneration(): Long = generation

    /** Pause/Stop/session replacement/positive-menu revocation: drop evidence, advance the epoch. */
    @Synchronized fun invalidate(nowMs: Long = 0L, expectedGeneration: Long? = null) {
        if (expectedGeneration != null && expectedGeneration != generation) return
        generation += 1L
        evidence = null
        invalidatedAtMs = maxOf(invalidatedAtMs, nowMs)
    }

    /**
     * Positive face evidence observed for [observedGeneration]. Arms the coins continuation and
     * returns `true` only while that captured generation is still current; an observation that
     * straddled an invalidation is refused and never re-arms.
     */
    @Synchronized fun arm(observedGeneration: Long, nowMs: Long, geometry: Long, capturedAtMs: Long = nowMs): Boolean {
        if (observedGeneration != generation || !freshCapture(capturedAtMs, nowMs)) return false
        // Re-reading the capture cache cannot renew the original face timestamp or roll it back.
        if (evidence?.let { capturedAtMs < it.atMs } == true) return false
        evidence = Evidence(generation, capturedAtMs, geometry)
        return true
    }

    /**
     * Coins continuation for an observation captured at [observedGeneration]. Requires the same
     * generation that armed the face evidence, the same geometry identity, and positive freshness
     * (`atMs <= nowMs <= atMs + ttlMs`) on one clock.
     */
    @Synchronized fun continues(observedGeneration: Long, nowMs: Long, geometry: Long, capturedAtMs: Long = nowMs): Boolean {
        if (observedGeneration != generation || !freshCapture(capturedAtMs, nowMs)) return false
        val e = evidence ?: return false
        if (e.generation != generation) return false
        if (capturedAtMs < e.atMs || nowMs < e.atMs) return false
        if (nowMs - e.atMs > ttlMs) return false
        return e.geometry == geometry
    }

    private fun freshCapture(capturedAtMs: Long, nowMs: Long): Boolean =
        capturedAtMs > invalidatedAtMs && capturedAtMs <= nowMs && nowMs - capturedAtMs <= 3_000L
}
