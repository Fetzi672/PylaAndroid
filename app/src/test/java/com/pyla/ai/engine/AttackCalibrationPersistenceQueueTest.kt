package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AttackCalibrationPersistenceQueueTest {
    private class Manual : Executor {
        val jobs = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { jobs.add(command) }
        fun run() { jobs.remove().run() }
    }

    @Test fun noMutationNeedsNoFlush() {
        val e = Manual(); val q = AttackCalibrationPersistenceQueue(e, { error("not called") })
        assertTrue(q.settled()); assertTrue(e.jobs.isEmpty())
    }

    @Test fun successRequiresActualWorkerAcknowledgment() {
        val e = Manual(); var calls = 0
        val q = AttackCalibrationPersistenceQueue(e, { calls++; true })
        q.request(); assertFalse(q.settled()); assertEquals(0, calls)
        e.run(); assertTrue(q.settled()); assertEquals(1, calls)
    }

    @Test fun burstsCoalesceToOneQueuedJob() {
        val e = Manual(); var calls = 0
        val q = AttackCalibrationPersistenceQueue(e, { calls++; true })
        repeat(100) { q.request() }; assertEquals(1, e.jobs.size)
        e.run(); assertEquals(1, calls); assertTrue(q.settled())
    }

    @Test fun clearDuringFlushCommitsLatestStateAndNeverReplaysOldSave() {
        val e = Manual(); var value: String? = "learned"; val writes = mutableListOf<String?>()
        lateinit var q: AttackCalibrationPersistenceQueue
        q = AttackCalibrationPersistenceQueue(e, {
            writes.add(value)
            if (writes.size == 1) { value = null; q.request() }
            true
        })
        q.request(); e.run()
        assertEquals(listOf("learned", null), writes)
        assertNull(value); assertTrue(q.settled()); assertTrue(e.jobs.isEmpty())
    }

    @Test fun failedFlushIsNotAcknowledgedOrRetriedSilently() {
        val e = Manual(); var calls = 0
        val q = AttackCalibrationPersistenceQueue(e, { calls++; false })
        q.request(); e.run(); assertFalse(q.settled()); assertEquals(1, calls); assertTrue(e.jobs.isEmpty())
        q.request(); assertEquals(1, e.jobs.size)
    }

    @Test fun throwingFlushAndObserverCannotKillQueue() {
        val e = Manual(); var fail = true
        val q = AttackCalibrationPersistenceQueue(e, { if (fail) throw IllegalStateException("synthetic") else true }, { throw IllegalStateException("observer") })
        q.request(); e.run(); assertFalse(q.settled())
        fail = false; q.request(); e.run(); assertTrue(q.settled())
    }

    @Test fun rejectedExecutorDoesNotPretendSuccess() {
        val q = AttackCalibrationPersistenceQueue(Executor { throw RejectedExecutionException("synthetic") }, { true })
        q.request(); assertFalse(q.settled())
    }

    @Test fun blockedIODoesNotBlockMutationOrStateChecks() {
        val started = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(1)
        val e = Executor { job -> Thread { try { job.run() } finally { done.countDown() } }.apply { isDaemon = true }.start() }
        var first = true
        val q = AttackCalibrationPersistenceQueue(e, {
            if (first) { first = false; started.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
            true
        })
        q.request(); assertTrue(started.await(3, TimeUnit.SECONDS))
        try { q.request(); assertFalse(q.settled()) } finally { release.countDown() }
        assertTrue(done.await(3, TimeUnit.SECONDS)); assertTrue(q.settled())
    }
}
