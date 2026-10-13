package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LobbyMenuEntryGateTest {
    private val width = 100
    private val height = 100
    private val bounds = LobbyMenuEntryGate.Bounds(10, 20, 30, 40)
    private fun pixels() = IntArray(width * height) { 0xff444444.toInt() }
    private fun stable(a: IntArray, b: IntArray, area: LobbyMenuEntryGate.Bounds = bounds) =
        LobbyMenuEntryGate.pixelsStill(width, height, a, b, area)

    @Test fun stationaryControlPasses() {
        assertTrue(stable(pixels(), pixels()))
    }

    @Test fun animationOutsideControlDoesNotBlock() {
        val a = pixels()
        val b = IntArray(width * height) { 0xffffffff.toInt() }
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right)
            b[y * width + x] = a[y * width + x]
        assertTrue(stable(a, b))
    }

    @Test fun movingControlBlocks() {
        val a = pixels(); val b = pixels()
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right)
            b[y * width + x] = 0xffffffff.toInt()
        assertFalse(stable(a, b))
    }

    @Test fun fivePercentChangedPixelBoundaryIsNotRelaxed() {
        val a = pixels(); val b = pixels()
        val cells = (bounds.top until bounds.bottom).flatMap { y ->
            (bounds.left until bounds.right).map { x -> y * width + x }
        }
        for (i in 0 until 20) b[cells[i]] = 0xff555555.toInt() // delta 51, 5% of 400
        assertTrue(stable(a, b))
        b[cells[20]] = 0xff555555.toInt()
        assertFalse(stable(a, b))
    }

    @Test fun meanDifferenceBoundaryIsNotRelaxed() {
        val a = pixels(); val b = pixels()
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right)
            b[y * width + x] = 0xff474747.toInt() // delta 9
        assertTrue(stable(a, b))
        b[bounds.top * width + bounds.left] = 0xff484848.toInt()
        assertFalse(stable(a, b))
    }

    @Test fun malformedPixelBuffersFailClosed() {
        assertFalse(stable(IntArray(width * height - 1), pixels()))
        assertFalse(stable(pixels(), IntArray(width * height + 1)))
        assertFalse(LobbyMenuEntryGate.pixelsStill(0, height, IntArray(0), IntArray(0), bounds))
        assertFalse(LobbyMenuEntryGate.pixelsStill(Int.MAX_VALUE, Int.MAX_VALUE, pixels(), pixels(), bounds))
    }

    @Test fun malformedControlBoundsFailClosed() {
        for (area in listOf(
            LobbyMenuEntryGate.Bounds(-1, 0, 20, 20), LobbyMenuEntryGate.Bounds(0, -1, 20, 20),
            LobbyMenuEntryGate.Bounds(0, 0, 101, 20), LobbyMenuEntryGate.Bounds(0, 0, 20, 101),
            LobbyMenuEntryGate.Bounds(20, 20, 20, 40), LobbyMenuEntryGate.Bounds(20, 20, 40, 20),
            LobbyMenuEntryGate.Bounds(30, 20, 20, 40), LobbyMenuEntryGate.Bounds(20, 40, 30, 20)
        )) assertFalse(stable(pixels(), pixels(), area))
    }

    @Test fun labelGuardUsesObservedBoundsAndExistingGeometrySlack() {
        val hit = BrawlerOcrHit("BRAWLERS", 400, 980, 520, 1010)
        val area = LobbyMenuEntryGate.aroundLabel(1920, 1080, hit)!!
        val padding = BrawlerSelectionGate.tolerancePx(1080)
        assertEquals(hit.left - padding, area.left)
        assertEquals(hit.top - padding, area.top)
        assertEquals(hit.right + padding, area.right)
        assertEquals(hit.bottom + padding, area.bottom)
        assertTrue(hit.centerX in area.left until area.right)
        assertTrue(hit.centerY in area.top until area.bottom)
    }

    @Test fun labelGuardRejectsInvalidOriginalBoxRatherThanClampingItIntoFrame() {
        for (hit in listOf(
            BrawlerOcrHit("BRAWLERS", -1, 20, 30, 40), BrawlerOcrHit("BRAWLERS", 20, -1, 30, 40),
            BrawlerOcrHit("BRAWLERS", 20, 20, 101, 40), BrawlerOcrHit("BRAWLERS", 20, 20, 30, 101),
            BrawlerOcrHit("BRAWLERS", 20, 20, 20, 40), BrawlerOcrHit("BRAWLERS", 20, 20, 30, 20)
        )) assertNull(LobbyMenuEntryGate.aroundLabel(width, height, hit))
    }

    @Test fun legacyPointGuardCoversActualPointWithHeightScaledInspectionRadius() {
        val full = LobbyMenuEntryGate.aroundPoint(1920, 1080, 110f, 490f)!!
        assertEquals(50, full.left); assertEquals(430, full.top)
        assertEquals(171, full.right); assertEquals(551, full.bottom)
        val half = LobbyMenuEntryGate.aroundPoint(960, 540, 55f, 245f)!!
        assertEquals(25, half.left); assertEquals(215, half.top)
        assertEquals(86, half.right); assertEquals(276, half.bottom)
    }

    @Test fun invalidLegacyPointNeverBecomesClampedTap() {
        for ((x, y) in listOf(-1f to 20f, 20f to -1f, 100f to 20f, 20f to 100f,
            Float.NaN to 20f, 20f to Float.NaN, Float.POSITIVE_INFINITY to 20f,
            20f to Float.NEGATIVE_INFINITY)) assertNull(LobbyMenuEntryGate.aroundPoint(width, height, x, y))
        assertNull(LobbyMenuEntryGate.aroundPoint(0, height, 0f, 20f))
    }

    @Test fun validEdgePointKeepsBoundedPositiveInspectionArea() {
        val area = LobbyMenuEntryGate.aroundPoint(width, height, 0f, 99f)!!
        assertEquals(0, area.left); assertEquals(height, area.bottom)
        assertTrue(area.right > area.left && area.bottom > area.top)
        assertNotNull(LobbyMenuEntryGate.aroundLabel(width, height, BrawlerOcrHit("BRAWLERS", 0, 0, 1, 1)))
    }
}
