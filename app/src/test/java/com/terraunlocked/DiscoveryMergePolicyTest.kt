package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class DiscoveryMergePolicyTest {
    @Test fun staleUndoSnapshotNeverClobbersNewGpsFix() {
        repeat(1000) { cycle ->
            val gpsBefore=DiscoveryPoint(25.0,55.0,false,1000L)
            val gpsNew=DiscoveryPoint(25.1,55.1,false,2000L+cycle)
            val manualCurrent=DiscoveryPoint(25.2,55.2,true,1200L)
            val manualUndo=DiscoveryPoint(25.3,55.3,true,900L)
            // UI snapshot was taken before gpsNew was saved by the service.
            val stored=listOf(gpsBefore,manualCurrent,gpsNew)
            val merged=DiscoveryMergePolicy.replaceManual(stored,listOf(manualUndo))
            assertEquals(2,merged.count{!it.manual})
            assertTrue(merged.any{it.timestamp==gpsNew.timestamp && !it.manual})
            assertFalse(merged.any{it.timestamp==manualCurrent.timestamp && it.manual})
            assertTrue(merged.any{it.timestamp==manualUndo.timestamp && it.manual})
            assertEquals(merged.sortedBy{it.timestamp},merged)
        }
    }

    @Test fun aRedoRestoresOnlyManualData() {
        val gps=DiscoveryPoint(48.0,2.0,false,200L)
        val undo=DiscoveryMergePolicy.replaceManual(listOf(gps),emptyList())
        val manual=DiscoveryPoint(48.1,2.1,true,300L)
        val redo=DiscoveryMergePolicy.replaceManual(undo,listOf(manual))
        assertEquals(setOf(200L,300L),redo.map{it.timestamp}.toSet())
        assertEquals(1,redo.count{!it.manual})
    }
}
