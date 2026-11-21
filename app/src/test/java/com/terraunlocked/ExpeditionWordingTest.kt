package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class ExpeditionWordingTest {
    @Test fun threeThemesMapToOneConsistentConcept() {
        assertEquals("MISSIONS",ExpeditionWording.forId("MILITARY").heading)
        assertEquals("OPERATION",ExpeditionWording.forId("MILITARY").journey)
        assertEquals("EXPEDITIONS",ExpeditionWording.forId("EXPLORER").heading)
        assertEquals("DISCOVERY",ExpeditionWording.forId("EXPLORER").single)
        assertEquals("TASKS",ExpeditionWording.forId("CIVIL").heading)
        assertEquals("ITINERARY",ExpeditionWording.forId("CIVIL").journey)
    }
    @Test fun unknownSelectionUsesMilitaryWithoutChangingData() {
        assertEquals(ExpeditionWording.military,ExpeditionWording.forId("unknown"))
        assertEquals(ExpeditionWording.military,ExpeditionWording.forId(null))
    }
}
