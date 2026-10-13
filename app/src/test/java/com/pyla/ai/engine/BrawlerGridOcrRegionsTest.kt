package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focused tests for [BrawlerGridOcrRegions]: the pure integer geometry behind the user-approved
 * targeted brawler-list OCR.
 *
 * The expected rectangles below are hard-coded for the supplied 1280x591 screenshot and the
 * 1920x886 control capture, so a change to any region fraction, scale, gutter or rounding rule
 * fails here instead of silently shifting what the recogniser is shown. The unsupported cases are
 * as important as the supported ones: every one of them must return `null` so that the caller keeps
 * its previous bounded strict path, and none of them may ever be mistaken for a list admission.
 */
class BrawlerGridOcrRegionsTest {

    // ---- exact plans for the supplied captures ------------------------------------------------

    @Test fun screenshotCapturePlansHeadingAndThreeTitleStrips() {
        val plan = assertPlan(1280, 591)

        assertEquals(1280, plan.sourceWidth)
        assertEquals(591, plan.sourceHeight)
        assertEquals(4, plan.tiles.size)
        assertEquals(BrawlerGridOcrRegions.TILE_COUNT, plan.tiles.size)
        // Mosaic is 1248 x 393 for the screenshot.
        assertEquals(1248, plan.width)
        assertEquals(393, plan.height)

        assertEquals(GridOcrRect(0, 35, 588, 94), plan.tiles[0].source)
        assertEquals(GridOcrRect(0, 0, 1176, 118), plan.tiles[0].destination)
        assertEquals(GridOcrRect(0, 177, 832, 224), plan.tiles[1].source)
        assertEquals(GridOcrRect(0, 142, 1248, 212), plan.tiles[1].destination)
        assertEquals(GridOcrRect(0, 336, 832, 384), plan.tiles[2].source)
        assertEquals(GridOcrRect(0, 236, 1248, 308), plan.tiles[2].destination)
        assertEquals(GridOcrRect(0, 496, 832, 537), plan.tiles[3].source)
        assertEquals(GridOcrRect(0, 332, 1248, 393), plan.tiles[3].destination)
    }

    @Test fun controlCapturePlansHeadingAndThreeTitleStrips() {
        val plan = assertPlan(1920, 886)

        assertEquals(1920, plan.sourceWidth)
        assertEquals(886, plan.sourceHeight)
        assertEquals(1872, plan.width)
        assertEquals(552, plan.height)

        assertEquals(GridOcrRect(0, 53, 883, 141), plan.tiles[0].source)
        assertEquals(GridOcrRect(0, 0, 1766, 176), plan.tiles[0].destination)
        assertEquals(GridOcrRect(0, 265, 1248, 336), plan.tiles[1].source)
        assertEquals(GridOcrRect(0, 200, 1872, 306), plan.tiles[1].destination)
        assertEquals(GridOcrRect(0, 505, 1248, 575), plan.tiles[2].source)
        assertEquals(GridOcrRect(0, 330, 1872, 435), plan.tiles[2].destination)
        assertEquals(GridOcrRect(0, 744, 1248, 806), plan.tiles[3].source)
        assertEquals(GridOcrRect(0, 459, 1872, 552), plan.tiles[3].destination)
    }

    @Test fun tilesStackSequentiallyWithExactGutterAndNoTrailingGap() {
        forEachPlan { plan, _ ->
            assertEquals("tiles touch the mosaic left edge", 0, plan.tiles.first().destination.left)
            assertEquals("first tile starts the mosaic", 0, plan.tiles.first().destination.top)
            for (index in 1 until plan.tiles.size) {
                val previous = plan.tiles[index - 1].destination
                val current = plan.tiles[index].destination
                assertEquals(
                    "gap between tile ${index - 1} and $index must be exactly the gutter",
                    BrawlerGridOcrRegions.GUTTER,
                    current.top - previous.bottom,
                )
            }
            val last = plan.tiles.last().destination
            assertEquals("mosaic height is the bottom of the last tile", last.bottom, plan.height)
            assertEquals("mosaic width is the widest tile", maxRight(plan), plan.width)
        }
    }

    @Test fun everyTileSourceStaysInsideTheCapturedFrame() {
        forEachPlan { plan, _ ->
            for (tile in plan.tiles) {
                val source = tile.source
                assertTrue("tile source must have positive area", source.width > 0 && source.height > 0)
                assertTrue("tile source inside frame", source.left >= 0 && source.top >= 0)
                assertTrue(
                    "tile source inside frame",
                    source.right <= plan.sourceWidth && source.bottom <= plan.sourceHeight,
                )
                assertTrue(
                    "title strips stop at the left 65% selection",
                    source.right <= plan.sourceWidth * 65 / 100,
                )
            }
            assertEquals(24, BrawlerGridOcrRegions.GUTTER)
        }
    }

    // ---- exact bounding-box transforms --------------------------------------------------------

    @Test fun headingTileStartsAtTheLeftEdgeSoTheHeadingWordIsNeverClipped() {
        // Measured on the supplied 1280x591 brawler-list captures (img_9aa1eb3acace.jpg,
        // img_313e0018c002.jpg): the "BRAWLERS <count>" heading glyphs occupy capture box
        // (72, 36, 252, 94). A left inset of 8% starts the heading tile at x=102 and clips the
        // word to "AWLERS", which is why the heading tile must start at x=0.
        val heading = GridOcrRect(72, 36, 252, 94)

        // The heading tile starts at the left frame edge for every supported capture size.
        for ((width, height) in listOf(1280 to 591, 1920 to 886, 1880 to 800, 4096 to 2048)) {
            assertEquals(
                "heading tile of ${width}x$height must start at the left frame edge",
                0,
                assertPlan(width, height).tiles[0].source.left,
            )
        }

        // The measured heading box must lie inside the header source of the capture it came from,
        // and it must not fit the previous 8%-inset region, which is the regression itself.
        val header = assertPlan(1280, 591).tiles[0].source
        assertTrue(
            "measured heading $heading must lie inside header source $header",
            contains(header, heading),
        )
        val insetHeader = GridOcrRect(102, 35, 588, 94)
        assertTrue(
            "the 8%-inset header region clips the heading, so it must no longer be the tile",
            !contains(insetHeader, heading),
        )

        // The measured heading box must survive the mosaic round trip unchanged, so the
        // recogniser's box for the heading maps back to the exact capture pixels it was cut from.
        val plan = assertPlan(1280, 591)
        val mapped = BrawlerGridOcrRegions.map(plan, GridOcrRect(144, 2, 504, 118))
        assertEquals(heading, mapped)
        assertEquals(GridOcrRect(0, 35, 588, 94), map(1280, 591, 0, 0, 1176, 118))
    }

    @Test fun headingTileKeepsItsOriginalTopRightBandAndScale() {
        for ((width, height) in listOf(1280 to 591, 1920 to 886, 1880 to 800, 4096 to 2048)) {
            val header = assertPlan(width, height).tiles[0].source
            assertEquals(
                "header top/right/bottom of ${width}x$height stay on 6%/46%/16%",
                GridOcrRect(0, height * 6 / 100, width * 46 / 100, height * 16 / 100),
                header,
            )
        }
        // Only the left edge moved: the heading tile is still scaled exactly 2x.
        val plan = assertPlan(1280, 591)
        assertEquals(2, plan.tiles[0].destination.width / plan.tiles[0].source.width)
        assertEquals(2, plan.tiles[0].destination.height / plan.tiles[0].source.height)
        assertEquals(
            "mosaic width is still driven by the 65% title strips, not the heading tile",
            1248,
            plan.width,
        )
    }


    @Test fun fullTileBoxesMapBackToTheExactSourceRegions() {
        assertEquals(GridOcrRect(0, 35, 588, 94), map(1280, 591, 0, 0, 1176, 118))
        assertEquals(GridOcrRect(0, 177, 832, 224), map(1280, 591, 0, 142, 1248, 212))
        assertEquals(GridOcrRect(0, 336, 832, 384), map(1280, 591, 0, 236, 1248, 308))
        assertEquals(GridOcrRect(0, 496, 832, 537), map(1280, 591, 0, 332, 1248, 393))

        assertEquals(GridOcrRect(0, 53, 883, 141), map(1920, 886, 0, 0, 1766, 176))
        assertEquals(GridOcrRect(0, 265, 1248, 336), map(1920, 886, 0, 200, 1872, 306))
        assertEquals(GridOcrRect(0, 505, 1248, 575), map(1920, 886, 0, 330, 1872, 435))
        assertEquals(GridOcrRect(0, 744, 1248, 806), map(1920, 886, 0, 459, 1872, 552))
    }

    @Test fun trailingEdgeCeilsSoEveryRequestedPixelStaysCaptured() {
        // These boxes land exactly on a pixel boundary where floor and ceil disagree. Each one must
        // keep the capture pixel the request covered, so a floor on the trailing edge would silently
        // drop the last column/row of a title.
        assertEquals(GridOcrRect(0, 496, 832, 537), map(1280, 591, 0, 332, 1247, 393))
        assertEquals(GridOcrRect(0, 496, 831, 537), map(1280, 591, 0, 332, 1246, 393))
        assertEquals(GridOcrRect(0, 496, 832, 536), map(1280, 591, 0, 332, 1248, 391))
        assertEquals(GridOcrRect(0, 496, 832, 537), map(1280, 591, 0, 332, 1248, 392))
        assertEquals(GridOcrRect(0, 496, 831, 537), map(1280, 591, 1, 333, 1246, 392))
        assertEquals(GridOcrRect(0, 177, 831, 224), map(1280, 591, 0, 142, 1246, 212))
        assertEquals(GridOcrRect(50, 45, 151, 65), map(1280, 591, 100, 20, 301, 60))

        assertEquals(GridOcrRect(0, 505, 1248, 575), map(1920, 886, 0, 330, 1871, 435))
        assertEquals(GridOcrRect(0, 744, 1247, 806), map(1920, 886, 0, 459, 1870, 552))
        assertEquals(GridOcrRect(0, 744, 1248, 806), map(1920, 886, 0, 459, 1872, 551))
        assertEquals(GridOcrRect(333, 744, 1246, 806), map(1920, 886, 500, 460, 1869, 551))
    }

    @Test fun partialBoxesUseTheExactTileRatio() {
        // Heading tile is scaled 2x over a 588 x 59 capture region.
        assertEquals(GridOcrRect(50, 45, 150, 65), map(1280, 591, 100, 20, 300, 60))
        // 832x47 capture strip scaled 1.5x into a 1248 x 70 tile.
        assertEquals(GridOcrRect(6, 179, 600, 216), map(1280, 591, 10, 145, 900, 200))
        assertEquals(GridOcrRect(0, 338, 400, 379), map(1280, 591, 0, 240, 600, 300))
        assertEquals(GridOcrRect(3, 498, 800, 535), map(1280, 591, 5, 335, 1200, 390))
        // A one-pixel box keeps its single pixel: leading floor, trailing ceil.
        assertEquals(GridOcrRect(485, 93, 486, 94), map(1280, 591, 971, 117, 972, 118))

        assertEquals(GridOcrRect(66, 508, 1000, 572), map(1920, 886, 100, 335, 1500, 430))
        assertEquals(GridOcrRect(0, 268, 600, 332), map(1920, 886, 0, 205, 900, 300))
        assertEquals(GridOcrRect(200, 751, 1200, 802), map(1920, 886, 300, 470, 1800, 545))
    }

    @Test fun mappedBoxNeverLeavesTheTileItCameFrom() {
        forEachPlan { plan, _ ->
            for (tile in plan.tiles) {
                val destination = tile.destination
                val boxes = listOf(
                    destination,
                    GridOcrRect(destination.left, destination.top, destination.right, destination.top + 1),
                    GridOcrRect(destination.left, destination.bottom - 1, destination.right, destination.bottom),
                    GridOcrRect(
                        destination.left + destination.width / 4,
                        destination.top + destination.height / 4,
                        destination.left + 3 * destination.width / 4,
                        destination.top + 3 * destination.height / 4,
                    ),
                )
                for (box in boxes) {
                    val mapped = BrawlerGridOcrRegions.map(plan, box)
                    assertNotNull("box $box of tile ${destination} must map", mapped)
                    assertTrue("mapped box inside tile source", contains(tile.source, mapped!!))
                    assertTrue("mapped box keeps positive area", mapped.width > 0 && mapped.height > 0)
                    // Rounding may only widen by the per-pixel scale step, never clip the request.
                    val sourceWidth = tile.source.width.toLong()
                    val destinationWidth = destination.width.toLong()
                    assertTrue(
                        "mapped box must not be narrower than the exact ratio",
                        mapped.width.toLong() >= (box.width.toLong() * sourceWidth) / destinationWidth,
                    )
                }
            }
        }
    }

    // ---- gaps, cross-tile and out-of-bounds boxes are rejected, never clipped -----------------

    @Test fun boxInsideAGutterIsRejected() {
        assertRejected(1280, 591, 0, 118, 100, 142)
        assertRejected(1280, 591, 0, 119, 100, 141)
        assertRejected(1280, 591, 0, 120, 100, 140)
        // A box that only touches the gutter row is still outside both tiles.
        assertRejected(1280, 591, 0, 118, 100, 119)
        assertRejected(1920, 886, 0, 176, 1000, 177)
        assertRejected(1920, 886, 0, 177, 1000, 200)
    }

    @Test fun boxSpanningTwoTilesIsRejected() {
        assertRejected(1280, 591, 0, 100, 100, 200)
        assertRejected(1280, 591, 0, 0, 100, 393)
        assertRejected(1280, 591, 0, 0, 1248, 393)
        assertRejected(1920, 886, 0, 0, 1766, 552)
        assertRejected(1920, 886, 0, 200, 1872, 435)
    }

    @Test fun boxOutsideTheMosaicIsRejected() {
        assertRejected(1280, 591, 0, 0, 10, 10000)
        assertRejected(1280, 591, 0, 0, 1249, 10)
        assertRejected(1280, 591, 1248, 380, 1300, 400)
        assertRejected(1920, 886, 0, 0, 1873, 100)
    }

    @Test fun negativeCoordinatesAreRejected() {
        assertRejected(1280, 591, -1, 0, 10, 10)
        assertRejected(1280, 591, 0, -1, 10, 10)
        assertRejected(1280, 591, -100, -100, -1, -1)
    }

    @Test fun degenerateBoxesAreRejected() {
        assertRejected(1280, 591, 5, 5, 5, 20)
        assertRejected(1280, 591, 5, 5, 20, 5)
        assertRejected(1280, 591, 5, 5, 4, 19)
        assertRejected(1920, 886, 0, 0, 0, 0)
        assertRejected(1920, 886, 400, 40, 400, 40)
    }

    @Test fun extremeReversedBoxCoordinatesCannotWrapIntoPositiveArea() {
        // Int subtraction wraps for these bounds; ordering must be checked before width/height.
        assertRejected(1280, 591, Int.MAX_VALUE, 142, Int.MIN_VALUE, 143)
        assertRejected(1280, 591, 1, Int.MAX_VALUE, 2, Int.MIN_VALUE)
        assertRejected(1280, 591, Int.MIN_VALUE, 142, Int.MAX_VALUE, 143)
        assertRejected(1280, 591, 1, Int.MIN_VALUE, 2, Int.MAX_VALUE)
    }

    // ---- a forged plan is rejected instead of being mapped through -----------------------------

    @Test fun forgedPlanIsRejectedInsteadOfMapped() {
        val plan = assertPlan(1280, 591)
        val headerBox = GridOcrRect(0, 0, 1176, 118)

        fun withSource(width: Int, height: Int) = plan.copy(sourceWidth = width, sourceHeight = height)
        fun withMosaic(width: Int, height: Int) = plan.copy(width = width, height = height)
        fun withTiles(tiles: List<GridOcrTile>) = plan.copy(tiles = tiles)

        val forged = listOf(
            "wrong source width" to withSource(1281, 591),
            "wrong source height" to withSource(1280, 590),
            "zero source width" to withSource(0, 591),
            "negative source width" to withSource(-5, 591),
            "unsupported source size" to withSource(639, 591),
            "widened mosaic" to withMosaic(plan.width + 1, plan.height),
            "raised mosaic" to withMosaic(plan.width, plan.height + 1),
            "dropped tile" to withTiles(plan.tiles.take(3)),
            "no tiles" to withTiles(emptyList()),
            "narrowed destination" to withTiles(
                plan.tiles.toMutableList().also {
                    it[1] = it[1].copy(destination = GridOcrRect(0, 142, 1247, 212))
                },
            ),
            "dropped gutter" to withTiles(
                plan.tiles.toMutableList().also {
                    it[2] = it[2].copy(destination = GridOcrRect(0, 212, 1248, 284))
                },
            ),
            "shifted destination" to withTiles(
                plan.tiles.toMutableList().also {
                    it[3] = it[3].copy(destination = GridOcrRect(0, 336, 1248, 397))
                },
            ),
            "moved source band" to withTiles(
                plan.tiles.toMutableList().also {
                    it[0] = it[0].copy(source = GridOcrRect(10, 35, 588, 94))
                },
            ),
            "indented destination" to withTiles(
                plan.tiles.toMutableList().also {
                    it[0] = it[0].copy(destination = GridOcrRect(1, 0, 1176, 118))
                },
            ),
        )

        for ((label, candidate) in forged) {
            assertNull("forged plan must not map ($label)", BrawlerGridOcrRegions.map(candidate, headerBox))
        }
        // The untouched plan still maps, so the rejections above are specific.
        assertEquals(GridOcrRect(0, 35, 588, 94), BrawlerGridOcrRegions.map(plan, headerBox))
    }

    // ---- contract boundaries: inclusive lower/upper, unsupported sizes ------------------------

    @Test fun minimumAspectRatioIsAcceptedAndSlightlyTallerIsNot() {
        // Ratio exactly 2.0 is inside the inclusive contract.
        assertPlan(1200, 600)
        assertPlan(1280, 640)
        assertPlan(1280, 639)
        assertPlan(1281, 640)
        assertNull(BrawlerGridOcrRegions.plan(1280, 641))
        assertNull(BrawlerGridOcrRegions.plan(1200, 601))
    }

    @Test fun maximumAspectRatioIsAcceptedAndSlightlyWiderIsNot() {
        val plan = assertPlan(1880, 800)
        assertEquals(1833, plan.width)
        assertEquals(508, plan.height)
        assertPlan(1879, 800)
        assertPlan(1880, 801)
        assertNull(BrawlerGridOcrRegions.plan(1881, 800))
        assertNull(BrawlerGridOcrRegions.plan(1920, 800))
    }

    @Test fun supportedSizeRangeIsInclusive() {
        assertPlan(640, 320)
        assertPlan(4096, 2048)
        assertNull(BrawlerGridOcrRegions.plan(639, 320))
        assertNull(BrawlerGridOcrRegions.plan(4097, 2048))
        assertNull(BrawlerGridOcrRegions.plan(640, 2049))
        assertNull(BrawlerGridOcrRegions.plan(640, 179))
    }

    @Test fun degenerateAndNegativeFramesReturnNull() {
        for ((width, height) in listOf(0 to 0, -1 to 0, 1280 to 0, 1280 to -591, -1280 to -591)) {
            assertNull(
                "frame ${width}x$height must be unsupported",
                BrawlerGridOcrRegions.plan(width, height),
            )
        }
        assertEquals(640, BrawlerGridOcrRegions.MIN_SOURCE_WIDTH)
        assertEquals(4096, BrawlerGridOcrRegions.MAX_SOURCE_WIDTH)
        assertEquals(180, BrawlerGridOcrRegions.MIN_SOURCE_HEIGHT)
        assertEquals(2048, BrawlerGridOcrRegions.MAX_SOURCE_HEIGHT)
    }

    @Test fun planMatchesAnIndependentComputationOfTheContract() {
        // Independent re-derivation of the same contract, so plan() is checked against a second
        // implementation rather than against itself.
        var supported = 0
        var width = BrawlerGridOcrRegions.MIN_SOURCE_WIDTH
        while (width <= BrawlerGridOcrRegions.MAX_SOURCE_WIDTH) {
            var height = BrawlerGridOcrRegions.MIN_SOURCE_HEIGHT
            while (height <= BrawlerGridOcrRegions.MAX_SOURCE_HEIGHT) {
                val expected = width.toLong() * 100L >= height.toLong() * 2L * 100L &&
                    width.toLong() * 100L <= height.toLong() * 235L
                val actual = BrawlerGridOcrRegions.plan(width, height)
                if (expected) {
                    supported++
                    assertNotNull("${width}x$height must be supported", actual)
                    assertEquals(width.toLong() * 65L / 100L * 3L / 2L, actual!!.width.toLong())
                    assertTrue("mosaic taller than source for ${width}x$height", actual.height <= 1188)
                } else {
                    assertNull("${width}x$height must be unsupported", actual)
                }
                height += 11
            }
            width += 13
        }
        assertTrue("sweep must exercise supported frames", supported > 4000)
    }

    @Test fun mosaicAllocationIsBoundedByTheContractMaximum() {
        val largest = assertPlan(4096, 2048)
        assertEquals(3993, largest.width)
        assertEquals(1188, largest.height)
        // One bitmap row of the biggest permitted mosaic, used as the allocation ceiling.
        val bounded = largest.width * largest.height
        assertTrue("mosaic must stay within the contract budget", bounded <= 3993 * 1188)
        assertEquals(
            "height is the tile heights plus three gutters, nothing more",
            largest.tiles.sumOf { it.destination.height.toLong() } + 3L * BrawlerGridOcrRegions.GUTTER,
            largest.height.toLong(),
        )
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun assertPlan(width: Int, height: Int): GridOcrPlan {
        val plan = BrawlerGridOcrRegions.plan(width, height)
        assertNotNull("plan for ${width}x$height must exist", plan)
        return plan!!
    }

    private fun map(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): GridOcrRect? =
        BrawlerGridOcrRegions.map(assertPlan(width, height), GridOcrRect(left, top, right, bottom))

    private fun assertRejected(width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int) {
        assertNull(
            "box ${left},$top,$right,$bottom must not be accepted for ${width}x$height",
            map(width, height, left, top, right, bottom),
        )
    }

    /** Runs [block] for the two supplied captures plus the contract corners. */
    private inline fun forEachPlan(block: (GridOcrPlan, String) -> Unit) {
        for ((width, height) in listOf(1280 to 591, 1920 to 886, 1280 to 640, 1880 to 800, 4096 to 2048)) {
            block(assertPlan(width, height), "${width}x$height")
        }
    }

    private fun maxRight(plan: GridOcrPlan): Int =
        plan.tiles.maxOf { it.destination.right }

    private fun contains(outer: GridOcrRect, inner: GridOcrRect): Boolean =
        inner.left >= outer.left &&
            inner.top >= outer.top &&
            inner.right <= outer.right &&
            inner.bottom <= outer.bottom
}