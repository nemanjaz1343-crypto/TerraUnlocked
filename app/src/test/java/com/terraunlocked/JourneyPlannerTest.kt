package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class JourneyPlannerTest {
    @Test fun orderedStopsDedupeAndSkipUnknownIds() {
        val actual=JourneyPlanner.normalize(
            listOf("b","a","b","deleted","c"),setOf("a","b","c"))
        assertEquals(listOf("b","a","c"),actual)
    }

    @Test fun progressAndNextPreserveSequence() {
        val ids=listOf("almas","marinawalk","pier7","bluewaters")
        val completed=setOf("almas","pier7")
        assertEquals(2 to 4,JourneyPlanner.progress(ids,completed))
        assertEquals("marinawalk",JourneyPlanner.next(ids,completed))
        assertNull(JourneyPlanner.next(ids,ids.toSet()))
    }

    @Test fun limitAndEmptySelectionAreSafe() {
        val ids=(1..100).map{"id_${it}"}
        assertEquals(40,JourneyPlanner.normalize(ids,ids.toSet()).size)
        assertNull(JourneyPlanner.next(emptyList(),emptySet()))
    }
}
