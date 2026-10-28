package com.pyla.ai.engine

/**
 * GRID pre-card-tap stability evidence bound to the ONE card the selector is about to tap.
 *
 * ## Why the whole-area rule is replaced here only
 *
 * Unrelated preview/banner pixels can change while a target label stays stable.
 * The synthetic differential fixture models that condition; a single phone screenshot does not
 * establish which pixels moved during the reported run. The previous live gate before the card tap compared a broad 20x20 lattice
 * over the whole capture (x 4%..96%, y 16%..96%), which includes that animated preview, so a
 * confirmed end-of-list target can fail the last live check. The reported run published
 * `VERIFY_DETAIL`, logged `live_pixels_changed` and abandoned the run before any card tap - the
 * exact reported boundary (`grid_admitted`, `SCAN_NAMES` -> `VERIFY_DETAIL`, `selection.live.grid`
 * `reason=live_pixels_changed`, no DETAIL/SELECT stage).
 *
 * ## What replaces it
 *
 * Only the bounded vicinity of the OBSERVED target label - the already-proven per-control geometry
 * slack of [LobbyMenuEntryGate.aroundLabel] - is compared, with the unchanged 5%-changed /
 * mean-RGB-delta<=9 pixel policy of [LobbyMenuEntryGate.pixelsStill]. Nothing else on the grid is
 * inspected, so unrelated animation (right preview, banner, portraits of other cards) no longer
 * vetoes a tap, while the target label moving, changing, disappearing or being covered is inside its
 * own window and is still refused. The tap point is the same observed box centre, so what is proven
 * stable is exactly what is tapped.
 *
 * ## Deliberately pure and narrow
 *
 * No Android, no ML Kit, no clock, no I/O, no logging, no mutable state and no action authority:
 * every rejection is a plain `null`/`false`, exactly like [BrawlerDetailStability],
 * [BrawlerSelectionGate] and [LobbyMenuEntryGate]. The caller keeps its own freshness, deadline,
 * cancellation and live authority, and this helper never sees them. It grants nothing beyond the
 * single pre-tap observation it is asked to compare; every other grid gate (list admission, paging,
 * rewind) keeps using its existing whole-area evidence.
 */
object BrawlerGridTargetStability {

    /**
     * The bounded inspection window of a `width` x `height` capture around the observed [target]
     * card label, expressed as a one-element list so the caller can reuse the existing
     * window-stability call sites unchanged.
     *
     * Returns `null` when the observed box is degenerate or does not fit the frame, so a caller that
     * cannot localise the target never gets a window and must keep its stricter whole-area evidence.
     */
    fun windows(
        width: Int,
        height: Int,
        target: BrawlerOcrHit,
    ): List<LobbyMenuEntryGate.Bounds>? {
        if (width <= 0 || height <= 0) return null
        val window = LobbyMenuEntryGate.aroundLabel(width, height, target) ?: return null
        return listOf(window)
    }

    /**
     * True when EVERY window is pixel-stable across the two captures, using the unchanged
     * 5%-changed / mean-RGB-delta<=9 policy of [LobbyMenuEntryGate.pixelsStill].
     *
     * Mismatched capture geometry, an empty window set or a malformed pixel buffer fails closed
     * without being inspected, so this can only ever be as permissive as the policy it reuses.
     */
    fun targetStill(
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
            if (!LobbyMenuEntryGate.pixelsStill(first.width, first.height, first.argb, second.argb, bounds)) {
                return false
            }
        }
        return true
    }
}
