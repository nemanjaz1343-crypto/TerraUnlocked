package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class MapHeadingPolicyTest {
    @Test fun headingUpPointsForward() {
        assertEquals(0f,MapHeadingPolicy.headingUpOrientation(0f),0.01f)
        assertEquals(270f,MapHeadingPolicy.headingUpOrientation(90f),0.01f)
        assertEquals(180f,MapHeadingPolicy.headingUpOrientation(180f),0.01f)
        assertEquals(90f,MapHeadingPolicy.headingUpOrientation(270f),0.01f)
    }
    @Test fun shortestTurnCrossesNorthWithoutSpinning() {
        assertEquals(2f,MapHeadingPolicy.shortestTurn(359f,1f),0.01f)
        assertEquals(-2f,MapHeadingPolicy.shortestTurn(1f,359f),0.01f)
        assertEquals(0f,MapHeadingPolicy.shortestTurn(0f,360f),0.01f)
    }
    @Test fun dampingAndSmallAngleThresholdPreventJitter() {
        assertEquals(0f,MapHeadingPolicy.normalize(720f),0.01f)
        assertEquals(0f,MapHeadingPolicy.easeTowards(359f,1f,.5f),.01f)
        assertFalse(MapHeadingPolicy.shouldUpdate(0f,1f,200))
        assertFalse(MapHeadingPolicy.shouldUpdate(0f,10f,40))
        assertTrue(MapHeadingPolicy.shouldUpdate(0f,10f,180))
    }
}
