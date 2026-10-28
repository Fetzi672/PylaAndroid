package com.pyla.ai.engine

/**
 * Pure merge of the two-pixel-path baseline outcome with the bounded crosshair-template result.
 *
 * Kept free of OpenCV and of any timing/state so the production merge can be exercised on a plain
 * JVM. It grants no input authority: at best it reports one observed geometry plus an explicit
 * outcome, and every doubtful combination denies (null point).
 *
 * Rules:
 *  - OPAQUE_AMBIGUOUS / TRANSLUCENT_EXHAUSTED / TRANSLUCENT_AMBIGUOUS / CROSS_PATH_CONFLICT /
 *    INVALID_FRAME / NOT_ANALYZED veto the template path outright. Baseline ambiguity or a
 *    malformed frame is never "resolved" by a weaker recognizer.
 *  - baseline point present, template NONE: the baseline point is retained unchanged (the template
 *    is additive evidence, not a replacement).
 *  - baseline point present, template SINGLE: agreement uses the same predicate as the existing
 *    translucent path; disagreement is CROSS_PATH_CONFLICT.
 *  - baseline point absent, template SINGLE: the template point is reported as TEMPLATE_POINT and
 *    is never mislabelled as a translucent or opaque observation.
 *  - template AMBIGUOUS / EXHAUSTED / ERROR: deny with a distinct bounded outcome so the pixel
 *    trace states why nothing was reported. A malformed SINGLE result is treated as ERROR.
 */
internal object TemplateAttackControlMerge {

    /** One decision: the observable geometry (or null) and the outcome that must be traced. */
    data class Decision(val point: AttackButtonPoint?, val outcome: AttackRecognitionOutcome)

    private val VETO = setOf(
        AttackRecognitionOutcome.NOT_ANALYZED,
        AttackRecognitionOutcome.INVALID_FRAME,
        AttackRecognitionOutcome.OPAQUE_AMBIGUOUS,
        AttackRecognitionOutcome.TRANSLUCENT_EXHAUSTED,
        AttackRecognitionOutcome.TRANSLUCENT_AMBIGUOUS,
        AttackRecognitionOutcome.CROSS_PATH_CONFLICT,
    )

    /** True when the baseline already reported uncertainty, so the template must not run at all. */
    fun vetoesTemplate(baselineOutcome: AttackRecognitionOutcome): Boolean = baselineOutcome in VETO

    /** Arm's-length same-control check matching [TranslucentAttackControl.sameControl]. */
    fun sameControl(a: AttackButtonPoint, b: AttackButtonPoint): Boolean {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        return Math.hypot(dx, dy) <= Math.max(2.0, Math.min(a.radius, b.radius) * 0.20) &&
            Math.max(a.radius, b.radius) <= Math.min(a.radius, b.radius) * 1.25f
    }

    fun decide(
        baselineOutcome: AttackRecognitionOutcome,
        baselinePoint: AttackButtonPoint?,
        template: TemplateControlResult,
        sameControl: (AttackButtonPoint, AttackButtonPoint) -> Boolean = ::sameControl,
    ): Decision {
        if (vetoesTemplate(baselineOutcome)) return Decision(null, baselineOutcome)
        // A "found" baseline outcome without a point is internally inconsistent evidence.
        if (baselineOutcome != AttackRecognitionOutcome.NOT_FOUND && baselinePoint == null) {
            return Decision(null, baselineOutcome)
        }
        if (baselinePoint != null) {
            return when (template.status) {
                TemplateControlStatus.NONE -> Decision(baselinePoint, baselineOutcome)
                TemplateControlStatus.SINGLE -> {
                    val match = templatePoint(template) ?: return Decision(null, AttackRecognitionOutcome.TEMPLATE_ERROR)
                    if (sameControl(baselinePoint, match)) {
                        Decision(baselinePoint, AttackRecognitionOutcome.BOTH_TEMPLATE_SAME_CONTROL)
                    } else {
                        Decision(null, AttackRecognitionOutcome.CROSS_PATH_CONFLICT)
                    }
                }
                TemplateControlStatus.AMBIGUOUS -> Decision(null, AttackRecognitionOutcome.TEMPLATE_AMBIGUOUS)
                TemplateControlStatus.EXHAUSTED -> Decision(null, AttackRecognitionOutcome.TEMPLATE_EXHAUSTED)
                TemplateControlStatus.ERROR -> Decision(null, AttackRecognitionOutcome.TEMPLATE_ERROR)
            }
        }
        return when (template.status) {
            TemplateControlStatus.NONE -> Decision(null, AttackRecognitionOutcome.NOT_FOUND)
            TemplateControlStatus.SINGLE ->
                Decision(templatePoint(template) ?: return Decision(null, AttackRecognitionOutcome.TEMPLATE_ERROR),
                    AttackRecognitionOutcome.TEMPLATE_POINT)
            TemplateControlStatus.AMBIGUOUS -> Decision(null, AttackRecognitionOutcome.TEMPLATE_AMBIGUOUS)
            TemplateControlStatus.EXHAUSTED -> Decision(null, AttackRecognitionOutcome.TEMPLATE_EXHAUSTED)
            TemplateControlStatus.ERROR -> Decision(null, AttackRecognitionOutcome.TEMPLATE_ERROR)
        }
    }

    private fun templatePoint(template: TemplateControlResult): AttackButtonPoint? {
        val candidate = template.candidate ?: return null
        if (!candidate.usable) return null
        return AttackButtonPoint(candidate.x, candidate.y, candidate.radius)
    }
}
