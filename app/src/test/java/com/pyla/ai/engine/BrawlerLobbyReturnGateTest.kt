package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit evidence for the post-SELECT returned-lobby contract.
 *
 * Everything exercised here is production code ([BrawlerLobbyReturnGate], [BrawlerSelectionGate],
 * [LobbyMenuEntryGate], [BrawlerGridOcrRegions] types) driven with synthetic captures of the
 * measured geometry: 1280x591, `BRAWLERS` around x 75..150 / y 300 and `PLAY` around x 1090 / y 530.
 * No Android type, no recognizer and no device is involved.
 */
class BrawlerLobbyReturnGateTest {

    private val width = 1280
    private val height = 591
    private val permille = 800

    private fun plan(permille: Int = this.permille) =
        BrawlerLobbyReturnGate.plan(width, height, permille)!!

    /** The measured lobby control boxes: BRAWLERS on the left, PLAY bottom-right. */
    private fun brawlersHit(text: String = "BRAWLERS") = BrawlerOcrHit(text, 75, 300, 150, 322)
    private fun playHit(text: String = "PLAY") = BrawlerOcrHit(text, 1060, 518, 1120, 540)

    private fun scan(
        hits: List<BrawlerOcrHit>,
        capturedAtMs: Long = 1_000L,
        w: Int = width,
        h: Int = height,
    ) = BrawlerOcrScan(w, h, capturedAtMs, hits)

    private fun frame(
        capturedAtMs: Long,
        w: Int = width,
        h: Int = height,
        fill: (x: Int, y: Int) -> Int = { _, _ -> 0xff303030.toInt() },
    ) = FrameSnapshot(w, h, IntArray(w * h) { index -> fill(index % w, index / w) }, capturedAtMs)

    @Test fun identicalCompleteRenderedInputMayReuseWithoutRenewingOriginalAge() {
        val p = plan(); val pixels = IntArray(p.width * p.height) { it }
        val cache = BrawlerLobbyReturnGate.OcrReuse()
        cache.remember(scan(listOf(brawlersHit(), playHit())), p, pixels, 1_500)
        val first = cache.reuse(frame(2_000), p, pixels.clone(), 2_000)
        assertEquals(2_000L, first!!.capturedAtMs)
        assertEquals(listOf(brawlersHit(), playHit()), first.hits)
        assertNotNull(cache.reuse(frame(4_000), p, pixels.clone(), 4_000))
        assertNull(cache.reuse(frame(4_001), p, pixels.clone(), 4_001))
    }

    @Test fun anyChangedRenderedEvidenceForcesOcrEvenAwayFromAcceptedLabels() {
        val p = plan(); val pixels = IntArray(p.width * p.height)
        for (index in listOf(0, p.width - 1, pixels.size / 2, pixels.lastIndex)) {
            val cache = BrawlerLobbyReturnGate.OcrReuse()
            cache.remember(scan(listOf(brawlersHit(), playHit())), p, pixels, 1_100)
            val changed = pixels.clone(); changed[index] = 1
            assertNull(cache.reuse(frame(1_200), p, changed, 1_200))
            assertNull(cache.reuse(frame(1_300), p, pixels, 1_300)) // miss invalidates prior authority
        }
    }

    @Test fun retainedRenderedPixelsAreNotAliasedToMutableCallerBuffer() {
        val p = plan(); val pixels = IntArray(p.width * p.height)
        val cache = BrawlerLobbyReturnGate.OcrReuse()
        cache.remember(scan(listOf(brawlersHit(), playHit())), p, pixels, 1_100)
        pixels[0] = 7
        assertNull(cache.reuse(frame(1_200), p, pixels, 1_200))
    }

    @Test fun reuseRejectsNonIncreasingFutureAndExpiredCaptureStamps() {
        val p = plan(); val pixels = IntArray(p.width * p.height)
        for ((stamp, now) in listOf(1_000L to 1_200L, 999L to 1_200L,
            1_301L to 1_300L, 1_001L to 4_001L, 1_200L to 999L)) {
            val cache = BrawlerLobbyReturnGate.OcrReuse()
            cache.remember(scan(listOf(brawlersHit(), playHit())), p, pixels, 1_100)
            assertNull(cache.reuse(frame(stamp), p, pixels, now))
        }
    }

    @Test fun reuseRejectsViewportScaleAndExplicitPhaseReset() {
        val p = plan(); val pixels = IntArray(p.width * p.height)
        for (mode in 0..2) {
            val cache = BrawlerLobbyReturnGate.OcrReuse()
            cache.remember(scan(listOf(brawlersHit(), playHit())), p, pixels, 1_100)
            if (mode == 2) cache.clear()
            assertNull(cache.reuse(frame(1_200, w = if (mode == 0) width - 1 else width),
                if (mode == 1) plan(500) else p, pixels, 1_200))
        }
    }

    @Test fun malformedMissingDuplicateOrExpiredScansCannotSeedReuse() {
        val p = plan(); val pixels = IntArray(p.width * p.height)
        for (bad in listOf(scan(emptyList()), scan(listOf(brawlersHit())),
            scan(listOf(brawlersHit(), playHit(), playHit())),
            scan(listOf(brawlersHit(), playHit()), capturedAtMs = 1),
            scan(listOf(brawlersHit(), playHit()), capturedAtMs = 5_000))) {
            val cache = BrawlerLobbyReturnGate.OcrReuse()
            cache.remember(bad, p, pixels, 4_000)
            assertNull(cache.reuse(frame(4_001), p, pixels, 4_001))
        }
    }

    // ---------------------------------------------------------------- plan geometry

    @Test fun planPlacesTwoDisjointObservationRegionsAtMeasuredPositions() {
        val p = plan()
        val brawlers = BrawlerLobbyReturnGate.region(p, BrawlerLobbyReturnGate.Control.BRAWLERS)!!
        val play = BrawlerLobbyReturnGate.region(p, BrawlerLobbyReturnGate.Control.PLAY)!!
        // x 75..150 / y 300 lies inside the left band; x 1090 / y 530 inside the right band.
        assertTrue(brawlersHit().left >= brawlers.left && brawlersHit().right <= brawlers.right)
        assertTrue(brawlersHit().top >= brawlers.top && brawlersHit().bottom <= brawlers.bottom)
        assertTrue(playHit().left >= play.left && playHit().right <= play.right)
        assertTrue(playHit().top >= play.top && playHit().bottom <= play.bottom)
        // Disjoint: one control can never be mistaken for the other, nor clipped between them.
        assertTrue(brawlers.right <= play.left)
        assertTrue(brawlers.bottom <= play.top)
    }

    @Test fun planRejectsUnsupportedCapturesAndScales() {
        assertNull(BrawlerLobbyReturnGate.plan(0, height, permille))
        assertNull(BrawlerLobbyReturnGate.plan(width, 0, permille))
        assertNull(BrawlerLobbyReturnGate.plan(320, 100, permille))       // too small
        assertNull(BrawlerLobbyReturnGate.plan(8192, 1024, permille))     // too wide
        assertNull(BrawlerLobbyReturnGate.plan(1280, 900, permille))      // aspect out of contract
        assertNull(BrawlerLobbyReturnGate.plan(width, height, 0))         // scale below 10%
        assertNull(BrawlerLobbyReturnGate.plan(width, height, 1_001))     // scale above 100%
    }

    @Test fun mappedCoordinatesAreInverseOfTheMosaicInFullFrameSpace() {
        val p = plan()
        for (control in listOf(
            BrawlerLobbyReturnGate.Control.BRAWLERS, BrawlerLobbyReturnGate.Control.PLAY
        )) {
            val area = BrawlerLobbyReturnGate.region(p, control)!!
            val tile = p.tiles.first { it.source == area }
            // The whole tile maps back onto its own capture rectangle, to the pixel.
            val whole = GridOcrRect(
                tile.destination.left, tile.destination.top, tile.destination.right, tile.destination.bottom,
            )
            assertEquals(area, BrawlerLobbyReturnGate.map(p, whole))
            // A half-tile box maps strictly inside the capture region, never outside it.
            val inner = BrawlerLobbyReturnGate.map(
                p,
                GridOcrRect(
                    tile.destination.left + 2, tile.destination.top + 2,
                    tile.destination.right - 2, tile.destination.bottom - 2,
                ),
            )!!
            assertTrue(inner.left >= area.left && inner.right <= area.right)
            assertTrue(inner.top >= area.top && inner.bottom <= area.bottom)
            assertTrue(inner.width > 0 && inner.height > 0)
        }
    }

    @Test fun unmappableBoxesAreRejectedRatherThanClippedIntoANeighbour() {
        val p = plan()
        val between = GridOcrRect(0, 0, p.width, p.height)                  // bridges both tiles
        assertNull(BrawlerLobbyReturnGate.map(p, between))
        val inGutter = GridOcrRect(
            0, p.tiles[0].destination.bottom + 1,
            p.tiles[0].destination.right, p.tiles[0].destination.bottom + 5,
        )
        assertNull(BrawlerLobbyReturnGate.map(p, inGutter))
        assertNull(BrawlerLobbyReturnGate.map(p, GridOcrRect(4, 4, 4, 9)))   // empty
        assertNull(BrawlerLobbyReturnGate.map(p, GridOcrRect(9, 4, 4, 9)))   // reversed
        assertNull(BrawlerLobbyReturnGate.map(p, GridOcrRect(-1, 0, 4, 4)))  // negative
        assertNull(BrawlerLobbyReturnGate.map(p, GridOcrRect(0, 0, p.width + 1, 4)))
    }

    @Test fun mutatedPlanIsNeverMappedThrough() {
        val p = plan()
        assertNull(BrawlerLobbyReturnGate.map(p.copy(width = p.width + 1), GridOcrRect(0, 0, 10, 10)))
        assertNull(BrawlerLobbyReturnGate.map(p.copy(height = p.height + 1), GridOcrRect(0, 0, 10, 10)))
        assertNull(BrawlerLobbyReturnGate.map(p.copy(scalePermille = 500), GridOcrRect(0, 0, 10, 10)))
        assertNull(
            BrawlerLobbyReturnGate.map(
                p.copy(tiles = p.tiles.dropLast(1)), GridOcrRect(0, 0, 10, 10),
            ),
        )
        val moved = p.tiles.toMutableList()
        moved[0] = moved[0].copy(destination = moved[0].destination.copy(left = 1))
        assertNull(BrawlerLobbyReturnGate.map(p.copy(tiles = moved), GridOcrRect(0, 0, 10, 10)))
        assertNull(
            BrawlerLobbyReturnGate.region(
                p.copy(sourceWidth = 1920), BrawlerLobbyReturnGate.Control.BRAWLERS,
            ),
        )
    }

    // ---------------------------------------------------------------- exact labels only

    @Test fun onlyExactFixedControlLabelsAreAccepted() {
        assertTrue(BrawlerLobbyReturnGate.isBrawlersLabel("BRAWLERS"))
        assertTrue(BrawlerLobbyReturnGate.isBrawlersLabel("brawlers"))
        assertTrue(BrawlerLobbyReturnGate.isBrawlersLabel("Lottatori"))
        assertTrue(BrawlerLobbyReturnGate.isPlayLabel("PLAY"))
        assertTrue(BrawlerLobbyReturnGate.isPlayLabel("Spielen"))
        assertTrue(BrawlerLobbyReturnGate.isPlayLabel("jouer"))
        // Surrounding whitespace and separator punctuation normalize away under the existing
        // selector-wide normalizer, exactly as for every other label this selector compares.
        assertTrue(BrawlerLobbyReturnGate.isPlayLabel(" PLAY "))
        assertTrue(BrawlerLobbyReturnGate.isPlayLabel("Play!"))
        // No substring, no superstring: a different word is never a control.
        for (candidate in listOf("PLAYER", "PLAY AGAIN", "REPLAY", "PLAYING", "1 PLAY", "REPLAYING")) {
            assertFalse(candidate, BrawlerLobbyReturnGate.isPlayLabel(candidate))
        }
        for (candidate in listOf("BRAWLERS 24", "SUPERBRAWLERS", "BRAWLERLIST", "NORI", "")) {
            assertFalse(candidate, BrawlerLobbyReturnGate.isBrawlersLabel(candidate))
        }
    }

    @Test fun bothControlsMustBePresentInTheirOwnRegion() {
        val p = plan()
        val both = BrawlerLobbyReturnGate.uniqueControls(scan(listOf(brawlersHit(), playHit())), p)!!
        assertEquals(listOf("BRAWLERS", "PLAY"), both.map { it.text })
        // Missing PLAY, or missing BRAWLERS, is not a lobby control proof.
        assertNull(BrawlerLobbyReturnGate.uniqueControls(scan(listOf(brawlersHit())), p))
        assertNull(BrawlerLobbyReturnGate.uniqueControls(scan(listOf(playHit())), p))
        assertNull(BrawlerLobbyReturnGate.uniqueControls(scan(emptyList()), p))
    }

    @Test fun aControlOutsideItsOwnRegionIsNeverAccepted() {
        val p = plan()
        // Both labels present, but both in the left band: PLAY is not a left-band control.
        val swapped = listOf(brawlersHit(), BrawlerOcrHit("PLAY", 76, 340, 120, 360))
        assertNull(BrawlerLobbyReturnGate.uniqueControls(scan(swapped), p))
        // A label straddling its own region edge is rejected instead of clipped in.
        val area = BrawlerLobbyReturnGate.region(p, BrawlerLobbyReturnGate.Control.PLAY)!!
        val straddling = listOf(
            brawlersHit(), BrawlerOcrHit("PLAY", area.left - 10, area.top + 2, area.left + 20, area.top + 24),
        )
        assertNull(BrawlerLobbyReturnGate.uniqueControls(scan(straddling), p))
    }

    @Test fun candidateOrCardLabelsNeverAuthorizeTheControls() {
        val p = plan()
        // Only the two exact fixed control words authorize: a card, candidate or unrelated lobby
        // caption - including one that merely CONTAINS a control word - is never a match, so it can
        // neither stand in for a control nor add unresolvable evidence to its region.
        for (candidate in listOf("PLAYER", "PLAY AGAIN", "REPLAY", "BRAWLERS 24", "SUPERBRAWLERS")) {
            assertNull(candidate, BrawlerLobbyReturnGate.uniqueControls(
                scan(listOf(brawlersHit(), BrawlerOcrHit(candidate, 1070, 520, 1140, 545))), p,
            ))
        }
        // Unrelated lobby text alone authorizes nothing.
        assertNull(
            BrawlerLobbyReturnGate.uniqueControls(scan(listOf(BrawlerOcrHit("NORI", 400, 400, 470, 430))), p),
        )
        // A second, different control caption in the same region is refused rather than resolved.
        val twoPlay = listOf(brawlersHit(), playHit(), BrawlerOcrHit("Play", 1150, 560, 1190, 580))
        assertNull(BrawlerLobbyReturnGate.uniqueControls(scan(twoPlay), p))
        // A label that only partly enters its region is refused, never clipped into it.
        val area = BrawlerLobbyReturnGate.region(p, BrawlerLobbyReturnGate.Control.PLAY)!!
        val straddling = listOf(
            brawlersHit(), BrawlerOcrHit("PLAY", area.left - 10, area.top + 2, area.left + 20, area.top + 24),
        )
        assertNull(BrawlerLobbyReturnGate.uniqueControls(scan(straddling), p))
    }

    @Test fun duplicateControlLabelsInOneScanVetoTheFrame() {
        val p = plan()
        val duplicate = listOf(brawlersHit(), brawlersHit("brawlers"), playHit())
        assertNull(BrawlerLobbyReturnGate.uniqueControls(scan(duplicate), p))
        val duplicatePlay = listOf(brawlersHit(), playHit(), playHit("PLAY"))
        assertNull(BrawlerLobbyReturnGate.uniqueControls(scan(duplicatePlay), p))
    }

    @Test fun overlappingDistinctLinesAreStillAmbiguous() {
        val a = brawlersHit()
        val b = a.copy(right = a.right + 4, bottom = a.bottom + 3)
        assertNull(BrawlerLobbyReturnGate.uniqueControls(scan(listOf(a, b, playHit())), plan()))
    }

    @Test fun threeDuplicatesAndUnmappedLabelsVeto() {
        val a = brawlersHit()
        val b = a.copy(top = a.top + 2, bottom = a.bottom + 2)
        val c = a.copy(top = a.top + 40, bottom = a.bottom + 40)
        assertNull(BrawlerLobbyReturnGate.uniqueControls(scan(listOf(a, b, c, playHit())), plan()))
        assertNull(BrawlerLobbyReturnGate.uniqueControls(scan(listOf(a, playHit())), plan(), true))
    }

    @Test fun foreignOrMalformedDuplicateCannotDisappear() {
        val good = listOf(brawlersHit(), playHit())
        for (extra in listOf(BrawlerOcrHit("BRAWLERS", 1060, 520, 1120, 540),
            BrawlerOcrHit("PLAY", Int.MAX_VALUE, 520, Int.MIN_VALUE, 540),
            BrawlerOcrHit("PLAY", -1, 520, 1120, 540))) {
            assertNull(BrawlerLobbyReturnGate.uniqueControls(scan(good + extra), plan()))
        }
        assertNotNull(BrawlerLobbyReturnGate.plan(1920, 1080, 800))
    }

    // ---------------------------------------------------------------- cross-frame agreement

    @Test fun controlsMustAgreeAcrossTheTwoCaptures() {
        val tolerance = BrawlerSelectionGate.tolerancePx(height)
        val base = listOf(brawlersHit(), playHit())
        assertTrue(BrawlerLobbyReturnGate.agree(height, base, base))
        val shifted = listOf(base[0].copy(left = base[0].left + tolerance + 1), base[1])
        assertFalse(BrawlerLobbyReturnGate.agree(height, base, shifted))
        val wordChanged = listOf(base[0], playHit("Spielen"))
        assertFalse(BrawlerLobbyReturnGate.agree(height, base, wordChanged))
        val swappedOrder = listOf(base[1], base[0])
        assertFalse(BrawlerLobbyReturnGate.agree(height, base, swappedOrder))
        assertFalse(BrawlerLobbyReturnGate.agree(height, base, base.dropLast(1)))
        assertFalse(BrawlerLobbyReturnGate.agree(0, base, base))
    }

    @Test fun staleEqualOrRegressedTimestampsAreRefused() {
        val p = plan()
        val hits = listOf(brawlersHit(), playHit())
        val first = scan(hits, capturedAtMs = 1_000L)
        val later = scan(hits, capturedAtMs = 1_200L)
        assertTrue(BrawlerLobbyReturnGate.distinctFreshPair(first, later, nowMs = 1_300L))
        // Equal / regressed timestamps are a reused capture, not two fresh frames.
        assertFalse(BrawlerLobbyReturnGate.distinctFreshPair(first, first, nowMs = 1_300L))
        assertFalse(BrawlerLobbyReturnGate.distinctFreshPair(later, first, nowMs = 1_300L))
        // Zero / negative capture time.
        assertFalse(BrawlerLobbyReturnGate.distinctFreshPair(scan(hits, 0L), later, 1_300L))
        assertFalse(BrawlerLobbyReturnGate.distinctFreshPair(scan(hits, -5L), later, 1_300L))
        // From the future, stale beyond budget, or separated by more than the gap budget.
        assertFalse(BrawlerLobbyReturnGate.distinctFreshPair(first, later, nowMs = 900L))
        assertFalse(BrawlerLobbyReturnGate.distinctFreshPair(first, later, nowMs = 1_000L + 3_001L))
        assertFalse(
            BrawlerLobbyReturnGate.distinctFreshPair(
                first, scan(hits, capturedAtMs = 5_001L), nowMs = 5_100L,
            ),
        )
        assertFalse(BrawlerLobbyReturnGate.distinctFreshPair(first, later, nowMs = -1L))
        // Geometry change between the captures.
        assertFalse(BrawlerLobbyReturnGate.distinctFreshPair(first, later.copy(width = 1920), 1_300L))
        // A plan built for a different capture never reads this one.
        val otherPlan = BrawlerLobbyReturnGate.plan(1920, 960, permille)!!
        assertNull(BrawlerLobbyReturnGate.uniqueControls(first, otherPlan))
    }

    // ---------------------------------------------------------------- bounded pixel windows

    @Test fun controlWindowsDeriveFromBothObservedLabels() {
        val controls = listOf(brawlersHit(), playHit())
        val windows = BrawlerLobbyReturnGate.controlWindows(width, height, controls)!!
        assertEquals(2, windows.size)
        val padding = BrawlerSelectionGate.tolerancePx(height)
        for (index in controls.indices) {
            val hit = controls[index]
            val bounds = windows[index]
            assertEquals(hit.left - padding, bounds.left)
            assertEquals(hit.top - padding, bounds.top)
            assertEquals(hit.right + padding, bounds.right)
            assertEquals(hit.bottom + padding, bounds.bottom)
        }
        assertNull(BrawlerLobbyReturnGate.controlWindows(width, height, controls.dropLast(1)))
    }

    @Test fun animationOutsideBothControlsDoesNotBlockTheHandoff() {
        val controls = listOf(brawlersHit(), playHit())
        val windows = BrawlerLobbyReturnGate.controlWindows(width, height, controls)!!
        // The lobby character occupies the centre and changes completely between captures.
        val a = frame(1_000L) { _, _ -> 0xff202020.toInt() }
        val b = frame(1_200L) { x, y ->
            if (x in 400..900 && y in 150..470) 0xff4466aa.toInt() else 0xff202020.toInt()
        }
        assertTrue(BrawlerLobbyReturnGate.controlsStill(a, b, windows))
    }

    @Test fun movementInsideEitherControlBlocksTheHandoff() {
        val controls = listOf(brawlersHit(), playHit())
        val windows = BrawlerLobbyReturnGate.controlWindows(width, height, controls)!!
        val base = frame(1_000L) { _, _ -> 0xff202020.toInt() }
        for (index in controls.indices) {
            val hit = controls[index]
            val moved = frame(1_200L) { x, y ->
                if (x >= hit.left && x < hit.right && y >= hit.top && y < hit.bottom) {
                    0xffffffff.toInt()
                } else {
                    0xff202020.toInt()
                }
            }
            assertFalse(
                "control $index moving must refuse the handoff",
                BrawlerLobbyReturnGate.controlsStill(base, moved, windows),
            )
        }
    }

    @Test fun malformedCapturesAndWindowsFailClosed() {
        val controls = listOf(brawlersHit(), playHit())
        val windows = BrawlerLobbyReturnGate.controlWindows(width, height, controls)!!
        val good = frame(1_000L)
        assertFalse(BrawlerLobbyReturnGate.controlsStill(good, frame(1_200L, w = 1920, h = 1080), windows))
        assertFalse(
            BrawlerLobbyReturnGate.controlsStill(
                FrameSnapshot(width, height, IntArray(width * height - 1), 1_000L), good, windows,
            ),
        )
        assertFalse(BrawlerLobbyReturnGate.controlsStill(good, good, emptyList()))
        assertFalse(
            BrawlerLobbyReturnGate.controlsStill(good, good, listOf(LobbyMenuEntryGate.Bounds(0, 0, 0, 0))),
        )
    }

    @Test fun fullPositivePathStaysInsideTheExistingBudgets() {
        // The budgets the handoff runs under are the selector's own unchanged ones.
        assertEquals(3_000L, BrawlerLobbyReturnGate.MAX_AGE_MS)
        assertEquals(4_000L, BrawlerLobbyReturnGate.MAX_GAP_MS)
        assertEquals(2, BrawlerLobbyReturnGate.REGION_COUNT)
        assertNotNull(plan())
    }
}