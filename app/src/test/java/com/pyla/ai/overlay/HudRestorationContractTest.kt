package com.pyla.ai.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the restored compact-HUD contract that the full-width strip regression broke:
 * the HUD is a centred pill sized to its content, it never overlaps the bubble band even with the
 * detail row open, and an unmeasured value is never rendered as a number.
 */
class HudRestorationContractTest {
    private val density = 2.75f
    private val metrics = OverlayMetrics.forDensity(density)
    private val spec = HudPillSpec.forDensity(density)
    private val phone = ScreenGeometry(widthPx = 1080, heightPx = 2400, insetTopPx = 90, insetBottomPx = 84)

    /** measure stub: 6 px per character, independent of style. */
    private val measure: (String, HudTextStyle) -> Float = { text, _ -> text.length * 6f }

    private fun frame(compact: HudCompact = HudCompact(), detail: HudDetail? = null, error: String? = null, width: Float = 900f) =
        HudPillLayout.layout(compact, detail, error, width, spec, measure)

    /** The reserved band must clear the tallest pill, otherwise opening the details covers the bubble. */
    @Test fun reservedBandClearsTallestPill() {
        val tallest = spec.rowHeight + spec.detailHeight
        assertTrue(tallest <= metrics.hudHeightPx + 0.5f)
        val band = OverlayGeometry.usableVerticalRange(phone, metrics)
        val pill = OverlayGeometry.pillRect(phone, metrics, widthPx = 600, heightPx = tallest.toInt())
        assertTrue(pill.bottom <= band.top)
    }

    /** Negative: the HUD must not be a full-width strip again. */
    @Test fun pillIsContentSizedAndCentredNotFullWidth() {
        val pill = OverlayGeometry.pillRect(phone, metrics, widthPx = 420, heightPx = 90)
        assertEquals(420, pill.width)
        assertTrue(pill.width < phone.widthPx)
        assertTrue(kotlin.math.abs(pill.centerX - phone.widthPx / 2) <= 2)
    }

    /** Negative: an unmeasured metric is an em dash, never 0 and never a fabricated FPS. */
    @Test fun unavailableMetricsAreDashesNotNumbers() {
        assertEquals(HudMetrics.EM_DASH, HudMetrics.ms(null))
        assertEquals(HudMetrics.EM_DASH, HudMetrics.ips(null))
        assertEquals(HudMetrics.EM_DASH, HudMetrics.frameAge(null))
        val compact = HudCompact(status = "STOPPED")
        assertEquals(HudMetrics.EM_DASH, compact.loop)
        assertEquals(HudMetrics.EM_DASH, compact.ips)
        val rendered = frame(compact)
        assertFalse(rendered.segments.any { it.text.contains("0 ms") || it.text.contains("fps", ignoreCase = true) })
        // Both metric runs exist (as kinds) even while they have no measurement.
        assertTrue(rendered.segments.any { it.kind == HudSegmentKind.LOOP })
        assertTrue(rendered.segments.any { it.kind == HudSegmentKind.IPS })
    }

    /** Status is evidence, never inference from metrics: MATCH text without verification is not MATCH. */
    @Test fun statusIsNotInferredWithoutVerification() {
        val unverified = HudStatusResolver.resolve(true, false, verified = false, stateLabel = HudStatus.MATCH, hasError = false)
        assertEquals(HudStatusResolver.VERIFYING, unverified.label)
        val verified = HudStatusResolver.resolve(true, false, verified = true, stateLabel = HudStatus.MATCH, hasError = false)
        assertEquals(HudStatus.MATCH, verified.label)
        assertEquals(HudStatusResolver.STOPPED, HudStatusResolver.resolve(false, false, true, HudStatus.MATCH, true).label)
        assertEquals(HudStatusResolver.PAUSED, HudStatusResolver.resolve(true, true, true, HudStatus.MATCH, true).label)
        assertEquals(HudStatusResolver.ERROR, HudStatusResolver.resolve(true, false, true, HudStatus.MATCH, true).label)
    }

    /** The expanded row is English, ordered, and only exists when asked for. */
    @Test fun detailsAppearOnlyWhenExpandedWithEnglishLabels() {
        assertNull(frame(HudCompact()).columns.takeIf { it.isNotEmpty() })
        val expanded = frame(detail = HudDetail())
        assertEquals(listOf("DETECT", "STYLE", "INPUT", "FRAME", "BACKEND"), expanded.columns.map { it.label })
        assertTrue(expanded.height > frame().height)
    }

    /** A narrow screen keeps both measured columns instead of dropping a metric. */
    @Test fun narrowScreenKeepsEveryMetricRun() {
        val narrow = frame(width = 300f)
        assertEquals(5, narrow.segments.size)
        assertTrue(narrow.width <= 300f + 0.01f)
        assertTrue(narrow.segments.all { it.left >= 0f && it.right <= 300f + 0.5f })
        assertNull(narrow.errorLine)
    }
}
