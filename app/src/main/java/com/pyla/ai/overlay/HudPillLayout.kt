package com.pyla.ai.overlay

import kotlin.math.max
import kotlin.math.min

/** Text runs the pill draws. Each style maps to one Paint in the view. */
enum class HudTextStyle { BRAND, STATUS, STYLE, LABEL, VALUE, DETAIL_LABEL, DETAIL_VALUE, ERROR }

/** Compact row content, already formatted by [HudMetrics]. */
data class HudCompact(
    val brand: String = "PYLA",
    val status: String = "STOPPED",
    val style: String = HudMetrics.EM_DASH,
    val loop: String = HudMetrics.EM_DASH,
    val ips: String = HudMetrics.EM_DASH,
)

/** Optional detail row, shown only while the bubble controls are open. */
data class HudDetail(
    val detect: String = HudMetrics.EM_DASH,
    val style: String = HudMetrics.EM_DASH,
    val input: String = HudMetrics.EM_DASH,
    val frame: String = HudMetrics.EM_DASH,
    val backend: String = HudMetrics.EM_DASH,
)

/** Density-scaled pill geometry constants. */
data class HudPillSpec(
    val padH: Float,
    val itemGap: Float,
    val dividerGap: Float,
    val dotDiameter: Float,
    val dotGap: Float,
    val rowHeight: Float,
    val detailHeight: Float,
    val errorHeight: Float,
    val radius: Float,
    val loopSample: String = "888 ms",
    val ipsSample: String = "888.8",
    val detailSample: String = "888.8 ms",
) {
    companion object {
        const val PAD_H_DP = 12f
        const val ITEM_GAP_DP = 10f
        const val DIVIDER_GAP_DP = 7f
        const val DOT_DP = 7f
        const val DOT_GAP_DP = 5f
        const val ROW_HEIGHT_DP = 34f
        const val DETAIL_HEIGHT_DP = 34f
        const val ERROR_HEIGHT_DP = 20f
        const val RADIUS_DP = 12f

        fun forDensity(density: Float): HudPillSpec {
            val d = if (density.isFinite() && density > 0f) density else 1f
            fun px(dp: Float) = (dp * d).coerceAtLeast(0.5f)
            return HudPillSpec(
                padH = px(PAD_H_DP), itemGap = px(ITEM_GAP_DP), dividerGap = px(DIVIDER_GAP_DP),
                dotDiameter = px(DOT_DP), dotGap = px(DOT_GAP_DP), rowHeight = px(ROW_HEIGHT_DP),
                detailHeight = px(DETAIL_HEIGHT_DP), errorHeight = px(ERROR_HEIGHT_DP), radius = px(RADIUS_DP),
            )
        }
    }
}

enum class HudSegmentKind { BRAND, STATUS, STYLE, LOOP, IPS }

/**
 * One laid-out run group. LOOP/IPS reserve a fixed value column ([valueWidth]) so a changing number
 * never shifts its neighbours; labels and values are left-aligned inside that column.
 */
data class HudSegment(
    val kind: HudSegmentKind,
    val left: Float,
    val width: Float,
    val text: String = "",
    val label: String? = null,
    val labelLeft: Float = 0f,
    val labelWidth: Float = 0f,
    val value: String = "",
    val valueLeft: Float = 0f,
    val valueWidth: Float = 0f,
    val dot: Boolean = false,
) {
    val right: Float get() = left + width
}

/** One stacked label/value column of the detail row, centred within [width]. */
data class HudColumn(val label: String, val value: String, val left: Float, val width: Float, val top: Float) {
    val right: Float get() = left + width
}

/** The truncated error line drawn under the compact row while the controls are open. */
data class HudErrorLine(val text: String, val left: Float, val top: Float, val width: Float)

data class HudPillFrame(
    val width: Float,
    val height: Float,
    val level: Int,
    val segments: List<HudSegment>,
    val dividers: List<Float>,
    val columns: List<HudColumn> = emptyList(),
    val detailTop: Float = 0f,
    val errorLine: HudErrorLine? = null,
) {
    val hasDetail: Boolean get() = columns.isNotEmpty()
}

/**
 * Deterministic pill layout. Pure Kotlin: text is measured through the `measure` callback, so
 * production passes `Paint::measureText` and tests pass a stub with exact expectations.
 *
 * Narrow-screen ladder (a measured metric is never dropped):
 *  0 standard spacing, 1 tight spacing, 2 drop dividers, 3 ellipsize style,
 *  4 drop style, 5 drop LOOP/IPS labels (values only).
 */
object HudPillLayout {
    private val DETAIL_LABELS = listOf("DETECT", "STYLE", "INPUT", "FRAME", "BACKEND")

    fun layout(
        compact: HudCompact,
        detail: HudDetail?,
        errorText: String?,
        maxWidth: Float,
        spec: HudPillSpec,
        measure: (String, HudTextStyle) -> Float,
    ): HudPillFrame {
        val budget = if (maxWidth.isFinite() && maxWidth > 0f) maxWidth else 0f
        val fit = fitter(measure)
        val loopColumn = max(measure(spec.loopSample, HudTextStyle.VALUE), measure(HudMetrics.EM_DASH, HudTextStyle.VALUE))
        val ipsColumn = max(measure(spec.ipsSample, HudTextStyle.VALUE), measure(HudMetrics.EM_DASH, HudTextStyle.VALUE))

        val standard = Config(spec.padH, spec.itemGap, spec.dividerGap, dividers = true, labels = true)
        val tight = Config(spec.padH * 0.6f, spec.itemGap * 0.6f, spec.dividerGap * 0.6f, dividers = true, labels = true)
        val plain = tight.copy(dividers = false)
        val minimal = Config(spec.padH * 0.2f, spec.itemGap * 0.2f, 0f, dividers = false, labels = false)

        var level = 0
        var current = assemble(compact, standard, spec, loopColumn, ipsColumn, measure)
        if (current.width > budget + 0.01f) {
            current = assemble(compact, tight, spec, loopColumn, ipsColumn, measure); level = 1
        }
        if (current.width > budget + 0.01f) {
            current = assemble(compact, plain, spec, loopColumn, ipsColumn, measure); level = 2
        }
        if (current.width > budget + 0.01f) {
            // Keep every field but shorten the style label into the space that is actually left.
            val withoutStyle = assemble(
                compact.copy(style = ""), plain.copy(styleVisible = false), spec, loopColumn, ipsColumn, measure,
            )
            // Re-inserting the style run also re-adds one inter-group gap, so that gap is spent first.
            val available = budget - withoutStyle.width - plain.gap
            val styleText = if (available > measure("…", HudTextStyle.STYLE) + measure("M", HudTextStyle.STYLE)) {
                fit(compact.style, available, HudTextStyle.STYLE)
            } else {
                ""
            }
            current = assemble(
                compact.copy(style = styleText), plain.copy(styleVisible = styleText.isNotEmpty()),
                spec, loopColumn, ipsColumn, measure,
            )
            level = if (styleText.isEmpty()) 4 else 3
        }
        if (current.width > budget + 0.01f) {
            current = assemble(compact, minimal, spec, loopColumn, ipsColumn, measure); level = 5
        }
        if (current.width > budget + 0.01f) {
            // Nothing left to drop without losing a measured metric: compress the whole row
            // proportionally and ellipsize each run into its own scaled box. Geometry stays inside
            // the frame instead of being clipped by the window.
            current = compress(current, budget, spec, measure); level = 6
        }

        val compactWidth = min(current.width, budget)
        val detailLayout = detail?.let { buildDetail(it, budget, spec, measure) }
        val width = max(compactWidth, min(detailLayout?.width ?: 0f, budget))
        // The error row is fitted to the frame that actually exists, not to the larger budget: on a
        // narrower compact row a budget-sized line would stick out past the pill's right edge.
        val errorLayout = buildError(
            errorText, width, spec, measure,
            top = spec.rowHeight + if (detailLayout != null) spec.detailHeight else 0f,
        )
        // Each row is centred inside the final frame on its own terms: the compact row only widens
        // when the detail row is wider, and the detail/error rows keep their own internal padding.
        val shift = (width - current.width).coerceAtLeast(0f) / 2f
        val detailShift = (width - (detailLayout?.width ?: 0f)).coerceAtLeast(0f) / 2f
        val errorShift = (width - (errorLayout?.line?.width ?: 0f)).coerceAtLeast(0f) / 2f
        val height = spec.rowHeight +
            (if (detailLayout != null) spec.detailHeight else 0f) +
            (if (errorLayout != null) spec.errorHeight else 0f)

        return HudPillFrame(
            width = width,
            height = height,
            level = level,
            segments = current.segments.map { it.shiftedBy(shift) },
            dividers = current.dividers.map { it + shift },
            columns = detailLayout?.columns.orEmpty().map { it.copy(left = it.left + detailShift) },
            detailTop = spec.rowHeight,
            errorLine = errorLayout?.line?.let { it.copy(left = it.left + errorShift) },
        )
    }

    /** Move a run (and its label/value anchors) sideways by [dx]. */
    private fun HudSegment.shiftedBy(dx: Float): HudSegment {
        if (dx == 0f) return this
        return copy(
            left = left + dx,
            labelLeft = if (label != null) labelLeft + dx else labelLeft,
            valueLeft = when (kind) {
                HudSegmentKind.STATUS, HudSegmentKind.LOOP, HudSegmentKind.IPS -> valueLeft + dx
                else -> valueLeft
            },
        )
    }

    /**
     * Last-resort fit for budgets narrower than the labels-free row: every run keeps its place in
     * the order, but positions, boxes and text are scaled/ellipsized so the row ends exactly at
     * [budget]. Both metric columns survive as kinds; only their text can be shortened.
     */
    private fun compress(
        assembled: Assembled,
        budget: Float,
        spec: HudPillSpec,
        measure: (String, HudTextStyle) -> Float,
    ): Assembled {
        if (assembled.width <= 0f || budget <= 0f) return Assembled(budget.coerceAtLeast(0f), emptyList(), emptyList())
        val k = (budget / assembled.width).coerceIn(0f, 1f)
        val fit = fitter(measure)
        val segments = assembled.segments.map { segment ->
            val left = segment.left * k
            val width = segment.width * k
            val keepsDot = segment.dot && width >= spec.dotDiameter + spec.dotGap
            val valueLeft = left + if (keepsDot) spec.dotDiameter + spec.dotGap else 0f
            val valueWidth = (width - (valueLeft - left)).coerceAtLeast(0f)
            when (segment.kind) {
                HudSegmentKind.BRAND ->
                    segment.copy(left = left, width = width, text = fit(segment.text, valueWidth, HudTextStyle.BRAND))
                HudSegmentKind.STATUS ->
                    segment.copy(
                        left = left, width = width, dot = keepsDot,
                        text = fit(segment.text, valueWidth, HudTextStyle.STATUS),
                        valueLeft = valueLeft, valueWidth = valueWidth,
                    )
                HudSegmentKind.STYLE ->
                    segment.copy(left = left, width = width, text = fit(segment.text, valueWidth, HudTextStyle.STYLE))
                HudSegmentKind.LOOP, HudSegmentKind.IPS -> {
                    val label = segment.label?.let { raw ->
                        val labelWidth = segment.labelWidth * k
                        if (labelWidth > measure("…", HudTextStyle.LABEL) + measure("M", HudTextStyle.LABEL)) {
                            fit(raw, labelWidth, HudTextStyle.LABEL)
                        } else {
                            null
                        }
                    }
                    val labelWidth = if (label == null) 0f else (segment.labelWidth * k).coerceAtMost(valueWidth)
                    val textLeft = left + labelWidth
                    val textWidth = (width - labelWidth).coerceAtLeast(0f)
                    segment.copy(
                        left = left, width = width, label = label,
                        labelLeft = left, labelWidth = labelWidth,
                        value = fit(segment.value, textWidth, HudTextStyle.VALUE),
                        valueLeft = textLeft, valueWidth = textWidth,
                    )
                }
            }
        }
        return Assembled(budget, segments, emptyList())
    }

    // ------------------------------------------------------------------ internal assembly

    private data class Config(
        val pad: Float,
        val gap: Float,
        val dividerGap: Float,
        val dividers: Boolean,
        val labels: Boolean,
        val styleVisible: Boolean = true,
    )

    private data class Assembled(val width: Float, val segments: List<HudSegment>, val dividers: List<Float>)

    private fun assemble(
        compact: HudCompact,
        config: Config,
        spec: HudPillSpec,
        loopColumn: Float,
        ipsColumn: Float,
        measure: (String, HudTextStyle) -> Float,
    ): Assembled {
        val styleText = if (config.styleVisible) compact.style else ""
        val kinds = buildList {
            add(HudSegmentKind.BRAND)
            add(HudSegmentKind.STATUS)
            if (styleText.isNotEmpty()) add(HudSegmentKind.STYLE)
            add(HudSegmentKind.LOOP)
            add(HudSegmentKind.IPS)
        }
        val loopLabel = if (config.labels) "LOOP" else null
        val ipsLabel = if (config.labels) "IPS" else null
        val labelGap = config.gap * 0.4f
        val loopLabelWidth = loopLabel?.let { measure(it, HudTextStyle.LABEL) + labelGap } ?: 0f
        val ipsLabelWidth = ipsLabel?.let { measure(it, HudTextStyle.LABEL) + labelGap } ?: 0f
        val statusTextLeft = spec.dotDiameter + spec.dotGap

        val widths = kinds.map { kind ->
            when (kind) {
                HudSegmentKind.BRAND -> measure(compact.brand, HudTextStyle.BRAND)
                HudSegmentKind.STATUS -> statusTextLeft + measure(compact.status, HudTextStyle.STATUS)
                HudSegmentKind.STYLE -> measure(styleText, HudTextStyle.STYLE)
                HudSegmentKind.LOOP -> loopLabelWidth + loopColumn
                HudSegmentKind.IPS -> ipsLabelWidth + ipsColumn
            }
        }
        val separator = if (config.dividers) config.dividerGap * 2f + 1f else config.gap
        var x = config.pad
        val segments = ArrayList<HudSegment>(kinds.size)
        val dividers = ArrayList<Float>(kinds.size)
        kinds.forEachIndexed { index, kind ->
            if (index > 0) {
                if (config.dividers) dividers += x + config.dividerGap
                x += separator
            }
            val w = widths[index]
            val segment = when (kind) {
                HudSegmentKind.BRAND -> HudSegment(kind, x, w, text = compact.brand)
                HudSegmentKind.STATUS -> HudSegment(
                    kind, x, w, text = compact.status, dot = true,
                    valueLeft = x + statusTextLeft, valueWidth = w - statusTextLeft,
                )
                HudSegmentKind.STYLE -> HudSegment(kind, x, w, text = styleText)
                HudSegmentKind.LOOP -> HudSegment(
                    kind, x, w, label = loopLabel, labelLeft = x, labelWidth = loopLabelWidth,
                    value = compact.loop, valueLeft = x + loopLabelWidth, valueWidth = loopColumn,
                )
                HudSegmentKind.IPS -> HudSegment(
                    kind, x, w, label = ipsLabel, labelLeft = x, labelWidth = ipsLabelWidth,
                    value = compact.ips, valueLeft = x + ipsLabelWidth, valueWidth = ipsColumn,
                )
            }
            segments += segment
            x += w
        }
        return Assembled(if (segments.isEmpty()) 0f else x + config.pad, segments, dividers)
    }

    private data class DetailLayout(val width: Float, val columns: List<HudColumn>)

    private data class ErrorLayout(val line: HudErrorLine)

    private fun buildDetail(
        detail: HudDetail,
        budget: Float,
        spec: HudPillSpec,
        measure: (String, HudTextStyle) -> Float,
    ): DetailLayout? {
        val fit = fitter(measure)
        val values = mutableListOf(detail.detect, detail.style, detail.input, detail.frame, detail.backend)
        val labels = DETAIL_LABELS.toMutableList()
        var gap = spec.itemGap
        var pad = spec.padH * 0.6f
        fun columnWidth(index: Int): Float {
            val labelW = measure(labels[index], HudTextStyle.DETAIL_LABEL)
            val valueW = if (index == 4) {
                measure(values[index], HudTextStyle.DETAIL_VALUE)
            } else {
                // Fixed budget column: a changing number never resizes the row.
                max(measure(values[index], HudTextStyle.DETAIL_VALUE), measure(spec.detailSample, HudTextStyle.DETAIL_VALUE))
            }
            return max(labelW, valueW)
        }
        fun total(): Float = pad * 2f + (0 until values.size).sumOf { columnWidth(it).toDouble() }.toFloat() +
            gap * (values.size - 1).coerceAtLeast(0)

        if (total() > budget) {
            val rest = pad * 2f + (0 until 4).sumOf { columnWidth(it).toDouble() }.toFloat() + gap * 4f
            val available = budget - rest
            val ellipsis = measure("…", HudTextStyle.DETAIL_VALUE)
            values[4] = if (available > ellipsis) fit(detail.backend, available, HudTextStyle.DETAIL_VALUE) else ""
        }
        // Free-form backend first, then optional columns from the right.
        if (values[4].isBlank() && values.size == 5) {
            values.removeAt(4); labels.removeAt(4)
        }
        while (total() > budget && values.size > 3) {
            values.removeAt(values.size - 1); labels.removeAt(labels.size - 1)
        }
        if (total() > budget) {
            // Last resort for very narrow portrait widths: tighten the internal spacing.
            gap *= 0.3f
            pad *= 0.3f
        }
        // The detail row is optional: if even the tightened three columns cannot fit, omit the row
        // rather than emit columns that reach outside the pill.
        if (values.isEmpty() || total() > budget + 0.01f) return null
        var x = pad
        val columns = values.indices.map { index ->
            val w = columnWidth(index)
            HudColumn(labels[index], values[index], x, w, spec.rowHeight).also { x += w + gap }
        }
        return DetailLayout(total(), columns)
    }

    private fun buildError(
        errorText: String?,
        budget: Float,
        spec: HudPillSpec,
        measure: (String, HudTextStyle) -> Float,
        top: Float,
    ): ErrorLayout? {
        val text = HudMetrics.error(errorText) ?: return null
        val pad = spec.padH * 0.6f
        val available = budget - pad * 2f
        if (available <= measure("…", HudTextStyle.ERROR)) return null
        val fitted = fitter(measure)("ERR $text", available, HudTextStyle.ERROR)
        return ErrorLayout(HudErrorLine(fitted, pad, top, min(measure(fitted, HudTextStyle.ERROR), available)))
    }

    private fun fitter(measure: (String, HudTextStyle) -> Float): (String, Float, HudTextStyle) -> String =
        { text, maxWidth, style -> OverlayStatusText.fit(text, maxWidth, measure = { candidate -> measure(candidate, style) }) }
}
