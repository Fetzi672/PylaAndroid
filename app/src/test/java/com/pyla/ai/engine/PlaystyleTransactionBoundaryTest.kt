package com.pyla.ai.engine

import com.pyla.ai.capture.InputCoordinates
import com.pyla.ai.pyla.PyDict
import com.pyla.ai.pyla.PyNone
import org.junit.Assert.*
import org.junit.Test

/**
 * Real Play decision/token/persistence methods; no constructor, inference or OS dispatch proof.
 *
 * Target adaptation vs the runtime donor: the baseline-generation `admittedMovementFrameMs`
 * publication field does not exist in the current fork-main Play, and the cadence policy is
 * constructed with its real arguments instead of a default constructor.
 */
class PlaystyleTransactionBoundaryTest {
    private class Rig {
        val play: Play
        val persisted = PyDict(linkedMapOf<Any, Any>("time_since_holding_attack" to PyNone))
        val point = AttackButtonPoint(1_000f, 400f, 40f)
        val button: AttackButtonEvidenceGate.Evidence
        init {
            val type = Class.forName("sun.misc.Unsafe")
            val unsafe = type.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val allocate = type.getMethod("allocateInstance", Class::class.java)
            play = allocate.invoke(unsafe, Play::class.java) as Play
            set("windowController", allocate.invoke(unsafe, WindowController::class.java))
            set("monotonicNowMs", { 2_600L }); set("inputAllowed", { true })
            set("gameplayGeometry", InputCoordinates.geometryGeneration)
            val evidence = GameplayEvidenceGate(Play.MAX_EVIDENCE_FRAME_AGE_MS)
            evidence.observe(true, 1, true, 900L, 1_100L)
            assertTrue(evidence.observe(true, 1, true, 1_000L, 1_100L).admitted)
            set("gameplayEvidence", evidence)
            val buttons = AttackButtonEvidenceGate(Play.MAX_EVIDENCE_FRAME_AGE_MS)
            buttons.observe(point, 900L, 1_100L, InputCoordinates.geometryGeneration)
            button = buttons.observe(point, 1_000L, 1_100L, InputCoordinates.geometryGeneration)!!
            set("attackButtonEvidence", buttons)
            set("basicAttackEligible", true)
            // Constructor-free rig: inject the new durable-calibration boundary explicitly.
            // Real Android preferences are verified separately on the owned emulator.
            set("activeAttackCalibrationRevision", 1L)
            set("calibrationRevisionNow", { 1L })
            set("resetAttackCalibrationObservations", { Unit })
            set("activeGameplayFrameMs", 1_000L)
            set("persistentDataPy", persisted)
            set("attackPolicy", RepeatedAttackPolicy(Play.MAX_EVIDENCE_FRAME_AGE_MS, requireNewCapture = false))
            set("removeCombatPulse", { _: Runnable -> Unit })
        }
        fun set(name: String, value: Any?) = Play::class.java.getDeclaredField(name).apply { isAccessible = true }.set(play, value)
        fun get(name: String): Any? = Play::class.java.getDeclaredField(name).apply { isAccessible = true }.get(play)
        fun apply(vararg intents: PlaystyleIntent) {
            val type = Class.forName("com.pyla.ai.engine.Play\$PlaystyleOutcome\$Decided")
            val decision = type.declaredConstructors.single { it.parameterCount == 5 }.apply { isAccessible = true }
                .newInstance(PlaystyleMovement.Still, intents.toList(), PyDict(LinkedHashMap(persisted.map)), get("scriptHoldEpochRevision"), 0)
            synchronized(play) {
                Play::class.java.getDeclaredMethod("applyPlaystyleDecision", type, Long::class.javaPrimitiveType, Thread::class.java)
                    .apply { isAccessible = true }.invoke(play, decision, 0L, Thread.currentThread())
            }
        }
        @Suppress("UNCHECKED_CAST")
        fun guard(finite: Boolean, token: Long = get("attackDemandToken") as Long): () -> Boolean =
            Play::class.java.getDeclaredMethod("attackIntentGuard", Long::class.javaPrimitiveType,
                AttackButtonEvidenceGate.Evidence::class.java, Boolean::class.javaPrimitiveType,
                Thread::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(play, 0L, button, finite, Thread.currentThread(), token) as () -> Boolean
    }

    @Test fun recalibrationCannotReviveAnOldQueuedAttackGuard() {
        val r = Rig()
        val old = r.guard(false)
        assertTrue(old())
        r.set("calibrationRevisionNow", { 2L })
        assertFalse(old())
        r.set("activeAttackCalibrationRevision", 2L)
        assertFalse("a newer live epoch must not rejuvenate an old command", old())
        assertTrue(r.guard(false)())
    }

    @Test fun successfulWaitingTickPreservesSameScriptOwnedChargeLatch() {
        val r = Rig()
        r.set("pylaScript", com.pyla.ai.pyla.PylaScript.parseContent("charge-contract.pyla", "movement=(0,0)\n"))
        r.set("currentBrawler", "hank")
        r.persisted.map["time_since_holding_attack"] = System.currentTimeMillis() / 1000.0
        r.apply(PlaystyleIntent.Hold)
        val token = r.get("holdIntentToken")
        assertEquals(true, r.get("holdIntentLive"))
        r.apply() // Shipped strategies emit no attack command while their charge timer is live.
        assertEquals("a valid same-module charging wait is not an explicit release", true, r.get("holdIntentLive"))
        assertEquals(token, r.get("holdIntentToken"))
    }

    @Test fun acceptedTapDecisionRevokesEarlierFiniteTapEvenWhenBothWantAttack() {
        val r = Rig(); r.apply(PlaystyleIntent.Tap)
        val old = r.guard(true); assertTrue(old())
        val before = r.get("attackDemandToken") as Long
        r.apply(PlaystyleIntent.Tap)
        assertEquals(before + 1, r.get("attackDemandToken")); assertFalse(old()); assertTrue(r.guard(true)())
    }

    @Test fun everyAcceptedDecisionRotatesFiniteAbilityEpoch() {
        val r = Rig(); r.apply(PlaystyleIntent.Ability("super"))
        val before = r.get("abilityDemandToken") as Long
        r.apply(PlaystyleIntent.Ability("gadget"))
        assertEquals(before + 1, r.get("abilityDemandToken"))
        r.apply(); assertEquals(before + 2, r.get("abilityDemandToken"))
    }

    @Test fun consecutiveHoldDecisionsKeepHoldEpochButReleaseAndReholdReplaceIt() {
        val r = Rig(); r.apply(PlaystyleIntent.Hold)
        val token = r.get("holdIntentToken"); assertEquals(true, r.get("holdIntentLive"))
        val heldBoundary = r.guard(false); assertTrue(heldBoundary())
        r.apply(PlaystyleIntent.Hold)
        assertEquals(token, r.get("holdIntentToken")); assertTrue(heldBoundary())
        r.apply(PlaystyleIntent.Release)
        assertEquals(false, r.get("holdIntentLive")); assertNotEquals(token, r.get("holdIntentToken"))
        r.apply(PlaystyleIntent.Hold)
        assertEquals(true, r.get("holdIntentLive")); assertNotEquals(token, r.get("holdIntentToken"))
    }

    @Test fun newHoldInvalidatesFiniteTapWithoutMakingItsOwnHoldStale() {
        val r = Rig(); r.apply(PlaystyleIntent.Tap); val tap = r.guard(true); assertTrue(tap())
        r.apply(PlaystyleIntent.Hold); assertFalse(tap()); assertTrue(r.guard(false)())
    }

    @Test fun latchedChargeCannotMigrateToAReplacementModule() {
        val r = Rig()
        r.set("pylaScript", com.pyla.ai.pyla.PylaScript.parseContent("first.pyla", "movement=(0,0)\n"))
        r.persisted.map["time_since_holding_attack"] = System.currentTimeMillis() / 1000.0
        r.apply(PlaystyleIntent.Hold)
        r.set("pylaScript", com.pyla.ai.pyla.PylaScript.parseContent("replacement.pyla", "movement=(0,0)\n"))
        r.apply()
        assertEquals(false, r.get("holdIntentLive"))
        assertSame(PyNone, r.persisted.map["time_since_holding_attack"])
    }

    @Test fun changingTheScriptTimestampDoesNotRenewAnExistingChargeLease() {
        val r = Rig()
        r.set("pylaScript", com.pyla.ai.pyla.PylaScript.parseContent("same.pyla", "movement=(0,0)\n"))
        val epoch = System.currentTimeMillis() / 1000.0
        r.persisted.map["time_since_holding_attack"] = epoch
        r.apply(PlaystyleIntent.Hold)
        r.persisted.map["time_since_holding_attack"] = epoch - 0.1
        r.apply()
        assertEquals(false, r.get("holdIntentLive"))
    }

    @Test fun repeatedHoldDoesNotExtendAbsoluteMonotonicDeadline() {
        val r = Rig(); r.apply(PlaystyleIntent.Hold)
        val start = r.get("holdLeaseStartedAtMs") as Long
        r.set("holdLeaseDurationMs", 100L)
        r.apply(PlaystyleIntent.Hold)
        assertEquals(start, r.get("holdLeaseStartedAtMs"))
        assertEquals(100L, r.get("holdLeaseDurationMs"))
        val valid = Play::class.java.getDeclaredMethod("holdLeaseCurrent").apply { isAccessible = true }
        r.set("monotonicNowMs", { start + 99L }); assertEquals(true, valid.invoke(r.play))
        r.set("monotonicNowMs", { start + 100L }); assertEquals(false, valid.invoke(r.play))
        r.apply(PlaystyleIntent.Hold)
        assertEquals("expiry releases, never starts another DOWN in the same decision", false, r.get("holdIntentLive"))
    }

    @Test fun missingScriptLatchDoesNotMakeAnUnrequestedHoldSticky() {
        val r = Rig(); r.apply(PlaystyleIntent.Hold); r.apply()
        assertEquals(false, r.get("holdIntentLive"))
    }

    @Test fun deniedFirstHoldClearsUnsentLatchAndAllowsFreshVerifiedRetry() {
        val r = Rig()
        val verified = r.get("attackButtonEvidence")
        r.set("attackButtonEvidence", AttackButtonEvidenceGate(Play.MAX_EVIDENCE_FRAME_AGE_MS))
        r.persisted.map["accepted_data"] = 7L
        r.persisted.map["time_since_holding_attack"] = System.currentTimeMillis() / 1000.0
        r.apply(PlaystyleIntent.Hold)
        assertEquals(false, r.get("holdIntentLive"))
        assertSame("a denied DOWN must not commit a fictional charging wait", PyNone,
            r.persisted.map["time_since_holding_attack"])
        assertEquals(7L, r.persisted.map["accepted_data"])
        r.set("attackButtonEvidence", verified)
        r.persisted.map["time_since_holding_attack"] = System.currentTimeMillis() / 1000.0
        r.apply(PlaystyleIntent.Hold)
        assertEquals(true, r.get("holdIntentLive"))
    }

    @Test fun releaseClearsSpeculativeLatchEvenWhenNoDownReachedTheDriver() {
        val r = Rig(); r.persisted.map["time_since_holding_attack"] = System.currentTimeMillis() / 1000.0
        r.apply(PlaystyleIntent.Release)
        assertSame(PyNone, r.persisted.map["time_since_holding_attack"])
    }

    @Test fun interveningNativeReleaseCannotResurrectScriptHoldEpochOnCommit() {
        val r = Rig(); r.persisted.map["time_since_holding_attack"] = 123L
        val staged = PyDict(LinkedHashMap(r.persisted.map)); staged.map["accepted_data"] = 7L
        val revision = r.get("scriptHoldEpochRevision")
        Play::class.java.getDeclaredMethod("clearScriptHoldEpoch").apply { isAccessible = true }.invoke(r.play)
        Play::class.java.getDeclaredMethod("commitPlaystylePersistence", PyDict::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(r.play, staged, revision)
        assertSame(PyNone, r.persisted.map["time_since_holding_attack"])
        assertEquals(7L, r.persisted.map["accepted_data"])
    }

    @Test fun unverifiedSuperCannotFallBackToBlindMappedPress() {
        val r = Rig(); r.set("isSuperReady", true); r.set("activeGameplayTarget", true)
        val result = Play::class.java.getDeclaredMethod("issueSpecialAbility", String::class.java, java.lang.Long::class.java)
            .apply { isAccessible = true }.invoke(r.play, "super", null)
        assertEquals(false, result)
        assertEquals(true, r.get("isSuperReady"))
    }
}
