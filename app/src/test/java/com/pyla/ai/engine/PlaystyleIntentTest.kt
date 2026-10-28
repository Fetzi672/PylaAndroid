package com.pyla.ai.engine

import com.pyla.ai.pyla.PyDict
import com.pyla.ai.pyla.PyList
import com.pyla.ai.pyla.PyNone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused contract tests for the strategy intent bridge: argument forms, coalescing, whole-tick
 * rejection, movement classification and the isolated persistence copy. Pure JVM, no Android.
 */
class PlaystyleIntentTest {

    private fun collector() = PlaystyleIntentCollector()

    private fun attack(c: PlaystyleIntentCollector, args: List<Any?> = emptyList(), kwargs: Map<String, Any?> = emptyMap()) {
        c.requestAttack(args, kwargs)
    }

    @Test
    fun persistenceSnapshotRejectsCyclesInsteadOfOverflowingTheStack() {
        val cycle = PyList(mutableListOf())
        cycle.items.add(cycle)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { deepCopyPlaystyleValue(cycle) }
    }

    @Test
    fun persistenceSnapshotRejectsOversizedAndNonDataValues() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            deepCopyPlaystyleValue(PyList(MutableList<Any>(4097) { it.toLong() }))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { deepCopyPlaystyleValue(Any()) }
    }

    @Test
    fun doubleVectorThatOverflowsNativeFloatRejectsBeforeAnyBufferedAttack() {
        val c = collector()
        c.requestAttack(emptyList(), emptyMap())
        assertTrue(PlaystyleDecision.decide(PlaystyleMovementOutput.Vector(1e100, 0.0), c.finish()) is PlaystyleDecisionResult.Rejected)
    }

    @Test
    fun shippedChargeReleaseThenReholdKeepsAnUnconditionalReleaseBoundary() {
        val c = collector()
        assertTrue(c.requestAttack(emptyList(), mapOf("touch_up" to true, "touch_down" to false)))
        assertTrue(c.requestAttack(emptyList(), mapOf("touch_up" to false, "touch_down" to true)))
        val batch = c.finish()
        assertTrue("shipped charge rollover is valid, not a malformed tick", batch.isWhole)
        assertEquals(listOf(PlaystyleIntent.Release), batch.intents)
    }

    @Test
    fun defaultAttackIsOneTap() {
        val c = collector()
        assertTrue(c.requestAttack(emptyList(), emptyMap()))
        val batch = c.finish()
        assertTrue(batch.isWhole)
        assertEquals(listOf<PlaystyleIntent>(PlaystyleIntent.Tap), batch.intents)
    }

    @Test
    fun touchUpDownKeywordFormsMapToTapHoldRelease() {
        val tap = collector().apply { attack(this) }
        assertEquals(listOf<PlaystyleIntent>(PlaystyleIntent.Tap), tap.finish().intents)

        val hold = collector()
        hold.requestAttack(emptyList(), mapOf("touch_up" to false, "touch_down" to true))
        assertEquals(listOf<PlaystyleIntent>(PlaystyleIntent.Hold), hold.finish().intents)

        val release = collector().apply { }
        release.requestAttack(emptyList(), mapOf("touch_up" to true, "touch_down" to false))
        assertEquals(listOf<PlaystyleIntent>(PlaystyleIntent.Release), release.finish().intents)
    }

    @Test
    fun positionalAttackArgumentsKeepTheDocumentedOrder() {
        val c = collector()
        c.requestAttack(listOf(false, true), emptyMap())
        assertEquals(listOf<PlaystyleIntent>(PlaystyleIntent.Hold), c.finish().intents)
    }

    @Test
    fun bothFalseRejectsWholeTickInsteadOfGuessing() {
        val c = collector()
        assertFalse(c.requestAttack(emptyList(), mapOf("touch_up" to false, "touch_down" to false)))
        assertEquals(PlaystyleRejection.ATTACK_NOTHING_REQUESTED, c.finish().rejection)
        assertTrue(c.finish().intents.isEmpty())
    }

    @Test
    fun unknownKeywordOrNonBooleanValueRejectsWholeTick() {
        val unknown = collector()
        assertFalse(unknown.requestAttack(emptyList(), mapOf("hold" to true)))
        assertEquals(PlaystyleRejection.ATTACK_ARGUMENT_UNSUPPORTED, unknown.finish().rejection)

        val wrongType = collector()
        assertFalse(wrongType.requestAttack(emptyList(), mapOf("touch_up" to 1L)))
        assertEquals(PlaystyleRejection.ATTACK_ARGUMENT_UNSUPPORTED, wrongType.finish().rejection)
    }

    @Test
    fun positionalAndKeywordForSameFlagIsAConflict() {
        val c = collector()
        assertFalse(c.requestAttack(listOf(true), mapOf("touch_up" to true)))
        assertEquals(PlaystyleRejection.ATTACK_ARGUMENT_CONFLICT, c.finish().rejection)
    }

    @Test
    fun tapAndHoldInOneTickIsAConflictNotAMerge() {
        val c = collector()
        c.requestAttack(emptyList(), emptyMap())
        assertFalse(c.requestAttack(emptyList(), mapOf("touch_up" to false, "touch_down" to true)))
        assertEquals(PlaystyleRejection.ATTACK_INTENT_CONFLICT, c.finish().rejection)
    }

    @Test
    fun repeatedIdenticalAttackCoalesces() {
        val c = collector()
        assertTrue(c.requestAttack(emptyList(), emptyMap()))
        assertTrue(c.requestAttack(emptyList(), mapOf("touch_up" to true, "touch_down" to true)))
        assertEquals(1, c.finish().intents.size)
    }

    @Test
    fun abilitiesAreDistinctAndCoalesced() {
        val c = collector()
        assertTrue(c.requestAbility("super"))
        assertTrue(c.requestAbility("super"))
        assertTrue(c.requestAbility("gadget"))
        assertTrue(c.requestAbility("hypercharge"))
        val intents = c.finish().intents
        assertEquals(3, intents.size)
        assertEquals(
            listOf(
                PlaystyleIntent.Ability("super"),
                PlaystyleIntent.Ability("gadget"),
                PlaystyleIntent.Ability("hypercharge"),
            ),
            intents,
        )
    }

    @Test
    fun unsupportedAbilityNameRejectsWholeTick() {
        val c = collector()
        assertFalse(c.requestAbility("ultimate"))
        assertEquals(PlaystyleRejection.ABILITY_UNSUPPORTED, c.finish().rejection)
    }

    @Test
    fun budgetOverflowRejectsWholeTick() {
        val c = PlaystyleIntentCollector(maxIntents = 1)
        assertTrue(c.requestAbility("super"))
        assertFalse(c.requestAttack(emptyList(), emptyMap()))
        assertEquals(PlaystyleRejection.INTENT_BUDGET, c.finish().rejection)
        assertTrue("no partial intent set may run", c.finish().intents.isEmpty())
    }

    @Test
    fun resetClearsBufferedIntentsAndRejection() {
        val c = collector()
        c.requestAbility("bogus")
        c.reset()
        val batch = c.finish()
        assertTrue(batch.isWhole)
        assertTrue(batch.intents.isEmpty())
    }

    @Test
    fun explicitNoneIsAnIntentionalStill() {
        val c = collector()
        val decision = PlaystyleDecision.decide(PlaystyleMovementOutput.None, c.finish())
        assertTrue(decision is PlaystyleDecisionResult.Accepted)
        assertSame(PlaystyleMovement.Still, (decision as PlaystyleDecisionResult.Accepted).movement)
    }

    @Test
    fun zeroVectorIsAnIntentionalStill() {
        val decision = PlaystyleDecision.decide(PlaystyleMovementOutput.Vector(0.0, 0.0), PlaystyleIntentBatch.EMPTY)
        assertSame(PlaystyleMovement.Still, (decision as PlaystyleDecisionResult.Accepted).movement)
    }

    @Test
    fun missingMovementIsAFailureNotAStill() {
        val decision = PlaystyleDecision.decide(PlaystyleMovementOutput.Missing, PlaystyleIntentBatch.EMPTY)
        assertEquals(PlaystyleRejection.MOVEMENT_MISSING, (decision as PlaystyleDecisionResult.Rejected).reason)
    }

    @Test
    fun malformedAndNonFiniteMovementAreRejected() {
        val shape = PlaystyleDecision.decide(PlaystyleMovementOutput.Invalid, PlaystyleIntentBatch.EMPTY)
        assertEquals(PlaystyleRejection.MOVEMENT_SHAPE, (shape as PlaystyleDecisionResult.Rejected).reason)

        for (bad in listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY to 1.0)) {
            val decision = PlaystyleDecision.decide(PlaystyleMovementOutput.Vector(bad.first, bad.second), PlaystyleIntentBatch.EMPTY)
            assertEquals(PlaystyleRejection.MOVEMENT_NONFINITE, (decision as PlaystyleDecisionResult.Rejected).reason)
        }
    }

    @Test
    fun aRejectedIntentBatchCannotBecomeAnAcceptedDecision() {
        val c = collector()
        c.requestAttack(emptyList(), mapOf("touch_up" to false, "touch_down" to false))
        val decision = PlaystyleDecision.decide(PlaystyleMovementOutput.Vector(10.0, 0.0), c.finish())
        assertTrue(decision is PlaystyleDecisionResult.Rejected)
    }

    @Test
    fun validVectorIsAcceptedWithItsIntentsInOrder() {
        val c = collector()
        c.requestAbility("super")
        c.requestAttack(emptyList(), emptyMap())
        val decision = PlaystyleDecision.decide(PlaystyleMovementOutput.Vector(12.0, -4.0), c.finish())
        val accepted = decision as PlaystyleDecisionResult.Accepted
        assertEquals(12.0, (accepted.movement as PlaystyleMovement.Vector).dx, 0.0)
        assertEquals(
            listOf(PlaystyleIntent.Ability("super"), PlaystyleIntent.Tap),
            accepted.intents,
        )
    }

    @Test
    fun stagedPersistenceDeepCopyIsolatesNestedContainers() {
        val inner = PyDict(LinkedHashMap<Any, Any>().apply { put("k", 1L) })
        val list = PyList(arrayListOf<Any>(inner as Any, 2.0))
        val original = PyDict(LinkedHashMap<Any, Any>().apply {
            put("time_since_holding_attack", PyNone)
            put("nested", inner as Any)
            put("items", list as Any)
        })
        val copy = deepCopyPlaystyleValue(original) as PyDict

        assertNotSame(original, copy)
        assertNotSame(original.map["nested"], copy.map["nested"])
        assertNotSame(original.map["items"], copy.map["items"])
        // Mutating the copy must not be visible in the committed state.
        (copy.map["nested"] as PyDict).map["k"] = 99L
        assertEquals(1L, (original.map["nested"] as PyDict).map["k"])
        (copy.map["items"] as PyList).items.add(3L)
        assertEquals(2, (original.map["items"] as PyList).items.size)
        // Scalars are immutable and deliberately shared.
        assertSame(PyNone, copy.map["time_since_holding_attack"])
    }

    @Test
    fun randomMovementCandidateIsFiniteBoundedAndDeterministic() {
        for (index in 0 until 40) {
            val (dx, dy) = PlaystyleRandomMovement.candidate(index, 75.0)
            assertTrue(dx.isFinite() && dy.isFinite())
            val magnitude = kotlin.math.hypot(dx, dy)
            assertTrue("magnitude $magnitude must stay inside the joystick limit", magnitude in 1.0..75.0)
            assertEquals(dx, PlaystyleRandomMovement.candidate(index, 75.0).first, 0.0)
        }
        val odd = PlaystyleRandomMovement.candidate(3, Double.NaN)
        assertTrue(odd.first.isFinite() && odd.second.isFinite())
    }

    @Test
    fun telemetryUsesFixedStatusAndSanitizedModuleName() {
        val detail = PlaystyleTelemetry.detail(
            PlaystyleTelemetry.INVALID_OUTPUT,
            "/data/user/0/app/files/pyla/playstyles/../../secret name.pyla",
            12L,
            345L,
            PlaystyleRejection.MOVEMENT_NONFINITE,
        )
        assertTrue(detail.startsWith("status=INVALID_OUTPUT module="))
        assertTrue(detail.contains(" elapsed_ms=12 steps=345"))
        assertTrue(detail.contains("reason=MOVEMENT_NONFINITE"))
        assertFalse("no path separators may leak", detail.contains("/"))
        assertFalse(detail.contains("secret name"))
        assertEquals("unknown", PlaystyleTelemetry.sanitizeModule(null))
        assertEquals("a.pyla", PlaystyleTelemetry.sanitizeModule("cfg/../a.pyla"))
    }

    @Test
    fun negativeElapsedIsClampedInTelemetry() {
        val detail = PlaystyleTelemetry.detail(PlaystyleTelemetry.SUCCESS, "f.pyla", -5L, -1L)
        assertTrue(detail.contains("elapsed_ms=0 steps=0"))
    }
}
