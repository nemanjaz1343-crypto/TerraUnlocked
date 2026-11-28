package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class FluidBrushSamplingTest {
    @Test fun quickGestureInterpolatesWithoutGaps() {
        val steps=FluidBrushSampling.steps(480.0,20f)
        assertTrue(steps>=50)
        assertTrue((480.0/steps)<20f)
        assertEquals(0,FluidBrushSampling.steps(0.0,20f))
    }

    @Test fun pointInterpolationIncludesEndpoint() {
        assertEquals(25.1234,FluidBrushSampling.lerp(25.0,25.1234,1f),.0000001)
        assertEquals(25.0,FluidBrushSampling.lerp(25.0,26.0,0f),.0000001)
        assertEquals(25.5,FluidBrushSampling.lerp(25.0,26.0,.5f),.0000001)
    }

    @Test fun verySmallRadiusStillSamplesStroke() {
        assertTrue(FluidBrushSampling.steps(70.0,1.2f)>20)
    }
}
