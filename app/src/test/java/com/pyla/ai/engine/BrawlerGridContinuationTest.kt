package com.pyla.ai.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for [BrawlerGridContinuation], the supplemental pair evidence for a brawler-list
 * continuation page.
 *
 * Every geometry below is hard-coded from the two real capture sizes (1280x591 screenshot and
 * 1920x886 control) and from [BrawlerGridOcrRegions], so a change to a strip band, to the sort
 * region of interest, to the freshness budget or to the vocabulary fails here instead of silently
 * widening what authorizes a continuation page.
 *
 * The negative half is the point of this file: the helper returns a single `Boolean` and that
 * `true` is only ever additional current-pair evidence, so every ambiguous, duplicated, stale,
 * reused, moved, one-frame, out-of-region, malformed or unsupported input must return `false`.
 * The header is never accepted here and no test in this file expects the word "brawler" to
 * authorize anything on its own.
 */
class BrawlerGridContinuationTest {

    // ---- real capture geometry, derived from BrawlerGridOcrRegions -----------------------------

    private val names = setOf("Colt", "Rosa", "Bo")
    private val now = 100_000L

    private fun hit(text: String, l: Int, t: Int, r: Int, b: Int) = BrawlerOcrHit(text, l, t, r, b)

    /** Row boxes fully inside the three canonical 1280x591 title strips. */
    private fun rowHits(shift: Int = 0): List<BrawlerOcrHit> = listOf(
        hit("Colt", 100 + shift, 180, 300 + shift, 220),
        hit("Rosa", 100 + shift, 340, 300 + shift, 380),
        hit("Bo", 100 + shift, 500, 300 + shift, 535),
    )

    private fun sortHit(text: String = "Rarity", shift: Int = 0) = hit(text, 520 + shift, 5, 640 + shift, 30)

    private fun scan(
        w: Int = 1280,
        h: Int = 591,
        at: Long,
        hits: List<BrawlerOcrHit>,
        headerAmbiguous: Boolean = false,
    ) = BrawlerOcrScan(w, h, at, hits, headerAmbiguous)

    private fun goodPair(): Pair<BrawlerOcrScan, BrawlerOcrScan> =
        scan(at = 99_000L, hits = rowHits() + sortHit()) to
            scan(at = 99_400L, hits = rowHits() + sortHit())

    private fun confirm(first: BrawlerOcrScan, second: BrawlerOcrScan) =
        BrawlerGridContinuation.confirm(first, second, names, now)

    private fun confirmWith(
        vocabulary: Set<String>,
        first: BrawlerOcrScan,
        second: BrawlerOcrScan,
    ) = BrawlerGridContinuation.confirm(first, second, vocabulary, now)

    // ---- positive ----------------------------------------------------------------------------

    @Test fun screenshotCaptureConfirmsSameSortLabelAndThreeDistinctIdentities() {
        val (first, second) = goodPair()
        assertTrue(confirm(first, second))
    }

    @Test fun controlCaptureConfirmsSameSortLabelAndThreeDistinctIdentities() {
        val rows = listOf(
            hit("Colt", 200, 270, 400, 330),
            hit("Rosa", 200, 510, 400, 570),
            hit("Bo", 200, 750, 400, 800),
        )
        val sort = hit("Rarity", 800, 10, 950, 45)
        val first = scan(1920, 886, 99_000L, rows + sort)
        val second = scan(1920, 886, 99_400L, rows + sort)
        assertTrue(confirm(first, second))
    }

    @Test fun everyVocabularyAliasIsAcceptedVerbatimAndNormalizesAcrossThePair() {
        // Raw spelling is checked strictly per scan; the accent forms therefore only match when
        // both frames carry the SAME raw label, which normalize then folds together.
        for (alias in listOf("Rarity", "rarity", "RARITY", " Rarity ", "Seltenheit", "Rarità", "Rarita", "Rareté", "Rarete", "Rareza")) {
            assertTrue("alias must be a strict sort label: $alias", BrawlerGridContinuation.isSortLabel(alias))
            val sort = sortHit(alias)
            val first = scan(at = 99_000L, hits = rowHits() + sort)
            val second = scan(at = 99_400L, hits = rowHits() + sort)
            assertTrue("alias must confirm across the pair: $alias", confirm(first, second))
        }
        // The two accented spellings of the same word fold to one value only AFTER strict filtering.
        val accented = sortHit("Rarità")
        val unaccented = sortHit("Rarita")
        // Both raw strings are inside the strict vocabulary, so the pair is decided on the
        // normalized value: the accented and unaccented spellings are the same sort label.
        assertTrue(confirm(scan(at = 99_000L, hits = rowHits() + accented), scan(at = 99_400L, hits = rowHits() + unaccented)))
        // A genuinely different word still fails, so the folding above is not a wildcard.
        val different = sortHit("Rareza")
        assertFalse(confirm(scan(at = 99_000L, hits = rowHits() + accented), scan(at = 99_400L, hits = rowHits() + different)))
    }

    @Test fun headerWordsNeverAuthorizeOnTheirOwn() {
        // A list heading is present and readable in both frames, but it is neither the sort label
        // nor a known identity: it must not substitute for any required evidence.
        val heading = hit("Brawlers (107/107)", 72, 35, 400, 90)
        val first = scan(at = 99_000L, hits = rowHits() + sortHit() + heading)
        val second = scan(at = 99_400L, hits = rowHits() + sortHit() + heading)
        assertTrue(confirm(first, second))

        // Drop every row: the heading alone cannot carry the page.
        val onlyHeading = listOf(heading, sortHit())
        assertFalse(confirm(scan(at = 99_000L, hits = onlyHeading), scan(at = 99_400L, hits = onlyHeading)))
        // Drop the sort label: the heading is not a substitute for it either.
        assertFalse(confirm(scan(at = 99_000L, hits = rowHits() + heading), scan(at = 99_400L, hits = rowHits() + heading)))
    }

    // ---- heading ambiguity, the very gap this run's evidence was built against -----------------

    @Test fun extractorFlaggedAmbiguousHeadingFails() {
        val hits = rowHits() + sortHit()
        val flagged = scan(at = 99_000L, hits = hits, headerAmbiguous = true)
        val clean = scan(at = 99_400L, hits = hits, headerAmbiguous = false)
        // Either capture carrying the flag is fatal, on its own side and on the other.
        assertFalse(confirm(flagged, clean))
        assertFalse(confirm(clean, flagged))
        assertFalse(confirm(flagged, flagged))
    }

    @Test fun duplicateHeadingInTheHeaderBandFails() {
        // Two recognised headings in the heading band, even byte-identical, are ambiguous and must
        // never be tolerated just because a sort label is also present.
        // Two recognised headings INSIDE the header band (x 0..588, y 35..94 at 1280x591), even
        // byte-identical, are ambiguous and must never be tolerated just because a sort label is
        // also present. Duplicates are counted, never collapsed.
        val heading = hit("Brawlers (107/107)", 72, 38, 400, 90)
        val duplicate = hit("Brawlers (107/107)", 150, 40, 470, 88)
        val both = rowHits() + sortHit() + heading + duplicate
        assertFalse(confirm(scan(at = 99_000L, hits = both), scan(at = 99_400L, hits = both)))

        // One heading per frame is fine: the COUNT, not the presence, is what is rejected.
        val single = rowHits() + sortHit() + heading
        assertTrue(confirm(scan(at = 99_000L, hits = single), scan(at = 99_400L, hits = single)))

        // Only the second frame duplicated is still fatal.
        assertFalse(confirm(scan(at = 99_000L, hits = single), scan(at = 99_400L, hits = both)))
    }

    @Test fun headingShapedTextOutsideTheHeaderBandIsNotCountedAsADuplicate() {
        val heading = hit("Brawlers (107/107)", 72, 38, 400, 90)
        // A heading-shaped string below the header band (e.g. over a card) is not heading evidence,
        // so it must not spuriously reject an otherwise valid pair.
        val stray = hit("Brawler", 700, 180, 900, 220)
        val hits = rowHits() + sortHit() + heading + stray
        assertTrue(confirm(scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = hits)))
    }

    // ---- body coverage ------------------------------------------------------------------------

    @Test fun singleRowAndTwoRowPagesFail() {
        val one = listOf(hit("Colt", 100, 180, 300, 220), sortHit())
        assertFalse(confirm(scan(at = 99_000L, hits = one), scan(at = 99_400L, hits = one)))

        val two = listOf(hit("Colt", 100, 180, 300, 220), hit("Rosa", 100, 340, 300, 380), sortHit())
        assertFalse(confirm(scan(at = 99_000L, hits = two), scan(at = 99_400L, hits = two)))
    }

    @Test fun twoDistinctNamesCannotCoverThreeRows() {
        // Row three repeats row one: a repeated identity can never satisfy a second row.
        val rows = listOf(
            hit("Colt", 100, 180, 300, 220),
            hit("Rosa", 100, 340, 300, 380),
            hit("Colt", 100, 500, 300, 535),
        )
        val first = scan(at = 99_000L, hits = rows + sortHit())
        val second = scan(at = 99_400L, hits = rows + sortHit())
        assertFalse(confirm(first, second))
    }

    @Test fun nameOutsideItsCanonicalStripNeverSatisfiesARow() {
        // A known name inside row two's band, plus junk that is not a known identity in row three.
        val rows = listOf(
            hit("Colt", 100, 180, 300, 220),
            hit("Rosa", 100, 340, 300, 380),
            hit("Bo", 100, 400, 300, 440), // between strips: inside no canonical band
        )
        val first = scan(at = 99_000L, hits = rows + sortHit())
        val second = scan(at = 99_400L, hits = rows + sortHit())
        assertFalse(confirm(first, second))
    }

    @Test fun duplicatedNameInOneStripFails() {
        val duplicated = listOf(
            hit("Colt", 100, 180, 300, 220),
            hit("Colt", 400, 180, 600, 220),
            hit("Rosa", 100, 340, 300, 380),
            hit("Bo", 100, 500, 300, 535),
        )
        val first = scan(at = 99_000L, hits = duplicated + sortHit())
        val second = scan(at = 99_400L, hits = duplicated + sortHit())
        assertFalse(confirm(first, second))
    }

    @Test fun nameMovingBetweenFramesFails() {
        // Row three moves far beyond the existing geometry tolerance max(4, height * 0.015).
        val first = scan(at = 99_000L, hits = rowHits() + sortHit())
        val moved = listOf(
            hit("Colt", 100, 180, 300, 220),
            hit("Rosa", 100, 340, 300, 380),
            hit("Bo", 100, 200, 300, 235), // jumped into row one's band
        )
        val second = scan(at = 99_400L, hits = moved + sortHit())
        assertFalse(confirm(first, second))
    }

    @Test fun nameVisibleInOnlyOneFrameFails() {
        val first = scan(at = 99_000L, hits = rowHits() + sortHit())
        val withoutThird = listOf(
            hit("Colt", 100, 180, 300, 220),
            hit("Rosa", 100, 340, 300, 380),
        )
        val second = scan(at = 99_400L, hits = withoutThird + sortHit())
        assertFalse(confirm(first, second))
    }

    // ---- multi-column rows -------------------------------------------------------------------
    //
    // The supplied continuation screenshot shows SIX distinct known card titles over THREE rows,
    // i.e. a multi-column grid. The contract is "at least one stable known identity per row,
    // three distinct identities overall", NOT "exactly one known name per strip": a strip that
    // legitimately shows two different known cards must not be rejected for being a grid.

    private val gridNames = setOf("Colt", "Rosa", "Bo", "Shelly", "Barley", "El Primo", "Crow", "Pru", "Dynamike")

    /** Two known columns inside each of the three 1280x591 title strips (strips span x 0..832). */
    private fun twoPerRowHits(): List<BrawlerOcrHit> = listOf(
        hit("Colt", 60, 180, 240, 220),
        hit("Shelly", 520, 180, 700, 220),
        hit("Rosa", 60, 340, 240, 380),
        hit("Barley", 520, 340, 700, 380),
        hit("Bo", 60, 500, 240, 535),
        hit("El Primo", 520, 500, 760, 535),
    )

    /** Three known columns inside each strip; the extra cards are different identities. */
    private fun threePerRowHits(): List<BrawlerOcrHit> = twoPerRowHits() + listOf(
        hit("Crow", 760, 180, 830, 220),
        hit("Pru", 760, 340, 830, 380),
        hit("Dynamike", 760, 500, 830, 535),
    )

    @Test fun twoKnownCardsPerRowConfirmWhenEveryIdentityIsDistinctAndStable() {
        val hits = twoPerRowHits() + sortHit()
        val first = scan(at = 99_000L, hits = hits)
        val second = scan(at = 99_400L, hits = hits)
        assertTrue(
            "two stable, distinct known identities per row must confirm the page",
            confirmWith(gridNames, first, second),
        )
    }

    @Test fun threeKnownCardsPerRowStillConfirm() {
        // A legitimately wider grid adds cards; extra DIFFERENT stable known cards must never be
        // the reason a real continuation page is rejected.
        val hits = threePerRowHits() + sortHit()
        val first = scan(at = 99_000L, hits = hits)
        val second = scan(at = 99_400L, hits = hits)
        assertTrue(confirmWith(gridNames, first, second))
    }

    @Test fun twoColumnsPerRowAlsoConfirmOnTheControlCaptureSize() {
        val rows = listOf(
            hit("Colt", 80, 270, 300, 330),
            hit("Shelly", 700, 270, 900, 330),
            hit("Rosa", 80, 510, 300, 570),
            hit("Barley", 700, 510, 900, 570),
            hit("Bo", 80, 750, 300, 800),
            hit("El Primo", 700, 750, 1000, 800),
        )
        val sort = hit("Rarity", 800, 10, 950, 45)
        val first = scan(1920, 886, 99_000L, rows + sort)
        val second = scan(1920, 886, 99_400L, rows + sort)
        assertTrue(confirmWith(gridNames, first, second))
    }

    @Test fun duplicatedIdentityBoxInsideOneRowOfAMultiColumnGridFails() {
        // Row one shows "Colt" twice (duplicate OCR line for one card) next to an otherwise valid
        // row: duplicates are still ambiguous evidence and are never collapsed.
        val rows = listOf(
            hit("Colt", 60, 180, 240, 220),
            hit("Colt", 520, 180, 700, 220),
            hit("Shelly", 760, 180, 830, 220),
            hit("Rosa", 60, 340, 240, 380),
            hit("Barley", 520, 340, 700, 380),
            hit("Bo", 60, 500, 240, 535),
            hit("El Primo", 520, 500, 760, 535),
        )
        val hits = rows + sortHit()
        assertFalse(confirmWith(gridNames, scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = hits)))
    }

    @Test fun duplicatedIdentityInsideOneRowInOnlyOneFrameFails() {
        val clean = twoPerRowHits()
        val duplicated = clean + hit("Colt", 760, 180, 830, 220)
        val hits = clean + sortHit()
        assertFalse(
            confirmWith(gridNames, scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = duplicated + sortHit())),
        )
        assertFalse(
            confirmWith(gridNames, scan(at = 99_000L, hits = duplicated + sortHit()), scan(at = 99_400L, hits = hits)),
        )
    }

    @Test fun theSameIdentityInTwoRowsOfAMultiColumnGridFails() {
        // One identity reused to satisfy a second row is never allowed, even when every row also
        // carries other valid cards.
        val rows = listOf(
            hit("Colt", 60, 180, 240, 220),
            hit("Shelly", 520, 180, 700, 220),
            hit("Rosa", 60, 340, 240, 380),
            hit("Colt", 520, 340, 700, 380),
            hit("Bo", 60, 500, 240, 535),
            hit("El Primo", 520, 500, 760, 535),
        )
        val hits = rows + sortHit()
        assertFalse(confirmWith(gridNames, scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = hits)))
    }

    @Test fun aMultiColumnRowLeftWithoutAnyStableKnownIdentityFails() {
        // Row two holds only unknown cards: no stable known identity covers that row.
        val rows = listOf(
            hit("Colt", 60, 180, 240, 220),
            hit("Shelly", 520, 180, 700, 220),
            hit("Griff", 60, 340, 240, 380),
            hit("Nita", 520, 340, 700, 380),
            hit("Bo", 60, 500, 240, 535),
            hit("El Primo", 520, 500, 760, 535),
        )
        val hits = rows + sortHit()
        assertFalse(confirmWith(gridNames, scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = hits)))
    }

    @Test fun aSecondColumnIdentityClippedByTheStripEdgeNeverCounts() {
        // The row-two right card hangs over the 65% strip edge, so it is not inside its canonical
        // strip. The row keeps only "Rosa" in both frames and must still confirm, but the clipped
        // card may never be counted as the row's stable evidence.
        val rows = listOf(
            hit("Colt", 60, 180, 240, 220),
            hit("Shelly", 520, 180, 700, 220),
            hit("Rosa", 60, 340, 240, 380),
            hit("Barley", 700, 340, 900, 380),
            hit("Bo", 60, 500, 240, 535),
            hit("El Primo", 520, 500, 760, 535),
        )
        val hits = rows + sortHit()
        assertTrue("the in-strip identity still covers the row", confirmWith(gridNames, scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = hits)))

        // Now drop the in-strip identity too: nothing valid is left in that row.
        val noInStrip = rows.filterNot { it.text == "Rosa" }
        val other = noInStrip + sortHit()
        assertFalse(confirmWith(gridNames, scan(at = 99_000L, hits = other), scan(at = 99_400L, hits = other)))
    }

    @Test fun anOutOfFrameSecondColumnIdentityNeverCounts() {
        // Right column pushed past the frame width on row three: not usable evidence.
        val rows = listOf(
            hit("Colt", 60, 180, 240, 220),
            hit("Shelly", 520, 180, 700, 220),
            hit("Rosa", 60, 340, 240, 380),
            hit("Barley", 520, 340, 700, 380),
            hit("El Primo", 1100, 500, 1400, 535),
        )
        val hits = rows + sortHit()
        assertFalse(confirmWith(gridNames, scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = hits)))
    }

    @Test fun aMultiColumnIdentityMovingBeyondToleranceLeavesItsRowUncovered() {
        // Row two's only known card slides 40 px between the frames: beyond max(4, height * 0.015),
        // so the row has no stable known identity and the page must not confirm.
        val first = scan(at = 99_000L, hits = twoPerRowHits() + sortHit())
        val moved = twoPerRowHits().map { if (it.text == "Rosa") it.copy(left = 300, right = 480) else it }
        val second = scan(at = 99_400L, hits = moved + sortHit())
        assertFalse(confirmWith(gridNames, first, second))
    }

    @Test fun aMultiColumnIdentityVisibleInOnlyOneFrameFails() {
        val first = scan(at = 99_000L, hits = twoPerRowHits() + sortHit())
        // Row two loses both of its cards in the second frame, including the one covering the row.
        val second = scan(at = 99_400L, hits = twoPerRowHits().filterNot { it.top == 340 } + sortHit())
        assertFalse(confirmWith(gridNames, first, second))
    }

    @Test fun aMultiColumnGridStillNeedsItsSortLabelOnBothFrames() {
        val rows = twoPerRowHits()
        assertFalse(confirmWith(gridNames, scan(at = 99_000L, hits = rows + sortHit()), scan(at = 99_400L, hits = rows)))
        assertFalse(confirmWith(gridNames, scan(at = 99_000L, hits = rows), scan(at = 99_400L, hits = rows + sortHit())))
        // A duplicated sort label inside the multi-column grid is still ambiguous.
        val duplicated = rows + sortHit() + hit("Rarity", 700, 5, 820, 30)
        assertFalse(confirmWith(gridNames, scan(at = 99_000L, hits = duplicated), scan(at = 99_400L, hits = duplicated)))
    }

    @Test fun aMultiColumnGridStillFailsClosedOnStaleClockAndHeadingAmbiguity() {
        val hits = twoPerRowHits() + sortHit()
        assertFalse(confirmWith(gridNames, scan(at = 96_000L, hits = hits), scan(at = 96_400L, hits = hits)))
        assertFalse(confirmWith(gridNames, scan(at = 99_000L, hits = hits), scan(at = 99_000L, hits = hits)))
        assertFalse(
            confirmWith(gridNames, scan(at = 99_000L, hits = hits, headerAmbiguous = true), scan(at = 99_400L, hits = hits)),
        )
        // A duplicated heading stays fatal on a wide grid as well.
        val headings = hits + hit("Brawlers (107/107)", 72, 38, 400, 90) + hit("Brawlers (107/107)", 150, 40, 470, 88)
        assertFalse(confirmWith(gridNames, scan(at = 99_000L, hits = headings), scan(at = 99_400L, hits = headings)))
    }

    @Test fun multiColumnNamesAreStillExactWholeMatches() {
        // A superstring in a second column is not the identity, so that row keeps only "Rosa".
        val rows = listOf(
            hit("Colt", 60, 180, 240, 220),
            hit("Shelly", 520, 180, 700, 220),
            hit("Rosa", 60, 340, 240, 380),
            hit("Barleyx", 520, 340, 700, 380),
            hit("Bo", 60, 500, 240, 535),
            hit("El Primo", 520, 500, 760, 535),
        )
        val hits = rows + sortHit()
        assertTrue(confirmWith(gridNames, scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = hits)))

        val unknownOnly = rows.filterNot { it.text == "Rosa" }
        val other = unknownOnly + sortHit()
        assertFalse(confirmWith(gridNames, scan(at = 99_000L, hits = other), scan(at = 99_400L, hits = other)))
    }

    @Test fun twoKnownColumnsStillCoverOnlyTwoDistinctIdentities() {
        // Only two rows are fully covered, so the page has fewer than three distinct identities
        // even though four boxes are present.
        val rows = listOf(
            hit("Colt", 60, 180, 240, 220),
            hit("Shelly", 520, 180, 700, 220),
            hit("Rosa", 60, 340, 240, 380),
            hit("Barley", 520, 340, 700, 380),
        )
        val hits = rows + sortHit()
        assertFalse(confirmWith(gridNames, scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = hits)))
    }

    @Test fun oneIdentityReusedForTwoCompleteRowsNeverCoversThePage() {
        // Three covered rows, but only one distinct identity: repeating it is never allowed.
        val rows = listOf(
            hit("Colt", 60, 180, 240, 220),
            hit("Shelly", 520, 180, 700, 220),
            hit("Colt", 60, 340, 240, 380),
            hit("Shelly", 520, 340, 700, 380),
            hit("Colt", 60, 500, 240, 535),
            hit("Shelly", 520, 500, 700, 535),
        )
        val hits = rows + sortHit()
        assertFalse(confirmWith(gridNames, scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = hits)))
    }

    @Test fun unknownAndSuperstringNamesNeverMatch() {
        // No partial match and no fuzzy fallback: only whole normalized known names count.
        val rows = listOf(
            hit("Coltrix", 100, 180, 300, 220),
            hit("Rosa", 100, 340, 300, 380),
            hit("Bo", 100, 500, 300, 535),
        )
        val first = scan(at = 99_000L, hits = rows + sortHit())
        val second = scan(at = 99_400L, hits = rows + sortHit())
        assertFalse(confirm(first, second))
    }

    // ---- sort evidence ------------------------------------------------------------------------

    @Test fun missingSortLabelInEitherFrameFails() {
        val first = scan(at = 99_000L, hits = rowHits() + sortHit())
        val second = scan(at = 99_400L, hits = rowHits())
        assertFalse(confirm(first, second))
        assertFalse(confirm(second, first))
    }

    @Test fun differentSortLabelsAcrossThePairFail() {
        val first = scan(at = 99_000L, hits = rowHits() + sortHit("Rarity"))
        val second = scan(at = 99_400L, hits = rowHits() + sortHit("Rareza"))
        assertFalse(confirm(first, second))
    }

    @Test fun duplicatedSortLabelInOneScanFails() {
        val duplicated = rowHits() + sortHit("Rarity") + hit("Rarity", 700, 5, 820, 30)
        val first = scan(at = 99_000L, hits = duplicated)
        val second = scan(at = 99_400L, hits = duplicated)
        assertFalse(confirm(first, second))
    }

    @Test fun sortWordOutsideTheRegionOfInterestFails() {
        // Left of 40%, right of 75%, below 6% and above the frame are all outside the strip.
        val outside = listOf(
            hit("Rarity", 300, 5, 420, 30),   // x < 40%
            hit("Rarity", 1000, 5, 1120, 30), // x > 75%
            hit("Rarity", 520, 40, 640, 65),  // y > 6%
            hit("Rarity", 520, -5, 640, 20),  // y < 0
        )
        for (sort in outside) {
            val first = scan(at = 99_000L, hits = rowHits() + sort)
            val second = scan(at = 99_400L, hits = rowHits() + sort)
            assertFalse("sort box outside the region of interest must not authorize", confirm(first, second))
        }
    }

    @Test fun sortWordInsideABodyStripIsNotSortEvidence() {
        // A real label sitting in a card-title band proves nothing about the sort strip.
        val rows = rowHits() + sortHit("Rarity", shift = 0).copy(top = 180, bottom = 220)
        val first = scan(at = 99_000L, hits = rows)
        val second = scan(at = 99_400L, hits = rows)
        assertFalse(confirm(first, second))
    }

    @Test fun sortLabelClippingTheRegionBoundaryFails() {
        // A box that starts before 40% or ends after 75%/6% is not fully inside the strip.
        val clipping = listOf(
            hit("Rarity", 500, 5, 640, 30),   // left edge before the 40% mark
            hit("Rarity", 520, 5, 970, 30),   // right edge past the 75% mark
            hit("Rarity", 520, 0, 640, 40),   // bottom edge past the 6% mark
        )
        for (sort in clipping) {
            val first = scan(at = 99_000L, hits = rowHits() + sort)
            val second = scan(at = 99_400L, hits = rowHits() + sort)
            assertFalse("sort box clipping the boundary must not authorize", confirm(first, second))
        }
    }

    @Test fun prefixedSuffixedAndPunctuatedSortTextIsRejected() {
        for (text in listOf(
            "Sort: Rarity", "Rarity!", "Sortieren nach Rarität", "Rarities", "Rare", "Rarity.",
            "(Rarity)", "Rarity asc", "Raritydesc", "rar ity", "sort",
        )) {
            assertFalse("must not be a strict sort label: $text", BrawlerGridContinuation.isSortLabel(text))
        }
        assertFalse(BrawlerGridContinuation.isSortLabel(""))
        assertFalse(BrawlerGridContinuation.isSortLabel("   "))
        assertFalse(BrawlerGridContinuation.isSortLabel("R".repeat(64)))
    }

    // ---- malformed boxes ----------------------------------------------------------------------

    @Test fun invalidReversedAndOutOfBoundsBoxesNeverAuthorize() {
        val invalid = listOf(
            hit("Rarity", 640, 5, 520, 30),    // reversed horizontally
            hit("Rarity", 520, 30, 640, 5),    // reversed vertically
            hit("Rarity", 520, 5, 520, 30),    // zero width
            hit("Rarity", 520, 5, 640, 5),     // zero height
            hit("Rarity", -10, 5, 640, 30),    // negative left
            hit("Rarity", 520, -10, 640, 30),  // negative top
            hit("Rarity", 520, 5, 1400, 30),   // past the frame width
            hit("Rarity", 520, 5, 640, 700),   // past the frame height
        )
        for (sort in invalid) {
            val first = scan(at = 99_000L, hits = rowHits() + sort)
            val second = scan(at = 99_400L, hits = rowHits() + sort)
            assertFalse("malformed box must not authorize", confirm(first, second))
        }
    }

    @Test fun malformedRowBoxesNeverAuthorize() {
        val broken = listOf(
            hit("Colt", 300, 180, 100, 220),   // reversed row box
            hit("Rosa", 340, 100, 300, 380),   // row box outside the frame horizontally
            hit("Bo", 100, 500, 900, 535),     // row box clipped by the 65% strip edge
            hit("Bo", 100, 500, 300, 560),     // row box clipped by the strip bottom
        )
        val first = scan(at = 99_000L, hits = broken + sortHit())
        val second = scan(at = 99_400L, hits = broken + sortHit())
        assertFalse(confirm(first, second))
    }

    // ---- captures, clocks and geometry ---------------------------------------------------------

    @Test fun staleFutureReusedAndReversedCapturesFail() {
        val hits = rowHits() + sortHit()

        // Reused capture: identical timestamps.
        assertFalse(confirm(scan(at = 99_000L, hits = hits), scan(at = 99_000L, hits = hits)))
        // Reversed pair.
        assertFalse(confirm(scan(at = 99_400L, hits = hits), scan(at = 99_000L, hits = hits)))
        // Non-positive timestamps.
        assertFalse(confirm(scan(at = 0L, hits = hits), scan(at = 99_400L, hits = hits)))
        assertFalse(confirm(scan(at = -5L, hits = hits), scan(at = 99_400L, hits = hits)))
        // Stale: older than the 3000 ms budget.
        assertFalse(confirm(scan(at = 96_000L, hits = hits), scan(at = 96_400L, hits = hits)))
        // Stale relative to its OWN clock, not just relative to the default one.
        assertFalse(BrawlerGridContinuation.confirm(scan(at = 90_000L, hits = hits), scan(at = 90_400L, hits = hits), names, 94_000L))
        // Exactly on the 3000 ms budget it is still fresh, so the boundary is not off by one.
        assertTrue(BrawlerGridContinuation.confirm(scan(at = 96_400L, hits = hits), scan(at = 96_800L, hits = hits), names, 99_400L))
        // Too wide a gap: older than the 4000 ms gap budget.
        assertFalse(confirm(scan(at = 95_000L, hits = hits), scan(at = 99_400L, hits = hits)))
        // Future-dated capture.
        assertFalse(confirm(scan(at = 99_000L, hits = hits), scan(at = 100_001L, hits = hits)))
        assertFalse(BrawlerGridContinuation.confirm(scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = hits), names, 98_000L))
        // Negative clock.
        assertFalse(BrawlerGridContinuation.confirm(scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = hits), names, -1L))
        // Empty known-name vocabulary.
        assertFalse(BrawlerGridContinuation.confirm(scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = hits), emptySet(), now))
        assertFalse(BrawlerGridContinuation.confirm(scan(at = 99_000L, hits = hits), scan(at = 99_400L, hits = hits), setOf("   "), now))
    }

    @Test fun changingDimensionsBetweenTheCapturesFail() {
        val rows = listOf(
            hit("Colt", 200, 270, 400, 330),
            hit("Rosa", 200, 510, 400, 570),
            hit("Bo", 200, 750, 400, 800),
        )
        val sort = hit("Rarity", 800, 10, 950, 45)
        val first = scan(1280, 591, 99_000L, rowHits() + sortHit())
        // Same rows, second capture at a different resolution: pixel comparison is invalid.
        val second = scan(1920, 886, 99_400L, rows + sort)
        assertFalse(confirm(first, second))
        assertFalse(confirm(second, first))
    }

    @Test fun degenerateAndUnsupportedFramesFail() {
        val hits = rowHits() + sortHit()
        assertFalse(confirm(scan(0, 0, 99_000L, emptyList()), scan(0, 0, 99_400L, emptyList())))
        assertFalse(confirm(scan(640, 600, 99_000L, hits), scan(640, 600, 99_400L, hits)))  // ratio 1.07
        assertFalse(confirm(scan(1280, 400, 99_000L, hits), scan(1280, 400, 99_400L, hits)))  // ratio 3.2
        assertFalse(confirm(scan(320, 150, 99_000L, hits), scan(320, 150, 99_400L, hits)))  // below the size floor
        assertFalse(confirm(scan(5000, 2200, 99_000L, hits), scan(5000, 2200, 99_400L, hits))) // above the ceiling
    }

    @Test fun regionOfInterestAndStripGeometryAreExact() {
        val small = BrawlerGridContinuation.sortRegion(1280, 591)
        assertTrue(small.left == 512 && small.top == 0 && small.right == 960 && small.bottom == 35)
        val control = BrawlerGridContinuation.sortRegion(1920, 886)
        assertTrue(control.left == 768 && control.top == 0 && control.right == 1440 && control.bottom == 53)

        // The three body bands are exactly the plan's card-title strips; the helper must not assume
        // the plan's total tile count, so the bands are read from the plan itself.
        for ((w, h) in listOf(1280 to 591, 1920 to 886)) {
            val plan = BrawlerGridOcrRegions.plan(w, h)!!
            assertTrue("plan must carry at least the three title strips", plan.tiles.size >= 4)
            for (offset in 0..2) {
                val strip = plan.tiles[1 + offset].source
                assertTrue("strip $offset must have positive area", strip.width > 0 && strip.height > 0)
            }
        }
    }

    @Test fun appendedPagingTileDoesNotShiftTheBandsTheHelperReads() {
        // The optional paging plan keeps the original four tiles byte-geometrically identical and
        // only APPENDS the sort tile, so the heading band and the three title strips this helper
        // reads must be unchanged no matter how many tiles the plan carries in total.
        for ((w, h) in listOf(1280 to 591, 1920 to 886)) {
            val base = BrawlerGridOcrRegions.plan(w, h)!!
            val paging = BrawlerGridOcrRegions.pagingPlan(w, h)!!
            assertTrue("paging plan must not shrink", paging.tiles.size > base.tiles.size)
            for (index in 0..3) {
                assertTrue(
                    "tile $index must be identical in both plans",
                    paging.tiles[index].source == base.tiles[index].source &&
                        paging.tiles[index].destination == base.tiles[index].destination,
                )
            }
        }
    }
}