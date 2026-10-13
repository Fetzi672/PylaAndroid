package com.pyla.ai.engine

import java.text.Normalizer
import java.util.Locale

/**
 * One OCR text box from a single captured brawler-screen frame, in raw capture pixels.
 *
 * @param text   raw OCR text exactly as the recognizer returned it (unmodified).
 * @param left   box left edge, capture pixels, inclusive.
 * @param top    box top edge, capture pixels, inclusive.
 * @param right  box right edge, capture pixels, exclusive.
 * @param bottom box bottom edge, capture pixels, exclusive.
 */
data class BrawlerOcrHit(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    /** Horizontal center of the box, rounded down. */
    val centerX: Int get() = left + (right - left) / 2

    /** Vertical center of the box, rounded down. */
    val centerY: Int get() = top + (bottom - top) / 2

    /** Box width in capture pixels; zero or negative when the box is degenerate. */
    val width: Int get() = right - left

    /** Box height in capture pixels; zero or negative when the box is degenerate. */
    val height: Int get() = bottom - top
}

/**
 * One captured frame plus every OCR box produced from that exact frame.
 *
 * [capturedAtMs] is the capture timestamp on the same monotonic clock the caller uses for
 * [BrawlerSelectionGate.confirmPair] `nowMs`; it must not be a wall-clock value.
 */
data class BrawlerOcrScan(
    val width: Int,
    val height: Int,
    val capturedAtMs: Long,
    val hits: List<BrawlerOcrHit>,
    /** GRID extractor observed ambiguous heading labels whose boxes could not all be retained. */
    val gridHeaderAmbiguous: Boolean = false
)

/**
 * Pure, stateless gate deciding whether two fresh frames legitimately show the same brawler card.
 *
 * It exists so a selection can never be committed from a single frame, a scrolled grid, a
 * duplicated OCR line, a stale capture, or a superstring/substring of some other brawler's name.
 * The gate performs no I/O, holds no mutable state, and owns no screen classifier: the caller
 * supplies already-settled frames and their real OCR boxes, and integrates the verdict into its
 * own scrolling, tapping and detail-verification flow.
 *
 * Every rejection is a plain `null`. There is deliberately no partial match, no nearest-name
 * fallback and no fuzzy edit-distance matching: "close enough" is exactly how a wrong brawler
 * would be selected.
 */
object BrawlerSelectionGate {

    /** Minimum geometry slack in pixels before a card is considered to have moved. */
    private const val MIN_TOLERANCE_PX = 4

    /** Geometry slack as a fraction of the capture height. */
    private const val TOLERANCE_HEIGHT_FRACTION = 0.015

    /** Characters that separate words in OCR output and are never part of a brawler name. */
    private val SEPARATORS = charArrayOf(
        ' ', '\t', '\n', '\r', '\u000B', '\u000C',
        '-', '_', '.', ',', ':', ';', '!', '?', '\'', '"', '(', ')',
        '[', ']', '{', '}', '/', '\\', '|', '+', '*', '=', '&', '#', '@', '~', '`', '^'
    )

    /**
     * Normalizes one raw OCR string for exact comparison.
     *
     * Case-folds with [Locale.ROOT], decomposes with NFKD and strips combining marks so
     * "Purió" and "purio" compare equal, then removes separators to retain the existing cfg/names.json compact-name contract
     * (e.g. "8-Bit" -> "8bit"). Whitespace-only or empty input normalizes to the empty string.
     */
    fun normalize(raw: String): String {
        if (raw.isEmpty()) return ""
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFKD).lowercase(Locale.ROOT)
        val builder = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt() ||
                Character.getType(ch) == Character.ENCLOSING_MARK.toInt() ||
                Character.getType(ch) == Character.FORMAT.toInt() ||
                SEPARATORS.contains(ch) || ch.isWhitespace()) continue
            builder.append(ch)
        }
        return builder.toString()
    }

    /**
     * Returns the single unambiguous, fully in-frame, non-degenerate box in [scan] whose normalized
     * text is exactly one of [names], or `null`.
     *
     * [names] must already be normalized with [normalize]. More than one candidate in the same
     * frame - including two OCR lines describing one card - is ambiguous and returns `null`
     * rather than picking one. [topFraction] and [bottomFraction] bound the accepted vertical
     * region of the frame as fractions of its height, matching the caller's grid or detail band.
     */
    fun uniqueMatch(
        scan: BrawlerOcrScan,
        names: Set<String>,
        topFraction: Double,
        bottomFraction: Double
    ): BrawlerOcrHit? {
        if (!validRegion(scan, topFraction, bottomFraction)) return null
        if (names.isEmpty()) return null

        val bandTop = topFraction * scan.height
        val bandBottom = bottomFraction * scan.height
        var match: BrawlerOcrHit? = null
        for (hit in scan.hits) {
            if (!validBox(scan, hit, bandTop, bandBottom)) continue
            if (normalize(hit.text) !in names) continue
            if (match != null) return null // duplicate / ambiguous OCR evidence for one card
            match = hit
        }
        return match
    }

    /**
     * Confirms that two DISTINCT fresh frames show the same card in the same place, and returns the
     * confirmed box from [second].
     *
     * Returns `null` unless all of the following hold:
     *  - [second] was captured strictly after [first], and neither timestamp is in the future
     *    relative to [nowMs] (identical or reversed timestamps are a reused/reset capture, not
     *    two fresh frames);
     *  - both frames are no older than [maxAgeMs] and no more than [maxGapMs] apart;
     *  - both frames have identical capture dimensions (an orientation or resolution reset
     *    invalidates the comparison);
     *  - each frame yields exactly one unambiguous, non-degenerate, fully in-frame match against
     *    [names] inside the requested vertical region, and both matches are the same normalized
     *    name;
     *  - the two boxes agree within `max(4, height * 0.015)` pixels on center and on all four
     *    edges, so the card did not move between frames.
     */
    fun confirmPair(
        first: BrawlerOcrScan,
        second: BrawlerOcrScan,
        names: Set<String>,
        topFraction: Double,
        bottomFraction: Double,
        nowMs: Long,
        maxAgeMs: Long = 3_000L,
        maxGapMs: Long = 4_000L
    ): BrawlerOcrHit? {
        if (maxAgeMs < 0L || maxGapMs < 0L) return null
        if (nowMs < 0L) return null

        // Strictly increasing, and no timestamp from the future.
        if (first.capturedAtMs <= 0L || second.capturedAtMs <= 0L) return null
        if (second.capturedAtMs <= first.capturedAtMs) return null
        if (first.capturedAtMs > nowMs || second.capturedAtMs > nowMs) return null

        // Both frames fresh, and captured close together.
        val firstAge = nowMs - first.capturedAtMs
        val secondAge = nowMs - second.capturedAtMs
        if (firstAge > maxAgeMs || secondAge > maxAgeMs) return null
        val gap = second.capturedAtMs - first.capturedAtMs
        if (gap > maxGapMs) return null

        // Same capture geometry: a dimension change between frames invalidates pixel comparison.
        if (first.width != second.width || first.height != second.height) return null
        if (first.width <= 0 || first.height <= 0) return null

        val firstHit = uniqueMatch(first, names, topFraction, bottomFraction) ?: return null
        val secondHit = uniqueMatch(second, names, topFraction, bottomFraction) ?: return null

        // Exactly the same name on the same card, not two different brawlers.
        if (normalize(firstHit.text) != normalize(secondHit.text)) return null

        val tolerance = tolerancePx(second.height)
        if (abs(firstHit.centerX - secondHit.centerX) > tolerance) return null
        if (abs(firstHit.centerY - secondHit.centerY) > tolerance) return null
        if (abs(firstHit.left - secondHit.left) > tolerance) return null
        if (abs(firstHit.top - secondHit.top) > tolerance) return null
        if (abs(firstHit.right - secondHit.right) > tolerance) return null
        if (abs(firstHit.bottom - secondHit.bottom) > tolerance) return null

        return secondHit
    }

    /** Geometry slack in pixels for a frame of [height] px: `max(4, height * 0.015)`. */
    fun tolerancePx(height: Int): Int {
        val scaled = (height.toDouble() * TOLERANCE_HEIGHT_FRACTION).toInt()
        return if (scaled > MIN_TOLERANCE_PX) scaled else MIN_TOLERANCE_PX
    }

    /** True when [topFraction]..[bottomFraction] is a usable, non-empty band inside the frame. */
    private fun validRegion(scan: BrawlerOcrScan, topFraction: Double, bottomFraction: Double): Boolean {
        if (!topFraction.isFinite() || !bottomFraction.isFinite()) return false
        if (topFraction < 0.0 || bottomFraction > 1.0) return false
        if (bottomFraction - topFraction <= 0.0) return false
        // The band must actually cover part of this frame.
        return bottomFraction * scan.height > topFraction * scan.height
    }

    /** True when [hit] is non-degenerate, fully inside the frame, and fully inside the band. */
    private fun validBox(
        scan: BrawlerOcrScan,
        hit: BrawlerOcrHit,
        bandTop: Double,
        bandBottom: Double
    ): Boolean {
        if (scan.width <= 0 || scan.height <= 0) return false
        if (hit.left < 0 || hit.top < 0) return false
        if (hit.right > scan.width || hit.bottom > scan.height) return false
        if (hit.width <= 0 || hit.height <= 0) return false
        // The whole box must sit inside the requested vertical band, not merely its center.
        return hit.top >= bandTop && hit.bottom <= bandBottom
    }

    private fun abs(value: Int): Int = if (value < 0) -value else value
}