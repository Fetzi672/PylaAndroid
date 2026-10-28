package com.pyla.ai.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exact geometry for the compact pill and its optional detail row, using a deterministic 7px-per-
 * character measurer so every coordinate below is a real, hand-checkable expectation.
 */
class HudPillLayoutTest {
    private val spec = HudPillSpec(
        padH = 12f, itemGap = 10f, dividerGap = 7f, dotDiameter = 8f, dotGap = 4f,
        rowHeight = 34f, detailHeight = 34f, errorHeight = 20f, radius = 12f,
    )
    private val measure: (String, HudTextStyle) -> Float = { text, _ -> text.length * 7f }

    private val compact = HudCompact(status = "MATCH", style = "RUSH", loop = "28 ms", ips = "35.7")

    private fun layout(
        maxWidth: Float = 400f,
        compact: HudCompact = this.compact,
        detail: HudDetail? = null,
        error: String? = null,
    ) = HudPillLayout.layout(compact, detail, error, maxWidth, spec, measure)

    @Test fun compactRowTilesTheApprovedOrderWithoutGaps() {
        val frame = layout()
        assertEquals(0, frame.level)
        // PYLA | ● MATCH | RUSH | LOOP 28 ms | IPS 35.7, padded by 12px on both sides.
        assertEquals(listOf(12f, 55f, 117f, 160f, 249f), frame.segments.map { it.left })
        assertEquals(listOf(28f, 47f, 28f, 74f, 60f), frame.segments.map { it.width })
        assertEquals(321f, frame.width, 0.001f)
        assertEquals(34f, frame.height, 0.001f)
        assertEquals(
            listOf(HudSegmentKind.BRAND, HudSegmentKind.STATUS, HudSegmentKind.STYLE, HudSegmentKind.LOOP, HudSegmentKind.IPS),
            frame.segments.map { it.kind },
        )
    }

    @Test fun dividersSitBetweenGroupsAndAreVerticallyCentredByTheView() {
        val frame = layout()
        // Divider centres: 7px after each run's right edge (LOOP ends at 234, so its rule is 241).
        assertEquals(listOf(47f, 109f, 152f, 241f), frame.dividers)
        assertEquals(frame.segments.size - 1, frame.dividers.size)
        // Every divider sits strictly between two neighbouring runs.
        frame.dividers.forEachIndexed { index, x ->
            assertTrue(x > frame.segments[index].right)
            assertTrue(x < frame.segments[index + 1].left)
        }
    }

    @Test fun statusSegmentCarriesTheDotAndLeavesRoomForItsLabel() {
        val status = layout().segments.first { it.kind == HudSegmentKind.STATUS }
        assertTrue(status.dot)
        assertEquals(55f + 8f + 4f, status.valueLeft, 0.001f)
        assertEquals(35f, status.valueWidth, 0.001f)
    }

    @Test fun metricColumnsAreFixedSoDigitsNeverJitter() {
        val short = layout(compact = compact.copy(loop = "5 ms"))
        val long = layout(compact = compact.copy(loop = "888 ms"))
        assertEquals(
            short.segments.first { it.kind == HudSegmentKind.LOOP }.valueWidth,
            long.segments.first { it.kind == HudSegmentKind.LOOP }.valueWidth,
        )
        // The neighbouring IPS group does not move when the loop number grows.
        assertEquals(
            short.segments.first { it.kind == HudSegmentKind.IPS }.left,
            long.segments.first { it.kind == HudSegmentKind.IPS }.left,
        )
    }

    @Test fun missingMetricsReserveTheSameColumnAndShowTheEmDash() {
        val frame = layout(compact = compact.copy(loop = HudMetrics.EM_DASH, ips = HudMetrics.EM_DASH, style = HudMetrics.EM_DASH))
        assertEquals(HudMetrics.EM_DASH, frame.segments.first { it.kind == HudSegmentKind.LOOP }.value)
        assertEquals(HudMetrics.EM_DASH, frame.segments.first { it.kind == HudSegmentKind.IPS }.value)
        // Only the metric columns are fixed-width; the style run really is narrower as an em dash,
        // so the pill legitimately shrinks by the 21px the word "RUSH" no longer needs.
        assertEquals(300f, frame.width, 0.001f)
        val styleFree = layout()
        assertEquals(
            styleFree.segments.first { it.kind == HudSegmentKind.LOOP }.valueWidth,
            frame.segments.first { it.kind == HudSegmentKind.LOOP }.valueWidth,
        )
        assertEquals(
            styleFree.segments.first { it.kind == HudSegmentKind.IPS }.valueWidth,
            frame.segments.first { it.kind == HudSegmentKind.IPS }.valueWidth,
        )
    }

    @Test fun detailRowOnlyExistsWhileTheControlsAreOpen() {
        val closed = layout()
        val open = layout(detail = HudDetail("18 ms", "2 ms", "8 ms", "32 ms", "NNAPI"))
        assertEquals(34f, closed.height, 0.001f)
        assertTrue(closed.columns.isEmpty())
        assertEquals(68f, open.height, 0.001f)
        assertEquals(34f, open.detailTop, 0.001f)
        assertEquals(listOf("DETECT", "STYLE", "INPUT", "FRAME", "BACKEND"), open.columns.map { it.label })
        assertEquals(listOf("18 ms", "2 ms", "8 ms", "32 ms", "NNAPI"), open.columns.map { it.value })
    }

    @Test fun detailRowIsWiderThanTheCompactRowWithoutExceedingTheViewport() {
        val open = layout(maxWidth = 400f, detail = HudDetail("18 ms", "2 ms", "8 ms", "32 ms", "NNAPI"))
        val compactOnly = layout(maxWidth = 400f)
        assertTrue(open.width > compactOnly.width)
        assertTrue(open.width <= 400f)
        // The compact runs stay centred inside the widened pill.
        val shift = (open.width - compactOnly.width) / 2f
        assertEquals(compactOnly.segments.first().left + shift, open.segments.first().left, 0.001f)
        // Runs, dividers and the detail columns all travel with the widening pill, and the detail
        // row keeps its own padding instead of inheriting the compact centring shift.
        open.dividers.zip(compactOnly.dividers).forEach { (after, before) ->
            assertEquals(before + shift, after, 0.001f)
        }
        open.segments.forEach { assertTrue(it.left >= 0f && it.right <= open.width + 0.01f) }
        open.columns.forEach { assertTrue(it.left >= 0f && it.right <= open.width + 0.01f) }
        assertEquals(open.width, open.columns.last().right + spec.padH * 0.6f, 0.01f)
    }

    @Test fun detailColumnsNeverOverlapAndStayInsideThePill() {
        listOf(400f, 340f, 300f, 260f, 220f, 200f).forEach { width ->
            val frame = layout(maxWidth = width, detail = HudDetail("18 ms", "2 ms", "8 ms", "32 ms", "A-VERY-LONG-BACKEND"))
            assertTrue("width $width", frame.width <= width)
            assertTrue("width $width", frame.columns.size >= 3)
            frame.columns.zipWithNext().forEach { (a, b) -> assertTrue(a.right <= b.left) }
            frame.columns.forEach { assertTrue(it.right <= frame.width + 0.01f) }
        }
    }

    @Test fun extremeNarrowWidthsNeverOverflowAndKeepBothMetrics() {
        listOf(1080f, 720f, 480f, 360f, 320f, 300f, 280f, 240f, 200f).forEach { width ->
            val frame = layout(maxWidth = width)
            assertTrue("width $width -> ${frame.width}", frame.width <= width)
            assertTrue(frame.segments.any { it.kind == HudSegmentKind.LOOP })
            assertTrue(frame.segments.any { it.kind == HudSegmentKind.IPS })
            assertEquals("28 ms", frame.segments.first { it.kind == HudSegmentKind.LOOP }.value)
            assertEquals("35.7", frame.segments.first { it.kind == HudSegmentKind.IPS }.value)
            frame.segments.forEach { assertTrue(it.left >= 0f) }
        }
    }

    @Test fun spacingTightensBeforeAnyContentIsDropped() {
        val wide = layout(maxWidth = 400f)
        val tight = layout(maxWidth = 300f)
        assertEquals(0, wide.level)
        assertEquals(1, tight.level)
        assertEquals(wide.segments.size, tight.segments.size)
        assertTrue(tight.width < wide.width)
    }

    @Test fun longPlaystyleIsEllipsizedBeforeItIsDropped() {
        val frame = layout(maxWidth = 270f, compact = compact.copy(style = "BRAWL BALL"))
        assertEquals(3, frame.level)
        val style = frame.segments.first { it.kind == HudSegmentKind.STYLE }
        assertTrue(style.text.endsWith("…"))
        assertTrue(frame.segments.any { it.kind == HudSegmentKind.STYLE })
        assertTrue(frame.segments.any { it.kind == HudSegmentKind.LOOP })
        assertTrue(frame.segments.any { it.kind == HudSegmentKind.IPS })
        assertTrue(frame.width <= 270f)
    }

    @Test    fun styleIsTheOnlyGroupDroppedWhenNothingElseFits() {
        // 240px still fits the labels-on row once the style is dropped: level 4, not the
        // labels-free level 5.
        val frame = layout(maxWidth = 240f)
        assertEquals(4, frame.level)
        assertFalse(frame.segments.any { it.kind == HudSegmentKind.STYLE })
        assertTrue(frame.segments.any { it.kind == HudSegmentKind.STATUS })
        assertTrue(frame.width <= 240f)
    }

    @Test fun errorLineIsTruncatedInsideThePillAndAddsAThirdRow() {
        val long = "Guard rejected at frame 12 because the target left the verified region"
        val frame = layout(maxWidth = 300f, detail = HudDetail(), error = long)
        val line = frame.errorLine!!
        assertTrue(line.text.startsWith("ERR "))
        assertTrue(line.text.endsWith("…"))
        assertTrue(line.width <= 300f - 2 * spec.padH * 0.6f + 0.01f)
        assertEquals(34f + 34f + 20f, frame.height, 0.001f)
        // The error line is the third row: it starts below the compact row AND the detail row.
        assertEquals(68f, line.top, 0.001f)
        assertTrue(line.left + line.width <= frame.width + 0.01f)
    }

    @Test fun errorLineIsAbsentWhenThereIsNoError() {
        assertNull(layout(detail = HudDetail()).errorLine)
        assertNull(layout(error = "   ").errorLine)
    }

    @Test fun zeroBudgetDegradesInsteadOfThrowing() {
        val frame = layout(maxWidth = 0f)
        assertEquals(0f, frame.width, 0.001f)
        assertEquals(34f, frame.height, 0.001f)
    }

    @Test fun densityScalingKeepsTheSpecProportional() {
        val one = HudPillSpec.forDensity(1f)
        val three = HudPillSpec.forDensity(3f)
        assertEquals(one.padH * 3f, three.padH, 0.001f)
        assertEquals(one.rowHeight * 3f, three.rowHeight, 0.001f)
        assertEquals(one.radius * 3f, three.radius, 0.001f)
    }

    @Test fun everyVisibleRunStaysInsideThePillWhenTheDetailRowWidensIt() {
        listOf(400f, 360f, 320f, 280f, 240f, 200f, 160f, 120f).forEach { width ->
            val frame = layout(maxWidth = width, detail = HudDetail("18 ms", "2 ms", "8 ms", "32 ms", "NNAPI"), error = "boom")
            assertTrue("width $width frame ${frame.width}", frame.width <= width + 0.01f)
            frame.segments.forEach {
                assertTrue("width $width ${it.kind} left ${it.left}", it.left >= -0.01f)
                assertTrue("width $width ${it.kind} right ${it.right}", it.right <= frame.width + 0.01f)
            }
            frame.dividers.forEach { x ->
                assertTrue("width $width divider $x", x > 0f && x < frame.width)
            }
            frame.columns.zipWithNext().forEach { (a, b) -> assertTrue("width $width", a.right <= b.left + 0.01f) }
            frame.columns.forEach {
                assertTrue("width $width ${it.label} right ${it.right}", it.right <= frame.width + 0.01f)
                assertTrue("width $width ${it.label} left ${it.left}", it.left >= -0.01f)
            }
            frame.errorLine?.let { assertTrue("width $width", it.left + it.width <= frame.width + 0.01f) }
        }
    }

    @Test fun budgetsBelowTheLabelsFreeRowCompressInsteadOfClipping() {
        // 160px is narrower than the labels-free row (~193px), so text is shortened but no run may
        // reach outside the frame and both metric groups must survive.
        val frame = layout(maxWidth = 160f)
        assertEquals(6, frame.level)
        assertEquals(160f, frame.width, 0.01f)
        assertTrue(frame.segments.any { it.kind == HudSegmentKind.LOOP })
        assertTrue(frame.segments.any { it.kind == HudSegmentKind.IPS })
        frame.segments.forEach {
            assertTrue(it.left >= -0.01f)
            assertTrue("${it.kind} right ${it.right}", it.right <= frame.width + 0.01f)
        }
        frame.segments.zipWithNext().forEach { (a, b) -> assertTrue(a.right <= b.left + 0.01f) }
    }

    @Test fun detailRowIsOmittedRatherThanOverflowingWhenItsColumnsCannotFit() {
        val frame = layout(maxWidth = 150f, detail = HudDetail("18 ms", "2 ms", "8 ms", "32 ms", "NNAPI"))
        assertTrue(frame.columns.isEmpty())
        assertTrue(frame.width <= 150f)
        frame.segments.forEach { assertTrue(it.right <= frame.width + 0.01f) }
    }
}
