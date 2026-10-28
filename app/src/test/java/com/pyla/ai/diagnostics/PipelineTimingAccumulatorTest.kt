package com.pyla.ai.diagnostics

import com.pyla.ai.diagnostics.PipelineTimingTelemetry.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Pure accumulator: injected clock and sink, no Android runtime. */
class PipelineTimingAccumulatorTest {
    private class FakeClock(var now: Long = 1_000L)

    private class Harness(initialMs: Long = 1_000L) {
        val clock = FakeClock(initialMs)
        val lines: MutableList<Pair<String, String>> = Collections.synchronizedList(ArrayList())
        val acc = PipelineTimingAccumulator({ clock.now }, { kind, detail -> lines.add(kind to detail) })

        fun advance(ms: Long) {
            clock.now += ms
        }

        fun lineFor(stage: Stage): String? =
            lines.lastOrNull { it.first == "timing." + stage.name.lowercase() }?.second
    }

    @Test
    fun exactQuantilesOfRetainedSamples() {
        val h = Harness()
        repeat(200) { h.acc.record(Stage.ENTITY_INFERENCE, it.toLong()) }
        h.acc.flush()

        val line = h.lineFor(Stage.ENTITY_INFERENCE)
        assertNotNull(line)
        // Last 128 samples are 72..199: p50 = index 63 -> 135, p95 = nearest rank 122 -> 193.
        assertTrue(line, line!!.contains("p50=135ms"))
        assertTrue(line, line.contains("p95=193ms"))
        assertTrue(line, line.contains("max=199ms"))
        assertTrue(line, line.contains("n=200"))
        assertTrue(line, line.contains("n_total=200"))
        assertTrue(line, line.contains("n_sample=128"))
        assertTrue(line, line.contains("sample=last128"))
        assertEquals(0, h.acc.sampleCount(Stage.ENTITY_INFERENCE))
        assertEquals(200L, h.acc.totalCount(Stage.ENTITY_INFERENCE))
    }

    @Test
    fun singleSampleQuantilesAreExact() {
        val h = Harness()
        h.acc.record(Stage.LOOP_TOTAL, 37)
        h.acc.flush()
        val line = h.lineFor(Stage.LOOP_TOTAL)!!
        assertTrue(line, line.contains("p50=37ms"))
        assertTrue(line, line.contains("p95=37ms"))
        assertTrue(line, line.contains("max=37ms"))
        assertTrue(line, line.contains("n_sample=1"))
    }

    @Test
    fun windowMaxCoversSamplesEvictedFromRing() {
        val h = Harness()
        h.acc.record(Stage.TERRAIN_DECODE, 10_000)
        repeat(200) { h.acc.record(Stage.TERRAIN_DECODE, it.toLong()) }
        h.acc.flush()
        val line = h.lineFor(Stage.TERRAIN_DECODE)!!
        assertTrue(line, line.contains("max=10000ms"))
        assertTrue(line, line.contains("n_sample=128"))
        assertTrue(line, line.contains("n=201"))
    }

    @Test
    fun noEagerEmissionWithinWindow() {
        val h = Harness()
        h.acc.record(Stage.CLOSE_INFERENCE, 5)
        h.advance(4_999)
        h.acc.record(Stage.CLOSE_INFERENCE, 6)
        assertTrue("emitted before window elapsed", h.lines.isEmpty())

        h.advance(1)
        h.acc.record(Stage.CLOSE_INFERENCE, 7)
        assertEquals(1, h.lines.size)
        assertTrue(h.lineFor(Stage.CLOSE_INFERENCE)!!.contains("n=3"))
    }

    @Test
    fun clockRollbackReanchorsWithoutEmittingOrLosingSamples() {
        val h = Harness(10_000)
        h.acc.record(Stage.ENTITY_DECODE, 10)
        h.clock.now = 5_000
        h.acc.record(Stage.ENTITY_DECODE, 20)
        assertTrue("rollback must not emit", h.lines.isEmpty())

        h.clock.now = 10_000
        h.acc.record(Stage.ENTITY_DECODE, 30)
        assertEquals(1, h.lines.size)
        val line = h.lineFor(Stage.ENTITY_DECODE)!!
        assertTrue(line, line.contains("n=3"))
        assertTrue(line, line.contains("n_total=3"))
    }

    @Test
    fun negativeFutureAndOverflowedIntervalsAreIgnored() {
        val h = Harness(1_000)
        assertEquals(-1L, h.acc.elapsed(Stage.ENTITY_PREPROCESS, 2_000)) // start in the future
        assertEquals(-1L, h.acc.elapsed(Stage.ENTITY_PREPROCESS, 1_001))
        assertEquals(-1L, h.acc.elapsed(Stage.ENTITY_PREPROCESS, Long.MIN_VALUE)) // subtraction overflow
        h.acc.record(Stage.ENTITY_PREPROCESS, -5)
        assertEquals(0L, h.acc.totalCount(Stage.ENTITY_PREPROCESS))

        assertEquals(600L, h.acc.elapsed(Stage.ENTITY_PREPROCESS, 400))
        assertEquals(1L, h.acc.totalCount(Stage.ENTITY_PREPROCESS))
        h.acc.flush()
        assertTrue(h.lineFor(Stage.ENTITY_PREPROCESS)!!.contains("p50=600ms"))

        // Long.MAX_VALUE durations are kept verbatim, not summed or clamped.
        h.acc.record(Stage.ENTITY_PREPROCESS, Long.MAX_VALUE)
        h.acc.flush()
        val line = h.lineFor(Stage.ENTITY_PREPROCESS)!!
        assertTrue(line, line.contains("max=${Long.MAX_VALUE}ms"))
    }

    @Test
    fun ageKeepsOriginalCaptureTimestampAndRejectsInvalidInput() {
        val h = Harness(10_000)
        h.acc.age(Stage.ENTITY_INFERENCE, 9_000) // not a frame-age stage
        h.acc.age(Stage.FRAME_AGE_START, 9_000)
        h.acc.age(Stage.FRAME_AGE_START, 10_001) // future
        h.acc.age(Stage.FRAME_AGE_START, 0)
        h.acc.age(Stage.FRAME_AGE_START, -1)
        assertEquals(1L, h.acc.totalCount(Stage.FRAME_AGE_START))
        assertEquals(0L, h.acc.totalCount(Stage.ENTITY_INFERENCE))

        h.acc.flush()
        val line = h.lineFor(Stage.FRAME_AGE_START)!!
        assertTrue(line, line.contains("p50=1000ms"))
        assertFalse(line, line.contains("boundary="))
    }

    @Test
    fun dispatchAgeIsLabelledRequestBeforeQueue() {
        val h = Harness(10_000)
        h.acc.age(Stage.FRAME_AGE_DISPATCH, 9_500)
        h.acc.flush()
        val line = h.lineFor(Stage.FRAME_AGE_DISPATCH)!!
        assertTrue(line, line.contains("p50=500ms"))
        assertTrue(line, line.contains("boundary=request_before_queue"))
    }

    @Test
    fun movementGapUsesAcceptedRequestsOnly() {
        val h = Harness(1_000)
        h.acc.movementRequest() // opens the interval, no gap
        assertEquals(0L, h.acc.totalCount(Stage.MOVEMENT_REQUEST_GAP))

        h.clock.now = 1_300
        h.acc.movementRequest()
        h.acc.movementRequest() // zero-length observed gap is valid
        h.clock.now = 1_600
        h.acc.interruption("STOP") // ends the acceptance streak
        h.acc.movementRequest()
        h.clock.now = 2_200
        h.acc.movementRequest()

        h.acc.flush()
        val line = h.lineFor(Stage.MOVEMENT_REQUEST_GAP)!!
        assertTrue(line, line.contains("n=3"))
        assertTrue(line, line.contains("max=600ms"))
        assertTrue(
            h.lines.toString(),
            h.lines.any { it.first == "timing.interruptions" && it.second.contains("STOP=1") },
        )
    }

    @Test
    fun metadataIsWhitelistedAndNeverClaimsHardware() {
        val h = Harness()
        h.acc.model("person", "GPU", "vulkan", -3)
        h.acc.flush()

        val model = h.lines.first { it.first == "timing.model" }.second
        assertTrue(model, model.contains("role=UNKNOWN"))
        assertTrue(model, model.contains("requested=UNKNOWN"))
        assertTrue(model, model.contains("effective=UNKNOWN"))
        assertTrue(model, model.contains("threads=0"))
        assertTrue(model, model.contains("hardware=UNVERIFIED"))
        assertFalse(model, model.contains("GPU"))
        assertFalse(model, model.contains("vulkan"))

        h.acc.model("tile_centered", "CPU", "NNAPI", 4096)
        h.acc.flush()
        val nnapi = h.lines.last { it.first == "timing.model" }.second
        assertTrue(nnapi, nnapi.contains("role=tile_centered"))
        assertTrue(nnapi, nnapi.contains("requested=CPU"))
        assertTrue(nnapi, nnapi.contains("effective=NNAPI"))
        assertTrue(nnapi, nnapi.contains("threads=${PipelineTimingAccumulator.MAX_THREADS}"))
        assertTrue(nnapi, nnapi.contains("hardware=UNVERIFIED"))
        assertFalse(nnapi, nnapi.contains("hardware=gpu"))
    }

    @Test
    fun unknownInterruptionFoldsToOtherWithoutUserText() {
        val h = Harness()
        h.acc.interruption("user typed secret")
        h.acc.interruption("OTHER")
        h.acc.interruption("PAUSED")
        h.acc.flush()
        val line = h.lines.first { it.first == "timing.interruptions" }.second
        assertTrue(line, line.contains("OTHER=2"))
        assertTrue(line, line.contains("PAUSED=1"))
        assertFalse(line, line.contains("secret"))
        assertFalse(line, line.contains("user"))
    }

    @Test
    fun throwingSinkIsIsolatedPerLine() {
        var calls = 0
        val received = ArrayList<String>()
        val acc = PipelineTimingAccumulator({ 1_000L }, { _, detail ->
            calls++
            if (calls == 1) throw IllegalStateException("boom")
            received.add(detail)
        })
        acc.record(Stage.ENTITY_PREPROCESS, 5)
        acc.record(Stage.ENTITY_INFERENCE, 6)
        try {
            acc.flush()
        } catch (t: Throwable) {
            fail("sink exception escaped: $t")
        }
        assertEquals(2, calls)
        assertEquals(1, received.size)

        acc.record(Stage.ENTITY_PREPROCESS, 7)
        acc.flush()
        assertTrue(calls >= 3)
    }

    @Test
    fun resetDropsPendingWindowAndCounters() {
        val h = Harness(1_000)
        h.acc.record(Stage.CLOSE_DECODE, 5)
        h.advance(2_000)
        h.acc.reset()
        h.acc.flush()
        assertTrue("reset must not emit stale samples", h.lines.isEmpty())
        assertEquals(0L, h.acc.totalCount(Stage.CLOSE_DECODE))
        assertEquals(0, h.acc.sampleCount(Stage.CLOSE_DECODE))

        h.acc.record(Stage.CLOSE_DECODE, 7)
        h.advance(5_000)
        h.acc.record(Stage.CLOSE_DECODE, 8)
        val line = h.lineFor(Stage.CLOSE_DECODE)!!
        assertTrue(line, line.contains("n_total=2"))
        assertTrue(line, line.contains("n=2"))
    }

    @Test
    fun elapsedIgnoresStartsFromBeforeTheLastReset() {
        val h = Harness(10_000)
        h.acc.record(Stage.LOOP_TOTAL, 5)
        h.acc.reset()

        assertEquals(-1L, h.acc.elapsed(Stage.INPUT_CALLBACK, 9_000)) // old run start
        assertEquals(0L, h.acc.elapsed(Stage.INPUT_CALLBACK, 10_000))
        assertEquals(1L, h.acc.totalCount(Stage.INPUT_CALLBACK))
        h.acc.flush()
        val line = h.lineFor(Stage.INPUT_CALLBACK)!!
        assertTrue(line, line.contains("n=1"))
        assertTrue(line, line.contains("p50=0ms"))
    }

    @Test
    fun flushEmitsPartialWindowOnlyOnce() {
        val h = Harness(1_000)
        h.acc.record(Stage.INPUT_SUBMISSION, 5)
        h.advance(1_000)
        h.acc.flush()
        assertEquals(1, h.lines.count { it.first == "timing.input_submission" })
        h.acc.flush()
        assertEquals(1, h.lines.count { it.first == "timing.input_submission" })
    }

    @Test
    fun concurrentRecordingStaysBounded() {
        val clock = AtomicLong(0)
        val details: MutableList<String> = Collections.synchronizedList(ArrayList())
        val failure = AtomicReference<Throwable?>(null)
        // 10 ms per observation so 1200 records span ~12 s and cross two 5 s windows.
        val acc = PipelineTimingAccumulator({ clock.incrementAndGet() * 10L }, { _, detail -> details.add(detail) })
        val workers = (1..4).map { id ->
            thread {
                try {
                    repeat(300) { acc.record(Stage.TERRAIN_INFERENCE, (id * 1_000 + it).toLong()) }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        workers.forEach { it.join(20_000) }
        assertEquals(failure.get()?.toString(), null, failure.get())
        assertEquals(1200L, acc.totalCount(Stage.TERRAIN_INFERENCE))
        assertEquals(128, acc.sampleCount(Stage.TERRAIN_INFERENCE))
        assertTrue(details.isNotEmpty())
        for (detail in synchronized(details) { details.toList() }) {
            val sample = Regex("n_sample=(\\d+)").find(detail)!!.groupValues[1].toInt()
            assertTrue(detail, sample <= PipelineTimingAccumulator.SAMPLE_CAPACITY)
        }
    }

    @Test
    fun sinkRunsOutsideAccumulatorLock() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val acc = PipelineTimingAccumulator({ 1_000L }, { _, _ ->
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        })
        acc.record(Stage.LOOP_TOTAL, 1)
        val flusher = thread {
            try {
                acc.flush()
            } catch (_: Throwable) {
            }
        }
        assertTrue("flush never reached sink", entered.await(5, TimeUnit.SECONDS))

        val recorderDone = CountDownLatch(1)
        thread {
            acc.record(Stage.LOOP_TOTAL, 2)
            recorderDone.countDown()
        }
        assertTrue("recording blocked while sink ran (lock held during emit)", recorderDone.await(3, TimeUnit.SECONDS))
        release.countDown()
        flusher.join(5_000)
    }
}
