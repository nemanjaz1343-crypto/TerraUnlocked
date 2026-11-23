package com.terraunlocked

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos

/**
 * A mapped shopping destination; never inferred from a review/rating or from
 * a nearby supermarket. Coordinates and names come from OpenStreetMap only.
 */
internal data class MallPlace(
    val id:String,
    val name:String,
    val latitude:Double,
    val longitude:Double,
    val kind:String="MALL",
    val district:String="",
    val website:String="",
    val openingHours:String=""
)

internal object MallCatalog {
    private fun normalize(name:String):String = name.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+")," ").trim()
        .removePrefix("the ").trim()

    /**
     * Distinctive, verified landmarks get editorial descriptions.
     * This is NOT a popularity or footfall ranking. Every other verified
     * shopping centre remains visible when zoomed in.
     */
    fun signature(name:String):String? {
        val n=normalize(name)
        return when {
            n=="dubai mall" -> "A Downtown landmark with aquarium, ice rink and Burj Khalifa surroundings."
            n=="mall of the emirates" || n=="emirates mall" ->
                "The Al Barsha destination known for Ski Dubai's indoor slopes."
            n=="ibn battuta mall" -> "Six themed courts inspired by the travels of Ibn Battuta."
            n=="wafi mall" || n=="wafi city" || n=="wafi city mall" ->
                "Egyptian-inspired architecture, art and interiors give this mall its identity."
            n=="mercato mall" || n=="mercato shopping mall" ->
                "A Renaissance-inspired shopping arcade with an Italian streetscape atmosphere."
            n=="dubai festival city mall" || n=="festival city mall" ->
                "Dubai Creek waterfront setting, dining and the IMAGINE light-and-water show."
            n=="dubai marina mall" ->
                "An approachable waterfront mall beside the Marina promenade."
            n=="dubai hills mall" ->
                "A contemporary mall combining retail, family entertainment and a coaster."
            n=="city walk" || n=="city walk dubai" ->
                "An open-air urban district blending restaurants, art and shopping."
            n=="nakheel mall" || n=="palm jumeirah mall" ->
                "Shopping on Palm Jumeirah, with The View at the Palm nearby."
            n=="dragon mart" || n=="dragon mart 1" || n=="dragon mart 2" ->
                "An expansive trading and shopping destination in International City."
            n=="dubai outlet mall" -> "An outlet-focused retail destination outside central Dubai."
            n=="outlet village" || n=="the outlet village" ->
                "Designer outlets with Tuscan-inspired outdoor architecture."
            n=="souq madinat jumeirah" || n=="souk madinat jumeirah" ->
                "Traditional souk-inspired lanes, waterways and views toward Burj Al Arab."
            n=="city centre deira" || n=="deira city centre" ->
                "An established shopping destination rooted in Deira's everyday city life."
            n=="city centre mirdif" || n=="mirdif city centre" ->
                "A family-focused shopping and entertainment destination in Mirdif."
            else->null
        }
    }

    fun featured(name:String):Boolean=signature(name)!=null

    /** OSM ways, relations and nodes can all map the same named centre. */
    fun distinct(items:List<MallPlace>):List<MallPlace> {
        val output=ArrayList<MallPlace>(items.size)
        for(place in items.sortedWith(
            compareByDescending<MallPlace>{featured(it.name)}.thenBy{it.name}
        )) {
            val normal=normalize(place.name)
            val duplicate=output.any {saved->
                normalize(saved.name)==normal &&
                    closeMetres(saved.latitude,saved.longitude,
                        place.latitude,place.longitude)<350.0
            }
            if(!duplicate)output.add(place)
        }
        return output
    }

    private fun closeMetres(a:Double,b:Double,c:Double,d:Double):Double {
        val dy=(c-a)*111195.0
        val dx=(d-b)*111195.0*cos(Math.toRadians((a+c)*.5))
        return kotlin.math.hypot(dx,dy)
    }
}

/**
 * Low-rate OSM Overpass queries around the currently displayed map.
 * Long-lived local cache works offline; API is never queried per touch/frame.
 */
internal class MallStore(context:Context) {
    private val prefs=context.getSharedPreferences("terra_malls_osm_v1",Context.MODE_PRIVATE)
    fun lastFetchedAt():Long=prefs.getLong("fetched_at",0L)
    fun cachedCenter():GeoPoint? {
        val lat=prefs.getString("center_lat",null)?.toDoubleOrNull()
        val lon=prefs.getString("center_lon",null)?.toDoubleOrNull()
        return if(lat!=null&&lon!=null&&lat in -90.0..90.0&&lon in -180.0..180.0)
            GeoPoint(lat,lon) else null
    }

    fun load():List<MallPlace> =runCatching {
        val data=JSONArray(prefs.getString("places","[]"))
        val result=ArrayList<MallPlace>(data.length())
        for(i in 0 until data.length().coerceAtMost(500)) {
            val j=data.optJSONObject(i)?:continue
            val lat=j.optDouble("lat",Double.NaN)
            val lon=j.optDouble("lon",Double.NaN)
            if(!lat.isFinite()||!lon.isFinite()||lat !in -90.0..90.0 ||
                lon !in -180.0..180.0)continue
            val name=j.optString("name","").trim()
            if(name.isEmpty())continue
            result.add(MallPlace(j.optString("id"),name,lat,lon,
                j.optString("kind","MALL"),j.optString("district"),
                j.optString("website"),j.optString("hours")))
        }
        MallCatalog.distinct(result)
    }.getOrDefault(emptyList())

    fun fetch(center:GeoPoint):List<MallPlace> {
        require(center.latitude in -89.0..89.0 && center.longitude in -179.0..179.0)
        val lat=center.latitude
        val lon=center.longitude
        val halfHeight=.098 // ~11 km each way
        val halfWidth=(halfHeight/cos(Math.toRadians(lat)).coerceAtLeast(.35))
        val south=(lat-halfHeight).coerceAtLeast(-90.0)
        val north=(lat+halfHeight).coerceAtMost(90.0)
        val west=(lon-halfWidth).coerceAtLeast(-180.0)
        val east=(lon+halfWidth).coerceAtMost(180.0)
        val bbox="$south,$west,$north,$east"
        val query="[out:json][timeout:25];("+
            "nwr[\"shop\"=\"mall\"]($bbox);"+
            "nwr[\"shop\"=\"shopping_centre\"]($bbox);"+
            "nwr[\"shop\"=\"shopping_center\"]($bbox);"+
            "nwr[\"building\"=\"mall\"][\"name\"]($bbox);"+
            "nwr[\"landuse\"=\"retail\"][\"name\"~\"Mall|Centre|Center|Souk|Galleria\",i]($bbox);"+
            ");out center 400;"
        val payload="data="+java.net.URLEncoder.encode(query,"UTF-8")
        val hosts=listOf(
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass-api.de/api/interpreter"
        )
        var json:String?=null
        var error:Throwable?=null
        for(host in hosts) {
            try {
                val connection=(URL(host).openConnection() as HttpURLConnection).apply{
                    requestMethod="POST";doOutput=true
                    connectTimeout=9000;readTimeout=30000
                    setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8")
                    setRequestProperty("User-Agent","TerraUnlocked/Android Malls OpenStreetMap")
                }
                try {
                    connection.outputStream.use{
                        it.write(payload.toByteArray(Charsets.UTF_8))
                    }
                    if(connection.responseCode!=200)
                        throw java.io.IOException("Overpass HTTP "+connection.responseCode)
                    json=connection.inputStream.bufferedReader(Charsets.UTF_8).use{it.readText()}
                }finally{connection.disconnect()}
                if(json!=null)break
            }catch(cause:Exception){error=cause}
        }
        val response=json?:throw java.io.IOException("OSM malls service unavailable",error)
        val elements=JSONObject(response).optJSONArray("elements")?:JSONArray()
        val found=ArrayList<MallPlace>(elements.length().coerceAtMost(400))
        for(i in 0 until elements.length().coerceAtMost(400)) {
            val element=elements.optJSONObject(i)?:continue
            val tags=element.optJSONObject("tags")?:continue
            val name=tags.optString("name:en").ifBlank{tags.optString("name")}.trim()
            if(name.isBlank())continue // don't make up venue names
            val point=element.optJSONObject("center")
            val y=if(element.has("lat"))element.optDouble("lat",Double.NaN)
                else point?.optDouble("lat",Double.NaN)?:Double.NaN
            val x=if(element.has("lon"))element.optDouble("lon",Double.NaN)
                else point?.optDouble("lon",Double.NaN)?:Double.NaN
            if(!x.isFinite()||!y.isFinite()||y !in -90.0..90.0||
                x !in -180.0..180.0)continue
            val type=element.optString("type")
            if(type !in listOf("node","way","relation"))continue
            val mall=tags.optString("shop")
            val kind=if(mall=="shopping_centre"||mall=="shopping_center")
                "SHOPPING CENTRE" else "MALL"
            val website=tags.optString("website").ifBlank{
                tags.optString("contact:website")
            }.takeIf{it.startsWith("https://")} ?:""
            found.add(MallPlace(type+"_"+element.optLong("id"),
                name,y,x,kind,tags.optString("addr:suburb").ifBlank{
                    tags.optString("addr:city")
                },website,tags.optString("opening_hours")))
        }
        // Cache a rolling set of verified mapped centres as the user pans
        // across Dubai. Keep a hard bound to avoid accumulating unbounded POIs.
        val merged=MallCatalog.distinct(found+load()).take(350)
        val stored=JSONArray()
        merged.forEach {m->
            stored.put(JSONObject().put("id",m.id)
                .put("name",m.name).put("lat",m.latitude).put("lon",m.longitude)
                .put("kind",m.kind).put("district",m.district)
                .put("website",m.website).put("hours",m.openingHours))
        }
        prefs.edit().putString("places",stored.toString())
            .putString("center_lat",lat.toString())
            .putString("center_lon",lon.toString())
            .putLong("fetched_at",System.currentTimeMillis()).apply()
        return merged
    }
}
