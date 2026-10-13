package com.pyla.ai.engine
import org.junit.Test
import org.junit.Assert.*
class SceneProgressTrackerTest {
 @Test fun firstFrameUnknownThenUnchangedSceneIsNonprogress(){val p=SceneProgressTracker();assertNull(p.observe(IntArray(30){80},1000,1000));assertEquals(false,p.observe(IntArray(30){80},2000,2000))}
 @Test fun broadWorldChangeIsProgress(){val p=SceneProgressTracker();p.observe(IntArray(30){80},1000,1000);assertEquals(true,p.observe(IntArray(30){if(it<6)100 else 80},2000,2000))}
 @Test fun isolatedAnimationAndColourNoiseAreNotProgress(){val p=SceneProgressTracker();p.observe(IntArray(30){80},1000,1000);assertEquals(false,p.observe(IntArray(30){if(it==0)200 else 87},2000,2000))}
 @Test fun repeatedOrBackwardFrameCannotRenewSample(){val p=SceneProgressTracker();p.observe(IntArray(30){80},1000,1000);assertNull(p.observe(IntArray(30){200},1000,1200));assertNull(p.observe(IntArray(30){200},999,1300));assertEquals(false,p.observe(IntArray(30){80},1400,1400))}
 @Test fun invalidStaleFutureAndGeometryResetHistory(){for(bad in 0..3){val p=SceneProgressTracker();p.observe(IntArray(30){80},1000,1000);when(bad){0->assertNull(p.observe(IntArray(30){-1},2000,2000));1->assertNull(p.observe(IntArray(30){80},2001,2000));2->assertNull(p.observe(IntArray(30){80},1000,4001));else->assertNull(p.observe(IntArray(15){80},2000,2000))};assertNull(p.observe(IntArray(30){80},5000,5000))}}
 @Test fun callerMutationCannotChangeHistory(){val p=SceneProgressTracker();val x=IntArray(30){80};p.observe(x,1000,1000);x.fill(200);assertEquals(false,p.observe(IntArray(30){80},2000,2000))}
 @Test fun resetStartsUnknown(){val p=SceneProgressTracker();p.observe(IntArray(30){80},1000,1000);p.reset();assertNull(p.observe(IntArray(30){200},2000,2000))}
}
