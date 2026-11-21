package com.terraunlocked

import android.content.Context
import android.graphics.Color

/**
 * Small, coherent UI palettes: retain the original military look as default.
 * Explorer is deep pine + warm brass/parchment; Civil is urban navy + cyan
 * balanced by copper. Values are in-app designs informed by palette references,
 * not color assets downloaded from third-party sites.
 */
internal object ExpeditionThemes {
    data class Palette(
        val id:String,val label:String,
        val dark:Int,val gold:Int,val light:Int,val moss:Int,val muted:Int,
        val surface:Int,val raised:Int,val secondary:Int,val screen:Int,
        val accent:Int
    )
    private fun rgb(hex:Long):Int=Color.rgb(
        ((hex shr 16) and 255).toInt(),
        ((hex shr 8) and 255).toInt(),
        (hex and 255).toInt()
    )
    val military=Palette("MILITARY","Military",
        rgb(0x0E1410),rgb(0xD5AF6A),rgb(0xF0DAA6),
        rgb(0x2B2F20),rgb(0x69705B),rgb(0x1D241B),
        rgb(0x453F28),rgb(0x9BB586),rgb(0x1F2820),rgb(0xD5AF6A))
    val explorer=Palette("EXPLORER","Explorer",
        rgb(0x162720),rgb(0xD4A66B),rgb(0xF3E3C2),
        rgb(0x334337),rgb(0x849A87),rgb(0x293B31),
        rgb(0x4B4632),rgb(0xADC7AA),rgb(0x1B3027),rgb(0xD4A66B))
    val civil=Palette("CIVIL","Civil",
        rgb(0x142735),rgb(0x82BDD5),rgb(0xEFF4F7),
        rgb(0x2B4352),rgb(0x94AAB7),rgb(0x203745),
        rgb(0x365366),rgb(0xB8CFD8),rgb(0x1A3141),rgb(0xE6A066))
    private val all=listOf(military,explorer,civil)
    var current:Palette=military
        private set
    fun byId(id:String?):Palette=all.firstOrNull{it.id==id}?:military
    fun select(context:Context,id:String) {
        val palette=byId(id)
        current=palette
        context.getSharedPreferences("terra_ui_theme",Context.MODE_PRIVATE)
            .edit().putString("selected",palette.id).apply()
    }
    fun load(context:Context) {
        current=byId(context.getSharedPreferences("terra_ui_theme",Context.MODE_PRIVATE)
            .getString("selected","MILITARY"))
    }
    fun all():List<Palette> =all
}
