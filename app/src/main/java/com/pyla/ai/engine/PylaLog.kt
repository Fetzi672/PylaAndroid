package com.pyla.ai.engine

import android.util.Log
import com.pyla.ai.diagnostics.DiagnosticLog

/** Existing facade plus a private bounded RAM sink. No raw Throwable mirror. */
object PylaLog {
    private const val ROOT_TAG = "Pyla"

    fun p(tag: String, msg: String) { i(tag, msg) }
    fun d(tag: String, msg: String, t: Throwable? = null): Int = output("DEBUG", tag, msg, t)
    fun i(tag: String, msg: String, t: Throwable? = null): Int = output("INFO", tag, msg, t)
    fun w(tag: String, msg: String, t: Throwable? = null): Int = output("WARN", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null): Int = output("ERROR", tag, msg, t)

    private fun output(level: String, tag: String, msg: String, throwable: Throwable?): Int {
        // A failing platform Logcat must never prevent RAM recording or affect gameplay.
        try { DiagnosticLog.record(level, tag, msg, throwable) } catch (_: Throwable) { }
        return try {
            val safeTag = DiagnosticLog.safeText(tag, 48)
            val safeMsg = if (msg.startsWith("Runtime profile:")) "Runtime profile selected" else DiagnosticLog.safeText(msg)
            val error = if (throwable == null) "" else " exception=" + DiagnosticLog.safeText(throwable.javaClass.simpleName, 48)
            val body = safeMsg + error
            when (level) {
                "ERROR" -> { Log.e(ROOT_TAG, "[$safeTag] $body"); Log.e(safeTag, body) }
                "WARN" -> { Log.w(ROOT_TAG, "[$safeTag] $body"); Log.w(safeTag, body) }
                "DEBUG" -> { Log.d(ROOT_TAG, "[$safeTag] $body"); Log.d(safeTag, body) }
                else -> { Log.i(ROOT_TAG, "[$safeTag] $body"); Log.i(safeTag, body) }
            }
        } catch (_: Throwable) { 0 }
    }
}
