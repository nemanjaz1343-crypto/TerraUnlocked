package com.terraunlocked

import android.content.Context

data class ReconObjective(val id:String,val name:String,val category:String,val latitude:Double,val longitude:Double,val brief:String,val source:String)

object ReconObjectives {
    // Curated Dubai destinations. Coordinates locate the destination area;
    // users should verify entrances and accessibility before travelling.
    val all=listOf(
        ReconObjective("alserkal","Alserkal Avenue","ART",25.1415,55.2265,"Creative district: galleries, architecture, design and cinema.","https://www.visitdubai.com/en/places-to-visit/alserkal-avenue"),
        ReconObjective("fahidi","Al Fahidi Historical Neighbourhood","HERITAGE",25.2637,55.2972,"Wind towers, historic lanes and the old Dubai Creek neighbourhood.","https://www.visitdubai.com/en/places-to-visit/al-fahidi-historical-neighbourhood"),
        ReconObjective("rasalkhor","Ras Al Khor Wildlife Sanctuary","NATURE",25.1954,55.3555,"Wildlife habitat and birdwatching hides overlooking the wetlands.","https://www.visitdubai.com/en/explore-dubai"),
        ReconObjective("future","Museum of the Future","ARCH",25.2192,55.2814,"Distinctive contemporary architectural landmark on Sheikh Zayed Road.","https://www.visitdubai.com/articles/unique-things-to-do-in-dubai"),
        ReconObjective("frame","Dubai Frame","ARCH",25.2355,55.3004,"Frame-shaped architecture and panoramic views by Zabeel Park.","https://www.visitdubai.com/articles/unique-things-to-do-in-dubai"),
        ReconObjective("jumeirahmosque","Jumeirah Mosque","HERITAGE",25.2335,55.2651,"Cultural landmark with guided visitor experiences.","https://artsandculture.google.com/story/ugWBALMS6kwZvg"),
        ReconObjective("alsafalibrary","Al Safa Art & Design Library","ART",25.1768,55.2295,"Explore a design-focused public library and its architecture.","https://www.dubai.ae/web/dubai.ae/living/sports-culture/culture-arts"),
        ReconObjective("etihad","Etihad Museum","HERITAGE",25.2291,55.2650,"Museum documenting the UAE's foundation and architectural context.","https://www.dubai.ae/web/dubai.ae/living/sports-culture/culture-arts"),
        ReconObjective("alwasl","Al Wasl Plaza","ARCH",24.9617,55.1511,"Large immersive dome and public architecture at Expo City.","https://www.visitdubai.com/articles/unique-things-to-do-in-dubai"),
        ReconObjective("cinemaakil","Cinema Akil","ART",25.1414,55.2264,"Independent cinema in Alserkal's cultural district.","https://artsandculture.google.com/story/ugWBALMS6kwZvg")
    )
}
class ObjectiveStore(context:Context) {
    private val prefs=context.getSharedPreferences("terra_objectives_v1",Context.MODE_PRIVATE)
    var enabled:Boolean
        get()=prefs.getBoolean("enabled",false)
        set(v){prefs.edit().putBoolean("enabled",v).apply()}
    var active:String?
        get()=prefs.getString("active",null)
        set(v){prefs.edit().putString("active",v).apply()}
    fun isHidden(id:String)=prefs.getBoolean("hidden_"+id,false)
    fun hide(id:String,hidden:Boolean=true){prefs.edit().putBoolean("hidden_"+id,hidden).apply()}
    fun completed(id:String)=prefs.getBoolean("done_"+id,false)
    fun complete(id:String){prefs.edit().putBoolean("done_"+id,true).apply()}
    fun customPlaces():List<ReconObjective> = runCatching {
        val arr=org.json.JSONArray(prefs.getString("custom_places","[]"))
        (0 until arr.length()).map {i->
            val j=arr.getJSONObject(i)
            ReconObjective(j.getString("id"),j.getString("name"),"SAVED",j.getDouble("lat"),j.getDouble("lon"),j.optString("brief","Want to visit"),"")
        }
    }.getOrDefault(emptyList())
    fun addPlace(name:String,lat:Double,lon:Double,address:String):ReconObjective {
        val item=ReconObjective("saved_"+System.currentTimeMillis(),name,"SAVED",lat,lon,address,"")
        val arr=org.json.JSONArray(prefs.getString("custom_places","[]"))
        arr.put(org.json.JSONObject().put("id",item.id).put("name",name).put("lat",lat).put("lon",lon).put("brief",address))
        prefs.edit().putString("custom_places",arr.toString()).apply()
        return item
    }
    fun resetHiding(){ReconObjectives.all.forEach{hide(it.id,false)}}
}
