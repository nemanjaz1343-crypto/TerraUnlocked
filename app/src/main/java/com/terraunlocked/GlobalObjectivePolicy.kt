package com.terraunlocked

import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/** Five importance levels, from world landmarks (1) to neighbourhood POIs (5). */
internal object GlobalObjectivePolicy {
    fun visibleTier(zoom:Double):Int=when {
        zoom<7.0->1
        zoom<10.0->2
        zoom<12.5->3
        zoom<15.0->4
        else->5
    }

    fun fetchBand(zoom:Double):Int=when {
        zoom<9.0->0 // world landmarks are bundled, never query world-wide Overpass
        zoom<11.5->2
        zoom<13.5->3
        zoom<15.5->4
        else->5
    }

    fun cellSize(zoom:Double):Double=when(fetchBand(zoom)) {
        2->.65
        3->.18
        4->.065
        5->.022
        else->360.0
    }

    fun cellKey(lat:Double,lon:Double,zoom:Double):String {
        val size=cellSize(zoom)
        val y=floor(lat.coerceIn(-89.99,89.99)/size).toInt()
        val x=floor(lon.coerceIn(-179.99,179.99)/size).toInt()
        return "${fetchBand(zoom)}_${y}_${x}"
    }

    /**
     * Return fetch-cell centres that actually intersect the map viewport,
     * centre-first. Repeated requests fetch the next missing cell, rather
     * than overloading a public Overpass server with one giant query.
     * Coordinates are unwrapped relative to the camera to cross ±180°.
     */
    fun viewportCells(south:Double,west:Double,north:Double,east:Double,
        centerLat:Double,centerLon:Double,zoom:Double):List<Pair<Double,Double>> {
        if(fetchBand(zoom)==0 || !south.isFinite() || !north.isFinite() ||
            !west.isFinite() || !east.isFinite())return emptyList()
        val size=cellSize(zoom)
        val cy=floor(centerLat/size).toInt()
        val cx=floor(centerLon/size).toInt()
        fun unwrap(lon:Double):Double {
            var x=lon
            while(x-centerLon>180.0)x-=360.0
            while(x-centerLon< -180.0)x+=360.0
            return x
        }
        val loLat=floor(minOf(south,north).coerceIn(-89.9,89.9)/size).toInt()
        val hiLat=floor(maxOf(south,north).coerceIn(-89.9,89.9)/size).toInt()
        val loLon=floor(minOf(unwrap(west),unwrap(east))/size).toInt()
        val hiLon=floor(maxOf(unwrap(west),unwrap(east))/size).toInt()
        // A single viewport could span many hundreds of cells near world
        // zoom. Keep a finite centre-first queue; subsequent pans refill it.
        val candidates=ArrayList<Triple<Int,Int,Int>>()
        for(y in maxOf(loLat,cy-10)..minOf(hiLat,cy+10)) {
            for(x in maxOf(loLon,cx-10)..minOf(hiLon,cx+10)) {
                candidates.add(Triple(
                    kotlin.math.abs(y-cy)+kotlin.math.abs(x-cx),y,x))
            }
        }
        return candidates.sortedWith(compareBy<Triple<Int,Int,Int>>{it.first}
            .thenBy{it.second}.thenBy{it.third}).take(32).map{(_,y,x)->
            val lon=((x+.5)*size+540.0)%360.0-180.0
            (y+.5)*size to lon
        }
    }

    fun distanceMetres(aLat:Double,aLon:Double,bLat:Double,bLon:Double):Double {
        val y=(bLat-aLat)*111195.0
        var longitudeDelta=bLon-aLon
        if(longitudeDelta>180.0)longitudeDelta-=360.0
        if(longitudeDelta< -180.0)longitudeDelta+=360.0
        val x=longitudeDelta*111195.0*
            cos(Math.toRadians((aLat+bLat)*.5))
        return hypot(x,y)
    }

    fun overlapping(a:ReconObjective,b:ReconObjective):Boolean {
        fun folded(value:String):String=java.text.Normalizer.normalize(
            value.lowercase(Locale.US),java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"),"")
            .replace(Regex("[^a-z0-9]+"),"").trim()
        val aName=folded(a.name)
        val bName=folded(b.name)
        return aName==bName && distanceMetres(
            a.latitude,a.longitude,b.latitude,b.longitude)<400.0
    }

    /** Stable user IDs survive OSM tag edits and disappear/reappear cycles. */
    fun stableId(type:String,id:Long):String="osm_${type}_${id}"

    fun category(tags:Map<String,String>):String=when {
        tags["natural"]!=null || tags["leisure"]=="nature_reserve" ||
            tags["leisure"]=="park"->"NATURE"
        tags["historic"]!=null || tags["heritage"]!=null->"HERITAGE"
        tags["tourism"]=="gallery" || tags["tourism"]=="artwork"->"ART"
        else->"ARCH"
    }

    fun tier(tags:Map<String,String>):Int {
        val hasWiki=!tags["wikidata"].isNullOrBlank()||
            !tags["wikipedia"].isNullOrBlank()
        val tourism=tags["tourism"].orEmpty()
        val historic=tags["historic"].orEmpty()
        return when {
            hasWiki && (tags["heritage"]=="1" ||
                tags["heritage:operator"]=="whc" ||
                historic in setOf("castle","monument","archaeological_site") ||
                tourism=="attraction")->2
            hasWiki->3
            tourism in setOf("museum","attraction","gallery") ||
                historic.isNotBlank() ||
                tags["natural"] in setOf("waterfall","peak","cave_entrance")->4
            else->5
        }
    }

    fun distinct(legacy:List<ReconObjective>,
        incoming:List<ReconObjective>):List<ReconObjective> {
        val seenIds=legacy.mapTo(HashSet()){it.id}
        val accepted=ArrayList<ReconObjective>()
        for(o in incoming) {
            if(!seenIds.add(o.id))continue
            if(legacy.any{overlapping(it,o)} || accepted.any{overlapping(it,o)})continue
            accepted.add(o)
        }
        return accepted
    }
}

/**
 * Read-only global starting layer for world zoom. Coordinates are approximate
 * landmark centres (not entrances). They do NOT replace the original Dubai IDs.
 * Source coordinate references: Duck & Compass landmarks, UNESCO Sydney Opera
 * House nomination, the World Wonders longitude/latitude coordinate sheet.
 */
internal object GlobalWorldLandmarks {
    val all:List<ReconObjective> = listOf(
        ReconObjective("world_eiffel","Eiffel Tower","ARCH",48.8584,2.2945,
            "Paris / Worldwide architectural landmark.","https://www.openstreetmap.org/?mlat=48.8584&mlon=2.2945"),
        ReconObjective("world_liberty","Statue of Liberty","HERITAGE",40.68925,-74.04455,
            "New York Harbor / Worldwide heritage landmark.","https://www.openstreetmap.org/?mlat=40.68925&mlon=-74.04455"),
        ReconObjective("world_sydney_opera","Sydney Opera House","ARCH",-33.8572,151.21512,
            "Sydney / International cultural landmark.","https://www.openstreetmap.org/?mlat=-33.8572&mlon=151.21512"),
        ReconObjective("world_taj","Taj Mahal","HERITAGE",27.17501,78.0421,
            "Agra / Historic architectural landmark.","https://www.openstreetmap.org/?mlat=27.17501&mlon=78.0421"),
        ReconObjective("world_machu","Machu Picchu","HERITAGE",-13.16434,-72.54501,
            "Peru / Archaeological landscape.","https://www.openstreetmap.org/?mlat=-13.16434&mlon=-72.54501"),
        ReconObjective("world_giza","Great Pyramid of Giza","HERITAGE",29.9792,31.1342,
            "Giza / Ancient monument.","https://www.openstreetmap.org/?mlat=29.9792&mlon=31.1342"),
        ReconObjective("world_colosseum","Colosseum","HERITAGE",41.89056,12.49183,
            "Rome / Historic amphitheatre.","https://www.openstreetmap.org/?mlat=41.89056&mlon=12.49183"),
        ReconObjective("world_petra","Petra","HERITAGE",30.32845,35.44436,
            "Jordan / Archaeological city.","https://www.openstreetmap.org/?mlat=30.32845&mlon=35.44436"),
        ReconObjective("world_chichen","Chichén Itzá","HERITAGE",20.68428,-88.56778,
            "Yucatán / Archaeological complex.","https://www.openstreetmap.org/?mlat=20.68428&mlon=-88.56778"),
        ReconObjective("world_redeemer","Christ the Redeemer","HERITAGE",-22.95223,-43.21031,
            "Rio de Janeiro / Monument.","https://www.openstreetmap.org/?mlat=-22.95223&mlon=-43.21031"),
        ReconObjective("world_angkor","Angkor Wat","HERITAGE",13.4125,103.867,
            "Cambodia / Temple complex.","https://www.openstreetmap.org/?mlat=13.4125&mlon=103.867"),
        ReconObjective("world_golden_gate","Golden Gate Bridge","ARCH",37.8199,-122.4783,
            "San Francisco / Bridge landmark.","https://www.openstreetmap.org/?mlat=37.8199&mlon=-122.4783"),
        ReconObjective("world_everest","Mount Everest","NATURE",27.9881,86.9253,
            "Himalayas / Mountain summit.","https://www.openstreetmap.org/?mlat=27.9881&mlon=86.9253")
    )
}
