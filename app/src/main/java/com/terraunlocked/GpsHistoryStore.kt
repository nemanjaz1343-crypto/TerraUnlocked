package com.terraunlocked

import android.content.Context
import android.location.Location
import java.io.File
import java.util.Locale

/**
 * Separate, private GPS data streams:
 * - raw measurements (including fixes unsuitable for discovery)
 * - verified positions grouped into contiguous trace segments.
 *
 * These files never upload automatically. Archive export is a user action.
 * The durable discoveries store remains authoritative for unlocking fog.
 */
internal class GpsHistoryStore(context:Context) {
    private val raw=File(context.filesDir,"terra_gps_raw.csv")
    private val trace=File(context.filesDir,"terra_gps_trace.csv")
    private val rawHeader="fix_utc_ms,lat,lon,accuracy_m,speed_mps,age_ms\n"
    private val traceHeader="segment,fix_utc_ms,lat,lon,accuracy_m,speed_mps\n"
    private var previous:TraceReconstruction.Fix?=null
    private var segmentId=runCatching {
        trace.useLines { lines ->
            lines.drop(1).lastOrNull()?.substringBefore(',')?.toIntOrNull() ?: 0
        }
    }.getOrDefault(0)

    private fun number(value:Double,digits:Int):String =
        if(value.isFinite())String.format(Locale.US,"%.${digits}f",value) else ""

    /** Bounded local history: more recent raw fixes survive indefinitely long sessions. */
    private fun append(file:File,header:String,line:String,maxBytes:Long,keepLines:Int) {
        runCatching {
            if(!file.exists())file.writeText(header)
            if(file.length()>maxBytes) {
                val tail=file.useLines { it.filter { row ->
                    row.isNotBlank() && row!=header.trim()
                }.toList().takeLast(keepLines) }
                file.writeText(header+tail.joinToString("\n",postfix=if(tail.isEmpty())"" else "\n"))
            }
            file.appendText(line)
        }
    }

    @Synchronized fun recordRaw(loc:Location,ageMs:Long) {
        if(!loc.latitude.isFinite() || !loc.longitude.isFinite())return
        val speed=if(loc.hasSpeed())loc.speed.toDouble() else Double.NaN
        append(raw,rawHeader,listOf(
            loc.time.toString(),number(loc.latitude,7),number(loc.longitude,7),
            if(loc.hasAccuracy())number(loc.accuracy.toDouble(),1) else "",
            number(speed,2),ageMs.toString()
        ).joinToString(",")+"\n",2_000_000L,10000)
    }

    @Synchronized fun recordVerified(loc:Location,measuredAt:Long,speed:Float) {
        if(!loc.latitude.isFinite() || !loc.longitude.isFinite())return
        val current=TraceReconstruction.Fix(
            loc.latitude,loc.longitude,measuredAt,loc.accuracy,speed
        )
        val contiguous=previous?.let {TraceReconstruction.shouldJoin(it,current)} ?: false
        if(!contiguous)segmentId++
        append(trace,traceHeader,listOf(
            segmentId.toString(),measuredAt.toString(),
            number(loc.latitude,7),number(loc.longitude,7),
            number(loc.accuracy.toDouble(),1),number(speed.toDouble(),2)
        ).joinToString(",")+"\n",8_000_000L,70000)
        previous=current
    }

    @Synchronized fun clear() {
        previous=null
        segmentId=0
        raw.writeText(rawHeader)
        trace.writeText(traceHeader)
    }
}
