package com.pyla.ai.engine

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** One decoded terrain cell in original capture pixel coordinates. */
data class SemanticTerrainBox(
    val name: String,
    val classId: Int,
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
    val score: Float,
)

/**
 * Bounded Kotlin port of upstream `Detect._semantic_tile_detections` (PylaAI pinned
 * f572176e1b0370fc4530607034af80fb57cb1639). Pure Kotlin, no ORT/OpenCV/Android types, so the
 * geometry can be unit-tested on the JVM.
 *
 * Parity notes (do not "improve" these without changing the upstream pin):
 *  - Args are the *cropped letterbox content*: [classIds] and [confidence] are indexed over
 *    [contentWidth] x [contentHeight] only, which is what upstream gets after
 *    `raw[crop]` with `crop = (0, slice(pad_y, pad_y+new_h), slice(pad_x, pad_x+new_w))`.
 *  - Per-class confidence floor: out_of_bounds ignores the caller threshold entirely, rope_fence
 *    needs at least 0.65, water 0.75, everything else 0.60; the caller threshold wins when higher.
 *  - Components (except out_of_bounds) are 8-connected, must cover >= 0.0006 * content area and
 *    must average >= 0.80 confidence, otherwise they are dropped.
 *  - Each occupied tile cell (`perceived_tile_size` in original pixels) becomes one box. This is
 *    deliberately coarse: gameplay receives per-cell rectangles, never one box around a lake.
 *  - Coordinates use truncation for the near edge and ceil for the far edge, clamped to the frame.
 *
 * Memory is bounded and reused per decoder: one byte region mask plus one int label map and one
 * int flood-fill stack for the content area (480x832 => ~2 MB total). No per-pixel boxed objects,
 * no allocation inside the cell loops.
 */
class SemanticTerrainDecoder {
    private var region = ByteArray(0)
    private var labels = IntArray(0)
    private var stack = IntArray(0)
    private var componentArea = IntArray(0)
    private var componentConfidence = FloatArray(0)
    private var componentKeep = BooleanArray(0)

    /**
     * @param classIds uint8 argmax class per content pixel, tightly packed, [contentWidth] stride.
     * @param confidence float32 probability of the argmax class, same layout as [classIds].
     */
    fun decode(
        manifest: SemanticTerrainManifest,
        classIds: ByteArray,
        confidence: FloatArray,
        contentWidth: Int,
        contentHeight: Int,
        originalWidth: Int,
        originalHeight: Int,
        tileSizePx: Double,
        confidenceThreshold: Float,
    ): List<SemanticTerrainBox> {
        require(contentWidth in 1..manifest.width && contentHeight in 1..manifest.height) {
            "letterbox content ${contentWidth}x$contentHeight is outside the model input " +
                "${manifest.width}x${manifest.height}"
        }
        require(originalWidth >= 1 && originalHeight >= 1) { "invalid original frame size" }
        require(tileSizePx.isFinite() && tileSizePx > 0.0) { "invalid terrain tile size" }
        require(confidenceThreshold.isFinite() && confidenceThreshold in 0f..1f) { "invalid terrain confidence threshold" }
        val pixels = contentWidth * contentHeight
        require(classIds.size >= pixels) { "class_ids tensor is smaller than the letterbox content" }
        require(confidence.size >= pixels) { "confidence tensor is smaller than the letterbox content" }
        for (i in 0 until pixels) {
            require((classIds[i].toInt() and 255) < manifest.classNames.size &&
                confidence[i].isFinite() && confidence[i] in 0f..1f) { "invalid semantic raster value" }
        }

        val stepX = max(1, (tileSizePx / (originalWidth.toDouble() / contentWidth)).toInt())
        val stepY = max(1, (tileSizePx / (originalHeight.toDouble() / contentHeight)).toInt())
        val minComponentArea = 0.0006 * contentWidth * contentHeight
        val scaleX = originalWidth.toDouble() / contentWidth
        val scaleY = originalHeight.toDouble() / contentHeight

        ensureCapacity(pixels)
        val out = ArrayList<SemanticTerrainBox>()

        for (manifestId in manifest.classNames.indices) {
            val rawName = manifest.classNames[manifestId]
            val canonical = TerrainTileClasses.canonical(rawName) ?: continue
            val minimum = if (rawName == TerrainTileClasses.OUT_OF_BOUNDS) 0f else max(
                confidenceThreshold,
                when (rawName) {
                    TerrainTileClasses.ROPE_FENCE -> 0.65f
                    TerrainTileClasses.WATER -> 0.75f
                    else -> 0.60f
                },
            )
            val id = manifestId
            var occupied = 0
            for (i in 0 until pixels) {
                val hit = (classIds[i].toInt() and 0xFF) == id && confidence[i] >= minimum
                region[i] = if (hit) 1 else 0
                if (hit) occupied++
            }
            if (occupied == 0) continue

            if (rawName != TerrainTileClasses.OUT_OF_BOUNDS) {
                if (!filterComponents(pixels, contentWidth, contentHeight, confidence, minComponentArea)) continue
            }

            var x0 = Int.MAX_VALUE
            var y0 = Int.MAX_VALUE
            var x1 = -1
            var y1 = -1
            for (y in 0 until contentHeight) {
                val row = y * contentWidth
                for (x in 0 until contentWidth) {
                    if (region[row + x].toInt() == 0) continue
                    if (x < x0) x0 = x
                    if (x > x1) x1 = x
                    if (y < y0) y0 = y
                    if (y > y1) y1 = y
                }
            }
            if (x1 < 0) continue
            val width = x1 - x0 + 1
            val height = y1 - y0 + 1
            val outputId = TerrainTileClasses.ORDER.indexOf(canonical)

            // Same tiling as upstream: start on the cell grid at or before the region origin.
            var cellY = (y0 / stepY) * stepY
            while (cellY < y0 + height) {
                var cellX = (x0 / stepX) * stepX
                while (cellX < x0 + width) {
                    val cellRight = min(cellX + stepX, contentWidth)
                    val cellBottom = min(cellY + stepY, contentHeight)
                    var bx0 = Int.MAX_VALUE
                    var by0 = Int.MAX_VALUE
                    var bx1 = -1
                    var by1 = -1
                    var sum = 0.0
                    var count = 0
                    for (y in cellY until cellBottom) {
                        val row = y * contentWidth
                        for (x in cellX until cellRight) {
                            if (region[row + x].toInt() == 0) continue
                            if (x < bx0) bx0 = x
                            if (x > bx1) bx1 = x
                            if (y < by0) by0 = y
                            if (y > by1) by1 = y
                            sum += confidence[row + x]
                            count++
                        }
                    }
                    if (bx1 >= 0 && by1 >= 0) {
                        // bx/by are absolute content coordinates (the cell slice is not re-based),
                        // so they must not be offset by cellX/cellY again.
                        val left = bx0 * scaleX
                        val top = by0 * scaleY
                        val right = (bx1 + 1) * scaleX
                        val bottom = (by1 + 1) * scaleY
                        out.add(
                            SemanticTerrainBox(
                                name = canonical,
                                classId = outputId,
                                x1 = left.toInt(),
                                y1 = top.toInt(),
                                x2 = min(originalWidth, ceil(right).toInt()),
                                y2 = min(originalHeight, ceil(bottom).toInt()),
                                score = (sum / count).toFloat(),
                            ),
                        )
                    }
                    cellX += stepX
                }
                cellY += stepY
            }
        }
        return out
    }

    /**
     * 8-connected component filter over [region], in place. label 0 is background and is never
     * kept, matching upstream's `keep[0] = False`. Component labels are traversal-local; only
     * membership and the per-component area/mean confidence are observable, so results match
     * OpenCV's `connectedComponentsWithStats(connectivity=8)` without needing OpenCV.
     */
    private fun filterComponents(
        pixels: Int,
        contentWidth: Int,
        contentHeight: Int,
        confidence: FloatArray,
        minComponentArea: Double,
    ): Boolean {
        java.util.Arrays.fill(labels, 0, pixels, 0)
        var componentCount = 0
        var stackTop: Int
        for (start in 0 until pixels) {
            if (region[start].toInt() == 0 || labels[start] != 0) continue
            componentCount++
            var area = 0
            var confSum = 0.0
            stackTop = 0
            stack[stackTop++] = start
            labels[start] = componentCount
            while (stackTop > 0) {
                val index = stack[--stackTop]
                area++
                confSum += confidence[index]
                val y = index / contentWidth
                val x = index - y * contentWidth
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= contentHeight) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= contentWidth) continue
                        val neighbour = ny * contentWidth + nx
                        if (region[neighbour].toInt() == 0 || labels[neighbour] != 0) continue
                        labels[neighbour] = componentCount
                        stack[stackTop++] = neighbour
                    }
                }
            }
            if (componentArea.size <= componentCount) {
                componentArea = componentArea.copyOf(max(componentCount + 1, componentCount * 2))
                componentConfidence = componentConfidence.copyOf(componentArea.size)
                componentKeep = componentKeep.copyOf(componentArea.size)
            }
            componentArea[componentCount] = area
            componentConfidence[componentCount] = (confSum / area).toFloat()
            componentKeep[componentCount] = area >= minComponentArea && (confSum / area) >= 0.80
        }
        if (componentCount == 0) return false

        var kept = 0
        for (i in 0 until pixels) {
            val label = labels[i]
            if (label == 0) continue
            if (componentKeep[label]) {
                region[i] = 1
                kept++
            } else {
                region[i] = 0
            }
        }
        return kept > 0
    }

    private fun ensureCapacity(pixels: Int) {
        if (region.size < pixels) {
            region = ByteArray(pixels)
            labels = IntArray(pixels)
            stack = IntArray(pixels)
        }
    }
}

/**
 * Semantic preprocessing geometry, ported from upstream `preprocess_image`: aspect-preserving
 * resize with `round` (Python half-even), then *centred* letterbox padding filled with 114/255.
 * The legacy YOLO path in [Detect] stays top-left with 128/255; they are intentionally different.
 */
object SemanticTerrainLetterbox {
    const val PAD_VALUE: Float = 114f / 255f

    data class Geometry(
        val contentWidth: Int,
        val contentHeight: Int,
        val padX: Int,
        val padY: Int,
    )

    /** Python's `round()` is half-even; `roundToInt` is half-up, which differs at exact .5. */
    fun pythonRound(value: Double): Int {
        val floor = kotlin.math.floor(value)
        val diff = value - floor
        return when {
            diff > 0.5 -> (floor + 1).toInt()
            diff < 0.5 -> floor.toInt()
            else -> if (floor.toLong() % 2L == 0L) floor.toInt() else (floor + 1).toInt()
        }
    }

    fun compute(sourceWidth: Int, sourceHeight: Int, inputWidth: Int, inputHeight: Int): Geometry {
        val scale = min(inputHeight.toDouble() / sourceHeight, inputWidth.toDouble() / sourceWidth)
        val contentWidth = pythonRound(sourceWidth * scale).coerceIn(1, inputWidth)
        val contentHeight = pythonRound(sourceHeight * scale).coerceIn(1, inputHeight)
        return Geometry(
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            padX = (inputWidth - contentWidth) / 2,
            padY = (inputHeight - contentHeight) / 2,
        )
    }
}
