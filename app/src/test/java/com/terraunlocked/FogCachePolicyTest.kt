package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class FogCachePolicyTest {
    @Test fun cacheKeepsExistingRasterDuringOrdinaryPanAndPinch() {
        assertFalse(FogCachePolicy.needsFreshRaster(false,.01,40f,-30f,500))
        assertFalse(FogCachePolicy.needsFreshRaster(false,0.0,149f,100f,500))
    }
    @Test fun idleRefreshIsNeededForNewExplorationAndBigViewportChanges() {
        assertTrue(FogCachePolicy.needsFreshRaster(true,0.0,0f,0f,500))
        assertTrue(FogCachePolicy.needsFreshRaster(false,.1,0f,0f,500))
        assertTrue(FogCachePolicy.needsFreshRaster(false,0.0,151f,0f,500))
    }
    @Test fun fogProjectionsKeepRouteAndMaskBoundsInSync() {
        val points=listOf(
            FogProjectedPoint(120f,250f,false),
            FogProjectedPoint(123f,259f,true)
        )
        val input=FogRasterInput(1080,1920,960,18f,points,emptyList())
        assertEquals(1080,input.width)
        assertEquals(960,input.pad)
        assertEquals(2,input.points.size)
        assertTrue(input.points.last().joinPrevious)
    }
}
