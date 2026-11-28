package com.terraunlocked

import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/** Five importance levels, from world landmarks (1) to neighbourhood POIs (5). */
internal object GlobalObjectivePolicy {
    fun visibleTier(zoom:Double):Int=when {
        zoom<5.5->1
        zoom<9.0->2
        zoom<12.5->3
        zoom<15.0->4
        else->5
    }

    fun fetchBand(zoom:Double):Int=when {
        zoom<7.5->0 // never query a whole country or the whole world
        zoom<10.5->2
        zoom<13.5->3
        zoom<15.5->4
        else->5
    }

    fun cellSize(zoom:Double):Double=when(fetchBand(zoom)) {
        2->.35
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
            // Many cities have poor wikidata coverage in OSM. Named tourist
            // sights still deserve city-level visibility without a wiki tag.
            tourism in setOf("museum","attraction","gallery","zoo","theme_park") ||
                historic in setOf("castle","monument","archaeological_site","fort")->3
            tourism=="viewpoint" || historic.isNotBlank() ||
                tags["natural"] in setOf("waterfall","peak","cave_entrance") ||
                tags["leisure"] in setOf("nature_reserve","park")->4
            else->5
        }
    }

    /**
     * Group by normalized name before spatial de-duplication. Ordinary
     * objective catalogs have thousands of different names; checking each
     * one against every prior objective used to scale quadratically.
     */
    fun distinct(legacy:List<ReconObjective>,
        incoming:List<ReconObjective>):List<ReconObjective> {
        fun key(value:String)=java.text.Normalizer.normalize(
            value.lowercase(Locale.US),java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"),"")
            .replace(Regex("[^a-z0-9]+"),"").trim()
        val seenIds=legacy.mapTo(HashSet()){it.id}
        val byName=HashMap<String,MutableList<ReconObjective>>()
        legacy.forEach{byName.getOrPut(key(it.name)){ArrayList()}.add(it)}
        val accepted=ArrayList<ReconObjective>()
        for(o in incoming) {
            if(!seenIds.add(o.id))continue
            val sameName=byName.getOrPut(key(o.name)){ArrayList()}
            if(sameName.any{overlapping(it,o)})continue
            sameName.add(o)
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
    private data class Seed(val id:String,val name:String,val category:String,
        val lat:Double,val lon:Double,val city:String)
    // Keep the original iconic worldwide sites at the smallest scales.
    private val worldwideCoreIds=setOf(
        "world_eiffel","world_liberty","world_sydney_opera","world_taj",
        "world_machu","world_giza","world_colosseum","world_petra",
        "world_chichen","world_redeemer","world_angkor",
        "world_golden_gate","world_everest"
    )
    fun visible(zoom:Double):List<ReconObjective> =
        if(zoom<6.0)all.filter{it.id in worldwideCoreIds} else all

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
    ) + listOf(
        // Approximate public landmark centres: offline discovery seeds, not
        // entrances, reviews, or a replacement for nearby live OSM features.
        Seed("world_sagrada","Sagrada Família","ARCH",41.4036,2.1744,"Barcelona"),
        Seed("world_louvre","Louvre Museum","ART",48.8606,2.3376,"Paris"),
        Seed("world_notredame","Notre-Dame Cathedral","HERITAGE",48.8530,2.3499,"Paris"),
        Seed("world_arc_triomphe","Arc de Triomphe","HERITAGE",48.8738,2.2950,"Paris"),
        Seed("world_elizabeth_tower","Elizabeth Tower / Big Ben","HERITAGE",51.5007,-0.1246,"London"),
        Seed("world_tower_bridge","Tower Bridge","ARCH",51.5055,-0.0754,"London"),
        Seed("world_buckingham","Buckingham Palace","HERITAGE",51.5014,-0.1419,"London"),
        Seed("world_acropolis","Acropolis of Athens","HERITAGE",37.9715,23.7257,"Athens"),
        Seed("world_brandenburg","Brandenburg Gate","HERITAGE",52.5163,13.3777,"Berlin"),
        Seed("world_neuschwanstein","Neuschwanstein Castle","HERITAGE",47.5576,10.7498,"Bavaria"),
        Seed("world_saint_peter","St Peter's Basilica","HERITAGE",41.9022,12.4539,"Vatican City"),
        Seed("world_pisa","Leaning Tower of Pisa","HERITAGE",43.7230,10.3966,"Pisa"),
        Seed("world_hagia_sophia","Hagia Sophia","HERITAGE",41.0086,28.9802,"Istanbul"),
        Seed("world_charles_bridge","Charles Bridge","ARCH",50.0865,14.4114,"Prague"),
        Seed("world_alhambra","Alhambra","HERITAGE",37.1761,-3.5881,"Granada"),
        Seed("world_table_mountain","Table Mountain","NATURE",-33.9628,18.4098,"Cape Town"),
        Seed("world_victoria_falls","Victoria Falls","NATURE",-17.9243,25.8572,"Zimbabwe / Zambia"),
        Seed("world_kilimanjaro","Mount Kilimanjaro","NATURE",-3.0674,37.3556,"Tanzania"),
        Seed("world_hassan_mosque","Hassan II Mosque","HERITAGE",33.6084,-7.6326,"Casablanca"),
        Seed("world_abu_simbel","Abu Simbel Temples","HERITAGE",22.3372,31.6258,"Egypt"),
        Seed("world_burj_khalifa","Burj Khalifa","ARCH",25.1921,55.2814,"Dubai"),
        Seed("world_sheikh_zayed_mosque","Sheikh Zayed Grand Mosque","HERITAGE",24.4127,54.4749,"Abu Dhabi"),
        Seed("world_tokyo_tower","Tokyo Tower","ARCH",35.6586,139.7454,"Tokyo"),
        Seed("world_tokyo_skytree","Tokyo Skytree","ARCH",35.7101,139.8107,"Tokyo"),
        Seed("world_fushimi_inari","Fushimi Inari Shrine","HERITAGE",34.9671,135.7727,"Kyoto"),
        Seed("world_mount_fuji","Mount Fuji","NATURE",35.3606,138.7274,"Japan"),
        Seed("world_forbidden_city","Forbidden City","HERITAGE",39.9163,116.3972,"Beijing"),
        Seed("world_great_wall","Great Wall of China","HERITAGE",40.4319,116.5704,"Beijing region"),
        Seed("world_shanghai_tower","Shanghai Tower","ARCH",31.2335,121.5056,"Shanghai"),
        Seed("world_petronas","Petronas Towers","ARCH",3.1579,101.7116,"Kuala Lumpur"),
        Seed("world_mbs","Marina Bay Sands","ARCH",1.2834,103.8607,"Singapore"),
        Seed("world_gardens_bay","Gardens by the Bay","NATURE",1.2816,103.8636,"Singapore"),
        Seed("world_borobudur","Borobudur Temple","HERITAGE",-7.6079,110.2038,"Java"),
        Seed("world_shwedagon","Shwedagon Pagoda","HERITAGE",16.7984,96.1496,"Yangon"),
        Seed("world_wat_arun","Wat Arun","HERITAGE",13.7437,100.4889,"Bangkok"),
        Seed("world_gateway_india","Gateway of India","HERITAGE",18.9219,72.8347,"Mumbai"),
        Seed("world_empire_state","Empire State Building","ARCH",40.7484,-73.9857,"New York"),
        Seed("world_one_world_trade","One World Trade Center","ARCH",40.7127,-74.0134,"New York"),
        Seed("world_central_park","Central Park","NATURE",40.7829,-73.9654,"New York"),
        Seed("world_cn_tower","CN Tower","ARCH",43.6426,-79.3871,"Toronto"),
        Seed("world_niagara","Niagara Falls","NATURE",43.0962,-79.0377,"Canada / USA"),
        Seed("world_space_needle","Space Needle","ARCH",47.6205,-122.3493,"Seattle"),
        Seed("world_griffith","Griffith Observatory","ARCH",34.1184,-118.3004,"Los Angeles"),
        Seed("world_grand_canyon","Grand Canyon South Rim","NATURE",36.1069,-112.1129,"Arizona"),
        Seed("world_teotihuacan","Teotihuacan Pyramids","HERITAGE",19.6925,-98.8438,"Mexico"),
        Seed("world_iguazu","Iguazu Falls","NATURE",-25.6953,-54.4367,"Argentina / Brazil"),
        Seed("world_sugarloaf","Sugarloaf Mountain","NATURE",-22.9486,-43.1566,"Rio de Janeiro"),
        Seed("world_salar_uyuni","Salar de Uyuni","NATURE",-20.1338,-67.4891,"Bolivia"),
        Seed("world_sydney_bridge","Sydney Harbour Bridge","ARCH",-33.8523,151.2108,"Sydney"),
        Seed("world_uluru","Uluru","NATURE",-25.3444,131.0369,"Australia"),
        Seed("world_auckland_sky","Sky Tower Auckland","ARCH",-36.8485,174.7622,"Auckland"),
        Seed("world_milford_sound","Milford Sound","NATURE",-44.6718,167.9256,"New Zealand")
    ).map{seed->
        ReconObjective(seed.id,seed.name,seed.category,seed.lat,seed.lon,
            seed.city+" / Selected world landmark (approximate centre).",
            "https://www.openstreetmap.org/?mlat=${seed.lat}&mlon=${seed.lon}")
    }
}
