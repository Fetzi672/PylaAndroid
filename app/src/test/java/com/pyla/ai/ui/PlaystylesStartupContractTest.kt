package com.pyla.ai.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-wiring checks supplement the executable loader tests; not a native Compose test. */
class PlaystylesStartupContractTest {
    private fun screenSource(): String {
        val relative = "src/main/java/com/pyla/ai/ui/PlaystylesScreen.kt"
        return listOf(File(relative), File("app/$relative"))
            .first { it.isFile }.readText()
    }

    @Test fun selectionIsNotResolvedInsideComposition() {
        val source = screenSource()
        assertFalse(
            "Resolving the selection during composition can extract assets on the main thread",
            source.contains("mutableStateOf(PlaystyleSelection.effectivePlaystyle())"),
        )
    }

    @Test fun selectionLoadIsReadinessKeyedAndControlsAwaitItsResult() {
        val source = screenSource()
        assertTrue(source.contains("LaunchedEffect(assetsReady)"))
        assertTrue(source.contains("loadPlaystyleSelection(assetsReady)"))
        assertTrue(source.contains("val selectionReady = assetsReady && selectedPlaystyle != null"))
        assertTrue(source.contains("enabled = selectionReady && !running"))
        assertTrue(source.contains("if (selectionReady && !running && style.filename !in bundled)"))
    }
}
