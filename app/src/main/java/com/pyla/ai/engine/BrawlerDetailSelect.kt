package com.pyla.ai.engine

import java.util.Locale

/**
 * Supplemental, pure pair evidence for the brawler DETAIL screen's SELECT button when the
 * recognizer returned no SELECT label at all.
 *
 * The recognized-label path stays primary: this helper is only ever asked for a button after both
 * DETAIL scans contain no normalized SELECT label inside the admissible band. It never overrides a
 * recognized-but-rejected label, never widens the band, and never authorizes an action on its own -
 * the caller keeps its mandatory title check and its immediate live gate.
 *
 * Identity is a GLYPH identity, not a colour blob. A yellow component is only a localization
 * proposal; to be accepted it must also reproduce the observed SELECT word: an inline 2D binary
 * reference mask sampled from the user's own SELECT pixels (6 letter slots, 8x12 samples each,
 * 576 bits) plus bounded geometry. A 1-D column profile alone is not sufficient - different words
 * share column sums - so the 2D mask is the binding discriminator.
 *
 * The reference mask and every geometric bound were measured from the delivered detail frames
 * (1280x591 captures, SELECT at (76,506)-(322,566), 6 glyphs, cap height 18 px) and hold under
 * rescaling from 0.80x to 1.35x, because every bound is a fraction of the capture or of the
 * candidate itself, never an absolute pixel count.
 *
 * Deliberately narrow and pure: no I/O, no clock, no logging, no mutable shared state, no Android
 * or OpenCV dependency, and every rejection is a plain `null`.
 */
object BrawlerDetailSelect {

    /** Vertical band, as fractions of capture height, that may contain the SELECT button. */
    const val BAND_TOP: Double = 0.70
    const val BAND_BOTTOM: Double = 1.0

    /** Minimum fraction of agreeing 2D mask samples for a glyph identity. */
    const val MIN_SIMILARITY: Double = 0.80

    /** Reference glyph slots, samples per slot and total mask size: 6 * 8 * 12 = 576 bits. */
    const val SLOTS = 6
    const val SLOT_WIDTH = 8
    const val SLOT_HEIGHT = 12
    const val MASK_BITS = SLOTS * SLOT_WIDTH * SLOT_HEIGHT

    /** The observed SELECT word as a 2D binary mask. */
    private const val REFERENCE_HEX =
        "1e7e7ef0f87e7f0fcffefe10fffffffef0fefefcf0fffffff0f0f0f0f0f0f0f0f0ffffff7e7f7f7c707c7e7cf0ffffff1e7e7ef0e0e0e0e0f07f7f087effff7e1c1c1c1c1c1c1c1c"

    // Geometry bounds. Every measured positive stays inside; every measured near-miss stays outside.
    private const val MIN_ASPECT = 3.6
    private const val MAX_ASPECT = 4.6
    private const val MIN_FILL = 0.78
    private const val MIN_WIDTH_FRACTION = 0.14
    private const val MAX_WIDTH_FRACTION = 0.28
    private const val MIN_HEIGHT_FRACTION = 0.06
    private const val MAX_HEIGHT_FRACTION = 0.16
    private const val MAX_RIGHT_FRACTION = 0.65
    private const val MIN_GLYPHS = 6
    private const val MAX_GLYPHS = 6
    private const val MIN_ROW_SPAN = 3.2
    private const val MAX_ROW_SPAN = 5.0
    private const val MIN_TEXT_WIDTH_FRACTION = 0.20
    private const val MAX_TEXT_WIDTH_FRACTION = 0.45
    private const val MIN_CAP_HEIGHT_FRACTION = 0.20
    private const val MAX_CAP_HEIGHT_FRACTION = 0.40

    // Colour predicates, read in the capture buffer's own channel order (red low, blue high).
    private const val MIN_RED = 170
    private const val MIN_GREEN = 140
    private const val MAX_BLUE = 110
    private const val MIN_RED_BLUE_SPREAD = 90
    private const val MIN_GREEN_BLUE_SPREAD = 60
    private const val MIN_LIGHT_LEVEL = 165

    /** The synthetic label a located box is presented as; it normalizes to itself. */
    private const val SELECT_LABEL = "select"

    /** One accepted yellow component with its measured geometry and its glyph similarity. */
    data class Match(val hit: BrawlerOcrHit, val similarity: Double)

    /**
     * Returns the single SELECT button in this capture, or `null` when the buffer is unusable, the
     * button is absent, its glyphs are not the observed word, its geometry is outside the supported
     * layout, or more than one candidate qualifies.
     *
     * [argb] is the capture buffer exactly as [FrameSnapshot] holds it: ABGR packed, so red is the
     * LOW byte and blue the high channel byte ([PylaUtils.frameToBitmap] swaps red and blue
     * precisely because of this). Reading the channels the other way round cannot reproduce the
     * reference word, so a wrongly ordered buffer is rejected rather than accepted.
     */
    fun locate(width: Int, height: Int, argb: IntArray): Match? {
        if (width <= 0 || height <= 0) return null
        val pixels = width.toLong() * height.toLong()
        if (pixels > Int.MAX_VALUE || argb.size.toLong() != pixels) return null

        val bandTop = ceilFraction(BAND_TOP, height)
        val bandBottom = minOf(height, ceilFraction(BAND_BOTTOM, height))
        if (bandBottom <= bandTop) return null

        // The yellow mask exists ONLY inside the band: the band is never widened for a candidate.
        val yellow = BooleanArray(width * height)
        for (y in bandTop until bandBottom) {
            val row = y * width
            for (x in 0 until width) if (isYellow(argb[row + x])) yellow[row + x] = true
        }

        var accepted: Match? = null
        for (component in components(yellow, width, bandTop, bandBottom)) {
            val candidate = accept(component, width, height, argb) ?: continue
            // Two qualifying candidates are ambiguous: never pick the more confident of them.
            if (accepted != null) return null
            accepted = candidate
        }
        return accepted
    }

    /**
     * Confirms the SELECT button across two DISTINCT fresh frames and returns the box from [second],
     * or `null`.
     *
     * The freshness, capture-dimension and box-agreement rules are deliberately not re-implemented:
     * both located boxes are handed to [BrawlerSelectionGate.confirmPair], which rejects equal or
     * regressed timestamps, captures from the future or older than its own budget, mismatched
     * dimensions, and any pair whose boxes disagree by more than its own tolerance. A moved,
     * duplicated or stale button therefore fails closed without this helper knowing anything about
     * clocks, geometry slack or scroll offsets.
     */
    fun confirmPair(first: FrameSnapshot, second: FrameSnapshot, nowMs: Long): BrawlerOcrHit? {
        if (first.width != second.width || first.height != second.height) return null
        val firstMatch = locate(first.width, first.height, first.argb) ?: return null
        val secondMatch = locate(second.width, second.height, second.argb) ?: return null
        return BrawlerSelectionGate.confirmPair(
            scanOf(first, firstMatch.hit), scanOf(second, secondMatch.hit),
            setOf(SELECT_LABEL), BAND_TOP, BAND_BOTTOM, nowMs,
        )
    }

    /** Convenience overload for callers that already own a frame pair. */
    fun confirmPair(first: BrawlerOcrScan, second: BrawlerOcrScan, nowMs: Long): BrawlerOcrHit? =
        BrawlerSelectionGate.confirmPair(first, second, setOf(SELECT_LABEL), BAND_TOP, BAND_BOTTOM, nowMs)

    private fun scanOf(frame: FrameSnapshot, hit: BrawlerOcrHit): BrawlerOcrScan =
        BrawlerOcrScan(frame.width, frame.height, frame.capturedAtMs, listOf(hit))

    private fun isYellow(pixel: Int): Boolean {
        val r = pixel and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = (pixel ushr 16) and 0xFF
        return r > MIN_RED && g > MIN_GREEN && b < MAX_BLUE &&
            r - b > MIN_RED_BLUE_SPREAD && g - b > MIN_GREEN_BLUE_SPREAD
    }

    private fun isLight(pixel: Int): Boolean {
        val r = pixel and 0xFF
        val g = (pixel ushr 8) and 0xFF
        val b = (pixel ushr 16) and 0xFF
        val low = if (r < g) r else g
        return (if (low < b) low else b) >= MIN_LIGHT_LEVEL
    }

    /** An 8-connected component, already flattened so one blob is exactly one component. */
    private class Component(val left: Int, val top: Int, val right: Int, val bottom: Int, val area: Int)

    private fun accept(component: Component, width: Int, height: Int, argb: IntArray): Match? {
        val boxWidth = component.right - component.left
        val boxHeight = component.bottom - component.top
        if (boxWidth <= 0 || boxHeight <= 0) return null

        val widthFraction = boxWidth.toDouble() / width
        if (widthFraction < MIN_WIDTH_FRACTION || widthFraction > MAX_WIDTH_FRACTION) return null
        val heightFraction = boxHeight.toDouble() / height
        if (heightFraction < MIN_HEIGHT_FRACTION || heightFraction > MAX_HEIGHT_FRACTION) return null
        val aspect = boxWidth.toDouble() / boxHeight
        if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) return null
        if (component.area.toDouble() / (boxWidth.toDouble() * boxHeight) < MIN_FILL) return null
        // The DETAIL button sits on the left side; a right-hand control is never this button.
        if (component.right.toDouble() / width > MAX_RIGHT_FRACTION) return null

        val row = dominantGlyphRow(argb, width, component) ?: return null
        if (row.size < MIN_GLYPHS || row.size > MAX_GLYPHS) return null
        val capHeight = row.maxOf { it.bottom - it.top }
        val textLeft = row.minOf { it.left }
        val textRight = row.maxOf { it.right }
        val textWidth = textRight - textLeft
        val rowSpan = textWidth.toDouble() / capHeight
        if (rowSpan < MIN_ROW_SPAN || rowSpan > MAX_ROW_SPAN) return null
        val textWidthFraction = textWidth.toDouble() / boxWidth
        if (textWidthFraction < MIN_TEXT_WIDTH_FRACTION || textWidthFraction > MAX_TEXT_WIDTH_FRACTION) return null
        val capHeightFraction = capHeight.toDouble() / boxHeight
        if (capHeightFraction < MIN_CAP_HEIGHT_FRACTION || capHeightFraction > MAX_CAP_HEIGHT_FRACTION) return null

        val mask = maskOf(argb, width, row)
        val similarity = similarity(mask)
        if (similarity < MIN_SIMILARITY || !everyGlyphMatches(mask)) return null

        return Match(
            BrawlerOcrHit(SELECT_LABEL, component.left, component.top, component.right, component.bottom),
            similarity,
        )
    }

    private class Glyph(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * The glyph cluster of the button's text line: light blobs inside the yellow box, grouped into
     * horizontal bands by vertical span, keeping the band holding the most blobs.
     *
     * Each band tests its ORIGINAL span, never a merged extent, and blobs are visited in ascending
     * top order, so the result cannot depend on the order blobs happen to be enumerated. Ties keep
     * the topmost band.
     */
    private fun dominantGlyphRow(argb: IntArray, width: Int, component: Component): List<Glyph>? {
        val boxWidth = component.right - component.left
        val boxHeight = component.bottom - component.top
        val light = BooleanArray(boxWidth * boxHeight)
        for (y in 0 until boxHeight) {
            for (x in 0 until boxWidth) {
                light[y * boxWidth + x] = isLight(argb[(component.top + y) * width + component.left + x])
            }
        }
        val glyphs = components(light, boxWidth, 0, boxHeight)
            .filter { it.right - it.left >= 2 && it.bottom - it.top >= 2 }
            .map { Glyph(it.left + component.left, it.top + component.top, it.right + component.left, it.bottom + component.top) }
            .sortedBy { it.top }
        if (glyphs.isEmpty()) return null

        val band = ArrayList<Glyph>()
        var best = emptyList<Glyph>()
        val assigned = BooleanArray(glyphs.size)
        for (seed in glyphs.indices) {
            if (assigned[seed]) continue
            // ORIGINAL span of this band, never the merged extent: order-independent grouping.
            val bandTop = glyphs[seed].top
            val bandBottom = glyphs[seed].bottom
            band.clear()
            band.add(glyphs[seed])
            assigned[seed] = true
            for (i in glyphs.indices) {
                if (assigned[i]) continue
                val glyph = glyphs[i]
                if (glyph.top < bandBottom && glyph.bottom > bandTop) {
                    assigned[i] = true
                    band.add(glyph)
                }
            }
            // Ties keep the topmost band, because glyphs are visited in ascending top order.
            if (band.size > best.size) best = band.toList()
        }
        if (best.isEmpty()) return null
        return best.sortedBy { it.left }
    }

    /** Normalize each observed connected glyph separately; fixed-width slots cut into neighbours. */
    private fun maskOf(argb: IntArray, width: Int, row: List<Glyph>): BooleanArray {
        val mask = BooleanArray(MASK_BITS)
        var index = 0
        for (glyph in row) {
            for (r in 0 until SLOT_HEIGHT) for (c in 0 until SLOT_WIDTH) {
                val y = glyph.top + ((2 * r + 1) * (glyph.bottom - glyph.top)) / (2 * SLOT_HEIGHT)
                val x = glyph.left + ((2 * c + 1) * (glyph.right - glyph.left)) / (2 * SLOT_WIDTH)
                mask[index++] = isLight(argb[y * width + x])
            }
        }
        return mask
    }

    /** A changed letter cannot borrow agreement from the other five letter slots. */
    private fun everyGlyphMatches(mask: BooleanArray): Boolean {
        val reference = referenceMask()
        val size = SLOT_WIDTH * SLOT_HEIGHT
        val letters = "SELECT"
        for (slot in 0 until SLOTS) {
            var expected = 0
            var other = 0
            for (candidate in 0 until SLOTS) {
                var agree = 0
                for (i in 0 until size) if (mask[slot * size + i] == reference[candidate * size + i]) agree++
                if (letters[slot] == letters[candidate]) expected = maxOf(expected, agree)
                else other = maxOf(other, agree)
            }
            // The expected letter must win, not merely share most pixels with another letter.
            if (expected.toDouble() / size < 0.75 || expected - other < 3) return false
        }
        return true
    }

    /** Fraction of the 576 mask samples that agree with the observed SELECT word. */
    fun similarity(mask: BooleanArray): Double {
        if (mask.size != MASK_BITS) return 0.0
        var agree = 0
        var index = 0
        for (digit in 0 until REFERENCE_HEX.length step 2) {
            val value = hexByte(digit) ?: return 0.0
            for (bit in 7 downTo 0) {
                if (mask[index] == ((value ushr bit) and 1 == 1)) agree++
                index++
            }
        }
        return agree.toDouble() / MASK_BITS
    }

    /** The observed SELECT word as [SLOTS] slots of [SLOT_WIDTH] x [SLOT_HEIGHT] samples. */
    fun referenceMask(): BooleanArray {
        val mask = BooleanArray(MASK_BITS)
        var index = 0
        for (digit in 0 until REFERENCE_HEX.length step 2) {
            val value = hexByte(digit) ?: return BooleanArray(MASK_BITS)
            for (bit in 7 downTo 0) mask[index++] = (value ushr bit) and 1 == 1
        }
        return mask
    }

    private fun hexByte(digit: Int): Int? {
        if (digit + 1 >= REFERENCE_HEX.length) return null
        val high = Character.digit(REFERENCE_HEX[digit], 16)
        val low = Character.digit(REFERENCE_HEX[digit + 1], 16)
        if (high < 0 || low < 0) return null
        return (high shl 4) or low
    }

    /** Linear 8-connected flood fill. Primitive storage avoids per-pixel boxing on Android. */
    private fun components(mask: BooleanArray, width: Int, top: Int, bottom: Int): List<Component> {
        val pending = mask.copyOf()
        val queue = IntArray(width * (bottom - top))
        val out = ArrayList<Component>()
        for (start in top * width until bottom * width) {
            if (!pending[start]) continue
            var head = 0
            var tail = 1
            queue[0] = start
            pending[start] = false
            var left = start % width
            var right = left
            var yTop = start / width
            var yBottom = yTop
            while (head < tail) {
                val at = queue[head++]
                val x = at % width
                val y = at / width
                if (x < left) left = x
                if (x > right) right = x
                if (y < yTop) yTop = y
                if (y > yBottom) yBottom = y
                for (yy in maxOf(top, y - 1)..minOf(bottom - 1, y + 1)) {
                    val row = yy * width
                    for (xx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                        val next = row + xx
                        if (pending[next]) {
                            pending[next] = false
                            queue[tail++] = next
                        }
                    }
                }
            }
            out.add(Component(left, yTop, right + 1, yBottom + 1, tail))
        }
        return out.sortedByDescending { it.area }
    }

    /** `ceil(fraction * size)` in exact integer arithmetic, saturating instead of overflowing. */
    private fun ceilFraction(fraction: Double, size: Int): Int {
        val scaled = fraction * size
        if (scaled.isNaN() || scaled <= 0.0) return 0
        if (scaled >= Int.MAX_VALUE.toDouble()) return Int.MAX_VALUE
        val truncated = scaled.toInt()
        return if (scaled > truncated.toDouble()) truncated + 1 else truncated
    }

    private fun clamp(value: Int, low: Int, high: Int): Int = if (value < low) low else if (value > high) high else value

    /** Locale-free lowercase, retained so the synthetic label matches [BrawlerSelectionGate.normalize]. */
    internal fun label(): String = SELECT_LABEL.lowercase(Locale.ROOT)
}