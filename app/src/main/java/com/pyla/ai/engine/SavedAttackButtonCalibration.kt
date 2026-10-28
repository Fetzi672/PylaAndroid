package com.pyla.ai.engine

import android.content.Context
import com.pyla.ai.PylaApp

/**
 * Android facade over [SavedAttackButtonCalibrationCore].
 *
 * The learned attack-button location lives in a dedicated, narrowly named preferences file so
 * unrelated user settings are never touched. Reads and writes are serialized by one lock, and
 * writes use `apply()` plus a coalesced background commit acknowledgment. The IO worker flushes
 * the current map without replaying old values. Neither Play/STOP nor input callbacks wait for IO;
 * `persistenceSettled()` distinguishes accepted in-memory changes from confirmed durable storage.
 */
object SavedAttackButtonCalibration {
    private const val PREFS_NAME = "pyla_attack_calibration"
    private const val KEY_RECORD = "saved_attack_button_v1"

    private val lock = Any()
    private fun prefs() = PylaApp.ctx().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val persistence by lazy {
        AttackCalibrationPersistenceQueue(
            executor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
                Thread(task, "AttackCalibrationIO").apply { isDaemon = true }
            },
            // Empty edit: await durability of the current preference map without resurrecting
            // the value of an old save after a newer clear. Never runs under Play/core locks.
            flush = { prefs().edit().commit() },
            onResult = { ok ->
                com.pyla.ai.diagnostics.DiagnosticLog.event("attack.calibration.persistence",
                    "status=${if (ok) "stored" else "failed"}")
            },
        )
    }

    fun persistenceSettled(): Boolean = persistence.settled()

    private val storage: AttackCalibrationStorage = object : AttackCalibrationStorage {

        override fun read(): String? = try {
            prefs().getString(KEY_RECORD, null)
        } catch (_: ClassCastException) {
            // A foreign preference type is corrupt calibration, not an app-crashing read.
            prefs().edit().remove(KEY_RECORD).apply()
            persistence.request()
            null
        }

        override fun write(value: String) {
            prefs().edit().putString(KEY_RECORD, value).apply()
            persistence.request()
        }

        override fun remove(): Boolean {
            val prefs = prefs()
            val existed = prefs.contains(KEY_RECORD)
            prefs.edit().remove(KEY_RECORD).apply()
            persistence.request()
            return existed
        }
    }

    private val core by lazy {
        SavedAttackButtonCalibrationCore(storage, SavedAttackButtonCalibrationCore.DEFAULT_MAX_AGE_MS)
    }

    fun begin(width: Int, height: Int, geometry: Long): AttackCalibrationLease =
        synchronized(lock) { core.begin(width, height, geometry) }

    fun saved(lease: AttackCalibrationLease): AttackButtonPoint? =
        synchronized(lock) { core.saved(lease) }

    fun observeTemplate(
        lease: AttackCalibrationLease,
        point: AttackButtonPoint?,
        capturedAtMs: Long,
        nowMs: Long,
    ): AttackButtonPoint? = synchronized(lock) { core.observeTemplate(lease, point, capturedAtMs, nowMs) }

    fun current(lease: AttackCalibrationLease): Boolean = synchronized(lock) { core.current(lease) }

    fun revision(): Long = synchronized(lock) { core.revision() }

    fun resetObservations() = synchronized(lock) { core.resetObservations() }

    fun clear(): Boolean = synchronized(lock) { core.clear() }

    fun hasSaved(): Boolean = synchronized(lock) { core.hasSaved() }
}
