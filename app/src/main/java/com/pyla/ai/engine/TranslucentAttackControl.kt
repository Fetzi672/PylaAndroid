package com.pyla.ai.engine


import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pure, OpenCV-free recognizer for the RESTING/highlighted attack control: the same on-screen
 * control as [AttackButtonPixels], but composited translucently over the world before the press
 * tint makes it opaque red.
 *
 * ### Why a separate path is needed (measured, not assumed)
 * The opaque rule requires R>=120, G<=45, B<=55. On genuine resting frames the control's ring
 * samples around (129,104,112) and (153,64,68) — alpha composition over blue/teal scenery raises
 * G and B and lowers the absolute channel differences, so the opaque mask retains ~0% and ~0.38%
 * of the control's own annulus. Relaxing the red thresholds cannot fix this without admitting
 * world objects (the frames that motivate this path also contain a 8500 HP crate, red barrels and
 * a red ground decal that all sit at moderate redness).
 *
 * ### The structural rule actually used
 * A translucent warm control is a compact disc of *relative* chromatic elevation whose maximum
 * sits in an ANNULUS, not at its centre:
 *  - the face is warm and moderately composited (`R - max(G,B)` in a modest band, never saturated
 *    red — saturated red is the opaque path's domain),
 *  - the rim is warmer than the face and much warmer than the immediately surrounding scenery
 *    (radial chromatic step, both directions measured in the same frame),
 *  - the elevated rim is present on most angles and in every quadrant (a sparse scatter of warm
 *    world fragments cannot alias into a circle),
 *  - objects that are bright at their centre and fade outwards (barrels, pickup glows, glowing
 *    crates, saturated ground decals) fail the annular-maximum test,
 *  - a uniformly warm, rim-less disc fails the step test; a warm square fails the disc/size test.
 *
 * Every radius bound is a fraction of the capture height, so the same control is found after being
 * repositioned or rescaled, and the reported radius has the same meaning as the opaque path's
 * fitted ring radius: the outer edge of the observed control. That keeps [AttackButtonEvidenceGate]
 * radius/center continuity across the resting -> pressed transition without touching the gate.
 *
 * ### Ambiguity, budget and honesty
 * Zero accepted controls is "not seen"; more than one is ambiguity and the caller must deny. The
 * search is bounded on components and on refinements; hitting either bound reports
 * `exhausted = true`, which is unknown evidence and must never be read as a hit. Phase costs and
 * seed counts are reported through [AttackRecognitionMetrics] only when those phases really ran.
 */
internal object TranslucentAttackControl {

    data class Result(
        val points: List<AttackButtonPoint>,
        val exhausted: Boolean = false,
        val coarseCandidates: Int = 0,
        val refinementSeeds: Int = 0,
    )

    // --- warm (composited, non-saturated) chromatic category ---
    /** Minimal `R - max(G,B)` for a pixel to join the warm plane. */
    private const val WARM_FLOOR = 10
    /** Below this `R` the pixel is a dark outline/shadow, not a warm composite. */
    private const val WARM_MIN_R = 60
    /** Upper bound on the observed rim excursion: above this the body is saturated red. */
    private const val RIM_MAX = 95.0
    /** The rim must be warmer than the face by this many units (annular maximum). */
    private const val FACE_TO_RIM = 6.0
    /** The rim must be warmer than the immediately surrounding scenery by this many units. */
    private const val RIM_TO_OUTSIDE = 18.0
    /** The warm face must carry at least this fraction of the rim's elevation. */
    // Genuine resting faces retain >=0.66 of the rim excursion. The historical enemy/hat
    // distractor retains only ~0.46: require a filled composited face, not a hollow world glow.
    private const val FACE_RATIO = 0.60

    // --- shape, relative to capture height ---
    private const val MIN_SHORT_FRACTION = 0.050
    private const val MAX_LONG_FRACTION = 0.30
    private const val MAX_ASPECT = 1.35
    private const val MIN_DISC_RATIO = 0.40
    private const val MAX_DISC_RATIO = 1.15
    private const val MIN_RADIUS_FRACTION = 0.045
    private const val MAX_RADIUS_FRACTION = 0.125

    // --- angular distribution of the rim ---
    private const val ANGLES = 72
    private const val MIN_SUPPORT = 0.60
    private const val MIN_QUADRANT = 0.45
    /** Per-ray rim step over both neighbours: strict enough to reject texture, tolerant of JPEG. */
    private const val RAY_STEP = 6.0

    // --- bounded work ---
    internal const val MAX_COMPONENTS = 20_000
    internal const val MAX_REFINEMENTS = 64

    private val COS = DoubleArray(ANGLES) { cos(it * 2.0 * Math.PI / ANGLES) }
    private val SIN = DoubleArray(ANGLES) { sin(it * 2.0 * Math.PI / ANGLES) }


    /** Same-control predicate used for de-duplication and for mixed opaque/translucent agreement. */
    fun sameControl(a: AttackButtonPoint, b: AttackButtonPoint): Boolean =
        hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()) <= max(2.0, min(a.radius, b.radius) * 0.20) &&
            max(a.radius, b.radius) <= min(a.radius, b.radius) * 1.25f

    /**
     * Find resting/highlighted controls in an interleaved RGB buffer.
     *
     * [scratch] is the caller's thread-confined buffer set; both planes are written for every index
     * this call reads, so no state can leak between frames. [metrics] is an output sink only.
     */
    fun find(
        width: Int,
        height: Int,
        rgb: ByteArray,
        scratch: AttackButtonPixels.Scratch,
        metrics: AttackRecognitionMetrics?,
    ): Result {
        val pixelCount = width * height
        val warm = scratch.red
        val labels = scratch.labels
        val stack = scratch.stack
        java.util.Arrays.fill(labels, 0, pixelCount, 0)

        val coarseStart = System.nanoTime()
        var i = 0
        var p = 0

        while (p < pixelCount) {
            val r = rgb[i].toInt() and 0xFF
            val g = rgb[i + 1].toInt() and 0xFF
            val b = rgb[i + 2].toInt() and 0xFF
            val d = r - max(g, b)
            val warmHere = d >= WARM_FLOOR && r >= WARM_MIN_R && !AttackButtonPixels.isOpaqueRed(r, g, b)
            warm[p] = warmHere

            i += 3
            p++
        }

        // Components are labelled exactly like the opaque path (4-connectivity), then filtered by
        // the scale/disc bounds before any angular work is done.
        val candidates = ArrayList<Seed>(4)
        var exhausted = false
        var nextLabel = 0
        for (start in 0 until pixelCount) {
            if (!warm[start] || labels[start] != 0) continue
            nextLabel += 1
            if (nextLabel > MAX_COMPONENTS) { exhausted = true; break }
            val label = nextLabel
            var sp = 0
            stack[sp++] = start
            labels[start] = label
            var x0 = width
            var y0 = height
            var x1 = -1
            var y1 = -1
            var area = 0
            var sx = 0L
            var sy = 0L
            while (sp > 0) {
                val cur = stack[--sp]
                val cx = cur % width
                val cy = cur / width
                area++
                sx += cx
                sy += cy
                if (cx < x0) x0 = cx
                if (cx > x1) x1 = cx
                if (cy < y0) y0 = cy
                if (cy > y1) y1 = cy
                if (cx > 0) { val n = cur - 1; if (warm[n] && labels[n] == 0) { labels[n] = label; stack[sp++] = n } }
                if (cx < width - 1) { val n = cur + 1; if (warm[n] && labels[n] == 0) { labels[n] = label; stack[sp++] = n } }
                if (cy > 0) { val n = cur - width; if (warm[n] && labels[n] == 0) { labels[n] = label; stack[sp++] = n } }
                if (cy < height - 1) { val n = cur + width; if (warm[n] && labels[n] == 0) { labels[n] = label; stack[sp++] = n } }
            }
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            if (min(bw, bh) < MIN_SHORT_FRACTION * height) continue
            if (max(bw, bh) > MAX_LONG_FRACTION * height) continue
            if (max(bw, bh).toDouble() / min(bw, bh) > MAX_ASPECT) continue
            val boxRadius = (bw + bh) / 4.0
            if (boxRadius < MIN_RADIUS_FRACTION * height) continue
            val disc = area / (Math.PI * boxRadius * boxRadius)
            if (disc < MIN_DISC_RATIO || disc > MAX_DISC_RATIO) continue
            candidates.add(Seed(sx.toDouble() / area, sy.toDouble() / area, area))
            if (candidates.size > MAX_REFINEMENTS) { exhausted = true; break }
        }
        metrics?.coarseCandidates = candidates.size
        val coarseEnd = System.nanoTime()

        val refineStart = coarseEnd
        val found = ArrayList<AttackButtonPoint>(2)
        var refined = 0
        if (!exhausted) {
            for (seed in candidates) {
                refined++
                val point = verify(width, height, rgb, warm, seed) ?: continue
                if (found.none { sameControl(it, point) }) found.add(point)
                if (found.size > 1) break // ambiguity: the caller must deny
            }
        }
        val refineEnd = System.nanoTime()

        metrics?.let {
            it.coarseNanos = coarseEnd - coarseStart
            it.refineNanos = refineEnd - refineStart
            it.refinementSeeds = refined
            it.exhausted = exhausted
        }
        return Result(found, exhausted, candidates.size, refined)
    }

    private data class Seed(val x: Double, val y: Double, val area: Int)

    /**
     * Verify one warm component: search a small window and radius band for the annular chromatic
     * step, then require the rim to be distributed over angles and quadrants.
     */
    private fun verify(
        width: Int,
        height: Int,
        rgb: ByteArray,
        warm: BooleanArray,
        seed: Seed,
    ): AttackButtonPoint? {
        val radLo = max(6, (MIN_RADIUS_FRACTION * height).toInt())
        val radHi = (MAX_RADIUS_FRACTION * height).toInt()
        val radStep = max(2, height / 250)
        val span = max(2, (0.008 * height).toInt())
        val spanStep = max(1, span / 2)
        var bestScore = 0.0
        var bestX = 0.0
        var bestY = 0.0
        var bestR = 0

        var dy = -span
        while (dy <= span) {
            var dx = -span
            while (dx <= span) {
                val cx = seed.x + dx
                val cy = seed.y + dy
                var r = radLo
                while (r <= radHi) {
                    if (cx - r < 0 || cy - r < 0 || cx + r >= width || cy + r >= height) { r += radStep; continue }
                    val face = meanRing(cx, cy, r * 0.25, rgb, width, height)
                    val rim = meanRing(cx, cy, r * 0.80, rgb, width, height)
                    val outside = meanRing(cx, cy, r * 1.30, rgb, width, height)
                    if (rim < 12.0 || rim > RIM_MAX) { r += radStep; continue }
                    if (rim - face < FACE_TO_RIM) { r += radStep; continue }
                    if (rim - outside < RIM_TO_OUTSIDE) { r += radStep; continue }
                    if (face < FACE_RATIO * rim) { r += radStep; continue }
                    val support = distributedSupport(cx, cy, r, rgb, width, height)
                    if (support < 0) { r += radStep; continue }
                    val score = rim - outside
                    if (score > bestScore) { bestScore = score; bestX = cx; bestY = cy; bestR = r }
                    r += radStep
                }
                dx += spanStep
            }
            dy += spanStep
        }
        if (bestScore <= 0.0) return null

        // Never return an unverified post-fit clamp. Only this exact centre passed all dense rays.
        return AttackButtonPoint(bestX.toFloat(), bestY.toFloat(), bestR.toFloat())
    }

    /** Dense angular mean; use the same 72 directions as the final distributed evidence. */
    private fun meanRing(
        cx: Double,
        cy: Double,
        radius: Double,
        rgb: ByteArray,
        width: Int,
        height: Int,

    ): Double {
        var sum = 0.0
        for (k in 0 until ANGLES) {
            sum += chromaticAt(rgb, width, height, cx + radius * COS[k], cy + radius * SIN[k])
        }
        return sum / ANGLES
    }

    /**
     * Fraction of the dense angular samples whose rim reading exceeds both the face side and the
     * outside side, or -1 when any quadrant is under-supported. Density and quadrant balance are
     * what makes a sparse scatter of warm world fragments unable to alias into a circle.
     */
    private fun distributedSupport(
        cx: Double,
        cy: Double,
        radius: Int,
        rgb: ByteArray,
        width: Int,
        height: Int,
    ): Double {
        var supported = 0
        var q0 = 0
        var q1 = 0
        var q2 = 0
        var q3 = 0
        val rimR = radius * 0.80
        val faceR = radius * 0.55
        val outR = radius * 1.30
        for (k in 0 until ANGLES) {
            val c = COS[k]
            val s = SIN[k]
            val rim = chromaticAt(rgb, width, height, cx + rimR * c, cy + rimR * s)
            val face = chromaticAt(rgb, width, height, cx + faceR * c, cy + faceR * s)
            val out = chromaticAt(rgb, width, height, cx + outR * c, cy + outR * s)
            if (rim - max(face, out) > RAY_STEP) {
                supported++
                when (k / 18) {
                    0 -> q0++
                    1 -> q1++
                    2 -> q2++
                    else -> q3++
                }
            }
        }
        val quarter = ANGLES / 4
        val minQuadrant = min(min(q0, q1), min(q2, q3))
        if (minQuadrant < MIN_QUADRANT * quarter) return -1.0
        val fraction = supported.toDouble() / ANGLES
        if (fraction < MIN_SUPPORT) return -1.0
        return fraction
    }

    private fun chromaticAt(rgb: ByteArray, width: Int, height: Int, x: Double, y: Double): Double {
        val xi = x.roundToInt().coerceIn(0, width - 1)
        val yi = y.roundToInt().coerceIn(0, height - 1)
        val i = 3 * (yi * width + xi)
        val r = rgb[i].toInt() and 0xFF
        val g = rgb[i + 1].toInt() and 0xFF
        val b = rgb[i + 2].toInt() and 0xFF
        return (r - max(g, b)).toDouble()
    }


}
