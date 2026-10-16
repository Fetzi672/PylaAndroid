package com.pyla.ai.engine

/**
 * Center of the visually recognized red attack control, in the pixel coordinates of the
 * capture that produced it.
 *
 * This is observation data only: it grants no input authority and carries no history. Callers
 * must re-observe a fresh, consistent reading before dispatching anything.
 */
data class AttackButtonPoint(val x: Float, val y: Float, val radius: Float)

/**
 * Production pixel-geometry recognizer for the circular red attack control.
 *
 * The recognition rule is pure and OpenCV-free so it can be exercised on real capture frames in
 * plain JVM tests through [AttackButtonPixels] (the [AttackButtonDetector] bridge is the only
 * part that touches a `Mat`). This keeps the tested decision byte-for-byte identical to the
 * decision the product runs.
 *
 * The control is identified by structure, never by a remembered screen position:
 *  - it is a compact, roughly circular ring of strongly saturated pure red,
 *  - the ring encloses a non-red core (the weapon icon and/or the pale touch indicator), so the
 *    exact center pixel is intentionally *not* required to be red,
 *  - it is neither an elongated red world bar (enemy health bars) nor a solid red rectangle
 *    (plain red boxes / ambiguous panels) nor a solid red blob with a red center.
 *
 * Any frame yielding zero or more than one such control is treated as uncertainty and produces
 * `null`; there is deliberately no fixed-coordinate fallback. The bounds are expressed as
 * fractions of the capture height, so a rescaled or repositioned control is still recognized.
 */
object AttackButtonDetector {

    /**
     * Recognize the attack control in a 3-channel RGB [org.opencv.core.Mat] whose pixel data is
     * continuous `RGBRGB...`. Coordinates in the returned point are capture pixels.
     *
     * Returns `null` when the image is empty, not 3-channel, or the control is missing or
     * ambiguous.
     */
    fun detect(image: org.opencv.core.Mat): AttackButtonPoint? {
        if (image.empty() || image.channels() != 3 || image.depth() != org.opencv.core.CvType.CV_8U) return null
        val width = image.cols()
        val height = image.rows()
        if (width <= 0 || height <= 0 || width.toLong() * height > 16_777_216L) return null
        val bytes = ByteArray(width * height * 3)
        try {
            if (image.get(0, 0, bytes) != bytes.size) return null
        } catch (_: Exception) {
            return null
        }
        // Mat.get on a continuous 3-channel Mat yields row-major interleaved RGB bytes, which is
        // exactly the layout AttackButtonPixels expects.
        return AttackButtonPixels.detect(width, height, bytes)
    }
}
