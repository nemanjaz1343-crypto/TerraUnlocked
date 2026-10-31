package com.terraunlocked

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DiscoveryPoint(val latitude:Double,val longitude:Double,val manual:Boolean,val timestamp:Long)

class DiscoveryStore(context:Context) {
    private val preferences=context.getSharedPreferences("terra_discoveries_v1",Context.MODE_PRIVATE)
    // A single cached JSONArray avoids parsing the ENTIRE exploration history
    // on every GPS fix. Detect cross-instance edits before appending.
    private var cachedRaw:String?=null
    private var cachedArray:JSONArray?=null
    private fun array():JSONArray {
        val raw=preferences.getString("points","[]")?:"[]"
        cachedArray?.let {if(cachedRaw==raw)return it}
        return JSONArray(raw).also {cachedRaw=raw;cachedArray=it}
    }
    fun load():List<DiscoveryPoint> {
        val data=array()
        return (0 until data.length()).mapNotNull {index->
            runCatching {
                val p=data.getJSONObject(index)
                DiscoveryPoint(p.getDouble("lat"),p.getDouble("lon"),
                    p.getBoolean("manual"),p.getLong("time"))
            }.getOrNull()
        }
    }
    private fun json(point:DiscoveryPoint)=JSONObject()
        .put("lat",point.latitude).put("lon",point.longitude)
        .put("manual",point.manual).put("time",point.timestamp)
    fun replaceAll(points:List<DiscoveryPoint>) {
        val data=JSONArray()
        points.forEach {data.put(json(it))}
        val raw=data.toString()
        preferences.edit().putString("points",raw).apply()
        cachedRaw=raw;cachedArray=data
    }
    fun add(point:DiscoveryPoint) {
        val data=array()
        data.put(json(point))
        val raw=data.toString()
        preferences.edit().putString("points",raw).apply()
        cachedRaw=raw;cachedArray=data
    }
}
