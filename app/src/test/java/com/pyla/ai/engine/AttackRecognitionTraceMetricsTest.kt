package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Contract of [AttackButtonDetector.lastTrace]: same-thread immutable latest evidence, cleared
 * before every call, published only for a completed analysis of that call.
 */
class AttackRecognitionTraceMetricsTest {
    private val w = 320
    private val h = 180

    private fun background() = ByteArray(w * h * 3).also { a ->
        for (i in 0 until w * h) { a[i * 3] = 30; a[i * 3 + 1] = 90; a[i * 3 + 2] = 40 }
    }

    private fun pixel(a: ByteArray, x: Int, y: Int, r: Int, g: Int, b: Int) {
        val i = (y * w + x) * 3; a[i] = r.toByte(); a[i + 1] = g.toByte(); a[i + 2] = b.toByte()
    }

    private fun ring(a: ByteArray, cx: Int, cy: Int, radius: Int = 20) {
        for (y in 0 until h) for (x in 0 until w) {
            val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (d <= radius * radius) {
                if (d < 36) pixel(a, x, y, 220, 195, 200) else pixel(a, x, y, 160, 12, 20)
            }
        }
    }

    /** API-real Mat subclass: no Android JNI in a host unit test. The exact bridge is
     * exercised with genuine native Mats separately by the owned Android probe. */
    private class RasterMat(
        private val rgb: ByteArray = ByteArray(0),
        private val rowCount: Int = 0,
        private val columnCount: Int = 0,
        private val channelCount: Int = 3,
        private val throwOnEmpty: Boolean = false,
        private val throwOnGet: Boolean = false,
    ) : Mat(1L) {
        override fun empty(): Boolean {
            if (throwOnEmpty) throw IllegalStateException("owned empty fault")
            return rowCount == 0 || columnCount == 0
        }
        override fun channels(): Int = channelCount
        override fun depth(): Int = CvType.CV_8U
        override fun rows(): Int = rowCount
        override fun cols(): Int = columnCount
        override fun get(row: Int, col: Int, target: ByteArray): Int {
            if (throwOnGet) throw IllegalStateException("owned read fault")
            require(row == 0 && col == 0)
            val count = minOf(target.size, rgb.size)
            rgb.copyInto(target, endIndex = count)
            return count
        }
        // The synthetic nativeObj is never owned by JNI, including at garbage collection.
        protected override fun finalize() = Unit
    }

    private fun mat(rgb: ByteArray): Mat = RasterMat(rgb, h, w)

    @Test
    fun completedRecognitionPublishesBoundedPhaseEvidence() {
        val rgb = background().also { ring(it, 160, 90) }
        val point = requireNotNull(AttackButtonDetector.detect(mat(rgb)))
        assertEquals(160f, point.x, 0.6f)
        val trace = requireNotNull(AttackButtonDetector.lastTrace())
        assertEquals(AttackRecognitionOutcome.OPAQUE_POINT, trace.outcome)
        assertTrue(trace.opaqueNanos >= 0)
        assertTrue(trace.translucentNanos >= 0)
        assertTrue(trace.coarseNanos >= 0)
        assertTrue(trace.refineNanos >= 0)
        assertTrue(trace.coarseCandidates >= 0)
        assertTrue(trace.refinementSeeds >= 0)
        assertTrue("a completed bounded search is not exhaustion", !trace.exhausted)
        assertEquals(trace.opaqueNanos + trace.translucentNanos, trace.totalNanos)
    }

    @Test
    fun ambiguousFrameStillPublishesItsCompletedTrace() {
        val rgb = background().also { ring(it, 50, 90); ring(it, 250, 90) }
        assertNull(AttackButtonDetector.detect(mat(rgb)))
        assertEquals(AttackRecognitionOutcome.OPAQUE_AMBIGUOUS, requireNotNull(AttackButtonDetector.lastTrace()).outcome)
    }

    @Test fun absentControlAndInvalidBufferHaveSeparateOutcomes() {
        assertNull(AttackButtonDetector.detect(mat(background())))
        assertEquals(AttackRecognitionOutcome.NOT_FOUND, requireNotNull(AttackButtonDetector.lastTrace()).outcome)
        val metrics = AttackRecognitionMetrics()
        assertNull(AttackButtonPixels.detect(320, 180, ByteArray(0), metrics))
        assertEquals(AttackRecognitionOutcome.INVALID_FRAME, metrics.toTrace().outcome)
    }

    @Test
    fun staleTraceIsClearedByEveryNonAnalyzedFrame() {
        val valid = background().also { ring(it, 160, 90) }
        requireNotNull(AttackButtonDetector.detect(mat(valid)))
        assertNotNull(AttackButtonDetector.lastTrace())

        assertNull(AttackButtonDetector.detect(RasterMat()))
        assertNull("empty Mat must clear the previous frame's trace", AttackButtonDetector.lastTrace())

        requireNotNull(AttackButtonDetector.detect(mat(valid)))
        assertNotNull(AttackButtonDetector.lastTrace())
        assertNull(AttackButtonDetector.detect(RasterMat(ByteArray(w * h), h, w, 1)))
        assertNull("non-3-channel Mat must clear the previous frame's trace", AttackButtonDetector.lastTrace())
    }

    @Test
    fun traceIsConfinedToTheRecognizingThread() {
        val mainTrace = AttackButtonDetector.lastTrace()
        val task = java.util.concurrent.FutureTask<AttackRecognitionTrace> {
            requireNotNull(AttackButtonDetector.detect(mat(background().also { ring(it, 160, 90) })))
            requireNotNull(AttackButtonDetector.lastTrace())
        }
        val worker = Thread(task, "attack-trace-contract").apply { isDaemon = true }
        worker.start()
        try { assertNotNull(task.get(60, java.util.concurrent.TimeUnit.SECONDS)) }
        finally { worker.interrupt(); worker.join(1_000) }
        assertSame("another thread's trace must not be visible here", mainTrace, AttackButtonDetector.lastTrace())
    }

    @Test
    fun throwingAndShortReadsCannotPublishThePrecedingTrace() {
        val rgb = background().also { ring(it, 160, 90) }
        requireNotNull(AttackButtonDetector.detect(mat(rgb)))
        try {
            AttackButtonDetector.detect(RasterMat(throwOnEmpty = true))
            throw AssertionError("the original Mat-access failure must still propagate")
        } catch (_: IllegalStateException) { }
        assertNull(AttackButtonDetector.lastTrace())
        requireNotNull(AttackButtonDetector.detect(mat(rgb)))
        assertNull(AttackButtonDetector.detect(RasterMat(rgb, h, w, throwOnGet = true)))
        assertNull(AttackButtonDetector.lastTrace())
        requireNotNull(AttackButtonDetector.detect(mat(rgb)))
        assertNull(AttackButtonDetector.detect(RasterMat(ByteArray(1), h, w)))
        assertNull(AttackButtonDetector.lastTrace())
    }
}
