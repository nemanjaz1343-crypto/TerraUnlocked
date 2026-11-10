package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class MotionClassifierTest {
    @Test fun scooterSurvivesShortStop() {
        val c=MotionClassifier()
        repeat(6){c.update(5.0f,12f)}
        assertEquals("SCOOTER",c.current())
        repeat(2){c.update(0.1f,12f)}
        assertEquals("SCOOTER",c.current())
    }
    @Test fun switchesToCarAfterSustainedEvidence() {
        val c=MotionClassifier()
        repeat(9){c.update(10f,8f)}
        assertEquals("CAR",c.current())
    }
    @Test fun poorFixCannotChangeProfile() {
        val c=MotionClassifier()
        repeat(8){c.update(4.5f,12f)}
        repeat(20){c.update(0f,85f)}
        assertEquals("SCOOTER",c.current())
    }
    @Test fun unknownSpeedsDoNotEraseScooterProfile() {
        val c=MotionClassifier()
        repeat(8){c.update(5.5f,12f)}
        repeat(30){c.update(Float.NaN,35f)}
        assertEquals("SCOOTER",c.current())
        repeat(6){c.update(0.1f,8f)}
        assertEquals("SCOOTER",c.current())
    }

    @Test fun eventuallyReturnsToWalk() {
        val c=MotionClassifier()
        repeat(8){c.update(4f,10f)}
        repeat(12){c.update(0.1f,10f)}
        assertEquals("WALK",c.current())
    }
}
