package com.terraunlocked

/** Speed-based transport estimate. Classification never authorizes map discovery.
 * Thresholds are approximate, not Google's proprietary algorithm.
 */
internal class MotionClassifier {
    private val speeds=ArrayDeque<Float>()
    private var profile="WALK"
    private var candidateCount=0
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
            candidateCount=0
            return profile
        }
        if(next!=candidateProfile) {
            candidateProfile=next
            candidateCount=1
        } else candidateCount++
        val required=when(next) {
            // Wait for sustained reliable slow fixes before dropping SCOOTER.
            // An urban GPS blackout must not look like a walk/stop.
            "WALK" -> if(profile=="SCOOTER")10 else 5
            "CAR" -> 3
            else -> 2
        }
        if(candidateCount>=required) {
            profile=next
            candidateCount=0
        }
        return profile
    }
    fun current():String=profile
    fun reset() {
        speeds.clear()
        profile="WALK"
        candidateProfile="WALK"
        candidateCount=0
    }
}
