package com.terraunlocked

/**
 * Names are presentation only. Persisted mission IDs, categories, saved notes,
 * proof requirements and backup formats stay unchanged when changing styles.
 */
internal object ExpeditionWording {
    data class Terms(
        val heading:String,
        val single:String,
        val plural:String,
        val journey:String,
        val journal:String,
        val savedPlaces:String,
        val statistics:String,
        val verb:String
    )
    val military=Terms(
        "MISSIONS","MISSION","MISSIONS","OPERATION",
        "FIELD JOURNAL","BASES","INTELLIGENCE","ACTIVATE")
    val explorer=Terms(
        "EXPEDITIONS","DISCOVERY","DISCOVERIES","EXPEDITION",
        "EXPLORER'S LOG","WAYPOINTS","EXPLORATION STATS","FOLLOW")
    val civil=Terms(
        "TASKS","TASK","TASKS","ITINERARY",
        "NOTES","SAVED PLACES","ACTIVITY","START")
    fun forId(id:String?):Terms=when(id) {
        "EXPLORER"->explorer
        "CIVIL"->civil
        else->military
    }
    val current:Terms get()=forId(ExpeditionThemes.current.id)
}
