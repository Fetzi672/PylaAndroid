package com.pyla.ai.overlay

import org.junit.Assert.*
import org.junit.Test

class OverlayFieldFittingTest {
    private val measure: (String) -> Float = { it.length.toFloat() }

    @Test fun longValuesKeepAllFourLabelsInOneLine() {
        val parts = OverlayStatusText.parts("Running", "State: Brawler detail selection", "Check: Waiting for verification", "Waiting for an unusually long animation before attacking")
        val rendered = OverlayStatusText.fitFields(parts, 72f, measure)
        assertTrue(rendered.length <= 72)
        assertTrue(rendered.startsWith("PYLA Running"))
        assertTrue(rendered.contains("State:"))
        assertTrue(rendered.contains("Check:"))
        assertTrue(rendered.contains("Last:"))
        assertEquals(3, rendered.count { it == '|' })
    }

    @Test fun newlinesAreNormalizedWithoutChangingLiveMeaning() {
        val rendered = OverlayStatusText.compose("Paused", "State: Match", "Check: Verified", "Attack\n  target\t now")
        assertTrue(rendered.endsWith("Last: Attack target now"))
        assertFalse(rendered.contains('\n'))
        assertFalse(rendered.contains('\t'))
    }

    @Test fun roomyDisplayDoesNotTruncate() {
        val parts = OverlayStatusText.parts("Stopped", "State: Unknown", "Check: Stopped", "No active action")
        assertEquals(parts.joinToString(" | "), OverlayStatusText.fitFields(parts, 1000f, measure))
    }

    @Test fun exceptionallyNarrowDisplayStaysWithinItsSingleLineBudget() {
        val parts = OverlayStatusText.parts("Running", "State: Match", "Check: Verified", "Attack")
        for (width in listOf(0f, 1f, 8f, 20f)) {
            assertTrue(OverlayStatusText.fitFields(parts, width, measure).length <= width)
        }
    }
}
