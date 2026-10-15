package com.pyla.ai.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Behavioral, deterministic JVM regression tests for the production [EngineSessionAuthority]
 * (the session lifecycle authority owned by BotEngine for engine-initiated side effects).
 *
 * These exercise the REAL production class together with the REAL [NavigationAuthority] it wraps.
 * They are NOT a full BotEngine/watchdog/native/emulator test and are NOT an ownership-settlement
 * proof: every test drives only the authority's own public surface with an injected `sessionLive`
 * boolean. The wrapped geometry source is the faithful `InputCoordinates` stub compiled alongside
 * (see evidence/engine-session-tests/harness/InputCoordinates.kt) because the pure authority's
 * default reads `InputCoordinates.geometryGeneration`.
 *
 * Semantic boundaries intentionally documented here rather than faked:
 *  * `menuLive` and `foregroundLive` are hard-wired `true` inside production
 *    EngineSessionAuthority. Foreground is deliberately NOT required for the crash-recovery
 *    relaunch, so this authority exposes no foreground gate (see
 *    [foregroundIsNotRequiredForRelaunch]). There is no foreground API to call; the test asserts
 *    the admission semantic only.
 *  * "Paused", "wrong owner" and "stopped" are all expressed by the caller-supplied `sessionLive`
 *    becoming false; EngineSessionAuthority itself never samples ownership, so the tests set the
 *    predicate and observe the authority's denial.
 *  * Only revocation observable before `allows()` returns is claimed caught; OS-scheduled
 *    revocation landing after a `true` return is out of scope (exactly as production documents).
 */
class EngineSessionAuthorityTest {

    // -------------------------------------------------------------------------------------------
    // Positive control
    // -------------------------------------------------------------------------------------------

    @Test
    fun liveSessionAdmitsRequestAndGuard() {
        val authority = EngineSessionAuthority(sessionLive = { true })
        val request = authority.beginRequest()
        assertNotNull("a live authority must issue a request", request)
        assertTrue("a live request must be admitted", request!!.allows())

        val guard = authority.requestGuard()
        assertNotNull("a live authority must issue a guard", guard)
        assertTrue("a live guard must allow", guard!!())
    }

    @Test
    fun denyConstantIsAlwaysFalse() {
        assertFalse(EngineSessionAuthority.DENY())
    }

    // -------------------------------------------------------------------------------------------
    // sessionLive false: paused / wrong owner / stopped
    // -------------------------------------------------------------------------------------------

    @Test
    fun pausedSessionDenies() {
        var session = false // Pause: the engine no longer holds live session ownership.
        val authority = EngineSessionAuthority(sessionLive = { session })
        val request = authority.beginRequest()
        assertNotNull("issue() does not sample the predicate; a request is still minted", request)
        assertFalse("a paused session must deny the request", request!!.allows())
        val guard = authority.requestGuard()
        assertNotNull(guard)
        assertFalse("a paused session must deny the guard", guard!!())
    }

    @Test
    fun wrongOwnerSessionDenies() {
        var owned = false // Another owner holds the session; this authority is a stale owner.
        val authority = EngineSessionAuthority(sessionLive = { owned })
        val request = authority.beginRequest()!!
        assertFalse("a non-owning session must deny", request.allows())
        assertFalse(authority.requestGuard()!!())
    }

    @Test
    fun stoppedSessionDenies() {
        var running = false // Stop from the predicate's perspective.
        val authority = EngineSessionAuthority(sessionLive = { running })
        val request = authority.beginRequest()!!
        assertFalse("a stopped session must deny", request.allows())
        assertFalse(authority.requestGuard()!!())
    }

    // -------------------------------------------------------------------------------------------
    // Unsampled Pause -> Resume: old request dead forever, fresh request valid
    // -------------------------------------------------------------------------------------------

    @Test
    fun unsampledPauseResumeDeniesOldRequestButNewRequestIsValid() {
        val authority = EngineSessionAuthority(sessionLive = { true })
        val old = authority.beginRequest()
        assertNotNull(old)

        // Pause then Resume with NO guard evaluation in between (unsampled pair): both advance epoch.
        authority.invalidate()
        authority.invalidate()

        assertFalse("a request admitted before an unsampled Pause->Resume is denied forever", old!!.allows())

        val fresh = authority.beginRequest()
        assertNotNull("a fresh request after Resume must be issuable", fresh)
        assertTrue("a fresh request bound to the resumed epoch must be admitted", fresh!!.allows())
    }

    // -------------------------------------------------------------------------------------------
    // Sticky (latched) denial
    // -------------------------------------------------------------------------------------------

    @Test
    fun firstFalseLatchesAndNeverRevives() {
        var session = true
        val authority = EngineSessionAuthority(sessionLive = { session })
        val request = authority.beginRequest()!!
        assertTrue("admitted while live", request.allows())

        session = false
        assertFalse("observation of a false predicate denies", request.allows())

        session = true
        assertFalse("an observed denial is sticky; the same request cannot revive", request.allows())

        // A genuinely fresh request is not tainted by the old latched denial.
        assertTrue(authority.beginRequest()!!.allows())
    }

    // -------------------------------------------------------------------------------------------
    // Terminal close (Stop)
    // -------------------------------------------------------------------------------------------

    @Test
    fun closeIsTerminalForCurrentAndFutureRequests() {
        val authority = EngineSessionAuthority(sessionLive = { true })
        val outstanding = authority.beginRequest()!!
        assertTrue(outstanding.allows())

        authority.close()
        assertTrue("close() must be reflected by isClosed", authority.isClosed)
        assertNull("no future request after close", authority.beginRequest())
        assertNull("no future guard after close", authority.requestGuard())
        assertFalse("an outstanding request is denied after close", outstanding.allows())

        // Still terminal: no re-opening path exists.
        assertNull(authority.beginRequest())
        assertTrue(authority.isClosed)
    }

    // -------------------------------------------------------------------------------------------
    // Issuing-thread interruption
    // -------------------------------------------------------------------------------------------

    @Test
    fun interruptedIssuingThreadDenies() {
        val authority = EngineSessionAuthority(sessionLive = { true })
        val issued = AtomicReference<NavigationAuthority.BoundRequest?>()
        val worker = Thread {
            issued.set(authority.beginRequest())
            // Park without clearing the interrupt flag until interrupted.
            while (!Thread.currentThread().isInterrupted) LockSupport.park()
        }
        worker.start()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (issued.get() == null && System.nanoTime() < deadline) Thread.sleep(1)
        assertNotNull("worker must issue a request", issued.get())

        worker.interrupt()
        worker.join(TimeUnit.SECONDS.toMillis(3))
        assertFalse("issuing worker must finish", worker.isAlive)
        assertFalse("an interrupted issuing thread must deny its request", issued.get()!!.allows())
    }

    // -------------------------------------------------------------------------------------------
    // Callback coherence: a predicate that revokes during the call is caught by the post-check
    // -------------------------------------------------------------------------------------------

    @Test
    fun predicateInvalidatingDuringCallbackDeniesSameCall() {
        lateinit var authority: EngineSessionAuthority
        authority = EngineSessionAuthority(sessionLive = { authority.invalidate(); true })
        val request = authority.beginRequest()!!
        assertFalse("epoch revoked inside the session callback must deny this call", request.allows())
        assertFalse("denial must latch", request.allows())
    }

    @Test
    fun predicateClosingDuringCallbackDeniesSameCall() {
        lateinit var authority: EngineSessionAuthority
        authority = EngineSessionAuthority(sessionLive = { authority.close(); true })
        val request = authority.beginRequest()!!
        assertFalse("close() inside the session callback must deny this call", request.allows())
        assertFalse(request.allows())
        assertTrue(authority.isClosed)
    }

    // -------------------------------------------------------------------------------------------
    // Fail-closed throwing predicate
    // -------------------------------------------------------------------------------------------

    @Test
    fun throwingPredicateFailsClosedAndCannotRevive() {
        var boom = true
        val authority = EngineSessionAuthority(sessionLive = {
            if (boom) throw IllegalStateException("session predicate failure") else true
        })
        val request = authority.beginRequest()!!
        assertFalse("a throwing predicate must fail closed", request.allows())
        boom = false
        assertFalse("an observed denial cannot revive after the predicate recovers", request.allows())
        // A fresh request is not tainted by the old failure.
        assertTrue(authority.beginRequest()!!.allows())
    }

    // -------------------------------------------------------------------------------------------
    // Retained guard across a resume: never revives
    // -------------------------------------------------------------------------------------------

    @Test
    fun retainedGuardNeverRevivesThroughResume() {
        val authority = EngineSessionAuthority(sessionLive = { true })
        val guard = authority.requestGuard()
        assertNotNull(guard)
        assertTrue("guard admitted while live", guard!!())

        // Pause (retain the guard in a caller queue) then Resume.
        authority.invalidate()
        authority.invalidate()

        assertFalse("a retained guard must stay denied after Pause->Resume", guard())
        assertFalse("the denial is permanent", guard())
    }

    // -------------------------------------------------------------------------------------------
    // Independent owners: a fresh authority is good while the old one's request is denied
    // -------------------------------------------------------------------------------------------

    @Test
    fun independentNewOwnerAuthorityAdmitsWhileOldOwnerDenied() {
        var ownerA = true
        val authorityA = EngineSessionAuthority(sessionLive = { ownerA })
        val requestA = authorityA.beginRequest()!!
        assertTrue("current owner A admits", requestA.allows())

        // Ownership moves to a new, independent authority B.
        val authorityB = EngineSessionAuthority(sessionLive = { true })
        val requestB = authorityB.beginRequest()!!
        assertTrue("new owner B admits independently", requestB.allows())

        // Owner A loses ownership.
        ownerA = false
        assertFalse("stale owner A's old request is denied", requestA.allows())
        assertTrue("new owner B's request is unaffected", requestB.allows())
    }

    // -------------------------------------------------------------------------------------------
    // Foreground is intentionally NOT required for relaunch (semantic, no foreground API exists)
    // -------------------------------------------------------------------------------------------

    /**
     * Production wires `foregroundLive = { true }` and exposes no foreground input: the
     * crash-recovery relaunch and the idle reconnect must be authorized while a foreign app is
     * foreground (foreground is never a gate on this authority). There is no foreground API to
     * call here; this asserts the admission semantic — a session-live authority admits regardless
     * of any external foreground notion.
     */
    @Test
    fun foregroundIsNotRequiredForRelaunch() {
        @Suppress("UNUSED_VARIABLE")
        var foreignAppForeground = true // a foreign app is in the foreground; must not matter.
        // The session predicate is the ONLY gate; EngineSessionAuthority wires foregroundLive=true
        // and exposes no foreground input, so admission does not depend on foreground at all.
        val authority = EngineSessionAuthority(sessionLive = { true })
        val request = authority.beginRequest()
        assertNotNull(request)
        assertTrue(
            "relaunch must be authorized with a live session and no foreground gate",
            request!!.allows(),
        )
        foreignAppForeground = false
        assertTrue("still admitted regardless of foreground", request.allows())
    }
}
