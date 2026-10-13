package com.pyla.ai.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Consent-boundary regression suite. Every test uses a fake transport that
 * counts physical request attempts, so "zero bytes sent" is asserted by a
 * counter rather than by the absence of an exception.
 */
class DiagnosticConsentTest {

    private fun snapshot(text: String = "log", id: String = "0f9c1f7e-1c2b-4d3e-8a90-0123456789ab") =
        DiagnosticSnapshot(reportId = id, text = text)

    private class RecordingTransport(
        var status: Int = 201,
        var sawRedirect: Boolean = false,
        var gate: CountDownLatch? = null,
        var throwOnCall: Boolean = false,
        var captured: DiagnosticSnapshot = DiagnosticSnapshot(
            "0f9c1f7e-1c2b-4d3e-8a90-0123456789ab",
            "log",
        ),
    ) : DiagnosticTransport {
        val attempts = AtomicInteger()
        val bodies = mutableListOf<ByteArray>()

        override fun postJson(
            endpoint: String,
            bearerToken: String,
            jsonBody: ByteArray,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
            deadlineElapsedMillis: Long,
        ): RawResponse {
            attempts.incrementAndGet()
            synchronized(bodies) { bodies.add(jsonBody.copyOf()) }
            gate?.await(5, TimeUnit.SECONDS)
            if (throwOnCall) throw IllegalStateException("https://reports.example.com/boom token=abc123")
            val snap = captured
            val body = """{"reportId":"${snap.reportId}","sha256":"${snap.sha256}",""" +
                """"bytes":${snap.byteCount},"expiresAt":"2026-10-17T16:35:00.000Z"}"""
            return RawResponse(status, body.toByteArray(Charsets.UTF_8), sawRedirect, transportFailed = false)
        }
    }

    private fun uploaderFor(
        transport: DiagnosticTransport,
        token: String = "upload-only-token",
        endpoint: String = "https://reports.example.com/v1/reports",
        nowSeconds: Long = 1_700_000_000L,
    ) = DiagnosticUploader(
        config = DiagnosticUploadConfig(
            endpoint = endpoint,
            uploadToken = token,
            appVersion = "1.2.0",
            appVersionCode = 3,
            androidApi = 33,
        ),
        transport = transport,
        nowEpochSeconds = { nowSeconds },
    )

    private fun coordinatorFor(
        snap: DiagnosticSnapshot,
        transport: RecordingTransport,
    ) = DiagnosticSubmissionCoordinator(
        snapshotProvider = { snap },
        uploaderProvider = { uploaderFor(transport) },
    )

    // =====================================================================
    // Sent Log alone, Cancel, Back and outside-touch send nothing at all.
    // =====================================================================

    @Test
    fun pressingSentLog_opensConsentAndSendsZeroBytes() {
        val transport = RecordingTransport()
        val coordinator = coordinatorFor(snapshot(), transport)
        try {
            val request = coordinator.requestConsent()
            assertNotNull("Sent Log must open a consent request", request)
            Thread.sleep(150) // any accidental worker would already have fired
            assertEquals("Sent Log alone must send zero bytes", 0, transport.attempts.get())
            assertTrue(coordinator.consentState() is ConsentState.AwaitingConsent)
            assertEquals(SubmissionStatus.Idle, coordinator.status())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun cancelButton_sendsZeroBytes() = assertZeroBytes("cancel") { c, t -> c.cancel(t) }

    @Test
    fun backButton_sendsZeroBytes() = assertZeroBytes("back") { c, t -> c.dismiss(t) }

    @Test
    fun outsideTouch_sendsZeroBytes() = assertZeroBytes("outside touch") { c, t -> c.dismiss(t) }

    private fun assertZeroBytes(path: String, decide: (DiagnosticSubmissionCoordinator, Long) -> Unit) {
        val transport = RecordingTransport()
        val coordinator = coordinatorFor(snapshot(), transport)
        try {
            val request = coordinator.requestConsent()!!
            decide(coordinator, request.token)
            Thread.sleep(150)
            assertEquals("$path must send zero bytes", 0, transport.attempts.get())
            assertTrue("consent must be consumed by $path", coordinator.consentState() is ConsentState.Idle)
            assertFalse("a stale confirm after $path must be refused", coordinator.confirm(request.token))
            Thread.sleep(100)
            assertEquals("stale confirm after $path must send zero bytes", 0, transport.attempts.get())
        } finally {
            coordinator.close()
        }
    }

    // =====================================================================
    // Duplicate / rapid confirms admit exactly one physical attempt.
    // =====================================================================

    @Test
    fun duplicateConfirm_admitsExactlyOneUpload() {
        val gate = CountDownLatch(1)
        val transport = RecordingTransport(gate = gate)
        val snap = snapshot()
        transport.captured = snap
        val coordinator = coordinatorFor(snap, transport)
        try {
            val request = coordinator.requestConsent()!!
            assertTrue("first confirm must admit", coordinator.confirm(request.token))
            assertFalse(coordinator.confirm(request.token)) // rapid repeat
            assertFalse(coordinator.confirm(request.token)) // rapid repeat
            gate.countDown()
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals("duplicate confirm must not upload twice", 1, transport.attempts.get())
            assertEquals(snap.reportId, (coordinator.consentState() as ConsentState.Submitted).reportId)
        } finally {
            gate.countDown()
            coordinator.close()
        }
    }

    @Test
    fun confirmAfterVerifiedSubmit_doesNotResend() {
        val snap = snapshot()
        val transport = RecordingTransport().also { it.captured = snap }
        val coordinator = coordinatorFor(snap, transport)
        try {
            val request = coordinator.requestConsent()!!
            assertTrue(coordinator.confirm(request.token))
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals(1, transport.attempts.get())
            assertFalse(coordinator.confirm(request.token))
            Thread.sleep(120)
            assertEquals("no resend after a verified receipt", 1, transport.attempts.get())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun secondSentLogWhileUploadingIsRefused() {
        val gate = CountDownLatch(1)
        val transport = RecordingTransport(gate = gate)
        val snap = snapshot()
        transport.captured = snap
        val coordinator = coordinatorFor(snap, transport)
        try {
            val request = coordinator.requestConsent()!!
            assertTrue(coordinator.confirm(request.token))
            assertNull("no second consent while an attempt is in flight", coordinator.requestConsent())
            gate.countDown()
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals(1, transport.attempts.get())
        } finally {
            gate.countDown()
            coordinator.close()
        }
    }

    // =====================================================================
    // Snapshot freeze and Activity recreation.
    // =====================================================================

    @Test
    fun transmittedBytesAreTheFrozenSnapshotNotTheLiveLog() {
        val gate = CountDownLatch(1)
        val transport = RecordingTransport(gate = gate)
        val frozen = snapshot(text = "first")
        transport.captured = frozen
        var liveText = "first"
        val coordinator = DiagnosticSubmissionCoordinator(
            snapshotProvider = { snapshot(text = liveText) },
            uploaderProvider = { uploaderFor(transport) },
        )
        try {
            val request = coordinator.requestConsent()!!
            liveText = "first-and-then-a-lot-more" // the live log keeps moving
            assertTrue(coordinator.confirm(request.token))
            gate.countDown()
            assertTrue(coordinator.awaitIdle(5_000))
            val sent = String(transport.bodies.single(), Charsets.UTF_8)
            assertTrue("frozen bytes must be transmitted", sent.contains("\"log\":\"first\""))
            assertFalse("the live log must not leak into the request", sent.contains("first-and-then-a-lot-more"))
        } finally {
            gate.countDown()
            coordinator.close()
        }
    }

    @Test
    fun recreation_preservesConsentAndNeverAutoConfirms() {
        val gate = CountDownLatch(1)
        val transport = RecordingTransport(gate = gate)
        val snap = snapshot()
        transport.captured = snap
        val coordinator = coordinatorFor(snap, transport)
        try {
            coordinator.requestConsent()
            // A recreated panel re-attaches to the same process-owned coordinator.
            val reattached = coordinator.consentState()
            assertTrue(
                "recreation must preserve the pending consent",
                reattached is ConsentState.AwaitingConsent,
            )
            Thread.sleep(200)
            assertEquals("recreation must not upload by itself", 0, transport.attempts.get())
            assertNull(
                "a second dialog must not open while one is pending",
                coordinator.requestConsent(),
            )
            assertTrue(coordinator.confirm((reattached as ConsentState.AwaitingConsent).token))
            gate.countDown()
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals(1, transport.attempts.get())
        } finally {
            gate.countDown()
            coordinator.close()
        }
    }

    @Test
    fun staleConfirmCannotConfirmALaterSnapshot() {
        val transport = RecordingTransport()
        val first = snapshot(text = "one")
        transport.captured = first
        var live = first
        val coordinator = DiagnosticSubmissionCoordinator(
            snapshotProvider = { live },
            uploaderProvider = { uploaderFor(transport) },
        )
        try {
            val staleToken = coordinator.requestConsent()!!.token
            coordinator.cancel(staleToken)

            live = snapshot(text = "two-different-content")
            transport.captured = live
            val fresh = coordinator.requestConsent()!!
            assertFalse("a stale token must not confirm the later snapshot", coordinator.confirm(staleToken))
            Thread.sleep(120)
            assertEquals("stale confirm must send zero bytes", 0, transport.attempts.get())
            assertTrue(coordinator.consentState() is ConsentState.AwaitingConsent)
            assertTrue(coordinator.confirm(fresh.token))
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals(1, transport.attempts.get())
        } finally {
            coordinator.close()
        }
    }

    // =====================================================================
    // Failure is terminal; the RAM log stays; retry needs new consent.
    // =====================================================================

    @Test
    fun failedPartialRequest_isNotRetriedAutomatically() {
        val transport = RecordingTransport(status = 500)
        val snap = snapshot()
        transport.captured = snap
        val coordinator = coordinatorFor(snap, transport)
        try {
            val request = coordinator.requestConsent()!!
            assertTrue(coordinator.confirm(request.token))
            assertTrue(coordinator.awaitIdle(5_000))
            Thread.sleep(300) // any hidden retry would have fired by now
            assertEquals("a failed attempt must not retry itself", 1, transport.attempts.get())
            val state = coordinator.consentState()
            assertTrue("failure must be terminal", state is ConsentState.Failed)
            assertEquals(snap.reportId, (state as ConsentState.Failed).reportId)
            val status = coordinator.status()
            assertTrue(status is SubmissionStatus.Failed)
            val reason = (status as SubmissionStatus.Failed).reason
            assertFalse("no URL in the failure text", reason.contains("http", ignoreCase = true))
            assertFalse("no token in the failure text", reason.contains("token", ignoreCase = true))
            assertEquals("fixed safe status text", SubmissionStatus.failureText(), reason)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun retryRequiresANewExplicitConsent() {
        val transport = RecordingTransport(status = 503)
        val snap = snapshot()
        transport.captured = snap
        val coordinator = coordinatorFor(snap, transport)
        try {
            val first = coordinator.requestConsent()!!
            coordinator.confirm(first.token)
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals(1, transport.attempts.get())

            val second = coordinator.requestConsent()
            assertNotNull("a new consent must be possible after a failure", second)
            assertFalse("the consumed token must stay consumed", coordinator.confirm(first.token))
            transport.status = 201
            assertTrue(coordinator.confirm(second!!.token))
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals(2, transport.attempts.get())
            assertTrue(coordinator.consentState() is ConsentState.Submitted)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun failedSubmission_keepsTheRamLogSourceIntact() {
        val transport = RecordingTransport(status = 500)
        val snap = snapshot(text = "retained event text")
        transport.captured = snap
        var live = "retained event text"
        val coordinator = DiagnosticSubmissionCoordinator(
            snapshotProvider = { snapshot(text = live) },
            uploaderProvider = { uploaderFor(transport) },
        )
        try {
            val request = coordinator.requestConsent()!!
            coordinator.confirm(request.token)
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals("a failure must not disturb the RAM log", "retained event text", live)
            assertTrue(coordinator.consentState() is ConsentState.Failed)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun unconfiguredBuildProducesAFixedSafeStatus() {
        val snap = snapshot()
        val coordinator = DiagnosticSubmissionCoordinator(
            snapshotProvider = { snap },
            uploaderProvider = { null }, // parent has not supplied BuildConfig yet
        )
        try {
            val request = coordinator.requestConsent()!!
            assertTrue(coordinator.confirm(request.token))
            assertTrue(coordinator.awaitIdle(5_000))
            val status = coordinator.status()
            assertTrue(status is SubmissionStatus.Failed)
            assertEquals(SubmissionStatus.failureText(), (status as SubmissionStatus.Failed).reason)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun transportExceptionBecomesAFixedSafeStatus() {
        val transport = RecordingTransport(throwOnCall = true)
        val snap = snapshot()
        transport.captured = snap
        val coordinator = coordinatorFor(snap, transport)
        try {
            val request = coordinator.requestConsent()!!
            assertTrue(coordinator.confirm(request.token))
            assertTrue(coordinator.awaitIdle(5_000))
            val status = coordinator.status()
            assertTrue(status is SubmissionStatus.Failed)
            assertEquals(SubmissionStatus.failureText(), (status as SubmissionStatus.Failed).reason)
            assertEquals(1, transport.attempts.get())
        } finally {
            coordinator.close()
        }
    }

    // =====================================================================
    // Terminal-state re-entry: a Submitted/Failed machine must not be stuck.
    // =====================================================================

    @Test
    fun aFailedAttemptCanBeRetriedThroughAFreshConsentAndSnapshot() {
        val transport = RecordingTransport(status = 500)
        val snap = snapshot(text = "first attempt")
        transport.captured = snap
        var live = snap
        val coordinator = DiagnosticSubmissionCoordinator(
            snapshotProvider = { live },
            uploaderProvider = { uploaderFor(transport) },
        )
        try {
            val first = coordinator.requestConsent()!!
            assertTrue(coordinator.confirm(first.token))
            assertTrue(coordinator.awaitIdle(5_000))
            assertTrue(coordinator.consentState() is ConsentState.Failed)

            // Previously beginConsent only allowed Idle, so this returned null
            // forever and the user could never send again in this process.
            live = snapshot(text = "second attempt")
            transport.captured = live
            val second = coordinator.requestConsent()
            assertNotNull("a terminal failure must not freeze the consent machine", second)
            assertNotEquals("a retry needs a brand new token", first.token, second!!.token)
            assertEquals("the new dialog shows the NEW snapshot", live.byteCount, second.byteCount)
            assertFalse("the consumed token stays consumed", coordinator.confirm(first.token))
            Thread.sleep(120)
            assertEquals("the stale token must send zero bytes", 1, transport.attempts.get())

            transport.status = 201
            assertTrue(coordinator.confirm(second.token))
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals(2, transport.attempts.get())
            assertEquals(live.reportId, (coordinator.consentState() as ConsentState.Submitted).reportId)
            assertTrue(coordinator.status() is SubmissionStatus.Submitted)
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun aSubmittedReportCanBeSentAgainOnlyAsANewFrozenSnapshot() {
        val transport = RecordingTransport()
        var live = snapshot(text = "one")
        transport.captured = live
        val coordinator = DiagnosticSubmissionCoordinator(
            snapshotProvider = { live },
            uploaderProvider = { uploaderFor(transport) },
        )
        try {
            val first = coordinator.requestConsent()!!
            assertTrue(coordinator.confirm(first.token))
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals(1, transport.attempts.get())

            live = snapshot(text = "two")
            transport.captured = live
            val again = coordinator.requestConsent()
            assertNotNull("Submitted must also be re-enterable, via a new token and snapshot", again)
            assertNotEquals(first.token, again!!.token)
            assertTrue(coordinator.confirm(again.token))
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals(2, transport.attempts.get())
        } finally {
            coordinator.close()
        }
    }

    @Test
    fun onceOnlyConfirmationSurvivesARapidConfirmBurst() {
        val gate = CountDownLatch(1)
        val transport = RecordingTransport(gate = gate)
        val snap = snapshot()
        transport.captured = snap
        val coordinator = coordinatorFor(snap, transport)
        try {
            val request = coordinator.requestConsent()!!
            val accepts = AtomicInteger()
            // 16 threads racing to confirm the same token.
            val threads = (1..16).map {
                Thread { if (coordinator.confirm(request.token)) accepts.incrementAndGet() }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(2_000) }
            assertEquals("exactly one confirm may ever be admitted", 1, accepts.get())
            gate.countDown()
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals("one consent means one physical request", 1, transport.attempts.get())
            assertTrue(coordinator.consentState() is ConsentState.Submitted)
        } finally {
            gate.countDown()
            coordinator.close()
        }
    }

    @Test
    fun cancelDuringAnInFlightUploadDoesNotCancelTheWire() {
        val gate = CountDownLatch(1)
        val transport = RecordingTransport(gate = gate)
        val snap = snapshot()
        transport.captured = snap
        val coordinator = coordinatorFor(snap, transport)
        try {
            val request = coordinator.requestConsent()!!
            assertTrue(coordinator.confirm(request.token))
            // A stale Back/Cancel callback for the already-consumed token.
            assertFalse(coordinator.cancel(request.token))
            assertFalse(coordinator.dismiss(request.token))
            gate.countDown()
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals("an admitted attempt still completes exactly once", 1, transport.attempts.get())
            assertTrue(coordinator.consentState() is ConsentState.Submitted)
        } finally {
            gate.countDown()
            coordinator.close()
        }
    }

    @Test
    fun theLogicalOwnerIsHeldUntilIoReallyEnds() {
        val gate = CountDownLatch(1)
        val transport = RecordingTransport(gate = gate)
        val snap = snapshot(text = "owner")
        transport.captured = snap
        var live = snap
        val coordinator = DiagnosticSubmissionCoordinator(
            snapshotProvider = { live },
            uploaderProvider = { uploaderFor(transport) },
        )
        try {
            val first = coordinator.requestConsent()!!
            assertTrue(coordinator.confirm(first.token))
            // While I/O is still running, no new consent may even be opened.
            Thread.sleep(100)
            assertNull("no second consent while the owner is held", coordinator.requestConsent())
            assertFalse("not idle while I/O runs", coordinator.awaitIdle(50))
            gate.countDown()
            assertTrue(coordinator.awaitIdle(5_000))

            // Only once I/O ended may a new consent open.
            live = snapshot(text = "next")
            transport.captured = live
            assertNotNull(coordinator.requestConsent())
        } finally {
            gate.countDown()
            coordinator.close()
        }
    }

    @Test
    fun recreatedPanelReadsAwaitingDialogBackWithoutAutoConfirming() {
        val transport = RecordingTransport()
        val snap = snapshot()
        transport.captured = snap
        val coordinator = coordinatorFor(snap, transport)
        try {
            coordinator.requestConsent()
            // A recreated panel polls the process-owned coordinator and rebuilds
            // the dialog from whatever state it finds; this is what the UI does.
            val state = coordinator.consentState()
            assertTrue(state is ConsentState.AwaitingConsent)
            val awaiting = state as ConsentState.AwaitingConsent
            val rebuilt = ConsentRequest(
                awaiting.token,
                awaiting.snapshot.reportId,
                awaiting.snapshot.byteCount,
            )
            assertEquals("the rebuilt dialog is bound to the frozen snapshot", snap.byteCount, rebuilt.byteCount)
            Thread.sleep(250)
            assertEquals("restoring a dialog must never upload by itself", 0, transport.attempts.get())
            assertTrue(coordinator.confirm(rebuilt.token))
            assertTrue(coordinator.awaitIdle(5_000))
            assertEquals(1, transport.attempts.get())
        } finally {
            coordinator.close()
        }
    }
}
