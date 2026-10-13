package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class BlockedMovementRecoveryTest {
    private fun wall(a: Pair<Double,Double>, b: Pair<Double,Double>, radius: Double): Boolean {
        val dx=b.first-a.first; val dy=b.second-a.second
        var lo=0.0; var hi=1.0
        for ((p,q) in listOf(-dx to (a.first-20+radius),dx to (30+radius-a.first),
            -dy to (a.second+50+radius),dy to (50+radius-a.second))) {
            if (abs(p)<1e-12) { if(q<0) return false }
            else { val t=q/p; if(p<0)lo=max(lo,t) else hi=min(hi,t); if(lo>hi)return false }
        }
        return true
    }
    private fun plan(enemies:List<Pair<Double,Double>>, probe:(Pair<Double,Double>,Pair<Double,Double>,Double)->Boolean=::wall) =
        DirectedMovementPlanner.plan(0.0 to 0.0,enemies,attackRange=50.0,playerRadius=0.0,stepPx=54.0,wallProbe=probe)

    @Test fun blockedNearestCannotSuppressDetectedClearAlternative() {
        val targets=listOf(200.0 to 0.0,0.0 to 300.0)
        val d=plan(targets)
        assertEquals(MovementReason.APPROACH_TARGET,d.reason)
        assertEquals(0.0,d.vector!!.first,1e-6);assertEquals(75.0,d.vector.second,1e-6)
        assertEquals(d,plan(targets.reversed()))
    }
    @Test fun oneGoalUsesOnlyACollisionCheckedProgressRoute() {
        val d=plan(listOf(200.0 to 0.0))
        assertEquals(MovementReason.WALL_AVOIDED,d.reason)
        assertNotNull(d.vector)
        val mid=d.vector!!.let { it.first/75.0*54.0 to it.second/75.0*54.0 }
        assertFalse(wall(0.0 to 0.0,mid,0.0))
        val angle=atan2(-mid.second,200.0-mid.first)
        assertTrue(listOf(0.0,PI/4,-PI/4).any { turn ->
            val end=mid.first+cos(angle+turn)*54 to mid.second+sin(angle+turn)*54
            hypot(end.first-200,end.second)<200 && !wall(mid,end,0.0)
        })
    }
    @Test fun freeFirstLegWithBlockedSecondLegDoesNotAuthorizeDetour() {
        val d=plan(listOf(200.0 to 0.0)) { from,to,r ->
            if (from != (0.0 to 0.0)) true else wall(from,to,r)
        }
        assertEquals(MovementReason.ALL_PATHS_BLOCKED,d.reason);assertNull(d.vector)
    }
    @Test fun losingTargetAfterDetourDoesNotKeepMoving() {
        assertNotNull(plan(listOf(200.0 to 0.0)).vector)
        assertNull(plan(emptyList()).vector)
    }
    @Test fun blockedEverywhereStillReleasesWithAlternatives() {
        val d=plan(listOf(200.0 to 0.0,0.0 to 300.0)){_,_,_->true}
        assertEquals(MovementReason.ALL_PATHS_BLOCKED,d.reason);assertNull(d.vector)
    }
    @Test fun freeNearestRemainsPreferredOverAlternatives() {
        val d=plan(listOf(200.0 to 0.0,0.0 to 300.0)){_,_,_->false}
        assertEquals(75.0,d.vector!!.first,1e-6);assertEquals(0.0,d.vector.second,1e-6)
    }
    @Test fun excessiveDetectorOutputHasBoundedProbeCost() {
        var calls=0
        plan((1..10000).map { it.toDouble()+100 to it.toDouble()+200 }) { _,_,_->calls++;true }
        assertTrue(calls<500)
    }
}
