package com.pyla.ai.input

import com.pyla.ai.input.InputGestureComposition.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused regression tests for the combined movement+attack gesture composition.
 *
 * These exercise the real production seam ([InputGestureComposition], called by
 * `InputService.pump`) with plain data — no android.jar, no device, no clock.
 *
 * They pin, from the actual AOSP `MotionEventInjector.prepareToContinueOldGesture` algorithm:
 *
 *  - **RED / defect**: a gesture built the way the service emitted it before this fix — one held
 *    joystick continuation and one fresh attack tap, both at `startTime = 0` — puts a new point in
 *    the first step alongside the continuation, so the platform's count does not reach `0` and the
 *    dispatch is cancelled. This is the reported `strokes=2` cancellation.
 *  - **GREEN / fix**: offsetting the new pointer by 1 ms leaves the first step containing only the
 *    continuation, the platform accepts the gesture, and the new pointer opens on the next step.
 *  - Non-mixed gestures (pure continuation, pure new, two continuations, two new) are unchanged.
 *  - The gesture's total duration is preserved, so cadence is not widened.
 *  - A cancellation preserves the held channel's logical want (the documented recovery contract).
 */
class InputGestureCompositionTest {

    private fun cont(ms: Long) = Stroke(isContinuation = true, durationMs = ms)
    private fun new(ms: Long) = Stroke(isContinuation = false, durationMs = ms)

    /** A faithful transcription of the pre-fix `InputService.pump` scheduling: every stroke at 0. */
    private fun legacySchedule(strokes: List<Stroke>): List<InputGestureComposition.Scheduled> =
        strokes.map { InputGestureComposition.Scheduled(0L, it.durationMs.coerceAtLeast(1L)) }

    private fun firstStepHasNewPoint(
        scheduled: List<InputGestureComposition.Scheduled>,
        strokes: List<Stroke>,
    ): Boolean {
        val t0 = scheduled.minOf { it.startTimeMs }
        return strokes.indices.any { scheduled[it].startTimeMs == t0 && !strokes[it].isContinuation }
    }

    // ------------------------------------------------------------------ RED: current composition

    @Test
    fun `held joystick continuation plus fresh attack tap at startTime 0 is rejected by the platform`() {
        // The reported shape: dispatch strokes=2, one continuation (joystick) + one new (attack tap).
        val strokes = listOf(cont(32L), new(60L))
        val legacy = legacySchedule(strokes)

        // The new tap shares the first step with the continuation — the exact AOSP failure mode.
        assertTrue(firstStepHasNewPoint(legacy, strokes))

        // Modelled prepareToContinueOldGesture: 2 first-step points - 1 open joystick = 1 != 0.
        val net = legacy.count { it.startTimeMs == legacy.minOf { s -> s.startTimeMs } } - 1
        assertEquals(1, net)
        assertFalse(
            "the pre-fix composition must be shown as platform-rejected (the defect)",
            legacyNetAccepts(legacy, strokes, openStrokes = 1),
        )
    }

    /** Same count arithmetic as [InputGestureComposition.preparesToContinue], for the legacy layout. */
    private fun legacyNetAccepts(
        scheduled: List<InputGestureComposition.Scheduled>,
        strokes: List<Stroke>,
        openStrokes: Int,
    ): Boolean {
        val t0 = scheduled.minOf { it.startTimeMs }
        val first = strokes.indices.filter { scheduled[it].startTimeMs == t0 }
        if (first.none { strokes[it].isContinuation }) return false
        return first.size - openStrokes == 0
    }

    // ------------------------------------------------------------------ GREEN: the fix

    @Test
    fun `offsetting the new pointer makes the mixed gesture continuable`() {
        val strokes = listOf(cont(32L), new(60L))
        val scheduled = InputGestureComposition.schedule(strokes)

        // Continuation stays contiguous at 0; the new pointer opens one step later.
        assertEquals(0L, scheduled[0].startTimeMs)
        assertEquals(1L, scheduled[1].startTimeMs)
        assertEquals(32L, scheduled[0].durationMs)
        assertEquals(59L, scheduled[1].durationMs) // shortened by the offset: end time preserved

        // The first step now contains only the continuation.
        assertFalse(firstStepHasNewPoint(scheduled, strokes))
        assertTrue(
            "the fixed composition must be accepted as a continuation",
            InputGestureComposition.preparesToContinue(oldOpenStrokes = 1, strokes = strokes),
        )
    }

    @Test
    fun `the attack tap still lands during the held joystick, and total duration is preserved`() {
        val strokes = listOf(cont(96L), new(60L))
        val scheduled = InputGestureComposition.schedule(strokes)
        val newStart = scheduled[1].startTimeMs
        val newEnd = scheduled[1].endTimeMs
        // Overlaps the joystick hold (0..96) so movement and attack really coincide.
        assertTrue(newStart in 1L..95L)
        assertTrue(newEnd in 1L..96L)
        // Gesture length is still the longest stroke, not the sum: cadence is not widened.
        assertEquals(96L, InputGestureComposition.gestureDurationMs(scheduled))
        assertEquals(
            96L,
            InputGestureComposition.gestureDurationMs(legacySchedule(strokes)),
        )
    }

    @Test
    fun `a longer finite attack keeps the gesture length it already had`() {
        // Attack (60 ms) is the longest stroke; offsetting must not extend the gesture.
        val strokes = listOf(cont(32L), new(60L))
        val scheduled = InputGestureComposition.schedule(strokes)
        assertEquals(60L, InputGestureComposition.gestureDurationMs(scheduled))
    }

    // ------------------------------------------------------------------ non-mixed gestures unchanged

    @Test
    fun `a pure continuation gesture is never offset`() {
        val strokes = listOf(cont(96L))
        assertFalse(InputGestureComposition.isMixed(strokes))
        val s = InputGestureComposition.schedule(strokes)
        assertEquals(0L, s[0].startTimeMs)
        assertEquals(96L, s[0].durationMs)
        assertTrue(InputGestureComposition.preparesToContinue(1, strokes))
    }

    @Test
    fun `a pure new-pointer gesture is never offset`() {
        val strokes = listOf(new(40L), new(500L))
        assertFalse(InputGestureComposition.isMixed(strokes))
        val s = InputGestureComposition.schedule(strokes)
        assertTrue(s.all { it.startTimeMs == 0L })
        assertEquals(listOf(40L, 500L), s.map { it.durationMs })
        // Pure new work is not a "continuing gesture", so the platform never runs the count.
        assertFalse(InputGestureComposition.preparesToContinue(1, strokes))
    }

    @Test
    fun `two held channels plus a new tap offset only the new tap`() {
        val strokes = listOf(cont(32L), cont(96L), new(40L))
        assertTrue(InputGestureComposition.isMixed(strokes))
        val s = InputGestureComposition.schedule(strokes)
        assertEquals(0L, s[0].startTimeMs)
        assertEquals(0L, s[1].startTimeMs)
        assertEquals(1L, s[2].startTimeMs)
        assertTrue(InputGestureComposition.preparesToContinue(oldOpenStrokes = 2, strokes = strokes))
    }

    @Test
    fun `a one-millisecond stroke cannot be shortened below one millisecond`() {
        val strokes = listOf(cont(96L), new(1L))
        val s = InputGestureComposition.schedule(strokes)
        assertEquals(1L, s[1].startTimeMs)
        assertEquals(1L, s[1].durationMs)
    }

    // ------------------------------------------------------------------ recovery contract (verify)

    @Test
    fun `a cancellation preserves the held channel's logical want`() {
        val held = InputCancellationRecovery.afterCancel(wantDown = true)
        assertTrue("a held joystick must still want down after a cancel", held.wantDown)
        assertFalse("the pointer establishment must be dropped", held.established)

        val idle = InputCancellationRecovery.afterCancel(wantDown = false)
        assertFalse(idle.wantDown)
        assertFalse(idle.established)
    }
}
