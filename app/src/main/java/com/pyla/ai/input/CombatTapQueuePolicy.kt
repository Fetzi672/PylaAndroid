package com.pyla.ai.input

/**
 * Pure bookkeeping for guarded, replayable combat TAP requests.
 *
 * `InputService` used to coalesce *every* waiting guarded replayable tap whenever a fresh guarded
 * tap arrived, regardless of which ability it was for (`removeAll { authority != null && replayable }`).
 * A real `.pyla` tick can request `attack`, `super`, `gadget` and `hypercharge` together, so the
 * fourth request silently dropped the first three. This policy replaces that global rule with a
 * bounded per-channel rule:
 *
 *  - only a request for the SAME named channel replaces a waiting one (freshest tap wins);
 *  - at most one waiting pending per known combat channel (attack, super, gadget, hypercharge),
 *    so at most [MAX_COMBAT_PENDINGS] guarded combat taps can ever wait;
 *  - unguarded taps/swipes and non-replayable navigation never displace and are never displaced;
 *  - restoration is per channel and still requires the pending's sticky authority to allow it;
 *  - unknown ability keys are rejected, never rewritten onto a known channel.
 *
 * No Android types, no clock, no allocation of the engine: `InputService` projects each pending
 * into [Entry] and asks this object. Tests drive these rules directly.
 */
object CombatTapQueuePolicy {

    const val CHANNEL_ATTACK = "attack"

    /** Ability channels accepted by the named API. Basic attack keeps its legacy entry point. */
    val ABILITY_CHANNELS: Set<String> = linkedSetOf("super", "gadget", "hypercharge")

    /** Known combat channels in FIFO order; one waiting pending each at most. */
    val COMBAT_CHANNELS: List<String> = listOf(CHANNEL_ATTACK) + ABILITY_CHANNELS

    const val MAX_PENDING_PER_CHANNEL = 1

    // Mirrored by InputService; kept here so the pure policy can assert the global bounds.
    const val MAX_QUEUED_STROKES = 16
    const val MAX_STROKES_PER_GESTURE = 8

    /** Bounded cardinality of guarded combat pendings: one per known channel. */
    val MAX_COMBAT_PENDINGS: Int = COMBAT_CHANNELS.size

    /**
     * Rebuild passes in `InputService.pump` before it gives up for this dispatch round: one pass
     * per possible simultaneous denial (every channel plus the waiting-tap re-check), plus one for
     * the initial attempt. Finite by construction; authority stays sticky and is re-checked in
     * every pass, so extra passes can only remove authority, never grant it.
     */
    val MAX_REBUILD_ATTEMPTS: Int = 1 + MAX_COMBAT_PENDINGS

    /** Why a named ability request was refused before it could reach the queue. */
    enum class Rejection {
        NONE,
        NULL_KEY,
        NOT_ABILITY_CHANNEL,
        NON_FINITE,
        NON_POSITIVE_HOLD,
    }

    /**
     * Projection of one queued pending, so the policy can be exercised without `InputService`.
     * [channel] is null for every construction that predates named combat channels (unguarded
     * taps, swipes, non-replayable navigation).
     */
    data class Entry(
        val channel: String? = null,
        val guarded: Boolean = false,
        val replayable: Boolean = true,
    ) {
        /** A waiting guarded combat tap: the only shape this policy coalesces or bounds. */
        val isCombatTap: Boolean get() = guarded && replayable && channel != null
    }

    fun validateAbilityRequest(key: String?, x: Float, y: Float, holdMs: Long): Rejection = when {
        key == null -> Rejection.NULL_KEY
        key !in ABILITY_CHANNELS -> Rejection.NOT_ABILITY_CHANNEL
        !x.isFinite() || !y.isFinite() -> Rejection.NON_FINITE
        holdMs <= 0L -> Rejection.NON_POSITIVE_HOLD
        else -> Rejection.NONE
    }

    /**
     * Whether the incoming request (identified only by its channel tag) replaces [existing].
     * Unguarded (`null` tag) requests displace nothing, preserving historical UI behaviour.
     */
    fun replaces(existing: Entry, incomingChannel: String?): Boolean =
        incomingChannel != null && existing.isCombatTap && existing.channel == incomingChannel

    /** Indices in [queued] that an incoming request for [incomingChannel] must drop. */
    fun replaceIndices(queued: List<Entry>, incomingChannel: String?): List<Int> =
        queued.indices.filter { replaces(queued[it], incomingChannel) }

    /**
     * Whether [pending] may be put back after a build failure, cancellation or rejection.
     *
     * Navigation (`replayable = false`) consumes its ticket and is never restored. Unguarded
     * replayable work is always restored. A guarded combat tap needs a currently-allowing sticky
     * authority AND must not find a same-channel pending already waiting (per-channel coalescing),
     * so restoration can never grow a channel past [MAX_PENDING_PER_CHANNEL].
     */
    fun shouldRestore(queued: List<Entry>, pending: Entry, authorityAllows: Boolean): Boolean {
        if (!pending.replayable) return false
        if (!pending.isCombatTap) return true
        if (!authorityAllows) return false
        return queued.none { it.isCombatTap && it.channel == pending.channel }
    }

    fun combatPendingCount(queued: List<Entry>): Int = queued.count { it.isCombatTap }

    fun channelCardinality(queued: List<Entry>): Map<String, Int> =
        queued.filter { it.isCombatTap }
            .groupingBy { it.channel ?: "" }
            .eachCount()

    /** True when any known channel waits twice or an unknown channel somehow appears. */
    fun violatesCardinality(queued: List<Entry>): Boolean {
        val counts = channelCardinality(queued)
        if (counts.any { (channel, count) -> channel !in COMBAT_CHANNELS || count > MAX_PENDING_PER_CHANNEL }) return true
        return combatPendingCount(queued) > MAX_COMBAT_PENDINGS
    }

    /** Distinct channels in queue order (FIFO), for diagnostics and tests. */
    fun distinctChannels(queued: List<Entry>): List<String> =
        queued.filter { it.isCombatTap }.mapNotNull { it.channel }.distinct()
}
