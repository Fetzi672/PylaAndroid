package com.pyla.ai.overlay

import kotlin.math.abs
import kotlin.math.roundToInt

/** Side the main bubble is docked to. Persisted across restarts. */
enum class DockSide { LEFT, RIGHT;
    fun opposite(): DockSide = if (this == LEFT) RIGHT else LEFT
}

/** Pure integer rectangle in screen pixels (origin top-left). No Android dependency. */
data class OverlayRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun overlaps(other: OverlayRect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * Immutable description of the usable display area and the system insets that must be respected.
 * Captured from WindowMetrics/display metrics at install and on every configuration change.
 */
data class ScreenGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val insetLeftPx: Int = 0,
    val insetTopPx: Int = 0,
    val insetRightPx: Int = 0,
    val insetBottomPx: Int = 0,
)

/** Density-derived pixel sizes. Built once per configuration from dp constants. */
data class OverlayMetrics(
    val bubbleSizePx: Int,
    val controlSizePx: Int,
    val statusHeightPx: Int,
    val edgeMarginPx: Int,
    val controlGapPx: Int,
    val statusTopGapPx: Int,
    val dragSlopPx: Int,
) {
    companion object {
        const val BUBBLE_DP = 48f
        const val CONTROL_DP = 48f
        const val STATUS_DP = 28f
        const val EDGE_MARGIN_DP = 8f
        const val CONTROL_GAP_DP = 8f
        const val STATUS_TOP_GAP_DP = 4f
        const val DRAG_SLOP_DP = 6f

        fun forDensity(density: Float): OverlayMetrics {
            val d = if (density.isFinite() && density > 0f) density else 1f
            fun px(dp: Float) = (dp * d).roundToInt().coerceAtLeast(1)
            return OverlayMetrics(
                bubbleSizePx = px(BUBBLE_DP),
                controlSizePx = px(CONTROL_DP),
                statusHeightPx = px(STATUS_DP),
                edgeMarginPx = px(EDGE_MARGIN_DP),
                controlGapPx = px(CONTROL_GAP_DP),
                statusTopGapPx = px(STATUS_TOP_GAP_DP),
                dragSlopPx = px(DRAG_SLOP_DP),
            )
        }
    }
}

/**
 * Deterministic, side-effect-free layout math for the four independently bounded windows.
 * Every function is pure so it can be exercised by local JVM unit tests without an emulator.
 */
object OverlayGeometry {
    const val DEFAULT_RELATIVE_Y = 0.30f

    fun clampRelativeY(value: Float): Float =
        if (!value.isFinite()) DEFAULT_RELATIVE_Y else value.coerceIn(0f, 1f)

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).roundToInt()

    /**
     * Vertical band (top inclusive, bottom exclusive) usable by the bubble and controls. It sits
     * below the status strip (which is below the status bar / cutout) and above the navigation bar.
     */
    fun usableVerticalRange(g: ScreenGeometry, m: OverlayMetrics): OverlayRect {
        val top = g.insetTopPx + m.statusTopGapPx + m.statusHeightPx + m.edgeMarginPx
        val bottom = (g.heightPx - g.insetBottomPx - m.edgeMarginPx).coerceAtLeast(top + 1)
        return OverlayRect(0, top, g.widthPx, bottom)
    }

    /** Top coordinate for a square of [sizePx] placed at [relativeY] (0..1) in the usable band. */
    fun positionY(g: ScreenGeometry, m: OverlayMetrics, sizePx: Int, relativeY: Float): Int {
        val band = usableVerticalRange(g, m)
        val maxTop = (band.bottom - sizePx).coerceAtLeast(band.top)
        val rel = clampRelativeY(relativeY)
        return (band.top + (maxTop - band.top) * rel).roundToInt().coerceIn(band.top, maxTop)
    }

    /** Normalized vertical position recovered from a free-floating top coordinate. */
    fun relativeYForTop(top: Int, g: ScreenGeometry, m: OverlayMetrics, sizePx: Int): Float {
        val band = usableVerticalRange(g, m)
        val maxTop = (band.bottom - sizePx).coerceAtLeast(band.top)
        val span = maxTop - band.top
        if (span <= 0) return 0f
        return ((top - band.top).toFloat() / span).coerceIn(0f, 1f)
    }

    /** Fully revealed (t=1) and half-hidden (t=0) left coordinate for the docked bubble. */
    private fun bubbleLeft(side: DockSide, g: ScreenGeometry, m: OverlayMetrics, t: Float): Int {
        val size = m.bubbleSizePx
        return if (side == DockSide.RIGHT) {
            val revealed = g.widthPx - g.insetRightPx - m.edgeMarginPx - size
            val hidden = g.widthPx - size / 2
            lerp(hidden, revealed, t)
        } else {
            val revealed = g.insetLeftPx + m.edgeMarginPx
            val hidden = -size / 2
            lerp(hidden, revealed, t)
        }
    }

    /**
     * Main bubble rectangle. [revealProgress] 0 = half hidden at the docked edge,
     * 1 = fully visible inside the safe area.
     */
    fun bubbleRect(side: DockSide, g: ScreenGeometry, m: OverlayMetrics, relativeY: Float, revealProgress: Float): OverlayRect {
        val t = revealProgress.coerceIn(0f, 1f)
        val size = m.bubbleSizePx
        val top = positionY(g, m, size, relativeY)
        val left = bubbleLeft(side, g, m, t)
        return OverlayRect(left, top, left + size, top + size)
    }

    /**
     * The three action controls stacked vertically inward from the (revealed) bubble.
     * Index 0 = Start (top), 1 = Pause/Resume (middle), 2 = Stop (bottom).
     */
    fun controlRects(side: DockSide, g: ScreenGeometry, m: OverlayMetrics, relativeY: Float): List<OverlayRect> {
        val size = m.controlSizePx
        val gap = m.controlGapPx
        val bubble = bubbleRect(side, g, m, relativeY, 1f)
        val band = usableVerticalRange(g, m)
        val columnHeight = size * 3 + gap * 2
        val maxTop = (band.bottom - columnHeight).coerceAtLeast(band.top)
        val desiredTop = bubble.centerY - columnHeight / 2
        val columnTop = desiredTop.coerceIn(band.top, maxTop)
        val controlTopMax = (band.bottom - size).coerceAtLeast(band.top)

        val preferredLeft = if (side == DockSide.RIGHT) bubble.left - gap - size else bubble.right + gap
        val minLeft = g.insetLeftPx + m.edgeMarginPx
        val maxLeft = (g.widthPx - g.insetRightPx - m.edgeMarginPx - size).coerceAtLeast(minLeft)
        val left = preferredLeft.coerceIn(minLeft, maxLeft)

        return (0..2).map { i ->
            // Evenly spaced when the column fits; individually clamped on very short displays so no
            // control ever leaves the safe area.
            val top = (columnTop + i * (size + gap)).coerceIn(band.top, controlTopMax)
            OverlayRect(left, top, left + size, top + size)
        }
    }

    /** Nearest horizontal side for a free-floating bubble centre. */
    fun snapSide(centerX: Int, g: ScreenGeometry): DockSide {
        val mid = (g.insetLeftPx + (g.widthPx - g.insetRightPx)) / 2
        return if (centerX < mid) DockSide.LEFT else DockSide.RIGHT
    }

    /** Full-width status strip rectangle directly below the status bar / cutout. */
    fun statusRect(g: ScreenGeometry, m: OverlayMetrics): OverlayRect {
        val top = g.insetTopPx + m.statusTopGapPx
        val left = g.insetLeftPx
        val right = (g.widthPx - g.insetRightPx).coerceAtLeast(left + 1)
        return OverlayRect(left, top, right, top + m.statusHeightPx)
    }

    /** True when a movement of (dx,dy) from the initial touch exceeds the drag slop. */
    fun exceedsSlop(dx: Float, dy: Float, slopPx: Int): Boolean =
        abs(dx) > slopPx || abs(dy) > slopPx
}
