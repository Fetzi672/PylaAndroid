package com.pyla.ai.engine

/**
 * An axis-aligned integer rectangle in a source capture or in a generated mosaic.
 *
 * Coordinates are half-open on the right/bottom edge, exactly like the bitmap regions the caller
 * hands to ML Kit, so `width`/`height` are the pixel extents that are actually copied.
 * A rectangle with `width <= 0` or `height <= 0` is degenerate and is never accepted as an input.
 */
data class GridOcrRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * One OCR tile of a [GridOcrPlan]: the region cut out of the captured frame ([source]) and the
 * placement of the rescaled region inside the generated mosaic ([destination]).
 *
 * The pairing is bidirectional: [BrawlerGridOcrRegions.map] turns a mosaic box back into the
 * capture rectangle it came from using only this pair and exact integer ratios.
 */
data class GridOcrTile(val source: GridOcrRect, val destination: GridOcrRect)

/**
 * A complete, self-describing mosaic description for one capture frame.
 *
 * [width]/[height] are the mosaic extents: the widest tile and the bottom of the last tile. The
 * plan carries no pixels and no timestamps, so it is safe to build on a worker thread and to
 * compare in tests.
 */
data class GridOcrPlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val width: Int,
    val height: Int,
    val tiles: List<GridOcrTile>,
)

/**
 * Pure integer geometry for the user-approved targeted brawler-list OCR.
 *
 * The three-row wide brawler list is recognised as one heading tile plus three card-title strips.
 * Instead of rescaling the whole frame, only those regions are cut out, upscaled and stacked into
 * one narrow mosaic, so the recogniser sees the small title glyphs at a usable pixel height.
 *
 * Deliberately geometry-only:
 * - no Android types, no `Bitmap`, no ML Kit, no I/O, no logging, no clock, no persisted state;
 * - no brawler name knowledge, no freshness/retiming decision and no admission authority. A `null`
 *   return means "this frame is not supported by the plan", and the caller must then keep using its
 *   previous bounded strict full-resolution path. Nothing here ever accepts a frame or a brawler.
 *
 * All arithmetic is exact integer arithmetic on `Long` intermediates, so no ratio, rounding step or
 * guard can depend on floating point behaviour.
 */
object BrawlerGridOcrRegions {

    /** Smallest supported capture width in pixels (inclusive). */
    const val MIN_SOURCE_WIDTH: Int = 640

    /** Largest supported capture width in pixels (inclusive). */
    const val MAX_SOURCE_WIDTH: Int = 4096

    /** Smallest supported capture height in pixels (inclusive). */
    const val MIN_SOURCE_HEIGHT: Int = 180

    /** Largest supported capture height in pixels (inclusive). */
    const val MAX_SOURCE_HEIGHT: Int = 2048

    /**
     * Black separator inserted between two consecutive tiles of the mosaic, in mosaic pixels.
     * OCR may still report a box bridging tiles; [map] rejects it instead of clipping it.
     * The separator itself is not recognition or list-admission authority.
     */
    const val GUTTER: Int = 24

    // Fractions are expressed in whole percent so that every endpoint is an exact integer
    // floor(fraction * dimension) and therefore reproducible from the frame size alone.
    // The heading tile must start at the left frame edge: on the 1280x591 brawler-list captures the
    // "BRAWLERS <count>" heading glyphs begin at x=72, so any inset here clips the word to "AWLERS".
    private const val HEADER_LEFT_PCT = 0
    private const val HEADER_RIGHT_PCT = 46
    private const val HEADER_TOP_PCT = 6
    private const val HEADER_BOTTOM_PCT = 16
    private const val HEADER_SCALE_NUM = 2
    private const val HEADER_SCALE_DEN = 1

    private const val STRIP_LEFT_PCT = 0
    private const val STRIP_RIGHT_PCT = 65
    private const val STRIP_SCALE_NUM = 3
    private const val STRIP_SCALE_DEN = 2

    private const val PERCENT = 100L

    /** Vertical band of each of the three card-title strips, as (topPercent, bottomPercent). */
    private val STRIP_BANDS = intArrayOf(
        30, 38,
        57, 65,
        84, 91,
    )

    /** Number of tiles a plan always has: one heading plus three title strips. */
    const val TILE_COUNT: Int = 4

    /**
     * Builds the mosaic plan for a capture of [width] x [height] pixels, or returns `null` when the
     * frame is outside the user-approved contract.
     *
     * Supported means: width in [MIN_SOURCE_WIDTH, MAX_SOURCE_WIDTH], height in
     * [MIN_SOURCE_HEIGHT, MAX_SOURCE_HEIGHT] and an aspect ratio between 2.0 and 2.35 inclusive.
     * Anything else - including a degenerate or negative frame - returns `null`, and the caller is
     * expected to fall back to its previous strict path rather than to accept the frame.
     */
    fun plan(width: Int, height: Int): GridOcrPlan? = buildPlan(width, height)

    /**
     * Optional paging-only mosaic: keep the original four tiles byte-geometrically identical,
     * then append the list's sorting control. This adds observation, never admission authority.
     */
    fun pagingPlan(width: Int, height: Int): GridOcrPlan? {
        val base = buildPlan(width, height) ?: return null
        val source = region(40, 0, 75, 7, width, height) ?: return null
        val destination = GridOcrRect(0, base.height + GUTTER,
            source.width * 2, base.height + GUTTER + source.height * 2)
        return base.copy(width = maxOf(base.width, destination.right), height = destination.bottom,
            tiles = base.tiles + GridOcrTile(source, destination))
    }

    /**
     * Maps a box from mosaic coordinates back to capture coordinates.
     *
     * [box] must be a positive-area rectangle that lies fully inside exactly one tile; boxes that
     * are empty, negative, outside the mosaic, inside a gutter or spanning two tiles return `null`
     * and are never clipped into a neighbouring tile. Each axis is mapped with the exact integer
     * ratio of that tile (source extent over destination extent): the leading edge floors and the
     * trailing edge ceils, so a mapped box always keeps every mosaic pixel it contained. The
     * result must additionally stay inside the tile's capture rectangle, otherwise `null` is
     * returned instead of a clipped answer.
     *
     * The plan is re-validated structurally before use, so a forged or mutated plan (wrong source
     * dimensions, mosaic size, tile count or tile geometry) can never be mapped through.
     */
    fun map(plan: GridOcrPlan, box: GridOcrRect): GridOcrRect? {
        if (!isCanonical(plan)) return null
        if (box.right <= box.left || box.bottom <= box.top) return null
        // Negative coordinates can never be contained in a tile - every destination starts at 0 -
        // but the guard is kept explicit so the contract reads as stated even if a tile's origin
        // ever changes.
        if (box.left < 0 || box.top < 0) return null
        if (box.right > plan.width || box.bottom > plan.height) return null

        var owner: GridOcrTile? = null
        for (tile in plan.tiles) {
            if (!contains(tile.destination, box)) continue
            // A canonical plan cannot produce two owners, so this only guards a plan that was
            // accepted by isCanonical(); it is kept so no ambiguity is ever silently resolved.
            if (owner != null) return null
            owner = tile
        }
        val tile = owner ?: return null

        val source = tile.source
        val destination = tile.destination
        val sourceWidth = (source.right - source.left).toLong()
        val sourceHeight = (source.bottom - source.top).toLong()
        val destinationWidth = (destination.right - destination.left).toLong()
        val destinationHeight = (destination.bottom - destination.top).toLong()
        if (sourceWidth <= 0L || sourceHeight <= 0L || destinationWidth <= 0L || destinationHeight <= 0L) {
            return null
        }

        val left = scaleFloor(box.left - destination.left, sourceWidth, destinationWidth) + source.left
        val right = scaleCeil(box.right - destination.left, sourceWidth, destinationWidth) + source.left
        val top = scaleFloor(box.top - destination.top, sourceHeight, destinationHeight) + source.top
        val bottom = scaleCeil(box.bottom - destination.top, sourceHeight, destinationHeight) + source.top

        val mapped = GridOcrRect(toIntExact(left), toIntExact(top), toIntExact(right), toIntExact(bottom))
        // Rounding must never push the answer outside the pixels that were actually captured.
        if (mapped.left < source.left || mapped.top < source.top) return null
        if (mapped.right > source.right || mapped.bottom > source.bottom) return null
        if (mapped.width <= 0 || mapped.height <= 0) return null
        return mapped
    }

    /**
     * Returns `true` only for a plan that [buildPlan] would produce for its own source dimensions.
     *
     * `GridOcrPlan` is a public data class, so any caller could hand over a hand-built instance.
     * Comparing against the regenerated canonical plan rejects every mutation - resized mosaic,
     * missing or extra tile, moved or clipped destination rectangle, wrong source band - in one
     * step and keeps [map] independent of the caller's construction path.
     */
    private fun isCanonical(plan: GridOcrPlan): Boolean {
        val canonical = when (plan.tiles.size) {
            TILE_COUNT -> buildPlan(plan.sourceWidth, plan.sourceHeight)
            TILE_COUNT + 1 -> pagingPlan(plan.sourceWidth, plan.sourceHeight)
            else -> null
        } ?: return false
        if (canonical.width != plan.width || canonical.height != plan.height) return false
        val tiles = plan.tiles
        if (tiles.size != canonical.tiles.size) return false
        for (index in canonical.tiles.indices) {
            val expected = canonical.tiles[index]
            val actual = tiles[index]
            if (actual.source != expected.source || actual.destination != expected.destination) return false
        }
        return true
    }

    /** Internal construction shared by [plan] and [isCanonical]. */
    private fun buildPlan(width: Int, height: Int): GridOcrPlan? {
        if (!isSupported(width, height)) return null

        val headerSource = region(
            leftPercent = HEADER_LEFT_PCT,
            topPercent = HEADER_TOP_PCT,
            rightPercent = HEADER_RIGHT_PCT,
            bottomPercent = HEADER_BOTTOM_PCT,
            width = width,
            height = height,
        ) ?: return null

        val sources = ArrayList<GridOcrRect>(TILE_COUNT)
        sources.add(headerSource)
        var index = 0
        while (index < STRIP_BANDS.size) {
            val stripSource = region(
                leftPercent = STRIP_LEFT_PCT,
                topPercent = STRIP_BANDS[index],
                rightPercent = STRIP_RIGHT_PCT,
                bottomPercent = STRIP_BANDS[index + 1],
                width = width,
                height = height,
            ) ?: return null
            sources.add(stripSource)
            index += 2
        }

        val tiles = ArrayList<GridOcrTile>(TILE_COUNT)
        var cursorY = 0L
        var mosaicWidth = 0L
        var stripIndex = 0
        for (source in sources) {
            val scaleNum = if (stripIndex == 0) HEADER_SCALE_NUM.toLong() else STRIP_SCALE_NUM.toLong()
            val scaleDen = if (stripIndex == 0) HEADER_SCALE_DEN.toLong() else STRIP_SCALE_DEN.toLong()
            val sourceWidth = (source.right - source.left).toLong()
            val sourceHeight = (source.bottom - source.top).toLong()
            if (sourceWidth <= 0L || sourceHeight <= 0L) return null
            val tileWidth = sourceWidth * scaleNum / scaleDen
            val tileHeight = sourceHeight * scaleNum / scaleDen
            if (tileWidth <= 0L || tileHeight <= 0L) return null

            if (stripIndex > 0) cursorY += GUTTER.toLong()
            val destinationLeft = 0
            val destinationTop = toIntExact(cursorY)
            val destination = GridOcrRect(
                left = destinationLeft,
                top = destinationTop,
                right = destinationLeft + toIntExact(tileWidth),
                bottom = destinationTop + toIntExact(tileHeight),
            )
            tiles.add(GridOcrTile(source = source, destination = destination))
            cursorY = destination.bottom.toLong()
            if (destination.right > mosaicWidth) mosaicWidth = destination.right.toLong()
            stripIndex++
        }

        val widthOut = toIntExact(mosaicWidth)
        val heightOut = toIntExact(cursorY)
        if (widthOut <= 0 || heightOut <= 0) return null
        return GridOcrPlan(
            sourceWidth = width,
            sourceHeight = height,
            width = widthOut,
            height = heightOut,
            tiles = tiles,
        )
    }

    /** Aspect-ratio and size gate of the user-approved contract. */
    private fun isSupported(width: Int, height: Int): Boolean {
        if (width < MIN_SOURCE_WIDTH || width > MAX_SOURCE_WIDTH) return false
        if (height < MIN_SOURCE_HEIGHT || height > MAX_SOURCE_HEIGHT) return false
        // ratio = width / height must lie in [2.0, 2.35]; expressed without floating point.
        if (width.toLong() * PERCENT < height.toLong() * 2L * PERCENT) return false
        if (width.toLong() * PERCENT > height.toLong() * 235L) return false
        return true
    }

    /** Builds one source rectangle with exact floor(fraction * dimension) endpoints. */
    private fun region(
        leftPercent: Int,
        topPercent: Int,
        rightPercent: Int,
        bottomPercent: Int,
        width: Int,
        height: Int,
    ): GridOcrRect? {
        val left = width.toLong() * leftPercent / PERCENT
        val top = height.toLong() * topPercent / PERCENT
        val right = width.toLong() * rightPercent / PERCENT
        val bottom = height.toLong() * bottomPercent / PERCENT
        if (right <= left || bottom <= top) return null
        if (left < 0L || top < 0L) return null
        if (right > width.toLong() || bottom > height.toLong()) return null
        return GridOcrRect(toIntExact(left), toIntExact(top), toIntExact(right), toIntExact(bottom))
    }

    /** True when [inner] lies fully inside [outer] (a gutter or tile edge is therefore outside). */
    private fun contains(outer: GridOcrRect, inner: GridOcrRect): Boolean =
        inner.left >= outer.left &&
            inner.top >= outer.top &&
            inner.right <= outer.right &&
            inner.bottom <= outer.bottom

    /** floor(value * numerator / denominator) for non-negative inputs, on Long intermediates. */
    private fun scaleFloor(value: Int, numerator: Long, denominator: Long): Long =
        value.toLong() * numerator / denominator

    /** ceil(value * numerator / denominator) for non-negative inputs, on Long intermediates. */
    private fun scaleCeil(value: Int, numerator: Long, denominator: Long): Long =
        (value.toLong() * numerator + denominator - 1L) / denominator

    /** Narrows a `Long` back to `Int`, refusing any value that would not survive the round trip. */
    private fun toIntExact(value: Long): Int {
        if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
            throw ArithmeticException("geometry value out of Int range: $value")
        }
        return value.toInt()
    }
}