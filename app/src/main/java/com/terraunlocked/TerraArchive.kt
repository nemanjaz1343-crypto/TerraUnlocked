package com.terraunlocked

import android.content.Context
import java.io.InputStream
import java.io.OutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

class TerraArchive(private val context:Context) {
    companion object { private val restoreLock=Any() }
    private val keys=listOf(
        Triple("notes.json","terra_notes_v1","notes"),
        Triple("discoveries.json","terra_discoveries_v1","points"),
        Triple("paths.json","terra_manual_paths_v1","segments"),
        Triple("places.json","terra_objectives_v1","custom_places")
    )
    fun exportTo(output:OutputStream) {
        // A just-finished brush stroke may still be in the small durable
        // journal. Merge it before reading discoveries.json for backup.
        DiscoveryStore(context).flushPending()
        ZipOutputStream(output).use {zip->
            for((filename,pref,key) in keys) {
                val value=context.getSharedPreferences(pref,Context.MODE_PRIVATE).getString(key,"[]") ?: "[]"
                zip.putNextEntry(ZipEntry(filename))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            // Objective completion/visibility must also survive a backup so
            // a restored Journey retains its actual verified progress.
            val objectives=ObjectiveStore(context)
            val objectivePrefs=context.getSharedPreferences(
                "terra_objectives_v1",Context.MODE_PRIVATE)
            val global=GlobalObjectiveStore(context)
            val allGoals=ReconObjectives.all+objectives.customPlaces()+
                GlobalWorldLandmarks.all+global.savedObjectives()
            val done=org.json.JSONArray()
            val hidden=org.json.JSONArray()
            allGoals.forEach { goal ->
                if(objectives.completed(goal.id))done.put(goal.id)
                if(objectives.isHidden(goal.id))hidden.put(goal.id)
            }
            val objectiveState=JSONObject()
                .put("completed",done)
                .put("hidden",hidden)
                .put("active",objectivePrefs.getString("active","") ?: "")
            zip.putNextEntry(ZipEntry("objective_state.json"))
            zip.write(objectiveState.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            // Stable OSM objective metadata is optional. Legacy backups need
            // no migration; never put dynamic map caches into a user ZIP.
            zip.putNextEntry(ZipEntry("global_objectives.json"))
            zip.write(global.exportPinned().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            // Optional Journey remains independent of one active objective.
            val journey=JourneyStore(context)
            val journeyJson=JSONObject()
                .put("ids",org.json.JSONArray(journey.ids()))
                .put("enabled",journey.enabled)
            zip.putNextEntry(ZipEntry("journey.json"))
            zip.write(journeyJson.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            // GPS evidence is kept separate from discoveries and drawn paths.
            // Export only on explicit user request; older archives remain valid.
            for(name in listOf("terra_gps_raw.csv","terra_gps_trace.csv",
                    "terra_gps_diagnostics.csv")) {
                val file=File(context.filesDir,name)
                if(!file.isFile)continue
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use {it.copyTo(zip)}
                zip.closeEntry()
            }
            // Persist user anchors and non-list preferences as a separate backwards-compatible entry.
            val anchorPrefs=context.getSharedPreferences("terra_anchors_v1",Context.MODE_PRIVATE)
            val anchors=JSONObject()
            for((key,value) in anchorPrefs.all) if(value is String)anchors.put(key,value)
            zip.putNextEntry(ZipEntry("anchors.json"))
            zip.write(anchors.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            val names=FieldNotes(context).load().flatMap {
                it.attachments + (if(it.kind=="PHOTO"||it.kind=="VOICE") listOf(it.body) else emptyList())
            }.distinct()
            for(name in names) {
                if(name.contains("/") || name.contains("\\") || name.startsWith("."))continue
                val file=File(context.filesDir,name)
                if(!file.isFile)continue
                zip.putNextEntry(ZipEntry("media/"+name))
                file.inputStream().use {it.copyTo(zip)}
                zip.closeEntry()
            }
        }
    }
    internal data class BackupPreview(
        val discoveries:Int,val notes:Int,val paths:Int,
        val places:Int,val media:Int
    ) {
        fun describe():String=
            "$discoveries discovered points, $paths manual paths, "+
            "$notes journal notes, $places saved locations and "+
            "$media media files."
    }

    /** Non-mutating preview; used before asking permission to replace data. */
    internal fun preview(input:InputStream):BackupPreview {
        val counts=HashMap<String,Int>()
        val seen=HashSet<String>()
        var media=0
        var entries=0
        var total=0L
        ZipInputStream(input).use{zip->
            while(true) {
                val entry=zip.nextEntry?:break
                val name=entry.name
                require(++entries<=600 && seen.add(name)) {
                    "Too many or duplicate backup entries"
                }
                val json=name in keys.map{it.first}
                val output=if(json)java.io.ByteArrayOutputStream() else null
                val buffer=ByteArray(8192)
                var size=0L
                while(true) {
                    val n=zip.read(buffer)
                    if(n<0)break
                    size+=n;total+=n
                    require(total<=256L*1024L*1024L) {
                        "Backup expands beyond 256 MB"
                    }
                    if(json) {
                        require(size<=15_000_000L) {"Backup JSON too large"}
                        output!!.write(buffer,0,n)
                    } else require(size<=40_000_000L) {"Oversized entry"}
                }
                if(json)counts[name]=org.json.JSONArray(
                    output!!.toString("UTF-8")).length()
                if(name.startsWith("media/"))media++
                zip.closeEntry()
            }
        }
        require(counts.keys.containsAll(keys.take(3).map{it.first})) {
            "Not a complete TERRA backup"
        }
        return BackupPreview(counts["discoveries.json"]?:0,
            counts["notes.json"]?:0,counts["paths.json"]?:0,
            counts["places.json"]?:0,media)
    }

    private val recoveryFile:File get()=File(context.filesDir,"terra_restore_recovery.zip")
    private val recoveryPrefs get()=context.getSharedPreferences(
        "terra_restore_transaction_v1",Context.MODE_PRIVATE)

    /**
     * A complete pre-restore backup is created *before* mutating any files.
     * The durable marker allows a rollback on a later app launch if Android
     * kills this process in the middle of the multi-preference restore.
     */
    fun recoverInterruptedRestore() = synchronized(restoreLock) {
        if(!recoveryPrefs.getBoolean("in_progress",false))return@synchronized
        check(recoveryFile.isFile) {"Restore recovery file is missing"}
        recoveryFile.inputStream().use{applyImport(it)}
        check(recoveryPrefs.edit().putBoolean("in_progress",false).commit())
        recoveryFile.delete()
    }

    fun importFrom(input:InputStream) = synchronized(restoreLock) {
        recoverInterruptedRestore()
        val temp=File(context.filesDir,"terra_restore_recovery.tmp")
        try {
            temp.outputStream().use{exportTo(it)}
            check(temp.isFile && temp.length()>0L) {"Could not stage rollback"}
            if(recoveryFile.exists())recoveryFile.delete()
            check(temp.renameTo(recoveryFile)) {"Cannot install rollback"}
            check(recoveryPrefs.edit().putBoolean("in_progress",true).commit())
            try {
                applyImport(input)
                check(recoveryPrefs.edit().putBoolean("in_progress",false).commit())
                recoveryFile.delete()
            } catch(error:Throwable) {
                // Attempt immediate rollback, but retain the recovery marker
                // if this fails, so onCreate can retry before reading data.
                runCatching{recoverInterruptedRestore()}
                throw error
            }
        } finally {temp.delete()}
    }

    private fun applyImport(input:InputStream) {
        // Always cap reads BEFORE allocating the next chunk. Stage large
        // attachments on disk instead of retaining every byte in RAM.
        var totalBytes=0L
        fun stage(zip:ZipInputStream,out:java.io.OutputStream,limit:Long) {
            val buffer=ByteArray(8192)
            var size=0L
            while(true) {
                val count=zip.read(buffer)
                if(count<0)break
                size+=count
                totalBytes+=count
                require(size<=limit && totalBytes<=256L*1024L*1024L) {
                    "Backup exceeds safe decompressed limits"
                }
                out.write(buffer,0,count)
            }
        }
        fun boundedText(zip:ZipInputStream,limit:Long):String {
            val out=java.io.ByteArrayOutputStream()
            stage(zip,out,limit)
            return out.toString("UTF-8")
        }
        val stageDir=File(context.cacheDir,"terra_import_"+System.nanoTime()).apply{
            check(mkdirs()) {"Cannot stage backup files"}
        }
        try {
        // Finish any recently queued manual strokes before a backup restore
        // replaces discoveries.json. Otherwise a late writer could append
        // stale strokes into the newly imported history.
        DiscoveryStore(context).flushPending()
        val fields=mutableMapOf<String,String>()
        val media=mutableMapOf<String,File>()
        val gpsFiles=mutableMapOf<String,File>()
        val entryNames=HashSet<String>()
        var entryCount=0
        var anchors:JSONObject?=null
        var journey:JSONObject?=null
        var objectiveState:JSONObject?=null
        var globalObjectives:String?=null
        ZipInputStream(input).use {zip->
            while(true) {
                val entry=zip.nextEntry ?: break
                val name=entry.name
                require(++entryCount<=600 && entryNames.add(name)) {
                    "Too many or duplicate backup entries"
                }
                if(name=="anchors.json") {
                    anchors=JSONObject(boundedText(zip,100_000L))
                } else if(name=="journey.json") {
                    journey=JSONObject(boundedText(zip,100_000L))
                } else if(name=="global_objectives.json") {
                    val raw=boundedText(zip,250_000L)
                    org.json.JSONArray(raw)
                    globalObjectives=raw
                } else if(name=="objective_state.json") {
                    objectiveState=JSONObject(boundedText(zip,100_000L))
                } else if(name in keys.map {it.first}) {
                    val value=boundedText(zip,15_000_000L)
                    org.json.JSONArray(value)
                    fields[name]=value
                } else if(name in listOf("terra_gps_raw.csv","terra_gps_trace.csv",
                        "terra_gps_diagnostics.csv")) {
                    val output=File(stageDir,name)
                    output.outputStream().use{stage(zip,it,40_000_000L)}
                    gpsFiles[name]=output
                } else if(name.startsWith("media/")) {
                    val filename=name.removePrefix("media/")
                    require(filename.isNotBlank() && !filename.contains("/") &&
                        !filename.contains("\\") && !filename.startsWith(".")) {
                        "Unsafe media entry"
                    }
                    val output=File(stageDir,"media_"+entryCount)
                    output.outputStream().use{stage(zip,it,25_000_000L)}
                    media[filename]=output
                }
                zip.closeEntry()
            }
        }
        require(fields.keys.containsAll(keys.take(3).map {it.first})) {"Incomplete TerraUnlocked archive"}
        // Archive is validated before replacing local data.
        for((name,source) in media) source.copyTo(File(context.filesDir,name),overwrite=true)
        for((name,source) in gpsFiles) source.copyTo(File(context.filesDir,name),overwrite=true)
        anchors?.let {j->
            val edit=context.getSharedPreferences("terra_anchors_v1",Context.MODE_PRIVATE).edit()
            j.keys().forEach {k->edit.putString(k,j.optString(k))}
            edit.commit()
        }
        for((filename,pref,key) in keys.filter {fields.containsKey(it.first)}) {
            context.getSharedPreferences(pref,Context.MODE_PRIVATE).edit().putString(key,fields.getValue(filename)).commit()
        }
        // Restore retained global IDs before progress and Journey IDs.
        globalObjectives?.let{GlobalObjectiveStore(context).importPinned(it)}
        // Apply optional completion/visibility snapshot after places.json.
        // Strictly whitelist objective IDs from built-ins and restored custom
        // places, never arbitrary preference keys from a ZIP.
        objectiveState?.let {state->
            val valid=(ReconObjectives.all+ObjectiveStore(context).customPlaces()+
                GlobalWorldLandmarks.all+GlobalObjectiveStore(context).savedObjectives())
                .map{it.id}.toSet()
            fun listed(key:String):Set<String> {
                val array=state.optJSONArray(key)?:org.json.JSONArray()
                return (0 until array.length()).mapNotNull {i->
                    array.optString(i,"").takeIf{it in valid}
                }.toSet()
            }
            val completed=listed("completed")
            val hidden=listed("hidden")
            val editor=context.getSharedPreferences("terra_objectives_v1",
                Context.MODE_PRIVATE).edit()
            valid.forEach {id->
                editor.putBoolean("done_"+id,id in completed)
                editor.putBoolean("hidden_"+id,id in hidden)
            }
            val active=state.optString("active","").takeIf{it in valid}
            if(active==null)editor.remove("active")
            else editor.putString("active",active)
            editor.commit()
        }
        // Load Journey only after places.json, so restored user-created goals
        // can be matched to their stable IDs. Older backups remain valid.
        journey?.let {plan->
            val raw=plan.optJSONArray("ids") ?: org.json.JSONArray()
            val ids=(0 until raw.length()).mapNotNull {i->
                raw.optString(i,"").takeIf{it.isNotBlank()}
            }
            val valid=(ReconObjectives.all+ObjectiveStore(context).customPlaces()+
                GlobalWorldLandmarks.all+GlobalObjectiveStore(context).savedObjectives())
                .map{it.id}.toSet()
            JourneyStore(context).save(ids,valid,plan.optBoolean("enabled",false))
        }
        } finally {stageDir.deleteRecursively()}
    }
}