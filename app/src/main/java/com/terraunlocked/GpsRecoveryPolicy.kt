package com.terraunlocked

/**
 * Keep GNSS acquisition running while the receiver reports satellite activity.
 * Repeatedly unsubscribing every few minutes can prevent a difficult first fix
 * from stabilizing in an urban canyon.
 */
internal object GpsRecoveryPolicy {
    const val FUSED_MISSING_MS=60_000L
    const val FUSED_COOLDOWN_MS=180_000L
    const val GNSS_MISSING_MS=300_000L
    const val GNSS_STATUS_SILENCE_MS=300_000L
    const val GNSS_RECOVERY_COOLDOWN_MS=600_000L

    fun recoverFused(callbackAgeMs:Long,sinceLastRestartMs:Long,pending:Boolean):Boolean =
        !pending && callbackAgeMs>=FUSED_MISSING_MS &&
        sinceLastRestartMs>=FUSED_COOLDOWN_MS

    fun recoverGnss(
        gnssFixAgeMs:Long,satelliteStatusAgeMs:Long,
        sinceLastRestartMs:Long
    ):Boolean =
        gnssFixAgeMs>=GNSS_MISSING_MS &&
        satelliteStatusAgeMs>=GNSS_STATUS_SILENCE_MS &&
        sinceLastRestartMs>=GNSS_RECOVERY_COOLDOWN_MS
}
