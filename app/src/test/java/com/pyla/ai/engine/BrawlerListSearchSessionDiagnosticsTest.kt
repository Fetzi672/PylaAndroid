package com.pyla.ai.engine

import com.pyla.ai.engine.BrawlerListSearchSession.CaptureObserver
import com.pyla.ai.engine.BrawlerListSearchSession.Rejection
import org.junit.Assert.*
import org.junit.Test

/**
 * Runs against the real [BrawlerListSearchSession]. Every existing `null` return of `capture` must
 * be attributable to exactly one fixed code, the returned value must stay `null`, and a throwing
 * observer must never change the outcome.
 *
 * The binding is now the validated sort-control REGION pixels, so heading multiplicity/ambiguity
 * flags and sort-caption text are no longer rejection reasons: those cases assert that the capture
 * still succeeds without reporting a code.
 */
class BrawlerListSearchSessionDiagnosticsTest {
    private val header = BrawlerOcrHit("BRAWLERS", 72, 54, 252, 78)
    private val sort = BrawlerOcrHit("RARITY", 580, 5, 680, 30)
    private val control = BrawlerGridContinuation.sortRegion(1280, 600)

    private fun frame(
        at: Long,
        hits: List<BrawlerOcrHit> = listOf(header),
        width: Int = 1280,
        height: Int = 600,
        controlInk: Boolean = true,
        controlFlat: Boolean = false,
        pattern: (Int, Int) -> Int = { x, y -> if ((x / 4 + y / 4) % 2 == 0) 0xffeeeeee.toInt() else 0xff223344.toInt() },
    ): FrameSnapshot {
        val pixels = IntArray(width * height) { 0xff223344.toInt() }
        for (hit in hits) for (y in hit.top until hit.bottom) for (x in hit.left until hit.right) {
            pixels[y * width + x] = pattern(x, y)
        }
        if (controlInk && width == 1280 && height == 600) {
            var i = 0
            var x = control.left + 8
            while (x + 8 < control.right - 8) {
                val w = 8 + (i * 5) % 13
                for (y in control.top + 8 until control.bottom - 6) for (px in x until minOf(x + w, control.right)) {
                    pixels[y * width + px] =
                        if (controlFlat) 0xff223344.toInt()
                        else if (i % 2 == 0) 0xfff2f2f2.toInt() else 0xff101010.toInt()
                }
                x += w + 6
                i++
            }
        }
        return FrameSnapshot(width, height, pixels, at)
    }

    private fun scan(frame: FrameSnapshot, hits: List<BrawlerOcrHit> = listOf(header),
                     at: Long = frame.capturedAtMs, ambiguous: Boolean = false) =
        BrawlerOcrScan(frame.width, frame.height, at, hits, ambiguous)

    private fun rejection(
        first: FrameSnapshot,
        second: FrameSnapshot,
        firstScan: BrawlerOcrScan,
        secondScan: BrawlerOcrScan,
        nowMs: Long,
        initialListConfirmed: Boolean = true,
    ): Rejection {
        var seen: MutableList<Rejection> = ArrayList()
        val session = BrawlerListSearchSession.capture(first, second, firstScan, secondScan, nowMs,
            initialListConfirmed, CaptureObserver { seen.add(it) })
        assertNull("an attributed rejection must still return null", session)
        assertEquals("exactly one rejection code per failed capture", 1, seen.size)
        return seen[0]
    }

    private fun code(
        first: FrameSnapshot,
        second: FrameSnapshot,
        firstScan: BrawlerOcrScan,
        secondScan: BrawlerOcrScan,
        nowMs: Long,
        initialListConfirmed: Boolean = true,
    ): String = rejection(first, second, firstScan, secondScan, nowMs, initialListConfirmed).code

    /** Accepts a pair and proves the observer stayed silent (no rejection was attributed). */
    private fun accepted(
        first: FrameSnapshot,
        second: FrameSnapshot,
        firstScan: BrawlerOcrScan,
        secondScan: BrawlerOcrScan,
        nowMs: Long = 200,
    ) {
        var seen: MutableList<Rejection> = ArrayList()
        val session = BrawlerListSearchSession.capture(first, second, firstScan, secondScan, nowMs,
            true, CaptureObserver { seen.add(it) })
        assertNotNull("expected an accepted visual binding", session)
        assertTrue("an accepted capture must not report a rejection", seen.isEmpty())
    }

    @Test fun missingInitialAdmissionIsNamed() {
        val a = frame(100); val b = frame(200)
        assertEquals("no_initial_confirmation", code(a, b, scan(a), scan(b), 200, initialListConfirmed = false))
    }

    @Test fun firstFrameOutsideAgeBoundIsNamed() {
        val a = frame(100); val b = frame(200)
        assertEquals("first_frame_invalid", code(a, b, scan(a), scan(b), 3_101))
    }

    @Test fun secondFrameOutsideAgeBoundIsNamed() {
        // First frame is exactly at the 3 s bound; the second capture claims a future timestamp.
        val a = frame(1_000); val b = frame(5_000)
        assertEquals("second_frame_invalid", code(a, b, scan(a), scan(b), 4_000))
    }

    @Test fun differingCaptureGeometryIsNamed() {
        val a = frame(100); val b = FrameSnapshot(1281, 600, IntArray(1281 * 600), 200)
        assertEquals("frame_size_mismatch", code(a, b, scan(a), scan(b), 200))
    }

    @Test fun nonIncreasingCaptureTimesAreNamed() {
        val a = frame(200); val b = frame(200)
        assertEquals("capture_order_invalid", code(a, b, scan(a), scan(b), 200))
    }

    @Test fun firstScanNotBoundToItsFrameIsNamed() {
        val a = frame(100); val b = frame(200)
        assertEquals("first_scan_unbound", code(a, b, scan(a, at = 99), scan(b), 200))
    }

    @Test fun secondScanNotBoundToItsFrameIsNamed() {
        val a = frame(100); val b = frame(200)
        assertEquals("second_scan_unbound", code(a, b, scan(a), scan(b, at = 201), 200))
    }

    @Test fun ambiguousHeadingInFirstScanNoLongerRejects() {
        val a = frame(100); val b = frame(200)
        accepted(a, b, scan(a, ambiguous = true), scan(b))
    }

    @Test fun ambiguousHeadingInSecondScanNoLongerRejects() {
        val a = frame(100); val b = frame(200)
        accepted(a, b, scan(a), scan(b, ambiguous = true))
    }

    @Test fun unsupportedCaptureAspectIsNamed() {
        val a = frame(100, width = 1280, height = 700); val b = frame(200, width = 1280, height = 700)
        assertEquals("region_plan_unavailable", code(a, b, scan(a), scan(b), 200))
    }

    @Test fun duplicateHeadingInFirstScanIsNotAPrecondition() {
        val duplicate = BrawlerOcrHit("BRAWLERS", 300, 54, 400, 78)
        val a = frame(100, listOf(header, duplicate)); val b = frame(200, listOf(header, duplicate))
        accepted(a, b, scan(a, listOf(header, duplicate)), scan(b, listOf(header, duplicate)))
    }

    @Test fun duplicateHeadingInSecondScanIsNotAPrecondition() {
        val duplicate = BrawlerOcrHit("BRAWLERS", 300, 54, 400, 78)
        val a = frame(100); val b = frame(200, listOf(header, duplicate))
        accepted(a, b, scan(a), scan(b, listOf(header, duplicate)))
    }

    @Test fun duplicateSortLabelInFirstScanIsNotAPrecondition() {
        val other = BrawlerOcrHit("Seltenheitsgrad", 548, 21, 673, 38)
        val a = frame(100, listOf(header, sort, other)); val b = frame(200, listOf(header, sort, other))
        accepted(a, b, scan(a, listOf(header, sort, other)), scan(b, listOf(header, sort, other)))
    }

    @Test fun duplicateSortLabelInSecondScanIsNotAPrecondition() {
        val other = BrawlerOcrHit("Seltenheitsgrad", 548, 21, 673, 38)
        val a = frame(100, listOf(header, sort)); val b = frame(200, listOf(header, sort, other))
        accepted(a, b, scan(a, listOf(header, sort)), scan(b, listOf(header, sort, other)))
    }

    @Test fun movingControlPixelsAreNamed() {
        val a = frame(100)
        val b = frame(200, pattern = { _, _ -> 0xffffffff.toInt() }, controlFlat = true)
        assertEquals("patch_pixels_changed", code(a, b, scan(a), scan(b), 200))
    }

    @Test fun blankControlCropIsNamed() {
        val a = frame(100, controlFlat = true); val b = frame(200, controlFlat = true)
        assertEquals("patch_crop_flat", code(a, b, scan(a), scan(b), 200))
    }

    @Test fun acceptedPairKeepsPriorBehaviourAndStaysSilent() {
        val a = frame(100); val b = frame(200)
        accepted(a, b, scan(a), scan(b))
    }

    @Test fun throwingObserverCannotChangeARejection() {
        val a = frame(100); val b = frame(200)
        val session = BrawlerListSearchSession.capture(a, b, scan(a), scan(b), 3_101, true,
            CaptureObserver { throw IllegalStateException("observer failure") })
        assertNull(session)
    }

    @Test fun throwingObserverCannotChangeAnAcceptance() {
        val a = frame(100); val b = frame(200)
        val session = BrawlerListSearchSession.capture(a, b, scan(a), scan(b), 200, true,
            CaptureObserver { throw IllegalStateException("observer failure") })
        assertNotNull(session)
    }

    @Test fun rejectionCodesAreDistinctAndStable() {
        val codes = Rejection.values().map { it.code }
        assertEquals("every rejection needs its own code", codes.size, codes.toSet().size)
        assertEquals("no_initial_confirmation", Rejection.NO_INITIAL_CONFIRMATION.code)
        assertEquals("patch_crop_flat", Rejection.PATCH_CROP_FLAT.code)
        assertTrue(codes.all { it.matches(Regex("[a-z0-9_]+")) })
    }
}
