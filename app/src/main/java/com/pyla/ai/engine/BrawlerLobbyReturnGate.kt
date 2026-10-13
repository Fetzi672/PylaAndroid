package com.pyla.ai.engine

/**
 * Pure geometry, label and pixel-stability evidence for the post-SELECT handoff out of the brawler
 * DETAIL screen and back into the LOBBY.
 *
 * ## Why this replaces the old rule
 *
 * The old handoff required the requested brawler's own name to be readable inside the lobby. That
 * name is not rendered on the returned lobby: the native replay of the exact installed baseline
 * (`native-baseline/RESULT.json`) reads one exact `PLAY` and one exact `BRAWLERS` control label and
 * ZERO target-name boxes in the admissible detail band of the actual returned lobby capture. The
 * old rule therefore could never be satisfied by the very frame it was meant to accept, and its
 * name lookup is being deliberately replaced - not merely relaxed.
 *
 * What replaces it is a strictly causal binding: the handoff is decided by the same SELECT request
 * that produced the identity, in the same call stack, and only by lobby controls that were actually
 * observed as stable. This gate supplies the second half of that binding (unique controls, stable
 * bounds, bounded pixel windows); the caller supplies the first half (a committed, live-gated
 * DETAIL SELECT on a uniquely confirmed target) and never re-taps anything.
 *
 * ## Deliberately pure
 *
 * No Android types, no `Bitmap`, no ML Kit, no I/O, no clock, no logging, no persisted state and
 * no action authority: every rejection is a plain `null`/`false`, exactly like
 * [BrawlerSelectionGate] and [BrawlerGridContinuation]. The caller keeps its own state, freshness,
 * deadline, cancellation and live gate, and this helper never sees them.
 */
object BrawlerLobbyReturnGate {

    /** Freshness budget for both retained captures, in milliseconds. */
    const val MAX_AGE_MS: Long = 3_000L

    /** Maximum separation between the two retained captures, in milliseconds. */
    const val MAX_GAP_MS: Long = 4_000L

    /** Smallest supported capture width in pixels (inclusive). */
    const val MIN_SOURCE_WIDTH: Int = 640

    /** Largest supported capture width in pixels (inclusive). */
    const val MAX_SOURCE_WIDTH: Int = 4096

    /** Smallest supported capture height in pixels (inclusive). */
    const val MIN_SOURCE_HEIGHT: Int = 180

    /** Largest supported capture height in pixels (inclusive). */
    const val MAX_SOURCE_HEIGHT: Int = 2048

    /** The two lobby controls that must both be observed for a positive handoff. */
    enum class Control { BRAWLERS, PLAY }

    /**
     * Disjoint observation regions, in whole percent of the capture.
     *
     * The two regions were measured on the returned lobby itself (1280x591 capture: the `BRAWLERS`
     * menu label occupies roughly x 75..150 / y 300 and the `PLAY` control roughly x 1090 / y 530).
     * The left band therefore ends long before the right one begins, so a single recognized line
     * can never satisfy both controls and no candidate can be clipped into a neighbouring region.
     */
    const val BRAWLERS_LEFT_PCT: Int = 0
    const val BRAWLERS_RIGHT_PCT: Int = 18
    const val BRAWLERS_TOP_PCT: Int = 46
    const val BRAWLERS_BOTTOM_PCT: Int = 56

    const val PLAY_LEFT_PCT: Int = 80
    const val PLAY_RIGHT_PCT: Int = 96
    const val PLAY_TOP_PCT: Int = 82
    const val PLAY_BOTTOM_PCT: Int = 96

    /** Black separator between two consecutive mosaic tiles, in mosaic pixels. */
    const val GUTTER: Int = 24

    /**
     * The ONLY accepted raw `BRAWLERS` labels, compared as whole normalized strings.
     *
     * Reused verbatim from the existing verified menu contract; a card title or any other text that
     * merely contains one of these words normalizes to something else and is therefore not a match.
     */
    private val BRAWLERS_LABELS = setOf("brawlers", "brawler", "lottatori")

    /**
     * The ONLY accepted raw `PLAY` labels, compared as whole normalized strings.
     *
     * Spelled out for the same locales the selector already carries for `SELECT`/`BRAWLERS`
     * (`LobbyAutomation.SELECT_BUTTON_LABELS`), plus accented variants that
     * [BrawlerSelectionGate.normalize] reconciles. Nothing is stripped or substring-matched, so
     * "PLAYER", "PLAY AGAIN", "REPLAY" and "Play!" are not matches.
     */
    private val PLAY_LABELS = setOf(
        "play",
        "jouer",
        "gioca",
        "giocare",
        "jugar",
        "spielen",
        "juegue",
        "jouez",
    )

    private const val PERCENT = 100L

    /** Number of observation regions a plan always has: one per required control. */
    const val REGION_COUNT: Int = 2

    /**
     * The complete mosaic plan for one capture, plus the capture region each control is read in.
     *
     * @param scalePermille the configured OCR scale as exact integer permille (100..1000), so the
     *   plan - and therefore every mapped coordinate - is reproducible from integers alone.
     */
    data class Plan(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val scalePermille: Int,
        val width: Int,
        val height: Int,
        val tiles: List<GridOcrTile>,
    )

    /**
     * Builds the mosaic plan for a [width] x [height] capture at [scalePermille], or returns `null`
     * when the frame is outside the supported contract.
     *
     * The plan is a pure description: it carries no pixels and no timestamps, so the caller may
     * build it on a worker thread, compare it in tests and reuse it for the whole bounded phase.
     * A `null` result means "this capture is not supported by the gate", and the caller must then
     * fail closed instead of falling back to a whole-frame scan.
     */
    fun plan(width: Int, height: Int, scalePermille: Int): Plan? {
        if (!isSupported(width, height)) return null
        if (scalePermille < 100 || scalePermille > 1_000) return null

        val brawlers = region(
            BRAWLERS_LEFT_PCT, BRAWLERS_TOP_PCT, BRAWLERS_RIGHT_PCT, BRAWLERS_BOTTOM_PCT,
            width, height,
        ) ?: return null
        val play = region(
            PLAY_LEFT_PCT, PLAY_TOP_PCT, PLAY_RIGHT_PCT, PLAY_BOTTOM_PCT, width, height,
        ) ?: return null

        val tiles = ArrayList<GridOcrTile>(REGION_COUNT)
        var cursorY = 0L
        var mosaicWidth = 0L
        for (source in listOf(brawlers, play)) {
            val sourceWidth = (source.right - source.left).toLong()
            val sourceHeight = (source.bottom - source.top).toLong()
            val tileWidth = sourceWidth * scalePermille / 1_000L
            val tileHeight = sourceHeight * scalePermille / 1_000L
            if (tileWidth <= 0L || tileHeight <= 0L) return null
            if (tiles.isNotEmpty()) cursorY += GUTTER.toLong()
            val destination = GridOcrRect(
                left = 0,
                top = toIntExact(cursorY),
                right = toIntExact(tileWidth),
                bottom = toIntExact(cursorY + tileHeight),
            )
            tiles.add(GridOcrTile(source = source, destination = destination))
            cursorY = destination.bottom.toLong()
            if (destination.right.toLong() > mosaicWidth) mosaicWidth = destination.right.toLong()
        }
        val outWidth = toIntExact(mosaicWidth)
        val outHeight = toIntExact(cursorY)
        if (outWidth <= 0 || outHeight <= 0) return null
        return Plan(width, height, scalePermille, outWidth, outHeight, tiles)
    }

    /**
     * The capture region [control] may only be recognized in, as exact integer capture pixels.
     *
     * Returns `null` for an unsupported plan or an unknown control; a caller that cannot name the
     * region never gets one.
     */
    fun region(plan: Plan, control: Control): GridOcrRect? {
        if (!isCanonical(plan)) return null
        return when (control) {
            Control.BRAWLERS -> plan.tiles[0].source
            Control.PLAY -> plan.tiles[1].source
        }
    }

    /**
     * Maps a box from mosaic coordinates back into FULL-frame capture coordinates.
     *
     * [box] must be a positive-area rectangle lying fully inside exactly one tile. Empty, negative,
     * out-of-mosaic, gutter-spanning and tile-bridging boxes return `null` and are never clipped
     * into a neighbouring tile. Each axis is mapped with the exact integer ratio of its tile
     * (source extent over destination extent): the leading edge floors and the trailing edge ceils,
     * so a mapped box keeps every mosaic pixel it contained, and a result that would leave the
     * tile's capture rectangle is rejected instead of clipped.
     *
     * The plan is re-validated structurally against [plan] first, so a hand-built or mutated
     * instance can never be mapped through.
     */
    fun map(plan: Plan, box: GridOcrRect): GridOcrRect? {
        if (!isCanonical(plan)) return null
        if (box.right <= box.left || box.bottom <= box.top) return null
        if (box.left < 0 || box.top < 0) return null
        if (box.right > plan.width || box.bottom > plan.height) return null

        var owner: GridOcrTile? = null
        for (tile in plan.tiles) {
            if (!contains(tile.destination, box)) continue
            // A canonical plan cannot produce two owners; kept so ambiguity is never silently resolved.
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
        if (mapped.left < source.left || mapped.top < source.top) return null
        if (mapped.right > source.right || mapped.bottom > source.bottom) return null
        if (mapped.width <= 0 || mapped.height <= 0) return null
        return mapped
    }

    /**
     * The single unambiguous, fully in-frame control label of each required control, ordered
     * [Control.BRAWLERS] then [Control.PLAY], or `null`.
     *
     * `null` - never a partial answer - is returned when:
     *  - either control has no candidate at all in its own region (a missing `PLAY` or `BRAWLERS`
     *    is not a lobby control proof);
     *  - either control is reported by more than one usable box in the same frame (an ambiguous or
     *    duplicated label is never collapsed into the more confident candidate);
     *  - a candidate is degenerate, out of frame, only partly inside its own region, or reports a
     *    different word for the two controls.
     *
     * [unmappedLabelSeen] is the caller's already-established fact that the extractor dropped a
     * control-labelled box it could not map back into the capture. When it is true this function
     * returns `null` unconditionally: an unmapped duplicate must never be able to make its mapped
     * neighbour look unique.
     */
    fun uniqueControls(scan: BrawlerOcrScan, plan: Plan, unmappedLabelSeen: Boolean = false): List<BrawlerOcrHit>? {
        if (unmappedLabelSeen) return null
        if (!isCanonical(plan)) return null
        if (scan.width != plan.sourceWidth || scan.height != plan.sourceHeight) return null
        if (scan.width <= 0 || scan.height <= 0) return null

        val out = ArrayList<BrawlerOcrHit>(REGION_COUNT)
        for (control in CONTROL_ORDER) {
            val area = region(plan, control) ?: return null
            var match: BrawlerOcrHit? = null
            for (hit in scan.hits) {
                if (!isLabel(control, hit.text)) continue
                if (hit.right <= hit.left || hit.bottom <= hit.top) return null
                if (hit.left < 0 || hit.top < 0) return null
                if (hit.right > scan.width || hit.bottom > scan.height) return null
                if (hit.left < area.left || hit.top < area.top) return null
                if (hit.right > area.right || hit.bottom > area.bottom) return null
                // A second usable box for the same control is ambiguity, never a better candidate.
                if (match != null) return null
                match = hit
            }
            out.add(match ?: return null)
        }
        return out
    }

    /** Same-phase reuse only for byte-identical COMPLETE rendered OCR input. The original
     * evidence timestamp never advances: neither repeated captures nor cache hits renew its age.
     * Full-frame state, two-capture geometry and the final live check remain caller-owned. */
    class OcrReuse {
        private var observed: BrawlerOcrScan? = null
        private var layout: Plan? = null
        private var pixels: IntArray? = null

        fun clear() { observed = null; layout = null; pixels = null }

        fun reuse(frame: FrameSnapshot, plan: Plan, rendered: IntArray, nowMs: Long): BrawlerOcrScan? {
            val prior = observed ?: return null
            if (layout != plan || frame.width != prior.width || frame.height != prior.height ||
                frame.capturedAtMs <= prior.capturedAtMs || !fresh(prior.capturedAtMs, nowMs) ||
                !fresh(frame.capturedAtMs, nowMs) || !rendered.contentEquals(pixels)) {
                clear()
                return null
            }
            // Same complete recognizer input on a genuinely newer capture, not a redated old frame.
            return prior.copy(capturedAtMs = frame.capturedAtMs)
        }

        fun remember(scan: BrawlerOcrScan, plan: Plan, rendered: IntArray, nowMs: Long) {
            clear()
            if (scan.width != plan.sourceWidth || scan.height != plan.sourceHeight ||
                rendered.size.toLong() != plan.width.toLong() * plan.height ||
                !fresh(scan.capturedAtMs, nowMs) || uniqueControls(scan, plan) == null) return
            observed = scan.copy(hits = scan.hits.toList())
            layout = plan
            pixels = rendered.clone()
        }

        private fun fresh(stamp: Long, now: Long) =
            stamp > 0L && now >= stamp && now - stamp <= MAX_AGE_MS
    }

    /** Deterministic order of the required controls; never derived from OCR ordering. */
    private val CONTROL_ORDER = listOf(Control.BRAWLERS, Control.PLAY)

    /**
     * True when the two confirmed control pairs of a [height]-pixel capture describe the same
     * controls in the same place.
     *
     * Both lists must come from [uniqueControls], so they are already ordered
     * [Control.BRAWLERS] then [Control.PLAY] and already carry one box per control. Every box must
     * agree within the existing geometry tolerance ([BrawlerSelectionGate.tolerancePx]) on its
     * normalized text and on all four edges, so a control that shifted, swapped sides or changed
     * word between the two captures is rejected instead of being acted on.
     */
    fun agree(height: Int, first: List<BrawlerOcrHit>, second: List<BrawlerOcrHit>): Boolean {
        if (height <= 0) return false
        if (first.size != REGION_COUNT || second.size != REGION_COUNT) return false
        val tolerance = BrawlerSelectionGate.tolerancePx(height)
        for (index in CONTROL_ORDER.indices) {
            val a = first[index]
            val b = second[index]
            if (BrawlerSelectionGate.normalize(a.text) != BrawlerSelectionGate.normalize(b.text)) return false
            if (abs(a.left - b.left) > tolerance) return false
            if (abs(a.top - b.top) > tolerance) return false
            if (abs(a.right - b.right) > tolerance) return false
            if (abs(a.bottom - b.bottom) > tolerance) return false
        }
        return true
    }

    /** True when two captures show a timestamp pair this gate may accept. */
    fun distinctFreshPair(first: BrawlerOcrScan, second: BrawlerOcrScan, nowMs: Long): Boolean {
        if (nowMs < 0L) return false
        if (first.capturedAtMs <= 0L || second.capturedAtMs <= 0L) return false
        if (second.capturedAtMs <= first.capturedAtMs) return false
        if (first.capturedAtMs > nowMs || second.capturedAtMs > nowMs) return false
        if (nowMs - first.capturedAtMs > MAX_AGE_MS) return false
        if (nowMs - second.capturedAtMs > MAX_AGE_MS) return false
        if (second.capturedAtMs - first.capturedAtMs > MAX_GAP_MS) return false
        if (first.width != second.width || first.height != second.height) return false
        if (first.width <= 0 || first.height <= 0) return false
        return true
    }

    /**
     * The bounded pixel windows around both confirmed control labels, or `null` when any of them is
     * invalid.
     *
     * Windows come from [LobbyMenuEntryGate.aroundLabel], i.e. the already-proven per-control
     * geometry slack around an OBSERVED box - never a whole-frame lattice. That is what lets a
     * permanently animating lobby character be ignored while a moving control is still refused: the
     * character is outside every window, a sliding button is inside one.
     */
    fun controlWindows(
        width: Int,
        height: Int,
        controls: List<BrawlerOcrHit>,
    ): List<LobbyMenuEntryGate.Bounds>? {
        if (controls.size != REGION_COUNT) return null
        val out = ArrayList<LobbyMenuEntryGate.Bounds>(REGION_COUNT)
        for (hit in controls) {
            val bounds = LobbyMenuEntryGate.aroundLabel(width, height, hit) ?: return null
            out.add(bounds)
        }
        return out
    }

    /**
     * True when every control window in [windows] is pixel-stable across [first] and [second],
     * using the unchanged 5%-changed / mean-RGB-delta<=9 policy of [LobbyMenuEntryGate.pixelsStill].
     *
     * All windows must hold: one stable label never compensates for a moving other one. Mismatched
     * capture geometry or a malformed pixel buffer fails closed without being inspected.
     */
    fun controlsStill(
        first: FrameSnapshot,
        second: FrameSnapshot,
        windows: List<LobbyMenuEntryGate.Bounds>,
    ): Boolean {
        if (windows.isEmpty()) return false
        if (first.width != second.width || first.height != second.height) return false
        if (first.width <= 0 || first.height <= 0) return false
        if (first.argb.size.toLong() != first.width.toLong() * first.height) return false
        if (second.argb.size.toLong() != second.width.toLong() * second.height) return false
        for (bounds in windows) {
            if (!LobbyMenuEntryGate.pixelsStill(
                    first.width, first.height, first.argb, second.argb, bounds)) return false
        }
        return true
    }

    /** True when [text] is exactly the [Control.BRAWLERS] control label, never a substring of one. */
    fun isBrawlersLabel(text: String): Boolean = matches(BRAWLERS_LABELS, text)

    /** True when [text] is exactly the [Control.PLAY] control label, never a substring of one. */
    fun isPlayLabel(text: String): Boolean = matches(PLAY_LABELS, text)

    /** True when [text] is exactly the label of [control]. */
    fun isLabel(control: Control, text: String): Boolean = when (control) {
        Control.BRAWLERS -> isBrawlersLabel(text)
        Control.PLAY -> isPlayLabel(text)
    }

    private fun matches(labels: Set<String>, text: String): Boolean =
        BrawlerSelectionGate.normalize(text) in labels

    /**
     * Returns `true` only for a plan [plan] itself would produce for its own source dimensions and
     * scale, rejecting any hand-built or mutated instance (wrong mosaic size, missing/extra tile,
     * moved destination, wrong source band) in one step.
     */
    private fun isCanonical(plan: Plan): Boolean {
        val canonical = plan(plan.sourceWidth, plan.sourceHeight, plan.scalePermille) ?: return false
        if (canonical.width != plan.width || canonical.height != plan.height) return false
        if (canonical.tiles.size != plan.tiles.size) return false
        for (index in canonical.tiles.indices) {
            if (canonical.tiles[index] != plan.tiles[index]) return false
        }
        return true
    }

    /** Size and aspect-ratio gate of the supported capture contract. */
    private fun isSupported(width: Int, height: Int): Boolean {
        if (width < MIN_SOURCE_WIDTH || width > MAX_SOURCE_WIDTH) return false
        if (height < MIN_SOURCE_HEIGHT || height > MAX_SOURCE_HEIGHT) return false
        if (width.toLong() * 9L < height.toLong() * 16L) return false
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

    /** True when [inner] lies fully inside [outer]; a gutter or tile edge is therefore outside. */
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

    private fun abs(value: Int): Int = if (value < 0) -value else value
}