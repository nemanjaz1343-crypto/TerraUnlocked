package com.terraunlocked

import android.location.Location
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Provisional OSM way geometry, drawn as DASHED overlay only.
 * NEVER writes inferred paths to DiscoveryStore or removes fog. */
data class InferredPath(
    val points:List<Pair<Double,Double>>,val startTime:Long,val endTime:Long,
    val confidence:Int=0
)

object SmartPathRecovery {
    private fun d(a:PathIntelligence.Point,b:PathIntelligence.Point)=
        PathIntelligence.metres(a,b)

    fun scan(records:List<DiscoveryPoint>,centerLat:Double,centerLon:Double):List<InferredPath> {
        val gps=records.filter{!it.manual && it.timestamp>0}
            .sortedBy{it.timestamp}
        val center=PathIntelligence.Point(centerLat,centerLon)
        val gaps=gps.zipWithNext().filter{(a,b)->
            val seconds=(b.timestamp-a.timestamp)/1000.0
            val start=PathIntelligence.Point(a.latitude,a.longitude)
            val end=PathIntelligence.Point(b.latitude,b.longitude)
            val dist=d(start,end)
            // 35-180 sec outages: high enough to distinguish from ordinary
            // accurate sampling, short enough to retain useful route context.
            seconds in 35.0..180.0 &&
                dist in 28.0..200.0 && dist/seconds<=7.0 &&
                d(start,center)<1600.0 && d(end,center)<1600.0 &&
                !TraceReconstruction.shouldJoin(
                    TraceReconstruction.Fix(a.latitude,a.longitude,a.timestamp,a.accuracy,a.speed),
                    TraceReconstruction.Fix(b.latitude,b.longitude,b.timestamp,b.accuracy,b.speed))
        }.takeLast(50)
        if(gaps.isEmpty())return emptyList()
        val south=centerLat-0.014;val north=centerLat+0.014
        val west=centerLon-0.016;val east=centerLon+0.016
        val query="[out:json][timeout:20];way[\"highway\"~\"^(footway|path|pedestrian|cycleway|living_street|residential|service)$\"]($south,$west,$north,$east);out geom;"
        val request="data="+URLEncoder.encode(query,"UTF-8")
        val sources=listOf("https://overpass.private.coffee/api/interpreter",
            "https://overpass-api.de/api/interpreter")
        var body:String?=null
        for(host in sources) {
            try {
                val conn=(URL(host).openConnection() as HttpURLConnection).apply {
                    requestMethod="POST"
                    doOutput=true
                    connectTimeout=9000
                    readTimeout=23000
                    setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8")
                    setRequestProperty("User-Agent","TerraUnlocked/Android OSM uncertain path")
                }
                try {
                    conn.outputStream.use{it.write(request.toByteArray(Charsets.UTF_8))}
                    if(conn.responseCode==200)body=conn.inputStream.bufferedReader()
                        .use{it.readText()}
                } finally {conn.disconnect()}
                if(body!=null)break
            } catch(_:Exception) { /* fallback to secondary mirror */ }
        }
        val data=body ?: return emptyList()
        val entries=JSONObject(data).optJSONArray("elements") ?: return emptyList()
        val ways=mutableListOf<List<PathIntelligence.Point>>()
        for(i in 0 until entries.length()) {
            val item=entries.optJSONObject(i) ?: continue
            val tags=item.optJSONObject("tags")
            if(tags?.optString("access")=="private" || tags?.optString("foot")=="no")continue
            val geometry=item.optJSONArray("geometry")?:continue
            if(geometry.length()<2)continue
            val way=(0 until geometry.length()).mapNotNull {j->
                val p=geometry.optJSONObject(j)?:return@mapNotNull null
                val lat=p.optDouble("lat",Double.NaN)
                val lon=p.optDouble("lon",Double.NaN)
                if(lat.isFinite() && lon.isFinite())PathIntelligence.Point(lat,lon) else null
            }
            if(way.size>=2)ways.add(way)
        }
        val proposed=mutableListOf<InferredPath>()
        for((a,b) in gaps) {
            val origin=PathIntelligence.Point(a.latitude,a.longitude)
            val destination=PathIntelligence.Point(b.latitude,b.longitude)
            val duration=(b.timestamp-a.timestamp)/1000.0
            val matches=ways.mapNotNull {line->
                val p=PathIntelligence.boundedMatch(origin,destination,line,14.0)
                    ?:return@mapNotNull null
                val length=p.zipWithNext().sumOf{(x,y)->d(x,y)}
                if(length>215.0 || length/duration>7.0)return@mapNotNull null
                val startSnap=PathIntelligence.project(origin,line)?.distanceMeters?:99.0
                val endSnap=PathIntelligence.project(destination,line)?.distanceMeters?:99.0
                val score=(90.0-(startSnap+endSnap)*1.7 -
                    (length-d(origin,destination))*.35).toInt().coerceIn(0,100)
                if(score<68)null else score to p
            }.sortedByDescending{it.first}
            // If there are multiple plausible streets within the positioning
            // uncertainty, we cannot tell which the user walked on.
            if(matches.size!=1)continue
            val (score,path)=matches.first()
            proposed.add(InferredPath(path.map{it.lat to it.lon},
                a.timestamp,b.timestamp,score))
        }
        return proposed
    }
}
