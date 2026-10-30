package com.terraunlocked

import android.location.Location
import java.util.Calendar
import java.util.TimeZone

data class ReconStats(val distanceMeters:Double,val gpsFixes:Int,val exploredCells:Int,val sessions:Int,val visitedMarkers:Int,val photoCount:Int)

object ExplorationStats {
    // Filter disconnected jumps, unrealistic travel speed and stationary GPS jitter.
    fun eligibleSteps(points:List<DiscoveryPoint>):List<Pair<DiscoveryPoint,DiscoveryPoint>> {
        val ordered=points.filter {!it.manual}.sortedBy {it.timestamp}
        return ordered.zipWithNext().filter {(a,b)->
            val seconds=(b.timestamp-a.timestamp)/1000.0
            if(seconds<=0.0 || seconds>90.0)return@filter false
            val meters=FloatArray(1)
            Location.distanceBetween(a.latitude,a.longitude,b.latitude,b.longitude,meters)
            meters[0] in 5f..120f && meters[0]/seconds<=12.0
        }
    }
    fun calculate(points:List<DiscoveryPoint>,notes:List<FieldNote>,until:Long=Long.MAX_VALUE):ReconStats {
        val filtered=points.filter {it.timestamp<=until}
        val steps=eligibleSteps(filtered)
        val distance=steps.sumOf {(a,b)->
            val m=FloatArray(1)
            Location.distanceBetween(a.latitude,a.longitude,b.latitude,b.longitude,m)
            m[0].toDouble()
        }
        // Approximate coverage by distinct ~20m cells, not exact polygon surface area.
        val cells=filtered.map {p->
            val y=(p.latitude*111320/20).toLong()
            val x=(p.longitude*111320*kotlin.math.cos(Math.toRadians(p.latitude))/20).toLong()
            x to y
        }.toSet()
        val dates=filtered.filter {!it.manual}.map {p->
            val calendar=Calendar.getInstance(TimeZone.getDefault())
            calendar.timeInMillis=p.timestamp
            calendar.get(Calendar.YEAR)*1000+calendar.get(Calendar.DAY_OF_YEAR)
        }.toSet()
        return ReconStats(distance,filtered.count{!it.manual},cells.size,dates.size,
            notes.count {it.time<=until && it.visits.any {v->v<=until}},
            notes.filter{it.time<=until}.sumOf {it.attachments.count{a->!a.endsWith(".m4a")} })
    }
}
