package com.terraunlocked

/** Speed-based transport estimate. Classification never authorizes map discovery.
 * Thresholds are approximate, not Google's proprietary algorithm.
 */
internal class MotionClassifier {
    private val speeds=ArrayDeque<Float>()
    private var profile="WALK"
    private var candidateSince=0L
    private var candidateProfile="WALK"

    fun update(speedMetersPerSecond:Float,accuracyMeters:Float):String {
        if(!speedMetersPerSecond.isFinite() || speedMetersPerSecond<0f ||
            !accuracyMeters.isFinite() || accuracyMeters>35f)return profile
        // Use a short median to suppress one-off GPS speed spikes.
        speeds.addLast(speedMetersPerSecond.coerceAtMost(40f))
        while(speeds.size>5)speeds.removeFirst()
        val sorted=speeds.sorted()
        val speed=sorted[sorted.size/2]
        // Hysteresis prevents stoplights from turning a scooter ride into WALK.
        val next=when(profile) {
            "CAR" -> when {
                speed>=5.5f -> "CAR"
                speed>=1.6f -> "SCOOTER"
                else -> "WALK"
            }
            "SCOOTER" -> when {
                speed>=9.0f -> "CAR"
                speed>=1.25f -> "SCOOTER"
                else -> "WALK"
            }
            else -> when {
                speed>=9.0f -> "CAR"
                speed>=2.5f -> "SCOOTER"
                else -> "WALK"
            }
        }
        if(next==profile) {
            candidateProfile=profile
            candidateSince=0L
            return profile
        }
        val now=android.os.SystemClock.elapsedRealtime()
        if(next!=candidateProfile) {
            candidateProfile=next
            candidateSince=now
        }
        val delay=if(next=="WALK")25000L else 6000L
        if(now-candidateSince>=delay) {
            profile=next
            candidateSince=0L
        }
        return profile
    }
    fun current():String=profile
    fun reset() {
        speeds.clear()
        profile="WALK"
        candidateProfile="WALK"
        candidateSince=0L
    }
}
