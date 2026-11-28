package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class GlobalViewportPolicyTest {
    @Test fun cityViewportQueuesMoreThanCentre() {
        val items=GlobalObjectivePolicy.viewportCells(
            48.80,2.11,49.01,2.65,48.89,2.35,12.0)
        assertTrue(items.size>=2)
        assertEquals(items.size,items.toSet().size)
        assertTrue(items.all{it.first in 48.0..50.0 && it.second in 1.0..4.0})
    }

    @Test fun globalZoomNeverQueriesOverpass() {
        assertTrue(GlobalObjectivePolicy.viewportCells(
            -80.0,-170.0,80.0,170.0,0.0,0.0,4.0).isEmpty())
    }

    @Test fun extremeViewportRequestIsBounded() {
        val items=GlobalObjectivePolicy.viewportCells(
            -80.0,-170.0,80.0,170.0,0.0,0.0,9.0)
        assertTrue(items.size<=32)
    }

    @Test fun antimeridianYieldsNormalizedLongitude() {
        val items=GlobalObjectivePolicy.viewportCells(
            -3.0,179.4,3.0,-179.3,0.0,179.8,11.0)
        assertTrue(items.isNotEmpty())
        assertTrue(items.all{it.second in -180.0..180.0})
    }
}
