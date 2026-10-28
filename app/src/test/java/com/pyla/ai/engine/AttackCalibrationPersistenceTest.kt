package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence boundary: the learned record must survive a process restart and an in-place app
 * update, while corrupt, unsupported, non-finite, out-of-bounds and old-schema records are
 * rejected instead of being tapped.
 */
class AttackCalibrationPersistenceTest {

    private val w = 1080
    private val h = 2400
    private val g = 7L
    private val point = AttackButtonPoint(820f, 2010f, 88f)

    private fun learn(storage: FakeCalibrationStorage): AttackCalibrationLease {
        val core = SavedAttackButtonCalibrationCore(storage)
        val lease = core.begin(w, h, g)
        assertNull(core.observeTemplate(lease, point, 1_000L, 1_000L))
        assertEquals(point, core.observeTemplate(lease, point, 1_400L, 1_400L))
        assertNotNull(storage.read())
        return lease
    }

    private fun recordsAfterLearning(): String {
        val storage = FakeCalibrationStorage()
        learn(storage)
        return requireNotNull(storage.read())
    }

    @Test
    fun recordSurvivesProcessRestart() {
        val storage = FakeCalibrationStorage()
        learn(storage)
        // Fresh core == cold process start reading the same durable store.
        val restarted = SavedAttackButtonCalibrationCore(storage)
        assertTrue(restarted.hasSaved())
        val lease = restarted.begin(w, h, g)
        assertEquals(point, restarted.saved(lease))
        assertTrue(restarted.current(lease))
    }

    @Test
    fun recordSurvivesUpdateWithoutRelearning() {
        val storage = FakeCalibrationStorage()
        learn(storage)
        // An app update replaces code but keeps preferences; new code must read the old value.
        repeat(2) {
            val updated = SavedAttackButtonCalibrationCore(storage)
            val lease = updated.begin(w, h, g)
            assertEquals(point, updated.saved(lease))
        }
        assertEquals(1, storage.writes)
    }

    @Test
    fun restartAtDifferentDimensionsDoesNotRescaleStoredPoint() {
        val storage = FakeCalibrationStorage()
        learn(storage)
        val restarted = SavedAttackButtonCalibrationCore(storage)
        val smaller = restarted.begin(540, 1200, g)
        assertNull(restarted.saved(smaller))
        assertTrue(restarted.hasSaved())
        val back = restarted.begin(w, h, g)
        assertEquals(point, restarted.saved(back))
    }

    @Test
    fun newProcessEpochDoesNotInvalidateIdenticalDurableDimensions() {
        val storage = FakeCalibrationStorage()
        learn(storage)
        val restarted = SavedAttackButtonCalibrationCore(storage)
        val other = restarted.begin(w, h, 0L)
        assertEquals(point, restarted.saved(other))
        assertTrue(restarted.current(other))
        assertEquals(1, storage.writes)
    }

    @Test
    fun epochZeroCanLearnAndPersist() {
        val storage = FakeCalibrationStorage()
        val core = SavedAttackButtonCalibrationCore(storage)
        val lease = core.begin(w, h, 0L)
        assertTrue(core.current(lease))
        assertNull(core.observeTemplate(lease, point, 1_000L, 1_000L))
        assertEquals(point, core.observeTemplate(lease, point, 1_100L, 1_100L))
        val restarted = SavedAttackButtonCalibrationCore(storage)
        assertEquals(point, restarted.saved(restarted.begin(w, h, 12L)))
    }

    private fun assertRejected(raw: String?) {
        val storage = FakeCalibrationStorage(raw)
        val core = SavedAttackButtonCalibrationCore(storage)
        assertFalse(core.hasSaved())
        val lease = core.begin(w, h, g)
        assertNull(core.saved(lease))
        assertNull(core.observeTemplate(lease, point, 1_000L, 1_000L))
    }

    @Test
    fun garbageRecordRejected() = assertRejected("not-a-calibration-record")

    @Test
    fun oldSchemaRecordRejected() {
        val raw = recordsAfterLearning().replace("pyla.attack.calibration/1", "pyla.attack.calibration/0")
        assertRejected(raw)
    }

    @Test
    fun unknownTemplateRecordRejected() {
        val raw = recordsAfterLearning().replace("tpl=attack_crosshair_v1", "tpl=something_else")
        assertRejected(raw)
    }

    @Test
    fun nonFiniteCoordinateRecordRejected() {
        val raw = recordsAfterLearning().replace(
            ";x=${java.lang.Float.floatToIntBits(820f)}",
            ";x=${java.lang.Float.floatToIntBits(Float.NaN)}",
        )
        assertRejected(raw)
    }

    @Test
    fun outOfBoundsRecordRejected() {
        val raw = recordsAfterLearning().replace(
            ";x=${java.lang.Float.floatToIntBits(820f)}",
            ";x=${java.lang.Float.floatToIntBits(1_078f)}",
        )
        assertRejected(raw)
    }

    @Test
    fun controlOutsideLowerRightRecordRejected() {
        val raw = recordsAfterLearning().replace(
            ";y=${java.lang.Float.floatToIntBits(2_010f)}",
            ";y=${java.lang.Float.floatToIntBits(300f)}",
        )
        assertRejected(raw)
    }

    @Test
    fun rejectedRecordIsDiscardedFromStorage() {
        val storage = FakeCalibrationStorage("not-a-calibration-record")
        val core = SavedAttackButtonCalibrationCore(storage)
        assertFalse(core.hasSaved())
        assertNull(storage.read())
    }

    @Test
    fun clearRemovesDurableRecordAcrossRestart() {
        val storage = FakeCalibrationStorage()
        learn(storage)
        val core = SavedAttackButtonCalibrationCore(storage)
        assertTrue(core.clear())
        assertNull(storage.read())
        val restarted = SavedAttackButtonCalibrationCore(storage)
        assertFalse(restarted.hasSaved())
        assertNull(restarted.saved(restarted.begin(w, h, g)))
    }

    @Test
    fun maxAgeIsThreeSeconds() {
        assertEquals(3_000L, SavedAttackButtonCalibrationCore.DEFAULT_MAX_AGE_MS)
    }
}
