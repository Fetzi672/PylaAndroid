package com.pyla.ai.config

import android.content.Context
import com.pyla.ai.PylaApp

/**
 * Android-facing facade over [UserSettingsStore]. Overrides are persisted into the TOML
 * files themselves (the single authoritative copy) plus SharedPreferences bookkeeping
 * entries; the pristine TOML value is snapshotted before any write so `remove`/`clearAll`
 * restore the shipped default even after programmatic `set` calls.
 */
object PylaUserSettings {
    private const val PREFS_NAME = "pyla_overrides"

    private val store: UserSettingsStore by lazy {
        UserSettingsStore(
            prefs = object : PrefsBackend {
                private fun prefs(): android.content.SharedPreferences =
                    PylaApp.ctx().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                override fun contains(key: String): Boolean = prefs().contains(key)
                override fun getString(key: String): String? = prefs().getString(key, null)
                override fun putString(key: String, value: String) {
                    prefs().edit().putString(key, value).apply()
                }
                override fun remove(key: String) {
                    prefs().edit().remove(key).apply()
                }
                override fun allKeys(): Set<String> = prefs().all.keys.toSet()
            },
            resolveFile = { relativePath -> PylaConfig.resolve(relativePath) },
            log = { message -> android.util.Log.e("PylaSettings", message) },
        )
    }

    fun has(relativePath: String, configKey: String): Boolean = store.has(relativePath, configKey)

    fun getString(relativePath: String, configKey: String, default: String? = null): String? =
        store.getString(relativePath, configKey, default)

    fun set(relativePath: String, configKey: String, value: String) {
        store.set(relativePath, configKey, value)
        PylaConfig.invalidate(relativePath)
    }

    fun remove(relativePath: String, configKey: String) {
        store.remove(relativePath, configKey)
        PylaConfig.invalidate(relativePath)
    }

    fun clearAll() {
        store.clearAll()
        PylaConfig.invalidateAll()
    }

    fun getDefaultString(relativePath: String, configKey: String): String? =
        store.getDefaultString(relativePath, configKey)
}
