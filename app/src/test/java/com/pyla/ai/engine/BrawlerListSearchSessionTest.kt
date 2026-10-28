package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * Visual-binding contract of [BrawlerListSearchSession]: after a strict initial list admission the
 * binding is the PIXELS of the already validated sort-control region, so neither the currently
 * selected sort caption nor any heading/count/HUD text can decide whether the search continues.
 *
 * Every pre-existing negative is preserved: no initial admission, stale/future/zero timestamps,
 * reversed or identical captures, geometry change, malformed buffer, a changed screen, a blank
 * control crop, a moved or animated control crop, and unbound OCR scans.
 */
class BrawlerListSearchSessionTest {
    private val header = BrawlerOcrHit("BRAWLERS", 72, 54, 252, 78)
    private val sort = BrawlerOcrHit("RARITY", 580, 5, 680, 30)

    private val control = BrawlerGridContinuation.sortRegion(1280, 600)

    /** Real, contrast-bearing control ink inside the validated region, identical frame to frame. */
    private fun drawControl(pixels: IntArray, shift: Int = 0) {
        var i = 0
        var x = control.left + 8
        while (x + 8 < control.right - 8) {
            val w = 8 + (i * 5) % 13
            for (y in control.top + 8 until control.bottom - 6) for (px in x until minOf(x + w, control.right)) {
                pixels[y * 1280 + px] = if (i % 2 == 0) 0xfff2f2f2.toInt() else 0xff101010.toInt()
            }
            x += w + 6 + shift
            i++
        }
    }

    private fun frame(
        at: Long,
        hits: List<BrawlerOcrHit> = listOf(header),
        controlInk: Boolean = true,
        controlShift: Int = 0,
    ): FrameSnapshot {
        val pixels = IntArray(1280 * 600) { 0xff223344.toInt() }
        for (hit in hits) for (y in hit.top until hit.bottom) for (x in hit.left until hit.right) {
            if ((x / 4 + y / 4) % 2 == 0) pixels[y * 1280 + x] = 0xffeeeeee.toInt()
        }
        if (controlInk) drawControl(pixels, controlShift)
        return FrameSnapshot(1280, 600, pixels, at)
    }

    private fun scan(frame: FrameSnapshot, hits: List<BrawlerOcrHit> = listOf(header)) =
        BrawlerOcrScan(frame.width, frame.height, frame.capturedAtMs, hits)

    private fun capture(hits: List<BrawlerOcrHit> = listOf(header)): BrawlerListSearchSession {
        val a = frame(100, hits); val b = frame(200, hits)
        return BrawlerListSearchSession.capture(a, b, scan(a, hits), scan(b, hits), 200, true)!!
    }

    @Test fun requiresStrongInitialListAdmission() {
        val a = frame(100); val b = frame(200)
        assertNull(BrawlerListSearchSession.capture(a, b, scan(a), scan(b), 200))
    }

    @Test fun freshStaticControlKeepsListAcrossOcrDropouts() {
        val session = capture()
        assertTrue(session.confirms(frame(900), frame(1000), 1000))
    }

    @Test fun bodyAnimationDoesNotDestroyListIdentity() {
        val session = capture(); val a = frame(900); val b = frame(1000)
        for (y in 120 until 600) for (x in 0 until 1280) b.argb[y * 1280 + x] = 0xffffffff.toInt()
        assertTrue(session.confirms(a, b, 1000))
    }

    @Test fun templateIsNotAnExpiredAuthorityLease() {
        val session = capture()
        assertTrue(session.confirms(frame(60000), frame(60100), 60100))
    }

    @Test fun unchangedTimestampIsNotTwoCaptures() {
        assertFalse(capture().confirms(frame(1000), frame(1000), 1000))
    }

    @Test fun reversedCapturesAreRejected() {
        assertFalse(capture().confirms(frame(1000), frame(900), 1000))
    }

    @Test fun staleCurrentFrameIsRejected() { assertFalse(capture().matches(frame(900), 4000)) }

    @Test fun currentFrameAtExistingAgeBoundaryIsAllowed() { assertTrue(capture().matches(frame(1000), 4000)) }

    @Test fun futureFrameIsRejected() { assertFalse(capture().matches(frame(1100), 1000)) }

    @Test fun zeroTimestampIsRejected() { assertFalse(capture().matches(frame(0), 1000)) }

    @Test fun preAdmissionFrameIsRejected() { assertFalse(capture().matches(frame(99), 100)) }

    @Test fun geometryChangeIsRejected() {
        assertFalse(capture().matches(FrameSnapshot(1281, 600, IntArray(1281 * 600), 1000), 1000))
    }

    @Test fun malformedPixelBufferIsRejected() {
        assertFalse(capture().matches(FrameSnapshot(1280, 600, IntArray(4), 1000), 1000))
    }

    @Test fun changedScreenCannotInheritListAuthority() {
        val b = frame(1000); b.argb.fill(0xff223344.toInt())
        assertFalse(capture().matches(b, 1000))
    }

    // ---------------------------------------------------------------- caption text is irrelevant
    @Test fun unknownSortCaptionBindsAnInitiallyAdmittedList() {
        val unknown = sort.copy(text = "Meiste Trophäen")
        val a = frame(100, listOf(unknown)); val b = frame(200, listOf(unknown))
        assertNotNull(BrawlerListSearchSession.capture(a, b, scan(a, listOf(unknown)),
            scan(b, listOf(unknown)), 200, true))
    }

    @Test fun longNonLatinSortCaptionBindsAnInitiallyAdmittedList() {
        val caption = sort.copy(text = "Сортировка по редкости — 稀有的罕见度排序排序")
        val a = frame(100, listOf(caption)); val b = frame(200, listOf(caption))
        assertNotNull(BrawlerListSearchSession.capture(a, b, scan(a, listOf(caption)),
            scan(b, listOf(caption)), 200, true))
    }

    @Test fun sortOnlyCanBindAnInitiallyAdmittedList() {
        assertTrue(capture(listOf(sort)).matches(frame(1000, listOf(sort)), 1000))
    }

    @Test fun genuineGermanSortLabelCanBindAdmittedList() {
        val german = sort.copy(text = "Seltenheitsgrad")
        val a = frame(100, listOf(german)); val b = frame(200, listOf(german))
        assertNotNull(BrawlerListSearchSession.capture(a, b, scan(a, listOf(german)),
            scan(b, listOf(german)), 200, true))
    }

    @Test fun germanSortRequiresExactWholeVisibleLabel() {
        for (text in listOf("Sort: Seltenheitsgrad", "Seltenheitsgrad:", "SeltenheitsgradXYZ", "Seltenheits", "Grad"))
            assertFalse(text, BrawlerGridContinuation.isSortLabel(text))
    }

    @Test fun noOcrTextAtAllBindsOnTheControlPixels() {
        val a = frame(100, emptyList()); val b = frame(200, emptyList())
        assertNotNull(BrawlerListSearchSession.capture(a, b, scan(a, emptyList()),
            scan(b, emptyList()), 200, true))
    }

    // ---------------------------------------------------------------- heading/HUD text is irrelevant
    @Test fun headingPixelsAreNotPartOfTheBinding() {
        val session = capture()
        val later = frame(1000)
        // Mutable HUD ink lands on the heading band: the control crop is untouched.
        for (y in 40 until 90) for (x in 60 until 400) later.argb[y * 1280 + x] = 0xff25c04a.toInt()
        assertTrue(session.matches(later, 1000))
    }

    @Test fun ambiguousHeadingFlagDoesNotBlockTheVisualBinding() {
        val a = frame(100); val b = frame(200)
        assertNotNull(BrawlerListSearchSession.capture(a, b,
            scan(a).copy(gridHeaderAmbiguous = true), scan(b), 200, true))
    }

    @Test fun duplicateHeadingIsNeverANeededPrecondition() {
        val duplicate = header.copy(left = 300, right = 460)
        val hits = listOf(header, duplicate)
        val a = frame(100, hits); val b = frame(200, hits)
        assertNotNull(BrawlerListSearchSession.capture(a, b, scan(a, hits), scan(b, hits), 200, true))
    }

    // ---------------------------------------------------------------- no blank/generic authority
    @Test fun flatControlCropNeverBinds() {
        val a = frame(100, controlInk = false); val b = frame(200, controlInk = false)
        assertNull(BrawlerListSearchSession.capture(a, b, scan(a), scan(b), 200, true))
    }

    @Test fun sortOcrOutsideTheControlRegionIsNotAnAnchor() {
        val outside = sort.copy(text = "Seltenheitsgrad", top = 44, bottom = 62)
        val a = frame(100, listOf(outside), controlInk = false)
        val b = frame(200, listOf(outside), controlInk = false)
        assertNull(BrawlerListSearchSession.capture(a, b, scan(a, listOf(outside)),
            scan(b, listOf(outside)), 200, true))
    }

    @Test fun movedControlCropCannotEstablishSession() {
        val a = frame(100); val b = frame(200, controlShift = 9)
        assertNull(BrawlerListSearchSession.capture(a, b, scan(a), scan(b), 200, true))
    }

    @Test fun ocrMustBelongToExactCapturedFrame() {
        val a = frame(100); val b = frame(200)
        assertNull(BrawlerListSearchSession.capture(a, b, scan(a).copy(capturedAtMs = 99), scan(b), 200, true))
    }

    @Test fun sessionDoesNotRetainMutableCaptureBuffer() {
        val a = frame(100); val b = frame(200)
        val session = BrawlerListSearchSession.capture(a, b, scan(a), scan(b), 200, true)!!
        a.argb.fill(0); b.argb.fill(0)
        assertTrue(session.matches(frame(1000), 1000))
    }

    @Test fun currentComparisonNeverRollsTemplateForward() {
        val session = capture(); val a = frame(1000)
        for (y in control.top until control.bottom) for (x in control.left until control.right) {
            val i = y * 1280 + x
            a.argb[i] = a.argb[i] xor 0x00080808
        }
        assertFalse(session.matches(a, 1000))
        assertTrue(session.matches(frame(1200), 1200))
    }
}
