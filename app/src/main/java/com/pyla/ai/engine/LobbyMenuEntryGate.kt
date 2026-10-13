package com.pyla.ai.engine

import kotlin.math.abs
import kotlin.math.ceil

/** Pre-opening control inspection only; never a brawler identity or selection fallback. */
object LobbyMenuEntryGate {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun aroundLabel(width: Int, height: Int, label: BrawlerOcrHit): Bounds? {
        val original = Bounds(label.left, label.top, label.right, label.bottom)
        if (!validBounds(width, height, original)) return null
        val padding = BrawlerSelectionGate.tolerancePx(height)
        return Bounds(
            (label.left - padding).coerceAtLeast(0), (label.top - padding).coerceAtLeast(0),
            (label.right.toLong() + padding).coerceAtMost(width.toLong()).toInt(),
            (label.bottom.toLong() + padding).coerceAtMost(height.toLong()).toInt(),
        )
    }

    /** Inspect the vicinity of the existing opening point, without moving or clamping the tap. */
    fun aroundPoint(width: Int, height: Int, x: Float, y: Float): Bounds? {
        if (width <= 0 || height <= 0 || !x.isFinite() || !y.isFinite() ||
            x < 0f || y < 0f || x > width - 1f || y > height - 1f) return null
        // Radius is an inspection window, not a claim of a new phone-specific button geometry.
        val radius = ceil(60.0 * height / 1080.0).toInt().coerceAtLeast(4)
        return Bounds(
            (x.toInt().toLong() - radius).coerceAtLeast(0).toInt(),
            (y.toInt().toLong() - radius).coerceAtLeast(0).toInt(),
            (x.toInt().toLong() + radius + 1).coerceAtMost(width.toLong()).toInt(),
            (y.toInt().toLong() + radius + 1).coerceAtMost(height.toLong()).toInt(),
        )
    }

    /** Same 5%-changed / mean-RGB-delta <=9 policy as the existing sample gate, over ALL control pixels. */
    fun pixelsStill(width: Int, height: Int, first: IntArray, second: IntArray, bounds: Bounds): Boolean {
        if (!validBounds(width, height, bounds) ||
            first.size.toLong() != width.toLong() * height || second.size != first.size) return false
        var changed = 0L
        var totalDifference = 0L
        val area = (bounds.right - bounds.left).toLong() * (bounds.bottom - bounds.top)
        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
            val a = first[y * width + x]; val b = second[y * width + x]
            val delta = abs(((a ushr 16) and 255) - ((b ushr 16) and 255)) +
                abs(((a ushr 8) and 255) - ((b ushr 8) and 255)) + abs((a and 255) - (b and 255))
            totalDifference += delta
            if (delta > 30) changed++
            if (changed * 20L > area || totalDifference > area * 9L) return false
        }
        return true
    }

    private fun validBounds(width: Int, height: Int, bounds: Bounds): Boolean =
        width > 0 && height > 0 && bounds.left >= 0 && bounds.top >= 0 &&
            bounds.right <= width && bounds.bottom <= height &&
            bounds.right > bounds.left && bounds.bottom > bounds.top
}
