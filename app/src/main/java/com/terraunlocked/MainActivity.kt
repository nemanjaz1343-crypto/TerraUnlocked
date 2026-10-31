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
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

class MainActivity : Activity(), SensorEventListener {
    private val markerArt by lazy { ObjectiveMarkerArt(this) }
    private val olive = Color.rgb(31, 40, 32)
    private val sand = Color.rgb(218, 201, 156)
    private val sage = Color.rgb(155, 181, 134)
    private lateinit var map: MapView
    private lateinit var compassView: TextView
    private lateinit var weatherView: TextView
    private var weatherLastFetch=0L
    private var expeditionHeader:ExpeditionHeaderView?=null
    private var expeditionBottom:ExpeditionBottomView?=null
    private var expeditionPalette:ExpeditionMarkPanelView?=null
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
    private data class MarkState(val manual:List<DiscoveryPoint>,val segments:List<ManualSegment>)
    private val markUndo=java.util.ArrayDeque<MarkState>()
    private val markRedo=java.util.ArrayDeque<MarkState>()
    private val lasso=mutableListOf<GeoPoint>()
    private var lassoFinished=false
    private fun snapshotMark():MarkState=MarkState(records.filter{it.manual},segments.toList())
    private fun pushMark() {
        markUndo.addLast(snapshotMark())
        if(markUndo.size>20)markUndo.removeFirst()
        markRedo.clear()
        expeditionPalette?.let{it.canUndo=true;it.canRedo=false}
    }
    private fun applyMark(state:MarkState) {
        records.removeAll{it.manual};records.addAll(state.manual)
        segments.clear();segments.addAll(state.segments)
        store.replaceAll(records);pathStore.save(segments);refresh()
    }
    private fun markUndoAction() {
        if(markUndo.isEmpty())return
        markRedo.addLast(snapshotMark());applyMark(markUndo.removeLast())
        expeditionPalette?.let{it.canUndo=markUndo.isNotEmpty();it.canRedo=true}
    }
    private fun markRedoAction() {
        if(markRedo.isEmpty())return
        markUndo.addLast(snapshotMark());applyMark(markRedo.removeLast())
        expeditionPalette?.let{it.canUndo=true;it.canRedo=markRedo.isNotEmpty()}
    }
    private fun lassoContains(lat:Double,lon:Double):Boolean {
        var inside=false;var j=lasso.lastIndex
        for(i in lasso.indices) {
            val a=lasso[i];val b=lasso[j]
            if((a.latitude>lat)!=(b.latitude>lat) &&
               lon<(b.longitude-a.longitude)*(lat-a.latitude)/(b.latitude-a.latitude)+a.longitude)inside=!inside
            j=i
        }
        return inside
    }
    private fun commitLasso() {
        if(lasso.size<3){status.text="LASSO / MINIMUM 3 POINTS";return}
        pushMark()
        val minLat=lasso.minOf{it.latitude};val maxLat=lasso.maxOf{it.latitude}
        val minLon=lasso.minOf{it.longitude};val maxLon=lasso.maxOf{it.longitude}
        var added=0;var lat=minLat
        while(lat<=maxLat && added<1500) {
            var lon=minLon
            while(lon<=maxLon && added<1500) {
                if(lassoContains(lat,lon)){records.add(DiscoveryPoint(lat,lon,true,System.currentTimeMillis()));added++}
                lon+=0.00018
            }
            lat+=0.00018
        }
        store.replaceAll(records);lasso.clear();lassoFinished=false;refresh()
        status.text="LASSO / $added POINTS"
    }

    private var tracking = false
    private var receiverRegistered=false
    private var lastGpsUiRefresh=0L
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
            val revealed=intent.getBooleanExtra("revealed",false)
            if(revealed) {
                records.clear();records.addAll(store.load())
            }
            expeditionBottom?.gpsSearching=false
        status.text=(if(intent.getBooleanExtra("eco",false))"GPS ECO" else "GPS ACTIVE")+" / ±"+fogOverlay.positionAccuracy.toInt()+"m"
            val now=System.currentTimeMillis()
            if(now-lastGpsUiRefresh>=3500L) {
                lastGpsUiRefresh=now
                updateNavigation()
                refresh()
            }
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
    private var movingPinTime:Long?=null
    private var movingObjectiveId:String?=null
    private var movePinBar:LinearLayout?=null
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
    private var inferredPaths=emptyList<InferredPath>()
    private var recoveryRunning=false
    private var lastRecoveryScan=0L
    private var lastRecoveryPointCount=0
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
        window.statusBarColor=Color.rgb(14,20,16)
        window.navigationBarColor=Color.rgb(12,16,13)
        sensors=getSystemService(Context.SENSOR_SERVICE) as SensorManager
        compassEnabled=sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)!=null
        compassView=label("N ---°",10,sand)
        weatherView=label("--°",10,sage)
        expeditionHeader=ExpeditionHeaderView(this,
            onMissions={showObjectives()},
            onMap={chooseMapSource()},
            onUpdate={checkForUpdates()},
            onNorth={map.mapOrientation=0f}
        ).also {root.addView(it,LinearLayout.LayoutParams(-1,dp(45)))}
        // The status text remains available to services; no extra row obscures the map.
        status=label("GPS OFFLINE  |  FOG 82%  |  R 20m",10,sage).apply{
            setPadding(dp(12),dp(1),dp(8),dp(3))
            setBackgroundColor(Color.rgb(14,20,16))
        }
        root.addView(status,LinearLayout.LayoutParams(-1,dp(22)))
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
                val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=Color.argb(160,212,196,134)
                    strokeWidth=dp(4).toFloat();style=Paint.Style.STROKE
                    strokeCap=Paint.Cap.ROUND
                    pathEffect=android.graphics.DashPathEffect(floatArrayOf(dp(7).toFloat(),dp(6).toFloat()),0f)
                }
                inferredPaths.filter {historyCutoff==null || it.endTime<=historyCutoff!!}.forEach {candidate->
                    val path=android.graphics.Path()
                    candidate.points.forEachIndexed {i,pos->
                        val p=mapView.projection.toPixels(GeoPoint(pos.first,pos.second),Point())
                        if(i==0)path.moveTo(p.x.toFloat(),p.y.toFloat()) else path.lineTo(p.x.toFloat(),p.y.toFloat())
                    }
                    canvas.drawPath(path,ink)
                }
            }
        })
        map.overlays.add(object:Overlay() {
            override fun onSingleTapConfirmed(e:MotionEvent,mapView:MapView):Boolean {
                if(mode!="VIEW")return false
                val pos=fogOverlay.currentPosition ?: return false
                val pixel=mapView.projection.toPixels(pos,Point())
                val distance=kotlin.math.hypot((e.x-pixel.x).toDouble(),(e.y-pixel.y).toDouble())
                if(distance>dp(25))return false
                setMyLocation();return true
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
        map.overlays.add(object:Overlay() {
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(shadow || lasso.isEmpty())return
                val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=sand;strokeWidth=dp(2).toFloat();style=Paint.Style.STROKE
                }
                val line=android.graphics.Path()
                lasso.forEachIndexed{i,p->
                    val pixel=mapView.projection.toPixels(p,Point())
                    if(i==0)line.moveTo(pixel.x.toFloat(),pixel.y.toFloat())
                    else line.lineTo(pixel.x.toFloat(),pixel.y.toFloat())
                    if(i==0 || i==lasso.lastIndex)canvas.drawCircle(pixel.x.toFloat(),pixel.y.toFloat(),dp(2).toFloat(),ink)
                }
                if(lassoFinished && lasso.size>=3)line.close()
                canvas.drawPath(line,ink)
            }
        })
        map.overlays.add(object : Overlay() {
            private var strokeLast: GeoPoint? = null
            override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
                if(mode=="VIEW")return false
                if(mode=="LASSO") {
                    if(event.pointerCount>1)return true
                    val location=mapView.projection.fromPixels(event.x.toInt(),event.y.toInt())
                    val geo=GeoPoint(location.latitude,location.longitude)
                    when(event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            lassoFinished=false
                            lasso.clear()
                            confirmButton.visibility=View.GONE
                            lasso.add(geo)
                            status.text="LASSO / TRACE A FREEHAND AREA"
                            refresh()
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val last=lasso.lastOrNull()
                            if(last==null || gap(last,geo)>2.5f) {
                                if(lasso.size<1200)lasso.add(geo)
                                refresh()
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            if(lasso.size>=3) {
                                lasso.add(geo)
                                lassoFinished=true
                                confirmButton.text="CONFIRM LASSO"
                                confirmButton.visibility=View.VISIBLE
                                status.text="LASSO / REVIEW OUTLINE THEN CONFIRM"
                            } else {lasso.clear();lassoFinished=false;status.text="LASSO / DRAW A LARGER AREA"}
                            refresh()
                        }
                        MotionEvent.ACTION_CANCEL -> {lasso.clear();lassoFinished=false;confirmButton.visibility=View.GONE;refresh()}
                    }
                    return true
                }
                if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL) {
                    strokeLast=null
                    return true
                }
                if(event.actionMasked!=MotionEvent.ACTION_DOWN && event.actionMasked!=MotionEvent.ACTION_MOVE)return false
                val projected=mapView.projection.fromPixels(event.x.toInt(),event.y.toInt())
                val geo=GeoPoint(projected.latitude,projected.longitude)
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
                        if(strokeLast==null)pushMark()
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
                        // Eraser remains separate; MARK history covers path, paint and lasso.
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
                val painter=markerArt
                val user=fogOverlay.currentPosition
                val width=canvas.width.toFloat()
                val height=canvas.height.toFloat()
                val all=(ReconObjectives.all+objectiveStore.customPlaces()).filterNot {objectiveStore.isHidden(it.id)}
                val occupied=mutableListOf<Pair<Float,Float>>()
                all.forEach {o->
                    val p=mapView.projection.toPixels(GeoPoint(o.latitude,o.longitude),Point())
                    val x=p.x.toFloat();val y=p.y.toFloat()
                    val selected=objectiveStore.active==o.id || pendingObjective?.id==o.id
                    if(x<0f||y<0f||x>width||y>height) {
                        if(selected) {
                            val cx=width/2f;val cy=height/2f
                            val dx=x-cx;val dy=y-cy
                            val factor=minOf((width/2f-dp(25))/maxOf(kotlin.math.abs(dx),1f),(height/2f-dp(35))/maxOf(kotlin.math.abs(dy),1f))
                            painter.edge(canvas,cx+dx*factor,cy+dy*factor,kotlin.math.atan2(dy.toDouble(),dx.toDouble()),true)
                        }
                    } else {
                        val distance=user?.let {
                            val m=FloatArray(1)
                            android.location.Location.distanceBetween(it.latitude,it.longitude,o.latitude,o.longitude,m)
                            if(m[0]<1000f)"${m[0].toInt()} m" else "%.1f km".format(java.util.Locale.US,m[0]/1000f)
                        }
                        val overlap=occupied.any{kotlin.math.hypot((it.first-x).toDouble(),(it.second-y).toDouble())<dp(43)}
                        val showDistance=selected || (distance!=null && !overlap && mapView.zoomLevelDouble>=15.5)
                        painter.draw(canvas,x,y,o.category,selected,objectiveStore.completed(o.id),false,if(showDistance)distance else null)
                        if(showDistance)occupied.add(x to y)
                    }
                }
            }
            override fun onSingleTapConfirmed(event:MotionEvent,mapView:MapView):Boolean {
                if(!objectiveStore.enabled || mode!="VIEW")return false
                val hit=(ReconObjectives.all+objectiveStore.customPlaces()).filterNot {objectiveStore.isHidden(it.id)}.firstOrNull {o->
                    val p=mapView.projection.toPixels(GeoPoint(o.latitude,o.longitude),Point())
                    kotlin.math.hypot((p.x-event.x).toDouble(),(p.y-event.y).toDouble())<dp(22)
                } ?: return false
                showObjective(hit);return true
            }
            override fun onLongPress(event:MotionEvent,mapView:MapView):Boolean {
                if(mode!="VIEW")return false
                val hit=(ReconObjectives.all+objectiveStore.customPlaces()).filterNot {objectiveStore.isHidden(it.id)}.firstOrNull {o->
                    val p=mapView.projection.toPixels(GeoPoint(o.latitude,o.longitude),Point())
                    kotlin.math.hypot((p.x-event.x).toDouble(),(p.y-event.y).toDouble())<dp(24)
                } ?: return false
                showObjective(hit);return true
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
                val painter=markerArt
                metroStations.forEach {station->
                    val p=mapView.projection.toPixels(GeoPoint(station.lat,station.lon),Point())
                    if(p.x>=0 && p.y>=0 && p.x<canvas.width && p.y<canvas.height)
                        painter.draw(canvas,p.x.toFloat(),p.y.toFloat(),"TRANSIT",false,false,false)
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
                val painter=markerArt
                listOf("HOME","OFFICE").forEach {name->
                    val lat=prefs.getString(name+"_lat",null)?.toDoubleOrNull()
                    val lon=prefs.getString(name+"_lon",null)?.toDoubleOrNull()
                    if(lat!=null&&lon!=null) {
                        val p=mapView.projection.toPixels(GeoPoint(lat,lon),Point())
                        if(p.x>=0 && p.y>=0 && p.x<canvas.width && p.y<canvas.height)
                            painter.draw(canvas,p.x.toFloat(),p.y.toFloat(),name,false,false,false)
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
        val movingBar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER
            background=tacticalPanel();visibility=View.GONE
        }
        movePinBar=movingBar
        movingBar.addView(action("CANCEL MOVE").apply {setOnClickListener {movingPinTime=null;movingObjectiveId=null;movingBar.visibility=View.GONE}},LinearLayout.LayoutParams(0,dp(49),1f))
        movingBar.addView(action("CONFIRM NEW PIN POSITION").apply {setOnClickListener {
            val time=movingPinTime
            val index=notes.indexOfFirst {it.time==time}
            if(index>=0){
                val p=map.mapCenter
                notes[index]=notes[index].copy(latitude=p.latitude,longitude=p.longitude)
                fieldNotes.save(notes);status.text="PIN MOVED";refresh()
            }
            movingObjectiveId?.let {id->
                val p=map.mapCenter
                if(objectiveStore.moveCustom(id,p.latitude,p.longitude))status.text="OBJECTIVE MOVED"
                refresh()
            }
            movingObjectiveId=null;movingPinTime=null;movingBar.visibility=View.GONE
        }},LinearLayout.LayoutParams(0,dp(49),2f))
        // Center reticle is only visible while relocating a pin, not when reading a place.
        val moveReticle=object:View(this) {
            private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.rgb(246,211,132);strokeWidth=dp(2).toFloat();style=Paint.Style.STROKE
            }
            override fun onDraw(c:Canvas) {
                super.onDraw(c)
                if(movingPinTime==null && movingObjectiveId==null)return
                val x=width/2f;val y=height/2f
                c.drawCircle(x,y,dp(13).toFloat(),ink)
                c.drawLine(x-dp(23),y,x-dp(16),y,ink)
                c.drawLine(x+dp(16),y,x+dp(23),y,ink)
                c.drawLine(x,y-dp(23),x,y-dp(16),ink)
                c.drawLine(x,y+dp(16),x,y+dp(23),ink)
            }
        }
        mapArea.addView(moveReticle,FrameLayout.LayoutParams(-1,-1))
        moveReticle.isClickable=false
        mapArea.addView(movingBar,FrameLayout.LayoutParams(-1,dp(54),Gravity.BOTTOM))
        confirmButton.setOnClickListener {
            if(mode=="LASSO"){commitLasso();confirmButton.visibility=View.GONE;return@setOnClickListener}
            pushMark()
            pending.zipWithNext().forEach { (a,b) ->
                segments.add(ManualSegment(a.latitude,a.longitude,b.latitude,b.longitude))
            }
            pathStore.save(segments)
            pending.clear()
            confirmButton.visibility=View.GONE
            status.text="PATH CONFIRMED"
            refresh()
        }
        // The journal tab and its panel form ONE sliding, book-like drawer.
        val journalWidth=dp(230)
        val journalTabWidth=dp(31)
        val journalDrawer=FrameLayout(this)
        var journalTabRef:ExpeditionJournalTab?=null
        val drawerPanel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(13),dp(12),dp(13),dp(12))
            background=GradientDrawable().apply {
                cornerRadius=dp(10).toFloat()
                setColor(Color.rgb(22,27,20))
                setStroke(dp(1),Color.rgb(205,170,101))
            }
        }
        drawerPanel.addView(label("EXPEDITION JOURNAL",16,sand).apply {
            setTypeface(Typeface.SERIF,Typeface.BOLD)
        })
        fun slideJournal(open:Boolean) {
            journalTabRef?.open=open
            journalDrawer.animate().cancel()
            journalDrawer.animate()
                .translationX(if(open)0f else journalWidth.toFloat())
                .setDuration(360L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                .start()
        }
        drawerPanel.addView(action("▤  FIELD JOURNAL").apply {
            applyExpeditionStyle(this)
            setOnClickListener{slideJournal(false);showExpeditionJournal()}
        },LinearLayout.LayoutParams(-1,dp(46)))
        drawerPanel.addView(action("◈  FIELD STATISTICS").apply {
            applyExpeditionStyle(this)
            setOnClickListener{slideJournal(false);showExplorationStats()}
        },LinearLayout.LayoutParams(-1,dp(46)))
        drawerPanel.addView(action("× CLOSE").apply {
            applyExpeditionStyle(this)
            setOnClickListener{slideJournal(false)}
        },LinearLayout.LayoutParams(-1,dp(42)))
        journalDrawer.addView(drawerPanel,FrameLayout.LayoutParams(journalWidth,-2,Gravity.END or Gravity.CENTER_VERTICAL))
        val journalTab=ExpeditionJournalTab(this) {
            val opening=journalDrawer.translationX>journalWidth/2f
            slideJournal(opening)
        }
        journalTabRef=journalTab
        journalDrawer.addView(journalTab,FrameLayout.LayoutParams(journalTabWidth,dp(90),Gravity.START or Gravity.CENTER_VERTICAL))
        journalDrawer.translationX=journalWidth.toFloat()
        mapArea.addView(journalDrawer,FrameLayout.LayoutParams(
            journalWidth+journalTabWidth,dp(213),Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(mapArea,LinearLayout.LayoutParams(-1,0,1f))
        // Hidden adapter buttons preserve existing activity actions; visual chrome is Canvas based.
        trackButton=action("TRACK")
        followButton=action("FOLLOW")
        val markButton=action("MARK")
        val eraseButton=action("ERASE")
        val viewButton=action("VIEW")
        lateinit var palette:ExpeditionMarkPanelView
        val controls=ExpeditionBottomView(this){index->
            when(index) {
                0->trackButton.performClick()
                1->followButton.performClick()
                2->markButton.performClick()
                3->eraseButton.performClick()
                4->viewButton.performClick()
            }
        }
        expeditionBottom=controls
        controls.selected=-1
        controls.trackingActive=tracking
        controls.gpsSearching=tracking && fogOverlay.currentPosition==null
        fun chooseMark(next:String) {
            mode=next
            pending.clear();lasso.clear();lassoFinished=false
            confirmButton.visibility=View.GONE
            palette.selected=when(next){"PATH"->2;"PAINT"->3;"LASSO"->4;else->-1}
            confirmButton.text=if(next=="LASSO")"CONFIRM LASSO" else "CONFIRM PATH"
            status.text=when(next){
                "PATH"->"MARK / TAP VERTICES THEN CONFIRM"
                "PAINT"->"MARK / PAINT TO REVEAL"
                "LASSO"->"MARK / DRAW FREEHAND THEN CONFIRM"
                else->"MARK / SELECT A TOOL"
            }
            refresh()
        }
        palette=ExpeditionMarkPanelView(this){index->
            when(index){
                0->markUndoAction()
                1->markRedoAction()
                2->chooseMark("PATH")
                3->chooseMark("PAINT")
                4->chooseMark("LASSO")
            }
            palette.canUndo=markUndo.isNotEmpty()
            palette.canRedo=markRedo.isNotEmpty()
        }
        expeditionPalette=palette
        palette.visibility=View.GONE
        val paletteParams=FrameLayout.LayoutParams(dp(242),dp(70),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        paletteParams.bottomMargin=dp(12)
        mapArea.addView(palette,paletteParams)
        root.addView(controls,LinearLayout.LayoutParams(-1,dp(49)))
        setContentView(root)
        if(metroStations.isEmpty())handler.post{refreshMetro()}
        handler.postDelayed({maybeRecoverPaths()},12000L)
        trackButton.setOnClickListener {
            if(tracking)stopTracking() else {
                if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
                    startTracking()
                else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),10)
            }
            controls.trackingActive=tracking
            controls.gpsSearching=tracking && fogOverlay.currentPosition==null
        }
        followButton.setOnClickListener {
            followUser=true
            fogOverlay.currentPosition?.let{
                val next=(map.zoomLevelDouble+0.5849625).coerceAtMost(map.maxZoomLevel.toDouble())
                map.controller.setZoom(next)
                map.controller.animateTo(it)
                status.text="FOLLOW / ZOOM 150%"
            }
                ?:run{status.text="FOLLOW / WAITING FOR GPS"}
        }
        markButton.setOnClickListener {
            val opening=palette.visibility!=View.VISIBLE
            palette.visibility=if(opening)View.VISIBLE else View.GONE
            if(opening){
                mode="VIEW"
                palette.selected=-1
                palette.canUndo=markUndo.isNotEmpty()
                palette.canRedo=markRedo.isNotEmpty()
                status.text="MARK / SELECT TOOL"
            }else{
                mode="VIEW";pending.clear();lasso.clear();confirmButton.visibility=View.GONE
            }
            controls.selected=if(opening)2 else -1
            refresh()
        }
        eraseButton.setOnClickListener {
            if(mode!="ERASE"){
                mode="ERASE";palette.visibility=View.GONE
                controls.selected=3;status.text="ERASE / REMOVE MANUAL PAINT"
            }else{
                mode="VIEW";controls.selected=-1;status.text="MAP READY"
            }
        }
        viewButton.setOnClickListener {showLayers(viewButton)}
        map.setUseDataConnection(true)
        loadDubaiTemperature()
        applyMapSource(getSharedPreferences("terra_map_source",Context.MODE_PRIVATE).getInt("selected",0))
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
                4 -> {measure=!measure;measurePoints.clear();status.text=if(measure)"RULER / TAP TWO POINTS" else "RULER OFF";refresh()}
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
        // The MISSIONS list only flies to the target. Its icon opens the detail actions.
        clearMapPreview()
        followUser=false
        if(extent && map.zoomLevelDouble<17.0)map.controller.setZoom(17.0)
        map.controller.animateTo(GeoPoint(o.latitude,o.longitude))
        status.text="MISSION / "+o.name.take(36)+" / TAP MAP ICON FOR DETAILS"
        refresh()
    }
    private fun showObjectiveHint(o:ReconObjective) { showObjective(o) }
    private fun clearMapPreview() {
        previewLocation=null;previewLabel="";pendingObjective=null
        previewBar?.visibility=View.GONE
        refresh()
    }
    private fun tacticalPanel():GradientDrawable = GradientDrawable().apply {
        cornerRadius=dp(16).toFloat()
        setColor(Color.argb(238,38,47,38))
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
        fogOverlay.currentPosition?.let {position->
            val out=FloatArray(1)
            android.location.Location.distanceBetween(position.latitude,position.longitude,o.latitude,o.longitude,out)
            panel.addView(label("DISTANCE / "+(if(out[0]>=1000f)"%.1f km".format(java.util.Locale.US,out[0]/1000f) else "${out[0].toInt()} m"),12,sage))
        }
        val dialog=android.app.AlertDialog.Builder(this).setView(panel).setNegativeButton("CLOSE",null).create()
        panel.addView(action("⌖ SET ACTIVE").apply {setOnClickListener {
            objectiveStore.active=o.id;clearMapPreview();refresh();dialog.dismiss()
        }})
        panel.addView(action("✓ COMPLETE / SAVE FIELD MARKER").apply {setOnClickListener {
            objectiveStore.complete(o.id)
            if(notes.none {it.title==o.name}) {
                notes.add(FieldNote(o.latitude,o.longitude,o.name,"CHECKPOINT",o.brief,System.currentTimeMillis(),"⚑"))
                fieldNotes.save(notes)
            }
            refresh();dialog.dismiss()
        }})
        if(o.id.startsWith("saved_"))panel.addView(action("↔ MOVE OBJECTIVE").apply {setOnClickListener {
            dialog.dismiss();movingObjectiveId=o.id;movingPinTime=null
            followUser=false;map.controller.animateTo(GeoPoint(o.latitude,o.longitude))
            movePinBar?.visibility=View.VISIBLE;status.text="MOVE OBJECTIVE / PAN MAP, CONFIRM"
        }})
        panel.addView(action("HIDE OBJECTIVE").apply {setOnClickListener {objectiveStore.hide(o.id);refresh();dialog.dismiss()}})
        if(o.source.startsWith("http"))panel.addView(action("OPEN SOURCE").apply {setOnClickListener {startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(o.source)))}})
        dialog.show()
    }
    private fun showExpeditionJournal() {
        notes.clear();notes.addAll(fieldNotes.load())
        val panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=tacticalPanel()}
        panel.addView(label("EXPEDITION / FIELD JOURNAL",16,sand))
        panel.addView(label("${notes.size} PLACES / ${notes.count{it.visits.isNotEmpty()}} REVISITED",11,sage))
        val search=EditText(this).apply {hint="Search journal";setSingleLine(true);textSize=14f;setTextColor(sand);setHintTextColor(sage)}
        panel.addView(search)
        val list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        panel.addView(android.widget.ScrollView(this).apply {addView(list)},LinearLayout.LayoutParams(-1,dp(350)))
        val dialog=android.app.AlertDialog.Builder(this).setView(panel).setNegativeButton("CLOSE",null).create()
        fun populate(query:String) {
            list.removeAllViews()
            val entries=notes.filter{it.title.contains(query,true)||it.body.contains(query,true)}
                .sortedByDescending{maxOf(it.time,it.visits.lastOrNull()?:0L)}
            if(entries.isEmpty())list.addView(label("No records yet. Add a pin on the map.",12,sage))
            entries.forEach {entry->
                val date=java.text.SimpleDateFormat("dd MMM yyyy",java.util.Locale.getDefault()).format(java.util.Date(entry.time))
                val photos=entry.attachments.count{!it.endsWith(".m4a")}+(if(entry.kind=="PHOTO")1 else 0)
                val card=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8));background=tacticalPanel()}
                card.addView(label(entry.title,15,sand))
                card.addView(label("$date / ${entry.visits.size} VISITS / $photos PHOTOS",11,sage))
                if(entry.body.isNotBlank()&&entry.kind!="VOICE")card.addView(label(entry.body.take(100),12,sand))
                card.setOnClickListener {dialog.dismiss();showExisting(entry)}
                list.addView(card,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(7)})
            }
        }
        search.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){populate(s.toString())}
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        populate("")
        dialog.show()
    }
    private fun showExisting(note:FieldNote) {
        val current=notes.find { it.time==note.time } ?: return
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(10),dp(12),dp(10))
            background=GradientDrawable().apply {
                cornerRadius=dp(18).toFloat()
                setColor(Color.argb(126,32,45,37))
                setStroke(dp(1),Color.rgb(115,137,103))
            }
        }
        panel.addView(label("FIELD JOURNAL / PIN DETAILS",12,sand))
        panel.addView(label("RECORDED / "+java.text.SimpleDateFormat("dd MMM yyyy HH:mm",java.util.Locale.getDefault()).format(java.util.Date(current.time))+" / "+current.visits.size+" VISITS",11,sage))

        fogOverlay.currentPosition?.let {position->
            val out=FloatArray(1)
            android.location.Location.distanceBetween(position.latitude,position.longitude,current.latitude,current.longitude,out)
            panel.addView(label("DISTANCE / "+(if(out[0]>=1000f)"%.1f km".format(java.util.Locale.US,out[0]/1000f) else "${out[0].toInt()} m"),12,sage))
        }
        val moveButton=action("↔ MOVE PIN")
        panel.addView(moveButton)
        val name=EditText(this).apply {
            setText(current.title);textSize=15f;setSingleLine(true)
            setTextColor(sand);setHintTextColor(sage);hint="Marker title"
        }
        panel.addView(name)
        val memo=EditText(this).apply {
            setText(if(current.kind=="PHOTO"||current.kind=="VOICE") "" else current.body)
            minLines=3;maxLines=7;setTextColor(sand);setHintTextColor(sage)
            hint="Field notes / observations..."
        }
        if(current.kind!="PHOTO"&&current.kind!="VOICE")panel.addView(memo)
        var selected=current.icon
        val choices=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        listOf("◆","⚑","★","✎","▲","●","⌖","▣").forEach {glyph->
            choices.addView(action(glyph).apply {
                textSize=17f
                setOnClickListener {selected=glyph;this@MainActivity.status.text="ICON "+glyph}
            },LinearLayout.LayoutParams(0,dp(32),1f))
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
        val journalScroll=android.widget.ScrollView(this).apply {isFillViewport=false;addView(panel)}
        val dialog=android.app.AlertDialog.Builder(this).setView(journalScroll)
            .setNegativeButton("CLOSE",null)
            .setPositiveButton("SAVE") {_,_->updateNote()}
            .setNeutralButton("DELETE") {_,_->notes.removeAll {it.time==current.time};fieldNotes.save(notes);refresh()}
            .create()
        moveButton.setOnClickListener {dialog.dismiss();moveFieldPin(current)}
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
            "PHOTOS  /  "+stats.photoCount,
            "GPS GAPS TO REVIEW  /  "+ExplorationStats.gpsGaps(records),
            "DISTANCE THIS MONTH / %.2f km".format(ExplorationStats.distanceThisMonth(records)/1000.0),
            "DISTANCE TODAY / %.2f km".format(ExplorationStats.distanceToday(records)/1000.0)
        ).forEach {panel.addView(label(it,12,sand))}
        panel.addView(label("EXPEDITION MILESTONES",13,sand))
        val goals=listOf(
            Triple("FIRST 5 KM",stats.distanceMeters,5000.0),
            Triple("10 FIELD DAYS",stats.sessions.toDouble(),10.0),
            Triple("20 LOCATIONS",notes.size.toDouble(),20.0),
            Triple("100 DISCOVERY CELLS",stats.exploredCells.toDouble(),100.0)
        )
        goals.forEach { (name,progress,target)->
            val done=progress>=target
            val amount=if(target>=1000.0)"%.1f / %.0f".format(java.util.Locale.US,progress/1000.0,target/1000.0) else "${progress.toInt()} / ${target.toInt()}"
            panel.addView(label((if(done)"✓ " else "○ ")+name+"  "+amount,11,if(done)sage else sand))
        }
        panel.addView(label("Coverage is a 20m grid estimate, not surveyed area.",10,sage))
        val window=PopupWindow(android.widget.ScrollView(this).apply{addView(panel)},dp(310),dp(430),true).apply {
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
                status.text=if(stations.isEmpty())"TRANSIT / OFFLINE STATIONS ("+metroStations.size+")" else "TRANSIT / "+metroStations.size+" STATIONS"
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
    private fun setMyLocation() {
        val current=fogOverlay.currentPosition
        if(current==null){status.text="GPS / LOCATION NOT AVAILABLE";return}
        val choices=arrayOf("HOME","OFFICE","OTHER / CUSTOM PIN")
        android.app.AlertDialog.Builder(this).setTitle("SET LOCATION")
            .setItems(choices) {_,index->
                when(index) {
                    0,1 -> {
                        val key=choices[index]
                        val prefs=getSharedPreferences("terra_anchors_v1",Context.MODE_PRIVATE)
                        prefs.edit().putString(key+"_lat",current.latitude.toString())
                            .putString(key+"_lon",current.longitude.toString()).apply()
                        status.text=key+" / GPS POSITION SAVED";refresh()
                    }
                    else -> showNoteEditor(current,"POI")
                }
            }.setNegativeButton("CANCEL",null).show()
    }
    private val scooterMapSource by lazy {
        XYTileSource("CyclOSM", 0, 20, 256, ".png",
            arrayOf("https://a.tile-cyclosm.openstreetmap.fr/cyclosm/",
                    "https://b.tile-cyclosm.openstreetmap.fr/cyclosm/",
                    "https://c.tile-cyclosm.openstreetmap.fr/cyclosm/"),
            "© OpenStreetMap contributors · CyclOSM")
    }
    private fun loadDubaiTemperature() {
        if(System.currentTimeMillis()-weatherLastFetch<30L*60L*1000L)return
        weatherLastFetch=System.currentTimeMillis()
        Thread {
            val temp=runCatching {
                val conn=(java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=25.2048&longitude=55.2708&current=temperature_2m").openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout=8000;readTimeout=8000
                }
                try {
                    if(conn.responseCode!=200)throw java.io.IOException("Weather service unavailable")
                    org.json.JSONObject(conn.inputStream.bufferedReader().use{it.readText()}).getJSONObject("current").getDouble("temperature_2m")
                } finally {conn.disconnect()}
            }.getOrNull()
            if(temp!=null)runOnUiThread {
                if(::weatherView.isInitialized)weatherView.text="${temp.toInt()}°"
                expeditionHeader?.temperature=temp.toInt()
            }
        }.start()
    }
    private fun chooseMapSource() {
        val names=arrayOf("OpenStreetMap","OpenTopoMap","CyclOSM · BIKE / SCOOTER")
        val prefs=getSharedPreferences("terra_map_source",Context.MODE_PRIVATE)
        android.app.AlertDialog.Builder(this).setTitle("MAP SOURCE")
            .setSingleChoiceItems(names,prefs.getInt("selected",0)) {dialog,i->
                applyMapSource(i)
                prefs.edit().putInt("selected",i).apply()
                status.text="MAP SOURCE / "+names[i]
                dialog.dismiss()
            }.setNegativeButton("CANCEL",null).show()
    }
    private fun applyMapSource(index:Int) {
        map.setTileSource(when(index) {
            1 -> TileSourceFactory.OpenTopo
            2 -> scooterMapSource
            else -> TileSourceFactory.MAPNIK
        })
        map.invalidate()
    }
    private fun moveFieldPin(note:FieldNote) {
        movingObjectiveId=null
        movingPinTime=note.time
        followUser=false
        clearMapPreview()
        map.controller.animateTo(GeoPoint(note.latitude,note.longitude))
        movePinBar?.visibility=View.VISIBLE
        status.text="MOVE PIN / PAN MAP THEN CONFIRM"
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
    private fun checkForUpdates(silent:Boolean=false) {
        if(!silent)status.text="UPDATES / CHECKING..."
        Thread {
            val result=runCatching {
                val conn=(java.net.URL("https://api.github.com/repos/nemanjaz1343-crypto/TerraUnlocked/releases/latest").openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout=12000;readTimeout=12000
                    setRequestProperty("Accept","application/vnd.github+json")
                    setRequestProperty("User-Agent","TerraUnlocked-Android")
                }
                try {
                    if(conn.responseCode!=200)throw java.io.IOException("GitHub returned "+conn.responseCode)
                    org.json.JSONObject(conn.inputStream.bufferedReader().use{it.readText()})
                } finally {conn.disconnect()}
            }
            runOnUiThread {
                val latest=result.getOrNull()
                if(latest==null) {
                    if(silent)return@runOnUiThread
                    status.text="UPDATES / CHECK FAILED"
                    android.app.AlertDialog.Builder(this).setTitle("TERRA / UPDATES")
                        .setMessage("Could not check published releases. Check your connection and try again.")
                        .setPositiveButton("OK",null).show()
                    return@runOnUiThread
                }
                val tag=latest.optString("tag_name")
                val remote=tag.removePrefix("v").split(".").mapNotNull{it.toIntOrNull()}
                val local=(packageManager.getPackageInfo(packageName,0).versionName ?: "0").split(".").mapNotNull{it.toIntOrNull()}
                val cmp=(0..2).firstNotNullOfOrNull{i->
                    val difference=(remote.getOrElse(i){0}).compareTo(local.getOrElse(i){0})
                    if(difference==0)null else difference
                } ?: 0
                val assets=latest.optJSONArray("assets")
                var apkUrl=""
                if(assets!=null)for(i in 0 until assets.length()) {
                    val asset=assets.optJSONObject(i) ?: continue
                    if(asset.optString("name").endsWith(".apk")){apkUrl=asset.optString("browser_download_url");break}
                }
                if(cmp<=0) {
                    if(silent)return@runOnUiThread
                    status.text="UPDATES / UP TO DATE"
                    android.app.AlertDialog.Builder(this).setTitle("TERRA / UP TO DATE")
                        .setMessage("Installed: "+packageManager.getPackageInfo(packageName,0).versionName+"\nLatest: "+tag)
                        .setPositiveButton("OK",null).show()
                } else if(apkUrl.startsWith("https://")) {
                    status.text="UPDATES / "+tag+" AVAILABLE"
                    android.app.AlertDialog.Builder(this).setTitle("TERRA / UPDATE AVAILABLE")
                        .setMessage("Installed: "+packageManager.getPackageInfo(packageName,0).versionName+"\nNew version: "+tag+"\n\nDownload and install the update? Your map history will stay on this device.")
                        .setPositiveButton("UPDATE") {_,_->downloadUpdateApk(apkUrl)}
                        .setNegativeButton("LATER",null).show()
                } else {
                    if(silent)return@runOnUiThread
                    status.text="UPDATES / APK NOT FOUND"
                    android.app.AlertDialog.Builder(this).setTitle("TERRA / UPDATES")
                        .setMessage("Release "+tag+" has no downloadable APK yet.")
                        .setPositiveButton("OK",null).show()
                }
            }
        }.start()
    }

    private fun downloadUpdateApk(url:String) {
        status.text="UPDATES / DOWNLOADING..."
        Thread {
            val outcome=runCatching {
                val connection=(java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply{
                    connectTimeout=15000;readTimeout=30000
                    instanceFollowRedirects=true
                    setRequestProperty("User-Agent","TerraUnlocked-Android")
                }
                try {
                    if(connection.responseCode !in 200..299)throw java.io.IOException("Download error "+connection.responseCode)
                    val file=File(cacheDir,"terra-update.apk")
                    connection.inputStream.use{source->file.outputStream().use{sink->source.copyTo(sink)}}
                    if(file.length()<100_000)throw java.io.IOException("Invalid APK download")
                    val info=packageManager.getPackageArchiveInfo(file.absolutePath,0)
                    if(info?.packageName!=packageName)throw java.io.IOException("APK package does not match TerraUnlocked")
                    file
                } finally {connection.disconnect()}
            }
            runOnUiThread {
                val file=outcome.getOrNull()
                if(file==null) {
                    status.text="UPDATES / DOWNLOAD FAILED"
                    android.app.AlertDialog.Builder(this).setTitle("TERRA / UPDATE ERROR")
                        .setMessage(outcome.exceptionOrNull()?.message ?: "Download failed")
                        .setPositiveButton("OK",null).show()
                    return@runOnUiThread
                }
                status.text="UPDATES / READY TO INSTALL"
                try {
                    val uri=androidx.core.content.FileProvider.getUriForFile(
                        this, packageName + ".fileprovider", file
                    )
                    val install=Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri,"application/vnd.android.package-archive")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(install)
                } catch(e:Exception) {
                    status.text="UPDATES / INSTALL ERROR"
                    android.app.AlertDialog.Builder(this).setTitle("TERRA / INSTALL ERROR")
                        .setMessage("Android installer could not start: "+(e.message ?: "Unknown error"))
                        .setPositiveButton("OK",null).show()
                }
            }
        }.start()
    }
    private fun preloadOfflineArea() {
        if(offline){status.text="OFFLINE / SWITCH ONLINE TO CACHE TILES";return}
        val center=map.mapCenter
        val lat=Math.toRadians(center.latitude.coerceIn(-85.0,85.0))
        val lon=center.longitude
        val tiles=mutableListOf<Long>()
        for(z in 14..17){
            val scale=1 shl z
            val x=((lon+180.0)/360.0*scale).toInt().coerceIn(0,scale-1)
            val y=((1.0-kotlin.math.ln(kotlin.math.tan(lat)+1.0/kotlin.math.cos(lat))/Math.PI)/2.0*scale).toInt().coerceIn(0,scale-1)
            val radius=if(z>=16)4 else 2
            for(dx in -radius..radius)for(dy in -radius..radius){
                val xx=x+dx;val yy=y+dy
                if(xx in 0 until scale && yy in 0 until scale)
                    tiles.add(org.osmdroid.util.MapTileIndex.getTileIndex(z,xx,yy))
            }
        }
        status.text="OFFLINE CACHE / PREPARING ${tiles.size} MAP TILES"
        var index=0
        val task=object:Runnable {
            override fun run(){
                if(index>=tiles.size){status.text="OFFLINE CACHE / REQUESTED ${tiles.size} TILES";return}
                repeat(5) {if(index<tiles.size){map.tileProvider.getMapTile(tiles[index]);index++}}
                handler.postDelayed(this,250L)
            }
        }
        handler.post(task)
    }
    private fun showDataMenu() {
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=tacticalPanel()
        }
        panel.addView(label("▣ DATA / SOURCES & BACKUP",14,sand))
        fun option(name:String,fn:()->Unit) {panel.addView(action(name).apply {setOnClickListener {fn()}})}
        option("CHECK FOR UPDATES") {checkForUpdates()}
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

        val window=PopupWindow(panel,dp(285),dp(290),true).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        window.showAtLocation(map,Gravity.CENTER,0,0)
    }
    private fun maybeRecoverPaths() {
        val now=System.currentTimeMillis()
        if(recoveryRunning || records.size<3 || records.size==lastRecoveryPointCount)return
        if(now-lastRecoveryScan<180000L)return
        lastRecoveryScan=now
        lastRecoveryPointCount=records.size
        scanSmartPaths(silent=true)
    }
    private fun scanSmartPaths(silent:Boolean=false) {
        if(recoveryRunning)return
        recoveryRunning=true
        if(!silent)status.text="SMART PATH / SCANNING OSM..."
        val snapshot=records.toList()
        val center=map.mapCenter
        Thread {
            val outcome=runCatching {SmartPathRecovery.scan(snapshot,center.latitude,center.longitude)}
            runOnUiThread {
                recoveryRunning=false
                if(outcome.isFailure) {
                    if(!silent)status.text="SMART PATH / OSM UNAVAILABLE"
                } else {
                    inferredPaths=outcome.getOrDefault(emptyList())
                    if(!silent)status.text="SMART PATH / "+inferredPaths.size+" INFERRED GAPS"
                    refresh()
                }
            }
        }.start()
    }
    private fun applyExpeditionStyle(button:Button,selected:Boolean=false) {
        button.setTextColor(if(selected)Color.rgb(245,218,156) else sand)
        button.setTypeface(Typeface.SERIF,Typeface.BOLD)
        button.background=GradientDrawable().apply {
            cornerRadius=dp(6).toFloat()
            setColor(if(selected)Color.rgb(69,64,40) else Color.rgb(29,36,27))
            setStroke(dp(1),Color.rgb(151,125,76))
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
        option("RECOVER GPS GAPS / PREVIEW") {scanSmartPaths()}
        option("HIDE INFERRED PATHS") {inferredPaths=emptyList();refresh()}
        option("EXPLORATION STATISTICS") {showExplorationStats()}
        option("TIME MACHINE") {showTimeMachine()}
        option("TRANSIT / LOAD STATIONS") {refreshMetro()}
        option("FOG ${if (fogEnabled) "ON" else "OFF"}") {
            fogEnabled = !fogEnabled; fogOverlay.fogVisible = fogEnabled; refresh()
        }
        option("FOG OPACITY ${fogOverlay.opacity}") {
            fogOverlay.opacity = if (fogOverlay.opacity >= 235) 125 else fogOverlay.opacity + 35; refresh()
        }
        option("CHECKPOINTS ${if(fogOverlay.showCheckpoints) "ON" else "OFF"}") {
            fogOverlay.showCheckpoints=!fogOverlay.showCheckpoints;refresh()
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
        option("PRELOAD NEARBY MAP TILES") {preloadOfflineArea()}
        option("DATA / BACKUP & OFFLINE") {showDataMenu()}
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
        expeditionHeader?.bearing=degrees
        fogOverlay.northAzimuth = degrees.toFloat()
        map.invalidate()
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {}
    private fun startTracking() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return
        startForegroundService(Intent(this,TrackingService::class.java).apply {action=TrackingService.ACTION_START})
        tracking=true
        expeditionBottom?.trackingActive=true
        expeditionBottom?.gpsSearching=fogOverlay.currentPosition==null
        trackButton.text="STOP GPS"
        status.text="RECON ACTIVE / BACKGROUND ENABLED"
    }
    private fun stopTracking() {
        startService(Intent(this,TrackingService::class.java).apply {action=TrackingService.ACTION_STOP})
        tracking=false
        expeditionBottom?.trackingActive=false
        expeditionBottom?.gpsSearching=false
        trackButton.text="TRACK"
        status.text="TRACK STOPPED"
    }
    private fun stopSimulation() {
        simulating = false
        handler.removeCallbacks(simulationStep)
        demo.clear()
        simulationOrigin = null
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
        expeditionBottom?.trackingActive=tracking
        expeditionBottom?.gpsSearching=tracking && fogOverlay.currentPosition==null
        if(::trackButton.isInitialized)trackButton.text=if(tracking)"STOP GPS" else "TRACK"
        if(::store.isInitialized) {records.clear();records.addAll(store.load());notes.clear();notes.addAll(fieldNotes.load());refresh()}
        val filter=IntentFilter(TrackingService.ACTION_FIX)
        if(android.os.Build.VERSION.SDK_INT>=33) registerReceiver(trackingReceiver,filter,Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(trackingReceiver,filter)
        receiverRegistered=true
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors.registerListener(this,it,SensorManager.SENSOR_DELAY_UI)
        }
        val updatePrefs=getSharedPreferences("terra_updates",Context.MODE_PRIVATE)
        val now=System.currentTimeMillis()
        if(now-updatePrefs.getLong("last_check",0L)>24L*60L*60L*1000L) {
            updatePrefs.edit().putLong("last_check",now).apply()
            handler.postDelayed({if(!isFinishing)checkForUpdates(silent=true)},4000L)
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
