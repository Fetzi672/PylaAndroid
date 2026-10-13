package com.pyla.ai.engine

import java.util.Locale

/**
 * Pure, stateless recognition of the brawler-grid page heading as a single OCR string.
 *
 * The heading is either the bare label or the same label carrying the owned/total counter that the
 * German UI renders, e.g. `BRAWLER (107/107)` or `BRAWLER 107/107`. The classifier otherwise reads
 * the generic back arrow and reports the shop, so this helper exists to admit the visible heading
 * without weakening any other screen evidence.
 *
 * Deliberately narrow - it performs no I/O, holds no state, logs nothing, and persists nothing:
 * OCR text is caller-supplied and must never be written anywhere by recognition.
 *
 * There is no partial match, no substring search, no fuzzy/edit-distance fallback, and no knowledge
 * of any brawler card identity: only this fixed label vocabulary is ever accepted. Counting or
 * uniqueness of headings inside a frame is the caller's responsibility, so two identical heading
 * strings both return `true` here; the caller must reject distinct duplicate headings as ambiguous.
 */
object BrawlerGridHeader {

    /**
     * Longest raw input inspected. The longest accepted form is well below this (label + bounded
     * spacing + two 4-digit counts + optional parentheses), so oversized OCR noise is rejected
     * before any pattern is evaluated.
     */
    private const val MAX_INPUT_LENGTH = 64

    /** Maximum spacing allowed around the parenthesis and the slash. */
    private const val MAX_SPACING = 4

    /** Accepted bare heading labels, compared case-folded with [Locale.ROOT]. */
    private val BARE_LABELS = setOf("brawlers", "brawler", "lottatori")

    /**
     * Longest single raw element inspected by [permitsLabelElements] as count decoration.
     * A real counter element is far shorter; this bound rejects noise before it is patterned.
     */
    private const val MAX_DECORATION_LENGTH = 32

    /**
     * The only decoration a heading line may carry besides its own label: ASCII digits, the
     * counter's own parentheses, its own slash and spacing. Deliberately ASCII-only, so a
     * Unicode look-alike digit or any other word is rejected instead of accepted as a counter.
     * A blank (whitespace-only or empty) element carries no word at all, so it is accepted as
     * spacing like any other spacing instead of invalidating the line.
     */
    private val DECORATION = Regex("^[0-9 ()/]+$")

    /**
     * Counted heading shape, matched against the already trimmed, case-folded string.
     *
     * `[0-9]` and `[ \t]` are deliberately ASCII/space-only and every quantifier is bounded, so a
     * Unicode look-alike digit or an exotic separator cannot be accepted, and no input can drive
     * catastrophic backtracking. The parenthesis pair is balanced separately by [isHeader].
     */
    private val COUNTED = Regex(
        "^(brawlers|brawler|lottatori)[ \\t]{0,$MAX_SPACING}(?:\\([ \\t]{0,$MAX_SPACING})?" +
            "([0-9]{1,4})[ \\t]{0,$MAX_SPACING}/[ \\t]{0,$MAX_SPACING}([0-9]{1,4})" +
            "[ \\t]{0,$MAX_SPACING}(?:\\))?[ \\t]{0,$MAX_SPACING}$"
    )

    /**
     * Returns `true` only when [text] is exactly one grid heading, bare or counted.
     *
     * Rejects prefixes and suffixes, descriptive sentences, unbalanced parentheses, negative,
     * reversed, overflowing or zero-total counters, concatenated digits, and any string longer than
     * [MAX_INPUT_LENGTH].
     */
    fun isHeader(text: String): Boolean {
        if (text.length > MAX_INPUT_LENGTH) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_INPUT_LENGTH) return false

        val folded = trimmed.lowercase(Locale.ROOT)
        if (BARE_LABELS.contains(folded)) return true

        val match = COUNTED.matchEntire(folded) ?: return false
        // The counted form requires balanced parentheses; a half-open or half-closed pair is noise.
        if ((trimmed.indexOf('(') >= 0) != (trimmed.indexOf(')') >= 0)) return false

        // Without parentheses, the counter must be separated from the label by whitespace.
        if ('(' !in folded && folded.getOrNull(match.groupValues[1].length) !in listOf(' ', '\t')) return false
        val current = match.groupValues[2].toIntOrNull() ?: return false
        val total = match.groupValues[3].toIntOrNull() ?: return false
        if (total <= 0 || current < 0) return false
        return current <= total
    }

    /**
     * Returns `true` only when [raw] is EXACTLY one heading word.
     *
     * The raw element is trimmed and case-folded with [Locale.ROOT] and then compared against the
     * fixed label vocabulary; there is no [BrawlerSelectionGate.normalize], no substring search,
     * no fuzzy fallback and no reconstruction. `"Brawler"`, `"brawlers"` and `"lottatori"` are the
     * only accepted values; `"Brawler (107/107)"`, `"Brawlers!"`, `"der Brawler"` and card names
     * are not. The count carries no authority here - it is display-only decoration.
     *
     * Recognition is per element, so two identical label elements both return `true`: the caller
     * keeps both so its own uniqueness rule, not this helper, rejects an ambiguous heading.
     */
    fun isLabel(raw: String): Boolean {
        if (raw.length > MAX_INPUT_LENGTH) return false
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_INPUT_LENGTH) return false
        return trimmed.lowercase(Locale.ROOT) in BARE_LABELS
    }

    /**
     * Returns `true` only when a recognised heading line consists of heading words and bounded
     * count decoration, and of nothing else, AND carries at least one heading word.
     *
     * Every element must be either an exact heading word ([isLabel]) or a short ASCII
     * digit/parenthesis/slash/spacing decoration element. Any further word - a description, a
     * card name, a stray prefix or suffix - rejects the whole line, so no element of it can be
     * read as the heading. A blank element carries no word and is treated as spacing. An empty
     * element list, and a list with decoration but no heading word at all, are both rejected.
     */
    fun permitsLabelElements(elements: List<String>): Boolean {
        if (elements.isEmpty() || elements.size > 16) return false
        var sawLabel = false
        for (raw in elements) {
            if (isLabel(raw)) {
                sawLabel = true
                continue
            }
            if (raw.length > MAX_DECORATION_LENGTH) return false
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) continue
            if (!DECORATION.matches(trimmed)) return false
        }
        return sawLabel
    }
}