package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic in-memory stand-in for the durable Android storage used by the facade. */
class FakeCalibrationStorage(private var value: String? = null) : AttackCalibrationStorage {
    var writes = 0
    var removals = 0
    override fun read(): String? = value
    override fun write(value: String) {
        writes++
        this.value = value
    }
    override fun remove(): Boolean {
        removals++
        val existed = value != null
        value = null
        return existed
    }
}

class AttackCalibrationCoreTest {

    private val w = 1080
    private val h = 2400
    private val g = 7L
    private val point = AttackButtonPoint(800f, 2000f, 90f)

    private fun core(storage: FakeCalibrationStorage = FakeCalibrationStorage()) =
        SavedAttackButtonCalibrationCore(storage) to storage

    private fun learn(c: SavedAttackButtonCalibrationCore, lease: AttackCalibrationLease, p: AttackButtonPoint = point): AttackButtonPoint? {
        assertNull(c.observeTemplate(lease, p, 1_000L, 1_000L))
        return c.observeTemplate(lease, p, 1_500L, 1_500L)
    }

    @Test
    fun beginIsStableAcrossFramesWithSameGeometry() {
        val (c, _) = core()
        val first = c.begin(w, h, g)
        val second = c.begin(w, h, g)
        assertEquals(first, second)
        assertEquals(c.revision(), first.revision)
        assertNull(c.saved(first))
    }

    @Test
    fun requiresTwoDistinctFreshFrames() {
        val (c, storage) = core()
        val lease = c.begin(w, h, g)
        assertNull(c.observeTemplate(lease, point, 1_000L, 1_000L))
        assertFalse(c.hasSaved())
        assertEquals(0, storage.writes)
        assertNotNull(c.observeTemplate(lease, point, 1_500L, 1_500L))
        assertEquals(point, c.saved(lease))
        assertTrue(c.hasSaved())
        assertEquals(1, storage.writes)
    }

    @Test
    fun repeatedFrameNeverConfirms() {
        val (c, _) = core()
        val lease = c.begin(w, h, g)
        assertNull(c.observeTemplate(lease, point, 1_000L, 1_000L))
        assertNull(c.observeTemplate(lease, point, 1_000L, 1_400L))
        assertFalse(c.hasSaved())
        assertNotNull(c.observeTemplate(lease, point, 1_001L, 1_001L))
    }

    @Test
    fun secondFrameBeyondMaxAgeResetsPair() {
        val (c, _) = core()
        val lease = c.begin(w, h, g)
        assertNull(c.observeTemplate(lease, point, 1_000L, 1_000L))
        assertNull(c.observeTemplate(lease, point, 4_100L, 4_100L))
        assertFalse(c.hasSaved())
        assertNotNull(c.observeTemplate(lease, point, 4_200L, 4_200L))
    }

    @Test
    fun staleOrFutureCaptureTimeIsRejected() {
        val (c, _) = core()
        val lease = c.begin(w, h, g)
        assertNull(c.observeTemplate(lease, point, 1_000L, 4_001L))
        assertNull(c.observeTemplate(lease, point, 2_000L, 1_999L))
        assertNull(c.observeTemplate(lease, point, 0L, 10L))
        assertFalse(c.hasSaved())
    }

    @Test
    fun nullObservationResetsPendingPair() {
        val (c, _) = core()
        val lease = c.begin(w, h, g)
        assertNull(c.observeTemplate(lease, point, 1_000L, 1_000L))
        assertNull(c.observeTemplate(lease, null, 1_100L, 1_100L))
        assertNull(c.observeTemplate(lease, point, 1_200L, 1_200L))
        assertFalse(c.hasSaved())
        assertNotNull(c.observeTemplate(lease, point, 1_300L, 1_300L))
    }

    @Test
    fun inconsistentCenterOrRadiusIsNotConfirmed() {
        val (c, _) = core()
        val lease = c.begin(w, h, g)
        assertNull(c.observeTemplate(lease, point, 1_000L, 1_000L))
        assertNull(c.observeTemplate(lease, point.copy(x = 900f), 1_100L, 1_100L))
        assertNull(c.observeTemplate(lease, point.copy(radius = 200f), 1_200L, 1_200L))
        assertFalse(c.hasSaved())
        assertNull(c.observeTemplate(lease, point, 1_300L, 1_300L))
        assertNotNull(c.observeTemplate(lease, point, 1_400L, 1_400L))
    }

    @Test
    fun outOfOrderFrameRestartsPair() {
        val (c, _) = core()
        val lease = c.begin(w, h, g)
        assertNull(c.observeTemplate(lease, point, 2_000L, 2_000L))
        assertNull(c.observeTemplate(lease, point, 1_900L, 2_000L))
        assertFalse(c.hasSaved())
        assertNotNull(c.observeTemplate(lease, point, 2_100L, 2_100L))
    }

    @Test
    fun pointOutsideCaptureOrNotLowerRightIsRejected() {
        val (c, _) = core()
        val lease = c.begin(w, h, g)
        val offScreen = AttackButtonPoint(1_070f, 2_000f, 90f)
        val upper = AttackButtonPoint(300f, 400f, 90f)
        val negative = AttackButtonPoint(-5f, 2_000f, 90f)
        listOf(offScreen, upper, negative).forEach { p ->
            assertNull(c.observeTemplate(lease, p, 1_000L, 1_000L))
            assertNull(c.observeTemplate(lease, p, 1_100L, 1_100L))
        }
        assertFalse(c.hasSaved())
    }

    @Test
    fun changedSizeOrGeometryInvalidatesPendingAndLease() {
        val (c, _) = core()
        val lease = c.begin(w, h, g)
        assertNull(c.observeTemplate(lease, point, 1_000L, 1_000L))
        val resized = c.begin(720, 1600, g)
        assertTrue(resized.revision > lease.revision)
        assertNull(c.saved(lease))
        assertNull(c.observeTemplate(lease, point, 1_100L, 1_100L))
        assertFalse(c.hasSaved())
        val regrown = c.begin(w, h, g)
        assertTrue(regrown.revision > resized.revision)
        assertNull(c.observeTemplate(regrown, point, 1_200L, 1_200L))
        assertNotNull(c.observeTemplate(regrown, point, 1_300L, 1_300L))
    }

    @Test
    fun savedPointIsBoundToExactDimensionsWithoutScaling() {
        val (c, _) = core()
        val lease = c.begin(w, h, g)
        assertNotNull(learn(c, lease))
        assertNull(c.saved(lease.copy(width = 540, height = 1200)))
        assertNull(c.saved(lease.copy(geometry = 8L)))
        assertNull(c.saved(lease.copy(revision = lease.revision + 1)))
        assertNull(c.saved(lease.copy(height = h + 1)))
    }

    @Test
    fun resetObservationsKeepsSavedRecordButRevokesLease() {
        val (c, storage) = core()
        val lease = c.begin(w, h, g)
        assertNotNull(learn(c, lease))
        c.resetObservations()
        assertTrue(c.hasSaved())
        assertNull(c.saved(lease))
        assertNull(c.observeTemplate(lease, point, 2_000L, 2_000L))
        assertNotNull(storage.read())
        assertFalse(requireNotNull(storage.read()).isEmpty())
        val fresh = c.begin(w, h, g)
        assertTrue(fresh.revision > lease.revision)
        assertEquals(point, c.saved(fresh))
    }

    @Test
    fun clearRevokesLeasesAndOldObserveCannotResurrect() {
        val (c, storage) = core()
        val lease = c.begin(w, h, g)
        assertNotNull(learn(c, lease))
        assertTrue(c.clear())
        assertFalse(c.hasSaved())
        assertFalse(c.current(lease))
        assertNull(storage.read())
        assertNull(c.observeTemplate(lease, point, 2_000L, 2_000L))
        assertNull(c.observeTemplate(lease, point, 2_100L, 2_100L))
        assertFalse(c.hasSaved())
        val fresh = c.begin(w, h, g)
        assertNull(c.saved(fresh))
        assertNull(c.observeTemplate(fresh, point, 2_200L, 2_200L))
        assertNotNull(c.observeTemplate(fresh, point, 2_300L, 2_300L))
    }

    @Test
    fun clearOnEmptyStoreReportsNoRecordAndStillRevokes() {
        val (c, _) = core()
        assertFalse(c.clear())
        val lease = c.begin(w, h, g)
        c.resetObservations()
        assertNull(c.saved(lease))
    }

    @Test
    fun currentMeansValidLeaseEvenWhenLearningHasNotSavedAnything() {
        val (c, _) = core()
        val lease = c.begin(w, h, g)
        assertTrue(c.current(lease))
        assertNull(c.saved(lease))
        assertNotNull(learn(c, lease))
        assertTrue(c.current(lease))
        c.resetObservations()
        assertFalse(c.current(lease))
    }
}
