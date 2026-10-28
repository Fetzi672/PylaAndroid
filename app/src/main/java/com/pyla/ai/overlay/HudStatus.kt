package com.pyla.ai.overlay

/** Restrained HUD palette: purple brand, mint/blue/amber/red state, neutral metrics. */
enum class HudTone(val argb: Int) {
    GOOD(0xFF5BE49B.toInt()),
    WARN(0xFFFFC24B.toInt()),
    INFO(0xFF7FC4FF.toInt()),
    BAD(0xFFFF6B81.toInt()),
    BRAND(0xFFB79CFF.toInt()),
    MUTED(0xFF8A93A6.toInt()),
}

/** Surfaces of the pill and bubble. Values are ARGB ints so the palette stays Android-free. */
object HudTheme {
    val pillFill = 0xE6161C26.toInt()
    val pillBorder = 0x33FFFFFF
    val divider = 0x26FFFFFF
    val primaryText = 0xFFF2F5FA.toInt()
    val labelText = HudTone.MUTED.argb
    val bubbleFill = 0xF2151A22.toInt()
    val bubbleRing = 0x40FFFFFF
}

/**
 * Resolved, truthful HUD status. The vocabulary is fixed and short so it cannot be pushed out of
 * the pill by OCR text, and status is never inferred from metrics.
 */
data class HudStatus(val label: String, val tone: HudTone) {
    val isVerifiedMatch: Boolean get() = label == MATCH
    companion object {
        const val MATCH = "MATCH"
    }
}

object HudStatusResolver {
    const val STOPPED = "STOPPED"
    const val PAUSED = "PAUSED"
    const val ERROR = "ERROR"
    const val VERIFYING = "VERIFYING"
    const val STATUS_MAX_LENGTH = 9

    /**
     * Status precedence: user-visible stop > user pause > engine error > live verified state.
     * [stateLabel] is the already-vetted [com.pyla.ai.engine.RuntimeState] label; it is only shown
     * when the engine itself reports verified evidence, otherwise the bar says VERIFYING.
     */
    fun resolve(
        running: Boolean,
        paused: Boolean,
        verified: Boolean,
        stateLabel: String,
        hasError: Boolean,
    ): HudStatus {
        if (!running) return HudStatus(STOPPED, HudTone.MUTED)
        if (paused) return HudStatus(PAUSED, HudTone.INFO)
        if (hasError) return HudStatus(ERROR, HudTone.BAD)
        if (!verified) return HudStatus(VERIFYING, HudTone.WARN)
        val label = shortState(stateLabel)
        return when (label) {
            HudStatus.MATCH -> HudStatus(HudStatus.MATCH, HudTone.GOOD)
            else -> HudStatus(label, HudTone.GOOD)
        }
    }

    /** Upper-case, single-line, length-bounded state label; unknown evidence stays VERIFYING. */
    fun shortState(stateLabel: String): String {
        val normalized = stateLabel.replace(Regex("[\\p{Cntrl}\\s]+"), " ").trim().uppercase(java.util.Locale.US)
        if (normalized.isBlank()) return VERIFYING
        return if (normalized.length <= STATUS_MAX_LENGTH) normalized
        else normalized.substring(0, STATUS_MAX_LENGTH - 1) + "…"
    }

    /** Spoken form for the accessibility node, always spelling out the numbers. */
    fun description(status: HudStatus, loop: String, ips: String): String =
        "Pyla HUD, ${status.label.lowercase(java.util.Locale.US)}, loop $loop, $ips IPS"
}
