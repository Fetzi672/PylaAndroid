package com.pyla.ai.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring checks supplement executable policy/native tests; not live-game evidence. */
class SuperSettingsWiringTest {
    private fun source(path: String): String = listOf(File("src/main/$path"), File("app/src/main/$path"))
        .first { it.isFile }.readText()

    @Test fun superSettingsReachTheVisualDetector() {
        val play = source("java/com/pyla/ai/engine/Play.kt")
        val start = play.indexOf("fun checkIfSuperReady(")
        val body = play.substring(start, play.indexOf("fun checkIfGadgetReady(", start))
        assertTrue("Super settings must affect the actual readiness call", body.contains("SuperReadinessPolicy.fromLegacy("))
        assertTrue(body.contains("botConfig().opt(\"super_pixels_minimum\")"))
        assertTrue(body.contains("lobbyConfig().opt(\"pixel_counter_crop_area.super\")"))
        assertTrue(body.contains("PhoneVisualCues.superReady(frame, policy)"))
    }

    @Test fun visualReadinessConsumesConfiguredBoundsAndCoverage() {
        val visual = source("java/com/pyla/ai/engine/PhoneVisualCues.kt")
        val start = visual.indexOf("fun superReady(")
        val body = visual.substring(start, visual.indexOf("/** Coins dismissal", start))
        assertTrue(body.contains("policy?.searchBounds("))
        assertTrue(body.contains("policy.acceptsYellow("))
        assertFalse("The old unconditional 25% check must not ignore configuration", body.contains("mask.total()>0.25"))
    }

    @Test fun settingsExplainTheMigratedLegacyScale() {
        val ui = source("java/com/pyla/ai/ui/SettingsScreen.kt")
        assertTrue(ui.contains("Super readiness threshold"))
        assertTrue(ui.contains("1800 = 25% yellow coverage"))
    }
}
