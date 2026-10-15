package com.pyla.ai.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the production marker constant to the wire format the tests and writer rely on.
 * Compiled/run only in the post-fix (green) harness: the pre-fix production parser has no
 * such symbol, which is exactly the capability the compatibility change adds.
 */
class EscapeMarkerPinTest {

    @Test
    fun productionMarkerMatchesWireFormat() {
        assertEquals("pyla:esc", TomlParser.ESCAPE_MARKER)
    }

    @Test
    fun writerEmitsMarkerOnlyWhenEscaping() {
        assertTrue(formatTomlValue("a\\b").endsWith("# ${TomlParser.ESCAPE_MARKER}"))
        assertTrue(formatTomlValue("plain.pyla").indexOf('#') < 0)
        assertEquals("42", formatTomlValue("42"))
    }
}
