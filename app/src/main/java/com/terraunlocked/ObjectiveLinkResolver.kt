package com.terraunlocked

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Source links are deterministic and never depend on a website remaining live.
 * Official website metadata is refreshed lazily when the information bubble
 * opens, NOT across every map marker at startup. Results are cached for 24 h
 * and failures retain the previous known website (or the stable OSM fallback).
 */
internal object ObjectiveLinkPolicy {
    fun osmElementId(id:String):Pair<String,Long>? {
        val m=Regex("^osm_(node|way|relation)_([0-9]+)$").matchEntire(id)
            ?: return null
        val value=m.groupValues[2].toLongOrNull()?.takeIf{it>0L} ?: return null
        return m.groupValues[1] to value
    }
    fun stableSource(o:ReconObjective):String {
        val osm=osmElementId(o.id)
        if(osm!=null)return "https://www.openstreetmap.org/${osm.first}/${osm.second}"
        // OSM pin is stable even if the curated venue's old domain/URL changes.
        return "https://www.openstreetmap.org/?mlat=${o.latitude}&mlon=${o.longitude}"
    }
    fun safeWebsite(raw:String?):String? {
        if(raw.isNullOrBlank() || raw.length>2048)return null
        val value=raw.trim().substringBefore(';').trim()
        return runCatching {
            val url=URL(value)
            if(url.protocol.lowercase()!="https" || url.host.isBlank() ||
                url.userInfo!=null || url.host=="localhost" ||
                url.host.startsWith("127.") || url.host.endsWith(".local"))
                return@runCatching null
            url.toExternalForm()
        }.getOrNull()
    }
    fun extractWebsite(tags:JSONObject):String? {
        for(key in listOf("website","contact:website","official_website","url")) {
            safeWebsite(tags.optString(key,null))?.let{return it}
        }
        return null
    }
}

internal class ObjectiveLinkResolver(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences(
        "terra_objective_link_cache_v1",Context.MODE_PRIVATE)
    data class Link(val source:String,val website:String?,val updatedAt:Long)
    fun cached(o:ReconObjective):Link {
        val preferred=ObjectiveLinkPolicy.safeWebsite(o.source)
            ?.takeUnless{o.id.startsWith("osm_") || o.id.startsWith("world_")}
        val site=prefs.getString("site_"+
            o.id,null)?.let{ObjectiveLinkPolicy.safeWebsite(it)} ?: preferred
        return Link(ObjectiveLinkPolicy.stableSource(o),site,
            prefs.getLong("at_"+o.id,0L))
    }
    fun needsRefresh(o:ReconObjective,now:Long=System.currentTimeMillis()):Boolean =
        ObjectiveLinkPolicy.osmElementId(o.id)!=null &&
            now-cached(o).updatedAt>=24L*60L*60L*1000L

    /**
     * Must be called OFF the UI thread for a single open OSM bubble only.
     * Fetches OSM's own canonical object tags, never tests/visits arbitrary
     * third-party website URLs and never blocks map start or map rendering.
     */
    fun refresh(o:ReconObjective):Link {
        val osm=ObjectiveLinkPolicy.osmElementId(o.id)?:return cached(o)
        val url=URL("https://api.openstreetmap.org/api/0.6/${osm.first}/${osm.second}.json")
        val conn=(url.openConnection() as HttpURLConnection).apply {
            connectTimeout=4000
            readTimeout=5500
            setRequestProperty("User-Agent","TerraUnlocked/0.4 (objective source links)")
        }
        val site=try {
            if(conn.responseCode!=200)
                throw java.io.IOException("OSM metadata status: ${conn.responseCode}")
            val root=JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use{
                it.readText()
            })
            val element=root.optJSONArray("elements")?.optJSONObject(0)
            ObjectiveLinkPolicy.extractWebsite(element?.optJSONObject("tags")?:JSONObject())
        } finally {conn.disconnect()}
        prefs.edit().putLong("at_"+o.id,System.currentTimeMillis())
            .apply {
                if(site!=null)putString("site_"+o.id,site)
                else remove("site_"+o.id)
            }.apply()
        return cached(o)
    }
}
