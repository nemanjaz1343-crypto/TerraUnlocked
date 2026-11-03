package com.terraunlocked

/** Stable movement profile from recent verified speed evidence.
 * A single slow/zero GPS sample must not turn a scooter ride into WALK. */
internal class MotionClassifier {
    private val speeds=ArrayDeque<Float>()
    private var profile="WALK"
    private var pending="WALK"
    private var pendingCount=0

    fun update(speedMetersPerSecond:Float,accuracyMeters:Float):String {
        if(!speedMetersPerSecond.isFinite() || speedMetersPerSecond<0f ||
            !accuracyMeters.isFinite() || accuracyMeters>35f)return profile
        speeds.addLast(speedMetersPerSecond.coerceAtMost(40f))
        while(speeds.size>5)speeds.removeFirst()
        val sorted=speeds.sorted()
        val median=sorted[sorted.size/2]
        val candidate=when {
            median>=7.2f -> "CAR"
            median>=2.15f -> "SCOOTER"
            else -> "WALK"
        }
        if(candidate==profile) {
            pending=profile;pendingCount=0
            return profile
        }
        if(candidate!=pending) {
            pending=candidate;pendingCount=1
        } else pendingCount++
        val required=when {
            candidate=="WALK" -> 4
            candidate=="CAR" -> 3
            else -> 2
        }
        if(pendingCount>=required) {
            profile=candidate
            pendingCount=0
        }
        return profile
    }
    fun current():String=profile
}
