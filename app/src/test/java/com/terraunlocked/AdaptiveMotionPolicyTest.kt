package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class AdaptiveMotionPolicyTest {
    private fun at(x:Double,y:Double):Pair<Double,Double> {
        val lat=25.07+y/111195.0
        val lon=55.14+x/(111195.0*0.905)
        return lat to lon
    }
    private fun observe(p:AdaptiveMotionPolicy,x:Double,y:Double,time:Long,
                        accuracy:Float=7f,speed:Float=0.12f)
        :AdaptiveMotionPolicy.Change {
        val (lat,lon)=at(x,y)
        return p.onVerifiedFix(lat,lon,accuracy,speed,time)
    }
    private fun resting():AdaptiveMotionPolicy {
        val policy=AdaptiveMotionPolicy()
        policy.reset(1000L)
        for(i in 0..12) {
            val x=when(i%4) {0->0.0;1->6.0;2->-5.0;else->2.0}
            val y=when(i%3) {0->3.0;1->-4.0;else->0.0}
            observe(policy,x,y,1000L+i*9000L)
        }
        assertEquals(AdaptiveMotionPolicy.State.REST,policy.state())
        return policy
    }

    @Test fun flyPatternInsideSmallAreaEntersRest() {
        val policy=resting()
        assertEquals(AdaptiveMotionPolicy.State.REST,policy.state())
    }

    @Test fun slowStraightWalkNeverLooksLikeStationaryJitter() {
        val policy=AdaptiveMotionPolicy()
        policy.reset(1000L)
        for(i in 0..15)observe(policy,i*5.0,0.0,1000L+i*9000L,7f,0.5f)
        assertEquals(AdaptiveMotionPolicy.State.ACTIVE,policy.state())
    }

    @Test fun weakFixesCannotTriggerBatterySaver() {
        val policy=AdaptiveMotionPolicy()
        policy.reset(1000L)
        for(i in 0..25)observe(policy,i%3*4.0,0.0,1000L+i*9000L,70f,0.1f)
        assertEquals(AdaptiveMotionPolicy.State.ACTIVE,policy.state())
    }

    @Test fun inertialMovementWakesAndPreventsImmediateRest() {
        val policy=resting()
        assertEquals(AdaptiveMotionPolicy.Change.WAKE_MOVEMENT,
            policy.onInertialMovement(150000L))
        assertEquals(AdaptiveMotionPolicy.State.ACTIVE,policy.state())
        for(i in 0..7)observe(policy,0.0,1.0,151000L+i*9000L)
        assertEquals(AdaptiveMotionPolicy.State.ACTIVE,policy.state())
    }

    @Test fun coarseWifiCellTowerJumpNeedsTwoConsistentFixes() {
        val policy=resting()
        val first=at(150.0,8.0)
        val second=at(153.0,10.0)
        assertEquals(AdaptiveMotionPolicy.Change.NONE,
            policy.onCoarseEstimate(first.first,first.second,25f,120000L))
        assertEquals(AdaptiveMotionPolicy.State.REST,policy.state())
        assertEquals(AdaptiveMotionPolicy.Change.WAKE_MOVEMENT,
            policy.onCoarseEstimate(second.first,second.second,25f,124000L))
    }

    @Test fun verifiedDepartureAndPeriodicProbeBothWake() {
        val policy=resting()
        assertEquals(AdaptiveMotionPolicy.Change.WAKE_MOVEMENT,
            observe(policy,45.0,0.0,124000L))
        val policy2=resting()
        assertEquals(AdaptiveMotionPolicy.Change.NONE,
            policy2.onHeartbeat(120000L))
        assertEquals(AdaptiveMotionPolicy.Change.WAKE_PROBE,
            policy2.onHeartbeat(200000L))
    }
}
