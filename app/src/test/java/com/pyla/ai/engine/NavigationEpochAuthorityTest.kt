package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Behavioral tests for [NavigationAuthority] — the production pure authority. These exercise the
 * real class (no source-string assertions). No Android dependency: every predicate and the
 * geometry source are injected.
 *
 * Contract note: the three liveness predicates fail closed by default (`{ false }`). Every positive
 * control below explicitly wires `sessionLive`/`menuLive`/`foregroundLive` to valid predicates; an
 * unconfigured authority must deny. Predicates may run arbitrary code (including revocation), so a
 * request that is revoked *during* a predicate callback must be denied by that same `allows()` call.
 */
class NavigationEpochAuthorityTest {

    @Test fun finalGeometryReadInvalidationDeniesSameCall() = finalGeometryRevocation { it.invalidate() }
    @Test fun finalGeometryReadCloseDeniesSameCall() = finalGeometryRevocation { it.close() }
    @Test fun finalGeometryReadInterruptDeniesSameCall() = finalGeometryRevocation { Thread.currentThread().interrupt() }

    private fun finalGeometryRevocation(revoke: (NavigationAuthority) -> Unit) {
        lateinit var owner: NavigationAuthority
        var reads = 0
        owner = liveAuthority(geometry = {
            reads++
            // issue(), pre-check, then the FINAL geometry callback inside allows().
            if (reads == 3) revoke(owner)
            1L
        })
        try {
            val request = owner.issue()!!
            assertFalse(request.allows())
            assertEquals(3, reads)
            Thread.interrupted()
            assertFalse(request.allows()) // An observed denial cannot revive.
        } finally {
            Thread.interrupted()
        }
    }

    /** Explicit positive control: all three liveness predicates wired true. */
    private fun liveAuthority(
        session: () -> Boolean = { true },
        menu: () -> Boolean = { true },
        foreground: () -> Boolean = { true },
        geometry: () -> Long = { 1L },
    ) = NavigationAuthority(
        sessionLive = session,
        menuLive = menu,
        foregroundLive = foreground,
        geometryEpoch = geometry,
    )

    /**
     * Mandatory lifecycle case: a request is issued, Pause invalidates, Resume happens WITHOUT
     * evaluating the guard during the pause; the old request stays denied even though the live
     * predicate is true again. The denying condition is the bound epoch, not a predicate
     * observation, so no predicate is consulted while the guard is finally evaluated.
     */
    @Test
    fun pauseInvalidateThenResumeDeniesOldRequestWithoutEvaluatingGuardDuringPause() {
        var session = true
        var predicateCalls = 0
        val authority = NavigationAuthority(
            sessionLive = { predicateCalls++; session },
            menuLive = { predicateCalls++; true },
            foregroundLive = { predicateCalls++; true },
            geometryEpoch = { 7L },
        )

        val request = authority.issue()
        assertNotNull(request)

        // Pause: epoch advances; the outstanding request's guard is NOT evaluated here.
        session = false
        authority.invalidate()
        val callsDuringPause = predicateCalls

        // Resume: predicate true again, still no guard evaluation.
        session = true
        assertEquals(callsDuringPause, predicateCalls)

        // Old request remains denied because its bound epoch is superseded.
        assertFalse(request!!.allows())
        // Denial came from the epoch binding, so no predicate was consulted.
        assertEquals(callsDuringPause, predicateCalls)

        // A fresh request issued after Resume, with the predicate true, may pass.
        val fresh = authority.issue()
        assertNotNull(fresh)
        assertTrue(fresh!!.allows())
    }

    @Test
    fun freshEpochRequestMayPassAfterInvalidate() {
        val authority = liveAuthority()
        val first = authority.issue()!!
        authority.invalidate()
        assertFalse(first.allows())
        val second = authority.issue()!!
        assertTrue(second.allows())
    }

    @Test
    fun closeForbidsRequestsAndDeniesOutstanding() {
        val authority = liveAuthority()
        val outstanding = authority.issue()!!
        assertTrue(outstanding.allows())
        authority.close()
        assertNull(authority.issue())
        assertNull(authority.guardOrNull())
        assertTrue(authority.isClosed)
        assertFalse(outstanding.allows())
    }

    @Test
    fun throwingPredicateFailsClosedAndCannotRevive() {
        var boom = true
        val authority = liveAuthority(session = {
            if (boom) throw IllegalStateException("predicate failure") else true
        })
        val request = authority.issue()!!
        assertFalse(request.allows())
        boom = false
        // Observed denial cannot revive, even after the predicate would now be true.
        assertFalse(request.allows())
        // A genuinely fresh request is not tainted by the old failure.
        assertTrue(authority.issue()!!.allows())
    }

    @Test
    fun observedDenialCannotReviveAfterPredicateRecovers() {
        var session = false
        val authority = liveAuthority(session = { session })
        val request = authority.issue()!!
        assertFalse(request.allows())
        session = true
        assertFalse(request.allows())
    }

    @Test
    fun foregroundEqualityAloneNeverAuthorizesNavigation() {
        var session = false
        var menu = false
        val authority = liveAuthority(session = { session }, menu = { menu }, foreground = { true })
        val request = authority.issue()!!
        assertFalse("foreground alone must not authorize", request.allows())
        session = true
        // still denied (latched) even though session is now live
        assertFalse(request.allows())
        menu = true
        // A fresh request with every predicate true is allowed.
        assertTrue(authority.issue()!!.allows())
    }

    @Test
    fun menuLossDeniesBoundRequestBetweenAdmissionAndDispatch() {
        var menu = true
        val authority = liveAuthority(menu = { menu })
        val request = authority.issue()!!
        assertTrue(request.allows()) // admission
        menu = false
        assertFalse(request.allows()) // dispatch after the menu appeared
        menu = true
        assertFalse(request.allows()) // denial latched
    }

    @Test
    fun geometryChangeDeniesBoundRequest() {
        var geometry = 1L
        val authority = liveAuthority(geometry = { geometry })
        val request = authority.issue()!!
        assertTrue(request.allows())
        geometry = 2L
        assertFalse(request.allows())
        // A fresh request binds the new geometry.
        assertTrue(authority.issue()!!.allows())
    }

    @Test
    fun interruptedIssuingThreadDeniesBoundRequest() {
        val authority = liveAuthority()
        val issued = AtomicReference<NavigationAuthority.BoundRequest?>()
        val worker = Thread {
            issued.set(authority.issue())
            // Park without clearing the interrupt flag until interrupted.
            while (!Thread.currentThread().isInterrupted) LockSupport.park()
        }
        worker.start()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (issued.get() == null && System.nanoTime() < deadline) Thread.sleep(1)
        assertNotNull(issued.get())
        worker.interrupt()
        worker.join(3_000)
        assertFalse(worker.isAlive)
        assertFalse(issued.get()!!.allows())
    }

    @Test
    fun repeatedInvalidationPermanentlyDeniesHeldRequest() {
        val authority = liveAuthority()
        val request = authority.issue()!!
        val invalidator = Thread { repeat(1_000) { authority.invalidate() } }
        invalidator.start()
        invalidator.join(3_000)
        assertTrue(authority.currentEpoch >= 1_000L)
        assertFalse(request.allows())
    }

    @Test
    fun guardOrNullBindsCurrentEpochAndClosesWithAuthority() {
        val authority = liveAuthority()
        val guard = authority.guardOrNull()!!
        assertTrue(guard())
        authority.invalidate()
        assertFalse(guard())
        authority.close()
        assertNull(authority.guardOrNull())
    }

    // ---------------------------------------------------------------------------------------------
    // Regression cases for the callback/revocation race and the safe-default contract.
    // ---------------------------------------------------------------------------------------------

    /**
     * A predicate callback may revoke the epoch itself. Because the epoch snapshot was taken before
     * the predicates ran, the pre-fix authority returned `true` for a request revoked mid-call.
     * The post-callback re-validation must deny that same call and latch the denial.
     */
    @Test
    fun sessionPredicateInvalidatingEpochDuringCallbackDeniesSameCall() {
        lateinit var authority: NavigationAuthority
        authority = liveAuthority(session = { authority.invalidate(); true })
        val request = authority.issue()!!
        assertFalse("epoch revoked inside the session callback must deny this call", request.allows())
        assertFalse("denial must latch", request.allows())
        assertTrue(authority.currentEpoch >= 1L)
    }

    @Test
    fun menuPredicateClosingDuringCallbackDeniesSameCall() {
        lateinit var authority: NavigationAuthority
        authority = liveAuthority(menu = { authority.close(); true })
        val request = authority.issue()!!
        assertFalse("close() inside the menu callback must deny this call", request.allows())
        assertFalse(request.allows())
        assertTrue(authority.isClosed)
    }

    @Test
    fun foregroundPredicateInvalidatingDuringCallbackDeniesSameCall() {
        lateinit var authority: NavigationAuthority
        authority = liveAuthority(foreground = { authority.invalidate(); true })
        val request = authority.issue()!!
        assertFalse("epoch revoked inside the foreground callback must deny this call", request.allows())
        assertFalse(request.allows())
    }

    /** A predicate callback that changes geometry must deny the same call (post-check). */
    @Test
    fun predicateCallbackChangingGeometryDeniesSameCall() {
        var geometry = 1L
        val authority = liveAuthority(
            session = { geometry = 2L; true },
            geometry = { geometry },
        )
        val request = authority.issue()!!
        assertFalse("geometry changed inside a callback must deny this call", request.allows())
        assertFalse(request.allows())
    }

    /** A predicate callback that interrupts the issuing thread must deny the same call (post-check). */
    @Test
    fun predicateCallbackInterruptingIssuerDeniesSameCall() {
        val issuer = Thread.currentThread()
        val authority = liveAuthority(menu = { issuer.interrupt(); true })
        val request = authority.issue()!!
        try {
            assertFalse("issuer interruption inside a callback must deny this call", request.allows())
            assertFalse(request.allows())
        } finally {
            Thread.interrupted() // clear the flag for the JUnit worker thread
        }
    }

    /**
     * Revocation that lands while a predicate is blocked inside a callback must deny the call once
     * the callback returns — the post-check sees the advanced epoch. Deterministic synchronisation.
     */
    @Test
    fun blockingPredicateThenExternalInvalidateDeniesAfterCallbackReturns() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val authority = liveAuthority(session = {
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            true
        })
        val request = authority.issue()!!
        val result = AtomicReference<Boolean>()
        val worker = Thread { result.set(request.allows()) }
        worker.start()
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        authority.invalidate() // revocation while the callback is in flight
        release.countDown()
        worker.join(3_000)
        assertFalse(worker.isAlive)
        assertFalse("post-callback epoch recheck must deny", result.get()!!)
        assertFalse(request.allows())
    }

    /** Once any invocation latches a denial, concurrent invocations must never return true. */
    @Test
    fun denialLatchesMonotonicallyAcrossConcurrentInvocations() {
        var session = true
        val authority = liveAuthority(session = { session })
        val request = authority.issue()!!
        assertTrue(request.allows())
        session = false
        assertFalse(request.allows()) // latch
        session = true

        val trues = AtomicInteger(0)
        val threads = (0 until 8).map { Thread { repeat(500) { if (request.allows()) trues.incrementAndGet() } } }
        threads.forEach { it.start() }
        threads.forEach { it.join(3_000) }
        assertEquals("latched denial must never revive", 0, trues.get())
        assertFalse(request.allows())
    }

    /** Superseding the epoch denies every concurrent invocation of the held request. */
    @Test
    fun concurrentAllowsAfterInvalidationAllDeny() {
        val authority = liveAuthority()
        val request = authority.issue()!!
        assertTrue(request.allows())
        authority.invalidate()

        val trues = AtomicInteger(0)
        val falses = AtomicInteger(0)
        val threads = (0 until 8).map { Thread {
            repeat(500) { if (request.allows()) trues.incrementAndGet() else falses.incrementAndGet() }
        } }
        threads.forEach { it.start() }
        threads.forEach { it.join(3_000) }
        assertEquals("superseded request must never allow", 0, trues.get())
        assertEquals(4_000, falses.get())
    }

    /** A throwing geometry source must fail `issue()` closed (no request, no leaked exception). */
    @Test
    fun issueThrowingGeometryFailsClosedWithoutException() {
        val authority = liveAuthority(geometry = { throw IllegalStateException("geometry failure") })
        assertNull(authority.issue())
        assertNull(authority.guardOrNull())
    }

    /**
     * The initial geometry read in `issue()` runs a user callback. If it advances the epoch, the
     * returned request is bound to the pre-callback epoch and must be denied — `issue()` must never
     * re-snapshot the epoch after the callback (which would mint renewed authority).
     */
    @Test
    fun issueInitialGeometryCallbackInvalidatingEpochDoesNotMintAuthority() {
        var revokeOnGeometry = true
        lateinit var authority: NavigationAuthority
        authority = liveAuthority(geometry = {
            if (revokeOnGeometry) authority.invalidate()
            5L
        })
        val request = authority.issue()
        assertNotNull(request)
        assertFalse("request bound before the revoking geometry callback must be denied", request!!.allows())
        assertFalse(request.allows())

        revokeOnGeometry = false
        assertTrue(authority.issue()!!.allows())
    }

    /** No positive control wired: the authority must fail closed, never authorize navigation. */
    @Test
    fun unconfiguredAuthorityPredicatesDefaultFailClosed() {
        val authority = NavigationAuthority()
        val request = authority.issue()
        assertNotNull(request)
        assertFalse("missing liveness predicates must fail closed", request!!.allows())
        assertFalse(request.allows())
    }

    /** Foreground supplied alone must not authorize while session/menu keep their fail-closed default. */
    @Test
    fun foregroundAloneWithDefaultSessionAndMenuDoesNotAuthorize() {
        val authority = NavigationAuthority(foregroundLive = { true })
        val request = authority.issue()!!
        assertFalse("foreground alone with unset session/menu must not authorize", request.allows())
        assertFalse(request.allows())
    }
}
