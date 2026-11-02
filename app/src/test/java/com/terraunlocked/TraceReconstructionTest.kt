package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class TraceReconstructionTest {
    private val lat=25.07
    private val lon=55.14
    private fun point(
        x:Double,y:Double,time:Long,accuracy:Float=10f,speed:Float=1.3f
    )=TraceReconstruction.Fix(
        lat+y/111195.0,lon+x/(111195.0*0.905),time,accuracy,speed
    )

    @Test fun walkingSamplesAreDenserWithoutStationaryDrift() {
        assertTrue(TraceReconstruction.sampleSpacing(32f,1.2f)<20f)
        assertTrue(TraceReconstruction.sampleSpacing(25f,0.1f)>25f)
    }

    @Test fun shortUrbanWalkingOutageConnects() {
        assertTrue(TraceReconstruction.shouldJoin(
            point(0.0,0.0,1000),point(32.0,0.0,18000),
            point(-15.0,0.0,-9000),point(44.0,0.0,25000)))
    }

    @Test fun noShortcutAcrossAStreetCorner() {
        assertFalse(TraceReconstruction.shouldJoin(
            point(0.0,0.0,1000),point(48.0,0.0,35000),
            point(-12.0,0.0,-9000),point(48.0,13.0,47000)))
    }

    @Test fun noLongOrImpossibleWalkingJumps() {
        assertFalse(TraceReconstruction.shouldJoin(
            point(0.0,0.0,1000),point(120.0,0.0,99000)))
        assertFalse(TraceReconstruction.shouldJoin(
            point(0.0,0.0,1000),point(155.0,0.0,13000)))
    }

    @Test fun validShortCarGapIsAllowedButTeleportIsNot() {
        assertTrue(TraceReconstruction.shouldJoin(
            point(0.0,0.0,1000,9f,21f),point(208.0,0.0,11000,8f,22f),
            point(-40.0,0.0,-1000,9f,21f)))
        assertFalse(TraceReconstruction.shouldJoin(
            point(0.0,0.0,1000,9f,20f),point(900.0,0.0,11000,9f,20f)))
    }

    @Test fun neverConnectSameTimeOrReverseTime() {
        assertFalse(TraceReconstruction.shouldJoin(
            point(0.0,0.0,1000),point(12.0,0.0,1000)))
        assertFalse(TraceReconstruction.shouldJoin(
            point(0.0,0.0,1000),point(12.0,0.0,500)))
    }
}
