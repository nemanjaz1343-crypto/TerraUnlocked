package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

/** Repeatable 4 km scooter ride through an urban corridor with GPS outages. */
class UrbanScooterSimulationTest {
    private fun fix(x:Double,y:Double,seconds:Long):TraceReconstruction.Fix {
        val lat=25.07+y/111195.0
        val lon=55.14+x/(111195.0*0.905)
        return TraceReconstruction.Fix(lat,lon,seconds*1000L,12f,5f)
    }

    @Test fun shortDropoutsRemainConnectableWithoutInventingLongRoutes() {
        val samples=(0..160).filter{it%31 !in 8..9}.map{i->
            val jitter=if(i%4==0)2.0 else if(i%4==2)-2.0 else 0.0
            fix(i*25.0,jitter,i*5L)
        }
        var joined=0
        for(i in 1 until samples.size) {
            if(TraceReconstruction.shouldJoin(samples[i-1],samples[i],
                    samples.getOrNull(i-2),samples.getOrNull(i+1)))joined++
        }
        assertTrue("Expected most 4km scooter segments to join, got $joined",joined>135)
        assertFalse(TraceReconstruction.shouldJoin(
            fix(0.0,0.0,0),fix(250.0,0.0,50)))
    }

    @Test fun multipathTeleportDoesNotUncoverFalseStreet() {
        assertFalse(TraceReconstruction.shouldJoin(
            fix(500.0,0.0,100),fix(900.0,300.0,105),
            fix(475.0,0.0,95),fix(525.0,0.0,110)))
    }
}
