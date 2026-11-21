package com.terraunlocked

/** Captured media stays local until the user chooses a point on the map. */
internal data class SnapDraft(
    val filename:String,
    val kind:String, // PHOTO, VIDEO, VOICE
    val title:String
)

internal object SnapCapture {
    fun canUseMyLocation(
        nowMs:Long,lastFixMs:Long,accuracyMeters:Float,hasPosition:Boolean
    ):Boolean = hasPosition && lastFixMs>0L &&
        nowMs-lastFixMs in 0L..30000L &&
        accuracyMeters.isFinite() && accuracyMeters in 0f..60f

    fun isPhoto(filename:String):Boolean =
        setOf("jpg","jpeg","png","webp").contains(
            filename.substringAfterLast('.',missingDelimiterValue="").lowercase())
    fun isVideo(filename:String):Boolean =
        setOf("mp4","mov","3gp","mkv").contains(
            filename.substringAfterLast('.',missingDelimiterValue="").lowercase())
    fun isVoice(filename:String):Boolean =
        filename.substringAfterLast('.',missingDelimiterValue="").lowercase()=="m4a"
}
