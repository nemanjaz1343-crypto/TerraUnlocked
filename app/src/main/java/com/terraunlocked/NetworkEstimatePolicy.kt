package com.terraunlocked

/**
 * Cell/Wi-Fi estimates are useful for a live position hint, but cannot
 * establish visited streets or remove discovery fog without a GNSS fix.
 */
internal object NetworkEstimatePolicy {
    fun showEstimate(
        accuracyMeters:Float,ageMs:Long,ageSinceVerifiedMs:Long
    ):Boolean =
        accuracyMeters.isFinite() && accuracyMeters>0f &&
        accuracyMeters<=120f && ageMs in 0..15000L &&
        ageSinceVerifiedMs>=12000L
}
