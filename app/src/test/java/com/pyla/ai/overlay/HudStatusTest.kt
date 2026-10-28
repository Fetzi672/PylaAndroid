package com.pyla.ai.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HudStatusTest {
    @Test fun stoppedWinsOverEveryOtherFact() {
        // A stale error from a previous run must not resurrect ERROR on a stopped engine.
        val status = HudStatusResolver.resolve(
            running = false, paused = true, verified = true, stateLabel = "Match", hasError = true,
        )
        assertEquals(HudStatusResolver.STOPPED, status.label)
        assertEquals(HudTone.MUTED, status.tone)
    }

    @Test fun pausedDescribesReleasedInputs() {
        val status = HudStatusResolver.resolve(true, paused = true, verified = true, stateLabel = "Match", hasError = false)
        assertEquals(HudStatusResolver.PAUSED, status.label)
        assertEquals(HudTone.INFO, status.tone)
    }

    @Test fun engineErrorIsReportedWhileRunning() {
        val status = HudStatusResolver.resolve(true, paused = false, verified = true, stateLabel = "Match", hasError = true)
        assertEquals(HudStatusResolver.ERROR, status.label)
        assertEquals(HudTone.BAD, status.tone)
    }

    @Test fun unverifiedEvidenceReadsVerifyingNeverMatch() {
        val status = HudStatusResolver.resolve(true, paused = false, verified = false, stateLabel = "Match", hasError = false)
        assertEquals(HudStatusResolver.VERIFYING, status.label)
        assertEquals(HudTone.WARN, status.tone)
    }

    @Test fun verifiedMatchUsesTheApprovedGreenMatchLabel() {
        val status = HudStatusResolver.resolve(true, paused = false, verified = true, stateLabel = "Match", hasError = false)
        assertEquals("MATCH", status.label)
        assertEquals(HudTone.GOOD, status.tone)
        assertTrue(status.isVerifiedMatch)
    }

    @Test fun verifiedNonMatchStatesShowTheirOwnShortLabel() {
        val lobby = HudStatusResolver.resolve(true, paused = false, verified = true, stateLabel = "Lobby", hasError = false)
        assertEquals("LOBBY", lobby.label)
        assertEquals(HudTone.GOOD, lobby.tone)
        assertNotEquals("MATCH", lobby.label)
    }

    @Test fun longStateLabelsAreBoundedAndBlankFallsBackToVerifying() {
        val long = HudStatusResolver.shortState("Brawler detail")
        assertEquals(HudStatusResolver.STATUS_MAX_LENGTH, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals(HudStatusResolver.VERIFYING, HudStatusResolver.shortState("   "))
        assertEquals("LOBBY", HudStatusResolver.shortState("Lobby"))
    }

    @Test fun accessibilityDescriptionSpellsOutTheMeasuredValues() {
        val status = HudStatus(HudStatus.MATCH, HudTone.GOOD)
        assertEquals("Pyla HUD, match, loop 28 ms, 35.7 IPS", HudStatusResolver.description(status, "28 ms", "35.7"))
        assertEquals(
            "Pyla HUD, stopped, loop —, — IPS",
            HudStatusResolver.description(HudStatus(HudStatusResolver.STOPPED, HudTone.MUTED), HudMetrics.EM_DASH, HudMetrics.EM_DASH),
        )
    }
}
