package com.pyla.ai.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the escaping contract between the settings value writer ([formatTomlValue]) and the
 * parser that reads the files back ([TomlParser] via [TomlLite]/[PylaConfig]): strings
 * containing quotes, backslashes, newlines, CR, tabs, the comment marker and '$' must
 * survive a write -> parse cycle unchanged, while simple strings, numerics and booleans
 * keep their historical bare formatting. The parser-level cases use the pre-existing
 * internal [TomlParser] interface and were RED on base a45e0d15 (no escape support).
 */
class TomlValueRoundTripTest {

    /** Wire format of the writer's opt-in escape marker (space + '#' + space + token). */
    private val marker = "pyla:esc"

    private fun roundTrip(value: String): String? {
        val text = "current_playstyle = ${formatTomlValue(value)}\n"
        return TomlParser.parse(text)["current_playstyle"]?.toString()
    }

    private fun parseQuoted(text: String): String? = TomlParser.parse("k = $text")["k"]?.toString()

    // --- preservation of existing simple values (historical bare format) ---

    @Test
    fun plainStringStaysQuotedAndRoundTrips() {
        assertEquals("aggressive.pyla", roundTrip("aggressive.pyla"))
    }

    @Test
    fun integerStaysBareAndRoundTrips() {
        assertEquals(42, TomlParser.parse("k = ${formatTomlValue("42")}")["k"])
        assertEquals("42", roundTrip("42"))
    }

    @Test
    fun floatStaysBareAndRoundTrips() {
        assertEquals(1.5, TomlParser.parse("k = ${formatTomlValue("1.5")}")["k"])
        assertEquals("1.5", roundTrip("1.5"))
    }

    @Test
    fun booleanWordsStayBareAndRoundTrip() {
        assertEquals(true, TomlParser.parse("k = ${formatTomlValue("true")}")["k"])
        assertEquals(false, TomlParser.parse("k = ${formatTomlValue("false")}")["k"])
        assertEquals("true", roundTrip("true"))
        assertEquals("false", roundTrip("false"))
    }

    // --- required round-trip character classes ---

    @Test
    fun quotesRoundTrip() {
        assertEquals("he said \"hi\"", roundTrip("he said \"hi\""))
        assertEquals("a\"b", roundTrip("a\"b"))
        assertEquals("\"leading", roundTrip("\"leading"))
        assertEquals("trailing\"", roundTrip("trailing\""))
    }

    @Test
    fun backslashesRoundTrip() {
        assertEquals("a\\b", roundTrip("a\\b"))
        assertEquals("ends-with-backslash\\", roundTrip("ends-with-backslash\\"))
        assertEquals("mix\\\"ed\\n", roundTrip("mix\\\"ed\\n"))
    }

    @Test
    fun newlinesCarriageReturnsAndTabsRoundTrip() {
        assertEquals("line1\nline2", roundTrip("line1\nline2"))
        assertEquals("a\r\nb", roundTrip("a\r\nb"))
        assertEquals("col1\tcol2", roundTrip("col1\tcol2"))
    }

    @Test
    fun commentMarkerRoundTrips() {
        assertEquals("a#b", roundTrip("a#b"))
        assertEquals("a#b # c", roundTrip("a#b # c"))
        assertEquals("a\"b#c", roundTrip("a\"b#c"))
    }

    @Test
    fun dollarRoundTrips() {
        assertEquals("\$HOME", roundTrip("\$HOME"))
        assertEquals("\${expr}\$var", roundTrip("\${expr}\$var"))
    }

    @Test
    fun emptyStringRoundTrips() {
        assertEquals("", roundTrip(""))
    }

    @Test
    fun combinedSpecialCharactersRoundTrip() {
        val nasty = "say \"hi\"\\\n#1 ${'$'}{x}\tend"
        assertEquals(nasty, roundTrip(nasty))
    }

    // --- parser-level: escapes decode only for writer-marked (`# pyla:esc`) values ---

    private fun parseMarked(body: String): String? =
        TomlParser.parse("k = \"$body\" # $marker")["k"]?.toString()

    @Test
    fun parserDecodesEscapedQuoteBeforeCommentMarker() {
        assertEquals("a\"b#c", parseMarked("a\\\"b#c"))
    }

    @Test
    fun parserDecodesEscapedNewline() {
        assertEquals("a\nb", parseMarked("a\\nb"))
    }

    @Test
    fun parserDecodesEscapedBackslash() {
        assertEquals("a\\b", parseMarked("a\\\\b"))
    }

    @Test
    fun parserKeepsUnknownEscapesVerbatim() {
        // Lenient: unknown sequences stay literal so pre-existing configs stay loadable.
        assertEquals("a\\qb", parseMarked("a\\qb"))
        assertEquals("a\\qb", parseQuoted("\"a\\qb\""))
    }

    @Test
    fun parserKeepsPlainQuotedStringsUnchanged() {
        assertEquals("universal_smart_v5.pyla", parseQuoted("\"universal_smart_v5.pyla\""))
        // A plain string carries no escapes and therefore no marker.
        assertEquals("universal_smart_v5.pyla", parseMarked("universal_smart_v5.pyla"))
    }

    // --- regression: unmarked (pre-existing) values keep the historical literal meaning ---

    @Test
    fun unmarkedEscapesStayLiteral() {
        // Base a45e0d15 returned the quoted body verbatim; that behaviour must survive for
        // every value the writer did not explicitly mark.
        assertEquals("C:\\temp", parseQuoted("\"C:\\temp\""))
        assertEquals("C:\\new", parseQuoted("\"C:\\new\""))
        assertEquals("C:\\backup\\file", parseQuoted("\"C:\\backup\\file\""))
        assertEquals("line\\nnext", parseQuoted("\"line\\nnext\""))
        assertEquals("tab\\tend", parseQuoted("\"tab\\tend\""))
        assertEquals("a\\\\b", parseQuoted("\"a\\\\b\""))
        assertEquals("\\\"quoted\\\"", parseQuoted("\"\\\"quoted\\\"\""))
    }

    @Test
    fun markerLiteralInsideStringIsNotDecoded() {
        // The marker text inside a value must not enable decoding of that value.
        assertEquals("x # pyla:esc", parseQuoted("\"x # pyla:esc\""))
        // A real marker plus the marker text inside the string still decodes correctly.
        assertEquals("a\\ # pyla:esc", parseMarked("a\\\\ # pyla:esc"))
    }

    @Test
    fun commentsOutsideStringsAreStillStripped() {
        val parsed = TomlParser.parse("k = \"v\" # trailing comment\n")
        assertEquals("v", parsed["k"])
        assertTrue(!parsed.containsKey("#"))
        // A non-marker trailing comment must not trigger decoding.
        assertEquals("a\\nb", TomlParser.parse("k = \"a\\nb\" # note\n")["k"])
    }
}
