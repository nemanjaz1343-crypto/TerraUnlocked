package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class SpeedEvidenceTest {
    @Test fun inferredSpeedFromLockedScreenScooterRide() {
        // 200m/67s and subsequent 100m/30s displacements from urban GPS.
        assertEquals(200f/67f,SpeedEvidence.infer(200f,67.0,24.8f,7.5f),0.01f)
        assertEquals("SCOOTER",MotionClassifier().let { c ->
            repeat(5){c.update(SpeedEvidence.infer(100f,30.0,15f,18f),15f)}
            c.current()
        })
    }

    @Test fun noManufacturedMovementFromStationaryJitterOrLongGaps() {
        assertTrue(SpeedEvidence.infer(10f,5.0,15f,12f).isNaN())
        assertTrue(SpeedEvidence.infer(200f,260.0,12f,12f).isNaN())
        assertTrue(SpeedEvidence.infer(100f,5.0,50f,15f).isNaN())
        assertTrue(SpeedEvidence.infer(800f,5.0,8f,8f).isNaN())
    }

    @Test fun usesRealReportedSpeedUnlessStrongContradiction() {
        assertEquals(5f,SpeedEvidence.combine(Float.NaN,5f,5.0,25f,8f,8f),0f)
        assertEquals(5f,SpeedEvidence.combine(0f,5f,5.0,25f,8f,8f),0f)
        assertEquals(4f,SpeedEvidence.combine(4f,5f,5.0,25f,8f,8f),0f)
        assertTrue(SpeedEvidence.combine(Float.NaN,Float.NaN,100.0,200f,8f,8f).isNaN())
    }
}
