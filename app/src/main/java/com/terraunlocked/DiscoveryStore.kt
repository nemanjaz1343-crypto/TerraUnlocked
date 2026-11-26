package com.terraunlocked

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DiscoveryPoint(
    val latitude:Double,val longitude:Double,val manual:Boolean,val timestamp:Long,
    // Optional fix quality metadata; older files are still readable.
    val accuracy:Float=Float.NaN,val speed:Float=Float.NaN,
    val confidence:Int=0
)

class DiscoveryStore(context:Context) {
    companion object { private val sharedWriteLock=Any() }
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
                    p.getBoolean("manual"),p.getLong("time"),
                    p.optDouble("accuracy",Double.NaN).toFloat(),
                    p.optDouble("speed",Double.NaN).toFloat(),
                    p.optInt("confidence",0).coerceIn(0,100))
            }.getOrNull()
        }
    }
    private fun json(point:DiscoveryPoint)=JSONObject()
        .put("lat",point.latitude).put("lon",point.longitude)
        .put("manual",point.manual).put("time",point.timestamp)
        .also {
            if(point.accuracy.isFinite() && point.accuracy>=0f)
                it.put("accuracy",point.accuracy.toDouble())
            if(point.speed.isFinite() && point.speed>=0f)
                it.put("speed",point.speed.toDouble())
            if(point.confidence>0)it.put("confidence",point.confidence)
        }
    fun replaceAll(points:List<DiscoveryPoint>) = synchronized(sharedWriteLock) {
        val data=JSONArray()
        points.forEach {data.put(json(it))}
        val raw=data.toString()
        preferences.edit().putString("points",raw).apply()
        cachedRaw=raw;cachedArray=data
    }
    fun add(point:DiscoveryPoint) = addAll(listOf(point))

    /**
     * Append a complete pen stroke in one JSON pass. Synchronize across
     * DiscoveryStore instances (UI + GPS foreground service) so the brush
     * cannot overwrite a fix saved by the tracker during the same drag.
     */
    fun addAll(points:List<DiscoveryPoint>) = synchronized(sharedWriteLock) {
        if(points.isEmpty())return@synchronized
        val data=array()
        points.forEach{data.put(json(it))}
        val raw=data.toString()
        preferences.edit().putString("points",raw).apply()
        cachedRaw=raw;cachedArray=data
    }
}
