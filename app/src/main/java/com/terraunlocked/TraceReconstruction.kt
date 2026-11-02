package com.terraunlocked

import kotlin.math.*

/**
 * Conservative offline joining of already verified GPS discoveries.
 * No position is invented beyond a short plausible connector between two fixes.
 * Long gaps, sharp turns and physically implausible jumps remain covered by fog.
 */
internal object TraceReconstruction {
    data class Fix(
        val lat: Double, val lon: Double, val timestamp: Long,
        val accuracy: Float = Float.NaN, val speed: Float = Float.NaN
    )

    fun sampleSpacing(accuracyMeters: Float, speedMetersPerSecond: Float): Float {
        val accuracy=accuracyMeters.coerceIn(0f,35f)
        val minimum=if(accuracy<=12f)6.5f else if(accuracy<=22f)8f else 11f
        val stride=max(minimum,(accuracy*.45f).coerceAtMost(14f))
        // Avoid isolated fog reveals when a stationary GPS wanders around.
        return if(speedMetersPerSecond.isFinite() && speedMetersPerSecond in 0f..0.35f)
            max(19f,accuracy*1.2f) else stride
    }

    private fun metres(a:Fix,b:Fix): Double {
        val y=(b.lat-a.lat)*111195.0
        val x=(b.lon-a.lon)*111195.0*cos(Math.toRadians((a.lat+b.lat)*.5))
        return hypot(x,y)
    }

    private fun consistentDirection(a:Fix,b:Fix):Boolean {
        val dt=(b.timestamp-a.timestamp)/1000.0
        return dt in 0.3..35.0 && metres(a,b)>=9.0
    }

    private fun dotAligned(a:Fix,b:Fix,c:Fix):Boolean {
        val scale=111195.0
        val cosLat=cos(Math.toRadians((a.lat+b.lat+c.lat)/3.0))
        val dx1=(b.lon-a.lon)*scale*cosLat
        val dy1=(b.lat-a.lat)*scale
        val dx2=(c.lon-b.lon)*scale*cosLat
        val dy2=(c.lat-b.lat)*scale
        val length=hypot(dx1,dy1)*hypot(dx2,dy2)
        return length>0 && (dx1*dx2+dy1*dy2)/length>=0.64
    }

    fun shouldJoin(a:Fix,b:Fix,before:Fix?=null,after:Fix?=null):Boolean {
        val elapsed=(b.timestamp-a.timestamp)/1000.0
        if(elapsed<=0.0 || elapsed>90.0) return false
        val dist=metres(a,b)
        if(!dist.isFinite())return false
        val metadata=a.accuracy.isFinite() && b.accuracy.isFinite()
        if(!metadata) {
            // Old saved routes lacked quality/speed values: preserve legacy display.
            return if(elapsed<=25.0)dist<=750.0 && dist<=50.0*elapsed+15.0
                else dist<=70.0
        }
        if(a.accuracy>35f || b.accuracy>35f)return false
        // 20 m footprints overlap already. A connector adds no unknown terrain.
        if(dist<=36.0)return true
        val speed=maxOf(if(a.speed.isFinite())a.speed else 0f,
            if(b.speed.isFinite())b.speed else 0f)
        if(speed>=4f) {
            if(elapsed>20.0 || dist>650.0 || dist>speed*elapsed*1.65+25.0)return false
            if(dist<=90.0)return true
        } else if(speed>=2.0f) {
            if(elapsed>27.0 || dist>105.0 || dist>speed*elapsed*1.75+15.0)return false
            if(elapsed<=14.0 && dist<=65.0)return true
        } else {
            if(elapsed<=14.0 && dist<=min(65.0,3.2*elapsed+15.0))return true
            if(elapsed>45.0 || dist>min(86.0,2.6*elapsed+10.0))return false
            if(a.accuracy>28f || b.accuracy>28f)return false
        }
        val behind=before?.takeIf {consistentDirection(it,a)}
        val ahead=after?.takeIf {consistentDirection(b,it)}
        val backwardPass=behind?.let {dotAligned(it,a,b)}
        val forwardPass=ahead?.let {dotAligned(a,b,it)}
        if(backwardPass==false || forwardPass==false)return false
        return backwardPass==true || forwardPass==true
    }
}
