package com.terraunlocked

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import android.widget.TextView
import android.widget.EditText
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.media.MediaRecorder
import android.media.MediaPlayer
import android.graphics.drawable.GradientDrawable
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class MainActivity : Activity(), SensorEventListener {
    private val olive = Color.rgb(31, 40, 32)
    private val sand = Color.rgb(218, 201, 156)
    private val sage = Color.rgb(155, 181, 134)
    private lateinit var map: MapView
    private lateinit var compassView: TextView
    private lateinit var sensors: SensorManager
    private var compassEnabled = false
    private val rotation = FloatArray(9)
    private val orientation = FloatArray(3)
    private var offline = false
    private lateinit var fieldNotes:FieldNotes
    private val notes=mutableListOf<FieldNote>()
    private val pending=mutableListOf<GeoPoint>()
    private lateinit var confirmButton:Button
    private var lastGpsSample:android.location.Location?=null
    private lateinit var store: DiscoveryStore
    private lateinit var pathStore: PathStore
    private val segments = mutableListOf<ManualSegment>()
    private var pathStart: GeoPoint? = null
    private var layersPopup: PopupWindow? = null
    private lateinit var status: TextView
    private lateinit var trackButton: Button
    private lateinit var modeButton: Button
    private lateinit var fogOverlay: DiscoveryFogOverlay
    private val records = mutableListOf<DiscoveryPoint>()
    private val demo = mutableListOf<DiscoveryPoint>()
    private var mode = "VIEW"
    private var tracking = false
    private var followUser = true
    private lateinit var followButton:Button
    private var paintPopup:PopupWindow?=null
    private lateinit var radarDrawer:LinearLayout
    private var radarExpanded=false
    private var radialMenu:PopupWindow?=null
    private var attachmentPoint:GeoPoint?=null
    private var voicePoint:GeoPoint?=null
    private var recorder:MediaRecorder?=null
    private var audioFile:File?=null
    private var measure=false
    private val measurePoints=mutableListOf<GeoPoint>()
    private var filteredFixes=0
    private var stableFixCount=0
    private var lastSavedLocation:android.location.Location?=null
    private var simulating = false
    private var fogEnabled = true
    private var radius = 20
    private var last: DiscoveryPoint? = null
    private var lastPaintTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var simulationIndex = 0
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (!tracking) return
            result.locations.forEach { loc ->
                if (!loc.hasAccuracy() || !loc.latitude.isFinite() || !loc.longitude.isFinite()) return@forEach
                val prev = lastGpsSample
                val elapsed = if(prev!=null) (loc.elapsedRealtimeNanos-prev.elapsedRealtimeNanos)/1e9 else 0.0
                val distance = if(prev!=null) prev.distanceTo(loc) else 0f
                val plausible = prev==null || elapsed<=0.0 ||
                    distance <= maxOf(18f, 3f*maxOf(prev.accuracy,loc.accuracy)) ||
                    distance/elapsed <= 12.0
                val accurate = loc.accuracy<=25f
                if (!plausible || !accurate) {
                    filteredFixes++
                    status.text="GPS SEARCHING / ±${loc.accuracy.toInt()}m / NO REVEAL"
                    // A GPS jump never becomes the new reference. Keep the last
                    // trusted position and avoid making a bridge across a gap.
                    if(filteredFixes>=6) { lastGpsSample=null;last=null;stableFixCount=0;filteredFixes=0 }
                    return@forEach
                }
                filteredFixes=0
                stableFixCount++
                lastGpsSample=loc
                val position=GeoPoint(loc.latitude,loc.longitude)
                fogOverlay.currentPosition=position
                fogOverlay.positionAccuracy=loc.accuracy
                if(followUser && stableFixCount>=2) map.controller.animateTo(position)
                refresh()
                val saved=lastSavedLocation
                // Stationary GPS noise must not paint an area. Require clear
                // displacement beyond combined positioning uncertainty.
                val moved=saved==null || saved.distanceTo(loc) >=
                    maxOf(15f,loc.accuracy*1.8f,saved.accuracy*1.8f)
                if(stableFixCount>=2 && moved) {
                    val point=DiscoveryPoint(loc.latitude,loc.longitude,false,System.currentTimeMillis())
                    records.add(point);store.add(point);last=point
                    lastSavedLocation=loc
                }
                status.text=if(stableFixCount<2) "GPS STABILIZING" else
                    "GPS STABLE / ±${loc.accuracy.toInt()}m / "+if(moved) "RECON" else "STATIONARY"
            }
            }
        }
    }
    private val simulationStep = object : Runnable {
        override fun run() {
            if (!simulating) return
            val centre = map.mapCenter
            if (simulationIndex == 0) demo.clear()
            val t = simulationIndex.toDouble() / 12.0
            // Demo spirals away from starting point, generated entirely within the test layer.
            val base = simulationOrigin ?: GeoPoint(centre.latitude, centre.longitude)
            val lat = base.latitude + 0.000035 * simulationIndex * kotlin.math.sin(t)
            val lon = base.longitude + 0.000035 * simulationIndex * kotlin.math.cos(t)
            demo.add(DiscoveryPoint(lat, lon, false, System.currentTimeMillis() + simulationIndex * 1000L))
            simulationIndex++
            refresh()
            map.controller.animateTo(GeoPoint(lat, lon))
            if (simulationIndex < 90) handler.postDelayed(this, 550L) else stopSimulation()
        }
    }
    private var simulationOrigin: GeoPoint? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = olive
        window.navigationBarColor = olive
        Configuration.getInstance().userAgentValue = packageName
        store = DiscoveryStore(this)
        fieldNotes=FieldNotes(this)
        notes.addAll(fieldNotes.load())
        pathStore = PathStore(this)
        segments.addAll(pathStore.load())
        records.addAll(store.load())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(olive); fitsSystemWindows = true }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(9), 0)
            setBackgroundColor(olive)
        }
        val brand = label("TERRA / UNLOCKED", 12, sand).apply { setTypeface(null, Typeface.BOLD) }
        header.addView(brand, LinearLayout.LayoutParams(0, dp(40), 1f))
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        compassEnabled = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
        val compass = label(if (compassEnabled) "◇ N --°" else "◇ N/A", 12, sand)
        compassView = compass
        header.addView(compass)
        val gps = label("  ● RECON", 10, sage)
        header.addView(gps)
        root.addView(header, LinearLayout.LayoutParams(-1, dp(40)))
        status = label("GPS OFFLINE  |  FOG 82%  |  R 20m", 10, sage)
        status.setPadding(dp(10), dp(4), 0, dp(4))
        root.addView(status)

        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(25.0657, 55.1403))
        }
        fogOverlay = DiscoveryFogOverlay({ records }, { demo }, { segments }, { notes })
        map.overlays.add(fogOverlay)
        map.overlays.add(object : Overlay() {
            override fun onSingleTapConfirmed(event:MotionEvent,mapView:MapView):Boolean {
                if(mode!="VIEW") return false
                val clicked=notes.asReversed().firstOrNull { note ->
                    val p=mapView.projection.toPixels(GeoPoint(note.latitude,note.longitude),Point())
                    kotlin.math.hypot((event.x-p.x).toDouble(),(event.y-p.y).toDouble())<dp(24)
                }
                if(clicked!=null) {showExisting(clicked);return true}
                if(measure) {
                    val p=mapView.projection.fromPixels(event.x.toInt(),event.y.toInt())
                    if(measurePoints.size==2) measurePoints.clear()
                    measurePoints.add(GeoPoint(p.latitude,p.longitude))
                    status.text=if(measurePoints.size==2) "DISTANCE / %.0f m".format(gap(measurePoints[0],measurePoints[1]))
                        else "RULER / SELECT END POINT"
                    refresh();return true
                }
                return false
            }
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(shadow || measurePoints.isEmpty()) return
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.argb(190,250,210,130);strokeWidth=3f}
                val a=mapView.projection.toPixels(measurePoints[0],Point())
                canvas.drawCircle(a.x.toFloat(),a.y.toFloat(),5f,paint)
                if(measurePoints.size==2) {
                    val b=mapView.projection.toPixels(measurePoints[1],Point())
                    canvas.drawLine(a.x.toFloat(),a.y.toFloat(),b.x.toFloat(),b.y.toFloat(),paint)
                    canvas.drawCircle(b.x.toFloat(),b.y.toFloat(),5f,paint)
                }
            }
        })
        map.overlays.add(object : Overlay() {
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(shadow || pending.isEmpty()) return
                val red=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=Color.rgb(239,72,65);strokeWidth=3f;style=Paint.Style.STROKE
                }
                val dot=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(239,72,65)}
                var prev:Point?=null
                pending.forEach { p ->
                    val next=mapView.projection.toPixels(p,Point())
                    canvas.drawCircle(next.x.toFloat(),next.y.toFloat(),5f,dot)
                    prev?.let { canvas.drawLine(it.x.toFloat(),it.y.toFloat(),next.x.toFloat(),next.y.toFloat(),red) }
                    prev=next
                }
            }
            override fun onLongPress(e:MotionEvent,mapView:MapView):Boolean {
                val p=mapView.projection.fromPixels(e.x.toInt(),e.y.toInt())
                showRadialMenu(e.x.toInt(),e.y.toInt(),GeoPoint(p.latitude,p.longitude))
                return true
            }
        })
        map.overlays.add(object : Overlay() {
            override fun onTouchEvent(event:MotionEvent,mapView:MapView):Boolean {
                if(mode=="VIEW" && event.actionMasked==MotionEvent.ACTION_MOVE && event.pointerCount==1 && followUser) {
                    followUser=false
                    if(::followButton.isInitialized) followButton.text="FOLLOW ○"
                }
                return false
            }
        })
        map.overlays.add(object : Overlay() {
            private var strokeLast: GeoPoint? = null
            override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
                if (mode == "VIEW") return false
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    strokeLast = null
                    return true
                }
                if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_MOVE) return false
                val projected = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt())
                val geo = GeoPoint(projected.latitude, projected.longitude)
                if(mode=="PATH") {
                    if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                        pending.add(geo)
                        confirmButton.visibility=if(pending.size>=2) View.VISIBLE else View.GONE
                        status.text="PATH / ${pending.size} VERTICES / CONFIRM"
                        refresh()
                    }
                    return true
                }
                if (mode == "PAINT") {
                    val current = GeoPoint(geo.latitude, geo.longitude)
                    val previous = strokeLast
                    if (previous == null || gap(previous, current) > radius * 0.30f) {
                        val p = DiscoveryPoint(current.latitude, current.longitude, true, System.currentTimeMillis())
                        records.add(p)
                        store.add(p)
                        if (previous != null) {
                            segments.add(ManualSegment(previous.latitude, previous.longitude, current.latitude, current.longitude))
                            pathStore.save(segments)
                        }
                        strokeLast = current
                        refresh()
                    }
                    return true
                }
                if (mode == "ERASE") {
                    val before = records.size + segments.size
                    records.removeAll { it.manual && gap(GeoPoint(it.latitude, it.longitude), geo) < radius * 1.5f }
                    segments.removeAll {
                        gap(GeoPoint(it.aLat, it.aLon), geo) < radius * 1.5f ||
                        gap(GeoPoint(it.bLat, it.bLon), geo) < radius * 1.5f
                    }
                    if (records.size + segments.size != before) {
                        store.replaceAll(records)
                        pathStore.save(segments)
                        refresh()
                    }
                    return true
                }
                return false
            }
        })
        val mapArea=FrameLayout(this)
        mapArea.addView(map,FrameLayout.LayoutParams(-1,-1))
        confirmButton=action("CONFIRM PATH").apply {
            visibility=View.GONE
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(120,39,35))
        }
        mapArea.addView(confirmButton,FrameLayout.LayoutParams(dp(140),dp(43),Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        confirmButton.setOnClickListener {
            pending.zipWithNext().forEach { (a,b) ->
                segments.add(ManualSegment(a.latitude,a.longitude,b.latitude,b.longitude))
            }
            pathStore.save(segments)
            pending.clear()
            confirmButton.visibility=View.GONE
            status.text="PATH CONFIRMED"
            refresh()
        }
        root.addView(mapArea,LinearLayout.LayoutParams(-1,0,1f))
        val controls=LinearLayout(this).apply {setBackgroundColor(olive);gravity=Gravity.CENTER}
        trackButton=action("TRACK")
        followButton=action("FOCUS ●")
        val pathButton=action("PATH")
        modeButton=action("PAINT")
        val eraseButton=action("ERASE")
        val viewButton=action("VIEW")
        listOf(trackButton,followButton,pathButton,modeButton,eraseButton,viewButton).forEach {
            controls.addView(it,LinearLayout.LayoutParams(0,dp(46),1f))
        }
        root.addView(controls)
        setContentView(root)
        trackButton.setOnClickListener {
            if (tracking) stopTracking() else {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                    startTracking()
                else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
            }
        }
        followButton.setOnClickListener {
            followUser=!followUser
            followButton.text=if(followUser) "FOCUS ●" else "FOCUS ○"
            if(followUser) fogOverlay.currentPosition?.let {map.controller.animateTo(it)}
        }
        fun activate(selected: String, button: Button) {
            mode = if (mode == selected) "VIEW" else selected
            pathStart = null
            pending.clear()
            confirmButton.visibility=View.GONE
            refresh()
            pathButton.text = if (mode == "PATH") "PATH ●" else "PATH"
            modeButton.text = if (mode == "PAINT") "PAINT ●" else "PAINT"
            eraseButton.text = if (mode == "ERASE") "ERASE ●" else "ERASE"
            status.text = when(mode) {
                "PATH" -> "PATH / RED VERTICES / CONFIRM"
                "PAINT" -> "PAINT / DRAG TO REVEAL"
                "ERASE" -> "ERASE / DRAG TO REMOVE MANUAL REVEALS"
                else -> "MAP READY"
            }
        }
        pathButton.setOnClickListener { activate("PATH", pathButton) }
        modeButton.setOnClickListener {activate("PAINT",modeButton);if(mode=="PAINT") showPaintSettings() else paintPopup?.dismiss()}
        eraseButton.setOnClickListener { activate("ERASE", eraseButton) }
        viewButton.setOnClickListener { showLayers(viewButton) }
        compass.setOnClickListener { map.mapOrientation = 0f }
        map.setUseDataConnection(true)
    }


    private fun showRadialMenu(x:Int,y:Int,where:GeoPoint) {
        radialMenu?.dismiss()
        val size=dp(230)
        val panel=FrameLayout(this)
        fun item(title:String,gravity:Int,onTap:()->Unit) {
            panel.addView(action(title).apply {
                background=android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius=dp(12).toFloat()
                    setColor(Color.rgb(43,54,43))
                    setStroke(dp(1),Color.rgb(143,158,116))
                }
                setOnClickListener { radialMenu?.dismiss();onTap() }
            },FrameLayout.LayoutParams(dp(83),dp(45),gravity))
        }
        item("✎ NOTE",Gravity.START or Gravity.CENTER_VERTICAL) { showNoteEditor(where,"MEMORY") }
        item("⚑ FLAG",Gravity.TOP or Gravity.CENTER_HORIZONTAL) { showNoteEditor(where,"CHECKPOINT") }
        item("▣ PHOTO",Gravity.END or Gravity.CENTER_VERTICAL) {
            attachmentPoint=where
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type="image/*"
            },82)
        }
        item("◆ PIN",Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL) { showNoteEditor(where,"POI") }
        panel.addView(action("● REC").apply {setOnClickListener {radialMenu?.dismiss();startVoice(where)}},FrameLayout.LayoutParams(dp(75),dp(32),Gravity.CENTER))
        val loc=IntArray(2)
        map.getLocationOnScreen(loc)
        val w=resources.displayMetrics.widthPixels
        val h=resources.displayMetrics.heightPixels
        val left=(loc[0]+x-size/2).coerceIn(0,(w-size).coerceAtLeast(0))
        val top=(loc[1]+y-size/2).coerceIn(dp(35),(h-size-dp(40)).coerceAtLeast(dp(35)))
        radialMenu=PopupWindow(panel,size,size,true).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(6).toFloat()
            showAtLocation(map,Gravity.TOP or Gravity.START,left,top)
        }
    }

    private var poiPopup:PopupWindow?=null
    private fun showCheckpointPanel() {
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(10),dp(10),dp(10),dp(10))
            setBackgroundColor(Color.rgb(43,54,43))
        }
        panel.addView(label("FIELD INVENTORY / ${notes.size}",12,sand))
        for(kind in listOf("CHECKPOINT","POI")) {
            panel.addView(action("+ $kind").apply {setOnClickListener {
                poiPopup?.dismiss()
                val c=map.mapCenter
                showNoteEditor(GeoPoint(c.latitude,c.longitude),kind)
            }})
        }
        notes.takeLast(5).reversed().forEach { note ->
            panel.addView(action("${note.kind}: ${note.title.take(15)}").apply {
                setOnClickListener {
                    poiPopup?.dismiss()
                    map.controller.animateTo(GeoPoint(note.latitude,note.longitude))
                }
            })
        }
        poiPopup?.dismiss()
        poiPopup=PopupWindow(panel,dp(230),-2,true).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(43,54,43)))
            showAtLocation(map,Gravity.START or Gravity.CENTER_VERTICAL,dp(8),0)
        }
    }
    private fun showNoteEditor(where:GeoPoint,kind:String="POI") {
        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(15),dp(12),dp(15),dp(12))
        }
        val title=EditText(this).apply {hint="Title / callsign";setTextColor(sand);setHintTextColor(sage)}
        val detail=EditText(this).apply {hint="Field notes";minLines=3;setTextColor(sand);setHintTextColor(sage)}
        val kinds=listOf("POI","CHECKPOINT","MEMORY","CAMP")
        val selector=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,kinds)
            setSelection(kinds.indexOf(kind).coerceAtLeast(0))
        }
        layout.setBackgroundColor(Color.rgb(39,51,41))
        layout.addView(selector);layout.addView(title);layout.addView(detail)
        android.app.AlertDialog.Builder(this)
            .setView(layout).setNegativeButton("CANCEL",null)
            .setPositiveButton("SAVE") { _,_ ->
                notes.add(FieldNote(where.latitude,where.longitude,title.text.toString().ifBlank{"Untitled"},
                    selector.selectedItem.toString(),detail.text.toString(),System.currentTimeMillis()))
                fieldNotes.save(notes)
                status.text="FIELD NOTE SAVED"
                refresh()
            }.show()
    }

    private fun showExisting(note:FieldNote) {
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(12))
            setBackgroundColor(Color.rgb(40,52,42))
        }
        panel.addView(label(note.kind+" / "+note.title,16,sand))
        panel.addView(label(if(note.kind=="VOICE") "Voice recording" else if(note.kind=="PHOTO") "Photo attachment" else note.body,13,sage))
        if(note.kind=="VOICE") panel.addView(action("▶ PLAY").apply {setOnClickListener {
            runCatching {
                MediaPlayer().apply {
                    setDataSource(File(filesDir,note.body).absolutePath)
                    setOnCompletionListener {it.release()}
                    prepare();start()
                }
            }.onFailure { status.text="PLAYBACK ERROR" }
        }})
        if(note.kind=="PHOTO") panel.addView(action("▣ OPEN PHOTO").apply {setOnClickListener {
            val bitmap=android.graphics.BitmapFactory.decodeFile(File(filesDir,note.body).absolutePath)
            if(bitmap!=null) android.app.AlertDialog.Builder(this@MainActivity).setView(
                android.widget.ImageView(this@MainActivity).apply {setImageBitmap(bitmap);adjustViewBounds=true}
            ).setPositiveButton("CLOSE",null).show()
        }})
        val dialog=android.app.AlertDialog.Builder(this).setView(panel).setNegativeButton("CLOSE",null)
            .setNeutralButton("DELETE") {_,_->notes.remove(note);fieldNotes.save(notes);refresh()}.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }
    private fun startVoice(where:GeoPoint) {
        voicePoint=where
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),11);return
        }
        try {
            val file=File(filesDir,"voice_"+System.currentTimeMillis()+".m4a")
            recorder=MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath);prepare();start()
            }
            audioFile=file
            android.app.AlertDialog.Builder(this).setTitle("● VOICE MEMO")
                .setMessage("Recording locally")
                .setNegativeButton("DISCARD") {_,_->stopVoice(false)}
                .setPositiveButton("SAVE") {_,_->stopVoice(true)}
                .setCancelable(false).show()
        }catch(e:Exception) {recorder?.release();recorder=null;status.text="MIC UNAVAILABLE"}
    }
    private fun stopVoice(save:Boolean) {
        runCatching {recorder?.stop()}
        recorder?.release();recorder=null
        val file=audioFile
        val point=voicePoint
        if(save && file!=null && file.exists() && file.length()>0 && point!=null) {
            notes.add(FieldNote(point.latitude,point.longitude,"Voice memo","VOICE",file.name,System.currentTimeMillis()))
            fieldNotes.save(notes);refresh()
        }else file?.delete()
        audioFile=null;voicePoint=null
    }
    private fun gap(a: GeoPoint, b: GeoPoint): Float {
        val out = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
        return out[0]
    }
    private fun showPaintSettings() {
        paintPopup?.dismiss()
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(8),dp(12),dp(8))
            setBackgroundColor(Color.argb(230,38,52,41))
        }
        val caption=label("PAINT / RADIUS "+radius+"m",12,sand)
        panel.addView(caption)
        panel.addView(SeekBar(this).apply {
            max=95;progress=radius-5
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar?,value:Int,user:Boolean) {
                    radius=value+5;fogOverlay.radiusMeters=radius.toDouble()
                    caption.text="PAINT / RADIUS "+radius+"m";refresh()
                }
                override fun onStartTrackingTouch(bar:SeekBar?) {}
                override fun onStopTrackingTouch(bar:SeekBar?) {}
            })
        })
        paintPopup=PopupWindow(panel,dp(235),dp(98),true).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            showAtLocation(map,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,0,dp(61))
        }
    }
    private fun showLayers(anchor: View) {
        if (layersPopup?.isShowing == true) { layersPopup?.dismiss(); return }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(225,43,54,43))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        fun option(name: String, callback: () -> Unit) {
            panel.addView(action(name).apply { setOnClickListener { callback(); layersPopup?.dismiss() } },
                LinearLayout.LayoutParams(-1, dp(43)))
        }
        option("FOG ${if (fogEnabled) "ON" else "OFF"}") {
            fogEnabled = !fogEnabled; fogOverlay.fogVisible = fogEnabled; refresh()
        }
        option("FOG OPACITY ${fogOverlay.opacity}") {
            fogOverlay.opacity = if (fogOverlay.opacity >= 235) 125 else fogOverlay.opacity + 35; refresh()
        }
        option("CHECKPOINTS ${if(fogOverlay.showCheckpoints) "ON" else "OFF"}") {
            fogOverlay.showCheckpoints=!fogOverlay.showCheckpoints;refresh()
        }
        option("RULER ${if(measure) "ON" else "OFF"}") {
            measure=!measure;measurePoints.clear();status.text=if(measure) "RULER / SELECT FIRST POINT" else "RULER OFF";refresh()
        }
        option("BACKTRACK ${if(fogOverlay.backtrack) "ON" else "OFF"}") {
            fogOverlay.backtrack=!fogOverlay.backtrack;refresh()
        }
        option("ROUTE ${if (fogOverlay.showRoute) "ON" else "OFF"}") {
            fogOverlay.showRoute = !fogOverlay.showRoute; refresh()
        }
        option("OFFLINE ${if (offline) "ON" else "OFF"}") {
            offline = !offline
            map.setUseDataConnection(!offline)
            status.text = if (offline) "OFFLINE / LOCAL TILES ONLY" else "ONLINE / MAP TILES ENABLED"
        }
        option("IMPORT OFFLINE MAP") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            startActivityForResult(intent, 81)
        }
        option("MAP OSM / TOPO") {
            map.setTileSource(if(map.tileProvider.tileSource == TileSourceFactory.MAPNIK)
                TileSourceFactory.OpenTopo else TileSourceFactory.MAPNIK)
        }
        option("UNDO LAST PATH") {
            if (segments.isNotEmpty()) { segments.removeAt(segments.lastIndex); pathStore.save(segments); refresh() }
        }
        option("UNDO LAST PAINT") {
            val idx = records.indexOfLast { it.manual }
            if (idx >= 0) { records.removeAt(idx); store.replaceAll(records); refresh() }
        }
        layersPopup = PopupWindow(panel, dp(240), dp(475), true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(43, 54, 43)))
            elevation = dp(8).toFloat()
            showAtLocation(map, Gravity.BOTTOM or Gravity.END, dp(8), dp(60))
        }
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)

        if(requestCode==82 && resultCode==RESULT_OK) {
            val point=attachmentPoint
            val uri=data?.data
            if(point!=null && uri!=null) {
                try {
                    val file=File(filesDir,"photo_"+System.currentTimeMillis()+".jpg")
                    contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("Cannot open image")
                    notes.add(FieldNote(point.latitude,point.longitude,"Photo","PHOTO",file.name,System.currentTimeMillis()))
                    fieldNotes.save(notes)
                    status.text="PHOTO ATTACHED"
                    refresh()
                } catch(e:Exception) {status.text="PHOTO IMPORT FAILED"}
            }
            attachmentPoint=null
            return
        }
        if(requestCode!=81 || resultCode!=RESULT_OK) return
        val uri = data?.data ?: return
        val filename = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "offline.mbtiles"
        val extension = filename.substringAfterLast('.', "").lowercase()
        if(extension !in setOf("mbtiles","sqlite","zip","gemf")) {
            status.text = "UNSUPPORTED ARCHIVE / USE MBTILES, SQLITE, ZIP OR GEMF"
            return
        }
        try {
            val dir = Configuration.getInstance().osmdroidBasePath
            dir.mkdirs()
            val dest = File(dir,"terra_import_${System.currentTimeMillis()}.$extension")
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot read selected file" }
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            status.text = "OFFLINE MAP IMPORTED / RESTART APP TO LOAD"
        } catch(e:Exception) {
            status.text = "MAP IMPORT FAILED / ${e.javaClass.simpleName}"
        }
    }
    override fun onSensorChanged(event: SensorEvent) {
        if(event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotation,event.values)
        SensorManager.getOrientation(rotation,orientation)
        val degrees = ((Math.toDegrees(orientation[0].toDouble())+360.0)%360.0).toInt()
        compassView.text = "▲ N %03d°".format(degrees)
        fogOverlay.northAzimuth = degrees.toFloat()
        map.invalidate()
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {}
    private fun startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        last = null
        lastGpsSample=null
        lastSavedLocation=null
        stableFixCount=0
        tracking = true
        trackButton.text = "STOP GPS"
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateDistanceMeters(8f).build()
        client.requestLocationUpdates(req, callback, mainLooper)
        status.text = "GPS ACQUIRING..."
    }
    private fun stopTracking() {
        tracking = false
        client.removeLocationUpdates(callback)
        trackButton.text = "TRACK"
        status.text = "GPS OFFLINE  |  DATA SAVED"
    }
    private fun stopSimulation() {
        simulating = false
        handler.removeCallbacks(simulationStep)
        demo.clear()
        simulationOrigin = null
        (findViewById<android.view.ViewGroup>(android.R.id.content).getChildAt(0) as LinearLayout)
            .let { ((it.getChildAt(it.childCount - 1) as LinearLayout).getChildAt(4) as Button).text = "SIM" }
        status.text = "SIMULATION ENDED  |  NO TEST DATA SAVED"
        refresh()
    }
    private fun refresh() { map.invalidate() }
    private fun metres(a: DiscoveryPoint, b: DiscoveryPoint): Float {
        val out = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
        return out[0]
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun label(t: String, size: Int, color: Int) = TextView(this).apply {
        text = t; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
    }
    private fun action(t: String) = Button(this).apply {
        text = t; textSize = 10f; isAllCaps = false
        setTextColor(sand); setBackgroundColor(Color.rgb(58, 69, 52))
        setPadding(0, 0, 0, 0)
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 10 && grants.isNotEmpty() && grants[0] == PackageManager.PERMISSION_GRANTED) startTracking()
        if (code == 11 && grants.isNotEmpty() && grants[0] == PackageManager.PERMISSION_GRANTED) voicePoint?.let {startVoice(it)}
    }
    override fun onResume() {
        super.onResume(); map.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_UI)
        }
    }
    override fun onPause() {
        super.onPause(); sensors.unregisterListener(this); map.onPause()
        if (tracking) stopTracking()
    }
    override fun onDestroy() {
        super.onDestroy()
        if (tracking) stopTracking()
        handler.removeCallbacks(simulationStep)
        layersPopup?.dismiss()
        poiPopup?.dismiss()
        radialMenu?.dismiss()
        paintPopup?.dismiss()
    }
}
