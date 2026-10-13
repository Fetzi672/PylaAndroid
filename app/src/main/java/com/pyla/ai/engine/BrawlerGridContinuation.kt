package com.pyla.ai.engine

import java.util.Locale

/**
 * Supplemental, pure pair evidence for a brawler-list continuation page.
 *
 * Delivered list-search runs established that on the supplied continuation screenshot the brawler
 * grid is recognised reliably by the card-title strips but its heading is never recognised exactly,
 * so the delivered native adapter reads known names and 0 exact headings on that page. This helper
 * does NOT re-open that gap: it accepts no header and grants no action authority. It only answers a
 * narrower question for the caller: "do these two fresh, identically sized captures show the SAME
 * sort label inside the sort strip AND at least one stable, distinct known brawler identity in each
 * of the three canonical title strips?"
 *
 * The supplied screenshot is a MULTI-COLUMN grid: it carries six distinct known card titles over
 * those three rows. Each strip is therefore evaluated per card - a known title is confirmed on its
 * own across the pair - and a row is covered when it holds at least one such stable identity. A
 * strip that legitimately shows several DIFFERENT known cards is never rejected for being a grid.
 *
 * The verdict is a single `Boolean` and is strictly additional current-pair evidence. The caller
 * must still run its own live list gate before acting, and must call this only after an existing
 * legitimate initial grid admission plus an authorized paging request.
 *
 * Deliberately narrow and pure:
 *  - no I/O, no clock, no logging, no mutable state, no OCR-input reconstruction;
 *  - no partial match, no substring search, no fuzzy/edit-distance fallback, no punctuation
 *    stripping and no knowledge of the list's end;
 *  - every rejection is a plain `false`, exactly like the existing gates.
 *
 * Geometry comes from [BrawlerGridOcrRegions.plan]; only the three card-title strips (tiles
 * 1..3) are used for the body bands. The tile list length is never assumed, so a caller that
 * appends further tiles to the plan keeps working unchanged.
 */
object BrawlerGridContinuation {

    /** Freshness budget for both captures, in milliseconds. */
    const val MAX_AGE_MS: Long = 3_000L

    /** Maximum separation between the two captures, in milliseconds. */
    const val MAX_GAP_MS: Long = 4_000L

    /** Region of interest of the sort label, in whole percent of the capture. */
    const val SORT_LEFT_PCT: Int = 40
    const val SORT_RIGHT_PCT: Int = 75
    const val SORT_TOP_PCT: Int = 0
    const val SORT_BOTTOM_PCT: Int = 6

    private const val PERCENT = 100L

    /** Longest raw sort element inspected before any comparison. */
    private const val MAX_SORT_LENGTH = 32

    /** The first tile after the heading tile, i.e. the first card-title strip. */
    private const val FIRST_BODY_TILE_INDEX = 1

    /** Index of the heading tile in the plan: it is followed by the three title strips. */
    private const val HEADER_TILE_INDEX = 0

    /** Number of card-title strips a three-row list is recognised as. */
    private const val BODY_STRIP_COUNT = 3

    /** Number of distinct known identities the three rows must collectively carry. */
    private const val REQUIRED_DISTINCT_IDENTITIES = 3

    /**
     * The ONLY accepted raw sort labels, compared after `trim()` and `Locale.ROOT` lowercasing of
     * the whole raw string.
     *
     * Accented and unaccented spellings are both listed on purpose: the raw string itself must be
     * one exact word, and the accents are only reconciled afterwards, by
     * [BrawlerSelectionGate.normalize], when the confirmed pair is compared. Nothing is stripped
     * from the raw string, so `"Rarity:"`, `"Sort: Rarity"`, `"Rarities"` and `"Rare"` are rejected.
     */
    private val SORT_LABELS = setOf(
        "rarity",
        "seltenheit",
        "rarità",
        "rarita",
        "rareté",
        "rarete",
        "rareza",
    )

    /**
     * Returns `true` only when BOTH fresh captures show the same sort label inside the sort strip
     * of interest AND every one of the three canonical card-title strips carries at least one
     * DISTINCT known brawler identity confirmed across the pair inside that strip, with at least
     * [REQUIRED_DISTINCT_IDENTITIES] distinct identities collected over the three rows.
     *
     * Returns `false` when any of the following holds:
     *  - [knownNames] is empty, [nowMs] is negative, or the frames are not two distinct positive
     *    captures that are fresh, correctly ordered and no more than [MAX_GAP_MS] apart;
     *  - either capture is flagged `gridHeaderAmbiguous`, because the extractor could not resolve
     *    which of several heading labels was the real one, or either capture carries MORE THAN ONE
     *    heading label in the heading band: an unresolved duplicate heading must never be allowed
     *    to downgrade into sort evidence on a page whose heading was the thing in doubt;
     *  - the captures differ in width or height, or their geometry is not a supported list aspect
     *    ([BrawlerGridOcrRegions.plan] returns `null`);
     *  - a scan does not contain EXACTLY one strict sort candidate fully inside the sort region, or
     *    the two confirmed sort labels differ;
     *  - a strip yields no confirmed identity in either frame, a strip's known boxes cannot all be
     *    paired up because a duplicated identity was reported, an identity moves more than the
     *    existing [BrawlerSelectionGate] geometry tolerance, an identity is only visible in one
     *    frame, or fewer than [REQUIRED_DISTINCT_IDENTITIES] distinct identities are collected;
     *  - any candidate box is degenerate, reversed, partly outside the frame or partly outside its
     *    band.
     *
     * Multi-column rows are legitimate, so several DIFFERENT known identities inside one strip are
     * each confirmed on their own and the row is then covered. Duplicates are still never
     * collapsed: two identical sort words in one frame, or two OCR boxes reporting the SAME
     * identity inside one strip, are ambiguous evidence and therefore never authorize.
     */
    fun confirm(
        first: BrawlerOcrScan,
        second: BrawlerOcrScan,
        knownNames: Set<String>,
        nowMs: Long,
    ): Boolean {
        // ---- clock, names and capture identity ------------------------------------------------
        if (nowMs < 0L) return false
        if (knownNames.isEmpty()) return false
        // The extractor already reported unresolved heading duplicates for this frame.
        if (first.gridHeaderAmbiguous || second.gridHeaderAmbiguous) return false
        if (first.width <= 0 || first.height <= 0) return false
        if (first.width != second.width || first.height != second.height) return false
        if (!distinctFreshPair(first, second, nowMs)) return false

        // ---- supported list geometry ----------------------------------------------------------
        val plan = BrawlerGridOcrRegions.plan(first.width, first.height) ?: return false
        val tiles = plan.tiles
        // Never assume an exact tile count: tiles may legitimately be appended to the plan.
        if (tiles.size < FIRST_BODY_TILE_INDEX + BODY_STRIP_COUNT) return false

        // ---- the heading must be unambiguous in both frames -----------------------------------
        val headerBand = tiles[HEADER_TILE_INDEX].source
        if (headerBand.width <= 0 || headerBand.height <= 0) return false
        if (headingCount(first, headerBand) > 1) return false
        if (headingCount(second, headerBand) > 1) return false

        val names = LinkedHashSet<String>()
        for (name in knownNames) {
            val normalized = BrawlerSelectionGate.normalize(name)
            if (normalized.isNotEmpty()) names.add(normalized)
        }
        if (names.isEmpty()) return false

        // ---- the sort label, confirmed across the fresh pair ----------------------------------
        val sortValues = LinkedHashSet<String>()
        for (label in SORT_LABELS) sortValues.add(BrawlerSelectionGate.normalize(label))
        if (!confirmSort(first, second, nowMs, sortValues)) return false

        // ---- every row carries its own stable known identity, three identities overall --------
        val identities = LinkedHashSet<String>()
        var offset = 0
        while (offset < BODY_STRIP_COUNT) {
            val band = tiles[FIRST_BODY_TILE_INDEX + offset].source
            val rowIdentities = confirmStrip(first, second, band, names, nowMs)
            // An unpairable strip leaves its row uncovered, so the page is never admitted.
            if (rowIdentities == null) return false
            // No card of this row may repeat an identity an earlier row already used: one identity
            // can never satisfy two rows, whichever column it appeared in.
            for (identity in rowIdentities) {
                if (!identities.add(identity)) return false
            }
            offset++
        }
        // Three rows each contributed at least one new identity, so this is already true; it is
        // stated explicitly so the required distinct count stays visible at the decision point.
        return identities.size >= REQUIRED_DISTINCT_IDENTITIES
    }

    /** True when [text] is EXACTLY one known sort word, after `trim()` and ROOT lowercasing. */
    fun isSortLabel(text: String): Boolean {
        if (text.length > MAX_SORT_LENGTH) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_SORT_LENGTH) return false
        return trimmed.lowercase(Locale.ROOT) in SORT_LABELS
    }

    /**
     * Counts the heading labels recognised inside [band], reusing the existing exact heading
     * classifier [BrawlerGridHeader.isHeader].
     *
 * A duplicate heading is never collapsed here: the count is the evidence, so two identical
 * heading strings in one frame are already ambiguous and must be reported as two.
 */
    private fun headingCount(scan: BrawlerOcrScan, band: GridOcrRect): Int {
        if (scan.width <= 0 || scan.height <= 0) return 0
        var count = 0
        for (hit in scan.hits) {
            if (hit.width <= 0 || hit.height <= 0) continue
            if (hit.left < band.left || hit.top < band.top) continue
            if (hit.right > band.right || hit.bottom > band.bottom) continue
            if (!BrawlerGridHeader.isHeader(hit.text)) continue
            count++
        }
        return count
    }

    /** The sort region of interest of a [width] x [height] capture, as exact integer capture pixels. */
    fun sortRegion(width: Int, height: Int): GridOcrRect {
        // The lower bound is a ceil so a box is only accepted when it starts at or after the mark.
        val left = ceilDiv(width.toLong() * SORT_LEFT_PCT, PERCENT)
        val right = width.toLong() * SORT_RIGHT_PCT / PERCENT
        val top = height.toLong() * SORT_TOP_PCT / PERCENT
        val bottom = height.toLong() * SORT_BOTTOM_PCT / PERCENT
        return GridOcrRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    /**
     * Confirms the sort label across the pair, over the strictly filtered sort candidates only.
     *
     * The candidate list is narrowed here - exact raw label plus full containment in the region of
     * interest - so the existing [BrawlerSelectionGate.confirmPair] geometry tolerance, freshness
     * and uniqueness rules apply unchanged on top of it. Duplicate raw labels stay in the list and
     * are therefore rejected as ambiguous evidence.
     */
    private fun confirmSort(
        first: BrawlerOcrScan,
        second: BrawlerOcrScan,
        nowMs: Long,
        sortValues: Set<String>,
    ): Boolean {
        val region = sortRegion(first.width, first.height)
        val firstNarrowed = narrow(first, region) { hit -> isSortLabel(hit.text) }
        val secondNarrowed = narrow(second, region) { hit -> isSortLabel(hit.text) }
        if (firstNarrowed.hits.isEmpty() || secondNarrowed.hits.isEmpty()) return false
        val firstLabel = BrawlerSelectionGate.normalize(firstNarrowed.hits[0].text)
        if (firstLabel !in sortValues) return false
        return BrawlerSelectionGate.confirmPair(
            first = firstNarrowed,
            second = secondNarrowed,
            names = sortValues,
            topFraction = 0.0,
            bottomFraction = 1.0,
            nowMs = nowMs,
            maxAgeMs = MAX_AGE_MS,
            maxGapMs = MAX_GAP_MS,
        ) != null
    }

    /**
     * Confirms EVERY known identity inside one canonical card-title strip, each on its own, and
     * returns the normalized values in a deterministic order (first by the pair's left edge, then
     * by its normalized name).
     *
     * The strip is enforced with exact integer containment before anything else runs, so a name
     * that merely clips a band or sits outside the frame is dropped instead of counted. Each
     * remaining identity is then confirmed across the pair with the existing
     * [BrawlerSelectionGate.confirmPair] applied to a scan narrowed to that ONE identity, so the
     * unchanged freshness, uniqueness and movement rules decide every card individually and a
     * multi-column row is never collapsed into a single best match.
     *
     * Returns `null` - the row is uncovered - when a strip's known boxes cannot all be paired:
     *  - a duplicated identity is reported twice inside one frame (ambiguous, never collapsed);
     *  - a known identity does not appear in both frames, so it cannot be shown stable;
     *  - a known identity moves further than the existing geometry tolerance between the frames;
     *  - a confirmed identity in one frame turns into a different confirmed identity in the other.
     *
     * A strip holding several DIFFERENT stable identities returns them all; the caller treats the
     * row as covered and still requires a distinct identity for every other row.
     */
    private fun confirmStrip(
        first: BrawlerOcrScan,
        second: BrawlerOcrScan,
        band: GridOcrRect,
        names: Set<String>,
        nowMs: Long,
    ): List<String>? {
        if (band.width <= 0 || band.height <= 0) return null
        val keep = { hit: BrawlerOcrHit -> names.contains(BrawlerSelectionGate.normalize(hit.text)) }
        val firstNarrowed = narrow(first, band, keep)
        val secondNarrowed = narrow(second, band, keep)
        if (firstNarrowed.hits.isEmpty() || secondNarrowed.hits.isEmpty()) return null

        // Group by normalized identity; a name reported twice in ONE frame is ambiguous evidence.
        val firstByName = groupUnique(firstNarrowed)
        val secondByName = groupUnique(secondNarrowed)
        if (firstByName == null || secondByName == null) return null

        // Every known card of the row must be present in both frames: a card that appears in only
        // one frame, or disappears from the row, is not stable evidence.
        if (firstByName.keys != secondByName.keys) return null

        // Deterministic order: left-to-right, then by name, so the caller's per-row choice never
        // depends on the recognition order of the OCR boxes.
        val order = ArrayList<String>(firstByName.keys)
        order.sortWith(compareBy({ firstByName.getValue(it).left }, { it }))

        val confirmed = ArrayList<String>(order.size)
        for (name in order) {
            val firstCard = narrow(firstNarrowed, band) { hit: BrawlerOcrHit ->
                BrawlerSelectionGate.normalize(hit.text) == name
            }
            val secondCard = narrow(secondNarrowed, band) { hit: BrawlerOcrHit ->
                BrawlerSelectionGate.normalize(hit.text) == name
            }
            val hit = BrawlerSelectionGate.confirmPair(
                first = firstCard,
                second = secondCard,
                names = setOf(name),
                topFraction = 0.0,
                bottomFraction = 1.0,
                nowMs = nowMs,
                maxAgeMs = MAX_AGE_MS,
                maxGapMs = MAX_GAP_MS,
            ) ?: return null
            confirmed.add(BrawlerSelectionGate.normalize(hit.text))
        }
        return confirmed
    }

    /**
     * Maps each normalized known identity in [scan] to its single box, or returns `null` when an
     * identity is reported by more than one box in the same frame.
     *
     * This is the per-card duplicate rule for a multi-column row: two OCR lines reading the same
     * card title are ambiguous evidence, while two lines reading DIFFERENT titles are a grid.
     */
    private fun groupUnique(scan: BrawlerOcrScan): Map<String, BrawlerOcrHit>? {
        val byName = LinkedHashMap<String, BrawlerOcrHit>()
        for (hit in scan.hits) {
            val name = BrawlerSelectionGate.normalize(hit.text)
            if (byName.containsKey(name)) return null
            byName[name] = hit
        }
        return byName
    }

    /**
     * Two DISTINCT, positive, fresh captures with a sane clock order.
     *
     * Deliberately stated before any geometry work so a reused or future-dated capture can never
     * reach the evidence stages below.
     */
    private fun distinctFreshPair(first: BrawlerOcrScan, second: BrawlerOcrScan, nowMs: Long): Boolean {
        if (first.capturedAtMs <= 0L || second.capturedAtMs <= 0L) return false
        if (second.capturedAtMs <= first.capturedAtMs) return false
        if (first.capturedAtMs > nowMs || second.capturedAtMs > nowMs) return false
        if (nowMs - first.capturedAtMs > MAX_AGE_MS) return false
        if (nowMs - second.capturedAtMs > MAX_AGE_MS) return false
        if (second.capturedAtMs - first.capturedAtMs > MAX_GAP_MS) return false
        return true
    }

    /**
     * Returns [scan] with only the boxes that are usable inside [region] AND accepted by [keep].
     *
     * A usable box is non-degenerate, fully inside the frame and fully inside [region]: a reversed,
     * zero-area, out-of-frame or band-clipping box is dropped here, so it can never be counted as
     * evidence and is never clipped into a neighbouring band. Accepted boxes keep their raw text
     * and their exact coordinates, and duplicates are preserved so the existing uniqueness rule can
     * reject them.
     */
    private fun narrow(
        scan: BrawlerOcrScan,
        region: GridOcrRect,
        keep: (BrawlerOcrHit) -> Boolean,
    ): BrawlerOcrScan {
        if (scan.width <= 0 || scan.height <= 0) return BrawlerOcrScan(scan.width, scan.height, scan.capturedAtMs, emptyList())
        val kept = ArrayList<BrawlerOcrHit>()
        for (hit in scan.hits) {
            if (hit.width <= 0 || hit.height <= 0) continue
            if (hit.left < 0 || hit.top < 0) continue
            if (hit.right > scan.width || hit.bottom > scan.height) continue
            if (hit.left < region.left || hit.top < region.top) continue
            if (hit.right > region.right || hit.bottom > region.bottom) continue
            if (!keep(hit)) continue
            kept.add(hit)
        }
        return BrawlerOcrScan(scan.width, scan.height, scan.capturedAtMs, kept)
    }

    /** ceil(value / divisor) for non-negative inputs, on Long intermediates. */
    private fun ceilDiv(value: Long, divisor: Long): Long = (value + divisor - 1L) / divisor
}