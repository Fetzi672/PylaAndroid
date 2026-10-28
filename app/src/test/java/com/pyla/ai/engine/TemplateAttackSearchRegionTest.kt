package com.pyla.ai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.core.Rect

class TemplateAttackSearchRegionTest {
    @Test fun evenRasterUsesLowerRightQuarter() {
        assertEquals(Rect(480, 221, 480, 221), TemplateAttackControl.lowerRightSearchRegion(960, 442))
    }

    @Test fun oddRasterRoundsBoundaryInward() {
        assertEquals(Rect(481, 222, 480, 221), TemplateAttackControl.lowerRightSearchRegion(961, 443))
    }

    @Test fun minimumRasterRetainsValidQuarter() {
        assertEquals(Rect(4, 4, 4, 4), TemplateAttackControl.lowerRightSearchRegion(8, 8))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTooSmallWidth() {
        TemplateAttackControl.lowerRightSearchRegion(7, 590)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsTooSmallHeight() {
        TemplateAttackControl.lowerRightSearchRegion(1280, 7)
    }

    @Test fun largeDimensionsDoNotOverflowBoundary() {
        val region = TemplateAttackControl.lowerRightSearchRegion(Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(region.x > 0 && region.y > 0 && region.width > 0 && region.height > 0)
        assertEquals(Int.MAX_VALUE, region.x + region.width)
        assertEquals(Int.MAX_VALUE, region.y + region.height)
    }
}
