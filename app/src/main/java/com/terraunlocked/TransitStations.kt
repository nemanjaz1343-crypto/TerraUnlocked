package com.terraunlocked

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

data class TransitStation(val name:String,val lat:Double,val lon:Double)
class TransitStations(private val context:Context) {
    private val prefs=context.getSharedPreferences("terra_transit_v1",Context.MODE_PRIVATE)
    fun load():List<TransitStation> = runCatching {
        val data=JSONArray(prefs.getString("stations","[]"))
        (0 until data.length()).map {i->
            val p=data.getJSONObject(i)
            TransitStation(p.getString("name"),p.getDouble("lat"),p.getDouble("lon"))
        }
    }.getOrDefault(emptyList())
    fun refresh():List<TransitStation> {
        // OSM data, not live arrivals. Network request runs off the UI thread.
        val query="[out:json][timeout:30];(node[\"railway\"=\"station\"][\"station\"=\"subway\"](24.8,54.9,25.4,55.6);way[\"railway\"=\"station\"][\"station\"=\"subway\"](24.8,54.9,25.4,55.6););out center;"
        val conn=(URL("https://overpass.kumi.systems/api/interpreter").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod="POST";doOutput=true;connectTimeout=15000;readTimeout=35000
            setRequestProperty("Content-Type","application/x-www-form-urlencoded")
        }
        val payload="data="+java.net.URLEncoder.encode(query,"UTF-8")
        try {
            conn.outputStream.use {it.write(payload.toByteArray())}
            val body=conn.inputStream.bufferedReader().use {it.readText()}
            val array=JSONObject(body).getJSONArray("elements")
            val result=mutableListOf<TransitStation>()
            for(i in 0 until array.length()) {
                val obj=array.getJSONObject(i)
                val tags=obj.optJSONObject("tags") ?: continue
                val name=tags.optString("name:en",tags.optString("name"))
                if(name.isBlank())continue
                val center=obj.optJSONObject("center")
                val lat=if(obj.has("lat"))obj.getDouble("lat") else center?.optDouble("lat") ?: continue
                val lon=if(obj.has("lon"))obj.getDouble("lon") else center?.optDouble("lon") ?: continue
                if(result.none {it.name.equals(name,true)})result.add(TransitStation(name,lat,lon))
            }
            if(result.isNotEmpty()) {
                val out=JSONArray()
                result.forEach {out.put(JSONObject().put("name",it.name).put("lat",it.lat).put("lon",it.lon))}
                prefs.edit().putString("stations",out.toString()).apply()
            }
            return result
        } finally {conn.disconnect()}
    }
}