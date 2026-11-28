package com.terraunlocked

import kotlin.math.abs

/**
 * Map orientation convention used by osmdroid: positive clockwise map
 * rotation; place the direction the phone is facing at screen top.
 * JVM-only helpers keep normalization / shortest-turn behavior testable.
 */
internal object MapHeadingPolicy {
    fun normalize(degrees:Float):Float {
        if(!degrees.isFinite())return 0f
        return ((degrees%360f)+360f)%360f
    }
    fun headingUpOrientation(trueHeading:Float):Float =
        normalize(360f-normalize(trueHeading))
    fun shortestTurn(from:Float,to:Float):Float =
        (normalize(to)-normalize(from)+540f)%360f-180f

    fun easeTowards(from:Float,to:Float,weight:Float=.38f):Float =
        normalize(from+shortestTurn(from,to)*weight.coerceIn(0f,1f))

    fun shouldUpdate(from:Float,to:Float,deltaMs:Long):Boolean =
        deltaMs>=120L && abs(shortestTurn(from,to))>=3f

    /** Time-based low-pass filter. A frame animation keeps the camera smooth
     * while short-turn arithmetic stops the 359°→0° full-circle spin.
     * Tiny compass noise is absorbed to prevent perpetual tile redraw.
     */
    fun frameAngle(from:Float,target:Float,deltaMs:Long):Float {
        val distance=shortestTurn(from,target)
        if(abs(distance)<0.32f)return normalize(from)
        val elapsed=deltaMs.coerceIn(1L,80L).toDouble()
        val portion=(1.0-kotlin.math.exp(-elapsed/145.0)).toFloat()
        return normalize(from+distance*portion)
    }

    fun needsFrame(from:Float,target:Float):Boolean =
        abs(shortestTurn(from,target))>=0.32f
}
