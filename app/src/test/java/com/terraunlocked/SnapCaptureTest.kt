package com.terraunlocked

import org.junit.Assert.*
import org.junit.Test

class SnapCaptureTest {
    @Test fun snapRequiresRecentAccurateLocation() {
        assertTrue(SnapCapture.canUseMyLocation(50000L,40000L,12f,true))
        assertFalse(SnapCapture.canUseMyLocation(50000L,19000L,12f,true))
        assertFalse(SnapCapture.canUseMyLocation(50000L,40000L,70f,true))
        assertFalse(SnapCapture.canUseMyLocation(50000L,40000L,12f,false))
        assertFalse(SnapCapture.canUseMyLocation(50000L,40000L,Float.NaN,true))
    }
    @Test fun snapAttachmentTypesRemainDistinct() {
        assertTrue(SnapCapture.isPhoto("quick.jpg"))
        assertTrue(SnapCapture.isVideo("clip.mp4"))
        assertTrue(SnapCapture.isVoice("memo.m4a"))
        assertFalse(SnapCapture.isPhoto("clip.mp4"))
        assertFalse(SnapCapture.isPhoto("voice.m4a"))
    }
}
