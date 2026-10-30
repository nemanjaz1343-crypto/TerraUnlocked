package com.terraunlocked

import android.location.Location
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

/** Conservative, non-persistent OSM way matching. Never modifies recorded GPS points. */
data class InferredPath(val points:List<Pair<Double,Double>>,val startTime:Long,val endTime:Long)
object SmartPathRecovery {
    private fun dist(a:Pair<Double,Double>,b:Pair<Double,Double>):Double {
        val out=FloatArray(1)
        Location.distanceBetween(a.first,a.second,b.first,b.second,out)
        return out[0].toDouble()
    }
    private fun closestIndex(p:Pair<Double,Double>,line:List<Pair<Double,Double>>):Int =
        line.indices.minByOrNull {dist(p,line[it])} ?: 0

    fun scan(records:List<DiscoveryPoint>,centerLat:Double,centerLon:Double):List<InferredPath> {
        val gps=records.filter {!it.manual && it.timestamp>0}.sortedBy {it.timestamp}
        val candidates=gps.zipWithNext().filter {(a,b)->
            val seconds=(b.timestamp-a.timestamp)/1000.0
            val d=dist(a.latitude to a.longitude,b.latitude to b.longitude)
            seconds in 90.0..180.0 && d in 25.0..180.0 && d/seconds<=11.0 &&
                dist(a.latitude to a.longitude,centerLat to centerLon)<1400
        }.takeLast(75)
        if(candidates.isEmpty())return emptyList()
        // Read only short OSM footway/cycleway/pedestrian/residential geometries around current map area.
        val south=centerLat-0.014;val north=centerLat+0.014
        val west=centerLon-0.016;val east=centerLon+0.016
        val query="[out:json][timeout:25];way[\"highway\"~\"footway|path|pedestrian|cycleway|living_street|residential\"]($south,$west,$north,$east);out geom;"
        val conn=(URL("https://overpass.kumi.systems/api/interpreter").openConnection() as HttpURLConnection).apply {
            requestMethod="POST";doOutput=true;connectTimeout=12000;readTimeout=30000
            setRequestProperty("Content-Type","application/x-www-form-urlencoded")
        }
        val root=try {
            conn.outputStream.use {it.write(("data="+URLEncoder.encode(query,"UTF-8")).toByteArray())}
            JSONObject(conn.inputStream.bufferedReader().use {it.readText()})
        } finally {conn.disconnect()}
        val ways=mutableListOf<List<Pair<Double,Double>>>()
        val entries=root.getJSONArray("elements")
        for(i in 0 until entries.length()) {
            val obj=entries.getJSONObject(i)
            val geom=obj.optJSONArray("geometry") ?: continue
            if(geom.length()<2)continue
            val line=(0 until geom.length()).map {j->
                val p=geom.getJSONObject(j)
                p.getDouble("lat") to p.getDouble("lon")
            }
            ways.add(line)
        }
        val proposed=mutableListOf<InferredPath>()
        for((a,b) in candidates) {
            val start=a.latitude to a.longitude;val end=b.latitude to b.longitude
            val straight=dist(start,end)
            val matches=mutableListOf<Pair<Double,List<Pair<Double,Double>>>>()
            for(line in ways) {
                val ai=closestIndex(start,line);val bi=closestIndex(end,line)
                if(ai==bi || dist(start,line[ai])>14 || dist(end,line[bi])>14)continue
                val subsection=if(ai<bi)line.subList(ai,bi+1) else line.subList(bi,ai+1).reversed()
                val path= listOf(start)+subsection+listOf(end)
                val length=path.zipWithNext().sumOf { (u,v)->dist(u,v) }
                if(length>225 || length>straight*1.4 || length/((b.timestamp-a.timestamp)/1000.0)>11)continue
                matches.add(length to path)
            }
            // Several plausible ways => no automatic reconstruction.
            if(matches.size!=1)continue
            proposed.add(InferredPath(matches.single().second,a.timestamp,b.timestamp))
        }
        return proposed
    }
}
