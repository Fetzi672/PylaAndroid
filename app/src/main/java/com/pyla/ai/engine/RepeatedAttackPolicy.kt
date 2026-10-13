package com.pyla.ai.engine

/**
 * The basic-attack input the engine decided to produce (or to stop) for one gameplay frame.
 *
 * There is exactly one owner of the attack button: the engine. A `.pyla` playstyle can no longer
 * inject a second DOWN or race the charge timer, so this vocabulary is deliberately small.
 */
enum class BasicAttackAction { NONE, TAP, HOLD, RELEASE }

/**
 * Why no new attack input was produced. Fixed vocabulary - never coordinates, OCR text or ids, so
 * it is safe to log verbatim.
 */
enum class BasicAttackDenial {
    NONE,
    NO_TARGET,
    INVALID_FRAME,
    FUTURE_FRAME,
    STALE_FRAME,
    NO_FRESH_EVIDENCE,
    CADENCE,
    CHARGING,
}

data class BasicAttackDecision(
    val action: BasicAttackAction,
    val denial: BasicAttackDenial,
    val holding: Boolean,
) {
    val authorized: Boolean get() = action != BasicAttackAction.NONE
}

/**
 * Pure, clock-free state machine for a *repeated* mid-range basic attack.
 *
 * Owns basic-attack cadence independently of playstyle decisions. This is the requested behavior;
 * it does not establish the cause of an unrecorded phone run:
 *
 *  * a decision is a total function of (nowMs, frameCapturedAtMs, targetPresent, holdSeconds) plus
 *    this object's own state - there is no ambient clock, no RNG and no Android dependency, so the
 *    whole contract is directly unit-testable;
 *  * input is admitted only from *fresh* evidence: a present capture timestamp, not in the future,
 *    not older than [maxEvidenceAgeMs], and strictly newer than the capture that produced the last
 *    dispatched attack. That last rule is what stops one queued/stale frame from firing a burst and
 *    what stops a slow script from rejuvenating old evidence;
 *  * a fresh target never fires more than one attack per [minTapIntervalMs], so a long gap produces
 *    exactly one tap instead of a catch-up burst;
 *  * losing the target, a menu frame or a stale frame releases a held button exactly once and never
 *    issues a new DOWN while it is already down.
 *
 * Cadence deliberately survives a transient loss of target: re-acquiring an enemy must not reset
 * the clock and hand out a free burst.
 */
class RepeatedAttackPolicy(
    private val maxEvidenceAgeMs: Long = MAX_EVIDENCE_AGE_MS,
    private val minTapIntervalMs: Long = MIN_TAP_INTERVAL_MS,
    /** Continuous mode reuses STILL-FRESH gameplay evidence between captures, never beyond its TTL. */
    private val requireNewCapture: Boolean = true,
) {
    /** True while the attack button is logically down (a charge is running). */
    var holding: Boolean = false
        private set

    /** Timestamp the current charge started, or 0 when not charging. */
    var holdStartedAtMs: Long = 0L
        private set

    /** Capture timestamp of the last dispatched attack. Attacks need strictly newer evidence. */
    var lastAttackCaptureMs: Long = 0L
        private set

    /** Timestamp of the last dispatched attack (down or completed charge). */
    var lastAttackAtMs: Long = Long.MIN_VALUE
        private set

    /** Evals since construction; exposed so hosts can log/persist the repeated-attack rate. */
    var decisionCount: Long = 0L
        private set

    /**
     * Advance the machine by one gameplay frame.
     *
     * @param nowMs current monotonic time.
     * @param frameCapturedAtMs capture timestamp of the frame this decision is derived from.
     * @param targetPresent an eligible enemy exists in this frame (in engagement radius and not
     *        blocked by the existing wall check).
     * @param holdSeconds charge duration from the real brawler catalog; 0 (or non-finite/negative)
     *        means a plain tap, which is also the fallback for an unknown brawler.
     */
    fun step(
        nowMs: Long,
        frameCapturedAtMs: Long,
        targetPresent: Boolean,
        holdSeconds: Double,
    ): BasicAttackDecision {
        decisionCount++
        if (!targetPresent) return revoke(BasicAttackDenial.NO_TARGET)
        if (frameCapturedAtMs <= 0L) return revoke(BasicAttackDenial.INVALID_FRAME)
        if (frameCapturedAtMs > nowMs) return revoke(BasicAttackDenial.FUTURE_FRAME)
        if (nowMs - frameCapturedAtMs > maxEvidenceAgeMs) return revoke(BasicAttackDenial.STALE_FRAME)
        // Continuous mode may reuse still-fresh evidence, never an older capture.
        // Legacy callers retain one edge per distinct capture.
        if (frameCapturedAtMs < lastAttackCaptureMs ||
            (requireNewCapture && frameCapturedAtMs == lastAttackCaptureMs)) {
            return deny(BasicAttackDenial.NO_FRESH_EVIDENCE, holding)
        }
        val chargeMs = chargeDurationMs(holdSeconds)
        if (holding) {
            // A catalog that no longer describes a charge must never strand a held button down.
            if (chargeMs <= 0L || nowMs - holdStartedAtMs >= chargeMs) {
                finishCharge()
                lastAttackAtMs = nowMs
                lastAttackCaptureMs = frameCapturedAtMs
                return BasicAttackDecision(
                    BasicAttackAction.RELEASE, BasicAttackDenial.NONE, holding = false,
                )
            }
            return deny(BasicAttackDenial.CHARGING, holding = true)
        }
        if (lastAttackAtMs != Long.MIN_VALUE && nowMs - lastAttackAtMs < minTapIntervalMs) {
            return deny(BasicAttackDenial.CADENCE, holding = false)
        }
        lastAttackAtMs = nowMs
        lastAttackCaptureMs = frameCapturedAtMs
        if (chargeMs > 0L) {
            holding = true
            holdStartedAtMs = nowMs
            return BasicAttackDecision(BasicAttackAction.HOLD, BasicAttackDenial.NONE, holding = true)
        }
        return BasicAttackDecision(BasicAttackAction.TAP, BasicAttackDenial.NONE, holding = false)
    }

    /**
     * Give up the current engagement: release a held button exactly once and keep the cadence, so a
     * transient target dropout followed by re-acquisition cannot turn into a tap burst.
     */
    fun revoke(reason: BasicAttackDenial = BasicAttackDenial.NO_TARGET): BasicAttackDecision {
        decisionCount++
        if (!holding) return deny(reason, holding = false)
        finishCharge()
        return BasicAttackDecision(BasicAttackAction.RELEASE, reason, holding = false)
    }

    private fun finishCharge() {
        holding = false
        holdStartedAtMs = 0L
    }

    private fun deny(reason: BasicAttackDenial, holding: Boolean) =
        BasicAttackDecision(BasicAttackAction.NONE, reason, holding)

    private fun chargeDurationMs(holdSeconds: Double): Long {
        if (!holdSeconds.isFinite() || holdSeconds <= 0.0) return 0L
        val ms = holdSeconds * 1000.0
        if (!ms.isFinite() || ms <= 0.0) return 0L
        return if (ms >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else ms.toLong()
    }

    companion object {
        /** Same admission window as [Play.MAX_EVIDENCE_FRAME_AGE_MS]; kept here so the helper stands alone. */
        const val MAX_EVIDENCE_AGE_MS = 3_000L

        /** Minimum spacing between two dispatched attacks. */
        const val MIN_TAP_INTERVAL_MS = 300L

        /**
         * Engagement floor expressed in tiles. This is a *controller* choice - the range at which
         * the bot decides it is close enough to press - not a measured gameplay range, and it never
         * shortens a longer range the catalog actually claims.
         */
        const val ENGAGEMENT_TILE_FLOOR = 6.0

        /**
         * Minimum engagement radius in pixels: the longer of a valid catalog range and
         * [ENGAGEMENT_TILE_FLOOR] tiles. A longer real range is always preserved; an absent,
         * non-finite or non-positive catalog range falls back to the floor.
         */
        fun minEngagementRadiusPx(catalogRangePx: Double, tileSizePx: Double): Double {
            val catalog =
                if (catalogRangePx.isFinite() && catalogRangePx > 0.0) catalogRangePx else 0.0
            val floor =
                if (tileSizePx.isFinite() && tileSizePx > 0.0) ENGAGEMENT_TILE_FLOOR * tileSizePx else 0.0
            return maxOf(catalog, floor)
        }

        /**
         * Whether a target sits inside the engagement radius. Origin and target must both be finite:
         * a NaN distance never compares as "in range", so this rejects it explicitly rather than
         * letting a broken detection authorize an attack.
         */
        fun isWithinEngagement(
            originX: Double,
            originY: Double,
            targetX: Double,
            targetY: Double,
            radiusPx: Double,
        ): Boolean {
            if (!originX.isFinite() || !originY.isFinite()) return false
            if (!targetX.isFinite() || !targetY.isFinite()) return false
            if (!radiusPx.isFinite() || radiusPx <= 0.0) return false
            val dx = targetX - originX
            val dy = targetY - originY
            val distance = kotlin.math.hypot(dx, dy)
            return distance.isFinite() && distance <= radiusPx
        }
    }
}