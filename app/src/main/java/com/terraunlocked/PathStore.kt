package com.terraunlocked

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ManualSegment(val aLat: Double, val aLon: Double, val bLat: Double, val bLon: Double)

class PathStore(context: Context) {
    private val prefs = context.getSharedPreferences("terra_manual_paths_v1", Context.MODE_PRIVATE)
    fun load(): MutableList<ManualSegment> = runCatching {
        val a = JSONArray(prefs.getString("segments", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            ManualSegment(o.getDouble("aLat"), o.getDouble("aLon"), o.getDouble("bLat"), o.getDouble("bLon"))
        }.toMutableList()
    }.getOrDefault(mutableListOf())
    fun save(segments: List<ManualSegment>) {
        val a = JSONArray()
        segments.forEach { s ->
            a.put(JSONObject().put("aLat",s.aLat).put("aLon",s.aLon)
                .put("bLat",s.bLat).put("bLon",s.bLon))
        }
        prefs.edit().putString("segments", a.toString()).apply()
    }
}
