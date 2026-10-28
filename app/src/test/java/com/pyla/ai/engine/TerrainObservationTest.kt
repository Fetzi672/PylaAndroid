package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test

class TerrainObservationTest {
    private fun box(x: Float) = floatArrayOf(x, 2f, x + 1f, 3f)
    @Test fun waterFenceAndBoundsAreMovementOnly() {
        val input = mapOf("wall" to listOf(box(1f)), "bush" to listOf(box(2f)),
            "water" to listOf(box(3f)), "rope_fence" to listOf(box(4f)), "out_of_bounds" to listOf(box(5f)))
        val t = TerrainObservation.fromDetections(input, true)
        assertEquals(1, t.walls.size); assertEquals(1f, t.walls.single()[0])
        assertEquals(listOf(1f, 3f, 4f, 5f), t.movementObstacles(t.walls).map { it[0] })
        assertEquals(listOf(2f), t.bushes.map { it[0] })
    }
    @Test fun cachedPopulationRestoresEveryTerrainClassOnEachFrame() {
        val t = TerrainObservation(waters = listOf(box(9f)), ropeFences = listOf(box(8f)), outOfBounds = listOf(box(7f)))
        val first = hashMapOf<String, MutableList<FloatArray>>(); t.populate(first)
        first.getValue("water").single()[0] = 100f
        val next = hashMapOf<String, MutableList<FloatArray>>(); t.populate(next)
        assertEquals(9f, next.getValue("water").single()[0]); assertEquals(8f, next.getValue("rope_fence").single()[0])
        assertEquals(7f, next.getValue("out_of_bounds").single()[0]); assertTrue(next.getValue("wall").isEmpty())
    }
    @Test fun legacyCloseModelClassConventionIsUnchanged() {
        val t = TerrainObservation.fromDetections(mapOf("close_bush" to listOf(box(1f)), "wall" to listOf(box(2f))), false)
        assertEquals(1, t.bushes.size); assertEquals(1, t.walls.size); assertTrue(t.waters.isEmpty())
    }
    @Test(expected = IllegalArgumentException::class) fun unknownSemanticOutputDoesNotBecomeAWall() {
        TerrainObservation.fromDetections(mapOf("unexpected" to listOf(box(1f))), true)
    }
}

class IntegratedAdmissionBoundaryTest {
    private fun obs(t: Long, state: String = "brawler_selection") = MenuBackAdmission.Observation(state, true, true, false, t, 1920, 1080)
    @Test fun nonMenuLabelCannotObtainBackTicket() {
        val gate = MenuBackAdmission(); assertNull(gate.observe(obs(1000, "unknown"), 1100)); assertNull(gate.observe(obs(1200, "unknown"), 1250))
    }
    @Test fun firstFrameMustStillBeFreshAtPairAdmission() {
        val gate = MenuBackAdmission(); assertNull(gate.observe(obs(1000), 1100))
        assertNull(gate.observe(obs(2000), 3100))
    }
    @Test fun firstFrameExpiryAlsoRevokesQueuedTicket() {
        val gate = MenuBackAdmission(); gate.observe(obs(1000), 1100)
        val ticket = gate.observe(obs(2000), 2100)!!
        assertTrue(gate.allows(ticket, 2200)); assertFalse(gate.allows(ticket, 3100))
    }
    @Test fun tinyFiniteDirectionNormalizationCannotOverflow() {
        val v = PlaystyleContract.normalizeMove(1e-320, 0.0, 75.0)
        assertTrue(v.isFinite); assertEquals(75.0, v.x, 1e-6); assertEquals(0.0, v.y, 0.0)
    }
    @Test fun malformedSemanticConfidenceFailsClosed() {
        val m = SemanticTerrainManifest(8, 8, listOf("wall", "bush", "rope_fence", "water", "out_of_bounds"))
        val values = FloatArray(64) { 0.9f }; values[0] = Float.NaN
        try { SemanticTerrainDecoder().decode(m, ByteArray(64), values, 8, 8, 8, 8, 4.0, 0.5f); fail("must reject") }
        catch (_: IllegalArgumentException) { }
    }
}
