package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour tests for positive menu identity, game/menu arbitration and the upper-left BACK gate.
 *
 * Every case is the real decision function, not a source-wiring check:
 *  - a positive real menu (two independent controls) is admitted,
 *  - the heart-only HUD false positive that produced the 11 BACK taps is denied,
 *  - unknown / stale / STOP-invalidated evidence is denied,
 *  - the accepted list-search string for `shop` (and the list consumers of `brawler_selection`)
 *    is preserved instead of being replaced by a stricter rejection.
 */
class MenuIdentityAdmissionTest {

    private fun identity(
        state: String = "brawler_selection",
        primary: Boolean = true,
        independent: Boolean = true,
        gameplay: Boolean = false,
    ) = MenuIdentityAdmission.evaluate(state, primary, independent, gameplay)

    private fun observation(
        state: String = "brawler_selection",
        menuControl: Boolean = true,
        independent: Boolean = true,
        gameplayHud: Boolean = false,
        capturedAtMs: Long = 1_000L,
        width: Int = 1920,
        height: Int = 1080,
    ) = MenuBackAdmission.Observation(state, menuControl, independent, gameplayHud, capturedAtMs, width, height)

    // ---------------------------------------------------------------- identity

    @Test
    fun positiveRealMenuWithTwoIndependentControlsIsConfirmed() {
        val id = identity()
        assertEquals(MenuIdentityAdmission.Level.CONFIRMED, id.level)
        assertTrue(id.confirmed)
        assertEquals("brawler_selection", MenuIdentityAdmission.publicState(id))
    }

    @Test
    fun heartOnlyFrameIsNeverConfirmedMenuIdentity() {
        val id = identity(independent = false)
        assertEquals(MenuIdentityAdmission.Level.CORROBORATION_REQUIRED, id.level)
        assertFalse(id.confirmed)
        // The string survives for the accepted list-search consumers ...
        assertEquals("brawler_selection", MenuIdentityAdmission.publicState(id))
        // ... but it may not authorise a BACK tap.
        assertTrue(MenuIdentityAdmission.requiresIndependentControl("brawler_selection"))
    }

    @Test
    fun visibleInMatchHudControlDeniesTheMenuClassificationOutright() {
        val id = identity(independent = true, gameplay = true)
        assertEquals(MenuIdentityAdmission.Level.DENIED_GAMEPLAY_CONTROL, id.level)
        assertFalse(id.confirmed)
        assertFalse(id.menuControlPresent)
        assertTrue(id.gameplayControlPresent)
        assertEquals("unknown", MenuIdentityAdmission.publicState(id))
    }

    @Test
    fun noMenuControlPublishesUnknown() {
        val id = identity(primary = false)
        assertEquals(MenuIdentityAdmission.Level.NOT_APPLICABLE, id.level)
        assertEquals("unknown", MenuIdentityAdmission.publicState(id))
    }

    @Test
    fun shopKeepsItsStateWithoutRequiringTheBrawlerMenuIndependentControl() {
        val id = identity(state = "shop", independent = false)
        assertEquals(MenuIdentityAdmission.Level.CORROBORATION_REQUIRED, id.level)
        assertEquals("shop", MenuIdentityAdmission.publicState(id))
        assertFalse(MenuIdentityAdmission.requiresIndependentControl("shop"))
    }

    @Test
    fun ownControlScreensAreConfirmed() {
        val id = MenuIdentityAdmission.confirmedByOwnControl("shop", "brawl_pass_or_star_road")
        assertTrue(id.confirmed)
        assertEquals("shop", MenuIdentityAdmission.publicState(id))
    }

    @Test
    fun unrelatedStatesAreNotTreatedAsAmbiguousMenus() {
        assertFalse(MenuIdentityAdmission.evaluate("lobby", true, false, true).gameplayControlPresent)
        assertEquals(
            MenuIdentityAdmission.Level.NOT_APPLICABLE,
            MenuIdentityAdmission.evaluate("lobby", true, false, true).level,
        )
    }

    // -------------------------------------------------- match/menu arbitration

    @Test
    fun unconfirmedHeartOnlyMenuCannotDisplaceFreshAdmittedMatch() {
        assertFalse(
            MenuMatchArbitration.menuMayDisplaceMatch(
                priorState = "match", priorEvidenceAtMs = 1_000L, nowMs = 1_500L,
                observedState = "brawler_selection", identity = identity(independent = false),
            ),
        )
    }

    @Test
    fun confirmedMenuIdentityMayDisplaceFreshAdmittedMatch() {
        assertTrue(
            MenuMatchArbitration.menuMayDisplaceMatch(
                priorState = "match", priorEvidenceAtMs = 1_000L, nowMs = 1_500L,
                observedState = "brawler_selection", identity = identity(),
            ),
        )
    }

    @Test
    fun staleAdmittedMatchNoLongerProtectsAnUnconfirmedMenuObservation() {
        assertTrue(
            MenuMatchArbitration.menuMayDisplaceMatch(
                priorState = "match",
                priorEvidenceAtMs = 1_000L,
                nowMs = 1_000L + MenuMatchArbitration.FRESH_GAMEPLAY_EVIDENCE_MS + 1L,
                observedState = "brawler_selection", identity = identity(independent = false),
            ),
        )
    }

    @Test
    fun absentOrFutureGameplayEvidenceCannotBlockAMenu() {
        assertTrue(
            MenuMatchArbitration.menuMayDisplaceMatch(
                priorState = "unknown", priorEvidenceAtMs = 0L, nowMs = 1_500L,
                observedState = "brawler_selection", identity = identity(independent = false),
            ),
        )
        assertTrue(
            MenuMatchArbitration.menuMayDisplaceMatch(
                priorState = "match", priorEvidenceAtMs = 9_000L, nowMs = 1_500L,
                observedState = "brawler_selection", identity = identity(independent = false),
            ),
        )
    }

    @Test
    fun nonAmbiguousStatesAreNeverDelayedByArbitration() {
        assertTrue(
            MenuMatchArbitration.menuMayDisplaceMatch(
                priorState = "match", priorEvidenceAtMs = 1_000L, nowMs = 1_100L,
                observedState = "lobby", identity = identity(state = "lobby", primary = false),
            ),
        )
        assertTrue(
            MenuMatchArbitration.menuMayDisplaceMatch(
                priorState = "match", priorEvidenceAtMs = 1_000L, nowMs = 1_100L,
                observedState = "end_victory", identity = identity(state = "end_victory", primary = false),
            ),
        )
    }

    // ------------------------------------------------------- BACK admission

    @Test
    fun twoDistinctFreshConfirmedFramesGrantBackAndTheTicketIsUsable() {
        val gate = MenuBackAdmission()
        assertNull(gate.observe(observation(capturedAtMs = 1_000L), 1_000L))
        val ticket = gate.observe(observation(capturedAtMs = 1_250L), 1_250L)
        assertNotNull(ticket)
        assertEquals("brawler_selection", ticket!!.state)
        assertTrue(gate.allows(ticket, 1_300L))
    }

    @Test
    fun singleFrameNeverGrantsBack() {
        val gate = MenuBackAdmission()
        assertNull(gate.observe(observation(capturedAtMs = 1_000L), 1_000L))
    }

    @Test
    fun heartOnlyHudFalsePositiveNeverGrantsBack() {
        val gate = MenuBackAdmission()
        assertNull(gate.observe(observation(independent = false, capturedAtMs = 1_000L), 1_000L))
        assertNull(gate.observe(observation(independent = false, capturedAtMs = 1_250L), 1_250L))
    }

    @Test
    fun gameplayHudControlNeverGrantsBack() {
        val gate = MenuBackAdmission()
        assertNull(gate.observe(observation(gameplayHud = true, capturedAtMs = 1_000L), 1_000L))
        assertNull(gate.observe(observation(gameplayHud = true, capturedAtMs = 1_250L), 1_250L))
    }

    @Test
    fun unknownOrDifferentStatesNeverGrantBack() {
        val gate = MenuBackAdmission()
        assertNull(gate.observe(observation(state = "unknown", menuControl = false, independent = false, capturedAtMs = 1_000L), 1_000L))
        assertNull(gate.observe(observation(state = "unknown", menuControl = false, independent = false, capturedAtMs = 1_250L), 1_250L))
        // A screen change between the pair resets instead of combining.
        assertNull(gate.observe(observation(capturedAtMs = 1_000L), 1_000L))
        assertNull(gate.observe(observation(state = "shop", capturedAtMs = 1_250L), 1_250L))
    }

    @Test
    fun staleOrFutureFramesNeverGrantBack() {
        val gate = MenuBackAdmission()
        assertNull(gate.observe(observation(capturedAtMs = 1_000L), 1_000L))
        // Second frame too old by admission time.
        assertNull(gate.observe(observation(capturedAtMs = 1_500L), 9_000L))
        // Future timestamp (reused/replayed capture) is never fresh.
        val gate2 = MenuBackAdmission()
        assertNull(gate2.observe(observation(capturedAtMs = 2_000L), 1_000L))
        assertNull(gate2.observe(observation(capturedAtMs = 2_100L), 1_000L))
    }

    @Test
    fun captureGeometryChangeNeverGrantsBack() {
        val gate = MenuBackAdmission()
        assertNull(gate.observe(observation(capturedAtMs = 1_000L, width = 1920, height = 1080), 1_000L))
        assertNull(gate.observe(observation(capturedAtMs = 1_250L, width = 1080, height = 1920), 1_250L))
    }

    @Test
    fun ticketExpiresAndIsDeniedAfterInvalidation() {
        val gate = MenuBackAdmission()
        gate.observe(observation(capturedAtMs = 1_000L), 1_000L)
        val ticket = gate.observe(observation(capturedAtMs = 1_250L), 1_250L)!!
        assertFalse(gate.allows(ticket, 1_250L + 60_000L))

        val gate2 = MenuBackAdmission()
        gate2.observe(observation(capturedAtMs = 1_000L), 1_000L)
        val ticket2 = gate2.observe(observation(capturedAtMs = 1_250L), 1_250L)!!
        gate2.invalidate() // Pause / Stop / close / geometry epoch advance
        assertFalse(gate2.allows(ticket2, 1_300L))
    }

    @Test
    fun aLaterObservationRevokesThePreviouslyGrantedTicket() {
        val gate = MenuBackAdmission()
        gate.observe(observation(capturedAtMs = 1_000L), 1_000L)
        val ticket = gate.observe(observation(capturedAtMs = 1_250L), 1_250L)!!
        // A newer frame that no longer confirms must not leave the old permission alive.
        assertNull(gate.observe(observation(independent = false, capturedAtMs = 1_400L), 1_400L))
        assertFalse(gate.allows(ticket, 1_450L))
    }

    @Test
    fun acceptedShopBackStillWorksWithTwoFreshFrames() {
        // `shop` does not require the brawler-menu independent control, so the accepted
        // shop-exit behaviour is preserved as long as two fresh frames agree.
        val gate = MenuBackAdmission()
        assertNull(gate.observe(observation(state = "shop", independent = false, capturedAtMs = 1_000L), 1_000L))
        val ticket = gate.observe(observation(state = "shop", independent = false, capturedAtMs = 1_250L), 1_250L)
        assertNotNull(ticket)
        assertTrue(gate.allows(ticket!!, 1_300L))
    }
}
