package com.pyla.ai.engine

/** Fixed diagnostics vocabulary for gameplay evidence admission. Never coordinates/OCR/identifiers. */
enum class GameEvidenceReason {
    ADMITTED,
    STATE_MENU_REVOKED,
    STALE_FRAME,
    FUTURE_FRAME,
    PLAYER_MISSING,
    PLAYER_AMBIGUOUS,
    PLAYER_BOX_INVALID,
    SAME_FRAME_REPEATED,
    AWAITING_SECOND_OBSERVATION,
}

data class GameEvidenceDecision(
    val admitted: Boolean,
    val reason: GameEvidenceReason,
    val observations: Int,
    val needsFreshFrames: Int,
)

/**
 * Gameplay admission gate.
 *
 * A classifier that reports a screen is *not* proof that gameplay is running, and neither is the
 * absence of a menu match. Admission therefore requires positive evidence taken from the current
 * frame:
 *  1. a fresh capture timestamp (present, not in the future, within [maxFrameAgeMs] of now),
 *  2. exactly one valid player detection box (missing or ambiguous counts as no evidence),
 *  3. two such observations on two *distinct* capture timestamps,
 *  4. a state that is not a known menu screen (an "unknown" classifier is only allowed to continue
 *     the observation, and the caller must confirm the current frame itself is not a known menu).
 *
 * Any known menu screen revokes admission immediately. The gate holds no time source of its own so
 * every decision is a total function of its arguments.
 */
class GameplayEvidenceGate(
    private val maxFrameAgeMs: Long = 3_000L,
    private val requiredObservations: Int = 2,
) {
    private var lastPlayerFrameMs: Long = 0L
    private var observations: Int = 0

    val observationCount: Int get() = observations
    val lastEvidenceFrameMs: Long get() = lastPlayerFrameMs

    fun reset() {
        lastPlayerFrameMs = 0L
        observations = 0
    }

    /** A known menu screen is positive evidence that gameplay is not running. */
    fun observeMenuScreen(): GameEvidenceDecision {
        reset()
        return GameEvidenceDecision(false, GameEvidenceReason.STATE_MENU_REVOKED, 0, requiredObservations)
    }

    fun observe(
        statePermitted: Boolean,
        playerBoxes: Int,
        boxValid: Boolean,
        capturedAtMs: Long,
        nowMs: Long,
    ): GameEvidenceDecision {
        if (!statePermitted) return observeMenuScreen()
        if (capturedAtMs <= 0L) return deny(GameEvidenceReason.STALE_FRAME)
        if (capturedAtMs > nowMs) return deny(GameEvidenceReason.FUTURE_FRAME)
        if (nowMs - capturedAtMs > maxFrameAgeMs) return deny(GameEvidenceReason.STALE_FRAME)
        if (playerBoxes <= 0) return deny(GameEvidenceReason.PLAYER_MISSING)
        if (playerBoxes > 1) return deny(GameEvidenceReason.PLAYER_AMBIGUOUS)
        if (!boxValid) return deny(GameEvidenceReason.PLAYER_BOX_INVALID)
        if (capturedAtMs <= lastPlayerFrameMs) {
            return GameEvidenceDecision(
                false, GameEvidenceReason.SAME_FRAME_REPEATED, observations,
                (requiredObservations - observations).coerceAtLeast(0),
            )
        }
        if (lastPlayerFrameMs > 0L && nowMs - lastPlayerFrameMs > maxFrameAgeMs) reset()
        lastPlayerFrameMs = capturedAtMs
        observations = (observations + 1).coerceAtMost(requiredObservations)
        val needed = (requiredObservations - observations).coerceAtLeast(0)
        if (observations >= requiredObservations) {
            return GameEvidenceDecision(true, GameEvidenceReason.ADMITTED, observations, 0)
        }
        return GameEvidenceDecision(false, GameEvidenceReason.AWAITING_SECOND_OBSERVATION, observations, needed)
    }

    private fun deny(reason: GameEvidenceReason): GameEvidenceDecision {
        // Any non-positive observation invalidates the accumulated pair: the second observation
        // must be consecutive, so a dropout cannot be laundered into admission later.
        reset()
        return GameEvidenceDecision(false, reason, 0, requiredObservations)
    }

    companion object {
        /**
         * A detection box is valid evidence only when it is finite, non-degenerate and actually
         * inside the frame it was detected in.
         */
        fun validPlayerBox(box: FloatArray?, frameWidth: Int, frameHeight: Int): Boolean {
            if (box == null || box.size < 4) return false
            val x1 = box[0].toDouble(); val y1 = box[1].toDouble()
            val x2 = box[2].toDouble(); val y2 = box[3].toDouble()
            if (!isFinite(x1) || !isFinite(y1) || !isFinite(x2) || !isFinite(y2)) return false
            if (x2 <= x1 || y2 <= y1) return false
            if (frameWidth <= 0 || frameHeight <= 0) return false
            return x1 >= 0.0 && y1 >= 0.0 && x2 <= frameWidth.toDouble() && y2 <= frameHeight.toDouble()
        }

        private fun isFinite(v: Double): Boolean = !v.isNaN() && !v.isInfinite()
    }
}