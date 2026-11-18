package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class NetworkEstimatePolicyTest {
    @Test fun showFreshEstimateOnlyWhenSatelliteStreamIsMissing() {
        assertTrue(NetworkEstimatePolicy.showEstimate(35f,1000,15000))
        assertFalse(NetworkEstimatePolicy.showEstimate(35f,1000,4000))
        assertFalse(NetworkEstimatePolicy.showEstimate(140f,1000,15000))
        assertFalse(NetworkEstimatePolicy.showEstimate(15f,20000,15000))
        assertFalse(NetworkEstimatePolicy.showEstimate(15f,-200,15000))
        assertFalse(NetworkEstimatePolicy.showEstimate(Float.NaN,200,15000))
    }
}
