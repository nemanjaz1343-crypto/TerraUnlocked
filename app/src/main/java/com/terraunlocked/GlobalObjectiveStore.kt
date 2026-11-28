package com.terraunlocked

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.floor

internal data class GlobalObjectiveItem(val objective:ReconObjective,val tier:Int)
internal data class GlobalObjectiveCell(
    val key:String,val updated:Long,val items:List<GlobalObjectiveItem>
)

/**
 * Small, zoom-keyed regional OSM cache. Each request covers one nearby cell,
 * NEVER the whole earth. User-created and original Dubai objectives remain in
 * ObjectiveStore, completely independent from these temporary OSM entries.
 *
 * Public Overpass calls must run on a worker, never from MapView.draw().
 */
internal class GlobalObjectiveStore(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences(
        "terra_global_objectives_v1",Context.MODE_PRIVATE)
    private val cells=LinkedHashMap<String,GlobalObjectiveCell>()
    private val pinned=LinkedHashMap<String,GlobalObjectiveItem>()

    init {
        runCatching {
            val data=JSONArray(prefs.getString("cells","[]"))
            for(i in 0 until data.length().coerceAtMost(36)) {
                val j=data.optJSONObject(i)?:continue
                val key=j.optString("key")
                val items=decode(j.optJSONArray("items"))
                if(key.matches(Regex("^[2-5]_-?[0-9]+_-?[0-9]+$")))
                    cells[key]=GlobalObjectiveCell(key,j.optLong("at"),items)
            }
            val pins=JSONArray(prefs.getString("pinned","[]"))
            decode(pins).take(160).forEach{pinned[it.objective.id]=it}
        }
    }

    private fun decode(array:JSONArray?):List<GlobalObjectiveItem> {
        if(array==null)return emptyList()
        val out=ArrayList<GlobalObjectiveItem>()
        for(i in 0 until array.length().coerceAtMost(260)) {
            val j=array.optJSONObject(i)?:continue
            val id=j.optString("id")
            if(!id.startsWith("osm_") && !id.startsWith("world_"))continue
            val name=j.optString("name").take(100)
            val lat=j.optDouble("lat",Double.NaN)
            val lon=j.optDouble("lon",Double.NaN)
            if(name.isBlank() || !lat.isFinite() || !lon.isFinite() ||
                lat !in -90.0..90.0 || lon !in -180.0..180.0)continue
            val tier=j.optInt("tier",5).coerceIn(1,5)
            val category=j.optString("category","ARCH")
            if(category !in listOf("ARCH","HERITAGE","NATURE","ART"))continue
            val source=if(id.startsWith("osm_")) {
                val parts=id.removePrefix("osm_").split("_")
                if(parts.size!=2||parts[0] !in listOf("node","way","relation") ||
                    parts[1].toLongOrNull()==null)continue
                "https://www.openstreetmap.org/${parts[0]}/${parts[1]}"
            }else ""
            out.add(GlobalObjectiveItem(ReconObjective(id,name,category,
                lat,lon,j.optString("brief","Mapped landmark").take(180),
                source),tier))
        }
        return out
    }

    private fun encode(list:List<GlobalObjectiveItem>):JSONArray=JSONArray().also{arr->
        list.forEach{item->
            val o=item.objective
            arr.put(JSONObject().put("id",o.id).put("name",o.name)
                .put("lat",o.latitude).put("lon",o.longitude)
                .put("category",o.category).put("brief",o.brief)
                .put("tier",item.tier))
        }
    }

    private fun persist(durable:Boolean=false) {
        val arr=JSONArray()
        cells.values.forEach{cell->
            arr.put(JSONObject().put("key",cell.key).put("at",cell.updated)
                .put("items",encode(cell.items)))
        }
        val edit=prefs.edit().putString("cells",arr.toString())
            .putString("pinned",encode(pinned.values.toList()).toString())
        if(durable)check(edit.commit()) {"Global objective pins could not be saved"}
        else edit.apply()
    }

    @Synchronized fun exportPinned():String=encode(pinned.values.toList()).toString()
    @Synchronized fun savedObjectives():List<ReconObjective> =
        pinned.values.map{it.objective}
    @Synchronized fun savedForZoom(zoom:Double):List<ReconObjective> =
        pinned.values.filter{it.tier<=GlobalObjectivePolicy.visibleTier(zoom)}
            .map{it.objective}
    @Synchronized fun importPinned(raw:String) {
        val valid=decode(JSONArray(raw))
        valid.forEach{pinned[it.objective.id]=it}
        trim()
        persist(durable=true)
    }

    @Synchronized fun remember(objective:ReconObjective) {
        if(!objective.id.startsWith("osm_"))return
        val matched=cells.values.asSequence().flatMap{it.items.asSequence()}
            .firstOrNull{it.objective.id==objective.id}
        pinned[objective.id]=matched?:GlobalObjectiveItem(objective,3)
        trim()
        persist()
    }

    private fun trim() {
        while(cells.size>32)cells.remove(cells.keys.first())
        while(pinned.size>160)pinned.remove(pinned.keys.first())
    }

    /** Cached + pinned map facts only; no network, safe for the UI thread. */
    @Synchronized fun nearby(lat:Double,lon:Double,zoom:Double):List<ReconObjective> {
        val tier=GlobalObjectivePolicy.visibleTier(zoom)
        val range=when {
            zoom<7.0->21000000.0
            zoom<9.5->750000.0
            zoom<11.5->125000.0
            zoom<13.5->26000.0
            zoom<15.5->8000.0
            else->2900.0
        }
        val seed=GlobalWorldLandmarks.all.filter{
            GlobalObjectivePolicy.distanceMetres(lat,lon,
                it.latitude,it.longitude)<range
        }
        val collected=ArrayList<GlobalObjectiveItem>()
        for(cell in cells.values)for(item in cell.items) {
            if(item.tier>tier)continue
            val o=item.objective
            if(GlobalObjectivePolicy.distanceMetres(lat,lon,
                    o.latitude,o.longitude)<range)
                collected.add(item)
        }
        for(item in pinned.values)if(item.tier<=tier) {
            val o=item.objective
            if(GlobalObjectivePolicy.distanceMetres(lat,lon,
                    o.latitude,o.longitude)<range)collected.add(item)
        }
        val distinct=GlobalObjectivePolicy.distinct(seed,
            collected.sortedWith(compareBy<GlobalObjectiveItem>{it.tier}
                .thenBy{it.objective.name}).map{it.objective})
        return (seed+distinct).take(300)
    }

    @Synchronized fun needsFetch(lat:Double,lon:Double,zoom:Double,now:Long):Boolean {
        if(GlobalObjectivePolicy.fetchBand(zoom)==0)return false
        val key=GlobalObjectivePolicy.cellKey(lat,lon,zoom)
        val previous=cells[key]?:return true
        return now-previous.updated>6L*60L*60L*1000L
    }

    /**
     * Call ONLY from a dedicated background executor. One cell and at most
     * 240 named objects per request, with strict timeouts and fallback host.
     */
    fun fetchCell(lat:Double,lon:Double,zoom:Double):List<GlobalObjectiveItem> {
        val band=GlobalObjectivePolicy.fetchBand(zoom)
        if(band==0)return emptyList()
        val size=GlobalObjectivePolicy.cellSize(zoom)
        val south=(floor(lat/size)*size-.004).coerceAtLeast(-89.9)
        val west=(floor(lon/size)*size-.004).coerceAtLeast(-179.9)
        val north=(south+size+.008).coerceAtMost(89.9)
        val east=(west+size+.008).coerceAtMost(179.9)
        val box="${south},${west},${north},${east}"
        val query=when(band) {
            2->"""[out:json][timeout:22];(
                nwr["tourism"~"^(attraction|museum|gallery|zoo|theme_park)$"]["name"]($box);
                nwr["historic"~"^(castle|monument|archaeological_site|fort)$"]["name"]($box);
                nwr["natural"~"^(peak|waterfall)$"]["name"]($box);
            );out center 240;""".trimIndent()
            3->"""[out:json][timeout:22];(
                nwr["tourism"~"^(attraction|museum|gallery|viewpoint)$"]["name"]($box);
                nwr["historic"]["name"]($box);
                nwr["natural"~"^(peak|waterfall|cave_entrance)$"]["name"]($box);
                nwr["leisure"~"^(nature_reserve|park)$"]["name"]($box);
            );out center 240;""".trimIndent()
            else->"""[out:json][timeout:22];(
                nwr["tourism"~"^(attraction|museum|gallery|viewpoint|artwork)$"]["name"]($box);
                nwr["historic"]["name"]($box);
                nwr["natural"~"^(peak|waterfall|cave_entrance|beach)$"]["name"]($box);
                nwr["leisure"~"^(nature_reserve|park)$"]["name"]($box);
            );out center 240;""".trimIndent()
        }
        val payload="data="+URLEncoder.encode(query,"UTF-8")
        var body:String?=null
        var last:Throwable?=null
        for(host in listOf("https://overpass.private.coffee/api/interpreter",
            "https://overpass-api.de/api/interpreter")) {
            try {
                val conn=URL(host).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod="POST"
                    conn.doOutput=true
                    conn.connectTimeout=8000
                    conn.readTimeout=26000
                    conn.setRequestProperty("Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8")
                    conn.setRequestProperty("User-Agent","TerraUnlocked Global Objectives")
                    conn.outputStream.use{it.write(payload.toByteArray(Charsets.UTF_8))}
                    if(conn.responseCode!=200)
                        throw java.io.IOException("Overpass ${conn.responseCode}")
                    body=conn.inputStream.bufferedReader(Charsets.UTF_8).use{
                        it.readText()
                    }
                } finally {conn.disconnect()}
                break
            } catch(error:Exception){last=error}
        }
        val response=body?:throw java.io.IOException("OSM objectives unavailable",last)
        val elements=JSONObject(response).optJSONArray("elements")?:JSONArray()
        val found=ArrayList<GlobalObjectiveItem>()
        for(i in 0 until elements.length().coerceAtMost(300)) {
            val item=elements.optJSONObject(i)?:continue
            val tags=item.optJSONObject("tags")?:continue
            val type=item.optString("type")
            val osmId=item.optLong("id",0L)
            if(type !in listOf("node","way","relation") || osmId<=0L)continue
            val name=tags.optString("name:en")
                .ifBlank{tags.optString("name")}.trim().take(80)
            if(name.isBlank())continue
            val center=item.optJSONObject("center")
            val y=if(item.has("lat"))item.optDouble("lat",Double.NaN)
                else center?.optDouble("lat",Double.NaN)?:Double.NaN
            val x=if(item.has("lon"))item.optDouble("lon",Double.NaN)
                else center?.optDouble("lon",Double.NaN)?:Double.NaN
            if(!x.isFinite() || !y.isFinite() ||
                x !in -180.0..180.0 || y !in -90.0..90.0)continue
            val labels=mutableMapOf<String,String>()
            for(key in listOf("historic","heritage","heritage:operator","tourism",
                "natural","leisure","wikidata","wikipedia")) {
                val value=tags.optString(key)
                if(value.isNotBlank())labels[key]=value
            }
            val tier=GlobalObjectivePolicy.tier(labels)
            val category=GlobalObjectivePolicy.category(labels)
            val id=GlobalObjectivePolicy.stableId(type,osmId)
            val brief="OpenStreetMap • "+(tags.optString("historic")
                .ifBlank{tags.optString("tourism")}
                .ifBlank{tags.optString("natural")}
                .ifBlank{tags.optString("leisure")}
                .ifBlank{"Mapped destination"}).take(65)
            found.add(GlobalObjectiveItem(ReconObjective(id,name,category,y,x,brief,
                "https://www.openstreetmap.org/${type}/${osmId}"),tier))
        }
        return found.distinctBy{it.objective.id}
    }

    /** Commit a region independently so old cached regions survive failures. */
    @Synchronized fun put(lat:Double,lon:Double,zoom:Double,items:List<GlobalObjectiveItem>) {
        val key=GlobalObjectivePolicy.cellKey(lat,lon,zoom)
        cells.remove(key) // keep most recently used cells at end
        cells[key]=GlobalObjectiveCell(key,System.currentTimeMillis(),items.take(240))
        trim()
        persist()
    }
}
