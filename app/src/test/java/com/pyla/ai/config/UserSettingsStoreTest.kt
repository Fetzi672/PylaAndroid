package com.pyla.ai.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Deterministic JVM tests for the pure persistence core behind [PylaUserSettings]:
 * default snapshots must be taken BEFORE mutation, set/remove/clearAll must restore the
 * pristine TOML values, and values written through the codec must read back identical
 * through the real parser. Uses an in-memory [PrefsBackend] and real temp TOML files —
 * no Android involved.
 */
class UserSettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var prefs: FakePrefs
    private lateinit var store: UserSettingsStore
    private lateinit var configFile: File

    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        prefs = FakePrefs()
        configFile = tmp.newFile("bot_config.toml")
        configFile.writeText(
            """
            minimum_movement_delay = 0.1
            current_playstyle = "default.pyla"
            max_ips = 30
            play_again_on_win = "no"
            """.trimIndent()
        )
        store = UserSettingsStore(prefs, { relative -> File(tmp.root, relative) }, logs::add)
    }

    // --- the core regression: snapshot default BEFORE mutation ---

    @Test
    fun setThenRemoveRestoresPristineDefault() {
        store.set("bot_config.toml", "current_playstyle", "custom.pyla")
        assertTrue(configFile.readText().contains("current_playstyle = \"custom.pyla\""))

        store.remove("bot_config.toml", "current_playstyle")

        // Old behavior: the default snapshot missed the pristine value and remove wrote
        // the override back into the file, making overrides unremovable.
        assertTrue(configFile.readText().contains("current_playstyle = \"default.pyla\""))
        assertFalse(prefs.map.containsKey("bot_config.toml::current_playstyle"))
    }

    @Test
    fun firstEverSetSnapshotsPristineDefaultWithoutPriorRead() {
        store.set("bot_config.toml", "current_playstyle", "custom.pyla")
        assertEquals("default.pyla", store.getDefaultString("bot_config.toml", "current_playstyle"))
    }

    @Test
    fun defaultSnapshotIsStableAfterLaterExternalFileEdits() {
        store.set("bot_config.toml", "current_playstyle", "custom.pyla")
        configFile.writeText(configFile.readText().replace("custom.pyla", "externally_changed.pyla"))
        assertEquals("default.pyla", store.getDefaultString("bot_config.toml", "current_playstyle"))
    }

    @Test
    fun setWithoutExistingTomlKeyLineLeavesFileUntouchedAndTracksOverride() {
        store.set("bot_config.toml", "unknown_key", "x")
        assertFalse(configFile.readText().contains("unknown_key"))
        // The override is mirrored as bookkeeping so `has` reports it...
        assertTrue(prefs.map.containsKey("bot_config.toml::unknown_key"))
        assertTrue(store.has("bot_config.toml", "unknown_key"))
        // ...but [UserSettingsStore.getString] reads the authoritative TOML file, matching the
        // original PylaConfig-backed facade (PylaConfig.load(...).opt(key) ?: default): a key
        // absent from the file reads back as the default, not the preference mirror.
        assertNull(store.getString("bot_config.toml", "unknown_key", null))
    }

    @Test
    fun setOnMissingFileDoesNotThrowAndRemoveKeepsPrefWhenNoDefaultKnown() {
        store.set("missing.toml", "key", "v")
        assertEquals("v", prefs.map["missing.toml::key"])
        store.remove("missing.toml", "key")
        // No default was ever known -> remove is a no-op and keeps the override entry.
        assertEquals("v", prefs.map["missing.toml::key"])
        assertTrue(logs.isNotEmpty())
    }

    // --- reads ---

    @Test
    fun getStringReadsTomlValueOrDefault() {
        assertEquals("no", store.getString("bot_config.toml", "play_again_on_win", null))
        assertEquals("fallback", store.getString("bot_config.toml", "not_there", "fallback"))
        assertNull(store.getString("not_there.toml", "key", null))
    }

    @Test
    fun hasReflectsOverridePresence() {
        assertFalse(store.has("bot_config.toml", "current_playstyle"))
        store.set("bot_config.toml", "current_playstyle", "custom.pyla")
        assertTrue(store.has("bot_config.toml", "current_playstyle"))
        store.remove("bot_config.toml", "current_playstyle")
        assertFalse(store.has("bot_config.toml", "current_playstyle"))
    }

    @Test
    fun simpleValuesKeepBareTomlFormatting() {
        store.set("bot_config.toml", "max_ips", "45")
        assertTrue(configFile.readText().contains("max_ips = 45"))
        assertEquals("45", store.getString("bot_config.toml", "max_ips", null))
    }

    // --- special-character round trip through the full write -> read path ---

    @Test
    fun specialCharacterValueRoundTripsThroughSetAndGet() {
        val nasty = "say \"hi\"\\\n#1 ${'$'}x\tend"
        store.set("bot_config.toml", "current_playstyle", nasty)
        assertEquals(nasty, store.getString("bot_config.toml", "current_playstyle", null))
        // The file itself must stay a single physical line per key (escaped, not raw).
        assertEquals(1, configFile.readText().lines().count { it.contains("current_playstyle =") })
    }

    // --- clearAll ---

    @Test
    fun clearAllRestoresDefaultsAndKeepsSnapshots() {
        store.set("bot_config.toml", "current_playstyle", "custom.pyla")
        store.set("bot_config.toml", "play_again_on_win", "yes")

        store.clearAll()

        val text = configFile.readText()
        assertTrue(text.contains("current_playstyle = \"default.pyla\""))
        assertTrue(text.contains("play_again_on_win = \"no\""))
        assertFalse(prefs.map.containsKey("bot_config.toml::current_playstyle"))
        assertFalse(prefs.map.containsKey("bot_config.toml::play_again_on_win"))
        // Snapshot bookkeeping entries survive clearAll (historical contract).
        assertEquals("default.pyla", prefs.map["dflt::bot_config.toml::current_playstyle"])
        assertEquals("no", prefs.map["dflt::bot_config.toml::play_again_on_win"])
        // Keys without a known default are left alone rather than blanked.
        store.set("bot_config.toml", "unknown_key", "x")
        store.clearAll()
        assertEquals("x", prefs.map["bot_config.toml::unknown_key"])
    }
}

private class FakePrefs : PrefsBackend {
    val map = LinkedHashMap<String, String>()
    override fun contains(key: String): Boolean = map.containsKey(key)
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun allKeys(): Set<String> = map.keys.toSet()
}
