package com.pyla.ai.engine

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * Coalesces durability acknowledgments; flush must commit the CURRENT storage map, never replay
 * a captured old value. IO and observers run outside this monitor; request/settled never wait on IO.
 * A failed flush is not silently retried. A later explicit mutation may request another flush.
 */
class AttackCalibrationPersistenceQueue(
    private val executor: Executor,
    private val flush: () -> Boolean,
    private val onResult: (Boolean) -> Unit = {},
) {
    private var requested = 0L
    private var completed = 0L
    private var successful = true
    private var running = false

    fun request() {
        val schedule = synchronized(this) {
            requested++
            if (running) false else { running = true; true }
        }
        if (schedule) {
            try { executor.execute { drain() } }
            catch (_: RejectedExecutionException) {
                synchronized(this) { running = false; completed = requested; successful = false }
                report(false)
            }
        }
    }

    @Synchronized fun settled(): Boolean = requested == completed && successful

    private fun drain() {
        while (true) {
            val target = synchronized(this) { requested }
            val ok = try { flush() } catch (_: Exception) { false }
            val done = synchronized(this) {
                completed = target
                successful = ok
                if (requested == target) { running = false; true } else false
            }
            if (done) { report(ok); return }
        }
    }

    private fun report(ok: Boolean) { try { onResult(ok) } catch (_: Exception) { } }
}
