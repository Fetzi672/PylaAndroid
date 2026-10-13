package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test

class RuntimeVerificationTest {
    @Test fun firstObservationIsNotAdmission() { assertFalse(StateObservationGate().observe("lobby", 1000, 1000)) }
    @Test fun duplicateFrameIsNotSecondEvidence() {
        val g = StateObservationGate(); assertFalse(g.observe("lobby", 1000, 1000)); assertFalse(g.observe("lobby", 1000, 1001))
    }
    @Test fun twoFreshDistinctFramesAdmit() {
        val g = StateObservationGate(); g.observe("lobby", 1000, 1000); assertTrue(g.observe("lobby", 1250, 1250))
    }
    @Test fun changedStateNeedsItsOwnPair() {
        val g = StateObservationGate(); g.observe("lobby", 1000, 1000); g.observe("lobby", 1250, 1250)
        assertFalse(g.observe("shop", 1500, 1500)); assertTrue(g.observe("shop", 1750, 1750))
    }
    @Test fun unknownRevokesAdmission() {
        val g = StateObservationGate(); g.observe("lobby", 1000, 1000); g.observe("lobby", 1250, 1250)
        assertFalse(g.observe("unknown", 1500, 1500)); assertFalse(g.observe("lobby", 1750, 1750))
    }
    @Test fun classifierMatchDefaultCannotAdmitGameplay() {
        val g = StateObservationGate(); assertFalse(g.observe("match", 1000, 1000)); assertFalse(g.observe("match", 1250, 1250))
    }
    @Test fun arbitraryStateIsNotAdmission() { assertFalse(StateObservationGate().observe("untrusted OCR", 1000, 1000)) }
    @Test fun futureTimestampCannotAdmit() { assertFalse(StateObservationGate().observe("lobby", 1001, 1000)) }
    @Test fun staleTimestampCannotAdmit() { assertFalse(StateObservationGate().observe("lobby", 1000, 4001)) }
    @Test fun invalidTimestampCannotAdmit() { assertFalse(StateObservationGate().observe("lobby", 0, 1000)) }
    @Test fun reversedFramesRestartPair() {
        val g = StateObservationGate(); g.observe("lobby", 1000, 1000); g.observe("lobby", 1250, 1250)
        assertFalse(g.observe("lobby", 1200, 1300)); assertTrue(g.observe("lobby", 1350, 1350))
    }
    @Test fun overlongObservationGapRestartsPair() {
        val g = StateObservationGate(); g.observe("lobby", 1000, 1000); g.observe("lobby", 1250, 1250)
        assertFalse(g.observe("lobby", 5000, 5000)); assertTrue(g.observe("lobby", 5250, 5250))
    }
    @Test fun rollingEvidenceDoesNotExpireFirstFrameForever() {
        val g = StateObservationGate(); g.observe("lobby", 1000, 1000)
        for (n in 2..12) assertTrue(g.observe("lobby", n * 1000L, n * 1000L))
    }
    @Test fun resetRequiresNewPair() {
        val g = StateObservationGate(); g.observe("lobby", 1000, 1000); g.observe("lobby", 1250, 1250); g.reset()
        assertFalse(g.observe("lobby", 1500, 1500))
    }
    @Test fun unverifiedExpectedStateIsDisplayedAsUnknown() {
        val s = RuntimeVerification(RuntimeState.BRAWLER_LIST, VerificationPhase.VERIFY_GRID, false)
        assertEquals("State: Unknown", s.stateLine); assertEquals("Check: Verifying list", s.phaseLine)
    }
    @Test fun verifiedGridAndPhaseAreSeparate() {
        val s = RuntimeVerification(RuntimeState.BRAWLER_LIST, VerificationPhase.SCAN_NAMES, true)
        assertEquals("State: Brawler list", s.stateLine); assertEquals("Check: Scanning names", s.phaseLine)
    }
    @Test fun stoppedSnapshotDoesNotClaimOldState() {
        val s = RuntimeVerification(); assertEquals("State: Unknown", s.stateLine); assertEquals("Check: Stopped", s.phaseLine)
    }
    @Test fun classifierMappingsAreFixedAndNoMatchShortcut() {
        assertEquals(RuntimeState.UNKNOWN, RuntimeState.fromClassifier("match"))
        assertEquals(RuntimeState.UNKNOWN, RuntimeState.fromClassifier("private user text"))
        assertEquals(RuntimeState.LOBBY, RuntimeState.fromClassifier("lobby"))
        assertEquals(RuntimeState.BRAWLER_LIST, RuntimeState.fromClassifier("brawler_selection"))
        assertEquals(RuntimeState.RESULT, RuntimeState.fromClassifier("end_victory"))
        assertEquals(RuntimeState.REWARD, RuntimeState.fromClassifier("star_drop_regular"))
    }
    @Test fun bothPairMembersMustStillBeFreshAtDecision() {
        val g=StateObservationGate();assertFalse(g.observe("end_ranked",1000,1000))
        assertFalse(g.observe("end_ranked",4000,4001))
        assertTrue(g.observe("end_ranked",4002,4002))
    }
    @Test fun firstPairMemberAtExactMaxAgeIsAllowed() {
        val g=StateObservationGate();g.observe("lobby",1000,1000)
        assertTrue(g.observe("lobby",4000,4000))
    }
    @Test fun duplicateCannotRenewExpiredFirstPairMember() {
        val g=StateObservationGate();g.observe("lobby",1000,1000)
        assertTrue(g.observe("lobby",3999,3999))
        assertFalse(g.observe("lobby",3999,4001))
        assertTrue(g.observe("lobby",4002,4002))
    }
}
