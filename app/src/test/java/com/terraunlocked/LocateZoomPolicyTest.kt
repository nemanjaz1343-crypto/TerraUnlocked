package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class LocateZoomPolicyTest {
    @Test fun eachTapZoomsByOneAndHalfTimes() {
        val a=LocateZoomPolicy.next(15.0,19.0)
        val b=LocateZoomPolicy.next(a,19.0)
        assertEquals(15.5849625,a,0.00001)
        assertEquals(a+LocateZoomPolicy.STEP,b,0.00001)
    }
    @Test fun worldScaleTapFirstZoomsToCityAndNeverExceedsMaximum() {
        assertTrue(LocateZoomPolicy.next(3.0,19.0)>15.0)
        assertEquals(19.0,LocateZoomPolicy.next(18.9,19.0),0.0)
    }
}
