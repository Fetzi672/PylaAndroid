package com.pyla.ai.config

import java.io.File

/** Minimal persisted key/value storage; backed by SharedPreferences in production. */
interface PrefsBackend {
    fun contains(key: String): Boolean
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun allKeys(): Set<String>
}

/**
 * Pure persistence core behind [PylaUserSettings]: per-key overrides are written directly
 * into the target TOML file (the single authoritative copy) and mirrored as
 * SharedPreferences bookkeeping entries. The pristine file value is snapshotted under a
 * `dflt::` key the first time a key is touched — and always BEFORE any write — so
 * `remove`/`clearAll` restore the shipped default even after programmatic `set` calls.
 *
 * Storage and file resolution are injected so the full persistence contract is
 * deterministic-testable on the JVM without Android.
 */
class UserSettingsStore(
    private val prefs: PrefsBackend,
    private val resolveFile: (String) -> File,
    private val log: (String) -> Unit = {},
) {

    fun has(relativePath: String, configKey: String): Boolean =
        prefs.contains(cacheKey(relativePath, configKey))

    fun getString(relativePath: String, configKey: String, default: String? = null): String? =
        readToml(relativePath)?.opt(configKey)?.toString() ?: default

    fun set(relativePath: String, configKey: String, value: String) {
        // Snapshot the pristine default BEFORE any mutation; a later remove/clear must
        // restore the shipped value, not the programmatic override.
        snapshotDefault(relativePath, configKey)
        prefs.putString(cacheKey(relativePath, configKey), value)
        writeTomlDirect(relativePath, configKey, value)
    }

    fun remove(relativePath: String, configKey: String) {
        val defaultVal = snapshotDefault(relativePath, configKey) ?: return
        writeTomlDirect(relativePath, configKey, defaultVal)
        prefs.remove(cacheKey(relativePath, configKey))
    }

    fun clearAll() {
        for (k in prefs.allKeys()) {
            if (k.startsWith(DEFAULTS_PREFIX)) continue
            val parts = k.split("::", limit = 2)
            if (parts.size != 2) continue
            val relativePath = parts[0]
            val configKey = parts[1]
            val defaultVal = snapshotDefault(relativePath, configKey) ?: continue
            writeTomlDirect(relativePath, configKey, defaultVal)
            prefs.remove(k)
        }
    }

    fun getDefaultString(relativePath: String, configKey: String): String? =
        snapshotDefault(relativePath, configKey)

    /** Replaces the key's line in place; files without the key line are left untouched. */
    private fun writeTomlDirect(relativePath: String, key: String, value: String) {
        try {
            val file = resolveFile(relativePath)
            if (!file.exists()) {
                log("TOML file not found: ${file.absolutePath}")
                return
            }
            val content = file.readText()
            val escapedKey = Regex.escape(key)
            val regex = Regex("""(^|\n)([ \t]*${escapedKey}[ \t]*=[ \t]*)[^\n\r]*""")
            val formatted = formatTomlValue(value)
            val replaced = regex.replace(content) { m: MatchResult ->
                "${m.groupValues[1]}${m.groupValues[2]}$formatted"
            }
            file.writeText(replaced)
        } catch (e: Exception) {
            log("write failed $relativePath::$key: ${e.message}")
        }
    }

    private fun snapshotDefault(relativePath: String, configKey: String): String? {
        val defKey = DEFAULTS_PREFIX + relativePath + "::" + configKey
        val existing = prefs.getString(defKey)
        if (existing != null) return existing
        val raw = readToml(relativePath)?.opt(configKey)?.toString() ?: return null
        prefs.putString(defKey, raw)
        return raw
    }

    private fun readToml(relativePath: String): TomlLite? = try {
        val file = resolveFile(relativePath)
        if (!file.exists()) null else TomlLite.parse(file.readText())
    } catch (t: Throwable) {
        log("read failed $relativePath: ${t.message}")
        null
    }

    companion object {
        const val DEFAULTS_PREFIX = "dflt::"
        fun cacheKey(relativePath: String, configKey: String): String = "$relativePath::$configKey"
    }
}

/**
 * Formats a settings value for TOML so [TomlParser] reads back the exact string:
 * booleans/integers/floats keep their historical bare form; every other value is written
 * as a basic string. If — and only if — an escape is emitted (`\`, `"`, newline, CR, tab or
 * another control character), the line also gets the explicit `# pyla:esc` trailing comment
 * that tells the parser to decode escapes. Unmarked values keep the historical literal
 * (base-compatible) interpretation, so pre-existing configs containing backslashes are not
 * silently reinterpreted. Must stay in lockstep with [TomlParser.unescapeBasicString] —
 * both sides are pinned by `TomlValueRoundTripTest`.
 */
internal fun formatTomlValue(v: String): String {
    if (v == "true" || v == "false") return v
    v.toIntOrNull()?.let { return v }
    v.toDoubleOrNull()?.let { return v }
    val sb = StringBuilder(v.length + 2).append('"')
    var escaped = false
    for (c in v) {
        when (c) {
            '\\' -> { sb.append("\\\\"); escaped = true }
            '"' -> { sb.append("\\\""); escaped = true }
            '\n' -> { sb.append("\\n"); escaped = true }
            '\r' -> { sb.append("\\r"); escaped = true }
            '\t' -> { sb.append("\\t"); escaped = true }
            else ->
                if (c < ' ' || c == '\u007F') {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    escaped = true
                } else {
                    sb.append(c)
                }
        }
    }
    sb.append('"')
    if (escaped) sb.append(' ').append('#').append(' ').append(TomlParser.ESCAPE_MARKER)
    return sb.toString()
}
