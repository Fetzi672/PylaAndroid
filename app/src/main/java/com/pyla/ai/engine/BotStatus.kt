package com.pyla.ai.engine

import android.util.Log

object BotStatus {
    @Volatile var engineRunning: Boolean = false
    @Volatile var enginePaused: Boolean = false
    @Volatile var captureSize: String = ""
    @Volatile var currentState: String = ""
    @Volatile var lastAction: String = ""
    @Volatile var inputConnected: Boolean = false
    @Volatile var frameCount: Int = 0
    @Volatile var lastError: String = ""
    @Volatile var queueSummary: String = ""
    @Volatile var verification: RuntimeVerification = RuntimeVerification()
    @Volatile var selectionActive: Boolean = false
    @Volatile private var gameplayStatusGuard: (() -> Boolean)? = null

    fun bindGameplayStatusGuard(guard: () -> Boolean) { gameplayStatusGuard = guard }

    @Synchronized
    fun publish(state: RuntimeState, phase: VerificationPhase, verified: Boolean) {
        if (state == RuntimeState.MATCH && verified && gameplayStatusGuard?.invoke() == false) return
        val next = RuntimeVerification(state, phase, verified)
        if (next != verification) {
            verification = next
            com.pyla.ai.diagnostics.DiagnosticLog.event(
                "state.verification", "state=${state.name} phase=${phase.name} verified=$verified",
            )
        }
    }

    @Synchronized
    fun classifierObservation(state: String, verified: Boolean) {
        if (selectionActive) return // The selector owns stronger OCR state evidence during its bounded phase.
        val mapped = RuntimeState.fromClassifier(state)
        val confirmed = verified && mapped != RuntimeState.UNKNOWN
        publish(if (confirmed) mapped else RuntimeState.UNKNOWN,
            if (confirmed) VerificationPhase.VERIFIED else VerificationPhase.VERIFY_STATE, confirmed)
    }

    @Synchronized
    fun resetVerification() {
        selectionActive = false
        verification = RuntimeVerification()
    }

    fun action(msg: String) {
        lastAction = msg
        PylaLog.p("PylaStatus", msg)
    }

    fun error(msg: String) {
        lastError = msg
        PylaLog.e("PylaStatus", msg)
    }
}
