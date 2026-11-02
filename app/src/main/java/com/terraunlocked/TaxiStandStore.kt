package com.terraunlocked

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos

/** Verified OSM amenity=taxi ranks, not GPS positions of live taxis. */
data class TaxiStand(
    val id:String, val name:String, val latitude:Double, val longitude:Double,
    val operator:String=""
)

class TaxiStandStore(context:Context) {
    private val prefs=context.getSharedPreferences("terra_taxi_ranks_v1",Context.MODE_PRIVATE)

    fun lastFetchedAt():Long=prefs.getLong("fetched_at",0L)

    fun cachedCenter():GeoPoint? {
        val lat=prefs.getString("center_lat",null)?.toDoubleOrNull()
        val lon=prefs.getString("center_lon",null)?.toDoubleOrNull()
        return if(lat!=null && lon!=null && lat in -90.0..90.0 && lon in -180.0..180.0)
            GeoPoint(lat,lon) else null
    }

    fun load():List<TaxiStand> = runCatching {
        val array=JSONArray(prefs.getString("ranks","[]"))
        (0 until array.length()).mapNotNull { i ->
            val v=array.optJSONObject(i) ?: return@mapNotNull null
            val lat=v.optDouble("lat",Double.NaN)
            val lon=v.optDouble("lon",Double.NaN)
            if(!lat.isFinite() || !lon.isFinite())return@mapNotNull null
            TaxiStand(v.optString("id"),v.optString("name","Taxi stand"),
                lat,lon,v.optString("operator"))
        }
    }.getOrDefault(emptyList())

    /**
     * Load a roughly 8-km-wide map area; only OSM amenity=taxi features.
     * Never synthesize ranks or confuse a generic taxi pickup request with a rank.
     */
    fun fetch(center:GeoPoint):List<TaxiStand> {
        val lat=center.latitude
        val lon=center.longitude
        val halfHeight=0.036
        val halfWidth=halfHeight/cos(Math.toRadians(lat)).coerceAtLeast(0.3)
        val south=(lat-halfHeight).coerceAtLeast(-90.0)
        val north=(lat+halfHeight).coerceAtMost(90.0)
        val west=(lon-halfWidth).coerceAtLeast(-180.0)
        val east=(lon+halfWidth).coerceAtMost(180.0)
        val bounds="$south,$west,$north,$east"
        val query="[out:json][timeout:20];("+
            "node[\"amenity\"=\"taxi\"]($bounds);"+
            "way[\"amenity\"=\"taxi\"]($bounds);"+
            ");out center 200;"
        val connection=(URL("https://overpass.kumi.systems/api/interpreter")
            .openConnection() as HttpURLConnection).apply {
            requestMethod="POST"
            doOutput=true
            connectTimeout=12000
            readTimeout=26000
            setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
            setRequestProperty("User-Agent","TerraUnlocked/Android OSM taxi-rank layer")
        }
        val results=try {
            val request="data="+java.net.URLEncoder.encode(query,"UTF-8")
            connection.outputStream.use {it.write(request.toByteArray(Charsets.UTF_8))}
            require(connection.responseCode==200) {
                "Overpass returned "+connection.responseCode
            }
            val body=connection.inputStream.bufferedReader(Charsets.UTF_8)
                .use {it.readText()}
            val elements=JSONObject(body).optJSONArray("elements") ?: JSONArray()
            val found=LinkedHashMap<String,TaxiStand>()
            for(i in 0 until elements.length()) {
                val item=elements.optJSONObject(i) ?: continue
                val tags=item.optJSONObject("tags") ?: continue
                // Water taxi and motorcycle taxi ranks are not Dubai road taxi stands.
                val vehicle=tags.optString("taxi_vehicle")
                if(vehicle.contains("boat",true) || vehicle.contains("motorcycle",true))continue
                val geo=item.optJSONObject("center")
                val y=if(item.has("lat"))item.optDouble("lat",Double.NaN)
                    else geo?.optDouble("lat",Double.NaN) ?: Double.NaN
                val x=if(item.has("lon"))item.optDouble("lon",Double.NaN)
                    else geo?.optDouble("lon",Double.NaN) ?: Double.NaN
                if(!x.isFinite() || !y.isFinite() ||
                    x !in -180.0..180.0 || y !in -90.0..90.0)continue
                val id=item.optString("type")+"_"+item.optLong("id")
                found[id]=TaxiStand(
                    id,tags.optString("name:en").ifBlank {
                        tags.optString("name").ifBlank{"Taxi stand"}
                    },y,x,tags.optString("operator")
                )
            }
            found.values.toList()
        } finally {
            connection.disconnect()
        }
        val archive=JSONArray()
        results.forEach {stand ->
            archive.put(JSONObject()
                .put("id",stand.id).put("name",stand.name)
                .put("lat",stand.latitude).put("lon",stand.longitude)
                .put("operator",stand.operator))
        }
        prefs.edit().putString("ranks",archive.toString())
            .putString("center_lat",lat.toString())
            .putString("center_lon",lon.toString())
            .putLong("fetched_at",System.currentTimeMillis()).apply()
        return results
    }
}
