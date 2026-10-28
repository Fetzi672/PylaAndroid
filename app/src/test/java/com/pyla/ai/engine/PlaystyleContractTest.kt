package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Contract tests for the shared playstyle context/input geometry.
 *
 * These pin the three properties the port has to keep in step with the PC build:
 *  * a strategy vector is reference-space (1920x1080) and `normalize_move` returns exactly one
 *    joystick radius,
 *  * `WindowController.moveGuarded` converts that vector to capture space with `width_ratio` /
 *    `height_ratio`, so a full throw is the same fraction of the canvas on every aspect ratio,
 *  * a bad (non-finite / degenerate) vector never produces a movement — the caller releases.
 *
 * Pure JVM, no Android. Authority/STOP behaviour (`moveGuarded` releasing on a denied guard,
 * `InputService`'s sticky per-channel `Authority` and the unchanged renewal path) is not touched by
 * this contract and stays covered by the existing engine/input tests.
 */
class PlaystyleContractTest {

    private fun assertClose(expected: Double, actual: Double, eps: Double = 1e-9) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) <= eps)
    }

    private fun assertVector(expectedX: Double, expectedY: Double, actual: PlaystyleContract.Vector?) {
        assertNotNull("vector must not be null", actual)
        assertClose(expectedX, actual!!.x)
        assertClose(expectedY, actual.y)
    }

    // ---------- reference-space normalization (`normalize_move` context function) ----------

    @Test
    fun normalizeMoveProducesOneRadiusForCardinalDirections() {
        val radius = 75.0
        assertVector(0.0, -radius, PlaystyleContract.normalizeMove(0.0, -1.0, radius)) // UP
        assertVector(radius, 0.0, PlaystyleContract.normalizeMove(5.0, 0.0, radius))    // RIGHT
        assertVector(0.0, radius, PlaystyleContract.normalizeMove(0.0, 3.0, radius))    // DOWN
        assertVector(-radius, 0.0, PlaystyleContract.normalizeMove(-2.0, 0.0, radius))  // LEFT
    }

    @Test
    fun normalizeMoveKeepsDiagonalDirectionAtOneRadius() {
        val radius = 75.0
        val v = PlaystyleContract.normalizeMove(100.0, -100.0, radius)!!
        val expected = radius / kotlin.math.sqrt(2.0)
        assertClose(expected, v.x)
        assertClose(-expected, v.y)
        assertClose(radius, v.length)
    }

    @Test
    fun normalizeMoveRejectsDegenerateInputWithAStillVector() {
        assertVector(0.0, 0.0, PlaystyleContract.normalizeMove(0.0, 0.0, 75.0))
        assertVector(0.0, 0.0, PlaystyleContract.normalizeMove(Double.NaN, 1.0, 75.0))
        assertVector(0.0, 0.0, PlaystyleContract.normalizeMove(1.0, Double.POSITIVE_INFINITY, 75.0))
        assertVector(0.0, 0.0, PlaystyleContract.normalizeMove(1.0, 1.0, 0.0))
        assertVector(0.0, 0.0, PlaystyleContract.normalizeMove(1.0, 1.0, -5.0))
        assertVector(0.0, 0.0, PlaystyleContract.normalizeMove(1.0, 1.0, Double.NaN))
    }

    // ---------- geometry ----------

    @Test
    fun geometryMatchesPcReferenceResolution() {
        val g = PlaystyleContract.geometry(1920, 1080)!!
        assertClose(1.0, g.widthRatio)
        assertClose(1.0, g.heightRatio)
        assertClose(1.0, g.scaleFactor)
    }

    @Test
    fun geometryScalesPerAxisAndRejectsUnusableSizes() {
        val wide = PlaystyleContract.geometry(2400, 1080)!!
        assertClose(1.25, wide.widthRatio)
        assertClose(1.0, wide.heightRatio)
        assertClose(1.0, wide.scaleFactor)

        val tall = PlaystyleContract.geometry(1280, 1024)!!
        assertClose(1280.0 / 1920.0, tall.widthRatio)
        assertClose(1024.0 / 1080.0, tall.heightRatio)
        assertClose(1280.0 / 1920.0, tall.scaleFactor)

        assertNull(PlaystyleContract.geometry(0, 1080))
        assertNull(PlaystyleContract.geometry(1920, 0))
        assertNull(PlaystyleContract.geometry(-1, -1))
    }

    // ---------- reference -> capture conversion ----------

    private fun delta(x: Double, y: Double, w: Int = 1920, h: Int = 1080, radius: Double = 75.0) =
        PlaystyleContract.toJoystickDelta(x, y, PlaystyleContract.geometry(w, h)!!, radius)

    @Test
    fun fullThrowIsOneRadiusAtReferenceResolution() {
        assertVector(0.0, -75.0, delta(0.0, -75.0))  // UP
        assertVector(75.0, 0.0, delta(75.0, 0.0))    // RIGHT
        assertClose(75.0, delta(0.0, -75.0)!!.length)
    }

    @Test
    fun mixedAspectScalesEachAxisButKeepsDirection() {
        // 20:9 capture: a horizontal throw is 1.25x, a vertical throw is unchanged.
        assertVector(93.75, 0.0, delta(75.0, 0.0, 2400, 1080))
        assertVector(0.0, -75.0, delta(0.0, -75.0, 2400, 1080))

        val diag = delta(75.0, -75.0, 2400, 1080)!!
        val unit = 75.0 / kotlin.math.sqrt(2.0)
        assertClose(unit * 1.25, diag.x)
        assertClose(-unit, diag.y)
        // Same reference-space direction before and after conversion.
        assertClose(-diag.x / 1.25, diag.y * 1.0)
    }

    @Test
    fun magnitudeIsBoundedButNeverAmplified() {
        assertVector(75.0, 0.0, delta(10_000.0, 0.0)) // capped at one radius
        assertVector(10.0, 0.0, delta(10.0, 0.0))     // a half-speed request stays half speed
        assertClose(75.0, delta(75.0, 75.0)!!.length) // diagonal length is still one radius
    }

    @Test
    fun zeroVectorIsAStillAndBadVectorFailsClosed() {
        assertVector(0.0, 0.0, delta(0.0, 0.0))
        assertNull(delta(Double.NaN, 1.0))
        assertNull(delta(1.0, Double.NEGATIVE_INFINITY))
        assertNull(delta(5.0, 5.0, radius = 0.0))
        assertNull(delta(5.0, 5.0, radius = Double.NaN))
    }

    @Test
    fun throwIsShortenedUniformlyToStayInsideTheCapture() {
        // Joystick centre 19 px from the right edge: the whole vector shrinks, direction survives.
        val g = PlaystyleContract.geometry(1920, 1080)!!
        val v = PlaystyleContract.toJoystickDelta(
            75.0, -75.0, g, 75.0, centerX = 1900.0, centerY = 900.0,
        )!!
        assertTrue("must stay inside the right edge", 1900.0 + v.x <= 1919.0 + 1e-9)
        val k = v.x / (75.0 / kotlin.math.sqrt(2.0))
        assertClose(v.y, -(75.0 / kotlin.math.sqrt(2.0)) * k)
        assertTrue(k > 0.0 && k < 1.0)
    }

    @Test
    fun centreOutsideTheCaptureProducesNoMovement() {
        val g = PlaystyleContract.geometry(1920, 1080)!!
        val v = PlaystyleContract.toJoystickDelta(75.0, 0.0, g, 75.0, centerX = 5000.0, centerY = 500.0)!!
        assertClose(0.0, v.x)
        assertClose(0.0, v.y)
    }

    @Test
    fun screenFractionOfAFullThrowIsResolutionIndependent() {
        // The whole point of the ratio scaling: throw / capture size must be constant.
        val base = delta(75.0, -75.0, 1920, 1080)!!
        val big = delta(75.0, -75.0, 2880, 1620)!!
        assertClose(base.x / 1920.0, big.x / 2880.0)
        assertClose(base.y / 1080.0, big.y / 1620.0)
        // And the capture-space magnitude grows with the geometry instead of staying at a fixed 75.
        assertTrue(hypot(big.x, big.y) > hypot(base.x, base.y))
    }
}
