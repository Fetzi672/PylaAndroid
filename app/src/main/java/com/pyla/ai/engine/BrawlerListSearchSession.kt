package com.pyla.ai.engine

import kotlin.math.abs

/**
 * Run-local visual continuity for an ALREADY positively admitted brawler list.
 *
 * An OCR dropout is not a screen transition, and WHICH sort caption the list shows is not a
 * transition either. This binding therefore remembers the PIXELS of the one control whose geometry
 * the product already validated - the sort-caption strip [BrawlerGridContinuation.sortRegion],
 * which sits above the Pyla HUD - and never a recognised word. Every subsequent observation still
 * needs fresh current captures with that control present at the original geometry. The old crop is
 * only a visual template, never a timestamp/authority lease. Caller owns STOP, phase deadlines and
 * discards this object on return. It grants no card identity and no direct input authority.
 *
 * Deliberately OCR-independent:
 *  - the required anchor is the validated sort-control REGION, not any heading, count or HUD text;
 *    `BRAWLERS`/`<count>` is mutable HUD ink on the device and must never be a precondition;
 *  - the two OCR scans are still required to be bound to their exact captures, so a stale scan can
 *    never be mixed with a frame, but their TEXT decides nothing here;
 *  - a flat/blank crop, a generic rectangle and an aspect ratio can never anchor: the crop must
 *    carry real contrast AND be pixel-stable across the pair.
 */
class BrawlerListSearchSession private constructor(
    private val width: Int,
    private val height: Int,
    private val firstCaptureMs: Long,
    private val patches: List<Patch>,
) {
    private data class Patch(val bounds: LobbyMenuEntryGate.Bounds, val pixels: IntArray)

    /** Fresh current evidence against the ORIGINAL admitted chrome; never rolling/template drift. */
    fun matches(frame: FrameSnapshot, nowMs: Long): Boolean {
        if (!validFrame(frame, nowMs) || frame.width != width || frame.height != height ||
            frame.capturedAtMs < firstCaptureMs) return false
        return patches.all { patchStill(it, frame) }
    }

    fun confirms(first: FrameSnapshot, second: FrameSnapshot, nowMs: Long): Boolean =
        second.capturedAtMs > first.capturedAtMs && matches(first, nowMs) && matches(second, nowMs)

    /**
     * Fixed rejection vocabulary for [capture]. Active codes identify the failed visual-binding
     * predicate; none carries OCR text, pixel values, paths or device data.
     *
     * The heading/sort-label codes below are retained so a previously emitted vocabulary keeps its
     * meaning, but they are no longer emitted: caption text and heading multiplicity stopped being
     * preconditions when the anchor became the validated control REGION.
     */
    enum class Rejection(val code: String) {
        NO_INITIAL_CONFIRMATION("no_initial_confirmation"),
        FIRST_FRAME_INVALID("first_frame_invalid"),
        SECOND_FRAME_INVALID("second_frame_invalid"),
        FRAME_SIZE_MISMATCH("frame_size_mismatch"),
        CAPTURE_ORDER_INVALID("capture_order_invalid"),
        FIRST_SCAN_UNBOUND("first_scan_unbound"),
        SECOND_SCAN_UNBOUND("second_scan_unbound"),
        FIRST_HEADER_AMBIGUOUS("first_header_ambiguous"),
        SECOND_HEADER_AMBIGUOUS("second_header_ambiguous"),
        REGION_PLAN_UNAVAILABLE("region_plan_unavailable"),
        FIRST_HEADER_MULTIPLE("first_header_multiple"),
        SECOND_HEADER_MULTIPLE("second_header_multiple"),
        FIRST_SORT_MULTIPLE("first_sort_multiple"),
        SECOND_SORT_MULTIPLE("second_sort_multiple"),
        NO_CONFIRMED_CONTROL("no_confirmed_control"),
        PATCH_AREA_INVALID("patch_area_invalid"),
        PATCH_PIXELS_CHANGED("patch_pixels_changed"),
        PATCH_CROP_FLAT("patch_crop_flat"),
        ;
    }

    /** Optional passive observer. It must never influence the returned session. */
    fun interface CaptureObserver {
        fun rejected(reason: Rejection)
    }

    companion object {
        private const val MAX_AGE_MS = 3_000L // identical to the selector; no TTL extension

        /** Minimum number of pixels that must differ from the crop's own corner by > 90. */
        private const val REQUIRED_CONTRAST_PIXELS = 4

        /**
         * Caller must have just established its original strong inGrid(pair) proof.
         *
         * The pair is accepted only when the validated sort-control region is real, contrast-bearing
         * chrome that is pixel-stable across both captures. No sort caption, heading, count or HUD
         * text is read, so neither the currently selected sort nor a caption OCR dropout can decide
         * whether the search continues.
         *
         * [observer] names the rejecting predicate; a throwing observer cannot alter the result.
         * Frame/scan binding and pixel/freshness thresholds stay strict. Only the dependency on
         * recognised heading/sort text is removed from an already-admitted list's visual binding.
         */
        fun capture(
            first: FrameSnapshot,
            second: FrameSnapshot,
            firstScan: BrawlerOcrScan,
            secondScan: BrawlerOcrScan,
            nowMs: Long,
            initialListConfirmed: Boolean = false,
            observer: CaptureObserver? = null,
        ): BrawlerListSearchSession? {
            fun reject(reason: Rejection): BrawlerListSearchSession? {
                if (observer != null) try { observer.rejected(reason) } catch (_: Throwable) { }
                return null
            }
            if (!initialListConfirmed) return reject(Rejection.NO_INITIAL_CONFIRMATION)
            if (!validFrame(first, nowMs)) return reject(Rejection.FIRST_FRAME_INVALID)
            if (!validFrame(second, nowMs)) return reject(Rejection.SECOND_FRAME_INVALID)
            if (first.width != second.width || first.height != second.height)
                return reject(Rejection.FRAME_SIZE_MISMATCH)
            if (second.capturedAtMs <= first.capturedAtMs)
                return reject(Rejection.CAPTURE_ORDER_INVALID)
            // The scans must belong to these exact captures; their text is not evidence here.
            if (!scanBound(firstScan, first)) return reject(Rejection.FIRST_SCAN_UNBOUND)
            if (!scanBound(secondScan, second)) return reject(Rejection.SECOND_SCAN_UNBOUND)
            // A supported list geometry is still required: no plan, no validated control region.
            if (BrawlerGridOcrRegions.plan(first.width, first.height) == null)
                return reject(Rejection.REGION_PLAN_UNAVAILABLE)
            val control = BrawlerGridContinuation.sortRegion(first.width, first.height)
            if (control.width <= 0 || control.height <= 0) return reject(Rejection.NO_CONFIRMED_CONTROL)
            if (control.left < 0 || control.top < 0 ||
                control.right > first.width || control.bottom > first.height)
                return reject(Rejection.NO_CONFIRMED_CONTROL)
            val box = LobbyMenuEntryGate.Bounds(control.left, control.top, control.right, control.bottom)
            val w = control.width
            val h = control.height
            val area = w.toLong() * h
            if (area < 4 || area > first.width.toLong() * first.height / 8)
                return reject(Rejection.PATCH_AREA_INVALID)
            if (!LobbyMenuEntryGate.pixelsStill(first.width, first.height,
                    first.argb, second.argb, box)) return reject(Rejection.PATCH_PIXELS_CHANGED)
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                second.argb.copyInto(pixels, y * w,
                    (box.top + y) * second.width + box.left,
                    (box.top + y) * second.width + box.right)
            }
            // A recogniser double/blank crop is not a usable fixed-control fingerprint.
            val background = pixels[0]
            if (pixels.count { delta(background, it) > 90 } < REQUIRED_CONTRAST_PIXELS)
                return reject(Rejection.PATCH_CROP_FLAT)
            return BrawlerListSearchSession(first.width, first.height, first.capturedAtMs,
                listOf(Patch(box, pixels)))
        }

        private fun scanBound(scan: BrawlerOcrScan, frame: FrameSnapshot): Boolean =
            scan.width == frame.width && scan.height == frame.height &&
                scan.capturedAtMs == frame.capturedAtMs

        private fun validFrame(frame: FrameSnapshot, nowMs: Long): Boolean =
            nowMs >= 0 && frame.width > 0 && frame.height > 0 &&
                frame.width.toLong() * frame.height == frame.argb.size.toLong() &&
                frame.capturedAtMs > 0 && frame.capturedAtMs <= nowMs &&
                nowMs - frame.capturedAtMs <= MAX_AGE_MS

        private fun delta(a: Int, b: Int): Int =
            abs(((a ushr 16) and 255) - ((b ushr 16) and 255)) +
                abs(((a ushr 8) and 255) - ((b ushr 8) and 255)) + abs((a and 255) - (b and 255))

        /** Existing <=5% changed / mean RGB delta <=9, now over the compact retained control crop. */
        private fun patchStill(patch: Patch, frame: FrameSnapshot): Boolean {
            val box = patch.bounds; var i = 0; var changed = 0L; var difference = 0L
            val area = patch.pixels.size.toLong()
            for (y in box.top until box.bottom) for (x in box.left until box.right) {
                val d = delta(patch.pixels[i++], frame.argb[y * frame.width + x])
                difference += d
                if (d > 30) changed++
                if (changed * 20L > area || difference > area * 9L) return false
            }
            return true
        }
    }
}
