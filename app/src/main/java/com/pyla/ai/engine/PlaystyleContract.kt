package com.pyla.ai.engine

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Shared playstyle context / input contract for the Android port.
 *
 * ## Coordinate convention (read this before touching movement code)
 *
 * There are exactly three spaces, and every value crossing a boundary must be converted here.
 *
 * 1. **Reference space** — the PC build's fixed `1920x1080` canvas. The upstream interpreter gives
 *    strategies `width_ratio`/`height_ratio` and a joystick radius expressed in this space
 *    (`utils.JOYSTICK_RADIUS`), so a strategy vector is a direction plus a magnitude of at most one
 *    joystick radius *in reference pixels*, never in device pixels.
 * 2. **Capture space** — the frames the Android capture pipeline produces (`frame.cols()` x
 *    `frame.rows()`). All detection boxes (`player_data`, `enemy_data`, `walls`, ...) and every
 *    coordinate handed to `InputService` (`joystickMove`, `tapAt`, ...) live here. Y grows
 *    downwards, so `-y` is UP and `+x` is RIGHT in both spaces.
 * 3. **Screen space** — the physical display; `InputCoordinates.toScreenX/Y` maps capture -> screen.
 *    Nothing outside `InputCoordinates`/`InputService` may apply that mapping, and no strategy value
 *    is ever expressed in screen space.
 *
 * The bridge between 1 and 2 is [Geometry]: `width_ratio = captureWidth / 1920`,
 * `height_ratio = captureHeight / 1080`. A reference-space offset `(u * radius)` therefore becomes
 * the capture-space offset `(u.x * radius * width_ratio, u.y * radius * height_ratio)`. That keeps
 * the joystick throw a constant *fraction of the rendered canvas* on every aspect ratio, which is
 * what the game itself does — a fixed pixel offset would silently shrink the throw on high
 * resolution captures and skew diagonals on non-16:9 ones.
 *
 * Everything in this file is pure: no Android, no I/O, no mutable state, so the contract can be
 * unit tested without a device.
 */
object PlaystyleContract {

    /** Reference canvas the PC build and `InputCoordinates` ratios are defined against. */
    const val BASE_WIDTH: Int = 1920
    const val BASE_HEIGHT: Int = 1080

    /**
     * Upstream PC `utils.JOYSTICK_RADIUS`. Documentation only: the *applied* radius is always passed
     * explicitly so the Android build cannot silently diverge from the value strategies receive in
     * their context (`PylaUtils.JOYSTICK_RADIUS`).
     */
    const val REFERENCE_JOYSTICK_RADIUS: Double = 100.0

    /** Capture-space geometry, validated once so every derived ratio is finite and positive. */
    data class Geometry(val width: Int, val height: Int) {
        init {
            require(width > 0 && height > 0) { "PLAYSTYLE_GEOMETRY_INVALID" }
        }

        val widthRatio: Double get() = width.toDouble() / BASE_WIDTH
        val heightRatio: Double get() = height.toDouble() / BASE_HEIGHT
        val scaleFactor: Double get() = minOf(widthRatio, heightRatio)
    }

    /** An offset in whichever space the caller documents: reference for context, capture for input. */
    data class Vector(val x: Double, val y: Double) {
        val isFinite: Boolean get() = x.isFinite() && y.isFinite()
        val length: Double get() = hypot(x, y)
        val isZero: Boolean get() = isFinite && length == 0.0
    }

    /** Validated geometry, or `null` when the capture size cannot define one. */
    fun geometry(width: Int, height: Int): Geometry? =
        if (width > 0 && height > 0) Geometry(width, height) else null

    /**
     * `normalize_move` exposed to strategies: scale `(x, y)` to exactly [radius], PC semantics.
     *
     * Returns `(0, 0)` for a non-finite, zero-length or non-positive-radius input instead of
     * throwing or returning NaN. Strategies treat `(0, 0)` as "no intent", so a bad vector can only
     * ever produce a still decision, never a garbage joystick throw.
     */
    fun normalizeMove(x: Double, y: Double, radius: Double): Vector {
        if (!x.isFinite() || !y.isFinite() || !radius.isFinite() || radius <= 0.0) return Vector(0.0, 0.0)
        val length = hypot(x, y)
        if (!length.isFinite() || length <= 0.0) return Vector(0.0, 0.0)
        return Vector(x / length * radius, y / length * radius)
    }

    /**
     * Convert a strategy movement vector (reference space) into the capture-space offset handed to
     * `InputService.joystickMove`.
     *
     * Direction is preserved exactly; magnitude is capped at [radius] reference pixels but never
     * amplified, so a strategy that asks for less than a full throw still gets less. `null` means
     * "fail closed": the caller must release the joystick rather than move somewhere unintended.
     *
     * When [centerX]/[centerY] are supplied the offset is additionally shortened (uniformly, so the
     * direction survives) to keep the resulting touch point inside the capture rectangle.
     */
    fun toJoystickDelta(
        x: Double,
        y: Double,
        geometry: Geometry,
        radius: Double,
        centerX: Double = Double.NaN,
        centerY: Double = Double.NaN,
    ): Vector? {
        if (!x.isFinite() || !y.isFinite()) return null
        if (!radius.isFinite() || radius <= 0.0) return null
        val length = hypot(x, y)
        if (!length.isFinite()) return null
        if (length == 0.0) return Vector(0.0, 0.0)

        val magnitude = minOf(length, radius)
        var dx = x / length * magnitude * geometry.widthRatio
        var dy = y / length * magnitude * geometry.heightRatio

        if (centerX.isFinite() && centerY.isFinite()) {
            // One shared factor keeps the screen-space direction intact; per-axis clamping would
            // rotate the throw whenever only one axis reached an edge.
            val uniform = minOf(1.0, axisFit(dx, centerX, geometry.width), axisFit(dy, centerY, geometry.height))
            dx *= uniform
            dy *= uniform
        }
        if (!dx.isFinite() || !dy.isFinite()) return null
        return Vector(dx, dy)
    }

    /**
     * Largest multiplier in `[0, 1]` that keeps `center + delta` inside `[0, size - 1]`.
     * A center that is already outside the capture yields `0`, i.e. no movement at all.
     */
    private fun axisFit(delta: Double, center: Double, size: Int): Double {
        if (delta == 0.0) return 1.0
        val limit = if (delta > 0.0) (size - 1) - center else center
        if (!limit.isFinite() || limit <= 0.0) return 0.0
        return minOf(1.0, abs(limit) / abs(delta))
    }
}
