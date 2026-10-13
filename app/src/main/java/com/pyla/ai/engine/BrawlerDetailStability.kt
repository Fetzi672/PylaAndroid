package com.pyla.ai.engine

/**
 * DETAIL-only control-window stability evidence.
 *
 * ## Why the whole-left-screen rule is replaced
 *
 * The DETAIL screen animates continuously - the brawler render, particle effects and banner motion
 * - everywhere EXCEPT the two controls the selector actually acts on: the uniquely recognized
 * title and the SELECT button. The previous rule compared a broad sample lattice of the whole left
 * half of the capture, so a perfectly valid detail screen whose character kept animating could
 * never prove stability and the selection was abandoned near the phase deadline. That is the
 * recurring failure this helper exists to remove; it is not a threshold tweak.
 *
 * ## What replaces it
 *
 * Only the bounded vicinity of the two OBSERVED control boxes is compared - exactly the
 * per-control geometry slack [LobbyMenuEntryGate.aroundLabel] already uses for the lobby handoff -
 * with the unchanged 5%-changed / mean-RGB-delta<=9 pixel policy. Continuous animation outside
 * those windows is ignored; a title or SELECT that disappeared, moved, changed or got covered is
 * inside its own window and is still refused.
 *
 * ## Deliberately pure
 *
 * No Android, no ML Kit, no clock, no I/O, no logging, no mutable state and no action authority:
 * every rejection is a plain `null`/`false`, exactly like [BrawlerSelectionGate],
 * [BrawlerGridContinuation] and [BrawlerLobbyReturnGate]. The caller keeps its own freshness,
 * deadline, cancellation and live authority, and this helper never sees them.
 */
object BrawlerDetailStability {

    /** Number of control windows on the detail screen: the title and the SELECT button. */
    const val WINDOW_COUNT: Int = 2

    /**
     * The two bounded control windows of a `width` x `height` capture: the recognized title first,
     * then the recognized SELECT. Returns `null` when either box is degenerate or outside the
     * frame, so a caller that cannot localize a control never gets a window for it.
     */
    fun windows(
        width: Int,
        height: Int,
        title: BrawlerOcrHit,
        select: BrawlerOcrHit,
    ): List<LobbyMenuEntryGate.Bounds>? {
        if (width <= 0 || height <= 0) return null
        val titleWindow = LobbyMenuEntryGate.aroundLabel(width, height, title) ?: return null
        val selectWindow = LobbyMenuEntryGate.aroundLabel(width, height, select) ?: return null
        return listOf(titleWindow, selectWindow)
    }

    /**
     * True when EVERY window is pixel-stable across two captures, using the unchanged
     * 5%-changed / mean-RGB-delta<=9 policy of [LobbyMenuEntryGate.pixelsStill].
     *
     * All windows must hold: one stable control never compensates for a moving other one.
     * Mismatched capture geometry or a malformed pixel buffer fails closed without being inspected.
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
            if (!LobbyMenuEntryGate.pixelsStill(first.width, first.height, first.argb, second.argb, bounds)) {
                return false
            }
        }
        return true
    }
}
