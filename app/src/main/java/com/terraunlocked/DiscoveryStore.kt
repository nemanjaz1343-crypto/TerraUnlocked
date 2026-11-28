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
    companion object {
        private val sharedWriteLock=Any()
        private const val PENDING_MANUAL="pending_manual_strokes"
        private val manualWriter=java.util.concurrent.Executors.newSingleThreadExecutor {
            work->Thread(work,"terra-brush-commit").apply{isDaemon=true}
        }
    }
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
    private fun decode(data:JSONArray):List<DiscoveryPoint> =
        (0 until data.length()).mapNotNull {index->
            runCatching {
                val p=data.getJSONObject(index)
                DiscoveryPoint(p.getDouble("lat"),p.getDouble("lon"),
                    p.getBoolean("manual"),p.getLong("time"),
                    p.optDouble("accuracy",Double.NaN).toFloat(),
                    p.optDouble("speed",Double.NaN).toFloat(),
                    p.optInt("confidence",0).coerceIn(0,100))
            }.getOrNull()
        }

    fun load():List<DiscoveryPoint> = synchronized(sharedWriteLock) {
        val pending=runCatching {
            JSONArray(preferences.getString(PENDING_MANUAL,"[]"))
        }.getOrDefault(JSONArray())
        val result=decode(array())+decode(pending)
        if(pending.length()>0)manualWriter.execute{flushPending()}
        result
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
        // Undo, erase and reset supersede any queued, not-yet-merged pen
        // points so no background write can resurrect deleted discoveries.
        preferences.edit().putString("points",raw).remove(PENDING_MANUAL).apply()
        cachedRaw=raw;cachedArray=data
    }
    /**
     * Immediate visual commit is already reflected in MainActivity.records.
     * Store only the small stroke in a journal on the UI thread, then merge
     * it into the full historical JSON on a single writer thread. This avoids
     * serializing thousands of old GPS points at the end of each gesture.
     */
    fun addAllDeferred(points:List<DiscoveryPoint>) {
        if(points.isEmpty())return
        synchronized(sharedWriteLock) {
            val pending=JSONArray(preferences.getString(PENDING_MANUAL,"[]"))
            points.forEach{pending.put(json(it))}
            preferences.edit().putString(PENDING_MANUAL,pending.toString()).apply()
        }
        manualWriter.execute{flushPending()}
    }

    /** Merges journal and complete history under the GPS service's lock. */
    fun flushPending() = synchronized(sharedWriteLock) {
        val pending=JSONArray(preferences.getString(PENDING_MANUAL,"[]"))
        if(pending.length()==0)return@synchronized
        val historical=array()
        for(i in 0 until pending.length())historical.put(pending.getJSONObject(i))
        val raw=historical.toString()
        preferences.edit().putString("points",raw)
            .remove(PENDING_MANUAL).commit()
        cachedRaw=raw
        cachedArray=historical
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
