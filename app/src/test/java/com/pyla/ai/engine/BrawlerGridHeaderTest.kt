package com.pyla.ai.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for [BrawlerGridHeader] only: the exact heading shape the German brawler grid shows,
 * the preserved bare labels, and the safety controls that keep a look-alike string from being read
 * as the grid.
 */
class BrawlerGridHeaderTest {

    private fun header(text: String) = BrawlerGridHeader.isHeader(text)

    private fun assertHeaders(vararg texts: String) = texts.forEach { assertTrue("expected header: <$it>", header(it)) }

    private fun assertNotHeaders(vararg texts: String) = texts.forEach { assertFalse("expected NOT header: <$it>", header(it)) }

    // ---- exact counted heading as captured in the reported screenshot --------------------------

    @Test fun screenshotExactGermanCountedHeadingIsHeader() {
        assertHeaders("BRAWLER (107/107)")
    }

    @Test fun countedHeadingIsCaseInsensitive() {
        assertHeaders("brawler (107/107)", "Brawler (107/107)", "BrAwLeR (107/107)")
        assertHeaders("BRAWLERS (12/40)", "LOTTATORI (3/9)")
    }

    @Test fun countedHeadingAcceptsParentheselessAndSpacedForms() {
        assertHeaders("BRAWLER 107/107", "BRAWLER(107/107)", "BRAWLER (107/107)")
        assertHeaders("BRAWLER  (  107 / 107  )", "Brawler\t(107/107)")
    }

    @Test fun countedHeadingAcceptsPartialCounts() {
        assertHeaders("BRAWLER (0/1)", "BRAWLER (1/1)", "BRAWLER (0/107)", "BRAWLER (107/107)")
    }

    // ---- preserved bare labels ---------------------------------------------------------------

    @Test fun bareLabelsRemainAccepted() {
        assertHeaders("brawlers", "brawler", "lottatori")
        assertHeaders("BRAWLERS", "Brawler", "BRAWLER", "LoTtAtOrI")
        assertHeaders("  brawler  ", "\tbrawlers\n")
    }

    // ---- rejected prefixes, suffixes, descriptions --------------------------------------------

    @Test fun prefixesAndSuffixesAreRejected() {
        assertNotHeaders(
            "SUPER BRAWLERS", "MY BRAWLER", "BRAWLERS!", "BRAWLER.", "BRAWLER:",
            "BRAWLER (107/107) OK", "GO TO BRAWLER", "BRAWLER (107/107) x",
            "BRAWLERS UND MEHR", "der BRAWLER",
        )
    }

    @Test fun descriptiveSentencesAreRejected() {
        assertNotHeaders(
            "Tap a brawler to select", "Choose your brawler for this match",
            "Brawler (107/107) available now", "Brawler list of owned characters",
        )
    }

    @Test fun brawlerCardIdentitiesAreNotHeaders() {
        assertNotHeaders("AMY", "FRANK", "SHELLY", "El Primo", "Bo", "8-BIT")
    }

    @Test fun nonHeadingLabelsAreRejected() {
        assertNotHeaders("SHOP", "NEWS", "BRAWL STARS", "BRAWLERSS", "BBRAWLER", "brawler_", "lottator")
    }

    // ---- counter safety ----------------------------------------------------------------------

    @Test fun invalidCounterArithmeticIsRejected() {
        assertNotHeaders(
            "BRAWLER (10/7)", "BRAWLER (108/107)", "BRAWLER (9999/1)",
            "BRAWLER (0/0)", "BRAWLER 0/0",
        )
    }

    @Test fun negativeAndMalformedCountersAreRejected() {
        assertNotHeaders(
            "BRAWLER (-1/107)", "BRAWLER (107/-1)", "BRAWLER (-107/-107)",
            "BRAWLER (/107)", "BRAWLER (107/)", "BRAWLER (/)",
            "BRAWLER ()", "BRAWLER (107/107 108)",
        )
    }

    @Test fun overflowingCounterWidthIsRejected() {
        assertNotHeaders("BRAWLER (12345/12345)", "BRAWLER 99999/99999", "BRAWLER (107/1234567)")
    }

    @Test fun concatenatedDigitsWithoutSlashAreRejected() {
        assertNotHeaders("BRAWLER107107", "BRAWLER 107107", "BRAWLERS (107)107")
    }

    // ---- character-level safety controls ------------------------------------------------------

    @Test fun counterWithoutParenthesesRequiresLabelSeparator() {
        assertNotHeaders("BRAWLER107/107", "BRAWLERS1/2", "LOTTATORI0/107")
        assertHeaders("BRAWLER(107/107)", "BRAWLER 107/107")
    }

    @Test fun unicodeLookAlikeDigitsAreRejected() {
        assertNotHeaders(
            "BRAWLER (١٠٧/١٠٧)",
            "BRAWLER (107/１０７)",
            "BRAWLER (۱۰۷/۱۰۷)",
        )
    }

    @Test fun malformedParenthesesAndSlashAreRejected() {
        assertNotHeaders(
            "BRAWLER (107/107", "BRAWLER 107/107)", "BRAWLER ((107/107))",
            "BRAWLER (107//107)", "BRAWLER (107\\107)", "BRAWLER 107107/",
            "BRAWLER (107,107)",
        )
    }

    @Test fun blankAndEmptyInputIsRejected() {
        assertNotHeaders("", "   ", "\t\n", "()", "(/)")
    }

    @Test fun oversizedInputIsRejectedBeforePatternEvaluation() {
        assertNotHeaders(
            "BRAWLER " + " ".repeat(200) + "(107/107)",
            "BRAWLER (107/107)" + "!".repeat(4096),
            "B".repeat(100_000) + " (107/107)",
            " ".repeat(5000) + "BRAWLER (107/107)",
        )
    }

    // ---- caller-side uniqueness contract -------------------------------------------------------

    @Test fun duplicateHeadingsAreCallerSideAndBothRecognized() {
        // Recognition is per string: this helper must not silently de-duplicate, because a frame
        // showing the heading twice is rejected by the caller's uniqueness rule instead.
        assertTrue(header("BRAWLER (107/107)"))
        assertTrue(header("BRAWLER (107/107)"))
    }

    // ---- exact heading word (no card-name normalizer, no substring, no reconstruction) ---------

    private fun label(raw: String) = BrawlerGridHeader.isLabel(raw)

    private fun assertLabels(vararg raws: String) = raws.forEach { assertTrue("expected label: <$it>", label(it)) }

    private fun assertNotLabels(vararg raws: String) =
        raws.forEach { assertFalse("expected NOT label: <$it>", label(it)) }

    @Test fun headingWordsAreAcceptedAsExactTrimmedElements() {
        assertLabels("brawler", "brawlers", "lottatori")
        assertLabels("Brawler", "BRAWLER", "Brawlers", "LOTTATORI", "LoTtAtOrI")
        assertLabels("  brawler  ", "\tbrawlers\n", " brawler ")
    }

    @Test fun countedOrDecoratedLinesAreNotLabels() {
        // The count is display-only decoration on the LINE, never part of the label word itself,
        // and the whole counted string stays the old helper's job.
        assertNotLabels(
            "BRAWLER (107/107)", "BRAWLER 107/107", "Brawler (0/107)", "Brawler (107/107) OK",
        )
    }

    @Test fun prefixesSuffixesAndNearMissesAreNotLabels() {
        assertNotLabels(
            "SUPER BRAWLER", "MY BRAWLER", "der Brawler", "Brawler!", "Brawler.", "Brawler:",
            "Brawlerss", "BBrawler", "brawler_", "Brawler/S", "Brawler1", "lottator",
        )
    }

    @Test fun labelComparisonIsCaseFoldedNotCaseSensitive() {
        // Case folding is Locale.ROOT, so a differently cased plural is the same fixed word and is
        // accepted - it is not a look-alike, and no substring or fuzzy path is involved.
        assertLabels("BRAWLERs", "brawlerS", "BrawlerS")
    }

    @Test fun cardNamesAreNotLabels() {
        assertNotLabels("AMY", "FRANK", "SHELLY", "El Primo", "Bo", "8-BIT", "brawlerella")
    }

    @Test fun blankOversizedAndUnicodeLabelsAreRejected() {
        assertNotLabels("", "   ", "\t\n", "B".repeat(100_000), " ".repeat(5000) + "brawler")
        // Case folding is Locale.ROOT only: a Turkish dotless/dotted pair is not the same word.
        assertNotLabels("BRAWLERİ")
    }

    @Test fun duplicateLabelElementsAreBothRecognizedForCallerUniqueness() {
        // Two heading words in one frame must both survive so the caller's uniqueMatch rejects
        // them as ambiguous; this helper must not deduplicate them.
        assertTrue(label("Brawler"))
        assertTrue(label("Brawler"))
    }

    // ---- bounded numeric decoration permitted beside the heading word -------------------------

    private fun permits(vararg elements: String) =
        BrawlerGridHeader.permitsLabelElements(elements.toList())

    @Test fun headingWordWithCounterDecorationIsPermitted() {
        assertTrue(permits("Brawler"))
        assertTrue(permits("Brawler", " ", "(", "107", "/", "107", ")"))
        assertTrue(permits("Brawlers", "(107/107)"))
        assertTrue(permits("LOTTATORI", "0", "/", "107"))
        // ML Kit commonly splits a heading into word, spacing and counter elements.
        assertTrue(permits("Brawler", "", "  ", "\t", "(0/107)"))
    }

    @Test fun decorationWithoutAnyHeadingWordIsNotAPermittedHeadingLine() {
        // A pure counter line is not the grid heading, so no element of it can be emitted.
        assertFalse(permits("(107/107)"))
        assertFalse(permits("107", "/", "107"))
        assertFalse(permits("", "  ", "(0/107)"))
    }

    @Test fun anyOtherWordRejectsTheWholeHeaderLine() {
        // A description, a card name or an unknown heading word beside the label invalidates the
        // line, so none of its elements can be read as the grid heading.
        assertFalse(permits("Brawler", "available", "now"))
        assertFalse(permits("Brawler", "SHOP"))
        assertFalse(permits("Brawler", "FRANK"))
        assertFalse(permits("Shop", "Brawler"))
        assertFalse(permits("Brawler!", "107"))
        assertFalse(permits("Super", "Brawler"))
    }

    @Test fun nonAsciiOrOversizedDecorationIsRejected() {
        // Unicode look-alike digits are not the game's ASCII counter, and unbounded noise is
        // rejected before it is treated as decoration.
        assertFalse(permits("Brawler", "١٠٧/١٠٧"))
        assertFalse(permits("Brawler", "107/107", "×"))
        assertFalse(permits("Brawler", " ".repeat(40)))
        assertFalse(permits("Brawler", "1".repeat(64)))
    }

    @Test fun emptyElementListIsRejected() {
        assertFalse(permits())
        assertFalse(BrawlerGridHeader.permitsLabelElements(emptyList()))
    }

    @Test fun numericDecorationElementCountIsBounded() {
        assertTrue(BrawlerGridHeader.permitsLabelElements(listOf("BRAWLER") + List(15) { "1" }))
        assertFalse(BrawlerGridHeader.permitsLabelElements(listOf("BRAWLER") + List(16) { "1" }))
    }

    @Test fun wordPathAndCountedGrammarStayIndependent() {
        // Both paths must exist and stay separate: a counted heading line is still the old
        // strict grammar's business, while only the exact word is the new label's business.
        assertFalse(header("BRAWLER (10/7)"))  // invalid arithmetic stays rejected
        assertFalse(header("BRAWLER 0/0"))
        assertTrue(permits("Brawler", "(10/7)"))      // count is display-only in the word path
        assertTrue(label("Brawler"))
    }
}