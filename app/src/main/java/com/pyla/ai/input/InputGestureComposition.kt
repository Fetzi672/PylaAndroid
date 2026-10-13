package com.pyla.ai.input

/**
 * Pure, Android-free scheduling rule for one `GestureDescription` that may mix a **continued**
 * pointer with a **new** one.
 *
 * ── Why this exists (AOSP-verified, not assumed) ─────────────────────────────────────────────
 * When an `AccessibilityService.dispatchGesture` is delivered while a previous injected gesture is
 * still open, the platform decides whether the new gesture may continue the old one in
 * `MotionEventInjector.prepareToContinueOldGesture` (AOSP `android-15`
 * `services/accessibility/java/com/android/server/accessibility/MotionEventInjector.java:258-304`):
 *
 *  * it walks the **first step** of the new gesture and, for every point there, runs
 *    `numContinuedStrokes++` (line 295) — **including a brand-new touch point
 *    (`mIsStartOfPath == true`), which is only skipped for validation, not for the count**;
 *  * it then subtracts only the old, still-open points (lines 297-303);
 *  * it returns `true` **only when the total is exactly 0**.
 *
 * A gesture whose first step holds a continued point *and* a fresh point therefore counts one extra
 * and returns `false`. `injectEventsMainThread` (line 204) then takes the
 * `cancelAnyPendingInjectedEvents()` + `notifyService(sequence, false)` branch: the dispatch is
 * reported failed and the in-progress gesture is torn down through the hardcoded single-pointer
 * `ACTION_CANCEL` (line 324). That is exactly the reported combined movement+attack symptom — one
 * held joystick continuation plus one fresh attack tap share the first step at `startTime == 0`,
 * so `strokes=2` dispatches are cancelled while pure-continuation (`strokes=1`) dispatches proceed.
 *
 * A shorter continuation stroke is **not** by itself a cause: a `willContinue == true` stroke is
 * never `mIsEndOfPath` (`GestureDescription.java:154`) and `appendMoveEventIfNeeded`
 * (`MotionEventInjector.java:399-407`) never removes a point that is merely absent from a later
 * step, so the open pointer survives even when its own duration is shorter than the gesture's.
 *
 * ── The correction ───────────────────────────────────────────────────────────────────────────
 * When a gesture contains at least one continued stroke, every **new** stroke starts at
 * [NEW_POINTER_OFFSET_MS] instead of `0`, so the first step contains only continued points. The new
 * pointer then opens on the following step (`appendDownEvents`, the platform's MOVE→UP→DOWN order),
 * giving the intended simultaneous movement+attack. Continued strokes keep `startTime = 0`; a
 * continuation must stay contiguous. A gesture with no continuation, or no new stroke, is
 * unchanged — ordinary discrete taps, swipes and simultaneous play are untouched.
 *
 * This is a scheduling correction inside one accepted gesture: no retry, no replay, no widened
 * cadence or freshness, and no change to any queued/authority decision.
 */
object InputGestureComposition {

    /** One millisecond: the smallest non-zero start time the platform accepts for a new point. */
    const val NEW_POINTER_OFFSET_MS = 1L

    /** One stroke's role and nominal duration, before the composition rule is applied. */
    data class Stroke(val isContinuation: Boolean, val durationMs: Long)

    /** The start time and duration actually handed to the platform for one stroke. */
    data class Scheduled(val startTimeMs: Long, val durationMs: Long) {
        val endTimeMs: Long get() = startTimeMs + durationMs
    }

    /** True when the gesture mixes a continued pointer with a new one. */
    fun isMixed(strokes: List<Stroke>): Boolean =
        strokes.any { it.isContinuation } && strokes.any { !it.isContinuation }

    /** Start time for one stroke. A continuation is always contiguous (`0`). */
    fun startTimeFor(isContinuation: Boolean, mixed: Boolean): Long =
        if (!isContinuation && mixed) NEW_POINTER_OFFSET_MS else 0L

    /**
     * Duration for one stroke, preserving the requested nominal duration.
     *
     * Only a **new** stroke inside a mixed gesture is shortened by the offset, so its ending time is
     * unchanged; a continuation and every stroke of a non-mixed gesture keep their nominal duration.
     */
    fun durationFor(nominalMs: Long, isContinuation: Boolean, mixed: Boolean): Long {
        val nominal = nominalMs.coerceAtLeast(1L)
        if (isContinuation || !mixed) return nominal
        return (nominal - NEW_POINTER_OFFSET_MS).coerceAtLeast(1L)
    }

    /** Apply the rule to a whole gesture, in order. */
    fun schedule(strokes: List<Stroke>): List<Scheduled> {
        val mixed = isMixed(strokes)
        return strokes.map { s ->
            Scheduled(
                startTimeMs = startTimeFor(s.isContinuation, mixed),
                durationMs = durationFor(s.durationMs, s.isContinuation, mixed),
            )
        }
    }

    /** Total gesture duration, i.e. the platform's own `max(startTime + duration)`. */
    fun gestureDurationMs(scheduled: List<Scheduled>): Long =
        scheduled.maxOfOrNull { it.endTimeMs } ?: 0L

    /**
     * Faithful pure model of `MotionEventInjector.prepareToContinueOldGesture` for a gesture that
     * [oldOpenStrokes] old pointers are still holding.
     *
     * The continued-stroke-id and exact-position validation (lines 265-291) is assumed to pass:
     * `InputService` always continues from the accepted stroke's recorded end position. The
     * decisive count is modelled exactly — points present at the first step, minus old open points,
     * must reach `0`.
     *
     * @return `true` when the platform would accept this gesture as a continuation, `false` when it
     *         would fall back to cancelling the dispatch (the reported defect).
     */
    fun preparesToContinue(oldOpenStrokes: Int, strokes: List<Stroke>): Boolean {
        if (strokes.isEmpty() || oldOpenStrokes <= 0) return false
        val scheduled = schedule(strokes)
        val firstStepMs = scheduled.minOf { it.startTimeMs }
        val firstStepIndices = strokes.indices.filter { scheduled[it].startTimeMs == firstStepMs }
        if (firstStepIndices.none { strokes[it].isContinuation }) return false // not a continuing gesture
        val extraNewPoints = firstStepIndices.count { !strokes[it].isContinuation }
        val continuedPoints = firstStepIndices.size
        val net = continuedPoints - oldOpenStrokes
        return net == 0 && extraNewPoints == 0
    }
}

/**
 * One held channel's state after a cancellation, and the contract that makes recovery work.
 *
 * A cancellation means Android has already lifted every pointer of the cancelled gesture, so the
 * established chain must be dropped (`isDown = false`, no `lastStroke`) and the next dispatch presses
 * fresh. The **logical want must survive**: `WindowController.move` suppresses a repeated command for
 * an unchanged target (`unchanged && !reApplyMovement -> return`), so if a cancellation dropped
 * `wantDown` the joystick would never be re-pressed and movement would stop permanently.
 *
 * This pins the behaviour of `InputService.Channel.reset()` (which clears only `lastStroke`/`isDown`,
 * preserving `wantDown`) so a future edit cannot silently break recovery. It is a *verification*
 * contract: it changes no behaviour.
 */
data class ChannelRecovery(val wantDown: Boolean, val established: Boolean)

object InputCancellationRecovery {
    /**
     * @param wantDown the channel's logical want before the cancellation.
     * @return the post-cancellation state: want preserved, pointer establishment dropped.
     */
    fun afterCancel(wantDown: Boolean): ChannelRecovery =
        ChannelRecovery(wantDown = wantDown, established = false)
}
