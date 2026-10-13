package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Modifier
import java.util.Locale

/**
 * Pure JVM unit tests for the brawler OCR fresh-pair / geometry gate.
 *
 * The gate is deliberately stateless: it holds no mutable state, so every assertion here is a
 * total function of its arguments. Tests cover the positive path (two distinct fresh frames
 * showing the exact same card), and the fail-closed rejections that keep a wrong brawler from
 * ever being selected: motion beyond tolerance, duplicate/ambiguous OCR lines, alias and accent
 * handling, substring matches, stale/future/same-frame timestamps, dimension resets, degenerate
 * boxes, empty scans and invalid vertical regions.
 */
class BrawlerSelectionGateTest {

    // ------------------------------------------------------------------ fixtures

    private fun hit(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        BrawlerOcrHit(text, left, top, right, bottom)

    private fun scan(
        capturedAtMs: Long,
        vararg hits: BrawlerOcrHit,
        width: Int = W,
        height: Int = H
    ) = BrawlerOcrScan(width, height, capturedAtMs, hits.toList())

    private fun card(text: String = "El Primo", dx: Int = 0, dy: Int = 0) =
        hit(text, CARD_LEFT + dx, CARD_TOP + dy, CARD_RIGHT + dx, CARD_BOTTOM + dy)

    // ------------------------------------------------------------------ normalize

    @Test
    fun `normalize folds diacritics punctuation and spacing and lowercases with ROOT locale`() {
        assertEquals("elprimo", BrawlerSelectionGate.normalize("  El   Primo  "))
        assertEquals("elprimo", BrawlerSelectionGate.normalize("El-Primo"))
        assertEquals("elprimo", BrawlerSelectionGate.normalize("EL.PRIMO"))
        assertEquals("pepe", BrawlerSelectionGate.normalize("Pepé"))
        assertEquals("purio", BrawlerSelectionGate.normalize("Purió"))
        assertEquals("8bit", BrawlerSelectionGate.normalize("8-Bit"))
        assertEquals("mrp", BrawlerSelectionGate.normalize("Mr. P"))
        assertEquals("", BrawlerSelectionGate.normalize("   "))
        assertEquals("", BrawlerSelectionGate.normalize(""))
    }

    @Test
    fun `normalize is locale invariant for dotted and plain capital I`() {
        // A Turkish default locale must never turn "I" into a dotless "i".
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("title", BrawlerSelectionGate.normalize("TITLE"))
            assertEquals("brawler", BrawlerSelectionGate.normalize("BRAWLER"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `zero timestamp has no capture authority`() {
        assertNull(BrawlerSelectionGate.confirmPair(scan(0L, card()), scan(500L, card()), NAMES, GRID_TOP, GRID_BOTTOM, 1000L))
    }

    @Test
    fun `compact configured canonical names and spaced skin aliases match exactly`() {
        for ((raw, canonical) in listOf("El Primo" to "elprimo", "8-Bit" to "8bit", "Mr. P" to "mrp", "Bandita Shelly" to "banditashelly")) {
            assertNotNull(BrawlerSelectionGate.uniqueMatch(scan(9000L, card(raw)), setOf(canonical), GRID_TOP, GRID_BOTTOM))
        }
    }

    @Test
    fun `wrong title plus target description cannot confirm identity among known brawlers`() {
        val hits = listOf(hit("COLT", 200, 220, 320, 250), hit("SHELLY", 260, 550, 380, 580))
        val first = BrawlerOcrScan(1920, 1080, 2000L, hits)
        val second = first.copy(capturedAtMs = 2300L)
        assertNull(BrawlerSelectionGate.confirmPair(first, second, setOf("colt", "shelly"), DETAIL_TOP, DETAIL_BOTTOM, 2500L))
        assertNotNull(BrawlerSelectionGate.confirmPair(first.copy(hits = listOf(hits[1])), second.copy(hits = listOf(hits[1])), setOf("colt", "shelly"), DETAIL_TOP, DETAIL_BOTTOM, 2500L))
    }

    // ------------------------------------------------------------------ positive pair

    @Test
    fun `two distinct fresh frames of the exact card confirm the fresh frame hit`() {
        val first = scan(9_000L, card())
        val second = scan(9_400L, card())

        val confirmed = BrawlerSelectionGate.confirmPair(
            first, second, NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
        )

        assertNotNull("expected the pair to confirm", confirmed)
        assertEquals("El Primo", confirmed!!.text)
        assertEquals(second.hits[0], confirmed)
    }

    @Test
    fun `frame age exactly at maxAgeMs and gap exactly at maxGapMs are still accepted`() {
        val first = scan(7_000L, card())
        val second = scan(10_000L, card())

        val confirmed = BrawlerSelectionGate.confirmPair(
            first, second, NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L,
            maxAgeMs = 3_000L, maxGapMs = 4_000L
        )

        assertNotNull("inclusive boundaries must confirm", confirmed)
        assertEquals("El Primo", confirmed!!.text)
        assertEquals(CARD_LEFT, confirmed!!.left)
        assertEquals(CARD_TOP, confirmed!!.top)
    }

    @Test
    fun `small motion within tolerance confirms but motion beyond tolerance is rejected`() {
        // height 1000 -> tolerance = max(4, 1000 * 0.015) = 15 px
        val within = BrawlerSelectionGate.confirmPair(
            scan(9_000L, card()),
            scan(9_300L, card(dx = 12, dy = -10)),
            NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
        )
        assertNotNull("12px/10px jitter is inside the 15px tolerance", within)

        val beyond = BrawlerSelectionGate.confirmPair(
            scan(9_000L, card()),
            scan(9_300L, card(dx = 25, dy = 0)),
            NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
        )
        assertNull("25px horizontal jump means a different card", beyond)

        val beyondVertical = BrawlerSelectionGate.confirmPair(
            scan(9_000L, card()),
            scan(9_300L, card(dy = 40)),
            NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
        )
        assertNull("40px vertical jump means the grid scrolled", beyondVertical)
    }

    // ------------------------------------------------------------------ ambiguity

    @Test
    fun `duplicate OCR lines for the same card are rejected as ambiguous`() {
        val duplicated = BrawlerSelectionGate.uniqueMatch(
            scan(9_000L, card(), card(dy = 60)),
            NAMES, GRID_TOP, GRID_BOTTOM
        )
        assertNull("two OCR lines for one card must not be silently collapsed", duplicated)

        val pair = BrawlerSelectionGate.confirmPair(
            scan(9_000L, card(), card(dy = 60)),
            scan(9_400L, card(), card(dy = 60)),
            NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
        )
        assertNull("ambiguous frames must never confirm", pair)
    }

    @Test
    fun `two different known names in one frame are rejected as ambiguous`() {
        val twoNames = BrawlerSelectionGate.uniqueMatch(
            scan(9_000L, card("El Primo"), hit("Shelly", 500, 400, 700, 430)),
            NAMES, GRID_TOP, GRID_BOTTOM
        )
        assertNull("two distinct matches must not pick one", twoNames)
    }

    // ------------------------------------------------------------------ names / aliases

    @Test
    fun `exact alias is accepted only when that alias is in the allow list`() {
        val accepted = BrawlerSelectionGate.uniqueMatch(
            scan(9_000L, hit("Shellie", CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM)),
            setOf("shellie"), GRID_TOP, GRID_BOTTOM
        )
        assertNotNull("an exact alias present in the allow list must match", accepted)

        val rejected = BrawlerSelectionGate.uniqueMatch(
            scan(9_000L, hit("Shellie", CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM)),
            setOf("shelly"), GRID_TOP, GRID_BOTTOM
        )
        assertNull("an alias absent from the allow list must not fall back to canonical", rejected)
    }

    @Test
    fun `accented OCR text matches only its unaccented normalized allow list entry`() {
        val accepted = BrawlerSelectionGate.uniqueMatch(
            scan(9_000L, hit("Pepé", CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM)),
            setOf("pepe"), GRID_TOP, GRID_BOTTOM
        )
        assertNotNull("diacritics fold to the canonical name", accepted)

        val foldedAlias = BrawlerSelectionGate.uniqueMatch(
            scan(9_000L, hit("Purió", CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM)),
            setOf("purio"), GRID_TOP, GRID_BOTTOM
        )
        assertNotNull("accent folding yields the allow-listed name", foldedAlias)

        val wrongName = BrawlerSelectionGate.uniqueMatch(
            scan(9_000L, hit("Pepé", CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM)),
            setOf("purio"), GRID_TOP, GRID_BOTTOM
        )
        assertNull("accent folding must not turn one brawler into another", wrongName)
    }

    @Test
    fun `substring superstring and unknown names are rejected`() {
        val superstring = BrawlerSelectionGate.uniqueMatch(
            scan(9_000L, hit("Shelly Baby", CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM)),
            setOf("shelly"), GRID_TOP, GRID_BOTTOM
        )
        assertNull("a superstring is not the exact name", superstring)

        val substring = BrawlerSelectionGate.uniqueMatch(
            scan(9_000L, hit("Sandy Pop", CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM)),
            setOf("andy"), GRID_TOP, GRID_BOTTOM
        )
        assertNull("a substring must never match a different brawler", substring)

        val unknown = BrawlerSelectionGate.uniqueMatch(
            scan(9_000L, hit("Leon", CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM)),
            setOf("elprimo", "shelly"), GRID_TOP, GRID_BOTTOM
        )
        assertNull("an unknown name must not match", unknown)
    }

    @Test
    fun `empty scan empty hits and empty allow list yield null`() {
        assertNull(BrawlerSelectionGate.uniqueMatch(scan(9_000L), NAMES, GRID_TOP, GRID_BOTTOM))
        assertNull(
            BrawlerSelectionGate.uniqueMatch(
                scan(9_000L, card()), emptySet(), GRID_TOP, GRID_BOTTOM
            )
        )
        assertNull(
            BrawlerSelectionGate.confirmPair(
                scan(9_000L), scan(9_400L), NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
            )
        )
    }

    // ------------------------------------------------------------------ freshness

    @Test
    fun `stale first frame beyond max age is rejected`() {
        assertNull(
            "first frame older than maxAgeMs",
            BrawlerSelectionGate.confirmPair(
                scan(6_999L, card()),
                scan(9_400L, card()),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
            )
        )
        assertNull(
            "second frame older than maxAgeMs",
            BrawlerSelectionGate.confirmPair(
                scan(9_000L, card()),
                scan(6_999L, card()),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
            )
        )
    }

    @Test
    fun `future timestamps are rejected`() {
        assertNull(
            "first frame in the future",
            BrawlerSelectionGate.confirmPair(
                scan(10_001L, card()),
                scan(10_400L, card()),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
            )
        )
        assertNull(
            "second frame in the future",
            BrawlerSelectionGate.confirmPair(
                scan(9_000L, card()),
                scan(10_001L, card()),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
            )
        )
    }

    @Test
    fun `identical timestamps are not two distinct fresh frames`() {
        assertNull(
            BrawlerSelectionGate.confirmPair(
                scan(9_000L, card()),
                scan(9_000L, card()),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
            )
        )
        assertNull(
            "a second frame earlier than the first is a reset, not progress",
            BrawlerSelectionGate.confirmPair(
                scan(9_400L, card()),
                scan(9_000L, card()),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
            )
        )
    }

    @Test
    fun `a gap beyond maxGapMs is rejected and a custom maxGapMs is honored`() {
        assertNull(
            "5s gap exceeds the 4s default",
            BrawlerSelectionGate.confirmPair(
                scan(4_000L, card()),
                scan(9_400L, card()),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
            )
        )
        assertNull(
            "1.4s gap exceeds an explicit 1s maxGapMs",
            BrawlerSelectionGate.confirmPair(
                scan(9_000L, card()),
                scan(10_400L, card()),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 11_000L,
                maxAgeMs = 3_000L, maxGapMs = 1_000L
            )
        )
        assertNotNull(
            "the same 1.4s gap is inside the default 4s maxGapMs",
            BrawlerSelectionGate.confirmPair(
                scan(9_000L, card()),
                scan(10_400L, card()),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 11_000L
            )
        )
    }

    // ------------------------------------------------------------------ geometry

    @Test
    fun `a capture dimension reset between the two frames is rejected`() {
        assertNull(
            BrawlerSelectionGate.confirmPair(
                scan(9_000L, card()),
                scan(9_400L, card(), height = 1_200),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
            )
        )
        assertNull(
            BrawlerSelectionGate.confirmPair(
                scan(9_000L, card()),
                scan(9_400L, card(), width = 1_080),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
            )
        )
    }

    @Test
    fun `a matching name at a different position in each frame is rejected`() {
        // Same name visible twice per frame at two grid rows is already ambiguous; a single hit
        // per frame that jumps to a different card position must not confirm either.
        assertNull(
            BrawlerSelectionGate.confirmPair(
                scan(9_000L, hit("El Primo", 100, 400, 300, 430)),
                scan(9_400L, hit("El Primo", 700, 700, 900, 730)),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
            )
        )
    }

    @Test
    fun `two different brawlers in the same position across the pair are rejected`() {
        // Each frame is individually unambiguous and the card never moved, but the identity
        // changed between frames: the desired name is not actually on screen in both frames.
        assertNull(
            BrawlerSelectionGate.confirmPair(
                scan(9_000L, hit("El Primo", CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM)),
                scan(9_400L, hit("Shelly", CARD_LEFT, CARD_TOP, CARD_RIGHT, CARD_BOTTOM)),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
            )
        )
    }

    @Test
    fun `degenerate inverted and out of frame boxes are rejected`() {
        assertNull(
            "zero width box",
            BrawlerSelectionGate.uniqueMatch(
                scan(9_000L, hit("El Primo", 200, 400, 200, 430)), NAMES, GRID_TOP, GRID_BOTTOM
            )
        )
        assertNull(
            "zero height box",
            BrawlerSelectionGate.uniqueMatch(
                scan(9_000L, hit("El Primo", 100, 400, 300, 400)), NAMES, GRID_TOP, GRID_BOTTOM
            )
        )
        assertNull(
            "inverted box",
            BrawlerSelectionGate.uniqueMatch(
                scan(9_000L, hit("El Primo", 300, 430, 100, 400)), NAMES, GRID_TOP, GRID_BOTTOM
            )
        )
        assertNull(
            "box extends past the frame width",
            BrawlerSelectionGate.uniqueMatch(
                scan(9_000L, hit("El Primo", 900, 400, 1_001, 430)), NAMES, GRID_TOP, GRID_BOTTOM
            )
        )
        assertNull(
            "box extends past the frame height",
            BrawlerSelectionGate.uniqueMatch(
                scan(9_000L, hit("El Primo", 100, 980, 300, 1_001)), NAMES, GRID_TOP, GRID_BOTTOM
            )
        )
        assertNull(
            "negative origin",
            BrawlerSelectionGate.uniqueMatch(
                scan(9_000L, hit("El Primo", -1, 400, 300, 430)), NAMES, GRID_TOP, GRID_BOTTOM
            )
        )
    }

    @Test
    fun `non positive scan dimensions are rejected`() {
        assertNull(
            BrawlerSelectionGate.uniqueMatch(
                scan(9_000L, card(), width = 0), NAMES, GRID_TOP, GRID_BOTTOM
            )
        )
        assertNull(
            BrawlerSelectionGate.uniqueMatch(
                scan(9_000L, card(), height = 0), NAMES, GRID_TOP, GRID_BOTTOM
            )
        )
    }

    // ------------------------------------------------------------------ region

    @Test
    fun `invalid vertical region fractions are rejected`() {
        val invalid = listOf(
            0.91 to 0.16,      // inverted
            0.50 to 0.50,      // empty band
            -0.10 to 0.90,     // negative top
            0.16 to 1.50,      // beyond the frame
            0.20 to 0.10,      // inverted, short form
            Double.NaN to 0.90,
            0.16 to Double.NaN,
            Double.POSITIVE_INFINITY to 0.90,
            0.16 to Double.NEGATIVE_INFINITY
        )
        for ((top, bottom) in invalid) {
            assertNull(
                "region $top..$bottom must be rejected",
                BrawlerSelectionGate.uniqueMatch(scan(9_000L, card()), NAMES, top, bottom)
            )
            assertNull(
                "region $top..$bottom must reject a pair",
                BrawlerSelectionGate.confirmPair(
                    scan(9_000L, card()), scan(9_400L, card()), NAMES, top, bottom, nowMs = 10_000L
                )
            )
        }
    }

    @Test
    fun `a hit outside the requested vertical region is rejected for both grid and detail bands`() {
        // Grid band = y 160..910, detail band = y 80..650 for a 1000px frame.
        val gridOnly = hit("El Primo", CARD_LEFT, 800, CARD_RIGHT, 830)
        assertNotNull(
            "a card at y=800..830 is inside the grid band",
            BrawlerSelectionGate.uniqueMatch(scan(9_000L, gridOnly), NAMES, GRID_TOP, GRID_BOTTOM)
        )
        assertNull(
            "the same card is below the detail band",
            BrawlerSelectionGate.uniqueMatch(scan(9_000L, gridOnly), NAMES, DETAIL_TOP, DETAIL_BOTTOM)
        )

        val detailOnly = hit("El Primo", CARD_LEFT, 100, CARD_RIGHT, 130)
        assertNotNull(
            "a card at y=100..130 is inside the detail band",
            BrawlerSelectionGate.uniqueMatch(scan(9_000L, detailOnly), NAMES, DETAIL_TOP, DETAIL_BOTTOM)
        )
        assertNull(
            "the same card is above the grid band",
            BrawlerSelectionGate.uniqueMatch(scan(9_000L, detailOnly), NAMES, GRID_TOP, GRID_BOTTOM)
        )
    }

    @Test
    fun `a box straddling the region boundary is rejected`() {
        // Grid band starts at y=160 and ends at y=910 for a 1000px frame.
        val straddlingTop = hit("El Primo", CARD_LEFT, 150, CARD_RIGHT, 180)
        assertNull(
            "a box crossing the top of the band is not fully inside it",
            BrawlerSelectionGate.uniqueMatch(scan(9_000L, straddlingTop), NAMES, GRID_TOP, GRID_BOTTOM)
        )
        val straddlingBottom = hit("El Primo", CARD_LEFT, 900, CARD_RIGHT, 925)
        assertNull(
            "a box crossing the bottom of the band is not fully inside it",
            BrawlerSelectionGate.uniqueMatch(scan(9_000L, straddlingBottom), NAMES, GRID_TOP, GRID_BOTTOM)
        )
        assertNull(
            BrawlerSelectionGate.confirmPair(
                scan(9_000L, straddlingTop),
                scan(9_400L, straddlingTop),
                NAMES, GRID_TOP, GRID_BOTTOM, nowMs = 10_000L
            )
        )
    }

    // ------------------------------------------------------------------ purity

    @Test
    fun `gate is a pure stateless object with no mutable instance state`() {
        val cls = BrawlerSelectionGate::class.java
        for (field in cls.declaredFields) {
            assertTrue(
                "field ${field.name} must be static so the gate holds no instance state",
                Modifier.isStatic(field.modifiers)
            )
            assertTrue(
                "field ${field.name} must be final",
                Modifier.isFinal(field.modifiers)
            )
        }
        // No writable instance fields at all: that is what keeps the gate a pure function.
        assertEquals(
            "gate must expose no mutable instance state",
            emptyList<String>(),
            cls.declaredFields.filter { !Modifier.isStatic(it.modifiers) }.map { it.name }
        )

        // Repeated evaluation of the same inputs must return equal results, and confirming a pair
        // must not mutate either input scan.
        val first = scan(9_000L, card())
        val second = scan(9_400L, card())
        val a = BrawlerSelectionGate.confirmPair(first, second, NAMES, GRID_TOP, GRID_BOTTOM, 10_000L)
        val b = BrawlerSelectionGate.confirmPair(first, second, NAMES, GRID_TOP, GRID_BOTTOM, 10_000L)
        assertEquals(a, b)
        assertEquals(scan(9_000L, card()), first)
        assertEquals(scan(9_400L, card()), second)
        assertEquals(
            BrawlerSelectionGate.normalize("El Primo"),
            BrawlerSelectionGate.normalize("El Primo")
        )
    }

    private fun assertTrue(message: String, condition: Boolean) =
        org.junit.Assert.assertTrue(message, condition)

    private companion object {
        const val W = 1000
        const val H = 1000
        const val GRID_TOP = 0.16
        const val GRID_BOTTOM = 0.91
        const val DETAIL_TOP = 0.08
        const val DETAIL_BOTTOM = 0.65
        const val CARD_LEFT = 100
        const val CARD_TOP = 400
        const val CARD_RIGHT = 300
        const val CARD_BOTTOM = 430
        val NAMES = setOf("elprimo", "shelly", "pepe")
    }
}