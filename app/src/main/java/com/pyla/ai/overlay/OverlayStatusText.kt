package com.pyla.ai.overlay

/**
 * Pure helpers for the single-line status strip: text composition, width fitting and state colour.
 * Kept free of Android imports so it is fully unit-testable on the JVM.
 */
object OverlayStatusText {
    const val PREFIX = "PYLA"
    const val ELLIPSIS = "…"
    const val NO_ACTION = "No active action || DISCLAIMER: USE OVERLAY WITH CAUTION! IT MAY NOT LET THE BOT SEE THINGS ON THE SCREEN!"

    
    /** Engine state label used both in the strip and in the bubble content description. */
    fun engineState(running: Boolean, paused: Boolean): String = when {
        running && paused -> "Paused"
        running -> "Running"
        else -> "Stopped"
    }

    /**
     * Compose the strip: `PYLA <Engine> | <State line> | <Check line> | Last: <action>`.
     * [stateLine] and [checkLine] come from RuntimeVerification (already labelled); [last] is the
     * raw engine last-action and falls back to a neutral placeholder.
     */
    fun compose(engineLabel: String, stateLine: String, checkLine: String, last: String): String {
        return parts(engineLabel, stateLine, checkLine, last).joinToString(" | ")
    }

    fun parts(engineLabel: String, stateLine: String, checkLine: String, last: String): List<String> {
        fun single(value: String) = value.replace(Regex("\\s+"), " ").trim()
        return listOf("$PREFIX ${single(engineLabel)}", single(stateLine).ifBlank { "State: Unknown" },
            single(checkLine).ifBlank { "Check: Stopped" }, "Last: ${single(last).ifBlank { NO_ACTION }}")
    }

    /** Keep all four fields visible; truncate values rather than dropping Last entirely. */
    fun fitFields(fields: List<String>, width: Float, measure: (String) -> Float): String {
        val separator = " | "
        val full = fields.joinToString(separator)
        if (measure(full) <= width) return full
        if (fields.size != 4) return fit(full, width, measure)
        val minimum = listOf(fields[0], "State: …", "Check: …", "Last: …")
        val budgets = minimum.map(measure).toMutableList()
        var extra = width - budgets.sum() - measure(separator) * 3
        if (extra < 0f) return fit(full, width, measure)
        val pending = (1..3).filter { measure(fields[it]) > budgets[it] }.toMutableSet()
        while (pending.isNotEmpty() && extra > 0.1f) {
            val share = extra / pending.size
            for (index in pending.toList()) {
                val amount = minOf(share, (measure(fields[index]) - budgets[index]).coerceAtLeast(0f))
                budgets[index] += amount; extra -= amount
                if (measure(fields[index]) <= budgets[index] + 0.1f) pending.remove(index)
            }
        }
        return fields.mapIndexed { index, value -> fit(value, budgets[index], measure) }.joinToString(separator)
    }

    /** State colour as ARGB int: paused violet, running green, stopped grey. */
    fun stateColor(running: Boolean, paused: Boolean): Int = when {
        running && paused -> 0xFF9C8CFF.toInt()
        running -> 0xFF4CD964.toInt()
        else -> 0xFF7A8699.toInt()
    }

    /**
     * Truncate [text] with an ellipsis so that its measured width does not exceed [maxWidthPx].
     * [measure] returns the width of a candidate string (Paint.measureText in production, a stub in
     * tests). No wrapping: the result is always a single line.
     */
    fun fit(text: String, maxWidthPx: Float, measure: (String) -> Float, ellipsis: String = ELLIPSIS): String {
        if (maxWidthPx <= 0f) return ""
        if (measure(text) <= maxWidthPx) return text
        val ellipsisWidth = measure(ellipsis)
        if (ellipsisWidth > maxWidthPx) return ""
        var end = text.length
        while (end > 0 && measure(text.substring(0, end) + ellipsis) > maxWidthPx) end--
        return text.substring(0, end) + ellipsis
    }
}
