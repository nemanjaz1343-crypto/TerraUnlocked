package com.terraunlocked

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class DiscoveryPoint(val latitude: Double, val longitude: Double, val manual: Boolean, val timestamp: Long)

class DiscoveryStore(context: Context) {
    private val preferences = context.getSharedPreferences("terra_discoveries_v1", Context.MODE_PRIVATE)
    fun load(): List<DiscoveryPoint> {
        val data = JSONArray(preferences.getString("points", "[]") ?: "[]")
        return (0 until data.length()).mapNotNull { index ->
            runCatching {
                val p = data.getJSONObject(index)
                DiscoveryPoint(p.getDouble("lat"), p.getDouble("lon"), p.getBoolean("manual"), p.getLong("time"))
            }.getOrNull()
        }
    }
    fun add(point: DiscoveryPoint) {
        val array = JSONArray(preferences.getString("points", "[]") ?: "[]")
        array.put(JSONObject().put("lat", point.latitude).put("lon", point.longitude)
            .put("manual", point.manual).put("time", point.timestamp))
        preferences.edit().putString("points", array.toString()).apply()
    }
}
