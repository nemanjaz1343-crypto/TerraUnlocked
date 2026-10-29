package com.terraunlocked

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class FieldNote(val latitude:Double,val longitude:Double,val title:String,val kind:String,val body:String,val time:Long)
class FieldNotes(context: Context) {
    private val prefs=context.getSharedPreferences("terra_notes_v1",Context.MODE_PRIVATE)
    fun load():MutableList<FieldNote> = runCatching {
        val json=JSONArray(prefs.getString("notes","[]"))
        (0 until json.length()).map { i ->
            val j=json.getJSONObject(i)
            FieldNote(j.getDouble("lat"),j.getDouble("lon"),j.getString("title"),
                j.getString("kind"),j.getString("body"),j.getLong("time"))
        }.toMutableList()
    }.getOrDefault(mutableListOf())
    fun save(items:List<FieldNote>) {
        val json=JSONArray()
        items.forEach { n -> json.put(JSONObject().put("lat",n.latitude).put("lon",n.longitude)
            .put("title",n.title).put("kind",n.kind).put("body",n.body).put("time",n.time)) }
        prefs.edit().putString("notes",json.toString()).apply()
    }
}
