package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Deterministic contract tests for the HUD telemetry aggregator.
 *
 * Every test drives the production [HudTelemetryRecorder] with explicit monotonic timestamps; no
 * clock, thread, Android class or wall-clock assertion is involved. What is proven here is the
 * aggregation contract (staleness, validity refusal, rate honesty, generation binding, bounded
 * storage) - not Android input delivery or on-device frame timing.
 */
class HudTelemetryTest {

    private lateinit var recorder: HudTelemetryRecorder

    @Before
    fun setUp() {
        recorder = HudTelemetryRecorder()
    }

    @Test
    fun noSamplesReportEveryMetricUnavailable() {
        val snapshot = recorder.snapshot(1_000L)
        assertNull(snapshot.loopMs)
        assertNull(snapshot.ips)
        assertNull(snapshot.detectMs)
        assertNull(snapshot.styleMs)
        assertNull(snapshot.inputMs)
        assertNull(snapshot.frameAgeMs)
        assertNull(snapshot.backend)
        assertNull(snapshot.playstyle)
    }

    @Test
    fun frameStagesArePublishedWhileFreshAndExpireExactlyAtTheBoundary() {
        val cycle = recorder.generation()
        recorder.recordGameplayFrame(
            cycle, 1_000L, detectMs = 7.5, styleMs = 2.5, inputMs = 1.5, frameAgeMs = 40L,
        )

        val fresh = recorder.snapshot(1_000L)
        assertEquals(7.5, fresh.detectMs!!, 1e-9)
        assertEquals(2.5, fresh.styleMs!!, 1e-9)
        assertEquals(1.5, fresh.inputMs!!, 1e-9)
        assertEquals(40L, fresh.frameAgeMs)

        // Boundary is inclusive: the sample is still valid at exactly the max age.
        val atBoundary = recorder.snapshot(1_000L + HudTelemetryRecorder.DEFAULT_SAMPLE_MAX_AGE_MS)
        assertNotNull(atBoundary.detectMs)

        val expired = recorder.snapshot(1_000L + HudTelemetryRecorder.DEFAULT_SAMPLE_MAX_AGE_MS + 1L)
        assertNull(expired.detectMs)
        assertNull(expired.styleMs)
        assertNull(expired.inputMs)
        assertNull(expired.frameAgeMs)
    }

    @Test
    fun loopSampleAlsoExpires() {
        val cycle = recorder.generation()
        recorder.recordLoop(cycle, 2_000L, 12.0)
        assertEquals(12.0, recorder.snapshot(2_100L).loopMs!!, 1e-9)
        assertNull(recorder.snapshot(2_000L + HudTelemetryRecorder.DEFAULT_SAMPLE_MAX_AGE_MS + 1L).loopMs)
    }

    @Test
    fun nonFiniteNegativeAndImplausibleSamplesAreRefusedNotClamped() {
        val cycle = recorder.generation()
        recorder.recordLoop(cycle, 1_000L, Double.NaN)
        recorder.recordLoop(cycle, 1_000L, Double.POSITIVE_INFINITY)
        recorder.recordLoop(cycle, 1_000L, Double.NEGATIVE_INFINITY)
        recorder.recordLoop(cycle, 1_000L, -0.5)
        recorder.recordLoop(cycle, 1_000L, HudTelemetryRecorder.MAX_PLAUSIBLE_STAGE_MS + 1.0)
        assertNull("no refused loop sample may be presented", recorder.snapshot(1_000L).loopMs)

        recorder.recordGameplayFrame(
            cycle, 1_000L, detectMs = Double.NaN, styleMs = -3.0, inputMs = Double.POSITIVE_INFINITY, frameAgeMs = -1L,
        )
        val snapshot = recorder.snapshot(1_000L)
        assertNull(snapshot.detectMs)
        assertNull(snapshot.styleMs)
        assertNull(snapshot.inputMs)
        assertNull(snapshot.frameAgeMs)

        recorder.recordLoop(cycle, 1_000L, 0.0)
        assertEquals(0.0, recorder.snapshot(1_000L).loopMs!!, 1e-9)
    }

    @Test
    fun invalidTimestampsAreRefused() {
        val cycle = recorder.generation()
        recorder.recordLoop(cycle, 0L, 5.0)
        recorder.recordLoop(cycle, -10L, 5.0)
        recorder.recordGameplayFrame(cycle, 0L, detectMs = 5.0)
        assertNull(recorder.snapshot(1_000L).loopMs)
        assertNull(recorder.snapshot(1_000L).detectMs)
        assertNull(recorder.snapshot(1_000L).ips)
        // A non-positive reader clock cannot describe any sample.
        assertEquals(HudTelemetrySnapshot.EMPTY, recorder.snapshot(0L))
        assertEquals(HudTelemetrySnapshot.EMPTY, recorder.snapshot(-1L))
    }

    @Test
    fun futureDatedSampleIsNotPresentedAndBackwardClockFailsSafe() {
        val cycle = recorder.generation()
        recorder.recordGameplayFrame(cycle, 5_000L, detectMs = 9.0, frameAgeMs = 30L)
        val before = recorder.snapshot(4_999L)
        assertNull(before.detectMs)
        assertNull(before.frameAgeMs)
        assertEquals(9.0, recorder.snapshot(5_000L).detectMs!!, 1e-9)
    }

    @Test
    fun twoCompletionsProduceRateOverTheMeasuredSpanNotTheNominalWindow() {
        val cycle = recorder.generation()
        for (t in 1_000L..1_900L step 100L) recorder.recordGameplayFrame(cycle, t)
        // 10 completions 100 ms apart: 9 completed intervals over 900 ms.
        assertEquals(10.0, recorder.snapshot(1_900L).ips!!, 1e-9)
    }

    @Test
    fun singleCompletionIsNotARate() {
        val cycle = recorder.generation()
        recorder.recordGameplayFrame(cycle, 1_000L)
        assertNull(recorder.snapshot(1_000L).ips)
    }

    @Test
    fun rateWindowDropsStaleCompletions() {
        val cycle = recorder.generation()
        recorder.recordGameplayFrame(cycle, 1_000L)
        recorder.recordGameplayFrame(cycle, 1_100L)
        // Long gap (pause/resume without a reset would still be wrong; the window must exclude them).
        recorder.recordGameplayFrame(cycle, 5_000L)
        recorder.recordGameplayFrame(cycle, 5_100L)
        assertEquals(10.0, recorder.snapshot(5_100L).ips!!, 1e-9)

        // Everything outside the trailing window is unavailable, not a stale leftover rate.
        assertNull(recorder.snapshot(9_000L).ips)
    }

    @Test
    fun latencyAndCadenceAreIndependent() {
        val cycle = recorder.generation()
        for (t in 1_000L..1_900L step 100L) {
            recorder.recordLoop(cycle, t, 8.0)
            recorder.recordGameplayFrame(cycle, t, detectMs = 8.0)
        }
        val snapshot = recorder.snapshot(1_900L)
        assertEquals(8.0, snapshot.loopMs!!, 1e-9)
        assertEquals(8.0, snapshot.detectMs!!, 1e-9)
        assertEquals(10.0, snapshot.ips!!, 1e-9)
        // IPS is not a reciprocal of the measured latency.
        assertNotEquals(1_000.0 / 8.0, snapshot.ips!!, 0.001)
    }

    @Test
    fun resetClearsEverything() {
        val cycle = recorder.generation()
        recorder.recordLoop(cycle, 1_000L, 6.0)
        recorder.recordGameplayFrame(cycle, 1_000L, detectMs = 6.0, styleMs = 1.0, inputMs = 0.5, frameAgeMs = 20L)
        recorder.recordGameplayFrame(cycle, 1_100L, detectMs = 6.0)
        recorder.recordPlaystyle(cycle, "Bull")
        recorder.recordBackend(cycle, "NNAPI")

        recorder.reset()

        val snapshot = recorder.snapshot(1_100L)
        assertEquals(HudTelemetrySnapshot.EMPTY, snapshot)
    }

    @Test
    fun inFlightOldGenerationCannotPublishIntoTheNewRun() {
        val oldCycle = recorder.generation()
        // A frame started in the old run is still executing when Pause/Stop resets telemetry.
        recorder.reset()
        val newCycle = recorder.generation()
        assertNotEquals(oldCycle, newCycle)

        recorder.recordLoop(oldCycle, 2_000L, 99.0)
        recorder.recordGameplayFrame(oldCycle, 2_000L, detectMs = 99.0, styleMs = 99.0, inputMs = 99.0, frameAgeMs = 99L)
        recorder.recordPlaystyle(oldCycle, "stale-style")
        recorder.recordBackend(oldCycle, "stale-backend")
        assertEquals(HudTelemetrySnapshot.EMPTY, recorder.snapshot(2_000L))

        // The new generation's own writes still land.
        recorder.recordLoop(newCycle, 2_000L, 4.0)
        recorder.recordGameplayFrame(newCycle, 2_000L, detectMs = 4.0)
        assertEquals(4.0, recorder.snapshot(2_000L).loopMs!!, 1e-9)
        assertEquals(4.0, recorder.snapshot(2_000L).detectMs!!, 1e-9)
    }

    @Test
    fun pauseAndResumeStartFromAFreshCadenceAndFreshLatency() {
        var cycle = recorder.generation()
        for (t in 1_000L..1_200L step 100L) {
            recorder.recordLoop(cycle, t, 30.0)
            recorder.recordGameplayFrame(cycle, t, detectMs = 30.0)
        }
        assertEquals(10.0, recorder.snapshot(1_200L).ips!!, 1e-9)

        recorder.reset() // pause
        assertNull(recorder.snapshot(1_200L).ips)
        assertNull(recorder.snapshot(1_200L).loopMs)

        cycle = recorder.generation() // resume
        recorder.recordLoop(cycle, 4_000L, 5.0)
        recorder.recordGameplayFrame(cycle, 4_000L, detectMs = 5.0)
        recorder.recordGameplayFrame(cycle, 4_100L, detectMs = 5.0)

        val resumed = recorder.snapshot(4_100L)
        assertEquals(5.0, resumed.loopMs!!, 1e-9)
        assertEquals(10.0, resumed.ips!!, 1e-9)
    }

    @Test
    fun restartDoesNotInheritThePreviousRunsMetrics() {
        var cycle = recorder.generation()
        recorder.recordLoop(cycle, 1_000L, 11.0)
        recorder.recordGameplayFrame(cycle, 1_000L, detectMs = 11.0, styleMs = 3.0, inputMs = 1.0, frameAgeMs = 12L)

        recorder.reset() // stop
        recorder.reset() // start
        cycle = recorder.generation()
        val snapshot = recorder.snapshot(1_100L)
        assertEquals(HudTelemetrySnapshot.EMPTY, snapshot)
        recorder.recordGameplayFrame(cycle, 1_100L, detectMs = 2.0)
        assertNull("style did not run in the new frame", recorder.snapshot(1_100L).styleMs)
        assertNull(recorder.snapshot(1_100L).inputMs)
    }

    @Test
    fun deniedFrameReportsRealDetectionCostButNoZeroForStagesThatDidNotRun() {
        val cycle = recorder.generation()
        // Early-denied frame: detection and capture age are real, no script ran, no input dispatched.
        recorder.recordGameplayFrame(cycle, 1_000L, detectMs = 12.0, styleMs = null, inputMs = null, frameAgeMs = 55L)
        val snapshot = recorder.snapshot(1_000L)
        assertEquals(12.0, snapshot.detectMs!!, 1e-9)
        assertEquals(55L, snapshot.frameAgeMs)
        assertNull("absent style must not be reported as zero cost", snapshot.styleMs)
        assertNull("absent input dispatch must not be reported as zero cost", snapshot.inputMs)
        // The completion is still counted as one processed call (cadence), which is all it claims.
        assertNull("a single completion is not a rate", snapshot.ips)
    }

    @Test
    fun deniedFrameClearsThePreviousFramesUnexecutedStages() {
        val cycle = recorder.generation()
        recorder.recordGameplayFrame(cycle, 1_000L, detectMs = 12.0, styleMs = 3.0, inputMs = 2.0, frameAgeMs = 40L)
        recorder.recordGameplayFrame(cycle, 1_100L, detectMs = 9.0, frameAgeMs = 50L)
        val denied = recorder.snapshot(1_100L)
        assertEquals(9.0, denied.detectMs!!, 1e-9)
        assertEquals(50L, denied.frameAgeMs)
        assertNull("the previous admitted frame must not supply this denied frame's style", denied.styleMs)
        assertNull("the previous admitted frame must not supply this denied frame's dispatch", denied.inputMs)
        recorder.recordGameplayFrame(cycle, 1_200L)
        val absent = recorder.snapshot(1_200L)
        assertNull(absent.detectMs)
        assertNull(absent.styleMs)
        assertNull(absent.inputMs)
        assertNull(absent.frameAgeMs)
    }

    @Test
    fun rateIsUnavailableOnceTheLatestCompletionIsStale() {
        val cycle = recorder.generation()
        recorder.recordGameplayFrame(cycle, 1_000L)
        recorder.recordGameplayFrame(cycle, 1_100L)
        assertEquals(10.0, recorder.snapshot(3_100L).ips!!, 1e-9)
        assertNull("a trailing window must not keep a stopped processing rate looking current", recorder.snapshot(3_101L).ips)
    }

    @Test
    fun sessionFactsSurvivePerFrameStalenessAndAreClearedByReset() {
        val cycle = recorder.generation()
        recorder.recordPlaystyle(cycle, " Rico ")
        recorder.recordBackend(cycle, "XNNPACK")
        val late = recorder.snapshot(60_000L)
        assertEquals("Rico", late.playstyle)
        assertEquals("XNNPACK", late.backend)
        assertNull("per-frame metrics are still stale", late.detectMs)

        recorder.reset()
        val afterReset = recorder.snapshot(60_000L)
        assertNull(afterReset.playstyle)
        assertNull(afterReset.backend)
    }

    @Test
    fun malformedLabelsAreRefused() {
        val cycle = recorder.generation()
        recorder.recordBackend(cycle, "   ")
        recorder.recordPlaystyle(cycle, "")
        recorder.recordBackend(cycle, "bad\u0000label")
        recorder.recordPlaystyle(cycle, "x".repeat(HudTelemetryRecorder.MAX_LABEL_LENGTH + 1))
        val snapshot = recorder.snapshot(1_000L)
        assertNull(snapshot.backend)
        assertNull(snapshot.playstyle)

        recorder.recordBackend(cycle, "CPU")
        assertEquals("CPU", recorder.snapshot(1_000L).backend)
    }

    @Test
    fun longRunStaysBoundedToTheTrailingWindow() {
        // Capacity above the window so the assertion is about pruning, not ring wrap.
        val wide = HudTelemetryRecorder(rateCapacity = 4_096)
        val cycle = wide.generation()
        for (t in 1_000L..11_000L) wide.recordGameplayFrame(cycle, t)
        val snapshot = wide.snapshot(11_000L)
        // 3001 completions inside the 3 s window, 3000 intervals -> exactly 1000/s.
        assertEquals(1_000.0, snapshot.ips!!, 1e-6)

        // Default capacity clamps storage; the reported cadence stays the same because the rate is
        // measured over the samples that are actually retained.
        val bounded = HudTelemetryRecorder()
        val boundedCycle = bounded.generation()
        for (t in 1_000L..11_000L) bounded.recordGameplayFrame(boundedCycle, t)
        assertEquals(1_000.0, bounded.snapshot(11_000L).ips!!, 1e-6)
    }

    @Test
    fun snapshotIsImmutableAcrossLaterRecording() {
        val cycle = recorder.generation()
        recorder.recordGameplayFrame(cycle, 1_000L, detectMs = 3.0, styleMs = 1.0, inputMs = 0.5, frameAgeMs = 10L)
        recorder.recordPlaystyle(cycle, "Shelly")
        val taken = recorder.snapshot(1_000L)

        recorder.recordGameplayFrame(cycle, 1_000L, detectMs = 40.0, styleMs = 20.0, inputMs = 10.0, frameAgeMs = 99L)
        recorder.recordPlaystyle(cycle, "Colt")

        assertEquals(3.0, taken.detectMs!!, 1e-9)
        assertEquals(1.0, taken.styleMs!!, 1e-9)
        assertEquals(0.5, taken.inputMs!!, 1e-9)
        assertEquals(10L, taken.frameAgeMs)
        assertEquals("Shelly", taken.playstyle)
        assertEquals(40.0, recorder.snapshot(1_000L).detectMs!!, 1e-9)
    }

    @Test
    fun productionFacadeDelegatesAndResets() {
        HudTelemetry.reset()
        val first = HudTelemetry.generation()
        HudTelemetry.recordPlaystyle(first, "Pyla")
        HudTelemetry.recordGameplayFrame(first, 2_000L, detectMs = 6.0)
        HudTelemetry.recordLoop(first, 2_000L, 9.0)
        val snapshot = HudTelemetry.snapshot(2_000L)
        assertEquals(6.0, snapshot.detectMs!!, 1e-9)
        assertEquals(9.0, snapshot.loopMs!!, 1e-9)
        assertEquals("Pyla", snapshot.playstyle)

        HudTelemetry.reset()
        assertTrue(HudTelemetry.generation() != first)
        assertEquals(HudTelemetrySnapshot.EMPTY, HudTelemetry.snapshot(2_000L))
        HudTelemetry.reset()
    }
}
