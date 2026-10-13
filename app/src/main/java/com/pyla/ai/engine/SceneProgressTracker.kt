package com.pyla.ai.engine

/** Cheap scene-change evidence, not a claim of physical displacement.
 * The player is often screen-centred by the camera: unchanged player coordinates alone
 * must not be labelled stuck. The caller samples world pixels, excluding the player/HUD.
 * Animations may look like progress; the movement planner also bounds each roaming leg.
 */
class SceneProgressTracker {
    private var previous: IntArray? = null
    private var capturedAt = 0L

    @Synchronized fun reset() { previous = null; capturedAt = 0L }

    @Synchronized fun observe(samples: IntArray, capturedAtMs: Long, nowMs: Long): Boolean? {
        if (capturedAtMs <= 0L || capturedAtMs > nowMs || nowMs - capturedAtMs > 3_000L ||
            samples.size !in 16..128 || samples.any { it !in 0..255 }) {
            reset(); return null
        }
        if (capturedAtMs <= capturedAt) return null
        val old = previous
        previous = samples.copyOf()
        capturedAt = capturedAtMs
        if (old == null || old.size != samples.size) return null
        // Reject tiny colour noise and an isolated projectile/animated pixel as movement evidence.
        var changed = 0
        for (i in samples.indices) if (kotlin.math.abs(samples[i] - old[i]) >= 8) changed++
        return changed * 5 >= samples.size
    }
}
