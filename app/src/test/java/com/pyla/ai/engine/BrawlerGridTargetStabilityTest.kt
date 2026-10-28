package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit contract for [BrawlerGridTargetStability].
 *
 * The helper is deliberately pure: it derives a bounded inspection window from ONE observed target
 * card box and answers whether that window is unchanged between two captures. These tests pin the
 * three things the selector relies on:
 *
 *  - the window covers the observed label plus the SAME geometry slack the lobby handoff uses;
 *  - pixel change OUTSIDE that window (preview/banner animation, other cards) is ignored;
 *  - change INSIDE it (moved, covered, altered label), a different capture geometry or a malformed
 *    buffer still fails closed, with the UNCHANGED 5%-changed / mean-RGB-delta<=9 policy.
 */
class BrawlerGridTargetStabilityTest {

    private val width = 1280
    private val height = 591
    private val base = 0xff3a3a3a.toInt()
    private val ink = 0xffd8c070.toInt()

    private fun frame(mutate: (IntArray) -> Unit = {}): FrameSnapshot {
        val argb = IntArray(width * height) { base }
        mutate(argb)
        return FrameSnapshot(width, height, argb, 1_000L)
    }

    private fun put(argb: IntArray, left: Int, top: Int, right: Int, bottom: Int, colour: Int) {
        for (y in top until bottom) for (x in left until right) argb[y * width + x] = colour
    }

    private val target = BrawlerOcrHit("NORI", 560, 300, 660, 340)

    @Test
    fun windowCoversTheObservedLabelPlusGeometrySlack() {
        val windows = BrawlerGridTargetStability.windows(width, height, target)
        assertNotNull(windows)
        assertEquals(1, windows!!.size)
        val w = windows.single()
        val slack = BrawlerSelectionGate.tolerancePx(height)
        assertTrue(slack >= 4)
        assertEquals((target.left - slack).coerceAtLeast(0), w.left)
        assertEquals((target.top - slack).coerceAtLeast(0), w.top)
        assertEquals((target.right + slack).coerceAtMost(width), w.right)
        assertEquals((target.bottom + slack).coerceAtMost(height), w.bottom)
    }

    @Test
    fun windowIsNullForADegenerateOrOutOfFrameBox() {
        assertNull(BrawlerGridTargetStability.windows(0, height, target))
        assertNull(BrawlerGridTargetStability.windows(width, 0, target))
        assertNull(BrawlerGridTargetStability.windows(width, height, BrawlerOcrHit("NORI", 600, 300, 600, 340)))
        assertNull(BrawlerGridTargetStability.windows(width, height, BrawlerOcrHit("NORI", -40, 300, 10, 340)))
        assertNull(BrawlerGridTargetStability.windows(width, height, BrawlerOcrHit("NORI", 560, 300, 9000, 340)))
    }

    @Test
    fun animationOutsideTheTargetWindowIsIgnored() {
        val first = frame { put(it, target.left, target.top, target.right, target.bottom, ink) }
        val second = frame {
            put(it, target.left, target.top, target.right, target.bottom, ink)
            // Right-hand preview / banner band only, far outside the observed target label.
            put(it, width * 72 / 100, height * 20 / 100, width, height * 86 / 100, 0xffd02070.toInt())
            put(it, 0, 0, width, height * 10 / 100, 0xff30a0d0.toInt())
        }
        val windows = BrawlerGridTargetStability.windows(width, height, target)!!
        assertTrue(BrawlerGridTargetStability.targetStill(first, second, windows))
    }

    @Test
    fun changeInsideTheTargetWindowIsRefused() {
        val first = frame { put(it, target.left, target.top, target.right, target.bottom, ink) }
        // The label moved away: its observed window no longer holds what was confirmed.
        val moved = frame { put(it, target.left + 120, target.top, target.right + 120, target.bottom, ink) }
        // The label got covered.
        val covered = frame {
            put(it, target.left, target.top, target.right, target.bottom, ink)
            put(it, target.left, target.top, target.right, target.bottom, 0xff20ff20.toInt())
        }
        val windows = BrawlerGridTargetStability.windows(width, height, target)!!
        assertFalse(BrawlerGridTargetStability.targetStill(first, moved, windows))
        assertFalse(BrawlerGridTargetStability.targetStill(first, covered, windows))
    }

    @Test
    fun malformedInputsFailClosed() {
        val first = frame { put(it, target.left, target.top, target.right, target.bottom, ink) }
        val windows = BrawlerGridTargetStability.windows(width, height, target)!!
        assertFalse(BrawlerGridTargetStability.targetStill(first, first, emptyList()))
        assertFalse(BrawlerGridTargetStability.targetStill(
            first, FrameSnapshot(width + 1, height, IntArray((width + 1) * height), 1_000L), windows))
        assertFalse(BrawlerGridTargetStability.targetStill(
            first, FrameSnapshot(width, height, IntArray(10), 1_000L), windows))
        assertFalse(BrawlerGridTargetStability.targetStill(
            first, FrameSnapshot(width, height, IntArray(width * height), 1_000L), windows))
    }

    @Test
    fun unchangedTargetUnderThreePercentNoiseStillPasses() {
        // Policy parity: a few changed samples inside the window are tolerated exactly as the
        // unchanged pixelsStill policy tolerates them; the helper adds no threshold of its own.
        val first = frame { put(it, target.left, target.top, target.right, target.bottom, ink) }
        val second = frame {
            put(it, target.left, target.top, target.right, target.bottom, ink)
            for (i in 0 until 40) {
                val y = target.top + 4 + i / 20
                val x = target.left + 4 + i % 20
                val index = y * width + x
                val colour = it[index]
                val red = (((colour ushr 16) and 255) + 3).coerceAtMost(255)
                val green = (((colour ushr 8) and 255) + 2).coerceAtMost(255)
                val blue = ((colour and 255) + 1).coerceAtMost(255)
                it[index] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
        val windows = BrawlerGridTargetStability.windows(width, height, target)!!
        assertTrue(BrawlerGridTargetStability.targetStill(first, second, windows))
    }

    private fun nudge(frame: FrameSnapshot, x: Int, y: Int) {
        val c = frame.argb[y * width + x]
        val r = ((c ushr 16) and 255); val g = ((c ushr 8) and 255); val b = (c and 255)
        val shifted = (0xff shl 24) or ((r + 3).coerceAtMost(255) shl 16) or
            ((g + 2).coerceAtMost(255) shl 8) or (b + 1).coerceAtMost(255)
        frame.argb[y * width + x] = shifted
    }

    @Test
    fun windowSlackIsSymmetricAroundTheObservedBox() {
        val narrow = BrawlerOcrHit("NORI", 10, 10, 20, 20)
        val windows = BrawlerGridTargetStability.windows(width, height, narrow)!!
        val w = windows.single()
        assertTrue(w.left >= 0 && w.top >= 0 && w.right <= width && w.bottom <= height)
        assertTrue(w.right > w.left && w.bottom > w.top)
        // A second identical call must be deterministic (no hidden clock/state).
        assertEquals(w, BrawlerGridTargetStability.windows(width, height, narrow)!!.single())
        assertTrue(abs((w.right - w.left) - (narrow.width + 2 * BrawlerSelectionGate.tolerancePx(height))) <= 1)
    }
}
