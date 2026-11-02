package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class PathIntelligenceTest {
    private val origin=PathIntelligence.Point(25.07,55.14)
    private fun point(east:Double,north:Double=0.0)=PathIntelligence.Point(
        origin.lat+north/111195.0,
        origin.lon+east/(111195.0*0.905))

    @Test fun satelliteFixAccuracyGatesPermanentReveals() {
        assertTrue(PathIntelligence.assess(9f,2000,1.2f,5.0,4.0,false).accepted)
        assertTrue(PathIntelligence.assess(30f,2000,1.2f,5.0,4.0,true).accepted)
        assertFalse(PathIntelligence.assess(48f,2000,1.2f,5.0,4.0,true).accepted)
        assertFalse(PathIntelligence.assess(90f,2000,1.2f,5.0,4.0,true).accepted)
    }

    @Test fun inertialEvidenceDoesNotUnlockPoorGps() {
        val both=PathIntelligence.assess(49f,1000,1.2f,5.0,4.0,true)
        assertEquals("ESTIMATED",both.tier)
        assertFalse(both.accepted)
    }

    @Test fun teleportAndStaleFixesLoseConfidence() {
        val clean=PathIntelligence.assess(12f,900,1.5f,12.0,8.0,false)
        val teleport=PathIntelligence.assess(12f,900,1.5f,190.0,5.0,false)
        val old=PathIntelligence.assess(12f,23000,1.5f,12.0,8.0,false)
        assertTrue(clean.accepted)
        assertFalse(teleport.accepted)
        assertTrue(old.score<clean.score)
    }

    @Test fun nearestOsmSegmentPointWorksWithSparseNodes() {
        val line=listOf(point(0.0),point(100.0))
        val projection=PathIntelligence.project(point(50.0,8.0),line)!!
        assertEquals(8.0,projection.distanceMeters,1.0)
        assertEquals(0.5,projection.along,0.03)
    }

    @Test fun plausibleFootwayPreviewNeverUsesFarAwayRoutes() {
        val line=listOf(point(0.0),point(100.0))
        assertNotNull(PathIntelligence.boundedMatch(point(10.0,7.0),
            point(90.0,6.0),line))
        assertNull(PathIntelligence.boundedMatch(point(10.0,80.0),
            point(90.0,80.0),line))
    }

    @Test fun impossibleAndDetourPathAreRejected() {
        val detour=listOf(point(0.0),point(0.0,100.0),point(100.0,100.0),point(100.0))
        assertNull(PathIntelligence.boundedMatch(point(1.0),point(99.0),detour))
    }
}
