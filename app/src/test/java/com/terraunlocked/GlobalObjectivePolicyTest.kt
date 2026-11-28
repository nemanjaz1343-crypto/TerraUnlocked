package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class GlobalObjectivePolicyTest {
    @Test fun fiveImportanceLevelsFollowZoom() {
        assertEquals(1,GlobalObjectivePolicy.visibleTier(2.0))
        assertEquals(2,GlobalObjectivePolicy.visibleTier(8.0))
        assertEquals(3,GlobalObjectivePolicy.visibleTier(11.0))
        assertEquals(4,GlobalObjectivePolicy.visibleTier(14.0))
        assertEquals(5,GlobalObjectivePolicy.visibleTier(17.0))
        assertEquals(0,GlobalObjectivePolicy.fetchBand(6.0))
    }

    @Test fun regionalKeysStableWithinCellsAndChangeAcrossAreas() {
        val a=GlobalObjectivePolicy.cellKey(25.068,55.141,15.0)
        assertEquals(a,GlobalObjectivePolicy.cellKey(25.069,55.142,15.0))
        assertNotEquals(a,GlobalObjectivePolicy.cellKey(25.32,55.50,15.0))
        assertNotEquals(a,GlobalObjectivePolicy.cellKey(25.068,55.141,12.0))
    }

    @Test fun coordinatesStableAcrossOsmUpdates() {
        assertEquals("osm_node_234",GlobalObjectivePolicy.stableId("node",234L))
        assertEquals("osm_way_234",GlobalObjectivePolicy.stableId("way",234L))
    }

    @Test fun legacyDubaiObjectivesNeverDuplicateNearbyGlobalHits() {
        val legacy=ReconObjectives.all
        val dup=ReconObjective("osm_way_117","Museum of the Future","ARCH",
            25.21925,55.28142,"OSM","")
        val elsewhere=ReconObjective("osm_way_118","Museum of the Future","ARCH",
            48.0,2.0,"OSM","")
        val unique=GlobalObjectivePolicy.distinct(legacy,listOf(dup,elsewhere,elsewhere))
        assertEquals(1,unique.size)
        assertEquals("osm_way_118",unique.single().id)
        assertTrue(legacy.any{it.id=="future"})
        assertTrue(legacy.any{it.id=="almas"})
    }

    @Test fun accentedAndUnaccentedNamesDoNotOverlapAsDuplicateIcons() {
        val accented=ReconObjective("world_chichen","Chichén Itzá","HERITAGE",
            20.68428,-88.56778,"World","")
        val mapped=ReconObjective("osm_way_9876","Chichen Itza","HERITAGE",
            20.68430,-88.56777,"OSM","")
        assertTrue(GlobalObjectivePolicy.overlapping(accented,mapped))
    }

    @Test fun osmMetadataMapsToRelevantCategoriesAndTiers() {
        assertEquals(2,GlobalObjectivePolicy.tier(mapOf(
            "tourism" to "attraction","wikidata" to "Q123")))
        assertEquals(3,GlobalObjectivePolicy.tier(mapOf(
            "tourism" to "gallery","wikidata" to "Q456")))
        assertEquals(3,GlobalObjectivePolicy.tier(mapOf(
            "tourism" to "museum")))
        assertEquals(5,GlobalObjectivePolicy.tier(mapOf(
            "tourism" to "artwork")))
        assertEquals("ART",GlobalObjectivePolicy.category(
            mapOf("tourism" to "gallery")))
        assertEquals("NATURE",GlobalObjectivePolicy.category(
            mapOf("natural" to "waterfall")))
    }

    @Test fun worldSeedsCoverMultipleContinentsWithoutModifyingDubai() {
        val world=GlobalWorldLandmarks.all
        assertTrue(world.size>=10)
        assertEquals(world.size,world.map{it.id}.distinct().size)
        assertTrue(world.any{it.latitude<0})
        assertTrue(world.any{it.longitude<0})
        assertTrue(world.all{it.id.startsWith("world_")})
        assertTrue(world.none{seed->ReconObjectives.all.any{it.id==seed.id}})
        assertTrue("Cities need offline reference sites",world.size>=50)
        assertTrue(world.any{it.name=="Sagrada Família"})
        assertTrue(world.any{it.name=="Central Park"})
        assertTrue(world.any{it.name=="Petronas Towers"})
        assertTrue(GlobalWorldLandmarks.visible(4.0).size <
            GlobalWorldLandmarks.visible(8.0).size)
    }
}
