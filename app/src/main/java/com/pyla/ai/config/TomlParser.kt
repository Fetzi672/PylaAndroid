package com.pyla.ai.config

internal object TomlParser {

    /**
     * Explicit per-value opt-in marker for the settings writer's escaped encoding.
     *
     * Historical behaviour (base a45e0d15): the writer emitted `"$value"` **without** any
     * escaping and the parser returned the quoted body **verbatim**, so a backslash in a
     * pre-existing value was literal. Resolving TOML basic-string escapes unconditionally
     * would silently reinterpret every existing value containing `\n` `\t` `\r` `\b` `\f`
     * `\\` (e.g. Windows paths like `C:\temp`), so decoding is now gated on this marker.
     *
     * A marked line carries ` # pyla:esc` as a *trailing comment* after the value. The
     * marker only triggers decoding when the comment is detected **outside** a string
     * (quote-aware), so the marker text appearing inside a value never changes parsing.
     * Unmarked lines keep the exact historical (base-compatible) interpretation.
     *
     * The encoding still round-trips through standard TOML tools: `"a\\b"` is valid TOML
     * decoding to `a\b`, and the trailing comment is ignored — no private sentinel leaks
     * into the value itself.
     */
    const val ESCAPE_MARKER = "pyla:esc"

    fun parse(text: String): Map<String, Any?> {
        val root = LinkedHashMap<String, Any?>()
        var currentTable: MutableMap<String, Any?> = root
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val (code, escaped) = splitComment(raw)
            val line = code.trim()
            if (line.isEmpty()) { i++; continue }

            if (line.startsWith('[') && line.endsWith(']')) {
                val name = line.substring(1, line.length - 1).trim()
                currentTable = navigateTable(root, name)
                i++; continue
            }

            val eq = line.indexOf('=')
            if (eq < 0) { i++; continue }
            val key = line.substring(0, eq).trim()
            var valuePart = line.substring(eq + 1).trim()

            if (valuePart.startsWith('[') && !valuePart.contains(']')) {
                val sb = StringBuilder(valuePart)
                var depth = sb.count { it == '[' } - sb.count { it == ']' }
                while (depth > 0 && i + 1 < lines.size) {
                    i++
                    sb.append('\n').append(stripComment(lines[i]))
                    depth = sb.count { it == '[' } - sb.count { it == ']' }
                }
                valuePart = sb.toString()
            }

            currentTable[key] = parseValue(valuePart, escaped)
            i++
        }
        return root
    }

    private fun navigateTable(root: MutableMap<String, Any?>, dotted: String): MutableMap<String, Any?> {
        val parts = dotted.split('.')
        var cur: MutableMap<String, Any?> = root
        for (p in parts) {
            val existing = cur[p]
            if (existing is MutableMap<*, *>) {
                @Suppress("UNCHECKED_CAST")
                cur = existing as MutableMap<String, Any?>
            } else {
                val child = LinkedHashMap<String, Any?>()
                cur[p] = child
                cur = child
            }
        }
        return cur
    }

    private fun stripComment(line: String): String = splitComment(line).first

    /**
     * Splits a physical line into (code, escapedMarkerSeen).
     *
     * A quote-aware scan locates the trailing comment so the marker is recognised even when
     * the value itself contains an escaped quote (`"a\"b" # pyla:esc`). When no marker is
     * present the code is produced by the **historical naive scan** (base a45e0d15), which
     * is bit-for-bit what pre-existing files were parsed with — so unmarked legacy lines
     * cannot be reinterpreted by the escape-aware logic.
     */
    private fun splitComment(line: String): Pair<String, Boolean> {
        val markerCode = escapeAwareSplit(line)
        if (markerCode.second) return markerCode
        return naiveStrip(line) to false
    }

    /** Historical (base-compatible) comment strip: naive quote toggle, no escape handling. */
    private fun naiveStrip(line: String): String {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') inString = !inString
            if (c == '#' && !inString) return line.substring(0, i)
            i++
        }
        return line
    }

    /** Escape-aware scan used only to detect an explicit [ESCAPE_MARKER] comment. */
    private fun escapeAwareSplit(line: String): Pair<String, Boolean> {
        val sb = StringBuilder(line.length)
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inString && c == '\\' && i + 1 < line.length) {
                sb.append(c).append(line[i + 1])
                i += 2
                continue
            }
            if (c == '"') inString = !inString
            if (c == '#' && !inString) {
                val marker = line.substring(i + 1).trim() == ESCAPE_MARKER
                return sb.toString() to marker
            }
            sb.append(c)
            i++
        }
        return sb.toString() to false
    }

    private fun parseValue(raw: String, decodeEscapes: Boolean = false): Any? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        if (v.startsWith('[')) return parseArray(v)
        if (v.startsWith('{')) return parseInlineObject(v)
        if (v.startsWith('"') && v.endsWith('"')) {
            val body = v.substring(1, v.length - 1)
            // Only values the writer explicitly marked are decoded; unmarked values keep the
            // historical verbatim (literal backslash) interpretation.
            return if (decodeEscapes) unescapeBasicString(body) else body
        }
        if (v == "true") return true
        if (v == "false") return false
        v.toIntOrNull()?.let { return it }
        v.toDoubleOrNull()?.let { return it }
        return v
    }

    /**
     * Resolves TOML basic-string escapes (`\\`, `\"`, `\n`, `\r`, `\t`, `\b`, `\f`, `\uXXXX`)
     * inside a quoted value the writer marked with [ESCAPE_MARKER]. Unknown escapes are kept
     * verbatim (backslash + character).
     */
    private fun unescapeBasicString(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) {
                sb.append(c)
                i++
                continue
            }
            when (val e = s[i + 1]) {
                '\\' -> { sb.append('\\'); i += 2 }
                '"' -> { sb.append('"'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'u' -> {
                    val hex = s.substring(i + 2, minOf(i + 6, s.length))
                    val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (code != null) { sb.append(code.toChar()); i += 6 }
                    else { sb.append('\\').append(e); i += 2 }
                }
                else -> { sb.append('\\').append(e); i += 2 }
            }
        }
        return sb.toString()
    }

    private fun parseArray(raw: String): List<Any?> {
        val inner = raw.substring(1, raw.length - 1)
        val parts = splitTopLevel(inner, ',')
        return parts.map { parseValue(it) }.filter { it != null }
    }

    private fun parseInlineObject(raw: String): Map<String, Any?> {
        val inner = raw.substring(1, raw.length - 1)
        val out = LinkedHashMap<String, Any?>()
        for (part in splitTopLevel(inner, ',')) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            out[part.substring(0, eq).trim().trim('"')] = parseValue(part.substring(eq + 1).trim())
        }
        return out
    }

    private fun splitTopLevel(s: String, sep: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        var inString = false
        var lastWasToken = false
        for (c in s) {
            if (c == '"') inString = !inString
            if (!inString) {
                if (c == '[' || c == '{') depth++
                if (c == ']' || c == '}') depth--
                if (c == sep && depth == 0) {
                    val t = sb.toString().trim()
                    if (t.isNotEmpty()) out.add(t)
                    sb.setLength(0); lastWasToken = true; continue
                }
            }
            sb.append(c); lastWasToken = false
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) out.add(tail)
        return out
    }
}
