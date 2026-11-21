package com.terraunlocked

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Detect rest from a bounded cloud of accurate positions, not from summed GPS
 * path length. A stationary phone may wander like a fly in a small area.
 * Inertial activity vetoes REST and wakes it immediately.
 *
 * State is transient: it never grants discovery, invents a route or modifies
 * the saved coordinate history. All times are elapsedRealtime milliseconds.
 */
internal class AdaptiveMotionPolicy {
    enum class State { ACTIVE, REST }
    enum class Change { NONE, ENTER_REST, WAKE_MOVEMENT, WAKE_PROBE }
    private data class Fix(
        val lat:Double,val lon:Double,val accuracy:Float,
        val speed:Float,val at:Long
    )
    private val history=ArrayDeque<Fix>()
    private var state=State.ACTIVE
    private var activeSince=0L
    private var restingSince=0L
    private var lastInertialMovement=0L
    private var anchor:Fix?=null
    private var coarseCandidate:Fix?=null

    fun state():State=state

    fun reset(now:Long) {
        history.clear()
        state=State.ACTIVE
        activeSince=now
        restingSince=0L
        lastInertialMovement=now
        anchor=null
        coarseCandidate=null
    }

    fun onInertialMovement(now:Long):Change {
        lastInertialMovement=now
        return if(state==State.REST) wake(now,Change.WAKE_MOVEMENT)
            else Change.NONE
    }

    private fun metres(a:Fix,b:Fix):Double {
        val y=(b.lat-a.lat)*111195.0
        val x=(b.lon-a.lon)*111195.0*
            cos(Math.toRadians((a.lat+b.lat)*.5))
        return hypot(x,y)
    }

    private fun wake(now:Long,cause:Change):Change {
        state=State.ACTIVE
        activeSince=now
        restingSince=0L
        coarseCandidate=null
        history.clear()
        return cause
    }

    /**
     * A coarse NETWORK/Fused estimate can ask for precise reacquisition but
     * never itself marks a place visited. Require two coherent out-of-cluster
     * estimates before waking to avoid hopping between neighboring cell towers.
     */
    fun onCoarseEstimate(
        lat:Double,lon:Double,accuracy:Float,now:Long
    ):Change {
        if(state!=State.REST || !accuracy.isFinite() ||
            accuracy<=0f || accuracy>80f ||
            !lat.isFinite() || !lon.isFinite())return Change.NONE
        val root=anchor?:return Change.NONE
        val current=Fix(lat,lon,accuracy,Float.NaN,now)
        val distance=metres(root,current)
        val threshold=maxOf(35.0,(accuracy+root.accuracy)*1.6)
        if(distance<threshold) {
            coarseCandidate=null
            return Change.NONE
        }
        val previous=coarseCandidate
        coarseCandidate=current
        return if(previous!=null && now-previous.at in 1000..30000 &&
            metres(previous,current)<maxOf(35.0,accuracy.toDouble())) {
            wake(now,Change.WAKE_MOVEMENT)
        } else Change.NONE
    }

    /**
     * Only confirmed, fresh, accurate coordinates enter the rest evidence
     * window. Reported speed must be low for most of the window, and the
     * entire track should occupy a small physical footprint.
     */
    fun onVerifiedFix(
        lat:Double,lon:Double,accuracy:Float,speed:Float,now:Long
    ):Change {
        if(!lat.isFinite() || !lon.isFinite() ||
            !accuracy.isFinite() || accuracy<=0f || accuracy>25f)
            return Change.NONE
        val current=Fix(lat,lon,accuracy,speed,now)
        if(state==State.REST) {
            val root=anchor?:return Change.NONE
            if(metres(root,current)>maxOf(22.0,
                    (accuracy+root.accuracy)*1.5)) {
                return wake(now,Change.WAKE_MOVEMENT)
            }
            return Change.NONE
        }
        history.addLast(current)
        while(history.isNotEmpty() && now-history.first().at>120000L)
            history.removeFirst()
        if(history.size<10 ||
            now-activeSince<90000L ||
            now-lastInertialMovement<75000L ||
            now-history.first().at<85000L)return Change.NONE

        val known=history.filter{it.speed.isFinite() && it.speed>=0f}
        if(known.isNotEmpty() &&
            (known.count{it.speed<=0.65f}<known.size*.75 ||
                known.any{it.speed>=1.6f}))return Change.NONE

        val centerLat=history.map{it.lat}.average()
        val centerLon=history.map{it.lon}.average()
        val center=Fix(centerLat,centerLon,accuracy,0f,now)
        val radii=history.map{metres(it,center)}.sorted()
        // A GPS fly-pattern is high total distance yet confined to this area.
        // The strict radius protects against a real, slow street walk.
        if(radii.last()>19.0 || radii[(radii.size*.9).toInt().coerceAtMost(radii.lastIndex)]>13.0)
            return Change.NONE
        state=State.REST
        restingSince=now
        coarseCandidate=null
        anchor=center
        return Change.ENTER_REST
    }

    /** Probe periodically so a slow scooter/car launch is never ignored forever. */
    fun onHeartbeat(now:Long):Change =
        if(state==State.REST && now-restingSince>=90000L)
            wake(now,Change.WAKE_PROBE)
        else Change.NONE
}
