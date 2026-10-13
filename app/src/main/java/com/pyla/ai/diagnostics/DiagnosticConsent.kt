package com.pyla.ai.diagnostics

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure consent state machine for the private diagnostic-log submission.
 *
 * No Android and no coroutine dependency on purpose: this is the unit-testable
 * authority for "was this exact snapshot consented to".
 *
 * Invariants:
 *  - A snapshot is frozen exactly once, when the consent dialog opens.
 *  - Cancel / Back / outside dismissal consume the pending consent atomically
 *    and authorize no network I/O at all.
 *  - Confirm consumes its consent token exactly once; duplicate confirms, stale
 *    dialog callbacks and confirms for an already-consumed token can never
 *    authorize a second submission and can never authorize a *later* snapshot.
 *  - No automatic retry: a failed attempt stays failed until the user opens a
 *    new consent dialog and confirms again.
 */
class DiagnosticConsentMachine {

    private val lock = Any()

    private var nextToken: Long = 1L
    private var current: ConsentState = ConsentState.Idle

    /**
     * Freeze [snapshot] and open a consent request for it.
     *
     * Returns null only while a consent dialog is still open or an upload is
     * still in flight, so the UI must not present a second dialog then. A
     * *terminal* state ([Submitted]/[Failed]) is re-enterable on purpose:
     * retry after a failure, or a second submission after a receipt, requires a
     * brand new, freshly frozen snapshot and a brand new token — never a reuse
     * of the consumed one.
     */
    fun beginConsent(snapshot: DiagnosticSnapshot): ConsentRequest? = synchronized(lock) {
        val state = current
        if (state is ConsentState.AwaitingConsent || state is ConsentState.Sending) return null
        val token = nextToken++
        current = ConsentState.AwaitingConsent(token, snapshot)
        ConsentRequest(token, snapshot.reportId, snapshot.byteCount)
    }

    /** Cancel button. Consumes the pending consent; sends nothing. */
    fun cancel(token: Long): Boolean = consume(token)

    /** Back button or outside touch. Same authority as cancel. */
    fun dismiss(token: Long): Boolean = consume(token)

    /**
     * Consume the pending consent for [token]. Returns the exact frozen
     * snapshot on the first successful call and null on every later call,
     * including calls whose token belongs to an already cancelled, already
     * confirmed or superseded consent.
     */
    fun confirm(token: Long): DiagnosticSnapshot? = synchronized(lock) {
        val state = current
        if (state !is ConsentState.AwaitingConsent || state.token != token) return null
        current = ConsentState.Sending(state.snapshot.reportId)
        state.snapshot
    }

    /** Publish a verified receipt for the attempt identified by [reportId]. */
    fun onUploadVerified(reportId: String): Boolean = synchronized(lock) {
        val state = current
        if (state !is ConsentState.Sending || state.reportId != reportId) return false
        current = ConsentState.Submitted(reportId)
        true
    }

    /**
     * Publish a terminal failure for the attempt identified by [reportId].
     * The RAM log is untouched and no retry is armed.
     */
    fun onUploadFailed(reportId: String): Boolean = synchronized(lock) {
        val state = current
        if (state !is ConsentState.Sending || state.reportId != reportId) return false
        current = ConsentState.Failed(reportId)
        true
    }

    /** Current state. Safe to call from any thread. */
    fun state(): ConsentState = synchronized(lock) { current }

    /** True while a consent dialog is open and undecided. */
    fun hasPendingConsent(): Boolean = synchronized(lock) { current is ConsentState.AwaitingConsent }

    private fun consume(token: Long): Boolean = synchronized(lock) {
        val state = current
        if (state !is ConsentState.AwaitingConsent || state.token != token) return false
        current = ConsentState.Idle
        true
    }
}

/** Opaque handle returned when the consent dialog opens. */
data class ConsentRequest(val token: Long, val reportId: String, val byteCount: Int)

/** Observable consent/transfer state. */
sealed class ConsentState {
    /** No consent open, no transfer running. */
    data object Idle : ConsentState()

    /** Dialog open for exactly this frozen snapshot. */
    data class AwaitingConsent(val token: Long, val snapshot: DiagnosticSnapshot) : ConsentState()

    /** Consent consumed exactly once; the bounded worker owns this attempt. */
    data class Sending(val reportId: String) : ConsentState()

    /** Server-confirmed receipt whose id/hash/bytes/expiry all matched. */
    data class Submitted(val reportId: String) : ConsentState()

    /** Terminal failure. The RAM log is still present; no retry is armed. */
    data class Failed(val reportId: String) : ConsentState()
}

/** Status published for the UI. Carries no URL, token, body or exception text. */
sealed class SubmissionStatus {
    data object Idle : SubmissionStatus()
    data object Sending : SubmissionStatus()
    data class Submitted(val reportId: String) : SubmissionStatus()
    data class Failed(val reason: String) : SubmissionStatus()

    /** Fixed, secret-free text. Deliberately identical for every failure cause. */
    companion object {
        fun failureText(): String =
            "Submission not confirmed. The event log is still here; Sent Log opens a new confirmation."
    }
}

/**
 * Process-owned coordinator: consent machine + exactly one physical upload
 * worker + an independent logical owner that is held from admission until the
 * attempt's I/O has fully ended.
 *
 * Plain Kotlin on purpose, so every ordering rule is unit-testable without an
 * emulator. The Activity-scoped Compose panel only reads [status] at a low rate.
 */
class DiagnosticSubmissionCoordinator(
    private val snapshotProvider: () -> DiagnosticSnapshot,
    private val uploaderProvider: () -> DiagnosticUploader?,
    private val executorFactory: () -> ThreadPoolExecutor = { defaultExecutor() },
) : AutoCloseable {

    private val consent = DiagnosticConsentMachine()
    private val ownerHeld = AtomicBoolean(false)

    @Volatile
    private var executor: ThreadPoolExecutor = executorFactory()

    @Volatile
    private var failureReason: String? = null

    /**
     * Freeze the snapshot and open the consent dialog. No queue slot, no worker
     * thread, no remote capacity is allocated here.
     */
    fun requestConsent(): ConsentRequest? {
        if (ownerHeld.get()) return null
        val request = consent.beginConsent(snapshotProvider())
        if (request != null) failureReason = null
        return request
    }

    /**
     * Consume [token] exactly once and admit exactly one bounded attempt.
     * Returns false for a duplicate, stale or already-cancelled confirm.
     */
    fun confirm(token: Long): Boolean {
        val snapshot = consent.confirm(token) ?: return false
        // Independent logical owner: a second Confirm can never reach the wire,
        // and it is held until the admitted attempt's I/O has ended.
        if (!ownerHeld.compareAndSet(false, true)) {
            consent.onUploadFailed(snapshot.reportId)
            return false
        }
        try {
            executor.execute {
                try {
                    val uploader = uploaderProvider()
                    when (val outcome = if (uploader == null) {
                        UploadOutcome.Rejected(UploadFailure.NOT_CONFIGURED)
                    } else {
                        uploader.upload(snapshot)
                    }) {
                        is UploadOutcome.Verified -> consent.onUploadVerified(outcome.receipt.reportId)
                        is UploadOutcome.Rejected -> {
                            failureReason = SubmissionStatus.failureText()
                            consent.onUploadFailed(snapshot.reportId)
                        }
                    }
                } catch (t: Throwable) {
                    failureReason = SubmissionStatus.failureText()
                    consent.onUploadFailed(snapshot.reportId)
                } finally {
                    // Release the logical owner only after I/O truly ended.
                    ownerHeld.set(false)
                }
            }
        } catch (t: Throwable) {
            failureReason = SubmissionStatus.failureText()
            consent.onUploadFailed(snapshot.reportId)
            ownerHeld.set(false)
            return false
        }
        return true
    }

    /** Cancel button. Zero bytes are sent; no worker is touched. */
    fun cancel(token: Long): Boolean = consent.cancel(token)

    /** Back button or outside touch. Identical authority to cancel. */
    fun dismiss(token: Long): Boolean = consent.dismiss(token)

    fun consentState(): ConsentState = consent.state()

    /** Current UI-visible status. Safe from any thread; cheap to poll. */
    fun status(): SubmissionStatus {
        if (ownerHeld.get()) return SubmissionStatus.Sending
        return when (val s = consent.state()) {
        is ConsentState.Idle -> SubmissionStatus.Idle
        // A pending dialog has decided nothing yet, so nothing is claimed.
        is ConsentState.AwaitingConsent -> SubmissionStatus.Idle
        is ConsentState.Sending -> SubmissionStatus.Sending
        is ConsentState.Submitted -> SubmissionStatus.Submitted(s.reportId)
        is ConsentState.Failed -> SubmissionStatus.Failed(
            failureReason ?: SubmissionStatus.failureText()
        )
        }
    }

    /** Test/diagnostic hook: wait until no attempt is in I/O. */
    fun awaitIdle(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!ownerHeld.get() && consent.state() !is ConsentState.Sending) return true
            Thread.sleep(5)
        }
        return !ownerHeld.get() && consent.state() !is ConsentState.Sending
    }

    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        /**
         * One physical worker plus a one-position handoff queue. `allowCoreThread
         * TimeOut` retires the idle thread without removing the bounded handoff
         * position, so a released logical slot can never be spuriously rejected
         * by a zero-capacity executor while the previous worker still unwinds.
         */
        fun defaultExecutor(): ThreadPoolExecutor =
            ThreadPoolExecutor(
                1, 1, 30L, TimeUnit.SECONDS,
                LinkedBlockingQueue<Runnable>(1),
                { r -> Thread(r, "pyla-diag-upload").apply { isDaemon = true } },
            ).apply { allowCoreThreadTimeOut(true) }
    }
}