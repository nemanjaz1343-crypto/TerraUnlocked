package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class MapTouchPolicyTest {
    @Test fun expeditionAndSnapUseMapTapsNotDrawingGesture() {
        for(mode in listOf("JOURNEY_PLAN","SNAP_PICK")) {
            assertTrue(MapTouchPolicy.usesTapToSelect(mode))
            assertFalse(MapTouchPolicy.drawsWithRawTouch(mode))
            assertFalse(MapTouchPolicy.canOpenRadialMenu(mode))
        }
    }

    @Test fun drawingToolsStillGetGestureStream() {
        for(mode in listOf("PATH","PAINT","ERASE","LASSO"))
            assertTrue(MapTouchPolicy.drawsWithRawTouch(mode))
    }

    @Test fun ordinaryMapGetsRadialMenuAndPinMoveDoesNot() {
        assertTrue(MapTouchPolicy.canOpenRadialMenu("VIEW"))
        assertFalse(MapTouchPolicy.canOpenRadialMenu("MOVE"))
        assertFalse(MapTouchPolicy.drawsWithRawTouch("MOVE"))
        assertFalse(MapTouchPolicy.usesTapToSelect("VIEW"))
    }
}
