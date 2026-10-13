package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Focused, pure JVM tests for the DETAIL SELECT fallback.
 *
 * No Android and no OpenCV: the only product types touched are [BrawlerDetailSelect],
 * [BrawlerSelectionGate], [BrawlerOcrHit]/[BrawlerOcrScan] and [FrameSnapshot].
 *
 * Real delivered frames are decoded with the JDK's own [ImageIO] by the private harness below and
 * converted to the capture buffer's true ABGR packing exactly once - red in the LOW byte, blue in
 * the high channel byte, exactly as [PylaUtils.frameToBitmap] has to swap. Cases derived from those
 * frames assert the accepted box, so a channel swap cannot pass unnoticed.
 *
 * Absent fixture images make the corresponding cases skip rather than silently pass; the synthetic
 * cases below never touch the filesystem.
 */
class BrawlerDetailSelectTest {

    // ---------------------------------------------------------------- positive: real frames

    /** The user's no-overlay detail capture: SELECT at (76,506)-(322,566), 6 glyphs. */
    @Test
    fun locatesSelectGlyphsInDeliveredNoOverlayFrame() {
        val frame = harness()?.frame("img_f43ed6f45078.jpg") ?: return skip()
        val match = BrawlerDetailSelect.locate(frame.width, frame.height, frame.argb)
        assertNotNull("the delivered SELECT button must be located", match)
        assertEquals(75, match!!.hit.left)
        assertEquals(507, match.hit.top)
        assertEquals(322, match.hit.right)
        assertEquals(566, match.hit.bottom)
        // The hit centre is derived from the observed box, never a fixed tap coordinate.
        assertEquals(198, match.hit.centerX)
        assertEquals(536, match.hit.centerY)
        assertTrue("glyph identity, not a colour blob", match.similarity >= BrawlerDetailSelect.MIN_SIMILARITY)
    }

    /** The overlay capture shows the same button; the overlay text elsewhere is never a candidate. */
    @Test
    fun locatesSelectGlyphsInDeliveredOverlayFrame() {
        val frame = harness()?.frame("img_cc38811eec0d.jpg") ?: return skip()
        val match = BrawlerDetailSelect.locate(frame.width, frame.height, frame.argb)
        assertNotNull("the overlay capture still shows SELECT", match)
        assertEquals(75, match!!.hit.left)
        assertEquals(507, match.hit.top)
        assertEquals(322, match.hit.right)
        assertEquals(566, match.hit.bottom)
    }

    /** The manual-video reference frame: same button, one pixel lower and a shorter cap height. */
    @Test
    fun locatesSelectGlyphsInManualVideoFrame() {
        val path = "frame-03.jpg"
        val frame = harness()?.frame(path) ?: return skip()
        val match = BrawlerDetailSelect.locate(frame.width, frame.height, frame.argb)
        assertNotNull("the video reference frame shows SELECT", match)
        assertEquals(76, match!!.hit.left)
        assertEquals(322, match.hit.right)
        assertTrue("top within a pixel or two of the measured 508", match.hit.top in 506..510)
        assertTrue("bottom within a pixel or two of the measured 567", match.hit.bottom in 564..568)
        // A moving character must not matter: identity comes from the static button's glyphs.
        assertTrue(match.similarity >= BrawlerDetailSelect.MIN_SIMILARITY)
    }

    /** Two distinct fresh captures of the same button confirm; the second box is returned. */
    @Test
    fun confirmsButtonAcrossTwoFreshDeliveredFrames() {
        val loader = harness() ?: return skip()
        val first = loader.frame("img_f43ed6f45078.jpg") ?: return skip()
        val second = loader.frame(
            "img_cc38811eec0d.jpg",
        ) ?: return skip()
        val hit = BrawlerDetailSelect.confirmPair(
            first.copy(capturedAtMs = 1_000L), second.copy(capturedAtMs = 1_300L), 1_500L,
        )
        assertNotNull("two fresh captures of the same button confirm", hit)
        assertEquals(75, hit!!.left)
        assertEquals(322, hit.right)
    }

    /** Captures at the supported geometry scales all yield the same word. */
    @Test
    fun acceptsSupportedScaleVariation() {
        val loader = harness() ?: return skip()
        val source = loader.frame("img_f43ed6f45078.jpg") ?: return skip()
        for (scale in listOf(0.80, 0.90, 1.10, 1.35)) {
            val scaled = loader.resample(source, scale)
            val match = BrawlerDetailSelect.locate(scaled.width, scaled.height, scaled.argb)
            assertNotNull("scale $scale must still locate SELECT", match)
            val expectedLeft = Math.round(76 * scale).toInt()
            val expectedTop = Math.round(506 * scale).toInt()
            assertTrue(
                "scale $scale left ${match!!.hit.left} near $expectedLeft",
                kotlin.math.abs(match.hit.left - expectedLeft) <= 6,
            )
            assertTrue(
                "scale $scale top ${match.hit.top} near $expectedTop",
                kotlin.math.abs(match.hit.top - expectedTop) <= 8,
            )
        }
    }

    // ---------------------------------------------------------------- negative: real frames

    /** The button painted over with the same yellow: no glyphs, so no identity. */
    @Test
    fun rejectsButtonWhoseGlyphsAreErased() {
        val loader = harness() ?: return skip()
        val frame = loader.frame("img_f43ed6f45078.jpg") ?: return skip()
        val erased = loader.eraseRect(frame, 76, 500, 322, 572, loader.yellow())
        assertNull("a blank yellow plate is not SELECT", BrawlerDetailSelect.locate(erased.width, erased.height, erased.argb))
    }

    /** The button removed entirely: nothing in the band qualifies. */
    @Test
    fun rejectsMissingButton() {
        val loader = harness() ?: return skip()
        val frame = loader.frame("img_f43ed6f45078.jpg") ?: return skip()
        val missing = loader.eraseRect(frame, 60, 490, 340, 585, loader.background())
        assertNull("an absent button must not be located", BrawlerDetailSelect.locate(missing.width, missing.height, missing.argb))
    }

    /** A different word in the same yellow plate: geometry is identical, identity is not. */
    @Test
    fun rejectsDifferentWordInSameButtonGeometry() {
        val loader = harness() ?: return skip()
        val frame = loader.frame("img_f43ed6f45078.jpg") ?: return skip()
        val other = loader.wordPlate(frame, 76, 506, 246, 60, "TRY", 3)
        assertNull(
            "TRY in SELECT's geometry must never be accepted as SELECT",
            BrawlerDetailSelect.locate(other.width, other.height, other.argb),
        )
    }

    /** Same yellow plate, different letters: only the 2D mask can reject this, not a column profile. */
    @Test
    fun rejectsChangedGlyphs() {
        val loader = harness() ?: return skip()
        val frame = loader.frame("img_f43ed6f45078.jpg") ?: return skip()
        val scrambled = loader.scrambleGlyphs(frame, 76, 506, 246, 60)
        assertNull(
            "changed glyph pixels must fail the mask identity",
            BrawlerDetailSelect.locate(scrambled.width, scrambled.height, scrambled.argb),
        )
    }

    /** A second, equally valid SELECT plate: ambiguous, so no candidate may be chosen. */
    @Test
    fun rejectsDuplicatedCandidate() {
        val loader = harness() ?: return skip()
        val frame = loader.frame("img_f43ed6f45078.jpg") ?: return skip()
        val duplicated = loader.eraseRect(frame, 530, 490, 810, 585, loader.background())
        val copy = loader.blit(frame, duplicated, 70, 500, 330, 575, 540, 500)
        assertNull("two qualifying buttons are ambiguous", BrawlerDetailSelect.locate(copy.width, copy.height, copy.argb))
    }

    /** The same plate in the OTHER channel order is not this capture's encoding. */
    @Test
    fun rejectsWrongChannelOrder() {
        val loader = harness() ?: return skip()
        val frame = loader.frame("img_f43ed6f45078.jpg") ?: return skip()
        val swapped = loader.swapRedBlue(frame)
        assertNull("an RGBA-ordered buffer is not an ABGR capture", BrawlerDetailSelect.locate(swapped.width, swapped.height, swapped.argb))
    }

    // ---------------------------------------------------------------- negative: pair gating

    @Test
    fun rejectsStaleAndRegressedAndFutureCaptures() {
        val loader = harness() ?: return skip()
        val first = loader.frame("img_f43ed6f45078.jpg") ?: return skip()
        val second = loader.frame("img_cc38811eec0d.jpg") ?: return skip()
        val a = first.copy(capturedAtMs = 1_000L)
        val b = second.copy(capturedAtMs = 1_300L)

        assertNull("equal timestamps are a reused capture", BrawlerDetailSelect.confirmPair(a, b.copy(capturedAtMs = 1_000L), 1_500L))
        assertNull("regressed timestamps are not two fresh frames", BrawlerDetailSelect.confirmPair(b, a, 1_500L))
        assertNull("a capture from the future is invalid", BrawlerDetailSelect.confirmPair(a, b, 1_200L))
        assertNull("stale captures are invalid", BrawlerDetailSelect.confirmPair(a.copy(capturedAtMs = 1L), b.copy(capturedAtMs = 2L), 90_000L))
        assertNull("invalid frames are rejected", BrawlerDetailSelect.confirmPair(a, b.copy(width = 0), 1_500L))
    }

    /** The button present in one frame and gone in the other must never confirm. */
    @Test
    fun rejectsDisappearingButton() {
        val loader = harness() ?: return skip()
        val present = loader.frame("img_f43ed6f45078.jpg") ?: return skip()
        val gone = loader.eraseRect(present, 60, 490, 340, 585, loader.background())
        assertNull(
            "a button missing from the second frame must not confirm",
            BrawlerDetailSelect.confirmPair(
                present.copy(capturedAtMs = 1_000L), gone.copy(capturedAtMs = 1_300L), 1_500L,
            ),
        )
    }

    /** Different capture dimensions invalidate the pixel comparison outright. */
    @Test
    fun rejectsMismatchedCaptureDimensions() {
        val loader = harness() ?: return skip()
        val first = loader.frame("img_f43ed6f45078.jpg") ?: return skip()
        val second = loader.resample(first, 0.8)
        assertNull(
            "a resolution change between frames is not the same button",
            BrawlerDetailSelect.confirmPair(
                first.copy(capturedAtMs = 1_000L), second.copy(capturedAtMs = 1_300L), 1_500L,
            ),
        )
    }

    /** The gate contract still holds when the caller presents scans directly. */
    @Test
    fun scanOverloadKeepsTheSelectionGateContract() {
        val hit = BrawlerOcrHit("select", 76, 506, 322, 566)
        val first = BrawlerOcrScan(1280, 591, 1_000L, listOf(hit))
        val second = BrawlerOcrScan(1280, 591, 1_300L, listOf(hit))
        assertNotNull(BrawlerDetailSelect.confirmPair(first, second, 1_500L))
        assertNull(BrawlerDetailSelect.confirmPair(second, first, 1_500L))
    }

    /** One actual glyph is replaced, keeping the surrounding five letters and plate intact. */
    @Test
    fun rejectsSingleLetterReplacementInActualTextRow() {
        val loader = harness() ?: return skip()
        val original = loader.frame("img_f43ed6f45078.jpg") ?: return skip()
        for (slot in 0 until 6) {
            val other = (slot + 1) % 6
            val pixels = original.argb.copyOf()
            for (y in 528 until 546) for (dx in 0 until 12) {
                pixels[y * original.width + 161 + slot * 12 + dx] = original.argb[y * original.width + 161 + other * 12 + dx]
            }
            assertTrue("mutation must change pixels", !pixels.contentEquals(original.argb))
            val a = FrameSnapshot(original.width, original.height, pixels, 1000)
            val b = FrameSnapshot(original.width, original.height, pixels.copyOf(), 1300)
            assertNull("single changed letter $slot must deny", BrawlerDetailSelect.confirmPair(a, b, 1500))
        }
    }

    /** The reference mask is the user's own SELECT pixels, not a blob signature. */
    @Test
    fun referenceMaskIsDistinctFromABlankPlate() {
        val reference = BrawlerDetailSelect.referenceMask()
        assertEquals(BrawlerDetailSelect.MASK_BITS, reference.size)
        val ones = reference.count { it }
        assertTrue("a real word fills a meaningful part of the mask, got $ones", ones in 150..450)
        assertTrue("a blank mask is below the identity threshold", BrawlerDetailSelect.similarity(BooleanArray(BrawlerDetailSelect.MASK_BITS)) < BrawlerDetailSelect.MIN_SIMILARITY)
        assertEquals(1.0, BrawlerDetailSelect.similarity(reference), 0.0)
        // The mask is a 2-D word: inverting it must lose identity, not merely shift it.
        val inverted = BooleanArray(BrawlerDetailSelect.MASK_BITS) { !reference[it] }
        assertEquals(0.0, BrawlerDetailSelect.similarity(inverted), 0.0)
    }

    private fun skip() {
        assumeTrue("real delivered fixture images are unavailable in this environment", false)
    }

    /**
     * Private fixture harness. Decoding happens here, with the JDK only, and the decoded RGB is
     * packed into the capture buffer's real ABGR order exactly once.
     */
    private class Harness {
        fun frame(path: String): FrameSnapshot? {
            val root = System.getenv("PYLA_DETAIL_FIXTURE_DIR") ?: return null
            val file = File(root, path)
            if (!file.isFile) return null
            // Android unit-test compile classpath omits java.desktop; JVM runtime provides it.
            val image = Class.forName("javax.imageio.ImageIO").getMethod("read", File::class.java).invoke(null, file) ?: return null
            val imageClass = Class.forName("java.awt.image.BufferedImage")
            val width = imageClass.getMethod("getWidth").invoke(image) as Int
            val height = imageClass.getMethod("getHeight").invoke(image) as Int
            val getRGB = imageClass.getMethod("getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val argb = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val rgb = getRGB.invoke(image, x, y) as Int
                    val r = (rgb ushr 16) and 0xFF
                    val g = (rgb ushr 8) and 0xFF
                    val b = rgb and 0xFF
                    // The capture buffer is ABGR: red low, blue high.
                    argb[y * width + x] = (0xFF shl 24) or (b shl 16) or (g shl 8) or r
                }
            }
            return FrameSnapshot(width, height, argb, 1_000L)
        }

        fun yellow(): Int = pack(250, 205, 70)
        fun background(): Int = pack(24, 26, 32)

        fun pack(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (b shl 16) or (g shl 8) or r

        fun copy(source: FrameSnapshot): FrameSnapshot =
            FrameSnapshot(source.width, source.height, source.argb.copyOf(), source.capturedAtMs)

        /** Nearest-neighbour rescale: changes geometry, never the colours. */
        fun resample(source: FrameSnapshot, scale: Double): FrameSnapshot {
            val width = Math.max(1, Math.round(source.width * scale).toInt())
            val height = Math.max(1, Math.round(source.height * scale).toInt())
            val argb = IntArray(width * height)
            for (y in 0 until height) {
                val sy = Math.min(source.height - 1, (y * source.height / height).toInt())
                for (x in 0 until width) {
                    val sx = Math.min(source.width - 1, (x * source.width / width).toInt())
                    argb[y * width + x] = source.argb[sy * source.width + sx]
                }
            }
            return FrameSnapshot(width, height, argb, source.capturedAtMs)
        }

        /** Paints [color] over a rectangle, inclusive of the given coordinates. */
        fun eraseRect(source: FrameSnapshot, x1: Int, y1: Int, x2: Int, y2: Int, color: Int): FrameSnapshot {
            val out = copy(source)
            for (y in y1.toInt()..y2.toInt()) {
                for (x in x1.toInt()..x2.toInt()) {
                    if (x in 0 until out.width && y in 0 until out.height) out.argb[y * out.width + x] = color
                }
            }
            return out
        }

        /** Blits a rectangle of [from] into [target] at the given offset. */
        fun blit(
            from: FrameSnapshot,
            target: FrameSnapshot,
            x1: Int, y1: Int, x2: Int, y2: Int, dx: Int, dy: Int,
        ): FrameSnapshot {
            val out = copy(target)
            for (y in y1..y2) {
                for (x in x1..x2) {
                    val tx = dx + (x - x1)
                    val ty = dy + (y - y1)
                    if (tx in 0 until out.width && ty in 0 until out.height) {
                        out.argb[ty * out.width + tx] = from.argb[y * from.width + x]
                    }
                }
            }
            return out
        }

        /**
         * Draws a legible block word on a yellow plate of the given box, so a DIFFERENT word occupies
         * exactly SELECT's geometry.
         */
        fun wordPlate(
            source: FrameSnapshot, x1: Int, y1: Int, width: Int, height: Int, word: String, letters: Int,
        ): FrameSnapshot {
            val out = eraseRect(source, x1, y1, x1 + width, y1 + height, yellow())
            val slot = width.toDouble() / letters
            val barHeight = height / 2
            for (i in 0 until letters) {
                val lx = (x1 + Math.round(i * slot)).toInt()
                val lw = Math.round(slot * 0.62).toInt()
                // Solid vertical bars plus a crossbar make a word no SELECT mask can match.
                for (y in y1 + (height - barHeight) / 2 until y1 + (height + barHeight) / 2) {
                    for (x in lx until lx + lw) {
                        if (x in 0 until out.width && y in 0 until out.height) out.argb[y * out.width + x] = pack(255, 255, 255)
                    }
                }
                for (y in y1 + height / 4 until y1 + height / 4 + Math.max(2, barHeight / 6)) {
                    for (x in lx until lx + lw) {
                        if (x in 0 until out.width && y in 0 until out.height) out.argb[y * out.width + x] = pack(255, 255, 255)
                    }
                }
            }
            return out
        }

        /**
         * Keeps the plate and its geometry but alters the glyph pixels: light strokes are moved to
         * the top of each letter slot, which preserves column sums while changing the 2-D mask.
         */
        fun scrambleGlyphs(source: FrameSnapshot, x1: Int, y1: Int, width: Int, height: Int): FrameSnapshot {
            val out = copy(source)
            val slot = width.toDouble() / 6
            for (i in 0 until 6) {
                val lx = (x1 + Math.round(i * slot)).toInt()
                val lw = Math.max(3, Math.round(slot * 0.7).toInt())
                for (y in y1 + 4 until y1 + Math.max(8, height / 5)) {
                    for (x in lx until lx + lw) {
                        if (x in 0 until out.width && y in 0 until out.height) out.argb[y * out.width + x] = pack(255, 255, 255)
                    }
                }
                for (y in y1 + height / 2 until y1 + height - 3) {
                    for (x in lx until lx + lw) {
                        if (x in 0 until out.width && y in 0 until out.height) out.argb[y * out.width + x] = yellow()
                    }
                }
            }
            return out
        }

        /** Re-encodes a capture buffer with red and blue exchanged. */
        fun swapRedBlue(source: FrameSnapshot): FrameSnapshot {
            val out = copy(source)
            for (i in out.argb.indices) {
                val p = out.argb[i]
                val r = p and 0xFF
                val b = (p ushr 16) and 0xFF
                out.argb[i] = (p and 0xFF00FF00.toInt()) or (r shl 16) or b
            }
            return out
        }
    }

    private fun FrameSnapshot.copy(width: Int = this.width, capturedAtMs: Long = this.capturedAtMs): FrameSnapshot =
        FrameSnapshot(width, height, argb.copyOf(), capturedAtMs)

    private fun harness(): Harness? = Harness()
}