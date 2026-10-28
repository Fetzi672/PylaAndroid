package com.pyla.ai.engine

/** Single-frame pixel outcome only; a point is NOT temporal/input authorization. */
enum class AttackRecognitionOutcome {
    NOT_ANALYZED, INVALID_FRAME, NOT_FOUND, OPAQUE_AMBIGUOUS,
    TRANSLUCENT_EXHAUSTED, TRANSLUCENT_AMBIGUOUS, CROSS_PATH_CONFLICT,
    OPAQUE_POINT, TRANSLUCENT_POINT, BOTH_SAME_CONTROL,
    // Crosshair-template evidence (TEST-build fallback path). TEMPLATE_POINT is its own geometry
    // source and is never reported as a translucent/opaque point.
    TEMPLATE_POINT, BOTH_TEMPLATE_SAME_CONTROL, TEMPLATE_AMBIGUOUS, TEMPLATE_EXHAUSTED, TEMPLATE_ERROR,
    // Explicitly persisted calibration; NOT a current visual match or gameplay authority.
    SAVED_CALIBRATION_POINT,
}

/**
 * Bounded, immutable phase evidence for ONE completed attack-recognition call.
 *
 * Published by [AttackButtonDetector.lastTrace] and consumed by parent telemetry. It carries
 * only monotonic-clock durations and counters: no image bytes, no pixel values, no configured
 * thresholds, no log text. It grants no input authority and never influences a decision.
 *
 *  - [opaqueNanos]      time spent in the opaque red-component path
 *  - [translucentNanos] time spent in the translucent warm-inner/concentric-ridge path
 *  - [coarseNanos]      time spent in the translucent coarse seed scan (sort/dedupe excluded)
 *  - [refineNanos]      time spent refining retained translucent seeds
 *  - [coarseCandidates] coarse seeds retained by the scan before dedupe
 *  - [refinementSeeds]  deduplicated seeds actually refined
 *  - [exhausted]        true when a bounded search budget was exhausted (uncertainty, never a hit)
 *
 * Unexecuted phases retain `0`/`false`, never estimated. The bounded translucent phase runs after
 * an unambiguous opaque analysis; opaque ambiguity stops the recognizer without executing it.
 */
class AttackRecognitionTrace(
    val opaqueNanos: Long,
    val translucentNanos: Long,
    val coarseNanos: Long,
    val refineNanos: Long,
    val coarseCandidates: Int,
    val refinementSeeds: Int,
    val exhausted: Boolean,
    val outcome: AttackRecognitionOutcome = AttackRecognitionOutcome.NOT_ANALYZED,
    val templateNanos: Long = 0L,
    val templateCandidates: Int = 0,
    val templatePeakScore: Float = 0f,
) {
    val totalNanos: Long get() = opaqueNanos + translucentNanos + templateNanos

    override fun toString(): String =
        "AttackRecognitionTrace(opaqueNanos=$opaqueNanos, translucentNanos=$translucentNanos, " +
            "coarseNanos=$coarseNanos, refineNanos=$refineNanos, coarseCandidates=$coarseCandidates, " +
            "refinementSeeds=$refinementSeeds, exhausted=$exhausted, outcome=$outcome, " +
            "templateNanos=$templateNanos, templateCandidates=$templateCandidates, " +
            "templatePeakScore=$templatePeakScore)"
}

/**
 * Mutable, single-call collector filled by the recognizer and frozen into an
 * [AttackRecognitionTrace] by the detector bridge. Never shared between threads: one instance
 * per recognition call, read only after that call completed.
 */
internal class AttackRecognitionMetrics {
    var opaqueNanos: Long = 0L
    var translucentNanos: Long = 0L
    var coarseNanos: Long = 0L
    var refineNanos: Long = 0L
    var coarseCandidates: Int = 0
    var refinementSeeds: Int = 0
    var exhausted: Boolean = false
    var outcome: AttackRecognitionOutcome = AttackRecognitionOutcome.NOT_ANALYZED
    var templateNanos: Long = 0L
    var templateCandidates: Int = 0
    var templatePeakScore: Float = 0f

    fun toTrace(): AttackRecognitionTrace = AttackRecognitionTrace(
        opaqueNanos = opaqueNanos,
        translucentNanos = translucentNanos,
        coarseNanos = coarseNanos,
        refineNanos = refineNanos,
        coarseCandidates = coarseCandidates,
        refinementSeeds = refinementSeeds,
        exhausted = exhausted,
        outcome = outcome,
        templateNanos = templateNanos,
        templateCandidates = templateCandidates,
        templatePeakScore = templatePeakScore,
    )
}
