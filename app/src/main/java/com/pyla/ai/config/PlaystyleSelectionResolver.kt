package com.pyla.ai.config

import java.io.File

/** Injected world for playstyle selection; SharedPreferences + TOML in production. */
interface PlaystyleSelectionSource {
    val defaultPlaystyle: String
    fun readLegacyPreference(): String?
    fun readTomlSelection(): String?
    fun writeTomlSelection(value: String)
    fun writeLegacyPreference(value: String)
    fun playstyleFileExists(filename: String): Boolean
}

/**
 * Resolves the single effective playstyle across the two historical stores: the legacy
 * `pyla`/`playstyle` SharedPreferences value written by older UI builds, and
 * `current_playstyle` in `cfg/bot_config.toml` read by the engine. Precedence:
 * non-blank legacy preference, then non-blank TOML value, then the bundled default; a
 * selection whose playstyle file no longer exists falls back to the bundled default.
 *
 * When the two stores disagree, the winner is reconciled into TOML (through the
 * [UserSettingsStore] write path that snapshots the pristine default first) and mirrored
 * into the legacy preference, so no contradictory authoritative copy survives. TOML-only
 * installs (no legacy preference) are never rewritten on read.
 */
class PlaystyleSelectionResolver(private val source: PlaystyleSelectionSource) {

    /** The selection both the UI and the engine must display/load. Read-only apart from
     *  the bounded one-time reconciliation of disagreeing legacy/TOML stores. */
    fun effectivePlaystyle(): String {
        val legacy = source.readLegacyPreference().nonBlank()
        val toml = source.readTomlSelection().nonBlank()
        val preferred = legacy ?: toml ?: source.defaultPlaystyle
        val effective = if (source.playstyleFileExists(preferred)) preferred else source.defaultPlaystyle
        if (legacy != null && legacy != toml) {
            source.writeTomlSelection(effective)
            if (legacy != effective) source.writeLegacyPreference(effective)
        }
        return effective
    }

    /** Persists [filename] as the effective selection in both stores; returns the
     *  persisted value (blank falls back to the bundled default). */
    fun select(filename: String): String {
        val chosen = filename.nonBlank() ?: source.defaultPlaystyle
        source.writeTomlSelection(chosen)
        source.writeLegacyPreference(chosen)
        return chosen
    }

    /** After [filename] was deleted: re-selects the bundled default when [filename] was the
     *  persisted selection, otherwise keeps the current one; returns the new effective.
     *
     *  The decision is made on the *persisted identity* (legacy preference, else TOML), not on
     *  [effectivePlaystyle]: once the file is gone, [effectivePlaystyle] has already fallen back
     *  to the bundled default, so comparing against it would leave the now-dead selection
     *  (`filename`) persisted in both stores forever. */
    fun onPlaystyleDeleted(filename: String): String {
        val selected = source.readLegacyPreference().nonBlank()
            ?: source.readTomlSelection().nonBlank()
        return if (selected == filename) select(source.defaultPlaystyle) else effectivePlaystyle()
    }

    private fun String?.nonBlank(): String? = this?.takeIf { it.isNotBlank() }
}

/**
 * True when [filename] addresses an existing direct child file of [dir]. Canonical-path
 * containment keeps traversal shapes (`../x.pyla`) from widening the trust boundary to
 * the playstyles directory.
 */
fun isContainedPlaystyleFile(dir: File, filename: String): Boolean {
    if (filename.isBlank()) return false
    return try {
        val canonicalDir = dir.canonicalFile
        val canonicalFile = File(canonicalDir, filename).canonicalFile
        canonicalFile.parentFile == canonicalDir && canonicalFile.isFile
    } catch (_: Exception) {
        false
    }
}
