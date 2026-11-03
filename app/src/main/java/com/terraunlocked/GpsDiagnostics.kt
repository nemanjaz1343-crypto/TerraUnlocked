package com.terraunlocked

import android.content.Context
import android.location.Location
import java.io.File
import java.io.OutputStream
import java.util.Locale

/**
 * Private, bounded GPS diagnostics. Export is an explicit user action.
 * Contains precise location history; never transmitted automatically.
 */
internal class GpsDiagnostics(context:Context) {
    private val file=File(context.filesDir,"terra_gps_diagnostics.csv")
    private val header="recorded_utc_ms,fix_utc_ms,lat,lon,accuracy_m,speed_mps,age_ms,dt_s,step_m,confidence,mode,decision,reason\n"

    @Synchronized fun record(
        loc:Location?,ageMs:Long,dt:Double,step:Double,
        confidence:Int,mode:String,decision:String,reason:String
    ) {
        runCatching {
            if(!file.exists())file.writeText(header)
            if(file.length()>1_500_000L) {
                val tail=file.readLines().takeLast(4500)
                file.writeText(header+tail.filter{it.isNotBlank() && !it.startsWith("recorded_utc_ms")}.joinToString("\n")+"\n")
            }
            val now=System.currentTimeMillis()
            val fixTime=loc?.time?.takeIf{it>0L} ?: now
            fun number(n:Double,digits:Int):String =
                if(n.isFinite())String.format(Locale.US,"%.${digits}f",n) else ""
            val line=listOf(
                now.toString(),fixTime.toString(),
                loc?.latitude?.let{number(it,7)} ?: "",
                loc?.longitude?.let{number(it,7)} ?: "",
                loc?.takeIf{it.hasAccuracy()}?.accuracy?.toDouble()?.let{number(it,1)} ?: "",
                loc?.takeIf{it.hasSpeed()}?.speed?.toDouble()?.let{number(it,2)} ?: "",
                ageMs.takeIf{it>=0}?.toString() ?: "",
                number(dt,2),number(step,1),
                confidence.coerceIn(0,100).toString(),
                mode,decision,reason
            ).joinToString(",")+"\n"
            file.appendText(line)
        }
    }

    fun exportTo(output:OutputStream) {
        if(file.exists())file.inputStream().use{it.copyTo(output)}
        else output.write(header.toByteArray(Charsets.UTF_8))
    }
    fun summary():String {
        if(!file.exists())return "No GPS samples recorded yet."
        var received=0;var accepted=0;var rejected=0;var gaps=0
        file.forEachLine {line->
            if(line.startsWith("recorded_utc_ms"))return@forEachLine
            received++
            when(line.split(",").getOrNull(11)) {
                "SAVED" -> accepted++
                "REJECTED" -> rejected++
                "NO_FIX" -> gaps++
            }
        }
        return "GPS samples: $received / saved: $accepted / rejected: $rejected / no-fix alerts: $gaps"
    }
    fun clear() {file.writeText(header)}
}
