package com.pyla.ai.engine

import org.junit.Assert.*
import org.junit.Test

class BrawlerGridPagingRegionsTest {
    @Test fun pagingAppendsOnlyOneBoundedSortTileWithoutMovingAnyOriginalTile() {
        for ((w,h) in listOf(1280 to 591,1920 to 886,640 to 320,4096 to 2048)) {
            val original = BrawlerGridOcrRegions.plan(w,h)!!
            val paging = BrawlerGridOcrRegions.pagingPlan(w,h)!!
            assertEquals(original.tiles, paging.tiles.take(4))
            assertEquals(5, paging.tiles.size)
            val sort = paging.tiles.last()
            assertEquals(GridOcrRect(w*40/100,0,w*75/100,h*7/100),sort.source)
            assertEquals(original.height+24,sort.destination.top)
            assertEquals(sort.source.width*2,sort.destination.width)
            assertEquals(sort.source.height*2,sort.destination.height)
            assertEquals(paging.height,sort.destination.bottom)
            assertEquals(original.width,paging.width)
        }
    }
    @Test fun sortFullAndPartialBoxesMapBackToOriginalPixels() {
        val p = BrawlerGridOcrRegions.pagingPlan(1280,591)!!
        val t = p.tiles.last()
        assertEquals(t.source,BrawlerGridOcrRegions.map(p,t.destination))
        assertEquals(GridOcrRect(512,0,513,1),BrawlerGridOcrRegions.map(p,
            GridOcrRect(0,t.destination.top,1,t.destination.top+1)))
        assertEquals(GridOcrRect(959,40,960,41),BrawlerGridOcrRegions.map(p,
            GridOcrRect(t.destination.right-1,t.destination.bottom-1,t.destination.right,t.destination.bottom)))
    }
    @Test fun separatorAndCrossTileBoxesRemainRejected() {
        val p=BrawlerGridOcrRegions.pagingPlan(1280,591)!!
        val prior=p.tiles[3].destination;val sort=p.tiles[4].destination
        assertNull(BrawlerGridOcrRegions.map(p,GridOcrRect(0,prior.bottom,20,sort.top)))
        assertNull(BrawlerGridOcrRegions.map(p,GridOcrRect(0,prior.bottom-1,20,sort.top+1)))
        assertNull(BrawlerGridOcrRegions.map(p,GridOcrRect(0,sort.top,sort.right+1,sort.bottom)))
    }
    @Test fun forgedPagingPlanCannotAdmitArbitraryFifthTile() {
        val p=BrawlerGridOcrRegions.pagingPlan(1280,591)!!
        val t=p.tiles.last()
        for (fake in listOf(t.copy(source=t.source.copy(left=500)),
            t.copy(destination=t.destination.copy(top=t.destination.top-1)))) {
            assertNull(BrawlerGridOcrRegions.map(p.copy(tiles=p.tiles.take(4)+fake),t.destination))
        }
        assertNull(BrawlerGridOcrRegions.map(p.copy(height=p.height+1),t.destination))
        assertNull(BrawlerGridOcrRegions.map(p.copy(tiles=p.tiles+t),t.destination))
    }
    @Test fun unsupportedCapturesDoNotGainPagingAuthority() {
        for ((w,h) in listOf(1920 to 1080,0 to 0,Int.MAX_VALUE to Int.MAX_VALUE,1280 to 720))
            assertNull(BrawlerGridOcrRegions.pagingPlan(w,h))
    }
}
