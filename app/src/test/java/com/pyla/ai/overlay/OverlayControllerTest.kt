package com.pyla.ai.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayControllerTest {
    private val reveal = 180L
    private val menu = 160L
    private val hide = 3_000L

    private fun controller() = OverlayController(reveal, menu, hide)

    @Test fun startsClosedAndHalfHidden() {
        val c = controller()
        assertEquals(OverlayStage.CLOSED, c.stage)
        assertEquals(0f, c.revealProgress, 0.0001f)
        assertEquals(0f, c.menuProgress, 0.0001f)
        assertFalse(c.controlsVisible)
    }

    @Test fun firstTapRevealsButDoesNotOpenMenu() {
        val c = controller()
        assertTrue(c.onTap(1000))
        assertTrue(c.animating)
        // Still clamping to the reveal animation; menu never begins on the first tap.
        c.onTick(1000 + reveal)
        assertEquals(OverlayStage.REVEALED, c.stage)
        assertEquals(1f, c.revealProgress, 0.001f)
        assertEquals(0f, c.menuProgress, 0.001f)
    }

    @Test fun tapDuringRevealAnimationIsIgnored() {
        val c = controller()
        c.onTap(0)
        // Mid reveal - a second tap must not open the menu.
        assertFalse(c.onTap(90))
        c.onTick(reveal)
        assertEquals(OverlayStage.REVEALED, c.stage)
        assertEquals(0f, c.menuProgress, 0.001f)
    }

    @Test fun secondTapAfterRevealCompletesOpensMenu() {
        val c = controller()
        c.onTap(0); c.onTick(reveal)
        assertTrue(c.onTap(reveal))
        c.onTick(reveal + menu)
        assertEquals(OverlayStage.MENU_OPEN, c.stage)
        assertEquals(1f, c.menuProgress, 0.001f)
        assertTrue(c.controlsVisible)
    }

    @Test fun tapOnOpenMenuClosesToRevealedThenAutoHides() {
        val c = controller()
        c.onTap(0); c.onTick(reveal)
        c.onTap(reveal); c.onTick(reveal + menu)
        assertEquals(OverlayStage.MENU_OPEN, c.stage)

        c.onTap(reveal + menu)
        c.onTick(reveal + menu + menu)
        assertEquals(OverlayStage.REVEALED, c.stage)
        assertFalse(c.controlsVisible)

        // Not yet: the auto-hide delay has not elapsed.
        c.onTick(reveal + menu + menu + hide - 1)
        assertEquals(OverlayStage.REVEALED, c.stage)

        c.onTick(reveal + menu + menu + hide + 1)
        assertTrue(c.animating)
        c.onTick(reveal + menu + menu + hide + 1 + reveal)
        assertEquals(OverlayStage.CLOSED, c.stage)
        assertEquals(0f, c.revealProgress, 0.001f)
    }

    @Test fun revealWithoutMenuAutoHides() {
        val c = controller()
        c.onTap(0); c.onTick(reveal)
        assertEquals(OverlayStage.REVEALED, c.stage)
        c.onTick(reveal + hide + 1)
        assertTrue(c.animating)
        c.onTick(reveal + hide + 1 + reveal)
        assertEquals(OverlayStage.CLOSED, c.stage)
    }

    @Test fun dragStartClosesMenuImmediatelyAndReveals() {
        val c = controller()
        c.onTap(0); c.onTick(reveal)
        c.onTap(reveal); c.onTick(reveal + menu)
        assertEquals(OverlayStage.MENU_OPEN, c.stage)

        c.onDragStart()
        assertEquals(OverlayStage.REVEALED, c.stage)
        assertEquals(1f, c.revealProgress, 0.001f)
        assertEquals(0f, c.menuProgress, 0.001f)
        assertFalse(c.controlsVisible)
        assertFalse(c.animating)
    }

    @Test fun dragEndSchedulesAutoHideFromNow() {
        val c = controller()
        c.onDragStart()
        c.onDragEnd(10_000)
        assertEquals(OverlayStage.REVEALED, c.stage)
        c.onTick(10_000 + hide - 1)
        assertEquals(OverlayStage.REVEALED, c.stage)
        c.onTick(10_000 + hide + 1)
        assertTrue(c.animating)
        c.onTick(10_000 + hide + 1 + reveal)
        assertEquals(OverlayStage.CLOSED, c.stage)
    }

    @Test fun cancelToClosedIsImmediate() {
        val c = controller()
        c.onTap(0); c.onTick(reveal)
        c.onTap(reveal); c.onTick(reveal + menu)
        c.cancelToClosed()
        assertEquals(OverlayStage.CLOSED, c.stage)
        assertEquals(0f, c.revealProgress, 0.001f)
        assertEquals(0f, c.menuProgress, 0.001f)
        assertFalse(c.animating)
    }

    @Test fun tapIsNeverQueuedAcrossAnimations() {
        val c = controller()
        c.onTap(0)
        // Spam taps through the entire reveal animation.
        (0..20).forEach { c.onTap(it * 9L) }
        c.onTick(reveal)
        assertEquals(OverlayStage.REVEALED, c.stage)
        assertEquals(0f, c.menuProgress, 0.001f)
    }
}
