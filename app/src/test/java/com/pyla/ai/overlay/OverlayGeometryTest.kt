package com.pyla.ai.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGeometryTest {
    private val metrics = OverlayMetrics(
        bubbleSizePx = 132, controlSizePx = 132, statusHeightPx = 77,
        edgeMarginPx = 22, controlGapPx = 22, statusTopGapPx = 11, dragSlopPx = 16,
    )
    private val phone = ScreenGeometry(widthPx = 1080, heightPx = 2400, insetTopPx = 90, insetBottomPx = 84)

    @Test fun clampRelativeYHandlesFiniteAndNonFinite() {
        assertEquals(0f, OverlayGeometry.clampRelativeY(-3f), 0.0001f)
        assertEquals(1f, OverlayGeometry.clampRelativeY(9f), 0.0001f)
        assertEquals(OverlayGeometry.DEFAULT_RELATIVE_Y, OverlayGeometry.clampRelativeY(Float.NaN), 0.0001f)
        assertEquals(OverlayGeometry.DEFAULT_RELATIVE_Y, OverlayGeometry.clampRelativeY(Float.POSITIVE_INFINITY), 0.0001f)
    }

    @Test fun statusStripSitsBelowStatusBarAndAboveControlsBand() {
        val status = OverlayGeometry.statusRect(phone, metrics)
        val band = OverlayGeometry.usableVerticalRange(phone, metrics)
        assertTrue(status.top >= phone.insetTopPx)
        assertTrue(status.bottom <= band.top)
        assertEquals(phone.widthPx, status.width)
    }

    @Test fun hiddenBubbleIsHalfOffScreenOnRight() {
        val r = OverlayGeometry.bubbleRect(DockSide.RIGHT, phone, metrics, 0.4f, 0f)
        assertEquals(phone.widthPx - metrics.bubbleSizePx / 2, r.left)
        assertTrue(r.right > phone.widthPx)
    }

    @Test fun hiddenBubbleIsHalfOffScreenOnLeft() {
        val r = OverlayGeometry.bubbleRect(DockSide.LEFT, phone, metrics, 0.4f, 0f)
        assertEquals(-metrics.bubbleSizePx / 2, r.left)
        assertTrue(r.left < 0)
    }

    @Test fun revealedBubbleRespectsEdgeMarginsOnBothSides() {
        val right = OverlayGeometry.bubbleRect(DockSide.RIGHT, phone, metrics, 0.4f, 1f)
        assertEquals(phone.widthPx - metrics.edgeMarginPx - metrics.bubbleSizePx, right.left)
        val left = OverlayGeometry.bubbleRect(DockSide.LEFT, phone, metrics, 0.4f, 1f)
        assertEquals(metrics.edgeMarginPx, left.left)
    }

    @Test fun revealIsMonotonicBetweenHiddenAndShown() {
        fun left(side: DockSide, t: Float) = OverlayGeometry.bubbleRect(side, phone, metrics, 0.4f, t).left
        assertTrue(left(DockSide.RIGHT, 0f) > left(DockSide.RIGHT, 0.5f))
        assertTrue(left(DockSide.RIGHT, 0.5f) > left(DockSide.RIGHT, 1f))
        assertTrue(left(DockSide.LEFT, 0f) < left(DockSide.LEFT, 0.5f))
        assertTrue(left(DockSide.LEFT, 0.5f) < left(DockSide.LEFT, 1f))
    }

    @Test fun relativeYRoundTripsWithinTheBand() {
        val y = OverlayGeometry.positionY(phone, metrics, metrics.bubbleSizePx, 0.6f)
        val back = OverlayGeometry.relativeYForTop(y, phone, metrics, metrics.bubbleSizePx)
        assertEquals(0.6f, back, 0.01f)
    }

    @Test fun bubbleNeverEntersStatusBandOrBottomNav() {
        val band = OverlayGeometry.usableVerticalRange(phone, metrics)
        for (rel in listOf(0f, 0.5f, 1f)) {
            val r = OverlayGeometry.bubbleRect(DockSide.RIGHT, phone, metrics, rel, 1f)
            assertTrue(r.top >= band.top)
            assertTrue(r.bottom <= band.bottom)
            assertTrue(r.bottom <= phone.heightPx - phone.insetBottomPx)
        }
    }

    @Test fun controlsStackInwardAndDoNotOverlapBubbleOnRight() {
        val rects = OverlayGeometry.controlRects(DockSide.RIGHT, phone, metrics, 0.5f)
        val bubble = OverlayGeometry.bubbleRect(DockSide.RIGHT, phone, metrics, 0.5f, 1f)
        assertEquals(3, rects.size)
        // Inward means each control is left of the bubble.
        rects.forEach { assertTrue(it.right <= bubble.left) }
        // Vertical order Start(top), Pause(middle), Stop(bottom), evenly spaced.
        assertTrue(rects[0].bottom < rects[1].top)
        assertTrue(rects[1].bottom < rects[2].top)
        assertEquals(rects[1].top - rects[0].top, rects[2].top - rects[1].top)
        // No control/bubble overlap.
        rects.forEach { assertFalse(it.overlaps(bubble)) }
    }

    @Test fun controlsStackInwardOnLeft() {
        val rects = OverlayGeometry.controlRects(DockSide.LEFT, phone, metrics, 0.5f)
        val bubble = OverlayGeometry.bubbleRect(DockSide.LEFT, phone, metrics, 0.5f, 1f)
        rects.forEach { assertTrue(it.left >= bubble.right) }
    }

    @Test fun controlsStayInsideScreenOnNarrowDisplay() {
        val narrow = ScreenGeometry(widthPx = 320, heightPx = 480, insetTopPx = 24, insetBottomPx = 24)
        val rects = OverlayGeometry.controlRects(DockSide.RIGHT, narrow, metrics, 0.5f)
        rects.forEach {
            assertTrue(it.left >= narrow.insetLeftPx)
            assertTrue(it.right <= narrow.widthPx - narrow.insetRightPx)
            assertTrue(it.top >= 0)
            assertTrue(it.bottom <= narrow.heightPx)
        }
    }

    @Test fun controlsClampWithinShortDisplay() {
        val short = ScreenGeometry(widthPx = 2400, heightPx = 400, insetTopPx = 40, insetBottomPx = 24)
        val rects = OverlayGeometry.controlRects(DockSide.RIGHT, short, metrics, 1f)
        val band = OverlayGeometry.usableVerticalRange(short, metrics)
        rects.forEach {
            assertTrue(it.top >= band.top)
            assertTrue(it.bottom <= band.bottom)
        }
    }

    @Test fun snapSidePicksNearestEdge() {
        assertEquals(DockSide.LEFT, OverlayGeometry.snapSide(100, phone))
        assertEquals(DockSide.RIGHT, OverlayGeometry.snapSide(980, phone))
        assertEquals(DockSide.RIGHT, OverlayGeometry.snapSide(540, phone))
    }

    @Test fun statusDoesNotOverlapControls() {
        val status = OverlayGeometry.statusRect(phone, metrics)
        val rects = OverlayGeometry.controlRects(DockSide.LEFT, phone, metrics, 0.0f)
        rects.forEach { assertFalse(it.overlaps(status)) }
    }

    @Test fun slopDetection() {
        assertFalse(OverlayGeometry.exceedsSlop(5f, -5f, 16))
        assertTrue(OverlayGeometry.exceedsSlop(17f, 0f, 16))
        assertTrue(OverlayGeometry.exceedsSlop(0f, -40f, 16))
    }

    @Test fun metricsAreAtLeastOnePixelAtAnyDensity() {
        val tiny = OverlayMetrics.forDensity(0.1f)
        assertTrue(tiny.bubbleSizePx >= 1)
        assertTrue(tiny.dragSlopPx >= 1)
    }
}
