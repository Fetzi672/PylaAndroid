package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-pinned parity tests for the semantic terrain variant (upstream PylaAI
 * f572176e1b0370fc4530607034af80fb57cb1639 `detect.py`). Pure JVM: no ORT, OpenCV or Android
 * types are touched, so these run without a device or native model.
 */
class SemanticTerrainManifestTest {

    private fun manifestText(classIds: String, width: Int = 832, height: Int = 480) = """
        {"class_ids": {$classIds}, "width": $width, "height": $height,
         "input": "images: RGB float32 [1,3,${height},${width}] in [0,1]",
         "outputs": {"class_ids": "uint8", "confidence": "float32"}}
    """.trimIndent()

    private val full = """
        "0": "ground", "1": "wall", "2": "bush", "3": "rope_fence",
        "4": "water", "5": "out_of_bounds", "6": "indestructible_wall"
    """.trimIndent()

    @Test
    fun parsesDeclaredGeometryAndMapsTerrainClasses() {
        val manifest = SemanticTerrainManifest.parse(manifestText(full))
        assertEquals(832, manifest.width)
        assertEquals(480, manifest.height)
        assertEquals(7, manifest.classNames.size)
        assertEquals("wall", manifest.canonicalName(1))
        assertEquals("wall", manifest.canonicalName(6))
        assertEquals("water", manifest.canonicalName(4))
        assertNull(manifest.canonicalName(0))
        assertEquals(0, manifest.outputClassId(1))
        assertEquals(3, manifest.outputClassId(4))
        assertEquals(4, manifest.outputClassId(5))
        assertEquals(-1, manifest.outputClassId(0))
    }

    @Test
    fun rejectsManifestWithoutRequiredTerrainClass() {
        val missingWater = """
            "0": "ground", "1": "wall", "2": "bush", "3": "rope_fence", "4": "out_of_bounds"
        """.trimIndent()
        assertThrows { SemanticTerrainManifest.parse(manifestText(missingWater)) }
    }

    @Test
    fun rejectsNonContiguousClassIds() {
        val hole = """
            "0": "ground", "1": "wall", "2": "bush", "3": "rope_fence",
            "4": "water", "6": "out_of_bounds"
        """.trimIndent()
        assertThrows { SemanticTerrainManifest.parse(manifestText(hole)) }
    }

    @Test
    fun rejectsOutOfRangeGeometryAndMissingFields() {
        assertThrows { SemanticTerrainManifest.parse(manifestText(full, width = 0)) }
        assertThrows { SemanticTerrainManifest.parse(manifestText(full, height = 16384)) }
        assertThrows { SemanticTerrainManifest.parse("""{"class_ids": {$full}}""") }
        assertThrows { SemanticTerrainManifest.parse("""{"width": 832, "height": 480}""") }
        assertThrows { SemanticTerrainManifest.parse("not json") }
        assertThrows { SemanticTerrainManifest.parse("""{"class_ids": {$full}, "width": 832.5, "height": 480}""") }
    }

    @Test
    fun rejectsDuplicateNamesAndBlankNames() {
        val duplicate = """
            "0": "ground", "1": "wall", "2": "wall", "3": "rope_fence",
            "4": "water", "5": "out_of_bounds"
        """.trimIndent()
        assertThrows { SemanticTerrainManifest.parse(manifestText(duplicate)) }
        val blank = """
            "0": "", "1": "wall", "2": "bush", "3": "rope_fence",
            "4": "water", "5": "out_of_bounds"
        """.trimIndent()
        assertThrows { SemanticTerrainManifest.parse(manifestText(blank)) }
    }

    private inline fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}

class SemanticTerrainDecoderTest {

    private val manifest = SemanticTerrainManifest.parse(
        """
        {"class_ids": {"0": "ground", "1": "wall", "2": "bush", "3": "rope_fence",
                       "4": "water", "5": "out_of_bounds", "6": "indestructible_wall"},
         "width": 832, "height": 480}
        """.trimIndent(),
    )

    private fun raster(
        width: Int,
        height: Int,
        block: (x: Int, y: Int) -> Pair<Int, Float>,
    ): Pair<ByteArray, FloatArray> {
        val ids = ByteArray(width * height)
        val confidence = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (id, conf) = block(x, y)
                ids[y * width + x] = id.toByte()
                confidence[y * width + x] = conf
            }
        }
        return ids to confidence
    }

    private fun decode(
        ids: ByteArray,
        confidence: FloatArray,
        contentWidth: Int,
        contentHeight: Int,
        originalWidth: Int = contentWidth,
        originalHeight: Int = contentHeight,
        tileSize: Double = 54.0,
        threshold: Float = 0.6f,
    ) = SemanticTerrainDecoder().decode(
        manifest = manifest,
        classIds = ids,
        confidence = confidence,
        contentWidth = contentWidth,
        contentHeight = contentHeight,
        originalWidth = originalWidth,
        originalHeight = originalHeight,
        tileSizePx = tileSize,
        confidenceThreshold = threshold,
    )

    @Test
    fun emitsOneBoundedBoxPerOccupiedTileCell() {
        val (ids, confidence) = raster(832, 480) { x, y ->
            if (x in 108 until 162 && y in 54 until 108) 1 to 0.9f else 0 to 0.99f
        }
        // Ground is class 0 with high confidence and is not a terrain class, so it is ignored.
        val boxes = decode(ids, confidence, 832, 480)
        assertEquals(1, boxes.size)
        val box = boxes[0]
        assertEquals("wall", box.name)
        assertEquals(108, box.x1)
        assertEquals(54, box.y1)
        assertEquals(162, box.x2)
        assertEquals(108, box.y2)
        assertEquals(0.9f, box.score, 1e-5f)
    }

    @Test
    fun scalesCellsBackToOriginalFrame() {
        // Content is half the original size: scale 2 in both axes.
        val (ids, confidence) = raster(416, 240) { x, y ->
            if (x in 54 until 108 && y in 54 until 108) 1 to 0.9f else 0 to 0f
        }
        val boxes = decode(ids, confidence, 416, 240, originalWidth = 832, originalHeight = 480)
        // One 54 px original cell is 27 px in the half-size content raster, so the block is 2x2.
        assertEquals(4, boxes.size)
        assertEquals(108, boxes.minOf { it.x1 })
        assertEquals(108, boxes.minOf { it.y1 })
        assertEquals(216, boxes.maxOf { it.x2 })
        assertEquals(216, boxes.maxOf { it.y2 })
        for (box in boxes) assertTrue(box.x2 - box.x1 == 54 && box.y2 - box.y1 == 54)
    }

    @Test
    fun dropsWeakAndTinyComponentsButKeepsCompactStrongOnes() {
        val areaThreshold = 0.0006 * 832 * 480
        assertTrue(areaThreshold > 1.0)

        // 54x54 block, average confidence (0.9 + 0.6) / 2 = 0.75 < 0.80.
        val (weakIds, weakConf) = raster(832, 480) { x, y ->
            if (x in 108 until 162 && y in 54 until 108) 1 to (if (x < 135) 0.9f else 0.6f) else 0 to 0f
        }
        assertTrue(decode(weakIds, weakConf, 832, 480).isEmpty())

        // One pixel is far below the component area threshold.
        val (tinyIds, tinyConf) = raster(832, 480) { x, y ->
            if (x == 400 && y == 200) 1 to 0.95f else 0 to 0f
        }
        assertTrue(decode(tinyIds, tinyConf, 832, 480).isEmpty())
    }

    @Test
    fun appliesPerClassConfidenceFloors() {
        fun blockOf(id: Int, conf: Float, x0: Int = 108): Pair<ByteArray, FloatArray> =
            raster(832, 480) { x, y ->
                if (x in x0 until x0 + 54 && y in 54 until 108) id to conf else 0 to 0f
            }

        val (waterLowIds, waterLowConf) = blockOf(4, 0.70f)
        assertTrue("water needs 0.75", decode(waterLowIds, waterLowConf, 832, 480).isEmpty())

        val (waterOkIds, waterOkConf) = blockOf(4, 0.80f)
        val water = decode(waterOkIds, waterOkConf, 832, 480)
        assertEquals(1, water.size)
        assertEquals("water", water[0].name)
        assertEquals(3, water[0].classId)

        // rope_fence floor is 0.65, but the >=0.80 component mean still applies.
        val (ropeLowIds, ropeLowConf) = blockOf(3, 0.70f)
        assertTrue(decode(ropeLowIds, ropeLowConf, 832, 480).isEmpty())
        val (ropeOkIds, ropeOkConf) = blockOf(3, 0.90f)
        assertEquals("rope_fence", decode(ropeOkIds, ropeOkConf, 832, 480).single().name)
    }

    @Test
    fun outOfBoundsIgnoresThresholdAndComponentFilter() {
        val (ids, confidence) = raster(832, 480) { x, y ->
            if (x == 500 && y == 300) 5 to 0.02f else 0 to 0f
        }
        val boxes = decode(ids, confidence, 832, 480, threshold = 0.9f)
        assertEquals(1, boxes.size)
        assertEquals("out_of_bounds", boxes[0].name)
        assertEquals(4, boxes[0].classId)
        assertEquals(500, boxes[0].x1)
        assertEquals(501, boxes[0].x2)
        assertEquals(1, boxes[0].y2 - boxes[0].y1)
    }

    @Test
    fun foldsAliasClassesOntoWall() {
        val (ids, confidence) = raster(832, 480) { x, y ->
            if (x in 216 until 270 && y in 54 until 108) 6 to 0.9f else 0 to 0f
        }
        val boxes = decode(ids, confidence, 832, 480)
        assertEquals(1, boxes.size)
        assertEquals("wall", boxes[0].name)
        assertEquals(0, boxes[0].classId)
    }

    @Test
    fun largeRegionYieldsPerCellBoxesNotOneGiantBox() {
        val (ids, confidence) = raster(832, 480) { _, _ -> 1 to 0.9f }
        val boxes = decode(ids, confidence, 832, 480)
        val expectedColumns = (832 + 53) / 54
        val expectedRows = (480 + 53) / 54
        assertEquals(expectedColumns * expectedRows, boxes.size)
        assertTrue(boxes.size < 200)
        for (box in boxes) {
            assertTrue(box.x2 > box.x1 && box.y2 > box.y1)
            assertTrue(box.x1 >= 0 && box.y1 >= 0)
            assertTrue(box.x2 <= 832 && box.y2 <= 480)
            assertTrue(box.score > 0.89f)
        }
    }

    @Test
    fun rejectsTensorsSmallerThanTheDeclaredContent() {
        try {
            decode(ByteArray(10), FloatArray(10), 832, 480)
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}

class SemanticTerrainLetterboxTest {

    @Test
    fun centrePadsWideFrameLikeUpstream() {
        val g = SemanticTerrainLetterbox.compute(2400, 1080, 832, 480)
        assertEquals(832, g.contentWidth)
        assertEquals(374, g.contentHeight)
        assertEquals(0, g.padX)
        assertEquals(53, g.padY)
        assertEquals(114f / 255f, SemanticTerrainLetterbox.PAD_VALUE, 0f)
    }

    @Test
    fun pythonRoundIsHalfEven() {
        assertEquals(2, SemanticTerrainLetterbox.pythonRound(2.5))
        assertEquals(4, SemanticTerrainLetterbox.pythonRound(3.5))
        assertEquals(374, SemanticTerrainLetterbox.pythonRound(374.4))
        assertEquals(375, SemanticTerrainLetterbox.pythonRound(374.6))
    }
}
