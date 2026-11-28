package com.terraunlocked

/** Repeated LOCATE taps zoom toward the user's position as in older builds.
 * A world-scale view first jumps to city scale, then each tap gets 1.5x closer.
 * GPS/visited history is not changed by centring the camera.
 */
internal object LocateZoomPolicy {
    const val STEP=0.5849625007211562 // log2(1.5)
    fun next(current:Double,maxZoom:Double):Double {
        val cap=if(maxZoom.isFinite()) maxZoom.coerceAtLeast(1.0) else 19.0
        val start=if(current.isFinite()) current.coerceAtLeast(1.0) else 14.5
        return ((if(start<10.0)14.5 else start)+STEP).coerceAtMost(cap)
    }
}
