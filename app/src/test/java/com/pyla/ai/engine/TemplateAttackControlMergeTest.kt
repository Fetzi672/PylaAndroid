package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Contract of the pure template/baseline merge and of the attack-only detector entry point.
 *
 * These are host tests: no Android native library is loaded. The template recognizer is injected
 * through the internal seam, never through a runtime platform check, and the old
 * [AttackButtonDetector.detect] path is asserted to carry no template work at all.
 */
class TemplateAttackControlMergeTest {

    private val w = 320
    private val h = 180

    private fun background() = ByteArray(w * h * 3).also { a ->
        for (i in 0 until w * h) {
            a[i * 3] = 30; a[i * 3 + 1] = 90; a[i * 3 + 2] = 40
        }
    }

    private fun pixel(a: ByteArray, x: Int, y: Int, r: Int, g: Int, b: Int) {
        val i = (y * w + x) * 3
        a[i] = r.toByte(); a[i + 1] = g.toByte(); a[i + 2] = b.toByte()
    }

    private fun ring(a: ByteArray, cx: Int, cy: Int, radius: Int = 20) {
        for (y in 0 until h) for (x in 0 until w) {
            val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (d <= radius * radius) {
                if (d < 36) pixel(a, x, y, 220, 195, 200) else pixel(a, x, y, 160, 12, 20)
            }
        }
    }

    /** API-real Mat subclass: no Android JNI in a host unit test. */
    private class RasterMat(
        private val rgb: ByteArray = ByteArray(0),
        private val rowCount: Int = 0,
        private val columnCount: Int = 0,
        private val channelCount: Int = 3,
    ) : Mat(1L) {
        override fun empty(): Boolean = rowCount == 0 || columnCount == 0
        override fun channels(): Int = channelCount
        override fun depth(): Int = CvType.CV_8U
        override fun rows(): Int = rowCount
        override fun cols(): Int = columnCount
        override fun get(row: Int, col: Int, target: ByteArray): Int {
            require(row == 0 && col == 0)
            val count = minOf(target.size, rgb.size)
            rgb.copyInto(target, endIndex = count)
            return count
        }
        protected override fun finalize() = Unit
    }

    private fun mat(rgb: ByteArray): Mat = RasterMat(rgb, h, w)

    private fun candidate(x: Float, y: Float, radius: Float = 35f, score: Float = 0.62f) =
        TemplateControlCandidate(x, y, radius, score)

    private fun single(c: TemplateControlCandidate) =
        TemplateControlResult(status = TemplateControlStatus.SINGLE, candidate = c, candidates = 1, peakScore = c.score)

    private fun decide(
        outcome: AttackRecognitionOutcome,
        point: AttackButtonPoint?,
        template: TemplateControlResult,
    ) = TemplateAttackControlMerge.decide(outcome, point, template)

    private val baseline = AttackButtonPoint(1076f, 366f, 35f)

    // --- veto -------------------------------------------------------------------------------

    @Test
    fun everyUncertainBaselineOutcomeVetoesTheTemplate() {
        val template = single(candidate(100f, 100f))
        for (outcome in listOf(
            AttackRecognitionOutcome.NOT_ANALYZED,
            AttackRecognitionOutcome.INVALID_FRAME,
            AttackRecognitionOutcome.OPAQUE_AMBIGUOUS,
            AttackRecognitionOutcome.TRANSLUCENT_EXHAUSTED,
            AttackRecognitionOutcome.TRANSLUCENT_AMBIGUOUS,
            AttackRecognitionOutcome.CROSS_PATH_CONFLICT,
        )) {
            assertTrue(outcome.name, TemplateAttackControlMerge.vetoesTemplate(outcome))
            val decision = decide(outcome, null, template)
            assertNull(outcome.name, decision.point)
            assertEquals(outcome, decision.outcome)
        }
        assertTrue(!TemplateAttackControlMerge.vetoesTemplate(AttackRecognitionOutcome.OPAQUE_POINT))
        assertTrue(!TemplateAttackControlMerge.vetoesTemplate(AttackRecognitionOutcome.NOT_FOUND))
    }

    // --- template uncertainty ----------------------------------------------------------------

    @Test
    fun nearAndDistantPeaksStayAmbiguousAndDeny() {
        val decision = decide(
            AttackRecognitionOutcome.OPAQUE_POINT,
            baseline,
            TemplateControlResult(status = TemplateControlStatus.AMBIGUOUS, candidates = 2, peakScore = 0.71f),
        )
        assertNull(decision.point)
        assertEquals(AttackRecognitionOutcome.TEMPLATE_AMBIGUOUS, decision.outcome)
    }

    @Test
    fun exhaustedBudgetDeniesEvenWhenACandidateIsPresent() {
        val decision = decide(
            AttackRecognitionOutcome.NOT_FOUND,
            null,
            TemplateControlResult(status = TemplateControlStatus.EXHAUSTED, candidate = candidate(10f, 10f), candidates = 129),
        )
        assertNull(decision.point)
        assertEquals(AttackRecognitionOutcome.TEMPLATE_EXHAUSTED, decision.outcome)
    }

    @Test
    fun recognizerErrorDenies() {
        val decision = decide(
            AttackRecognitionOutcome.OPAQUE_POINT,
            baseline,
            TemplateControlResult(status = TemplateControlStatus.ERROR, nanos = 12L),
        )
        assertNull(decision.point)
        assertEquals(AttackRecognitionOutcome.TEMPLATE_ERROR, decision.outcome)
    }

    @Test
    fun malformedSingleResultIsAnErrorNotAPoint() {
        val missing = decide(AttackRecognitionOutcome.NOT_FOUND, null, TemplateControlResult(TemplateControlStatus.SINGLE))
        assertNull(missing.point)
        assertEquals(AttackRecognitionOutcome.TEMPLATE_ERROR, missing.outcome)

        val notFinite = decide(
            AttackRecognitionOutcome.NOT_FOUND,
            null,
            single(candidate(Float.NaN, 10f)),
        )
        assertNull(notFinite.point)
        assertEquals(AttackRecognitionOutcome.TEMPLATE_ERROR, notFinite.outcome)
    }

    // --- additive evidence ---------------------------------------------------------------

    @Test
    fun absentTemplateRetainsAnExistingBaselinePointUnchanged() {
        for (outcome in listOf(
            AttackRecognitionOutcome.OPAQUE_POINT,
            AttackRecognitionOutcome.TRANSLUCENT_POINT,
            AttackRecognitionOutcome.BOTH_SAME_CONTROL,
        )) {
            val decision = decide(outcome, baseline, TemplateControlResult())
            assertSame(outcome.name, baseline, decision.point)
            assertEquals(outcome, decision.outcome)
        }
    }

    @Test
    fun uniqueTemplateMatchWithoutBaselineIsItsOwnOutcome() {
        val match = candidate(410f, 300f, 20f, 0.64f)
        val decision = decide(AttackRecognitionOutcome.NOT_FOUND, null, single(match))
        assertEquals(AttackRecognitionOutcome.TEMPLATE_POINT, decision.outcome)
        val point = requireNotNull(decision.point)
        assertEquals(410f, point.x, 0f)
        assertEquals(300f, point.y, 0f)
        assertEquals(20f, point.radius, 0f)
    }

    @Test
    fun nearbyTemplateMatchConfirmsTheBaselineControl() {
        val decision = decide(
            AttackRecognitionOutcome.OPAQUE_POINT,
            baseline,
            single(candidate(1077f, 367f, 34.5f, 0.63f)),
        )
        assertSame(baseline, decision.point)
        assertEquals(AttackRecognitionOutcome.BOTH_TEMPLATE_SAME_CONTROL, decision.outcome)
    }

    @Test
    fun geometryMismatchIsACrossPathConflict() {
        val distant = decide(
            AttackRecognitionOutcome.OPAQUE_POINT,
            baseline,
            single(candidate(300f, 300f, 35f)),
        )
        assertNull(distant.point)
        assertEquals(AttackRecognitionOutcome.CROSS_PATH_CONFLICT, distant.outcome)

        val wrongRadius = decide(
            AttackRecognitionOutcome.TRANSLUCENT_POINT,
            baseline,
            single(candidate(1076f, 366f, 60f)),
        )
        assertNull(wrongRadius.point)
        assertEquals(AttackRecognitionOutcome.CROSS_PATH_CONFLICT, wrongRadius.outcome)
    }

    @Test
    fun sameControlMatchesTheExistingTranslucentPredicate() {
        val a = AttackButtonPoint(100f, 100f, 40f)
        assertTrue(TemplateAttackControlMerge.sameControl(a, AttackButtonPoint(107f, 100f, 40f)))
        assertTrue(TemplateAttackControlMerge.sameControl(a, AttackButtonPoint(100f, 100f, 50f)))
        assertTrue(!TemplateAttackControlMerge.sameControl(a, AttackButtonPoint(109f, 100f, 40f)))
        assertTrue(!TemplateAttackControlMerge.sameControl(a, AttackButtonPoint(100f, 100f, 51f)))
    }

    @Test
    fun neitherPathSeeingAnythingIsNotFound() {
        val decision = decide(AttackRecognitionOutcome.NOT_FOUND, null, TemplateControlResult())
        assertNull(decision.point)
        assertEquals(AttackRecognitionOutcome.NOT_FOUND, decision.outcome)
    }

    // --- detector boundary ---------------------------------------------------------------

    @Test
    fun legacyDetectNeverRunsTheTemplateRecognizer() {
        val rgb = background().also { ring(it, 160, 90) }
        val point = requireNotNull(AttackButtonDetector.detect(mat(rgb)))
        assertEquals(160f, point.x, 0.6f)
        val trace = requireNotNull(AttackButtonDetector.lastTrace())
        assertEquals(AttackRecognitionOutcome.OPAQUE_POINT, trace.outcome)
        // Old path publishes no template phase at all.
        assertEquals(0L, trace.templateNanos)
        assertEquals(0, trace.templateCandidates)
        assertEquals(0f, trace.templatePeakScore, 0f)
        assertEquals(trace.opaqueNanos + trace.translucentNanos, trace.totalNanos)
    }

    @Test
    fun attackDetectOnEmptyFrameClearsTraceAndSkipsTheTemplate() {
        var invoked = false
        val result = AttackButtonDetector.detectForAttack(RasterMat()) {
            invoked = true
            TemplateControlResult()
        }
        assertNull(result)
        assertTrue(!invoked)
        assertNull(AttackButtonDetector.lastTrace())
    }

    @Test
    fun attackDetectReportsUniqueTemplateEvidenceOnABlankFrame() {
        val decision = AttackButtonDetector.detectForAttack(mat(background())) {
            single(candidate(240f, 120f, 22f, 0.61f))
        }
        val point = requireNotNull(decision)
        assertEquals(240f, point.x, 0f)
        val trace = requireNotNull(AttackButtonDetector.lastTrace())
        assertEquals(AttackRecognitionOutcome.TEMPLATE_POINT, trace.outcome)
        assertEquals(1, trace.templateCandidates)
        assertEquals(0.61f, trace.templatePeakScore, 0.0001f)
    }

    @Test
    fun attackDetectTreatsMatcherFailureAsUncertainty() {
        val decision = AttackButtonDetector.detectForAttack(mat(background())) {
            throw IllegalStateException("synthetic recognizer failure")
        }
        assertNull(decision)
        assertEquals(AttackRecognitionOutcome.TEMPLATE_ERROR, requireNotNull(AttackButtonDetector.lastTrace()).outcome)
    }

    @Test
    fun calibrationRejectsLegacyOnlyPoint() {
        val rgb = background().also { ring(it, 160, 90) }
        assertNull(AttackButtonDetector.detectForCalibration(mat(rgb)) { TemplateControlResult() })
    }

    @Test
    fun calibrationUsesActualTemplateCenterAfterCrossPathAgreement() {
        val rgb = background().also { ring(it, 160, 90) }
        val point = requireNotNull(AttackButtonDetector.detectForCalibration(mat(rgb)) {
            single(candidate(161f, 90f, 22f, 0.65f))
        })
        assertEquals(161f, point.x, 0f)
        assertEquals(AttackRecognitionOutcome.BOTH_TEMPLATE_SAME_CONTROL,
            requireNotNull(AttackButtonDetector.lastTrace()).outcome)
    }

    @Test
    fun calibrationRejectsAmbiguousExhaustedAndThrowingTemplate() {
        for (status in listOf(TemplateControlStatus.AMBIGUOUS, TemplateControlStatus.EXHAUSTED, TemplateControlStatus.ERROR)) {
            assertNull(AttackButtonDetector.detectForCalibration(mat(background())) {
                TemplateControlResult(status = status)
            })
        }
        assertNull(AttackButtonDetector.detectForCalibration(mat(background())) { throw IllegalStateException("synthetic") })
    }

    @Test
    fun savedCalibrationTraceDoesNotImpersonatePixelWork() {
        AttackButtonDetector.detectForAttack(mat(background())) { single(candidate(240f, 120f, 22f, 0.65f)) }
        AttackButtonDetector.recordSavedCalibration()
        val trace = requireNotNull(AttackButtonDetector.lastTrace())
        assertEquals(AttackRecognitionOutcome.SAVED_CALIBRATION_POINT, trace.outcome)
        assertEquals(0L, trace.totalNanos)
        assertEquals(0, trace.templateCandidates)
    }

    @Test
    fun attackDetectRetainsBaselineEvidenceWhenTemplateSeesNothing() {
        val rgb = background().also { ring(it, 160, 90) }
        val decision = AttackButtonDetector.detectForAttack(mat(rgb)) { TemplateControlResult() }
        val point = requireNotNull(decision)
        assertEquals(160f, point.x, 0.6f)
        assertEquals(AttackRecognitionOutcome.OPAQUE_POINT, requireNotNull(AttackButtonDetector.lastTrace()).outcome)
    }
}
