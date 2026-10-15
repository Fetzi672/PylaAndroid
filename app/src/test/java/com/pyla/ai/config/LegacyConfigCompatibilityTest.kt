package com.pyla.ai.config

import com.pyla.ai.compat.BaseTomlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Retained-config backslash compatibility for the settings/parser change.
 *
 * Historical contract (base a45e0d15): the writer emitted `"$value"` with no escaping and the
 * parser returned the quoted body verbatim, so a backslash in a pre-existing value was
 * literal. The candidate parses TOML escapes, which must NOT silently reinterpret existing
 * files — decoding is now opt-in per value via the writer's `# pyla:esc` trailing comment.
 *
 * The invariant is pinned two ways:
 *  1. a differential comparison against the **unmodified base parser** (compiled from
 *     `git show a45e0d15:.../TomlParser.kt` as [BaseTomlParser]) over a corpus of legacy
 *     snippets — unmarked input must parse identically; and
 *  2. end-to-end `UserSettingsStore` set/remove cycles proving new escaped values round-trip
 *     while legacy defaults keep their literal meaning on reset.
 */
class LegacyConfigCompatibilityTest {

    private companion object {
        /** Wire format of the writer's opt-in escape marker. */
        const val MARKER = "pyla:esc"
    }

    @get:Rule
    val tmp = TemporaryFolder()

    // --- differential invariant: unmarked legacy input is bit-for-bit base-compatible ---

    private val legacyCorpus = listOf(
        "",                                                                 // empty
        "k = \"plain\"\n",
        "k = 42\n",                                                         // int
        "k = 1.5\n",                                                        // float
        "k = true\nk2 = false\n",                                           // bool
        "current_playstyle = \"universal_smart_v5.pyla\"\n",
        "k = \"C:\\backup\\file\"\n",                                       // Windows path
        "k = \"C:\\temp\"\n",                                               // \t would decode if unmarked
        "k = \"C:\\new\"\n",                                                // \n
        "k = \"C:\\random\"\n",                                             // \r
        "k = \"C:\\bread\\fnord\"\n",                                       // \b, \f
        "k = \"\\\\server\\share\"\n",                                      // UNC
        "k = \"a\\qb\\zc\"\n",                                              // unknown escapes
        "k = \"a\\\"b\"\n",                                                 // escaped-quote-looking legacy value
        "k = \"C:\\\"\n",                                                   // value ending in backslash
        "k = \"C:\\\" # trailing comment\n",                                // + comment (divergence-sensitive)
        "k = \"a\\\"b\" # c\n",                                             // legacy, backslash before quote + comment
        "k = \"v\" # plain comment\n",
        "k = \"a#b\" # c\n",                                                // '#' inside string then comment
        "k = [\"C:\\a\", \"C:\\b\", 3]\n",                                  // array with backslash strings
        "k = [\n  \"C:\\x\",\n  \"C:\\y\",\n]\n",                            // multi-line array
        "k = { a = \"C:\\x\", b = 2 }\n",                                   // inline object
        "k = \"\"\n",                                                       // empty string
        "[section]\nkey = \"C:\\path\"\nnum = 7\n",                          // table + values
        "[a.b]\nk = \"x\\ty\"\n",                                           // dotted table
        "# a comment line\nk = 1\n\n",                                      // comment-only line
        "k = \"line\\nnext\"\n",                                            // literal \n text
    )

    @Test
    fun unmarkedLegacyInputParsesIdenticallyToBaseParser() {
        for (snippet in legacyCorpus) {
            assertEquals(
                "divergence for snippet: ${snippet.replace("\n", "\\n")}",
                BaseTomlParser.parse(snippet),
                TomlParser.parse(snippet),
            )
        }
    }

    @Test
    fun legacyWindowsPathStaysLiteralAndUnchanged() {
        val value = TomlParser.parse("k = \"C:\\backup\\file\"\n")["k"]
        assertEquals("C:\\backup\\file", value)
    }

    @Test
    fun legacyArrayAndTableValuesUnchanged() {
        val parsed = TomlParser.parse(
            "[paths]\nall = [\"C:\\a\", \"C:\\b\"]\none = \"C:\\c\"\n"
        )
        @Suppress("UNCHECKED_CAST")
        val paths = parsed["paths"] as Map<String, Any?>
        assertEquals(listOf("C:\\a", "C:\\b"), paths["all"])
        assertEquals("C:\\c", paths["one"])
    }

    @Test
    fun unmarkedQuotedValueWithTrailingCommentStaysLiteral() {
        assertEquals("a\\nb", TomlParser.parse("k = \"a\\nb\" # note\n")["k"])
    }

    // --- end-to-end store: preset legacy values preserved, new values marked & round-trip ---

    private class FakePrefs : PrefsBackend {
        val map = LinkedHashMap<String, String>()
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun allKeys(): Set<String> = map.keys.toSet()
    }

    @Test
    fun legacyBackslashDefaultIsSnapshottedLiterallyAndRestoredOnRemove() {
        val prefs = FakePrefs()
        val file = tmp.newFile("bot_config.toml")
        // A pre-existing (unmarked) config: the decoded meaning must be the literal string.
        file.writeText("path = \"C:\\backup\\file\"\n")
        val store = UserSettingsStore(prefs, { File(tmp.root, it) })

        assertEquals("C:\\backup\\file", store.getDefaultString("bot_config.toml", "path"))

        store.set("bot_config.toml", "path", "new\\value")
        assertEquals("new\\value", store.getString("bot_config.toml", "path", null))
        assertTrue(file.readText().contains("# ${MARKER}"))

        store.remove("bot_config.toml", "path")
        // Reset restores the original decoded default (not the raw marker form).
        assertEquals("C:\\backup\\file", store.getString("bot_config.toml", "path", null))
    }

    @Test
    fun newEscapedValueWrittenWithMarkerAndReadBackIdentical() {
        val prefs = FakePrefs()
        val file = tmp.newFile("bot_config.toml")
        file.writeText("current_playstyle = \"default.pyla\"\n")
        val store = UserSettingsStore(prefs, { File(tmp.root, it) })

        val nasty = "C:\\tmp\\new \"q\" #x\u0001\ttab\nnl"
        store.set("bot_config.toml", "current_playstyle", nasty)

        // Encoded form is still standard TOML (backslashes escaped) plus the opt-in comment.
        assertTrue(file.readText().contains("# ${MARKER}"))
        assertEquals(1, file.readText().lines().count { it.contains("current_playstyle =") })
        // Parsed back through the real parser the value is identical.
        assertEquals(nasty, store.getString("bot_config.toml", "current_playstyle", null))
        assertEquals(nasty, TomlParser.parse(file.readText())["current_playstyle"])
    }

    @Test
    fun mixedOldAndNewLinesInOneFileBothReadCorrectly() {
        val prefs = FakePrefs()
        val file = tmp.newFile("bot_config.toml")
        file.writeText(
            "legacy_path = \"C:\\backup\\file\"\n" +   // pre-existing literal value, no marker
            "new_value = \"default\"\n"
        )
        val store = UserSettingsStore(prefs, { File(tmp.root, it) })

        store.set("bot_config.toml", "new_value", "a\n#b")

        val parsed = TomlParser.parse(file.readText())
        assertEquals("C:\\backup\\file", parsed["legacy_path"])   // untouched
        assertEquals("a\n#b", parsed["new_value"])                // real newline from the marker
        assertEquals("a\n#b", store.getString("bot_config.toml", "new_value", null))
    }

    @Test
    fun markerLiteralInsideNewValueIsNotMisdecoded() {
        val prefs = FakePrefs()
        val file = tmp.newFile("bot_config.toml")
        file.writeText("k = \"x\"\n")
        val store = UserSettingsStore(prefs, { File(tmp.root, it) })

        // Contains the marker text but no escape-triggering characters -> written unmarked.
        val v = "plain # ${MARKER}"
        store.set("bot_config.toml", "k", v)
        assertFalse(file.readText().contains("# ${MARKER}\n"))
        assertEquals(v, store.getString("bot_config.toml", "k", null))
    }

    @Test
    fun missingKeyLineNeverRewrittenByCompatChange() {
        val prefs = FakePrefs()
        val file = tmp.newFile("bot_config.toml")
        val original = "other = \"C:\\keep\"\n"
        file.writeText(original)
        val store = UserSettingsStore(prefs, { File(tmp.root, it) })

        store.set("bot_config.toml", "absent_key", "v")
        assertEquals(original, file.readText())
        assertNull(store.getString("bot_config.toml", "absent_key", null))
    }
}
