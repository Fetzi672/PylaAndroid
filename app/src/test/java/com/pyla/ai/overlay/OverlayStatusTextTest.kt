package com.pyla.ai.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayStatusTextTest {
    /** Deterministic stub: every character is 10px wide. */
    private val measure: (String) -> Float = { it.length * 10f }

    @Test fun engineStateMapping() {
        assertEquals("Stopped", OverlayStatusText.engineState(false, false))
        assertEquals("Running", OverlayStatusText.engineState(true, false))
        assertEquals("Paused", OverlayStatusText.engineState(true, true))
        // Paused implies running; a paused flag without running stays Stopped.
        assertEquals("Stopped", OverlayStatusText.engineState(false, true))
    }

    @Test fun compositionContainsAllFourSections() {
        val line = OverlayStatusText.compose(
            "Running", "State: Lobby", "Check: Verified", "Attacking nearest",
        )
        assertEquals("PYLA Running | State: Lobby | Check: Verified | Last: Attacking nearest", line)
        assertTrue(line.startsWith("PYLA"))
    }

    @Test fun blankActionFallsBackToPlaceholder() {
        val line = OverlayStatusText.compose("Stopped", "State: Unknown", "Check: Stopped", "   ")
        assertTrue(line.endsWith("Last: ${OverlayStatusText.NO_ACTION}"))
    }

    @Test fun blankStateAndCheckFallBackToDefaults() {
        val line = OverlayStatusText.compose("Stopped", "", "", "")
        assertTrue(line.contains("State: Unknown"))
        assertTrue(line.contains("Check: Stopped"))
    }

    @Test fun fitLeavesShortTextUntouched() {
        assertEquals("PYLA", OverlayStatusText.fit("PYLA", 100f, measure))
    }

    @Test fun fitTruncatesWithEllipsisWithinWidth() {
        val text = "PYLA Running | State: Lobby | Check: Verified | Last: Attacking"
        val fitted = OverlayStatusText.fit(text, 100f, measure)
        assertTrue(fitted.endsWith(OverlayStatusText.ELLIPSIS))
        assertTrue(measure(fitted) <= 100f)
        assertTrue(fitted.length < text.length)
    }

    @Test fun fitNeverWrapsAndHandlesTinyWidth() {
        assertEquals("", OverlayStatusText.fit("PYLA Running", 5f, measure))
        assertEquals("", OverlayStatusText.fit("PYLA", 0f, measure))
    }

    @Test fun stateColorsAreDistinctPerState() {
        val stopped = OverlayStatusText.stateColor(false, false)
        val running = OverlayStatusText.stateColor(true, false)
        val paused = OverlayStatusText.stateColor(true, true)
        assertEquals(3, setOf(stopped, running, paused).size)
        assertEquals(0xFF4CD964.toInt(), running)
        assertEquals(0xFF9C8CFF.toInt(), paused)
    }
}
