package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic verification of the bounded, thread-confined scratch reuse in
 * [AttackButtonPixels]: buffer identity/counters only, never wall-clock assertions.
 */
class AttackRecognitionScratchReuseTest {
    // A Gradle worker can have run other recognizer suites already. Each case needs a fresh
    // thread-local workspace, rather than depending on suite order or resetting product state.
    private fun confined(block: () -> Unit) {
        val task = java.util.concurrent.FutureTask<Unit> { block() }
        val worker = Thread(task, "attack-scratch-contract").apply { isDaemon = true }
        worker.start()
        try {
            task.get(60, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        } finally {
            worker.interrupt()
            worker.join(1_000)
        }
    }

    private val w = 320
    private val h = 180

    private fun background(width: Int = w, height: Int = h) = ByteArray(width * height * 3).also { a ->
        for (i in 0 until width * height) { a[i * 3] = 30; a[i * 3 + 1] = 90; a[i * 3 + 2] = 40 }
    }

    private fun pixel(a: ByteArray, width: Int, x: Int, y: Int, r: Int, g: Int, b: Int) {
        val i = (y * width + x) * 3; a[i] = r.toByte(); a[i + 1] = g.toByte(); a[i + 2] = b.toByte()
    }

    private fun ring(a: ByteArray, width: Int, height: Int, cx: Int, cy: Int, radius: Int = 20) {
        val coreRadius = maxOf(6, radius / 5)
        for (y in 0 until height) for (x in 0 until width) {
            val d = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (d <= radius * radius) {
                if (d < coreRadius * coreRadius) pixel(a, width, x, y, 220, 195, 200) else pixel(a, width, x, y, 160, 12, 20)
            }
        }
    }

    @Test
    fun sameFrameAreaReusesTheSameConfinedBuffers() = confined {
        val scratch = AttackButtonPixels.retainedScratchForTest()
        val first = background().also { ring(it, w, h, 160, 90) }
        val second = background().also { ring(it, w, h, 160, 90) }

        val p1 = requireNotNull(AttackButtonPixels.detect(w, h, first))
        val labelsAfterFirst = scratch.labels
        val redAfterFirst = scratch.red
        val stackAfterFirst = scratch.stack
        val allocationsAfterFirst = scratch.allocations

        val p2 = requireNotNull(AttackButtonPixels.detect(w, h, second))
        assertEquals(p1, p2)
        assertEquals("second call of equal area must not allocate", allocationsAfterFirst, scratch.allocations)
        assertSame(labelsAfterFirst, scratch.labels)
        assertSame(redAfterFirst, scratch.red)
        assertSame(stackAfterFirst, scratch.stack)
    }

    @Test
    fun frameGrowthResizesOnceAndKeepsTheDecision() = confined {
        val scratch = AttackButtonPixels.retainedScratchForTest()
        val small = background().also { ring(it, w, h, 160, 90) }
        requireNotNull(AttackButtonPixels.detect(w, h, small))
        val smallLabels = scratch.labels
        val allocationsBefore = scratch.allocations

        val bigW = 640
        val bigH = 360
        val big = background(bigW, bigH).also { ring(it, bigW, bigH, 320, 180, 40) }
        val bigPoint = requireNotNull(AttackButtonPixels.detect(bigW, bigH, big))
        assertEquals(320f, bigPoint.x, 0.6f)
        assertEquals(180f, bigPoint.y, 0.6f)
        assertEquals("growing the retained scratch allocates exactly once", allocationsBefore + 1, scratch.allocations)
        assertTrue(scratch.labels !== smallLabels)
        assertTrue(scratch.labels.size >= bigW * bigH)

        val bigLabels = scratch.labels
        val allocationsAfterGrowth = scratch.allocations
        requireNotNull(AttackButtonPixels.detect(bigW, bigH, big))
        assertEquals(allocationsAfterGrowth, scratch.allocations)
        assertSame(bigLabels, scratch.labels)

        // Shrinking reuses the larger retained buffers instead of reallocating.
        requireNotNull(AttackButtonPixels.detect(w, h, small))
        assertEquals(allocationsAfterGrowth, scratch.allocations)
        assertSame(bigLabels, scratch.labels)
    }

    @Test
    fun oversizedFrameUsesThrowawayBuffersAndIsNotRetained() = confined {
        val scratch = AttackButtonPixels.retainedScratchForTest()
        val w0 = 320
        val h0 = 180
        requireNotNull(AttackButtonPixels.detect(w0, h0, background(w0, h0).also { ring(it, w0, h0, 160, 90) }))
        val retainedLabels = scratch.labels
        val retainedArea = scratch.pixels
        val retainedAllocations = scratch.allocations
        val transientBefore = scratch.transientAllocations

        val hugeW = 4096
        val hugeH = 1024
        assertTrue(hugeW.toLong() * hugeH > AttackButtonPixels.MAX_RETAINED_PIXELS)
        val huge = background(hugeW, hugeH).also { ring(it, hugeW, hugeH, 2048, 512, 120) }
        val hugePoint = requireNotNull(AttackButtonPixels.detect(hugeW, hugeH, huge))
        assertEquals(2048f, hugePoint.x, 2f)
        assertEquals(512f, hugePoint.y, 2f)

        assertEquals("oversized frames must not grow the retained scratch", retainedAllocations, scratch.allocations)
        assertEquals(retainedArea, scratch.pixels)
        assertSame(retainedLabels, scratch.labels)
        assertEquals(transientBefore + 1, scratch.transientAllocations)

        // Next normal frame still uses the retained buffers.
        assertNotNull(AttackButtonPixels.detect(w0, h0, background(w0, h0).also { ring(it, w0, h0, 160, 90) }))
        assertEquals(retainedAllocations, scratch.allocations)
        assertSame(retainedLabels, scratch.labels)
    }

    @Test
    fun recycledMasksCannotCarryStateAcrossFrames() = confined {
        val a = background().also { ring(it, w, h, 40, 40) }
        val b = background().also { ring(it, w, h, 270, 120) }
        val empty = background()

        val pa = requireNotNull(AttackButtonPixels.detect(w, h, a))
        assertEquals(40f, pa.x, 0.6f); assertEquals(40f, pa.y, 0.6f)
        assertNull(AttackButtonPixels.detect(w, h, empty))
        val pb = requireNotNull(AttackButtonPixels.detect(w, h, b))
        assertEquals(270f, pb.x, 0.6f); assertEquals(120f, pb.y, 0.6f)
        // A frame with two controls stays ambiguous even after both single-control frames reused
        // the same label plane: no residual component may survive into the next call.
        val two = background().also { ring(it, w, h, 50, 90); ring(it, w, h, 250, 90) }
        assertNull(AttackButtonPixels.detect(w, h, two))
        val again = requireNotNull(AttackButtonPixels.detect(w, h, a))
        assertEquals(40f, again.x, 0.6f); assertEquals(40f, again.y, 0.6f)
    }

    @Test
    fun concurrentRecognitionKeepsScratchAndDecisionsThreadConfined() = confined {
        val a = background().also { ring(it, w, h, 40, 40) }
        val b = background().also { ring(it, w, h, 270, 120) }
        val expectedA = requireNotNull(AttackButtonPixels.detect(w, h, a))
        val expectedB = requireNotNull(AttackButtonPixels.detect(w, h, b))
        val mine = AttackButtonPixels.retainedScratchForTest()

        val scratches = java.util.Collections.synchronizedList(ArrayList<AttackButtonPixels.Scratch>())
        val failures = java.util.Collections.synchronizedList(ArrayList<String>())
        val workers = (0 until 2).map { worker ->
            Thread({
                try {
                    scratches.add(AttackButtonPixels.retainedScratchForTest())
                    for (round in 0 until 100) {
                        val gotA = AttackButtonPixels.detect(w, h, a)
                        val gotB = AttackButtonPixels.detect(w, h, b)
                        if (gotA != expectedA) failures.add("worker $worker round $round frame A: $gotA")
                        if (gotB != expectedB) failures.add("worker $worker round $round frame B: $gotB")
                    }
                } catch (e: Throwable) {
                    failures.add("worker $worker threw $e")
                }
            }, "attack-scratch-worker-$worker").apply { isDaemon = true; start() }
        }
        workers.forEach { it.join(60_000) }
        assertTrue("workers must finish", workers.none { it.isAlive })
        assertEquals("no worker may observe a foreign decision", emptyList<String>(), failures.toList())
        assertEquals(2, scratches.size)
        assertTrue("each thread gets its own scratch", scratches.none { it === mine })
        assertTrue("two threads must not share one scratch", scratches[0] !== scratches[1])
        assertSame("this thread's scratch is untouched", mine, AttackButtonPixels.retainedScratchForTest())
    }
}
