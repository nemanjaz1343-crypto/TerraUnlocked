package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class GpsRecoveryPolicyTest {
    @Test fun satellitesSearchingShouldNotRestartGnss() {
        assertFalse(GpsRecoveryPolicy.recoverGnss(14_000_000L,12_000L,14_000_000L))
        assertFalse(GpsRecoveryPolicy.recoverGnss(14_000_000L,310_000L,180_000L))
        assertTrue(GpsRecoveryPolicy.recoverGnss(900_000L,900_000L,900_000L))
    }

    @Test fun keepFusedSubscribedDuringPoorQualityStream() {
        assertFalse(GpsRecoveryPolicy.recoverFused(1000L,1_000_000L,false))
        assertFalse(GpsRecoveryPolicy.recoverFused(100_000L,30_000L,false))
        assertFalse(GpsRecoveryPolicy.recoverFused(100_000L,400_000L,true))
        assertTrue(GpsRecoveryPolicy.recoverFused(120_000L,400_000L,false))
    }
}
