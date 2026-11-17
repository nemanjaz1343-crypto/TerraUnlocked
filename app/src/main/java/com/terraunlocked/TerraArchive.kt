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
    private val keys=listOf(
        Triple("notes.json","terra_notes_v1","notes"),
        Triple("discoveries.json","terra_discoveries_v1","points"),
        Triple("paths.json","terra_manual_paths_v1","segments"),
        Triple("places.json","terra_objectives_v1","custom_places")
    )
    fun exportTo(output:OutputStream) {
        ZipOutputStream(output).use {zip->
            for((filename,pref,key) in keys) {
                val value=context.getSharedPreferences(pref,Context.MODE_PRIVATE).getString(key,"[]") ?: "[]"
                zip.putNextEntry(ZipEntry(filename))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
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
    fun importFrom(input:InputStream) {
        val fields=mutableMapOf<String,String>()
        val media=mutableMapOf<String,ByteArray>()
        val gpsFiles=mutableMapOf<String,ByteArray>()
        var anchors:JSONObject?=null
        ZipInputStream(input).use {zip->
            while(true) {
                val entry=zip.nextEntry ?: break
                val name=entry.name
                if(name=="anchors.json") {
                    val bytes=zip.readBytes()
                    require(bytes.size<=100_000) {"Anchors too large"}
                    anchors=JSONObject(bytes.toString(Charsets.UTF_8))
                } else if(name in keys.map {it.first}) {
                    val data=zip.readBytes()
                    require(data.size<=15_000_000) {"Archive data too large"}
                    val value=data.toString(Charsets.UTF_8)
                    org.json.JSONArray(value)
                    fields[name]=value
                } else if(name in listOf("terra_gps_raw.csv","terra_gps_trace.csv",
                        "terra_gps_diagnostics.csv")) {
                    val out=java.io.ByteArrayOutputStream()
                    val buffer=ByteArray(8192)
                    while(true) {
                        val count=zip.read(buffer)
                        if(count<0)break
                        require(out.size()+count<=10_000_000) {"GPS archive too large"}
                        out.write(buffer,0,count)
                    }
                    gpsFiles[name]=out.toByteArray()
                } else if(name.startsWith("media/")) {
                    val filename=name.removePrefix("media/")
                    if(filename.isNotBlank() && !filename.contains("/") && !filename.contains("\\") && !filename.startsWith(".")) {
                        val out=java.io.ByteArrayOutputStream()
                        val buffer=ByteArray(8192)
                        while(true) {
                            val count=zip.read(buffer)
                            if(count<0)break
                            require(out.size()+count<=25_000_000) {"Attachment too large"}
                            out.write(buffer,0,count)
                        }
                        media[filename]=out.toByteArray()
                    }
                }
                zip.closeEntry()
            }
        }
        require(fields.keys.containsAll(keys.take(3).map {it.first})) {"Incomplete TerraUnlocked archive"}
        // Archive is validated before replacing local data.
        for((name,bytes) in media) File(context.filesDir,name).writeBytes(bytes)
        for((name,bytes) in gpsFiles) File(context.filesDir,name).writeBytes(bytes)
        anchors?.let {j->
            val edit=context.getSharedPreferences("terra_anchors_v1",Context.MODE_PRIVATE).edit()
            j.keys().forEach {k->edit.putString(k,j.optString(k))}
            edit.commit()
        }
        for((filename,pref,key) in keys.filter {fields.containsKey(it.first)}) {
            context.getSharedPreferences(pref,Context.MODE_PRIVATE).edit().putString(key,fields.getValue(filename)).commit()
        }
    }
}