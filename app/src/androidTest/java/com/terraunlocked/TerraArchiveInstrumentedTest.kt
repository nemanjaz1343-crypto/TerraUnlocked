package com.terraunlocked

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Runs in a disposable emulator application, never against a user's device
 * data. Covers actual SharedPreferences/files rather than JVM Android stubs.
 */
@RunWith(AndroidJUnit4::class)
class TerraArchiveInstrumentedTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private var baseline:ByteArray=byteArrayOf()

    @Before fun saveTestProfile() {
        TrackingService.running=false
        TerraArchive(context).recoverInterruptedRestore()
        val bytes=ByteArrayOutputStream()
        TerraArchive(context).exportTo(bytes)
        baseline=bytes.toByteArray()
    }

    @After fun restoreTestProfile() {
        TrackingService.running=false
        TerraArchive(context).importFrom(ByteArrayInputStream(baseline))
    }

    private fun seedOne():DiscoveryPoint {
        val p=DiscoveryPoint(25.07665,55.14021,false,1700000000123L,
            8f,1.2f,94)
        DiscoveryStore(context).replaceAll(listOf(p))
        return p
    }

    private fun backup():ByteArray {
        val bytes=ByteArrayOutputStream()
        TerraArchive(context).exportTo(bytes)
        return bytes.toByteArray()
    }

    @Test fun fullRoundTripRestoresVerifiedDiscovery() {
        val expected=seedOne()
        val exported=backup()
        DiscoveryStore(context).replaceAll(emptyList())
        assertTrue(DiscoveryStore(context).load().isEmpty())
        TerraArchive(context).importFrom(ByteArrayInputStream(exported))
        val restored=DiscoveryStore(context).load()
        assertEquals(1,restored.size)
        assertEquals(expected.latitude,restored.single().latitude,0.0000001)
        assertEquals(expected.longitude,restored.single().longitude,0.0000001)
        assertFalse(restored.single().manual)
        assertEquals(expected.timestamp,restored.single().timestamp)
        assertFalse(File(context.filesDir,"terra_restore_recovery.zip").exists())
    }

    @Test fun persistedRecoveryMarkerRestoresPreImportHistory() {
        val expected=seedOne()
        // Simulate a kill after the durable rollback file + flag were
        // written, but before the imported profile was fully committed.
        val recovery=File(context.filesDir,"terra_restore_recovery.zip")
        recovery.outputStream().use{TerraArchive(context).exportTo(it)}
        val prefs=context.getSharedPreferences(
            "terra_restore_transaction_v1",android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putBoolean("in_progress",true).commit())
        DiscoveryStore(context).replaceAll(emptyList())

        TerraArchive(context).recoverInterruptedRestore()

        val restored=DiscoveryStore(context).load()
        assertEquals(1,restored.size)
        assertEquals(expected.timestamp,restored.single().timestamp)
        assertFalse(prefs.getBoolean("in_progress",false))
        assertFalse(recovery.exists())
    }

    private fun archiveWithOversizedUnknownEntry():ByteArray {
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use{zip->
            for(name in listOf("notes.json","discoveries.json","paths.json")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("[]".toByteArray())
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("unexpected-large-binary"))
            val block=ByteArray(8192)
            repeat(5200){zip.write(block)}
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test fun unknownZipEntryIsBoundedAndNeverReplacesCurrentHistory() {
        val expected=seedOne()
        val oversized=archiveWithOversizedUnknownEntry()
        val error=runCatching{
            TerraArchive(context).importFrom(ByteArrayInputStream(oversized))
        }.exceptionOrNull()
        assertNotNull("Unknown ZIP payload must be capped",error)
        assertEquals(expected.timestamp,
            DiscoveryStore(context).load().single().timestamp)
        assertFalse(context.getSharedPreferences(
            "terra_restore_transaction_v1",android.content.Context.MODE_PRIVATE)
            .getBoolean("in_progress",false))
    }

    @Test fun importDoesNotStartWhileGpsServiceIsRunning() {
        seedOne()
        val saved=backup()
        TrackingService.running=true
        try {
            assertNotNull(runCatching{
                TerraArchive(context).importFrom(ByteArrayInputStream(saved))
            }.exceptionOrNull())
        }finally{TrackingService.running=false}
        assertEquals(1,DiscoveryStore(context).load().size)
    }
}
