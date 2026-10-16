package com.pyla.ai.engine

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe, per-session lifecycle epoch owner for navigation requests.
 *
 * A navigation request is admitted by [issue], which binds the epoch that is current at that
 * moment to the issuing thread. Any Pause/Stop [invalidate] or terminal [close] advances the
 * epoch, so a request bound to a superseded epoch is denied forever — even after Resume makes the
 * live predicate true again. Each bound request additionally fails closed on a throwing predicate,
 * on an interrupted issuing thread, on a geometry change, and whenever any of the live
 * session/menu/foreground predicates is false.
 *
 * A denied request can never revive: [BoundRequest.allows] latches its first `false`. Allowance is
 * not latched, so a request that was once admitted is re-evaluated (and may still be denied) at
 * every admission/dispatch/continuation point.
 *
 * **Callback coherence.** The liveness predicates and the geometry source are arbitrary user code:
 * a callback may itself call [invalidate]/[close], change the geometry identity, or interrupt the
 * issuing thread. [BoundRequest.allows] therefore validates its invariants BOTH before and AFTER
 * running any callback, uses neither observation until it has been re-validated, and denies the
 * whole call if a callback revoked it. Only revocation observable *before this call returns* is
 * guaranteed to be caught; an OS-scheduled revocation that lands after `allows()` already returned
 * `true` is outside any such check and is not claimed to be prevented here.
 *
 * The liveness predicates fail closed: [sessionLive], [menuLive] and [foregroundLive] default to
 * `false`, so an authority constructed without explicitly wiring live predicates denies navigation
 * rather than silently authorizing it. Foreground is a necessary but never sufficient condition, so
 * navigation is never authorized by foreground equality alone.
 *
 * No monitor is ever held across a user predicate, an interrupt probe, a join, IO or a callback;
 * the only critical section reads or advances two primitive fields.
 */
class NavigationAuthority(
    /**
     * Session ownership/liveness: engine running, not Pause/Stop, lifecycle still owns input.
     * Fails closed by default — bind a real predicate explicitly.
     */
    private val sessionLive: () -> Boolean = { false },
    /** External menu/overlay must be absent for navigation. Fails closed by default. */
    private val menuLive: () -> Boolean = { false },
    /** Game-foreground predicate (necessary, never sufficient). Fails closed by default. */
    private val foregroundLive: () -> Boolean = { false },
    /** Geometry identity captured when a request is issued. */
    private val geometryEpoch: () -> Long = { com.pyla.ai.capture.InputCoordinates.geometryGeneration },
) {
    private val lock = Any()
    private var epoch: Long = 0L
    private var closed: Boolean = false

    /**
     * Advance the lifecycle epoch (Pause/Stop). Every request issued before this call is
     * permanently denied; the guard of an old request is not evaluated by this method.
     */
    fun invalidate() {
        synchronized(lock) { epoch += 1L }
    }

    /** Terminal close: forbid all current and future requests. Never reversible. */
    fun close() {
        synchronized(lock) { closed = true; epoch += 1L }
    }

    val isClosed: Boolean get() = synchronized(lock) { closed }

    val currentEpoch: Long get() = synchronized(lock) { epoch }

    /**
     * Admit a request bound to the current epoch, or return `null` when the authority is closed or
     * its geometry source throws. The epoch is snapshotted BEFORE the (out-of-lock) geometry read
     * and is never re-taken: if the geometry callback advances the epoch, the returned request is
     * bound to the superseded epoch and is denied — [issue] must not mint renewed authority.
     */
    fun issue(): BoundRequest? {
        val boundEpoch: Long
        synchronized(lock) {
            if (closed) return null
            boundEpoch = epoch
        }
        val geometry = try {
            geometryEpoch()
        } catch (_: Throwable) {
            return null // fail closed: do not leak the exception, do not mint a request
        }
        return BoundRequest(boundEpoch, Thread.currentThread(), geometry)
    }

    /** Convenience guard closure for the current request, or `null` when closed/unissuable. */
    fun guardOrNull(): (() -> Boolean)? = issue()?.let { bound -> { bound.allows() } }

    /** One admitted navigation request, bound to its epoch, issuing thread and geometry. */
    inner class BoundRequest internal constructor(
        val epoch: Long,
        private val issuingThread: Thread,
        private val geometry: Long,
    ) {
        private val denied = AtomicBoolean(false)

        /** Fail-closed authority for this request. The first `false` is permanent. */
        fun allows(): Boolean {
            if (denied.get()) return false
            val ok = try { evaluate() } catch (_: Throwable) { false }
            if (!ok) denied.set(true)
            return ok
        }

        private fun evaluate(): Boolean {
            // Pre-check before running any callback. The monitor is held only for two primitive reads.
            if (!isCurrent()) return false
            if (issuingThread.isInterrupted) return false
            if (readGeometry() != geometry) return false

            // Run the user predicates strictly OUTSIDE the monitor. Each may run arbitrary code,
            // including revocation (invalidate/close), a geometry change or an interrupt.
            if (!sessionLive()) return false
            if (!menuLive()) return false
            if (!foregroundLive()) return false

            // Geometry is itself a callback: run it BEFORE the final callback-free checks.
            // It may revoke the epoch, close the owner or interrupt the issuing thread even
            // while returning the original geometry. Never put a callback after isCurrent().
            if (readGeometry() != geometry) return false
            if (issuingThread.isInterrupted) return false
            return isCurrent() && !denied.get()
        }

        /** Current epoch + open state, read under the monitor; no callback runs here. */
        private fun isCurrent(): Boolean = synchronized(lock) {
            !closed && this@NavigationAuthority.epoch == epoch
        }

        /** Geometry identity is a user callback: read outside the monitor, fail closed on throw. */
        private fun readGeometry(): Long = geometryEpoch()
    }
}
