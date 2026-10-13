package com.pyla.ai.engine
import org.junit.Test
import org.junit.Assert.*

class ContinuousAttackPolicyTest {
 private fun policy()=RepeatedAttackPolicy(requireNewCapture=false)
 @Test fun attacksContinueBetweenSlowButFreshCaptures() {
  val p=policy();repeat(6){assertEquals(BasicAttackAction.TAP,p.step(10000L+it*300,10000,true,0.0).action)}
 }
 @Test fun sameSnapshotCannotAuthorizePastThreeSeconds() {
  val p=policy();assertEquals(BasicAttackAction.TAP,p.step(10000,10000,true,0.0).action)
  assertEquals(BasicAttackAction.TAP,p.step(13000,10000,true,0.0).action)
  assertEquals(BasicAttackDenial.STALE_FRAME,p.step(13001,10000,true,0.0).denial)
 }
 @Test fun continuousModeDoesNotDisableCadence() {
  val p=policy();assertEquals(BasicAttackAction.TAP,p.step(10000,10000,true,0.0).action)
  assertEquals(BasicAttackAction.NONE,p.step(10299,10000,true,0.0).action)
  assertEquals(BasicAttackAction.TAP,p.step(10300,10000,true,0.0).action)
 }
 @Test fun delayedTickNeverCatchesUpWithBurst() {
  val p=policy();p.step(10000,10000,true,0.0)
  assertEquals(BasicAttackAction.TAP,p.step(12500,12000,true,0.0).action)
  repeat(4){assertEquals(BasicAttackAction.NONE,p.step(12500,12000,true,0.0).action)}
 }
 @Test fun chargeCompletesBetweenFramesAndRespectsNextCadence() {
  val p=policy();assertEquals(BasicAttackAction.HOLD,p.step(10000,10000,true,0.6).action)
  assertEquals(BasicAttackAction.NONE,p.step(10300,10000,true,0.6).action)
  assertEquals(BasicAttackAction.RELEASE,p.step(10600,10000,true,0.6).action)
  assertEquals(BasicAttackAction.NONE,p.step(10899,10000,true,0.6).action)
  assertEquals(BasicAttackAction.HOLD,p.step(10900,10000,true,0.6).action)
 }
 @Test fun denialAlwaysReleasesCharge() {
  for(at in listOf(0L,10001L,6000L)) {
   val p=policy();p.step(9000,9000,true,2.0)
   assertEquals(BasicAttackAction.RELEASE,p.step(10000,at,true,2.0).action)
   assertFalse(p.holding)
  }
 }
 @Test fun lostGameplayRevokesWithoutFreeBurst() {
  val p=policy();p.step(10000,10000,true,0.0);p.revoke()
  assertEquals(BasicAttackAction.NONE,p.step(10001,10001,true,0.0).action)
 }
 @Test fun chargeRevocationIsIdempotent() {
  val p=policy();p.step(10000,10000,true,2.0)
  assertEquals(BasicAttackAction.RELEASE,p.revoke().action);assertEquals(BasicAttackAction.NONE,p.revoke().action)
 }
 @Test fun backwardCaptureCannotRenewAuthority() {
  val p=policy();p.step(10000,10000,true,0.0)
  assertEquals(BasicAttackAction.NONE,p.step(10300,9999,true,0.0).action)
 }
 @Test fun legacyModeStillNeedsNewEvidence() {
  val p=RepeatedAttackPolicy();p.step(10000,10000,true,0.0)
  assertEquals(BasicAttackAction.NONE,p.step(10300,10000,true,0.0).action)
 }
}
