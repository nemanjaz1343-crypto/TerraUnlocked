package com.terraunlocked

/**
 * Map gestures belong to exactly one tool. Only drawing tools consume raw
 * MotionEvent UP/DOWN; selection tools need osmdroid's tap recognizer.
 */
internal object MapTouchPolicy {
    fun drawsWithRawTouch(mode:String):Boolean =
        mode=="PATH" || mode=="PAINT" || mode=="ERASE" || mode=="LASSO"

    fun usesTapToSelect(mode:String):Boolean =
        mode=="JOURNEY_PLAN" || mode=="SNAP_PICK"

    fun canOpenRadialMenu(mode:String):Boolean = mode=="VIEW"
}
