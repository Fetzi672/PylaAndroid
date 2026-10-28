package com.pyla.ai.engine

/**
 * Positive menu identity and positive gameplay arbitration for the two menu states whose historical
 * classifier evidence was a SINGLE small template (`brawler_menu_heart`, `powerpoint`).
 *
 * The 3.0.4 Trio follower report is the reason this exists: a single-template `brawler_selection`
 * match inside a real match frame revoked the admitted match and StageManager pressed the
 * upper-left BACK eleven times. A template correlation inside one rectangle is not menu identity,
 * so every consumer now needs an explicit [Identity.level] instead of a bare boolean.
 *
 * Everything here is pure (no OpenCV, no I/O, no clock) so the admission rules are testable as
 * behaviour rather than as source wiring.
 */
object MenuIdentityAdmission {

    /** Menu states whose classifier evidence is a single small control template. */
    val AMBIGUOUS_MENU_STATES: Set<String> = setOf("brawler_selection", "shop")

    /** Back-navigation for this state additionally requires the independent second control. */
    val STATES_REQUIRING_INDEPENDENT_CONTROL: Set<String> = setOf("brawler_selection")

    enum class Level {
        /**
         * Two independent menu-only image controls matched the same frame (or the state is not
         * ambiguous): this is that menu screen and it may authorise menu navigation.
         */
        CONFIRMED,

        /**
         * Only the legacy single control matched and no gameplay control is visible. The state
         * string is preserved because the accepted list-search flow consumes it
         * (LobbyAutomation grid/detail arbitration), but it may neither displace fresh admitted
         * gameplay nor authorise a BACK tap on its own.
         */
        CORROBORATION_REQUIRED,

        /**
         * A real in-match HUD control (the red attack ring) is visible on this exact frame, so the
         * frame is gameplay, never this menu. This is positive gameplay evidence, not the mere
         * absence of menu evidence.
         */
        DENIED_GAMEPLAY_CONTROL,

        /** The state is not an ambiguous menu state, or no menu control matched. */
        NOT_APPLICABLE,
    }

    /**
     * @param state  classifier state the evidence belongs to.
     * @param level  admission level for this exact frame.
     * @param reason stable machine-readable reason code (diagnostics only, never permission).
     */
    data class Identity(val state: String, val level: Level, val reason: String) {
        /** Two independent positive menu controls on this frame. */
        val confirmed: Boolean get() = level == Level.CONFIRMED

        /** Some positive menu control matched and the frame is not gameplay. */
        val menuControlPresent: Boolean
            get() = level == Level.CONFIRMED || level == Level.CORROBORATION_REQUIRED

        val gameplayControlPresent: Boolean get() = level == Level.DENIED_GAMEPLAY_CONTROL

        /** Safe to publish as this menu state at all (navigation still needs [confirmed] where required). */
        val mayPublishState: Boolean get() = menuControlPresent
    }

    /**
     * @param primaryControl    the configured legacy control template for [state] matched in its region.
     * @param independentControl a second, menu-only control matched the same frame.
     * @param gameplayControl   the real in-match attack HUD control was found in the same frame.
     */
    fun evaluate(
        state: String,
        primaryControl: Boolean,
        independentControl: Boolean,
        gameplayControl: Boolean,
    ): Identity {
        if (state !in AMBIGUOUS_MENU_STATES) {
            return Identity(state, Level.NOT_APPLICABLE, "not_ambiguous")
        }
        // Positive gameplay arbitration first: a visible in-match HUD control outranks any
        // single-rectangle template correlation.
        if (gameplayControl) {
            return Identity(state, Level.DENIED_GAMEPLAY_CONTROL, "gameplay_hud_control")
        }
        if (!primaryControl) return Identity(state, Level.NOT_APPLICABLE, "no_menu_control")
        if (independentControl) {
            return Identity(state, Level.CONFIRMED, "two_independent_menu_controls")
        }
        return Identity(state, Level.CORROBORATION_REQUIRED, "single_menu_control")
    }

    /** Whether [state] needs the independent second control before a BACK dispatch. */
    fun requiresIndependentControl(state: String): Boolean = state in STATES_REQUIRING_INDEPENDENT_CONTROL

    /**
     * Identity for a state that is established by its OWN configured control template(s) rather
     * than by the ambiguous single-heart/single-powerpoint evidence (e.g. the brawl-pass and
     * star-road screens that both classify as `shop`). It is confirmed because the template itself
     * is the screen-specific control, and it carries no gameplay override.
     */
    fun confirmedByOwnControl(state: String, reason: String): Identity =
        Identity(state, Level.CONFIRMED, reason)

    /**
     * The classifier string to publish for this identity.
     *
     * A gameplay veto and a total absence of menu evidence both publish `unknown` (the existing
     * safety fallback). `CORROBORATION_REQUIRED` keeps its menu string on purpose: the accepted
     * list-search flow reads it, and navigation itself is gated downstream.
     */
    fun publicState(identity: Identity): String =
        if (identity.mayPublishState) identity.state else "unknown"
}

/**
 * Ordering rule between a menu classification and gameplay authority that Play already admitted.
 *
 * `GameStateAuthority` fulfils its own contract - one positive menu observation revokes combat -
 * but a single ambiguous-menu template match inside a live match is far more likely to be a
 * misclassification than a real screen change. Until a menu classification carries confirmed
 * independent identity, it may not displace a match whose admitted evidence is still fresh; the
 * observation is recorded as `unknown`, which by that authority's own contract can neither grant
 * nor revoke gameplay.
 */
object MenuMatchArbitration {

    /** Same freshness window the state authority uses for admitted evidence. */
    const val FRESH_GAMEPLAY_EVIDENCE_MS = 3_000L

    /**
     * @param priorState        authoritative state before this observation ("match" when admitted).
     * @param priorEvidenceAtMs capture timestamp of that admitted evidence (0 = none).
     * @param nowMs             monotonic now, same clock as the timestamps.
     * @param observedState     classifier state for the new observation.
     * @param identity          admission identity for that same observation.
     */
    fun menuMayDisplaceMatch(
        priorState: String?,
        priorEvidenceAtMs: Long,
        nowMs: Long,
        observedState: String,
        identity: MenuIdentityAdmission.Identity,
    ): Boolean {
        if (observedState !in MenuIdentityAdmission.AMBIGUOUS_MENU_STATES) return true
        if (identity.state == observedState && identity.confirmed) return true
        val hasMatch = priorState == "match"
        val evidencePresent = priorEvidenceAtMs > 0L && priorEvidenceAtMs <= nowMs
        val evidenceFresh = evidencePresent && nowMs - priorEvidenceAtMs <= FRESH_GAMEPLAY_EVIDENCE_MS
        return !(hasMatch && evidenceFresh)
    }
}

/**
 * Stage-owned gate for the single dangerous navigation action: the upper-left menu BACK shared by
 * `shop` and `brawler_selection`.
 *
 * A cached classifier state may be seconds old and may itself be a single-frame artefact, so a BACK
 * requires two DISTINCT fresh re-checked frames of the same screen, no gameplay HUD control, and -
 * for the states that need it - the independent second menu control. The returned [Ticket] is
 * re-validated at queue dispatch; any invalidation (Pause/Stop/close, screen change, geometry
 * change) denies it forever because the epoch moved.
 */
class MenuBackAdmission(
    private val maxAgeMs: Long = 2_000L,
    private val maxGapMs: Long = 1_500L,
) {

    /** One fresh re-check of one frame. All fields are evidence, none is a permission. */
    data class Observation(
        val state: String,
        val menuControlPresent: Boolean,
        val independentControlPresent: Boolean,
        val gameplayHudControl: Boolean,
        val capturedAtMs: Long,
        val width: Int,
        val height: Int,
    )

    class Ticket internal constructor(
        val state: String,
        val serial: Long,
        val confirmedAtMs: Long,
        internal val firstCapturedAtMs: Long,
        internal val epoch: Long,
    )

    private var epoch = 0L
    private var serial = 0L
    private var first: Observation? = null
    private var granted: Ticket? = null

    /** Pause/Stop/close/geometry change: every outstanding ticket is denied permanently. */
    @Synchronized
    fun invalidate() {
        epoch++
        serial++
        first = null
        granted = null
    }

    /**
     * Feed one fresh frame. Returns a [Ticket] only when this frame and the previous fed frame are
     * two distinct, fresh, confirmed observations of the same screen and geometry.
     */
    @Synchronized
    fun observe(observation: Observation, nowMs: Long): Ticket? {
        granted = null
        if (!usable(observation, nowMs)) {
            // An unusable frame breaks any pending pair instead of extending it.
            first = null
            return null
        }
        val previous = first
        val sameScreen = previous != null &&
            usable(previous, nowMs) &&
            previous.state == observation.state &&
            previous.width == observation.width &&
            previous.height == observation.height &&
            observation.capturedAtMs > previous.capturedAtMs &&
            observation.capturedAtMs - previous.capturedAtMs <= maxGapMs
        first = observation
        if (!sameScreen) return null
        val ticket = Ticket(observation.state, ++serial, observation.capturedAtMs, previous!!.capturedAtMs, epoch)
        granted = ticket
        return ticket
    }

    /** Dispatch-time re-validation. A ticket is usable exactly once per granted observation. */
    @Synchronized
    fun allows(ticket: Ticket, nowMs: Long): Boolean {
        if (ticket.epoch != epoch) return false
        if (granted !== ticket) return false
        if (first?.state != ticket.state) return false
        if (ticket.confirmedAtMs <= 0L || ticket.confirmedAtMs > nowMs) return false
        if (ticket.firstCapturedAtMs <= 0L || ticket.firstCapturedAtMs > nowMs ||
            nowMs - ticket.firstCapturedAtMs > maxAgeMs) return false
        return nowMs - ticket.confirmedAtMs <= maxAgeMs
    }

    private fun usable(observation: Observation, nowMs: Long): Boolean {
        if (observation.state !in MenuIdentityAdmission.AMBIGUOUS_MENU_STATES) return false
        if (!observation.menuControlPresent) return false
        if (observation.gameplayHudControl) return false
        if (MenuIdentityAdmission.requiresIndependentControl(observation.state) &&
            !observation.independentControlPresent
        ) return false
        if (observation.width <= 0 || observation.height <= 0) return false
        if (observation.capturedAtMs <= 0L || observation.capturedAtMs > nowMs) return false
        return nowMs - observation.capturedAtMs <= maxAgeMs
    }
}
