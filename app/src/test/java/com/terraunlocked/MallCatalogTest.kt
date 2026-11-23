package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class MallCatalogTest {
    @Test fun uniqueOsmGeometryAndNodeNames() {
        val items=listOf(
            MallPlace("node_1","Dubai Marina Mall",25.0765,55.1398),
            MallPlace("way_2","Dubai Marina Mall",25.0767,55.1400),
            MallPlace("node_3","Dubai Marina Mall",25.0990,55.1490),
            MallPlace("way_4","The Dubai Mall",25.1972,55.2796)
        )
        val unique=MallCatalog.distinct(items)
        assertEquals(3,unique.size)
        assertEquals(2,unique.count{it.name=="Dubai Marina Mall"})
    }

    @Test fun curatedIdentityDoesNotInventPopularityRanking() {
        assertNotNull(MallCatalog.signature("Ibn Battuta Mall"))
        assertNotNull(MallCatalog.signature("Wafi Mall"))
        assertNotNull(MallCatalog.signature("Mercato Shopping Mall"))
        assertFalse(MallCatalog.featured("A previously unmapped local shopping centre"))
        assertNull(MallCatalog.signature("A previously unmapped local shopping centre"))
    }

    @Test fun distinctNamedCentresNearbyRemainSeparate() {
        val places=listOf(
            MallPlace("way_11","Mall A",25.07,55.14),
            MallPlace("way_12","Mall B",25.0701,55.1401)
        )
        assertEquals(2,MallCatalog.distinct(places).size)
    }

    @Test fun featuredMallsSortBeforeUncuratedButAllRemain() {
        val places=listOf(
            MallPlace("node_local","Community Shopping Centre",25.1,55.12),
            MallPlace("node_icon","Wafi Mall",25.2,55.31)
        )
        val distinct=MallCatalog.distinct(places)
        assertEquals(2,distinct.size)
        assertEquals("Wafi Mall",distinct.first().name)
    }
}
