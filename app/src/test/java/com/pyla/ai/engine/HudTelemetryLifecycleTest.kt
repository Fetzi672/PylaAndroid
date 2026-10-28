package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the honest-reset rule of the HUD telemetry facade: a pause, resume or stop advances the
 * lifecycle generation, so work that began before the boundary cannot publish afterwards.
 */
class HudTelemetryLifecycleTest {
    private fun recorder() = HudTelemetryRecorder(sampleMaxAgeMs = 2_000L, rateWindowMs = 3_000L)

    @Test fun pauseBoundaryDiscardsInFlightSample() {
        val r = recorder()
        val before = r.generation()
        r.recordLoop(before, nowMs = 1_000L, durationMs = 12.0)
        assertEquals(12.0, r.snapshot(1_000L).loopMs!!, 1e-9)
        r.reset() // pause
        // The iteration that started before the pause completes afterwards.
        r.recordLoop(before, nowMs = 1_100L, durationMs = 40.0)
        assertNull(r.snapshot(1_100L).loopMs)
    }

    @Test fun resumeAcceptsNewGenerationSamples() {
        val r = recorder()
        r.reset()
        val current = r.generation()
        r.recordLoop(current, nowMs = 2_000L, durationMs = 9.5)
        assertEquals(9.5, r.snapshot(2_000L).loopMs!!, 1e-9)
    }

    @Test fun stopBoundaryClearsRateAndStages() {
        val r = recorder()
        val g = r.generation()
        r.recordGameplayFrame(g, completedAtMs = 1_000L, detectMs = 5.0, frameAgeMs = 20L)
        r.recordGameplayFrame(g, completedAtMs = 1_100L, detectMs = 5.0, frameAgeMs = 20L)
        assertNotNull(r.snapshot(1_100L).ips)
        r.reset() // stop
        val after = r.snapshot(1_200L)
        assertNull(after.ips)
        assertNull(after.detectMs)
        assertNull(after.frameAgeMs)
    }

    @Test fun implausibleSamplesAreRefusedNotClamped() {
        val r = recorder()
        val g = r.generation()
        r.recordLoop(g, nowMs = 1_000L, durationMs = 8.0)
        r.recordLoop(g, nowMs = 1_200L, durationMs = Double.NaN)
        r.recordLoop(g, nowMs = 1_300L, durationMs = -1.0)
        r.recordLoop(g, nowMs = 1_400L, durationMs = 120_000.0)
        // Refused writes leave the last valid measurement (still inside its freshness window).
        assertEquals(8.0, r.snapshot(1_450L).loopMs!!, 1e-9)
    }

    @Test fun staleSampleIsNotShownAsCurrent() {
        val r = recorder()
        val g = r.generation()
        r.recordLoop(g, nowMs = 1_000L, durationMs = 8.0)
        assertNull(r.snapshot(4_000L).loopMs)
    }
}
