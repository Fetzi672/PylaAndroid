package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test

class GameStateAuthorityTest {
    @Test fun unknownMenuCannotGrantMatch() {
        val a=GameStateAuthority();assertEquals("unknown",a.menu("unknown",100,100).state)
    }
    @Test fun unknownMenuCannotEraseOrRenewAdmittedMatch() {
        val a=GameStateAuthority();a.gameplay(true,100,100)
        assertEquals(GameStateAuthority.Snapshot("match",100),a.menu("unknown",500,500))
        assertEquals("match",a.snapshot(3100).state)
        assertEquals("unknown",a.menu("unknown",3101,3101).state)
    }
    @Test fun everyNewPositiveGameplayFrameRefreshesOnlyItsOwnCaptureAge() {
        val a=GameStateAuthority();a.gameplay(true,100,100);a.gameplay(true,1000,1000)
        assertEquals(GameStateAuthority.Snapshot("match",1000),a.snapshot(3500))
        assertEquals("unknown",a.snapshot(4001).state)
    }
    @Test fun singleKnownMenuRevokesBeforeNavigationPair() {
        val a=GameStateAuthority();a.gameplay(true,100,100)
        assertEquals("unknown",a.menu("lobby",200,200).state)
        assertEquals("unknown",a.menu("lobby",200,200).state)
        assertEquals("lobby",a.menu("lobby",201,201).state)
    }
    @Test fun lateGameplayCannotOverwriteNewerMenu() {
        val a=GameStateAuthority();a.menu("lobby",200,200);a.menu("lobby",201,201)
        assertEquals("lobby",a.gameplay(true,100,250).state)
        assertEquals("lobby",a.gameplay(true,201,250).state)
    }
    @Test fun lateMenuCannotOverwriteNewerPositivePlayerFrame() {
        val a=GameStateAuthority();a.gameplay(true,500,500)
        assertEquals("match",a.menu("lobby",200,600).state)
    }
    @Test fun explicitPlayerLossRevokesAndCannotBeUndoneBySameFramePositive() {
        val a=GameStateAuthority();a.gameplay(true,100,100);a.gameplay(false,200,200)
        assertEquals("unknown",a.gameplay(true,200,200).state)
        assertEquals("unknown",a.menu("unknown",210,210).state)
        assertEquals("match",a.gameplay(true,220,220).state)
    }
    @Test fun olderDenialCannotEraseNewerAdmittedFrame() {
        val a=GameStateAuthority();a.gameplay(true,300,300)
        assertEquals("match",a.gameplay(false,200,300).state)
    }
    @Test fun staleFutureAndInvalidObservationsNeverGrantAuthority() {
        for(at in listOf(0L,-1L,100L,5001L)) {
            val a=GameStateAuthority()
            assertNotEquals("match",a.gameplay(true,at,5000).state)
            assertNotEquals("lobby",a.menu("lobby",at,5000).state)
        }
    }
    @Test fun pauseOrStopBarrierRejectsInFlightOldCapture() {
        val a=GameStateAuthority();a.gameplay(true,100,100);a.invalidate(250)
        assertEquals("unknown",a.gameplay(true,200,300).state)
        assertEquals("unknown",a.gameplay(true,250,300).state)
        assertEquals("match",a.gameplay(true,301,301).state)
    }
    @Test fun oldMenuCallbacksCannotCompleteANewMenuPair() {
        val a=GameStateAuthority();a.menu("lobby",200,200);a.menu("unknown",300,300)
        assertEquals("unknown",a.menu("lobby",250,350).state)
        assertEquals("unknown",a.menu("lobby",400,400).state)
        assertEquals("lobby",a.menu("lobby",401,401).state)
    }
    @Test fun resetSeparatesRuns() {
        val a=GameStateAuthority();a.gameplay(true,100,100);a.reset()
        assertNull(a.snapshot(200).state)
        assertEquals("unknown",a.menu("lobby",201,201).state)
    }
    @Test fun unknownRevokesMenuNavigationInsteadOfHoldingOldScreenUntilExpiry() {
        for (screen in listOf("lobby","match_making","match_start","end_ranked","defeated")) {
            val a=GameStateAuthority();a.menu(screen,100,100)
            assertEquals(screen,a.menu(screen,101,101).state)
            assertEquals(GameStateAuthority.Snapshot("unknown",0),a.menu("unknown",102,102))
            // Unknown itself does not grant; genuinely newer positive gameplay may do so.
            assertEquals("match",a.gameplay(true,103,103).state)
        }
    }
    @Test fun outOfOrderUnknownCannotEraseNewerVerifiedMenu() {
        val a=GameStateAuthority();a.menu("lobby",200,200);a.menu("lobby",201,201)
        assertEquals("lobby",a.menu("unknown",199,250).state)
    }
}
