package com.pyla.ai.engine

/**
 * Production session lifecycle authority owned by [BotEngine] for engine-initiated side effects
 * (game relaunch on crash/no-detections and the idle reconnect) that must stay subordinate to
 * Pause/Stop and to session ownership.
 *
 * It wraps the reviewed [NavigationAuthority] and wires its predicates explicitly:
 *  * `sessionLive` — this instance still owns the engine and is neither stopped nor paused. A
 *    boolean read once before a loop is insufficient: each side effect binds its own request via
 *    [beginRequest] and re-evaluates it immediately before the effect, at the queue dispatch point
 *    and again at every continuation, so a request admitted before a Pause cannot be replayed.
 *  * `menuLive` — always `true`. These effects are not menu navigation; a live combat/menu
 *    ownership conflict does not apply to relaunching the game or dismissing its idle dialog.
 *  * `foregroundLive` — always `true`. Foreground is a necessary *additional* condition for the idle
 *    reconnect (composed by the caller), but it must never be sufficient, and the crash-recovery
 *    relaunch is legitimately authorized while a foreign app is foreground, so foreground must
 *    never gate this authority.
 *
 * [invalidate] advances the epoch on BOTH Pause and Resume, so a request admitted before an
 * unsampled Pause->Resume pair is denied forever. [close] is terminal (Stop): no current or future
 * request is ever admitted afterwards.
 *
 * The wrapped authority fails closed (a throwing or false predicate denies; the first `false`
 * latches; a throwing geometry read yields no request) and holds no monitor across a predicate, an
 * interrupt probe, IO or a callback.
 */
class EngineSessionAuthority(
    sessionLive: () -> Boolean,
) {
    private val authority = NavigationAuthority(
        sessionLive = sessionLive,
        menuLive = { true },
        foregroundLive = { true },
    )

    /** Pause OR Resume: every retained request is permanently denied; in-flight guards now deny. */
    fun invalidate() = authority.invalidate()

    /** Terminal Stop: no current or future request is ever admitted. */
    fun close() = authority.close()

    val isClosed: Boolean get() = authority.isClosed

    /** Bind a fresh lifecycle request to the current epoch, or `null` (closed or unusable). */
    fun beginRequest(): NavigationAuthority.BoundRequest? = authority.issue()

    /**
     * Issue a fail-closed guard bound to the current epoch, or `null` when the authority cannot
     * issue (closed / throwing geometry source). Never reuse a request across actions.
     */
    fun requestGuard(): (() -> Boolean)? =
        authority.issue()?.let { bound -> { bound.allows() } }

    companion object {
        /** Constant fail-closed guard for callers that must inject one but have no live authority. */
        val DENY: () -> Boolean = { false }
    }
}
