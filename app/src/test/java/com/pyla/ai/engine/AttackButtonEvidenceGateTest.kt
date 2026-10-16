package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test

class AttackButtonEvidenceGateTest {
    private val point = AttackButtonPoint(500f, 300f, 50f)
    private fun gate() = AttackButtonEvidenceGate(1000L)
    private fun arm(g: AttackButtonEvidenceGate): AttackButtonEvidenceGate.Evidence {
        assertNull(g.observe(point, 100L, 100L, 1L))
        return requireNotNull(g.observe(point, 200L, 200L, 1L))
    }
    @Test fun requiresTwoDistinctCaptures() {
        val g=gate(); assertNull(g.observe(point,100L,100L,1L))
        assertNull(g.observe(point,100L,200L,1L))
        assertNotNull(g.observe(point,201L,201L,1L))
    }
    @Test fun returnsObservedCenterNotConfiguredCoordinate() {
        val g=gate(); val p=AttackButtonPoint(123f,456f,35f)
        g.observe(p,100L,100L,1L);assertEquals(p,g.observe(p,200L,200L,1L)?.point)
    }
    @Test fun absentOrAmbiguousDetectorOutputRevokesImmediately() {
        val g=gate();arm(g);assertNull(g.observe(null,300L,300L,1L));assertNull(g.current(300L,1L))
        assertNull(g.observe(point,400L,400L,1L));assertNotNull(g.observe(point,500L,500L,1L))
    }
    @Test fun changedCenterRequiresReconfirmationAndNewRevision() {
        val g=gate();val old=arm(g);val moved=point.copy(x=700f)
        assertNull(g.observe(moved,300L,300L,1L));assertNull(g.current(300L,1L))
        val newer=requireNotNull(g.observe(moved,400L,400L,1L));assertNotEquals(old.revision,newer.revision)
    }
    @Test fun cumulativeSmallDriftCannotKeepHeldAnchorAuthorized() {
        val g=gate();arm(g)
        assertNotNull(g.observe(point.copy(x=504f),300L,300L,1L))
        assertNull(g.observe(point.copy(x=508f),400L,400L,1L))
    }
    @Test fun staleEvidenceCannotBeRefreshedByReadingIt() {
        val g=gate();arm(g);assertNotNull(g.current(1200L,1L));assertNull(g.current(1201L,1L))
        assertNull(g.observe(point,1300L,1300L,1L))
    }
    @Test fun geometryChangeRevokesOldCoordinates() {
        val g=gate();arm(g);assertNull(g.current(200L,2L));assertNull(g.observe(point,300L,300L,2L))
    }
    @Test fun futureAndBackwardsCapturesFailClosed() {
        val g=gate();arm(g);assertNull(g.observe(point,400L,300L,1L));assertNull(g.current(300L,1L))
        arm(g);assertNull(g.observe(point,150L,300L,1L));assertNull(g.current(300L,1L))
    }
    @Test fun invalidNumbersAndRadiusNeverAuthorizeInput() {
        for(p in listOf(point.copy(x=Float.NaN),point.copy(y=Float.POSITIVE_INFINITY),point.copy(radius=0f),point.copy(x=-1f))) {
            val g=gate();arm(g);assertNull(g.observe(p,300L,300L,1L));assertNull(g.current(300L,1L))
        }
    }
    @Test fun resetInvalidatesQueuedRevisionEvenAtSameCenter() {
        val g=gate();val old=arm(g);g.reset();val newer=arm(g);assertNotEquals(old.revision,newer.revision)
    }
    @Test fun sizeChangeMustBeReconfirmed() {
        val g=gate();arm(g);assertNull(g.observe(point.copy(radius=80f),300L,300L,1L))
    }
}
