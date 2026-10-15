package com.pyla.ai.engine

import com.pyla.ai.config.TomlLite
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SuperReadinessPolicyTest {
    private val factory = listOf(1460, 830, 1560, 930)
    private fun policy(value: Any? = 1800.0, region: Any? = factory) =
        requireNotNull(SuperReadinessPolicy.fromLegacy(value, region))

    @Test fun defaultPreservesTheExistingVisualThreshold() {
        val p = policy()
        assertEquals(0.25, p.minimumYellowFraction, 0.0)
        assertFalse(p.acceptsYellow(250, 1000))
        assertTrue(p.acceptsYellow(251, 1000))
    }
    @Test fun missingSettingsMigrateToTheSameDefault() {
        val p = policy(null, null)
        assertEquals(SuperReadinessPolicy.defaults().minimumYellowFraction, p.minimumYellowFraction, 0.0)
        assertEquals(SuperReadinessPolicy.defaults().searchBounds(1560, 720), p.searchBounds(1560, 720))
    }
    @Test fun lowerThresholdActuallyIncreasesSensitivity() {
        assertFalse(policy().acceptsYellow(200, 1000))
        assertTrue(policy(900.0).acceptsYellow(200, 1000))
    }
    @Test fun higherThresholdActuallyDecreasesSensitivity() {
        assertTrue(policy().acceptsYellow(400, 1000))
        assertFalse(policy(3600.0).acceptsYellow(400, 1000))
        assertTrue(policy(3600.0).acceptsYellow(501, 1000))
    }
    @Test fun areaNormalizationIsResolutionIndependent() {
        for (area in listOf(1000L, 4000L, 9000L)) {
            assertFalse(policy().acceptsYellow(area / 4, area))
            assertTrue(policy().acceptsYellow(area / 4 + 1, area))
            assertFalse(policy(3600).acceptsYellow(area / 2, area))
        }
    }
    @Test fun largeThresholdsAreNotClampedIntoPermissiveValues() {
        for (value in listOf(7200.0, 7201.0, 1e100, Double.MAX_VALUE)) {
            assertFalse(policy(value).acceptsYellow(10000, 10000))
        }
    }
    @Test fun zeroStillRequiresSomeYellowEvidence() {
        assertFalse(policy(0).acceptsYellow(0, 1000))
        assertTrue(policy(0).acceptsYellow(1, 1000))
    }
    @Test fun negativeNonfiniteAndMalformedThresholdsDeny() {
        for (bad in listOf(-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            "", "NaN", "Infinity", "wrong", true, listOf(1800))) {
            assertNull("Must deny threshold $bad", SuperReadinessPolicy.fromLegacy(bad, factory))
        }
    }
    @Test fun numericLegacyStringsAreSupported() {
        assertEquals(0.5, policy("3600.0").minimumYellowFraction, 0.0)
    }
    @Test fun invalidCountsAndAreasNeverGrantReadiness() {
        for ((count, area) in listOf(1L to 0L, 0L to -1L, -1L to 100L, 101L to 100L)) {
            assertFalse(policy(0).acceptsYellow(count, area))
        }
    }
    @Test fun onlyTheExactFactoryRectangleBecomesAutomatic() {
        val b = requireNotNull(policy().searchBounds(1560, 720))
        assertEquals(SuperReadinessPolicy.Bounds(936, 288, 1550, 710), b)
        assertTrue(1120 in b.left until b.right && 483 in b.top until b.bottom)
        val custom = requireNotNull(policy(region = listOf(1460, 829, 1560, 930)).searchBounds(1560, 720))
        assertTrue(custom.top > 483) // Deliberately configured denial must not fall back to auto.
    }
    @Test fun matchingCustomRectangleKeepsLegacyAspectMapping() {
        assertEquals(SuperReadinessPolicy.Bounds(1040, 400, 1280, 566),
            policy(region = listOf(1280, 600, 1500, 850)).searchBounds(1560, 720))
    }
    @Test fun aCustomExclusionRemainsARealConstraint() {
        val b = requireNotNull(policy(region = listOf(0, 0, 400, 200)).searchBounds(1920, 1080))
        assertEquals(SuperReadinessPolicy.Bounds(0, 0, 400, 200), b)
        assertFalse(1120 in b.left until b.right && 483 in b.top until b.bottom)
    }
    @Test fun customRegionScalesAtMultipleCaptureSizes() {
        for (h in listOf(540, 720, 900, 1080)) {
            val width = h * 13 / 6
            val b = requireNotNull(policy(region = listOf(1280, 600, 1500, 850)).searchBounds(width, h))
            val x = 1120.0 * h / 720
            val y = 483.0 * h / 720
            assertTrue(x >= b.left && x < b.right && y >= b.top && y < b.bottom)
        }
    }
    @Test fun malformedCustomRectanglesNeverFallBack() {
        for (bad in listOf("auto", true, emptyList<Int>(), listOf(1, 2, 3), listOf(1, 2, 3, 4, 5),
            listOf(0, 0, 0, 100), listOf(0, 0, 100, 0), listOf(100, 20, 30, 40),
            listOf(-1, 0, 100, 100), listOf(0, 0, 1921, 100), listOf(0, 0, 100, 1081),
            listOf(0, 0, 100.5, 100), listOf(0, 0, Double.NaN, 100), listOf(0, 0, true, 100),
            listOf(0, 0, Double.POSITIVE_INFINITY, 100), listOf(0, 0, "100", 100))) {
            assertNull("Must deny region $bad", SuperReadinessPolicy.fromLegacy(1800, bad))
        }
    }
    @Test fun invalidOrCollapsedFrameGeometryDenies() {
        for ((w, h) in listOf(0 to 720, 1560 to 0, -1 to 720, 1560 to -1, 1 to 1)) {
            assertNull(policy().searchBounds(w, h))
        }
    }
    @Test fun changingThresholdNeverLoosensAHigherLimit() {
        for (count in 0L..100L) {
            var denied = false
            for (threshold in listOf(0, 900, 1800, 3600, 7200, 10000)) {
                val ready = policy(threshold).acceptsYellow(count, 100)
                if (denied) assertFalse(ready)
                if (!ready) denied = true
            }
        }
    }
    @Test fun actualTomlSettingsAreReadWithoutRewritingLegacyValues() {
        val text = "super_pixels_minimum = 3600.0\n[pixel_counter_crop_area]\nsuper = [1280, 600, 1500, 850]\n"
        val config = TomlLite.parse(text)
        val p = SuperReadinessPolicy.fromLegacy(config.opt("super_pixels_minimum"), config.opt("pixel_counter_crop_area.super"))!!
        assertEquals(0.5, p.minimumYellowFraction, 0.0)
        assertEquals(listOf(1280, 600, 1500, 850), config.opt("pixel_counter_crop_area.super"))
        assertEquals(3600.0, config.opt("super_pixels_minimum"))
    }
    @Test fun shippedConfigRetainsTheExistingVisualDefaults() {
        fun asset(name: String): TomlLite = TomlLite.parse(listOf(File("src/main/assets/pyla/cfg/$name.toml"),
            File("app/src/main/assets/pyla/cfg/$name.toml")).first { it.isFile })
        val p = SuperReadinessPolicy.fromLegacy(asset("bot_config").opt("super_pixels_minimum"),
            asset("lobby_config").opt("pixel_counter_crop_area.super"))!!
        assertEquals(0.25, p.minimumYellowFraction, 0.0)
        assertEquals(policy().searchBounds(1560, 720), p.searchBounds(1560, 720))
    }
}
