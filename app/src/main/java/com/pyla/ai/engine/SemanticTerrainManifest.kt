package com.pyla.ai.engine

/**
 * Strict, dependency-free reader for the semantic tile detector manifest that ships next to
 * `models/tileDetector.onnx` (upstream `detect.py` reads `model_path` with `.json` swapped in).
 *
 * Upstream contract (pinned PylaAI f572176e1b0370fc4530607034af80fb57cb1639):
 *  - `class_ids`: object mapping contiguous decimal indices "0".."N-1" to class names
 *  - `width`, `height`: ints; the model input must be exactly `[1, 3, height, width]`
 *  - outputs `class_ids` (uint8 argmax) and `confidence` (float32 probability of that class)
 *
 * The manifest is the *only* authority for class names and input geometry. Nothing here guesses:
 * a malformed manifest throws [IllegalArgumentException] instead of falling back to a legacy box
 * decoder, so a stale or truncated manifest can never be interpreted as some other model.
 *
 * Parsing uses a tiny strict JSON reader so the class stays usable in plain JVM unit tests (the
 * Android `org.json` stub is not available there and the project carries no JSON library).
 */
data class SemanticTerrainManifest(
    val width: Int,
    val height: Int,
    /** Manifest index -> raw class name, exactly as declared. */
    val classNames: List<String>,
) {
    /** Canonical terrain name for a manifest class id, or null for classes the decoder ignores. */
    fun canonicalName(classId: Int): String? = classNames.getOrNull(classId)?.let(TerrainTileClasses::canonical)

    /** Output class ids are positions in the fixed upstream order, not manifest indices. */
    fun outputClassId(classId: Int): Int {
        val canonical = canonicalName(classId) ?: return -1
        return TerrainTileClasses.ORDER.indexOf(canonical)
    }

    init {
        require(width in MIN_DIM..MAX_DIM) { "semantic manifest width out of range: $width" }
        require(height in MIN_DIM..MAX_DIM) { "semantic manifest height out of range: $height" }
        require(width.toLong() * height <= 1_048_576L) { "semantic manifest raster exceeds the bounded runtime workspace" }
        require(classNames.isNotEmpty()) { "semantic manifest declares no classes" }
        require(classNames.size <= MAX_CLASSES) { "semantic manifest declares too many classes: ${classNames.size}" }
        require(classNames.toSet().size == classNames.size) { "semantic manifest class names are not unique" }
        classNames.forEach { require(it.isNotBlank()) { "semantic manifest has a blank class name" } }
        // Upstream indexes every canonical terrain name at detection time; a manifest missing one
        // would fail mid-frame. Reject it up front instead of decoding a partial model.
        val declared = classNames.mapNotNull(TerrainTileClasses::canonical).toSet()
        for (required in TerrainTileClasses.ORDER) {
            require(required in declared) { "semantic manifest is missing required terrain class '$required'" }
        }
    }

    companion object {
        const val MIN_DIM = 8
        const val MAX_DIM = 8192
        const val MAX_CLASSES = 256

        /** Parses and fully validates the manifest text. Throws [IllegalArgumentException]. */
        fun parse(text: String): SemanticTerrainManifest {
            val root = StrictJson.parseObject(text)
            val classIdsRaw = root["class_ids"] as? Map<*, *>
                ?: throw IllegalArgumentException("semantic manifest has no class_ids object")
            val width = root.intField("width")
            val height = root.intField("height")

            val names = arrayOfNulls<String>(classIdsRaw.size)
            for ((key, value) in classIdsRaw) {
                val index = (key as? String)?.toIntOrNull()
                    ?: throw IllegalArgumentException("semantic manifest class_ids key is not a decimal index: $key")
                if (index < 0 || index >= classIdsRaw.size) {
                    throw IllegalArgumentException("semantic manifest class id $index is outside 0..${classIdsRaw.size - 1}")
                }
                if (names[index] != null) {
                    throw IllegalArgumentException("semantic manifest class id $index is declared twice")
                }
                names[index] = value as? String
                    ?: throw IllegalArgumentException("semantic manifest class id $index is not a string")
            }
            // Contiguity is implicit: a missing index leaves a null hole and is rejected here.
            val ordered = names.mapIndexed { i, name ->
                name ?: throw IllegalArgumentException("semantic manifest class id $i is missing")
            }
            return SemanticTerrainManifest(width = width, height = height, classNames = ordered)
        }

        private fun Map<*, *>.intField(name: String): Int {
            val raw = this[name]
            return when (raw) {
                is Long -> raw
                is Double -> if (raw == Math.floor(raw) && !raw.isInfinite()) raw.toLong() else null
                else -> null
            }?.let {
                if (it < Int.MIN_VALUE || it > Int.MAX_VALUE) null else it.toInt()
            } ?: throw IllegalArgumentException("semantic manifest field '$name' must be an integer")
        }
    }
}

/**
 * Upstream `_SEMANTIC_TILE_NAMES` / `_TERRAIN_TILE_CLASSES`, verbatim. Several manifest entries
 * collapse onto gameplay `wall`; the output id is the index in [ORDER].
 */
object TerrainTileClasses {
    const val WALL = "wall"
    const val BUSH = "bush"
    const val ROPE_FENCE = "rope_fence"
    const val WATER = "water"
    const val OUT_OF_BOUNDS = "out_of_bounds"

    /** Fixed output order; matches upstream `classes = list(_TERRAIN_TILE_CLASSES)`. */
    val ORDER: List<String> = listOf(WALL, BUSH, ROPE_FENCE, WATER, OUT_OF_BOUNDS)

    fun canonical(rawName: String): String? = when (rawName) {
        "wall", "indestructible_wall", "fragile" -> WALL
        "bush" -> BUSH
        "rope_fence" -> ROPE_FENCE
        "water" -> WATER
        "out_of_bounds" -> OUT_OF_BOUNDS
        else -> null
    }

    /** Classes that only block movement; they must never be treated as projectile walls. */
    val MOVEMENT_ONLY: List<String> = listOf(ROPE_FENCE, WATER, OUT_OF_BOUNDS)
}

/** Minimal strict JSON reader: objects, arrays, strings, numbers, booleans and null only. */
internal object StrictJson {
    fun parseObject(text: String): Map<String, Any?> {
        val parser = Parser(text)
        val value = parser.value()
        parser.skipWhitespace()
        require(parser.atEnd()) { "trailing content after semantic manifest JSON" }
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?>
            ?: throw IllegalArgumentException("semantic manifest root is not an object")
    }

    private class Parser(private val s: String) {
        private var i = 0
        fun atEnd(): Boolean = i >= s.length
        fun skipWhitespace() { while (i < s.length && s[i].isWhitespace()) i++ }

        fun value(): Any? {
            skipWhitespace()
            require(i < s.length) { "unexpected end of JSON" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("unexpected JSON char '$c'")
            }
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++ // {
            skipWhitespace()
            if (peek() == '}') { i++; return out }
            while (true) {
                skipWhitespace()
                require(peek() == '"') { "JSON object key must be a string" }
                val key = string()
                skipWhitespace()
                require(peek() == ':') { "JSON object missing ':'" }
                i++
                out[key] = value()
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected ',' or '}' in JSON object")
                }
            }
        }

        private fun array(): List<Any?> {
            val out = ArrayList<Any?>()
            i++ // [
            skipWhitespace()
            if (peek() == ']') { i++; return out }
            while (true) {
                out.add(value())
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected ',' or ']' in JSON array")
                }
            }
        }

        private fun string(): String {
            require(s[i] == '"') { "expected JSON string" }
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated JSON string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(i < s.length) { "unterminated JSON escape" }
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(i + 4 <= s.length) { "truncated JSON unicode escape" }
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("invalid JSON escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val raw = s.substring(start, i)
            val asLong = raw.toLongOrNull()
            if (asLong != null) return asLong
            return raw.toDoubleOrNull() ?: throw IllegalArgumentException("invalid JSON number '$raw'")
        }

        private fun literal(word: String, result: Any?): Any? {
            require(s.startsWith(word, i)) { "invalid JSON literal" }
            i += word.length
            return result
        }

        private fun peek(): Char = if (i < s.length) s[i] else throw IllegalArgumentException("unexpected end of JSON")
    }
}
