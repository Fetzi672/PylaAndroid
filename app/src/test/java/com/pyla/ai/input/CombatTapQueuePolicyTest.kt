package com.pyla.ai.input

import com.pyla.ai.input.CombatTapQueuePolicy.Entry
import com.pyla.ai.input.CombatTapQueuePolicy.Rejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host policy coverage for the bounded per-combat-channel tap queue.
 *
 * These tests drive the real production policy ([CombatTapQueuePolicy], used by
 * `InputService.pressAndRelease`, `InputService.pressCombatAbility` and
 * `InputService.restorePending`) with plain data: no android.jar, no device, no clock, no OS
 * gesture producer. They are **not** native/OS proof that four strokes reach the game; the parent
 * supplies the producer/native proof separately. What they do pin is the coalescing, cardinality,
 * restoration, cap and key-validation contract that the old global rule violated.
 *
 * The first test keeps a faithful transcription of the replaced expression
 * (`removeAll { it.authority != null && it.replayable }`, baseline `InputService.kt:217`) so the
 * cross-ability starvation is demonstrated against the actual old predicate, not a strawman.
 */
class CombatTapQueuePolicyTest {

    private val attack = CombatTapQueuePolicy.CHANNEL_ATTACK
    private val superKey = "super"
    private val gadget = "gadget"
    private val hyper = "hypercharge"

    private fun combat(channel: String) = Entry(channel = channel, guarded = true, replayable = true)
    private fun unguardedTap() = Entry(channel = null, guarded = false, replayable = true)
    private fun navigation() = Entry(channel = null, guarded = true, replayable = false)

    /** The exact old coalescing predicate, applied to the same queue shape. */
    private fun legacyCoalesce(queue: List<Entry>, incomingChannel: String): List<Entry> =
        queue.filterNot { it.guarded && it.replayable } + combat(incomingChannel)

    private fun offer(queue: List<Entry>, channel: String): List<Entry> {
        val drop = CombatTapQueuePolicy.replaceIndices(queue, channel).toSet()
        return queue.filterIndexed { i, _ -> i !in drop } + combat(channel)
    }

    // ------------------------------------------------------------ RED: the replaced global rule

    @Test
    fun `legacy global rule starves abilities that the per-channel policy retains`() {
        // One tick asks for attack, super, gadget, hypercharge in that order.
        var legacy = listOf<Entry>()
        for (key in listOf(attack, superKey, gadget, hyper)) legacy = legacyCoalesce(legacy, key)
        assertEquals("old rule keeps only the last guarded tap", listOf(hyper), legacy.map { it.channel })

        var fixed = listOf<Entry>()
        for (key in listOf(attack, superKey, gadget, hyper)) fixed = offer(fixed, key)
        assertEquals(
            "per-channel rule keeps one waiting tap per ability, in FIFO order",
            listOf(attack, superKey, gadget, hyper),
            fixed.map { it.channel },
        )
        assertFalse(CombatTapQueuePolicy.violatesCardinality(fixed))
    }

    // ------------------------------------------------------------------------ attack duplicates

    @Test
    fun `repeated guarded basic attacks collapse to one waiting attack tap`() {
        var q = listOf<Entry>()
        repeat(4) { q = offer(q, attack) }
        assertEquals(listOf(attack), q.map { it.channel })
        assertEquals(1, CombatTapQueuePolicy.combatPendingCount(q))
    }

    @Test
    fun `repeated ability requests replace only their own channel`() {
        var q = listOf(combat(attack), combat(superKey), combat(gadget))
        q = offer(q, superKey)
        assertEquals("a refreshed super replaces the old super and nothing else", listOf(attack, gadget, superKey), q.map { it.channel })
        q = offer(q, hyper)
        assertEquals(4, CombatTapQueuePolicy.combatPendingCount(q))
        assertEquals(listOf(attack, gadget, superKey, hyper), CombatTapQueuePolicy.distinctChannels(q))
    }

    // ------------------------------------------------------------------------------ cardinality

    @Test
    fun `all four distinct channels are retained and cardinality stays bounded`() {
        var q = listOf<Entry>()
        for (key in CombatTapQueuePolicy.COMBAT_CHANNELS) q = offer(q, key)
        assertEquals(4, CombatTapQueuePolicy.MAX_COMBAT_PENDINGS)
        assertEquals(4, CombatTapQueuePolicy.combatPendingCount(q))
        assertEquals(1, CombatTapQueuePolicy.MAX_PENDING_PER_CHANNEL)
        assertTrue(CombatTapQueuePolicy.channelCardinality(q).values.all { it == 1 })
        assertFalse(CombatTapQueuePolicy.violatesCardinality(q))
    }

    @Test
    fun `global queue and gesture caps are unchanged`() {
        assertEquals(16, CombatTapQueuePolicy.MAX_QUEUED_STROKES)
        assertEquals(8, CombatTapQueuePolicy.MAX_STROKES_PER_GESTURE)
        // Four bounded combat channels can never consume the whole global queue on their own.
        assertTrue(CombatTapQueuePolicy.MAX_COMBAT_PENDINGS < CombatTapQueuePolicy.MAX_QUEUED_STROKES)
    }

    @Test
    fun `rebuild attempt cap is finite and covers every bounded denial`() {
        assertEquals(5, CombatTapQueuePolicy.MAX_REBUILD_ATTEMPTS)
        assertTrue(CombatTapQueuePolicy.MAX_REBUILD_ATTEMPTS <= 1 + CombatTapQueuePolicy.MAX_QUEUED_STROKES)
        assertTrue(CombatTapQueuePolicy.MAX_REBUILD_ATTEMPTS > CombatTapQueuePolicy.MAX_COMBAT_PENDINGS)
    }

    @Test
    fun `cardinality check rejects a duplicated or unknown channel`() {
        assertTrue(CombatTapQueuePolicy.violatesCardinality(listOf(combat(attack), combat(attack))))
        assertTrue(CombatTapQueuePolicy.violatesCardinality(listOf(combat("respawn"))))
        assertFalse(CombatTapQueuePolicy.violatesCardinality(listOf(unguardedTap(), navigation(), combat(attack))))
    }

    // -------------------------------------------------------------------------- non-combat work

    @Test
    fun `unguarded taps and navigation are never displaced by a guarded request`() {
        val q = listOf(unguardedTap(), navigation(), combat(gadget))
        assertTrue(CombatTapQueuePolicy.replaceIndices(q, attack).isEmpty())
        assertEquals("only the gadget slot is replaceable", listOf(2), CombatTapQueuePolicy.replaceIndices(q, gadget))
        assertEquals("unguarded work is never a replacement target", 3, q.size)
    }

    @Test
    fun `an unguarded incoming request replaces nothing`() {
        val q = listOf(combat(attack), combat(superKey), unguardedTap())
        assertTrue(CombatTapQueuePolicy.replaceIndices(q, null).isEmpty())
        q.indices.forEach { assertFalse(CombatTapQueuePolicy.replaces(q[it], null)) }
    }

    @Test
    fun `a guarded attack does not displace a waiting ability`() {
        val q = listOf(combat(superKey), combat(gadget), combat(hyper))
        assertTrue(CombatTapQueuePolicy.replaceIndices(q, attack).isEmpty())
        val after = offer(q, attack)
        assertEquals(listOf(superKey, gadget, hyper, attack), after.map { it.channel })
    }

    // ------------------------------------------------------------------------------ restoration

    @Test
    fun `restore keeps mixed unguarded work and one tap per allowed channel`() {
        val restored = mutableListOf<Entry>()
        val candidates = listOf(unguardedTap(), combat(attack), combat(superKey), combat(gadget), combat(hyper), navigation())
        for (candidate in candidates.asReversed()) {
            val allowed = candidate.channel != attack // attack authority went stale
            if (CombatTapQueuePolicy.shouldRestore(restored, candidate, allowed)) restored.add(0, candidate)
        }
        assertEquals(
            listOf(null, superKey, gadget, hyper),
            restored.map { it.channel },
        )
        assertTrue(
            "a denied sticky authority loses even after a cancellation",
            restored.none { it.channel == attack },
        )
        assertTrue("navigation is never restored", restored.none { !it.replayable })
    }

    @Test
    fun `restore coalesces duplicates of the same channel instead of queueing twice`() {
        val restored = mutableListOf<Entry>(combat(attack))
        assertFalse(CombatTapQueuePolicy.shouldRestore(restored, combat(attack), authorityAllows = true))
        assertFalse(CombatTapQueuePolicy.shouldRestore(restored, combat(attack), authorityAllows = false))
        assertTrue(CombatTapQueuePolicy.shouldRestore(restored, combat(superKey), authorityAllows = true))
        assertTrue(CombatTapQueuePolicy.shouldRestore(restored, unguardedTap(), authorityAllows = true))
        assertFalse(CombatTapQueuePolicy.shouldRestore(restored, navigation(), authorityAllows = true))
    }

    @Test
    fun `restore does not invent authority and never grows past four channels`() {
        var q = listOf<Entry>()
        for (key in CombatTapQueuePolicy.COMBAT_CHANNELS) {
            if (CombatTapQueuePolicy.shouldRestore(q, combat(key), authorityAllows = true)) q = q + combat(key)
        }
        assertEquals(4, q.size)
        assertFalse("a second restoration of any channel is refused", CombatTapQueuePolicy.shouldRestore(q, combat(attack), true))
        assertFalse("denied authority is refused even with a free slot", CombatTapQueuePolicy.shouldRestore(emptyList(), combat(hyper), false))
        assertFalse(CombatTapQueuePolicy.violatesCardinality(q))
    }

    // ---------------------------------------------------------------------------- key validation

    @Test
    fun `named API accepts only the three ability keys`() {
        for (key in CombatTapQueuePolicy.ABILITY_CHANNELS) {
            assertEquals(Rejection.NONE, CombatTapQueuePolicy.validateAbilityRequest(key, 10f, 20f, 60L))
        }
        assertEquals(3, CombatTapQueuePolicy.ABILITY_CHANNELS.size)
        assertFalse(attack in CombatTapQueuePolicy.ABILITY_CHANNELS)
        assertTrue(attack in CombatTapQueuePolicy.COMBAT_CHANNELS)
    }

    @Test
    fun `unknown and non-canonical keys are rejected not rewritten`() {
        for (key in listOf(attack, "respawn", "Super", "SUPER", " super", "", "gadget ")) {
            assertEquals(
                "key='$key' must be rejected rather than mapped onto a known channel",
                Rejection.NOT_ABILITY_CHANNEL,
                CombatTapQueuePolicy.validateAbilityRequest(key, 10f, 20f, 60L),
            )
        }
        assertEquals(Rejection.NULL_KEY, CombatTapQueuePolicy.validateAbilityRequest(null, 10f, 20f, 60L))
        assertTrue(CombatTapQueuePolicy.replaceIndices(emptyList(), "Super").isEmpty())
    }

    @Test
    fun `non-finite coordinates and non-positive holds are rejected`() {
        assertEquals(Rejection.NON_FINITE, CombatTapQueuePolicy.validateAbilityRequest(superKey, Float.NaN, 20f, 60L))
        assertEquals(Rejection.NON_FINITE, CombatTapQueuePolicy.validateAbilityRequest(superKey, 10f, Float.POSITIVE_INFINITY, 60L))
        assertEquals(Rejection.NON_POSITIVE_HOLD, CombatTapQueuePolicy.validateAbilityRequest(superKey, 10f, 20f, 0L))
        assertEquals(Rejection.NON_POSITIVE_HOLD, CombatTapQueuePolicy.validateAbilityRequest(superKey, 10f, 20f, -1L))
    }

    // ------------------------------------------------------------ full-tick stale-authority path

    @Test
    fun `one-tick four-channel request survives a dispatch where one authority went stale`() {
        // Producer tick: attack + super + gadget + hypercharge.
        var q = listOf<Entry>()
        for (key in CombatTapQueuePolicy.COMBAT_CHANNELS) q = offer(q, key)
        assertEquals(4, q.size)

        // Dispatch consumes what it can; super's authority denies at dispatch and it is dropped.
        val allowed = q.filter { it.channel != superKey }
        val denied = q.filter { it.channel == superKey }

        // Restoration must not resurrect the denied super, must not duplicate anything, and must
        // keep the three remaining channels intact.
        val restored = mutableListOf<Entry>()
        for (item in (allowed + denied).asReversed()) {
            if (CombatTapQueuePolicy.shouldRestore(restored, item, authorityAllows = item.channel != superKey)) {
                restored.add(0, item)
            }
        }
        assertEquals(listOf(attack, gadget, hyper), restored.map { it.channel })
        assertFalse(CombatTapQueuePolicy.violatesCardinality(restored))
    }
}
