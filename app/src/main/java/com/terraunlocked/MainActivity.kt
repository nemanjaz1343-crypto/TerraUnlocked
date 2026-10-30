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
import android.content.BroadcastReceiver
import android.content.IntentFilter
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
    private var receiverRegistered=false
    private val trackingReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action!=TrackingService.ACTION_FIX)return
            val lat=intent.getDoubleExtra("lat",Double.NaN)
            val lon=intent.getDoubleExtra("lon",Double.NaN)
            if(!lat.isFinite() || !lon.isFinite())return
            val point=GeoPoint(lat,lon)
            fogOverlay.currentPosition=point
            fogOverlay.positionAccuracy=intent.getFloatExtra("accuracy",0f)
            if(followUser)map.controller.animateTo(point)
            if(intent.getBooleanExtra("revealed",false)) {
                records.clear();records.addAll(store.load())
            }
            status.text="GPS ACTIVE / ±"+fogOverlay.positionAccuracy.toInt()+"m"
            updateNavigation()
            refresh()
        }
    }
    private var followUser = true
    private lateinit var followButton:Button
    private var paintPopup:PopupWindow?=null
    private lateinit var radarDrawer:LinearLayout
    private var radarExpanded=false
    private var radialMenu:PopupWindow?=null
    private var attachmentPoint:GeoPoint?=null
    private var attachmentNoteTime:Long?=null
    private var cameraUri:Uri?=null
    private var previewLocation:GeoPoint?=null
    private var previewLabel=""
    private var pendingObjective:ReconObjective?=null
    private var previewBar:LinearLayout?=null
    private var navigationTarget:Long?=null
    private var historyCutoff:Long?=null
    private var timeMachinePopup:PopupWindow?=null
    private lateinit var objectiveStore:ObjectiveStore
    private val metroStations=mutableListOf<TransitStation>()
    private var stationLoading=false
    private var objectivesPopup:PopupWindow?=null
    private var voicePoint:GeoPoint?=null
    private var voiceNoteTime:Long?=null
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
                val elapsed = if (prev != null) (loc.elapsedRealtimeNanos-prev.elapsedRealtimeNanos)/1e9 else 0.0
                val distance = if (prev != null) prev.distanceTo(loc) else 0f
                val reliable = loc.accuracy <= 50f
                val jump = prev != null && elapsed > 0.5 &&
                    distance > maxOf(45f, 2f*maxOf(prev.accuracy,loc.accuracy)) &&
                    distance / elapsed > 15.0
                if (!reliable || jump) {
                    filteredFixes++
                    status.text = if (!reliable) "GPS WEAK / ±${loc.accuracy.toInt()}m / WAITING"
                                  else "GPS JUMP / WAITING FOR STABLE FIX"
                    // Never reveal weak fixes. After repeated rejections, start
                    // a fresh segment instead of connecting unrelated locations.
                    if (filteredFixes >= 5) {
                        lastGpsSample=null
                        lastSavedLocation=null
                        stableFixCount=0
                        filteredFixes=0
                    }
                    return@forEach
                }
                filteredFixes=0
                stableFixCount++
                lastGpsSample=loc
                val position=GeoPoint(loc.latitude,loc.longitude)
                fogOverlay.currentPosition=position
                fogOverlay.positionAccuracy=loc.accuracy
                if(followUser) map.controller.animateTo(position)
                refresh()
                val saved=lastSavedLocation
                val secondsSinceSaved=if(saved!=null) (loc.elapsedRealtimeNanos-saved.elapsedRealtimeNanos)/1e9 else 0.0
                val displacement=if(saved!=null) saved.distanceTo(loc) else 0f
                val minimumMovement=if(saved!=null) maxOf(10f, 1.1f*maxOf(saved.accuracy,loc.accuracy)) else 0f
                // First reliable fix always reveals the user's current area.
                // Later samples need real displacement, and breaks never form bridges.
                val shouldReveal=saved==null || (displacement>=minimumMovement && secondsSinceSaved>0.0)
                if(shouldReveal) {
                    val time=System.currentTimeMillis()
                    val isolated=saved==null || secondsSinceSaved>90.0 ||
                        displacement>maxOf(120f,secondsSinceSaved.toFloat()*12f)
                    val point=DiscoveryPoint(loc.latitude,loc.longitude,false,time)
                    records.add(point)
                    store.add(point)
                    last=point
                    lastSavedLocation=loc
                    refresh()
                }
                status.text="GPS ACTIVE / ±${loc.accuracy.toInt()}m / "+if(shouldReveal) "REVEALED" else "STATIONARY"
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
        objectiveStore=ObjectiveStore(this)
        metroStations.addAll(TransitStations(this).load())
        objectiveStore.enabled=true
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
        header.addView(action("◎ MISSIONS").apply {setOnClickListener {showObjectives()}},LinearLayout.LayoutParams(dp(97),dp(34)))
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
        fogOverlay = DiscoveryFogOverlay({ records.filter { historyCutoff==null || it.timestamp<=historyCutoff!! } }, { if(historyCutoff==null)demo else emptyList() }, { if(historyCutoff==null)segments else emptyList() }, { notes.filter {historyCutoff==null || it.time<=historyCutoff!!} })
        map.overlays.add(fogOverlay)
        map.overlays.add(object:Overlay() {
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(shadow)return
                val marker=notes.firstOrNull {it.time==navigationTarget} ?: return
                val user=fogOverlay.currentPosition ?: return
                val results=FloatArray(2)
                android.location.Location.distanceBetween(user.latitude,user.longitude,marker.latitude,marker.longitude,results)
                val bearing=Math.toRadians((results[1]-mapView.mapOrientation).toDouble())
                val cx=canvas.width/2f;val cy=dp(54).toFloat()
                val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(241,214,143);strokeWidth=dp(3).toFloat();style=Paint.Style.STROKE}
                val dx=kotlin.math.sin(bearing).toFloat();val dy=-kotlin.math.cos(bearing).toFloat()
                canvas.drawCircle(cx,cy,dp(22).toFloat(),Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.argb(210,28,41,33)})
                canvas.drawLine(cx-dx*dp(9),cy-dy*dp(9),cx+dx*dp(13),cy+dy*dp(13),ink)
                val text=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;textSize=dp(10).toFloat();textAlign=Paint.Align.CENTER}
                val distance=if(results[0]>=1000f) "%.1f km".format(results[0]/1000f) else "%.0f m".format(results[0])
                canvas.drawText(distance,cx,cy+dp(37),text)
            }
        })
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
        map.overlays.add(object:Overlay() {
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(shadow)return
                val ink=Paint(Paint.ANTI_ALIAS_FLAG)
                (ReconObjectives.all+objectiveStore.customPlaces()).filterNot {objectiveStore.isHidden(it.id)}.forEach {o->
                    val p=mapView.projection.toPixels(GeoPoint(o.latitude,o.longitude),Point())
                    ink.color=Color.argb(215,27,43,34)
                    canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),dp(13).toFloat(),ink)
                    ink.color=if(objectiveStore.completed(o.id))Color.GREEN else if(objectiveStore.active==o.id)Color.YELLOW else Color.CYAN
                    ink.textSize=dp(18).toFloat();ink.textAlign=Paint.Align.CENTER
                    canvas.drawText(if(objectiveStore.completed(o.id))"✓" else if(objectiveStore.active==o.id)"⚑" else "◆",p.x.toFloat(),p.y.toFloat()+dp(5).toFloat(),ink)
                }
            }
            override fun onSingleTapConfirmed(event:MotionEvent,mapView:MapView):Boolean {
                if(!objectiveStore.enabled || mode!="VIEW")return false
                val hit=(ReconObjectives.all+objectiveStore.customPlaces()).filterNot {objectiveStore.isHidden(it.id)}.firstOrNull {o->
                    val p=mapView.projection.toPixels(GeoPoint(o.latitude,o.longitude),Point())
                    kotlin.math.hypot((p.x-event.x).toDouble(),(p.y-event.y).toDouble())<dp(22)
                } ?: return false
                focusObjective(hit);return true
            }
            override fun onLongPress(event:MotionEvent,mapView:MapView):Boolean {
                if(mode!="VIEW")return false
                val hit=(ReconObjectives.all+objectiveStore.customPlaces()).filterNot {objectiveStore.isHidden(it.id)}.firstOrNull {o->
                    val p=mapView.projection.toPixels(GeoPoint(o.latitude,o.longitude),Point())
                    kotlin.math.hypot((p.x-event.x).toDouble(),(p.y-event.y).toDouble())<dp(24)
                } ?: return false
                focusObjective(hit,true);return true
            }
        })
        map.overlays.add(object:Overlay() {
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(shadow)return
                val point=previewLocation ?: return
                val p=mapView.projection.toPixels(point,Point())
                val brush=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=sand;style=Paint.Style.STROKE;strokeWidth=dp(2).toFloat()
                }
                canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),dp(22).toFloat(),brush)
                canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),dp(7).toFloat(),brush)
                brush.style=Paint.Style.FILL
                canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),dp(2).toFloat(),brush)
            }
        })
        map.overlays.add(object:Overlay() {
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(shadow)return
                val brush=Paint(Paint.ANTI_ALIAS_FLAG)
                metroStations.forEach {station->
                    val p=mapView.projection.toPixels(GeoPoint(station.lat,station.lon),Point())
                    brush.color=Color.argb(215,29,69,94)
                    canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),dp(13).toFloat(),brush)
                    brush.color=Color.WHITE;brush.textSize=dp(13).toFloat()
                    brush.textAlign=Paint.Align.CENTER;brush.typeface=Typeface.DEFAULT_BOLD
                    // Draw a train pictogram with primitives; no emoji font dependency.
                    val x=p.x.toFloat();val y=p.y.toFloat()
                    brush.style=Paint.Style.STROKE;brush.strokeWidth=dp(1.5f).toFloat()
                    canvas.drawRoundRect(x-dp(7),y-dp(8),x+dp(7),y+dp(6),dp(3).toFloat(),dp(3).toFloat(),brush)
                    canvas.drawLine(x-dp(5),y-dp(2),x+dp(5),y-dp(2),brush)
                    canvas.drawLine(x-dp(4),y+dp(8),x-dp(1),y+dp(5),brush)
                    canvas.drawLine(x+dp(4),y+dp(8),x+dp(1),y+dp(5),brush)
                    brush.style=Paint.Style.FILL
                    canvas.drawCircle(x-dp(4),y+dp(3),dp(1).toFloat(),brush)
                    canvas.drawCircle(x+dp(4),y+dp(3),dp(1).toFloat(),brush)
                }
            }
            override fun onSingleTapConfirmed(e:MotionEvent,mapView:MapView):Boolean {
                if(mode!="VIEW")return false
                val hit=metroStations.firstOrNull {station->
                    val p=mapView.projection.toPixels(GeoPoint(station.lat,station.lon),Point())
                    kotlin.math.hypot((e.x-p.x).toDouble(),(e.y-p.y).toDouble())<dp(20)
                } ?: return false
                showTransitStation(hit);return true
            }
        })
        map.overlays.add(object:Overlay() {
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(shadow)return
                val prefs=getSharedPreferences("terra_anchors_v1",Context.MODE_PRIVATE)
                listOf("HOME" to "⌂","OFFICE" to "▣").forEach { item ->
                    val lat=prefs.getString(item.first+"_lat",null)?.toDoubleOrNull()
                    val lon=prefs.getString(item.first+"_lon",null)?.toDoubleOrNull()
                    if(lat!=null&&lon!=null) {
                        val p=mapView.projection.toPixels(GeoPoint(lat,lon),Point())
                        val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.argb(220,31,49,37)}
                        canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),dp(15).toFloat(),ink)
                        ink.color=sand;ink.textSize=dp(17).toFloat();ink.textAlign=Paint.Align.CENTER
                        canvas.drawText(item.second,p.x.toFloat(),p.y.toFloat()+dp(5),ink)
                    }
                }
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
        val previewControls=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER
            background=GradientDrawable().apply {
                cornerRadius=dp(12).toFloat();setColor(Color.argb(120,28,40,32))
                setStroke(dp(1),Color.argb(160,160,183,140))
            }
            visibility=View.GONE
        }
        previewBar=previewControls
        previewControls.addView(action("× CANCEL").apply {setOnClickListener {clearMapPreview()}},LinearLayout.LayoutParams(0,dp(48),1f))
        previewControls.addView(action("✓ CONFIRM OBJECTIVE").apply {setOnClickListener {
            val point=previewLocation
            if(point!=null) {
                val chosen=pendingObjective
                if(chosen!=null) {clearMapPreview();showObjective(chosen)}
                else {
                    objectiveStore.addPlace(previewLabel,point.latitude,point.longitude,"Saved map target")
                    objectiveStore.enabled=true
                    clearMapPreview();status.text="OBJECTIVE SAVED";refresh()
                }
            }
        }},LinearLayout.LayoutParams(0,dp(48),2f))
        mapArea.addView(previewControls,FrameLayout.LayoutParams(-1,dp(54),Gravity.BOTTOM))
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
        if(metroStations.isEmpty())handler.post {refreshMetro()}
        trackButton.setOnClickListener {
            if (tracking) stopTracking() else {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                    startTracking()
                else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
            }
        }
        followButton.setOnClickListener {
            followUser=true
            followButton.text="FOCUS ●"
            fogOverlay.currentPosition?.let {map.controller.setZoom(18.0);map.controller.animateTo(it)}
                ?: run {status.text="FOCUS / WAITING FOR GPS"}
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
        val size=dp(256)
        val ring=RadialMenuView(this) { choice ->
            radialMenu?.dismiss()
            when(choice) {
                0 -> showNoteEditor(where,"CHECKPOINT")
                1 -> {
                    attachmentPoint=where
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE);type="image/*"
                    },82)
                }
                2 -> showMapLookup()
                3 -> {
                    notes.add(FieldNote(where.latitude,where.longitude,"Quick pin","POI","",System.currentTimeMillis(),"◆"))
                    fieldNotes.save(notes);status.text="QUICK PIN SAVED";refresh()
                }
                4 -> startVoice(where)
                5 -> showNoteEditor(where,"MEMORY")
            }
        }
        val loc=IntArray(2);map.getLocationOnScreen(loc)
        val w=resources.displayMetrics.widthPixels
        val h=resources.displayMetrics.heightPixels
        val left=(loc[0]+x-size/2).coerceIn(0,(w-size).coerceAtLeast(0))
        val top=(loc[1]+y-size/2).coerceIn(dp(38),(h-size-dp(50)).coerceAtLeast(dp(38)))
        radialMenu=PopupWindow(ring,size,size,true).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(7).toFloat()
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
            setPadding(dp(17),dp(16),dp(17),dp(15))
            background=GradientDrawable().apply {
                cornerRadius=dp(19).toFloat()
                setColor(Color.argb(126,31,44,36))
                setStroke(dp(1),Color.rgb(119,139,106))
            }
        }
        layout.addView(label("FIELD ENTRY  /  "+kind,13,sand).apply {
            setTypeface(null,Typeface.BOLD)
        })
        val preview=label("◆  SELECT MARKER",14,sage)
        layout.addView(preview)
        val iconBar=LinearLayout(this).apply {gravity=Gravity.CENTER;orientation=LinearLayout.HORIZONTAL}
        val icons=listOf("◆","⚑","★","✎","▲","●","⌖","▣")
        var selected=when(kind) {"CHECKPOINT"->"⚑";"MEMORY"->"✎";"CAMP"->"▲";else->"◆"}
        fun updatePreview() { preview.text=selected+"  MARKER SELECTED" }
        icons.forEach { glyph ->
            iconBar.addView(action(glyph).apply {
                textSize=16f
                setOnClickListener {selected=glyph;updatePreview()}
            },LinearLayout.LayoutParams(0,dp(42),1f))
        }
        layout.addView(iconBar)
        updatePreview()
        val title=EditText(this).apply {
            hint="Name / callsign";setTextColor(sand);setHintTextColor(sage)
            textSize=15f;setSingleLine(true)
        }
        val detail=EditText(this).apply {
            hint="Add a note (optional)";minLines=2;maxLines=4
            setTextColor(sand);setHintTextColor(sage);textSize=14f
        }
        layout.addView(title);layout.addView(detail)
        val dialog=android.app.AlertDialog.Builder(this)
            .setView(layout).setNegativeButton("CANCEL",null)
            .setPositiveButton("SAVE") { _,_ ->
                notes.add(FieldNote(where.latitude,where.longitude,
                    title.text.toString().ifBlank{"Untitled"},kind,
                    detail.text.toString(),System.currentTimeMillis(),selected))
                fieldNotes.save(notes)
                status.text="MARKER SAVED"
                refresh()
            }.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    private fun loadOrientedPhoto(file:File):android.graphics.Bitmap? {
        val bitmap=android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val exif=android.media.ExifInterface(file.absolutePath)
        val angle=when(exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1)) {
            6 -> 90f
            3 -> 180f
            8 -> 270f
            else -> 0f
        }
        if(angle==0f)return bitmap
        val matrix=android.graphics.Matrix().apply {postRotate(angle)}
        return android.graphics.Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
    }
    private fun updateNavigation() {
        val target=notes.firstOrNull {it.time==navigationTarget} ?: return
        val user=fogOverlay.currentPosition ?: return
        val readings=FloatArray(2)
        android.location.Location.distanceBetween(user.latitude,user.longitude,target.latitude,target.longitude,readings)
        val distance=if(readings[0]>=1000f) "%.1f km".format(readings[0]/1000f) else "%.0f m".format(readings[0])
        status.text="NAV / "+target.title.take(12)+" / "+distance+" / "+readings[1].toInt()+"°"
    }
    private fun focusObjective(o:ReconObjective,extent:Boolean=false) {
        followUser=false
        if(::followButton.isInitialized)followButton.text="FOCUS ○"
        pendingObjective=o
        previewLocation=GeoPoint(o.latitude,o.longitude)
        previewLabel=o.name
        previewBar?.visibility=View.VISIBLE
        if(extent)map.controller.setZoom(17.5)
        map.controller.animateTo(GeoPoint(o.latitude,o.longitude))
        showObjectiveHint(o)
        refresh()
    }
    private fun showObjectiveHint(o:ReconObjective) {
        val panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(10),dp(12),dp(10));background=tacticalPanel()}
        panel.addView(label(o.name,14,sand))
        panel.addView(label(o.brief.take(100),12,sage))
        val current=fogOverlay.currentPosition
        if(current!=null){
            val d=FloatArray(1)
            android.location.Location.distanceBetween(current.latitude,current.longitude,o.latitude,o.longitude,d)
            panel.addView(label(if(d[0]>=1000f)"%.1f km".format(d[0]/1000f) else "%.0f m".format(d[0]),12,sand))
        }
        val popup=PopupWindow(panel,dp(285),dp(145),false).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(5).toFloat()
        }
        panel.addView(action("× CLOSE / CONFIRM BELOW FOR OPTIONS").apply {setOnClickListener {popup.dismiss()}})
        popup.showAtLocation(map,Gravity.TOP or Gravity.CENTER_HORIZONTAL,0,dp(90))
    }
    private fun clearMapPreview() {
        previewLocation=null;previewLabel="";pendingObjective=null
        previewBar?.visibility=View.GONE
        refresh()
    }
    private fun tacticalPanel():GradientDrawable = GradientDrawable().apply {
        cornerRadius=dp(16).toFloat()
        setColor(Color.argb(126,29,43,34))
        setStroke(dp(1),Color.argb(190,155,181,134))
    }
    private fun showMapLookup() {
        clearMapPreview()
        val box=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(14),dp(12),dp(14),dp(12))
            background=tacticalPanel()
        }
        box.addView(label("⌕ MAP / TARGET SEARCH",13,sand))
        val query=EditText(this).apply {
            hint="Google Maps link, coordinates or address"
            setTextColor(sand);setHintTextColor(sage);minLines=2;maxLines=3
        }
        box.addView(query)
        val popup=PopupWindow(box,dp(310),dp(204),true).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(7).toFloat()
        }
        fun locate() {
            val value=query.text.toString().trim()
            if(value.isNotEmpty()) {popup.dismiss();resolveMapLocation(value)}
        }
        box.addView(action("▣ PASTE & LOCATE").apply {setOnClickListener {
            val clipboard=getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val copied=clipboard.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
            if(!copied.isNullOrBlank())query.setText(copied)
            if(query.text.isBlank())status.text="CLIPBOARD EMPTY / COPY GOOGLE MAPS LINK FIRST"
            else locate()
        }})
        box.addView(action("⌖ SEARCH TEXT").apply {setOnClickListener {locate()}})
        popup.showAtLocation(map,Gravity.TOP or Gravity.CENTER_HORIZONTAL,0,dp(115))
    }
    private fun resolveMapLocation(raw:String) {
        if(raw.isBlank())return
        val shortLink=Regex("""https?://(?:maps\.app\.goo\.gl|goo\.gl/maps)/[^\s]+""").find(raw)?.value
        if(shortLink!=null) {
            status.text="MAP / RESOLVING GOOGLE MAPS LINK..."
            Thread {
                runCatching {
                    val conn=(java.net.URL(shortLink).openConnection() as java.net.HttpURLConnection).apply {
                        instanceFollowRedirects=true;connectTimeout=7000;readTimeout=7000
                    }
                    val resolved=try {conn.inputStream.use { };conn.url.toString()} finally {conn.disconnect()}
                    runOnUiThread {
                        if(resolved!=shortLink)resolveMapLocation(resolved)
                        else status.text="MAP / SHORT LINK NOT RESOLVED"
                    }
                }.onFailure {runOnUiThread {status.text="MAP / LINK UNAVAILABLE"}}
            }.start()
            return
        }
        val url=Regex("""https?://[^\s]+""").find(raw)?.value
        val decoded=runCatching {java.net.URLDecoder.decode(raw,"UTF-8")}.getOrDefault(raw)
        val mapCoords=listOf(
            Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)"""),
            Regex("""[?&](?:q|query|ll|center)=(-?\d+\.\d+),(-?\d+\.\d+)"""),
            Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""")
        ).firstNotNullOfOrNull {it.find(decoded)}
        if(mapCoords!=null) {
            val latitude=mapCoords.groupValues[1].toDoubleOrNull()
            val longitude=mapCoords.groupValues[2].toDoubleOrNull()
            if(latitude!=null&&longitude!=null&&latitude in -90.0..90.0&&longitude in -180.0..180.0) {
                showFoundLocation("Google Maps target",latitude,longitude);return
            }
        }
        if(url!=null && ("google.com/maps" in url || "maps.google." in url)) {
            val text=runCatching {java.net.URI(url)}.getOrNull()
            val path=text?.path.orEmpty()
            val name=path.substringAfter("/place/","").substringBefore("/").replace("+"," ")
            if(name.isNotEmpty()) {resolveAddress(java.net.URLDecoder.decode(name,"UTF-8"));return}
        }
        val embedded=Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""").find(raw)
        if(embedded!=null) {
            val lat=embedded.groupValues[1].toDoubleOrNull()
            val lon=embedded.groupValues[2].toDoubleOrNull()
            if(lat!=null && lon!=null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                showFoundLocation("Google Maps location",lat,lon);return
            }
        }
        val direct=Regex("""(-?\d{1,2}\.\d+)\s*[, ]\s*(-?\d{1,3}\.\d+)""").find(raw)
        val pair=direct?.let {
            val lat=it.groupValues[1].toDoubleOrNull();val lon=it.groupValues[2].toDoubleOrNull()
            if(lat!=null&&lon!=null&&lat in -90.0..90.0&&lon in -180.0..180.0) Pair(lat,lon) else null
        }
        if(pair!=null){showFoundLocation(raw.take(60),pair.first,pair.second);return}
        val query=raw.substringAfter("query=",raw).substringBefore("&").replace("+"," ")
        resolveAddress(query)
    }
    private fun resolveAddress(query:String) {
        status.text="MAP / LOCATING..."
        Thread {
            try {
                val geo=android.location.Geocoder(this,java.util.Locale.ENGLISH)
                @Suppress("DEPRECATION")
                val found=geo.getFromLocationName(query,5) ?: emptyList()
                runOnUiThread {
                    if(found.isEmpty())status.text="MAP / NOT FOUND: TRY ADDRESS OR COORDINATES"
                    else if(found.size==1)showFoundLocation(found[0].getAddressLine(0) ?: query,found[0].latitude,found[0].longitude)
                    else android.app.AlertDialog.Builder(this).setTitle("CHOOSE LOCATION")
                        .setItems(found.map{it.getAddressLine(0) ?: query}.toTypedArray()) {_,i->
                            showFoundLocation(found[i].getAddressLine(0) ?: query,found[i].latitude,found[i].longitude)
                        }.show()
                }
            }catch(e:Exception){runOnUiThread {status.text="MAP SEARCH UNAVAILABLE / TRY COORDINATES"}}
        }.start()
    }
    private fun showFoundLocation(name:String,lat:Double,lon:Double) {
        previewLocation=GeoPoint(lat,lon);previewLabel=name
        followUser=false;followButton.text="FOCUS ○"
        map.controller.setZoom(18.0)
        map.controller.animateTo(previewLocation)
        previewBar?.visibility=View.VISIBLE
        status.text="TARGET PREVIEW / "+name.take(35)
        refresh()
    }
    private fun showObjectives() {
        objectivesPopup?.dismiss()
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(12),dp(12),dp(12))
            background=GradientDrawable().apply {
                cornerRadius=dp(16).toFloat()
                setColor(Color.argb(126,31,44,36))
            }
        }
        panel.addView(label("◎ RECON OBJECTIVES",14,sand))
        val search=EditText(this).apply {
            hint="⌕ Search objectives";setSingleLine(true)
            setTextColor(sand);setHintTextColor(sage);textSize=13f
        }
        panel.addView(search)
        val list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val scroll=android.widget.ScrollView(this).apply {addView(list)}
        panel.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        fun populate(query:String) {
            list.removeAllViews()
            ReconObjectives.all.filterNot {objectiveStore.isHidden(it.id)}
                .filter {it.name.contains(query,true)||it.category.contains(query,true)}
                .sortedWith(compareBy<ReconObjective> {if(objectiveStore.completed(it.id))2 else if(objectiveStore.active==it.id)0 else 1}.thenBy{it.name})
                .forEach {o ->
                    val state=if(objectiveStore.completed(o.id))"✓ DONE" else if(objectiveStore.active==o.id)"● ACTIVE" else "◇ NEW"
                    list.addView(action(state+"  "+o.name+" / "+o.category).apply {
                        gravity=Gravity.CENTER_VERTICAL or Gravity.START
                        setPadding(dp(8),0,dp(5),0)
                        setOnClickListener { objectivesPopup?.dismiss();focusObjective(o) }
                        setOnLongClickListener { objectivesPopup?.dismiss();focusObjective(o,true);true }
                    },LinearLayout.LayoutParams(-1,dp(46)))
                }
        }
        search.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {populate(s.toString())}
            override fun afterTextChanged(s:android.text.Editable?) {}
        })
        populate("")
        objectivesPopup=PopupWindow(panel,dp(310),dp(480),true).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(8).toFloat()
            showAtLocation(map,Gravity.TOP or Gravity.START,dp(8),dp(84))
        }
    }
    private fun showObjective(o:ReconObjective) {
        val panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(10),dp(14),dp(10));background=tacticalPanel()}
        panel.addView(label("OBJECTIVE / "+o.category,12,sage))
        panel.addView(label(o.name,17,sand))
        panel.addView(label(o.brief,13,sand))
        panel.addView(label("SOURCE / "+o.source,10,sage))
        val dialog=android.app.AlertDialog.Builder(this).setView(panel).setNegativeButton("CLOSE",null).create()
        panel.addView(action("⌖ SET ACTIVE").apply {setOnClickListener {
            objectiveStore.active=o.id;map.controller.animateTo(GeoPoint(o.latitude,o.longitude));refresh();dialog.dismiss()
        }})
        panel.addView(action("✓ COMPLETE / SAVE FIELD MARKER").apply {setOnClickListener {
            objectiveStore.complete(o.id)
            if(notes.none {it.title==o.name}) {
                notes.add(FieldNote(o.latitude,o.longitude,o.name,"CHECKPOINT",o.brief,System.currentTimeMillis(),"⚑"))
                fieldNotes.save(notes)
            }
            refresh();dialog.dismiss()
        }})
        panel.addView(action("HIDE OBJECTIVE").apply {setOnClickListener {objectiveStore.hide(o.id);refresh();dialog.dismiss()}})
        if(o.source.startsWith("http"))panel.addView(action("OPEN SOURCE").apply {setOnClickListener {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(o.source)))}})
        dialog.show()
    }
    private fun showExisting(note:FieldNote) {
        val current=notes.find { it.time==note.time } ?: return
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(14),dp(16),dp(14))
            background=GradientDrawable().apply {
                cornerRadius=dp(18).toFloat()
                setColor(Color.argb(126,32,45,37))
                setStroke(dp(1),Color.rgb(115,137,103))
            }
        }
        panel.addView(label("FIELD INVENTORY / EDIT",12,sand))
        panel.addView(label("VISITS / "+current.visits.size,11,sage))
        panel.addView(action("⌖ NAVIGATE").apply {setOnClickListener {navigationTarget=current.time;updateNavigation()}})
        val name=EditText(this).apply {
            setText(current.title);textSize=15f;setSingleLine(true)
            setTextColor(sand);setHintTextColor(sage);hint="Marker title"
        }
        panel.addView(name)
        val memo=EditText(this).apply {
            setText(if(current.kind=="PHOTO"||current.kind=="VOICE") "" else current.body)
            minLines=2;maxLines=4;setTextColor(sand);setHintTextColor(sage)
            hint="Visit notes..."
        }
        if(current.kind!="PHOTO"&&current.kind!="VOICE")panel.addView(memo)
        var selected=current.icon
        val choices=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        listOf("◆","⚑","★","✎","▲","●","⌖","▣").forEach {glyph->
            choices.addView(action(glyph).apply {
                textSize=17f
                setOnClickListener {selected=glyph;this@MainActivity.status.text="ICON "+glyph}
            },LinearLayout.LayoutParams(0,dp(40),1f))
        }
        panel.addView(choices)
        val count=label("ATTACHMENTS / "+current.attachments.size,12,sage)
        panel.addView(count)
        val photoStrip=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        current.attachments.filterNot {it.endsWith(".m4a")}.forEach { filename ->
            val thumb=android.widget.ImageView(this).apply {
                scaleType=android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(loadOrientedPhoto(File(filesDir,filename)))
                setOnClickListener {
                    val bitmap=loadOrientedPhoto(File(filesDir,filename))
                    if(bitmap!=null)android.app.AlertDialog.Builder(this@MainActivity)
                        .setView(android.widget.ImageView(this@MainActivity).apply {setImageBitmap(bitmap);adjustViewBounds=true})
                        .setNegativeButton("CLOSE",null)
                        .setNeutralButton("REMOVE") {_,_->
                            val idx=notes.indexOfFirst {it.time==current.time}
                            if(idx>=0){
                                notes[idx]=notes[idx].copy(attachments=notes[idx].attachments.filterNot {it==filename})
                                fieldNotes.save(notes);refresh()
                            }
                            File(filesDir,filename).delete()
                        }.show()
                }
            }
            photoStrip.addView(thumb,LinearLayout.LayoutParams(dp(90),dp(88)))
        }
        val scroller=android.widget.HorizontalScrollView(this)
        scroller.addView(photoStrip);panel.addView(scroller)
        current.attachments.filter {it.endsWith(".m4a")}.forEachIndexed {i,file ->
            panel.addView(action("▶ VOICE MEMO "+(i+1)).apply {
                setOnClickListener {
                    runCatching {
                        MediaPlayer().apply {
                            setDataSource(File(filesDir,file).absolutePath)
                            setOnCompletionListener {it.release()}
                            prepare();start()
                        }
                    }.onFailure {status.text="VOICE PLAYBACK ERROR"}
                }
            })
        }
        val actions=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        fun updateNote():FieldNote {
            val edited=current.copy(title=name.text.toString().ifBlank{"Untitled"},
                body=if(current.kind=="PHOTO"||current.kind=="VOICE")current.body else memo.text.toString(),
                icon=selected)
            val index=notes.indexOfFirst {it.time==current.time}
            if(index>=0){notes[index]=edited;fieldNotes.save(notes);refresh()}
            return edited
        }
        val dialog=android.app.AlertDialog.Builder(this).setView(panel)
            .setNegativeButton("CLOSE",null)
            .setPositiveButton("SAVE") {_,_->updateNote()}
            .setNeutralButton("DELETE") {_,_->notes.removeAll {it.time==current.time};fieldNotes.save(notes);refresh()}
            .create()
        actions.addView(action("＋ ATTACH").apply {
            setOnClickListener {
                updateNote();attachmentNoteTime=current.time
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE);type="image/*"
                },83)
                dialog.dismiss()
            }
        },LinearLayout.LayoutParams(0,dp(44),1f))
        actions.addView(action("▣ QUICK PHOTO").apply {
            setOnClickListener {
                updateNote();attachmentNoteTime=current.time
                val values=android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,"terra_"+System.currentTimeMillis()+".jpg")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                }
                cameraUri=contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)
                val target=cameraUri
                if(target!=null) {
                    startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(android.provider.MediaStore.EXTRA_OUTPUT,target)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },84)
                }else status.text="CAMERA STORAGE UNAVAILABLE"
                dialog.dismiss()
            }
        },LinearLayout.LayoutParams(0,dp(44),1f))
        panel.addView(actions)
        panel.addView(action("● ADD VOICE MEMO").apply {
            setOnClickListener {
                updateNote();voiceNoteTime=current.time
                startVoice(GeoPoint(current.latitude,current.longitude))
                dialog.dismiss()
            }
        })
        panel.addView(action("＋ QUICK BRIEF").apply {
            setOnClickListener {
                updateNote()
                val entry=EditText(this@MainActivity).apply {hint="Visit observations";minLines=2}
                android.app.AlertDialog.Builder(this@MainActivity).setTitle("QUICK BRIEF")
                    .setView(entry).setNegativeButton("CANCEL",null)
                    .setPositiveButton("ADD") {_,_->
                        val idx=notes.indexOfFirst {it.time==current.time}
                        if(idx>=0 && entry.text.isNotBlank()) {
                            val old=notes[idx]
                            val date=java.text.DateFormat.getDateTimeInstance().format(java.util.Date())
                            notes[idx]=old.copy(body=old.body+"\n["+date+"] "+entry.text.toString(),
                                visits=old.visits+System.currentTimeMillis())
                            fieldNotes.save(notes);refresh()
                        }
                    }.show()
                dialog.dismiss()
            }
        })
        if(current.kind=="VOICE") panel.addView(action("▶ PLAY AUDIO").apply {
            setOnClickListener {runCatching {
                MediaPlayer().apply {
                    setDataSource(File(filesDir,current.body).absolutePath)
                    setOnCompletionListener {it.release()};prepare();start()
                }
            }}
        })
        dialog.show()
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
        if(save && file!=null && file.exists() && file.length()>0 && voiceNoteTime!=null) {
            val idx=notes.indexOfFirst {it.time==voiceNoteTime}
            if(idx>=0) {
                notes[idx]=notes[idx].copy(attachments=notes[idx].attachments+file.name)
                fieldNotes.save(notes);refresh()
            }
        } else if(save && file!=null && file.exists() && file.length()>0 && point!=null) {
            notes.add(FieldNote(point.latitude,point.longitude,"Voice memo","VOICE",file.name,System.currentTimeMillis()))
            fieldNotes.save(notes);refresh()
        }else file?.delete()
        audioFile=null;voicePoint=null;voiceNoteTime=null
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
    private fun showExplorationStats() {
        val stats=ExplorationStats.calculate(records,notes,historyCutoff ?: Long.MAX_VALUE)
        val panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(14),dp(18),dp(14));background=tacticalPanel()}
        panel.addView(label("◈ EXPLORATION / STATISTICS",14,sand))
        listOf(
            "DISTANCE  /  %.2f km".format(stats.distanceMeters/1000.0),
            "GPS SAMPLES  /  "+stats.gpsFixes,
            "ACTIVE DAYS  /  "+stats.sessions,
            "DISCOVERY CELLS  /  "+stats.exploredCells,
            "APPROX. COVERAGE  /  %.2f km²".format(stats.exploredCells*400/1000000.0),
            "VISITED MARKERS  /  "+stats.visitedMarkers,
            "PHOTOS  /  "+stats.photoCount
        ).forEach {panel.addView(label(it,12,sand))}
        panel.addView(label("Coverage is a 20m grid estimate, not surveyed area.",10,sage))
        val window=PopupWindow(panel,dp(300),dp(320),true).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(6).toFloat()
        }
        panel.addView(action("CLOSE").apply {setOnClickListener {window.dismiss()}})
        window.showAtLocation(map,Gravity.CENTER,0,0)
    }
    private fun showTimeMachine() {
        timeMachinePopup?.dismiss()
        val gps=records.filter {it.timestamp>0}.sortedBy {it.timestamp}
        if(gps.isEmpty()) {status.text="TIME MACHINE / NO RECORDED HISTORY";return}
        val earliest=gps.first().timestamp
        val latest=maxOf(gps.last().timestamp,System.currentTimeMillis())
        val panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(12),dp(14),dp(12));background=tacticalPanel()}
        panel.addView(label("◷ TIME MACHINE",14,sand))
        val date=label("",12,sage);panel.addView(date)
        val slider=SeekBar(this).apply {max=1000;progress=if(historyCutoff==null)1000 else (((historyCutoff!!-earliest).toDouble()/(latest-earliest).coerceAtLeast(1)*1000).toInt().coerceIn(0,1000))}
        fun showAt(progress:Int) {
            val time=earliest+((latest-earliest)*progress/1000L)
            historyCutoff=if(progress==1000)null else time
            date.text=if(historyCutoff==null)"LIVE / FULL MAP" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))
            refresh()
        }
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean) {if(fromUser)showAt(progress)}
            override fun onStartTrackingTouch(seekBar:SeekBar?) {}
            override fun onStopTrackingTouch(seekBar:SeekBar?) {}
        })
        panel.addView(slider)
        showAt(slider.progress)
        panel.addView(label("Slide to replay discoveries. GPS recording continues; no data is deleted.",10,sage))
        panel.addView(action("STATS AT SELECTED DATE").apply {setOnClickListener {showExplorationStats()}})
        val window=PopupWindow(panel,dp(325),dp(230),true).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(6).toFloat()
            setOnDismissListener {historyCutoff=null;refresh();timeMachinePopup=null}
        }
        panel.addView(action("RETURN TO LIVE").apply {setOnClickListener {window.dismiss()}})
        timeMachinePopup=window
        window.showAtLocation(map,Gravity.TOP or Gravity.CENTER_HORIZONTAL,0,dp(110))
    }
    private fun refreshMetro() {
        if(stationLoading)return
        stationLoading=true;status.text="TRANSIT / LOADING STATIONS"
        Thread {
            val stations=runCatching {TransitStations(this).refresh()}.getOrDefault(emptyList())
            runOnUiThread {
                stationLoading=false
                if(stations.isNotEmpty()) {metroStations.clear();metroStations.addAll(stations);refresh()}
                status.text=if(metroStations.isEmpty())"TRANSIT DATA UNAVAILABLE" else "TRANSIT / "+metroStations.size+" STATIONS"
            }
        }.start()
    }
    private fun showTransitStation(station:TransitStation) {
        val panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(12),dp(15),dp(12));background=tacticalPanel()}
        panel.addView(label("Ⓜ TRANSIT / METRO",13,sand))
        panel.addView(label(station.name,16,sand))
        fogOverlay.currentPosition?.let {origin->
            val d=FloatArray(1)
            android.location.Location.distanceBetween(origin.latitude,origin.longitude,station.lat,station.lon,d)
            panel.addView(label(if(d[0]>=1000)"%.1f km".format(d[0]/1000f) else "%.0f m".format(d[0]),12,sage))
        }
        panel.addView(label("NEXT 3 ARRIVALS / NOT AVAILABLE WITHOUT RTA LIVE FEED",11,sage))
        panel.addView(action("CHECK RTA S’HAIL").apply {setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.rta.ae/wps/portal/rta/ae/home/smart-apps/app-details/shail/shail-app?lang=en")))
        }})
        android.app.AlertDialog.Builder(this).setView(panel).setNegativeButton("CLOSE",null).show()
    }
    private fun anchor(name:String,save:Boolean) {
        val prefs=getSharedPreferences("terra_anchors_v1",Context.MODE_PRIVATE)
        if(save) {
            val point=map.mapCenter
            prefs.edit().putString(name+"_lat",point.latitude.toString()).putString(name+"_lon",point.longitude.toString()).apply()
            status.text=name+" / SAVED AT MAP CENTER"
        } else {
            val lat=prefs.getString(name+"_lat",null)?.toDoubleOrNull()
            val lon=prefs.getString(name+"_lon",null)?.toDoubleOrNull()
            if(lat!=null&&lon!=null) {followUser=false;map.controller.animateTo(GeoPoint(lat,lon));status.text=name+" / TARGET"}
            else status.text=name+" / NOT SET"
        }
    }
    private fun showLayers(anchor: View) {
        if (layersPopup?.isShowing == true) { layersPopup?.dismiss(); return }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background=tacticalPanel()
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        fun option(name: String, callback: () -> Unit) {
            panel.addView(action(name).apply { setOnClickListener { callback(); layersPopup?.dismiss() } },
                LinearLayout.LayoutParams(-1, dp(43)))
        }
        option("EXPLORATION STATISTICS") {showExplorationStats()}
        option("TIME MACHINE") {showTimeMachine()}
        option("TRANSIT / LOAD STATIONS") {refreshMetro()}
        option("HOME / GO") {anchor("HOME",false)}
        option("HOME / SAVE MAP CENTER") {anchor("HOME",true)}
        option("OFFICE / GO") {anchor("OFFICE",false)}
        option("OFFICE / SAVE MAP CENTER") {anchor("OFFICE",true)}
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
        option("EXPORT BACKUP ZIP") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type="application/zip"
                putExtra(Intent.EXTRA_TITLE,"TerraUnlocked-backup.zip")
            },85)
        }
        option("IMPORT BACKUP ZIP") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type="application/zip"
            },86)
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
        val viewScroll=android.widget.ScrollView(this).apply {addView(panel)}
        layersPopup = PopupWindow(viewScroll, dp(240), dp(495), true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
            showAtLocation(map, Gravity.BOTTOM or Gravity.END, dp(8), dp(60))
        }
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)

        if(requestCode==85 || requestCode==86) {
            val uri=data?.data
            if(resultCode==RESULT_OK && uri!=null) {
                runCatching {
                    if(requestCode==85) {
                        contentResolver.openOutputStream(uri)?.use {TerraArchive(this).exportTo(it)}
                            ?: error("Cannot write backup")
                        status.text="BACKUP EXPORTED"
                    } else {
                        contentResolver.openInputStream(uri)?.use {TerraArchive(this).importFrom(it)}
                            ?: error("Cannot read backup")
                        notes.clear();notes.addAll(fieldNotes.load())
                        records.clear();records.addAll(store.load())
                        segments.clear();segments.addAll(pathStore.load())
                        refresh();status.text="BACKUP IMPORTED"
                    }
                }.onFailure {status.text="BACKUP ERROR / "+it.javaClass.simpleName}
            }
            return
        }
        if(requestCode==83 || requestCode==84) {
            val id=attachmentNoteTime
            val uri=if(requestCode==84) cameraUri else data?.data
            if(resultCode==RESULT_OK && id!=null && uri!=null) {
                runCatching {
                    val file=File(filesDir,"marker_"+id+"_"+System.currentTimeMillis()+".jpg")
                    contentResolver.openInputStream(uri)?.use { input->
                        file.outputStream().use { output->input.copyTo(output) }
                    } ?: error("Cannot read photo")
                    val idx=notes.indexOfFirst {it.time==id}
                    if(idx>=0) {
                        notes[idx]=notes[idx].copy(attachments=notes[idx].attachments+file.name)
                        fieldNotes.save(notes);refresh()
                        status.text="PHOTO ATTACHED TO MARKER"
                    }
                }.onFailure {status.text="PHOTO SAVE FAILED"}
            }
            if(requestCode==84) {
                cameraUri?.let {contentResolver.delete(it,null,null)}
                cameraUri=null
            }
            attachmentNoteTime=null
            return
        }
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
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return
        startForegroundService(Intent(this,TrackingService::class.java).apply {action=TrackingService.ACTION_START})
        tracking=true
        trackButton.text="STOP GPS"
        status.text="RECON ACTIVE / BACKGROUND ENABLED"
    }
    private fun stopTracking() {
        startService(Intent(this,TrackingService::class.java).apply {action=TrackingService.ACTION_STOP})
        tracking=false
        trackButton.text="TRACK"
        status.text="TRACK STOPPED"
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
        tracking=TrackingService.running
        if(::trackButton.isInitialized)trackButton.text=if(tracking)"STOP GPS" else "TRACK"
        if(::store.isInitialized) {records.clear();records.addAll(store.load());notes.clear();notes.addAll(fieldNotes.load());refresh()}
        val filter=IntentFilter(TrackingService.ACTION_FIX)
        if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(trackingReceiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(trackingReceiver,filter)
        receiverRegistered=true
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_UI)
        }
    }
    override fun onPause() {
        super.onPause(); sensors.unregisterListener(this); map.onPause()
        if(receiverRegistered) {unregisterReceiver(trackingReceiver);receiverRegistered=false}
    }
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(simulationStep)
        layersPopup?.dismiss()
        poiPopup?.dismiss()
        radialMenu?.dismiss()
        objectivesPopup?.dismiss()
        paintPopup?.dismiss()
    }
}
