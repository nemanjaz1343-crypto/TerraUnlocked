package com.terraunlocked

import android.content.Context
import org.json.JSONArray

/**
 * One optional, ordered multi-objective plan. Completion belongs to
 * ObjectiveStore: this module never marks a mission as done or bypasses
 * the existing fresh-GPS + camera-proof check.
 */
internal object JourneyPlanner {
    const val MAX_STOPS=40

    fun normalize(ids:List<String>,validIds:Set<String>):List<String> {
        val result=LinkedHashSet<String>()
        for(id in ids) {
            if(id in validIds)result.add(id)
            if(result.size>=MAX_STOPS)break
        }
        return result.toList()
    }

    fun progress(ids:List<String>,completed:Set<String>):Pair<Int,Int> =
        ids.count{it in completed} to ids.size

    fun next(ids:List<String>,completed:Set<String>):String? =
        ids.firstOrNull{it !in completed}
}

internal class JourneyStore(context:Context) {
    private val prefs=context.getSharedPreferences("terra_journey_v1",Context.MODE_PRIVATE)
    var enabled:Boolean
        get()=prefs.getBoolean("enabled",false) && ids().isNotEmpty()
        set(value){prefs.edit().putBoolean("enabled",value).apply()}

    fun ids():List<String> =runCatching {
        val arr=JSONArray(prefs.getString("objective_ids","[]"))
        val seen=LinkedHashSet<String>()
        for(i in 0 until arr.length()) {
            val id=arr.optString(i,"")
            if(id.isNotBlank())seen.add(id)
            if(seen.size>=JourneyPlanner.MAX_STOPS)break
        }
        seen.toList()
    }.getOrDefault(emptyList())

    fun save(ids:List<String>,validIds:Set<String>,start:Boolean=true) {
        val normalized=JourneyPlanner.normalize(ids,validIds)
        val json=JSONArray()
        normalized.forEach{json.put(it)}
        prefs.edit().putString("objective_ids",json.toString())
            .putBoolean("enabled",start && normalized.isNotEmpty()).apply()
    }

    fun clear() {
        prefs.edit().remove("objective_ids").putBoolean("enabled",false).apply()
    }

    fun addOrRemove(id:String,validIds:Set<String>):Boolean {
        val existing=ids().toMutableList()
        val added=if(id in existing) {existing.remove(id);false}
            else {existing.add(id);true}
        save(existing,validIds,existing.isNotEmpty() && enabled)
        return added
    }

    fun progress(completed:(String)->Boolean):Pair<Int,Int> {
        val list=ids()
        return list.count(completed) to list.size
    }

    fun next(completed:(String)->Boolean):String? =
        ids().firstOrNull{!completed(it)}
}
