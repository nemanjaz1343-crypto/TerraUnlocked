package com.terraunlocked

/**
 * Conservative movement evidence when GNSS reports no trustworthy speed.
 * A straight-line displacement gives a lower bound on distance ridden;
 * it is NEVER a substitute for verified locations or permission to reveal fog.
 */
internal object SpeedEvidence {
    fun infer(distanceMeters:Float,seconds:Double,
        accuracyMeters:Float,previousAccuracyMeters:Float):Float {
        if(!distanceMeters.isFinite() || !seconds.isFinite() ||
            seconds !in 2.0..90.0 || !accuracyMeters.isFinite() ||
            !previousAccuracyMeters.isFinite() ||
            accuracyMeters !in 0.1f..35f ||
            previousAccuracyMeters !in 0.1f..35f)return Float.NaN
        val uncertainty=maxOf(accuracyMeters,previousAccuracyMeters)
        // Require displacement clearly larger than the position uncertainty.
        if(distanceMeters<maxOf(12f,uncertainty*1.3f))return Float.NaN
        val speed=distanceMeters/seconds
        if(speed !in 0.85..17.0)return Float.NaN
        return speed.toFloat()
    }

    fun combine(reported:Float,inferred:Float,seconds:Double,distance:Float,
        accuracy:Float,previousAccuracy:Float):Float {
        if(inferred.isFinite() && (!reported.isFinite() || reported<0.4f))
            return inferred
        // A stale near-zero GNSS speed can disagree with two accurate,
        // closely timed, spatially separated positions.
        if(inferred.isFinite() && reported.isFinite() && reported<1.5f &&
            seconds in 2.0..12.0 && accuracy<=20f && previousAccuracy<=20f &&
            distance>maxOf(24f,2f*maxOf(accuracy,previousAccuracy)) &&
            inferred>reported*2f+1f)return inferred
        return if(reported.isFinite() && reported>=0f)reported else Float.NaN
    }
}
