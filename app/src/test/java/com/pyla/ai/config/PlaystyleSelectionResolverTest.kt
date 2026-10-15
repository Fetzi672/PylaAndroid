package com.pyla.ai.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Deterministic JVM tests for the effective playstyle selection: legacy preference vs
 * TOML precedence, backward compatibility for TOML-only installs, blank/missing/unloadable
 * fallbacks to the bundled default, select/import/delete transitions, and filename
 * containment. Uses an injected fake source plus real temp files for the containment
 * helper — no Android involved.
 */
class PlaystyleSelectionResolverTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSource(
        override val defaultPlaystyle: String = "default.pyla",
        var legacy: String? = null,
        var toml: String? = null,
        files: Set<String> = setOf("default.pyla"),
    ) : PlaystyleSelectionSource {
        val existing = files.toMutableSet()
        val tomlWrites = mutableListOf<String>()
        val legacyWrites = mutableListOf<String>()
        override fun readLegacyPreference(): String? = legacy
        override fun readTomlSelection(): String? = toml
        override fun writeTomlSelection(value: String) { toml = value; tomlWrites += value }
        override fun writeLegacyPreference(value: String) { legacy = value; legacyWrites += value }
        override fun playstyleFileExists(filename: String): Boolean = filename in existing
    }

    // --- precedence and backward compatibility ---

    @Test
    fun legacyPreferenceWinsOverDifferingToml() {
        val source = FakeSource(legacy = "aggressive.pyla", toml = "defensive.pyla")
        source.existing += "aggressive.pyla"

        val effective = PlaystyleSelectionResolver(source).effectivePlaystyle()

        assertEquals("aggressive.pyla", effective)
        // Reconciled into TOML, mirrored preference untouched (already the winner).
        assertEquals(listOf("aggressive.pyla"), source.tomlWrites)
        assertTrue(source.legacyWrites.isEmpty())
        assertEquals("aggressive.pyla", source.toml)
    }

    @Test
    fun tomlOnlyInstallIsRetainedWithoutAnyRewrite() {
        val source = FakeSource(legacy = null, toml = "custom.pyla")
        source.existing += "custom.pyla"

        val effective = PlaystyleSelectionResolver(source).effectivePlaystyle()

        assertEquals("custom.pyla", effective)
        assertTrue(source.tomlWrites.isEmpty())
        assertTrue(source.legacyWrites.isEmpty())
    }

    @Test
    fun blankLegacyPreferenceFallsBackToToml() {
        val source = FakeSource(legacy = "   ", toml = "custom.pyla")
        source.existing += "custom.pyla"
        assertEquals("custom.pyla", PlaystyleSelectionResolver(source).effectivePlaystyle())
    }

    @Test
    fun blankTomlValueFallsBackToLegacyAndMigrates() {
        val source = FakeSource(legacy = "custom.pyla", toml = "")
        source.existing += "custom.pyla"

        assertEquals("custom.pyla", PlaystyleSelectionResolver(source).effectivePlaystyle())
        assertEquals(listOf("custom.pyla"), source.tomlWrites)
        assertTrue(source.legacyWrites.isEmpty())
    }

    // --- default fallbacks ---

    @Test
    fun bothStoresBlankFallBackToBundledDefault() {
        val source = FakeSource(legacy = "", toml = "")
        assertEquals("default.pyla", PlaystyleSelectionResolver(source).effectivePlaystyle())
        assertTrue(source.tomlWrites.isEmpty())
        assertTrue(source.legacyWrites.isEmpty())
    }

    @Test
    fun missingStoresFallBackToBundledDefault() {
        val source = FakeSource(legacy = null, toml = null)
        assertEquals("default.pyla", PlaystyleSelectionResolver(source).effectivePlaystyle())
    }

    @Test
    fun unloadableTomlSelectionFallsBackToDefaultWithoutRewrite() {
        val source = FakeSource(legacy = null, toml = "ghost.pyla") // file does not exist
        assertEquals("default.pyla", PlaystyleSelectionResolver(source).effectivePlaystyle())
        assertTrue(source.tomlWrites.isEmpty())
    }

    @Test
    fun unloadableLegacyPreferenceIsReconciledToDefault() {
        val source = FakeSource(legacy = "ghost.pyla", toml = "default.pyla")

        assertEquals("default.pyla", PlaystyleSelectionResolver(source).effectivePlaystyle())
        assertEquals(listOf("default.pyla"), source.tomlWrites)
        assertEquals(listOf("default.pyla"), source.legacyWrites)
    }

    @Test
    fun reconciliationIsIdempotentAfterFirstRead() {
        val source = FakeSource(legacy = "ghost.pyla", toml = "default.pyla")
        val resolver = PlaystyleSelectionResolver(source)
        resolver.effectivePlaystyle()
        resolver.effectivePlaystyle()
        assertEquals(1, source.tomlWrites.size)
        assertEquals(1, source.legacyWrites.size)
    }

    // --- select / import / delete transitions ---

    @Test
    fun selectPersistsToBothStoresAndBecomesEffective() {
        val source = FakeSource(legacy = null, toml = null)
        val resolver = PlaystyleSelectionResolver(source)

        assertEquals("new.pyla", resolver.select("new.pyla"))
        assertEquals("new.pyla", source.toml)
        assertEquals("new.pyla", source.legacy)

        source.existing += "new.pyla"
        assertEquals("new.pyla", resolver.effectivePlaystyle())
    }

    @Test
    fun selectBlankFallsBackToBundledDefault() {
        val source = FakeSource()
        assertEquals("default.pyla", PlaystyleSelectionResolver(source).select("  "))
    }

    @Test
    fun deletingEffectiveStyleFallsBackToDefaultInBothStores() {
        val source = FakeSource(legacy = "imported.pyla", toml = "imported.pyla")
        source.existing += "imported.pyla"
        val resolver = PlaystyleSelectionResolver(source)
        source.existing -= "imported.pyla" // PlaystyleRegistry.delete just removed the file

        assertEquals("default.pyla", resolver.onPlaystyleDeleted("imported.pyla"))
        assertEquals("default.pyla", source.toml)
        assertEquals("default.pyla", source.legacy)
    }

    @Test
    fun deletingInactiveStyleKeepsCurrentSelectionUntouched() {
        val source = FakeSource(legacy = "keep.pyla", toml = "keep.pyla")
        source.existing += setOf("keep.pyla", "other.pyla")

        assertEquals("keep.pyla", PlaystyleSelectionResolver(source).onPlaystyleDeleted("other.pyla"))
        assertTrue(source.tomlWrites.isEmpty())
        assertTrue(source.legacyWrites.isEmpty())
    }

    // --- filename containment (import trust boundary) ---

    @Test
    fun containmentAcceptsExistingDirectChildOnly() {
        val dir = tmp.newFolder("playstyles")
        File(dir, "ok.pyla").writeText("x")

        assertTrue(isContainedPlaystyleFile(dir, "ok.pyla"))
        assertFalse(isContainedPlaystyleFile(dir, "missing.pyla"))
        assertFalse(isContainedPlaystyleFile(dir, ""))
        assertFalse(isContainedPlaystyleFile(dir, "   "))
    }

    @Test
    fun containmentRejectsTraversalShapes() {
        val dir = tmp.newFolder("playstyles")
        File(tmp.root, "escape.pyla").writeText("x")
        val sub = File(dir, "sub").apply { mkdirs() }
        File(sub, "inner.pyla").writeText("x")

        assertFalse(isContainedPlaystyleFile(dir, "../escape.pyla"))
        assertFalse(isContainedPlaystyleFile(dir, "sub/inner.pyla"))
        assertFalse(isContainedPlaystyleFile(dir, ".."))
    }

    @Test
    fun containmentRejectsSymlinkEscapingTheDirectory() {
        val dir = tmp.newFolder("playstyles")
        val outside = File(tmp.root, "outside.pyla").apply { writeText("x") }
        val link = File(dir, "link.pyla")
        val created = try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        } catch (_: Throwable) {
            false
        }
        Assume.assumeTrue("symlinks unsupported on this filesystem", created)
        assertFalse(isContainedPlaystyleFile(dir, "link.pyla"))
    }

    // --- integration: TOML reconciliation goes through the settings write contract ---

    @Test
    fun tomlReconciliationUsesSettingsPersistenceContract() {
        val configDir = tmp.newFolder("cfg")
        val configFile = File(configDir, "bot_config.toml")
        configFile.writeText("current_playstyle = \"shipped_default.pyla\"\nother = 1\n")
        val prefs = object : PrefsBackend {
            val map = LinkedHashMap<String, String>()
            override fun contains(key: String) = map.containsKey(key)
            override fun getString(key: String) = map[key]
            override fun putString(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
            override fun allKeys(): Set<String> = map.keys.toSet()
        }
        val store = UserSettingsStore(prefs, { relative -> File(tmp.root, relative) })

        val source = object : PlaystyleSelectionSource {
            override val defaultPlaystyle = "shipped_default.pyla"
            override fun readLegacyPreference(): String? = "user_choice.pyla"
            override fun readTomlSelection(): String? =
                store.getString("cfg/bot_config.toml", "current_playstyle", null)
            override fun writeTomlSelection(value: String) =
                store.set("cfg/bot_config.toml", "current_playstyle", value)
            override fun writeLegacyPreference(value: String) {}
            override fun playstyleFileExists(filename: String) =
                filename == "user_choice.pyla" || filename == "shipped_default.pyla"
        }

        val effective = PlaystyleSelectionResolver(source).effectivePlaystyle()
        assertEquals("user_choice.pyla", effective)
        // Engine-style read of the file now sees the same choice, properly quoted.
        assertEquals(
            "user_choice.pyla",
            TomlLite.parse(configFile.readText()).opt("current_playstyle")?.toString(),
        )
        // And the shipped default was snapshotted before the reconcile write, so a
        // settings-style remove restores it instead of the reconciled value.
        store.remove("cfg/bot_config.toml", "current_playstyle")
        assertTrue(configFile.readText().contains("current_playstyle = \"shipped_default.pyla\""))
        assertEquals(
            "other = 1",
            configFile.readText().lines().first { it.isNotBlank() && !it.startsWith("current_playstyle") },
        )
    }
}
