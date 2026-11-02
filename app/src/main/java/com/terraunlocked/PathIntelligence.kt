package com.terraunlocked

import kotlin.math.*

/** Evidence score, not a probability that the user visited a place.
 * Only trusted GNSS/Fused fixes can permanently reveal fog. */
internal object PathIntelligence {
    data class Result(val score:Int,val tier:String,val accepted:Boolean)
    fun assess(
        accuracy:Float,ageMillis:Long,
        reportedSpeed:Float,displacementMeters:Double,deltaSeconds:Double,
        inertialActive:Boolean
    ):Result {
        val base=when {
            !accuracy.isFinite() || accuracy<=0f -> 0
            accuracy<=10f -> 96
            accuracy<=18f -> 88
            accuracy<=25f -> 80
            accuracy<=30f -> 74
            accuracy<=35f -> 69
            accuracy<=50f -> 50
            accuracy<=65f -> 36
            else -> 12
        }
        var score=base
        if(ageMillis>12000)score-=12
        if(ageMillis>20000)score-=20
        if(deltaSeconds>0.3 && deltaSeconds<35.0) {
            val measured=displacementMeters/deltaSeconds
            val upper=if(reportedSpeed.isFinite() && reportedSpeed>=0f)
                max(8.0,reportedSpeed*2.7+6.0) else 38.0
            if(measured>upper && displacementMeters>40)score-=42
        }
        // Inertial activity is supporting evidence, not proof of location.
        if(inertialActive && reportedSpeed.isFinite() && reportedSpeed>=0.75f &&
            accuracy<=35f)score+=3
        score=score.coerceIn(0,100)
        val tier=when {
            score>=65 && accuracy<=35f -> "CONFIRMED"
            score>=35 -> "ESTIMATED"
            else -> "LOW"
        }
        return Result(score,tier,tier=="CONFIRMED")
    }

    data class Point(val lat:Double,val lon:Double)
    data class Projection(val point:Point,val distanceMeters:Double,val along:Double)

    fun metres(a:Point,b:Point):Double {
        val mLat=(a.lat+b.lat)*.5
        return hypot((b.lat-a.lat)*111195.0,
            (b.lon-a.lon)*111195.0*cos(Math.toRadians(mLat)))
    }

    /** Projection to OSM way *segments*, not just nodes, which may be 100m apart. */
    fun project(query:Point,line:List<Point>):Projection? {
        if(line.size<2)return null
        val latScale=111195.0
        val lonScale=latScale*cos(Math.toRadians(query.lat)).coerceAtLeast(.05)
        var best:Projection?=null
        for(i in 0 until line.size-1) {
            val a=line[i];val b=line[i+1]
            val ax=(a.lon-query.lon)*lonScale
            val ay=(a.lat-query.lat)*latScale
            val vx=(b.lon-a.lon)*lonScale
            val vy=(b.lat-a.lat)*latScale
            val length2=vx*vx+vy*vy
            if(length2<0.01)continue
            val t=(-(ax*vx+ay*vy)/length2).coerceIn(0.0,1.0)
            val dx=ax+vx*t
            val dy=ay+vy*t
            val dist=hypot(dx,dy)
            if(best==null || dist<best.distanceMeters)
                best=Projection(Point(a.lat+(b.lat-a.lat)*t,a.lon+(b.lon-a.lon)*t),
                    dist,i+t)
        }
        return best
    }

    fun boundedMatch(
        start:Point,end:Point,way:List<Point>,maxSnapMeters:Double=16.0
    ):List<Point>? {
        val a=project(start,way)?:return null
        val b=project(end,way)?:return null
        if(a.distanceMeters>maxSnapMeters || b.distanceMeters>maxSnapMeters)return null
        if(abs(a.along-b.along)<0.02)return null
        val interior=if(a.along<b.along)
            way.indices.filter{it>a.along && it<b.along}
                .map{way[it]} else
            way.indices.filter{it<b.along && it>a.along}
                .reversed().map{way[it]}
        val route=listOf(start,a.point)+interior+listOf(b.point,end)
        val length=route.zipWithNext().sumOf{metres(it.first,it.second)}
        val direct=metres(start,end)
        if(length>min(240.0,direct*1.55+15.0))return null
        return route
    }
}
