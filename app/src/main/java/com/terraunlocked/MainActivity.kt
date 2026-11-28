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
    private val olive:Int get()=ExpeditionThemes.current.screen
    private val sand:Int get()=if(ExpeditionThemes.current.id=="MILITARY")
        Color.rgb(218,201,156) else ExpeditionThemes.current.light
    private val sage:Int get()=ExpeditionThemes.current.secondary
    private lateinit var map: MapView
    private lateinit var compassView: TextView
    private lateinit var weatherView: TextView
    private var weatherLastFetch=0L
    private val weatherPrefs by lazy {getSharedPreferences("terra_weather_v1",Context.MODE_PRIVATE)}
    private var mapSearchGeneration=0
    private var mapSearchLastRequest=0L
    private var expeditionHeader:ExpeditionHeaderView?=null
    private var solarPopup:PopupWindow?=null
    private val solarTick=object:Runnable {
        override fun run() {
            if(::map.isInitialized && ::fogOverlay.isInitialized) {
                updateSolarDial()
                handler.postDelayed(this,60000L)
            }
        }
    }
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
    private var overlayFiltersPopup:PopupWindow?=null
    private val layerFilters=mutableMapOf<String,Boolean>()
    private val objectiveLayerCategories=setOf("NATURE","HERITAGE","ARCH","ART","SAVED")
    // Last rendered markers; touch targets must match the exact visible set.
    private var drawnObjectiveTargets:List<ReconObjective> = emptyList()

    // Dynamic worldwide places augment -- never replace -- Dubai and SAVED IDs.
    // Draw only reads this snapshot; networking/cache writes run on a worker.
    private val globalObjectiveStore by lazy{GlobalObjectiveStore(applicationContext)}
    private var regionalObjectives:List<ReconObjective> = emptyList()
    private val globalObjectiveWorker=java.util.concurrent.Executors.newSingleThreadExecutor{
        job->Thread(job,"terra-global-objectives").apply{isDaemon=true}
    }
    private var globalObjectiveLoading=false
    private var globalRequestAt=0L
    private var globalRetryAfter=0L
    private val globalRefreshTask=Runnable{refreshGlobalObjectiveRegion()}

    /** Only displayed goals should be used in draw; saved Journey metadata
     * must remain resolvable across regions, zoom bands and offline relaunch. */
    private fun allObjectives():List<ReconObjective> =
        (ReconObjectives.all + objectiveStore.customPlaces() +
            regionalObjectives + globalObjectiveStore.savedObjectives()
                .filter{it.id in journeyStore.ids()}).distinctBy{it.id}

    private fun knownObjectives():List<ReconObjective> =
        (allObjectives()+globalObjectiveStore.savedObjectives()+
            GlobalWorldLandmarks.all).distinctBy{it.id}

    private fun scheduleGlobalObjectiveRefresh(delayMs:Long=440L) {
        handler.removeCallbacks(globalRefreshTask)
        if(::map.isInitialized)handler.postDelayed(globalRefreshTask,delayMs)
    }

    private fun refreshGlobalObjectiveRegion() {
        if(!::map.isInitialized || !::objectiveStore.isInitialized)return
        val lat=map.mapCenter.latitude
        val lon=map.mapCenter.longitude
        val zoom=map.zoomLevelDouble
        val oldIds=regionalObjectives.map{it.id}
        val legacy=ReconObjectives.all+objectiveStore.customPlaces()
        regionalObjectives=GlobalObjectivePolicy.distinct(
            legacy,globalObjectiveStore.nearby(lat,lon,zoom)+
                globalObjectiveStore.savedForZoom(zoom))
        if(regionalObjectives.map{it.id}!=oldIds)map.invalidate()
        if(offline || globalObjectiveLoading ||
            System.currentTimeMillis()<globalRetryAfter)return
        // Visible geographic cells, not just the centre cell. Keep results
        // already on screen while the worker fills missing cells one by one.
        val w=map.width.coerceAtLeast(1)
        val h=map.height.coerceAtLeast(1)
        val corners=listOf(
            map.projection.fromPixels(0,0),
            map.projection.fromPixels(w,0),
            map.projection.fromPixels(0,h),
            map.projection.fromPixels(w,h))
        fun lonNearCamera(value:Double):Double {
            var x=value
            while(x-lon>180.0)x-=360.0
            while(x-lon< -180.0)x+=360.0
            return x
        }
        val candidates=GlobalObjectivePolicy.viewportCells(
            corners.minOf{it.latitude},corners.minOf{lonNearCamera(it.longitude)},
            corners.maxOf{it.latitude},corners.maxOf{lonNearCamera(it.longitude)},
            lat,lon,zoom)
        val now=System.currentTimeMillis()
        val target=candidates.firstOrNull{
            globalObjectiveStore.needsFetch(it.first,it.second,zoom,now)
        }?:return
        if(now-globalRequestAt<9000L) {
            scheduleGlobalObjectiveRefresh(9000L-(now-globalRequestAt))
            return
        }
        globalRequestAt=now
        globalObjectiveLoading=true
        val executor=globalObjectiveWorker
        executor.execute {
            val fetched=runCatching {
                val found=globalObjectiveStore.fetchCell(target.first,target.second,zoom)
                globalObjectiveStore.put(target.first,target.second,zoom,found)
            }
            handler.post {
                globalObjectiveLoading=false
                if(fetched.isFailure) {
                    globalRetryAfter=System.currentTimeMillis()+120000L
                    if(!tracking && !isFinishing)
                        status.text="WORLD OBJECTIVES / NETWORK UNAVAILABLE / CACHED MAP"
                } else globalRetryAfter=0L
                if(!isFinishing && !isDestroyed && fetched.isSuccess)
                    scheduleGlobalObjectiveRefresh(320L)
            }
        }
    }

    private fun layerVisible(category:String):Boolean=when(category) {
        "TAXI" -> taxiStandsEnabled
        "MALLS" -> layerFilters["MALLS"]!=false
        "JOURNAL" -> ::fogOverlay.isInitialized && fogOverlay.showCheckpoints
        else -> layerFilters[category]!=false
    }

    private fun objectiveVisible(o:ReconObjective):Boolean {
        if(objectiveStore.isHidden(o.id))return false
        val category=o.category.uppercase(java.util.Locale.US)
        return layerVisible(if(category in objectiveLayerCategories)category else "OTHER")
    }

    private fun setLayerVisible(category:String,show:Boolean) {
        when(category) {
            "TAXI" -> {
                taxiStandsEnabled=show
                getSharedPreferences("terra_taxi_settings",Context.MODE_PRIVATE)
                    .edit().putBoolean("enabled",show).apply()
                if(show)scheduleTaxiStandsRefresh()
            }
            "JOURNAL" -> fogOverlay.showCheckpoints=show
            "MALLS" -> {
                layerFilters["MALLS"]=show
                getSharedPreferences("terra_layer_filters",Context.MODE_PRIVATE)
                    .edit().putBoolean("MALLS",show).apply()
                if(show)scheduleMallRefresh()
                else handler.removeCallbacks(mallRefreshTask)
            }
            else -> {
                layerFilters[category]=show
                getSharedPreferences("terra_layer_filters",Context.MODE_PRIVATE)
                    .edit().putBoolean(category,show).apply()
            }
        }
        refresh()
    }

    private fun showOverlayFilters() {
        if(overlayFiltersPopup?.isShowing==true){
            overlayFiltersPopup?.dismiss()
            return
        }
        val panel=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(10),dp(12),dp(12))
            background=GradientDrawable().apply{
                cornerRadius=dp(12).toFloat()
                setColor(Color.rgb(20,31,24))
                setStroke(dp(1),Color.rgb(189,156,92))
            }
        }
        panel.addView(label("◈  MAP / OVERLAY FILTERS",15,sand).apply{
            setTypeface(Typeface.SERIF,Typeface.BOLD)
        },LinearLayout.LayoutParams(-1,dp(33)))
        panel.addView(label("Choose what appears on your map",10,sage),
            LinearLayout.LayoutParams(-1,dp(22)))
        val contents=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        panel.addView(contents)
        val sets=listOf(
            "OBJECTIVES" to listOf(
                "NATURE" to "▲  NATURE",
                "HERITAGE" to "⌂  HERITAGE",
                "ARCH" to "⌗  ARCH",
                "ART" to "✧  ART",
                "SAVED" to "◆  SAVED",
                "OTHER" to "◇  OTHER"),
            "PLACES" to listOf(
                "MALLS" to "▤  MALLS (${malls.size})"),
            "TRANSPORT" to listOf(
                "METRO" to "Ⓜ  METRO",
                "TAXI" to "▣  TAXI (${taxiStands.size})"),
            "PATH INTELLIGENCE" to listOf(
                "ESTIMATES" to "⌁  PATH ESTIMATES"),
            "PERSONAL" to listOf(
                "ANCHORS" to "⌖  HOME / WORK",
                "JOURNAL" to "▤  FIELD PINS")
        )
        fun render(){
            contents.removeAllViews()
            sets.forEach{(heading,items)->
                contents.addView(label(heading,11,sage).apply{
                    setPadding(dp(3),dp(5),0,0)
                    setTypeface(Typeface.DEFAULT,Typeface.BOLD)
                },LinearLayout.LayoutParams(-1,dp(27)))
                items.chunked(2).forEach{pair->
                    val line=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
                    for((key,name) in pair){
                        val visible=layerVisible(key)
                        val button=action((if(visible)"✓ " else "○ ")+name).apply{
                            applyExpeditionStyle(this,visible)
                            textSize=10f
                            isAllCaps=false
                            maxLines=1
                            gravity=Gravity.CENTER
                            setOnClickListener{
                                setLayerVisible(key,!layerVisible(key))
                                render()
                            }
                        }
                        line.addView(button,LinearLayout.LayoutParams(0,dp(39),1f).apply{
                            setMargins(dp(2),dp(2),dp(2),dp(2))
                        })
                    }
                    if(pair.size==1)line.addView(View(this),
                        LinearLayout.LayoutParams(0,dp(39),1f))
                    contents.addView(line)
                }
            }
        }
        render()
        val controls=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        fun quickAction(name:String,visible:Boolean) {
            controls.addView(action(name).apply{
                applyExpeditionStyle(this)
                setOnClickListener{
                    sets.forEach{(_,entries)->
                        entries.forEach{(key,_)->setLayerVisible(key,visible)}
                    }
                    render()
                }
            },LinearLayout.LayoutParams(0,dp(37),1f).apply{
                setMargins(dp(2),dp(5),dp(2),0)
            })
        }
        quickAction("SHOW ALL",true)
        quickAction("HIDE ALL",false)
        panel.addView(controls)
        panel.addView(action("↻  RETRY WORLD OBJECTIVES").apply {
            applyExpeditionStyle(this)
            textSize=11f
            setOnClickListener {
                overlayFiltersPopup?.dismiss()
                globalRetryAfter=0L
                globalRequestAt=0L
                scheduleGlobalObjectiveRefresh(0L)
                status.text="WORLD OBJECTIVES / CHECKING VISIBLE AREA"
            }
        },LinearLayout.LayoutParams(-1,dp(42)).apply {
            setMargins(dp(2),dp(4),dp(2),0)
        })
        panel.addView(action("⌕  SCAN MALLS IN THIS AREA").apply {
            applyExpeditionStyle(this)
            textSize=11f
            setOnClickListener {
                overlayFiltersPopup?.dismiss()
                setLayerVisible("MALLS",true)
                maybeRefreshMalls(force=true)
            }
        },LinearLayout.LayoutParams(-1,dp(42)).apply {
            setMargins(dp(2),dp(4),dp(2),0)
        })
        panel.addView(action("⌕  SCAN TAXI STANDS IN THIS AREA").apply{
            applyExpeditionStyle(this)
            textSize=11f
            setOnClickListener {
                overlayFiltersPopup?.dismiss()
                setLayerVisible("TAXI",true)
                maybeRefreshTaxiStands(force=true)
            }
        },LinearLayout.LayoutParams(-1,dp(42)).apply{
            setMargins(dp(2),dp(4),dp(2),0)
        })
        panel.addView(label("Malls and taxi ranks use OpenStreetMap; smaller centres may be missing.",9,sage).apply{
            gravity=Gravity.CENTER
        })
        panel.addView(label("Visibility only / your data stays saved",9,sage).apply{
            gravity=Gravity.CENTER
        },LinearLayout.LayoutParams(-1,dp(26)))
        val scroll=android.widget.ScrollView(this).apply{
            isFillViewport=false
            addView(panel)
        }
        val popupWidth=dp(310).coerceAtMost(resources.displayMetrics.widthPixels-dp(20))
        // Extra places row and scan actions must not extend beyond the bottom
        // of smaller screens. Measure content, cap the popup and let the
        // ScrollView handle any overflow, with no unnecessary blank panel.
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(popupWidth,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
        )
        val popupHeight=panel.measuredHeight.coerceAtMost(
            (resources.displayMetrics.heightPixels-dp(142)).coerceAtLeast(dp(260)))
        overlayFiltersPopup=PopupWindow(
            scroll,popupWidth,popupHeight,true).apply{
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(8).toFloat()
            showAtLocation(map,Gravity.TOP or Gravity.END,dp(10),dp(96))
        }
    }
    private lateinit var trackButton: Button
    private lateinit var modeButton: Button
    private lateinit var fogOverlay: DiscoveryFogOverlay
    private val records = mutableListOf<DiscoveryPoint>()
    private val demo = mutableListOf<DiscoveryPoint>()
    private var mode = "VIEW"
    private var restoreProgressDialog:android.app.AlertDialog?=null
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
        // An in-memory UI snapshot does not contain every late GPS fix.
        // Replace only manual records under the service's shared write lock.
        val latest=store.replaceManual(state.manual)
        records.clear();records.addAll(latest)
        segments.clear();segments.addAll(state.segments)
        pathStore.save(segments);fogOverlay.invalidateData();refresh()
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
        val latest=store.replaceManual(records.filter{it.manual})
        records.clear();records.addAll(latest)
        lasso.clear();lassoFinished=false
        fogOverlay.invalidateData();refresh()
        status.text="LASSO / $added POINTS"
    }

    private var tracking = false
    private var receiverRegistered=false
    private var lastGpsUiRefresh=0L
    private var lastVerifiedGpsElapsed=0L
    private var lastTrackTapElapsed=0L
    private val gpsStatusTick=object:Runnable {
        override fun run() {
            if(tracking && !TrackingService.resting &&
                lastVerifiedGpsElapsed>0L &&
                android.os.SystemClock.elapsedRealtime()-lastVerifiedGpsElapsed>45000L) {
                status.text="GPS SIGNAL WEAK / NO FRESH VERIFIED POSITION / CHECK OPEN SKY"
                expeditionBottom?.gpsSearching=true
            }
            handler.postDelayed(this,15000L)
        }
    }
    private var lastKnownFixTime=0L
    private fun restoreLastKnownFix():Boolean {
        if(!::fogOverlay.isInitialized)return false
        val prefs=getSharedPreferences("terra_last_fix",Context.MODE_PRIVATE)
        val time=prefs.getLong("time",0L)
        if(time<=0L || time>System.currentTimeMillis())return false
        val lat=java.lang.Double.longBitsToDouble(prefs.getLong("latBits",0L))
        val lon=java.lang.Double.longBitsToDouble(prefs.getLong("lonBits",0L))
        if(!lat.isFinite() || !lon.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0)return false
        fogOverlay.currentPosition=GeoPoint(lat,lon)
        fogOverlay.positionAccuracy=prefs.getFloat("accuracy",0f)
        lastKnownFixTime=time
        return true
    }
    private fun lastKnownLabel():String {
        val age=((System.currentTimeMillis()-lastKnownFixTime)/1000L).coerceAtLeast(0L)
        return when {
            age<60L -> "LAST KNOWN / ${age}s AGO"
            age<3600L -> "LAST KNOWN / ${age/60L}m AGO"
            else -> "LAST KNOWN / ${age/3600L}h AGO"
        }
    }
    // Short-lived estimates are deliberately NEVER persisted to discoveries.
    private val uncertainGps=mutableListOf<Pair<GeoPoint,Long>>()
    private val trackingReceiver=object:BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            if(intent.action==TrackingService.ACTION_RESET_COMPLETE) {
                tracking=false
                expeditionBottom?.trackingActive=false
                expeditionBottom?.gpsSearching=false
                if(::trackButton.isInitialized)trackButton.text="TRACK"
                records.clear()
                segments.clear()
                demo.clear()
                simulating=false
                handler.removeCallbacks(simulationStep)
                inferredPaths=emptyList()
                uncertainGps.clear()
                historyCutoff=null
                lastRecoveryPointCount=0
                lastGpsSample=null
                pending.clear()
                lasso.clear()
                lassoFinished=false
                markUndo.clear()
                markRedo.clear()
                expeditionPalette?.let{it.canUndo=false;it.canRedo=false;it.selected=-1;it.visibility=View.GONE}
                confirmButton.visibility=View.GONE
                mode="VIEW"
                fogEnabled=true
                fogOverlay.fogVisible=true
                status.text="EXPLORATION RESET / FOG RESTORED"
                refresh()
                return
            }
            if(intent.action==TrackingService.ACTION_STATE) {
                val active=intent.getBooleanExtra("running",false)
                tracking=active
                expeditionBottom?.trackingActive=active
                expeditionBottom?.gpsSearching=active && fogOverlay.currentPosition==null
                if(!active)status.text="TRACK STOPPED / TAP TRACK TO RESTART"
                return
            }
            if(intent.action==TrackingService.ACTION_MODE) {
                val resting=intent.getBooleanExtra("eco",false)
                status.text=if(resting)"GPS REST / BATTERY SAVER / AUTO WAKE"
                    else "GPS ACTIVE / REACQUIRING SIGNAL"
                expeditionBottom?.gpsSearching=!resting
                return
            }
            if(intent.action!=TrackingService.ACTION_FIX)return
            val lat=intent.getDoubleExtra("lat",Double.NaN)
            val lon=intent.getDoubleExtra("lon",Double.NaN)
            if(!lat.isFinite() || !lon.isFinite())return
            val point=GeoPoint(lat,lon)
            val weak=intent.getBooleanExtra("weak",false)
            // A weak fix updates the uncertainty label, not a trusted map position.
            // Suppress tiny recentering jumps to avoid repeated expensive tile/fog redraws.
            if(!weak || fogOverlay.currentPosition==null)fogOverlay.currentPosition=point
            fogOverlay.positionAccuracy=intent.getFloatExtra("accuracy",0f)
            if(followUser && !weak) {
                val viewCenter=map.mapCenter
                val distance=FloatArray(1)
                android.location.Location.distanceBetween(viewCenter.latitude,viewCenter.longitude,
                    lat,lon,distance)
                if(distance[0]>maxOf(11f,fogOverlay.positionAccuracy*.85f))
                    map.controller.animateTo(point)
            }
            val confidence=intent.getIntExtra("confidence",0)
            val accuracy=intent.getFloatExtra("accuracy",99f)
            val observationTime=System.currentTimeMillis()
            if(weak && confidence in 35..64 && accuracy<=65f) {
                uncertainGps.add(point to observationTime)
                uncertainGps.removeAll{observationTime-it.second>90000L}
                while(uncertainGps.size>24)uncertainGps.removeAt(0)
            } else if(!weak) {
                uncertainGps.clear()
            }
            val revealed=intent.getBooleanExtra("revealed",false)
            if(revealed) {
                // Service has already saved the fix; avoid loading all historical JSON.
                val t=intent.getLongExtra("timestamp",System.currentTimeMillis())
                if(records.none{!it.manual && it.timestamp==t}) {
                    val rawLat=intent.getDoubleExtra("rawLat",lat)
                    val rawLon=intent.getDoubleExtra("rawLon",lon)
                    records.add(DiscoveryPoint(rawLat,rawLon,false,t,
                        accuracy,intent.getFloatExtra("speed",Float.NaN),confidence))
                    if(System.currentTimeMillis()-t<12000L)fogOverlay.pulseReveal(rawLat,rawLon)
                }
                if(!offline && records.size%35==0)handler.post{maybeRecoverPaths()}
            }
            if(!weak) {
                lastVerifiedGpsElapsed=android.os.SystemClock.elapsedRealtime()
                lastKnownFixTime=System.currentTimeMillis()
                expeditionBottom?.gpsSearching=false
            }
            status.text=(if(weak)"GPS ESTIMATE" else
                if(intent.getBooleanExtra("eco",false))"GPS ECO" else "GPS VERIFIED")+
                " / "+(intent.getStringExtra("motionProfile") ?: "WALK")+
                " / "+(intent.getStringExtra("source") ?: "FUSED")+
                " / C "+confidence+"/100"+
                " / ±"+fogOverlay.positionAccuracy.toInt()+"m"
            val now=System.currentTimeMillis()
            if(now-lastGpsUiRefresh>=3500L) {
                lastGpsUiRefresh=now
                updateNavigation()
                refresh()
            }
        }
    }
    private var followUser = true
    private var headingUp=false
    private var headingButton:Button?=null
    private var trueNorthDeclination=0f
    private var lastDeclinationLookup=0L
    private var lastHeadingRotationAt=0L
    private var headingTarget=0f
    private var headingFramePosted=false
    private val headingFrame=object:Runnable {
        override fun run() {
            headingFramePosted=false
            if(!headingUp || !::map.isInitialized || isFinishing || isDestroyed)return
            val now=android.os.SystemClock.uptimeMillis()
            val delta=if(lastHeadingRotationAt==0L)16L
                else (now-lastHeadingRotationAt).coerceIn(1L,80L)
            lastHeadingRotationAt=now
            val before=map.mapOrientation
            val next=MapHeadingPolicy.frameAngle(before,headingTarget,delta)
            if(kotlin.math.abs(MapHeadingPolicy.shortestTurn(before,next))>=0.04f)
                map.setMapOrientation(next,false)
            if(MapHeadingPolicy.needsFrame(next,headingTarget))
                postHeadingFrame()
        }
    }
    private fun postHeadingFrame() {
        if(!headingFramePosted && headingUp && ::map.isInitialized) {
            headingFramePosted=true
            map.postOnAnimation(headingFrame)
        }
    }
    private val correctedRotation=FloatArray(9)
    private lateinit var followButton:Button
    private var paintPopup:PopupWindow?=null
    private lateinit var radarDrawer:LinearLayout
    private var radarExpanded=false
    private var radialMenu:PopupWindow?=null
    private var attachmentPoint:GeoPoint?=null
    private var attachmentNoteTime:Long?=null
    private var cameraUri:Uri?=null
    private var snapCameraUri:Uri?=null
    private var snapCaptureKind:String?=null
    private var snapDraft:SnapDraft?=null
    private var snapPickingMap=false
    private var snapPickBar:LinearLayout?=null
    private var snapVoicePermissionPending=false
    private var snapVoiceRecording=false
    private val journeyDraft=mutableListOf<String>()
    private var journeyPlanBar:LinearLayout?=null
    private var journeyPlanSummary:TextView?=null
    // A mission can only be completed after a fresh GPS check and a new camera photo.
    private var objectivePhotoTargetId:String?=null
    private var objectivePhotoPermissionId:String?=null
    private var previewLocation:GeoPoint?=null
    private var previewLabel=""
    private var pendingObjective:ReconObjective?=null
    private var previewBar:LinearLayout?=null
    private var navigationTarget:Long?=null
    private var movingPinTime:Long?=null
    private var movingObjectiveId:String?=null
    private var movePinBar:LinearLayout?=null
    private var movingPreview:GeoPoint?=null
    private var movingOriginalNote:FieldNote?=null
    private var pinMoveReticle:View?=null

    private fun chooseMoveDestination(point:GeoPoint) {
        if(movingPinTime==null && movingObjectiveId==null)return
        val target=GeoPoint(point.latitude,point.longitude)
        movingPreview=target
        movingPinTime?.let {id->
            val index=notes.indexOfFirst {it.time==id}
            if(index>=0)notes[index]=notes[index].copy(
                latitude=target.latitude,longitude=target.longitude)
        }
        status.text="MOVE PIN / DRAG OR TAP MAP / CONFIRM TO SAVE"
        pinMoveReticle?.invalidate()
        refresh()
    }
    private fun cancelPinMove() {
        movingOriginalNote?.let {original->
            val index=notes.indexOfFirst {it.time==original.time}
            if(index>=0)notes[index]=original
        }
        movingOriginalNote=null
        movingPreview=null
        movingPinTime=null
        movingObjectiveId=null
        movePinBar?.visibility=View.GONE
        mode="VIEW"
        pinMoveReticle?.invalidate()
        status.text="MOVE PIN / CANCELLED"
        refresh()
    }
    private fun savePinMove() {
        val target=movingPreview
        if(target==null){status.text="MOVE PIN / CHOOSE A LOCATION FIRST";return}
        val noteId=movingPinTime
        if(noteId!=null) {
            val index=notes.indexOfFirst{it.time==noteId}
            if(index>=0) {
                notes[index]=notes[index].copy(
                    latitude=target.latitude,longitude=target.longitude)
                fieldNotes.save(notes)
            }
        }
        movingObjectiveId?.let {id->
            objectiveStore.moveCustom(id,target.latitude,target.longitude)
        }
        movingOriginalNote=null
        movingPreview=null
        movingPinTime=null
        movingObjectiveId=null
        movePinBar?.visibility=View.GONE
        mode="VIEW"
        pinMoveReticle?.invalidate()
        status.text="MOVE PIN / LOCATION SAVED"
        refresh()
    }

    private var historyCutoff:Long?=null
    private var timeMachinePopup:PopupWindow?=null
    private lateinit var objectiveStore:ObjectiveStore
    private lateinit var journeyStore:JourneyStore
    private val terms get()=ExpeditionWording.current
    private val metroStations=mutableListOf<TransitStation>()
    private val taxiStandStore by lazy { TaxiStandStore(this) }
    private val taxiStands=mutableListOf<TaxiStand>()
    private var taxiStandsEnabled=true
    private val mallStore by lazy {MallStore(this)}
    private val malls=mutableListOf<MallPlace>()
    private var mallLoading=false
    private var lastMallAttempt=0L
    private val mallRefreshTask=Runnable{maybeRefreshMalls()}
    private var taxiStandLoading=false
    private var lastTaxiStandAttempt=0L
    private val taxiRefreshTask=Runnable { maybeRefreshTaxiStands() }
    private var stationLoading=false
    private var objectivesPopup:PopupWindow?=null
    private var objectiveBubble:PopupWindow?=null
    private var objectiveBubblePlace:ReconObjective?=null
    private var objectiveBubbleTail:ObjectiveBubbleTail?=null
    private var objectiveBubbleStack:LinearLayout?=null
    private var objectiveBubbleBody:LinearLayout?=null
    private var objectiveBubbleBelow=false

    private fun updateObjectiveBubblePosition() {
        val popup=objectiveBubble ?: return
        val place=objectiveBubblePlace ?: return
        if(!popup.isShowing || map.width<=0 || map.height<=0)return
        val marker=map.projection.toPixels(GeoPoint(place.latitude,place.longitude),Point())
        if(marker.x<0 || marker.y<0 || marker.x>map.width || marker.y>map.height) {
            popup.dismiss();return
        }
        val screen=IntArray(2);map.getLocationOnScreen(screen)
        val width=popup.width
        val content=popup.contentView
        content.measure(
            View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val height=content.measuredHeight
        val placeAbove=marker.y>height+dp(23)
        if(objectiveBubbleBelow==placeAbove) {
            objectiveBubbleBelow=!placeAbove
            val stack=objectiveBubbleStack
            val tail=objectiveBubbleTail
            if(stack!=null && tail!=null) {
                stack.removeView(tail)
                tail.pointingUp=!placeAbove
                stack.addView(tail,if(placeAbove)stack.childCount else 0)
            }
        }
        val left=(screen[0]+marker.x-width/2).coerceIn(screen[0]+dp(8),
            screen[0]+map.width-width-dp(8))
        val top=if(placeAbove)screen[1]+marker.y-height-dp(7)
            else screen[1]+marker.y+dp(7)
        objectiveBubbleTail?.anchorX=(screen[0]+marker.x-left).toFloat()
        popup.update(left,top,-1,-1)
    }

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
    private var rulerBar:LinearLayout?=null
    private var rulerCaption:TextView?=null

    /** Ruler is a modal map interaction: a marker tap adds a measurement point. */
    private fun toggleRuler(active:Boolean) {
        measure=active
        measurePoints.clear()
        rulerBar?.visibility=if(active)View.VISIBLE else View.GONE
        if(active) {
            mode="VIEW"
            expeditionPalette?.visibility=View.GONE
            radialMenu?.dismiss()
            status.text="RULER / TAP TWO POINTS OR MARKERS"
        } else {
            status.text="RULER / OFF"
        }
        updateRulerCaption()
        refresh()
    }

    private fun recordRulerPoint(point:GeoPoint) {
        if(!measure)return
        if(measurePoints.size>=2)measurePoints.clear()
        measurePoints.add(GeoPoint(point.latitude,point.longitude))
        updateRulerCaption()
        refresh()
    }

    private fun updateRulerCaption() {
        val caption=when(measurePoints.size) {
            0 -> "RULER  /  SELECT START"
            1 -> "RULER  /  SELECT END"
            else -> {
                val metres=gap(measurePoints[0],measurePoints[1])
                if(metres>=1000f) "DISTANCE  /  %.2f km".format(java.util.Locale.US,metres/1000f)
                else "DISTANCE  /  %.0f m".format(java.util.Locale.US,metres)
            }
        }
        rulerCaption?.text=caption
        if(measure)status.text=caption
    }
    private var filteredFixes=0
    private var stableFixCount=0
    private var lastSavedLocation:android.location.Location?=null
    private var simulating = false
    private var fogEnabled = true
    private var radius = 40
    private var last: DiscoveryPoint? = null
    private var lastPaintTime = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val repositionBubbleAfterPan=Runnable {updateObjectiveBubblePosition()}
    private var lastCompassPaintElapsed=0L
    private var lastCompassHeading=-1
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
        ExpeditionThemes.load(this)
        // Avoid main-thread wait on a previous Activity's active import.
        if(TerraArchive.importInProgress) {
            android.widget.Toast.makeText(this,
                "TERRA is restoring a backup. Reopen after it completes.",
                android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // Recover a failed/interrupted ZIP restore BEFORE loading any stores.
        // If rollback is impossible, fail closed rather than loading a mixed
        // half-restored user profile and allowing it to be overwritten.
        try {
            TerraArchive(this).recoverInterruptedRestore()
        } catch(error:Exception) {
            android.util.Log.e("TERRA","Pending backup recovery failed",error)
            android.widget.Toast.makeText(this,
                "Backup recovery failed. Your recovery ZIP is preserved. "+
                "Do not clear app data; contact support.",
                android.widget.Toast.LENGTH_LONG).show()
            finish()
            return
        }
        attachmentNoteTime=savedInstanceState?.getLong("attachment_note_time")?.takeIf{it!=0L}
        objectivePhotoTargetId=savedInstanceState?.getString("objective_photo_target")
        objectivePhotoPermissionId=savedInstanceState?.getString("objective_photo_permission")
        savedInstanceState?.getString("objective_camera_uri")?.let { cameraUri=Uri.parse(it) }
        savedInstanceState?.getString("snap_camera_uri")?.let {snapCameraUri=Uri.parse(it)}
        snapCaptureKind=savedInstanceState?.getString("snap_capture_kind")
        val restored=savedInstanceState?.getString("snap_draft_filename")
        val restoredKind=savedInstanceState?.getString("snap_draft_kind")
        if(restored!=null && restoredKind!=null && File(filesDir,restored).isFile)
            snapDraft=SnapDraft(restored,restoredKind,
                savedInstanceState.getString("snap_draft_title")?:"Quick capture")
        if(snapDraft==null) {
            val draft=getSharedPreferences("terra_snap_draft_v1",Context.MODE_PRIVATE)
            val filename=draft.getString("file",null)
            val kind=draft.getString("kind",null)
            if(filename!=null && kind!=null &&
                !filename.contains("/") && !filename.contains("\\") &&
                File(filesDir,filename).isFile)
                snapDraft=SnapDraft(filename,kind,
                    draft.getString("title","Quick capture")?:"Quick capture")
        }
        if(savedInstanceState?.containsKey("field_photo_lat")==true &&
           savedInstanceState.containsKey("field_photo_lon")) {
            attachmentPoint=GeoPoint(
                savedInstanceState.getDouble("field_photo_lat"),
                savedInstanceState.getDouble("field_photo_lon"))
        }
        window.statusBarColor = olive
        window.navigationBarColor = olive
        Configuration.getInstance().userAgentValue = packageName
        store = DiscoveryStore(this)
        fieldNotes=FieldNotes(this)
        objectiveStore=ObjectiveStore(this)
        journeyStore=JourneyStore(this)
        metroStations.addAll(TransitStations(this).load())
        taxiStandsEnabled=getSharedPreferences("terra_taxi_settings",Context.MODE_PRIVATE)
            .getBoolean("enabled",true)
        taxiStands.addAll(taxiStandStore.load())
        malls.addAll(mallStore.load())
        objectiveStore.enabled=true
        getSharedPreferences("terra_layer_filters",Context.MODE_PRIVATE).let {prefs->
            listOf("NATURE","HERITAGE","ARCH","ART","SAVED","OTHER","METRO","MALLS","ANCHORS","ESTIMATES")
                .forEach{layerFilters[it]=prefs.getBoolean(it,true)}
        }
        notes.addAll(fieldNotes.load())
        pathStore = PathStore(this)
        segments.addAll(pathStore.load())
        records.addAll(store.load())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(olive); fitsSystemWindows = true }
        window.statusBarColor=ExpeditionThemes.current.dark
        window.navigationBarColor=ExpeditionThemes.current.dark
        sensors=getSystemService(Context.SENSOR_SERVICE) as SensorManager
        compassEnabled=sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)!=null
        headingUp=getSharedPreferences("terra_map_orientation_v1",Context.MODE_PRIVATE)
            .getBoolean("heading_up",false) && compassEnabled
        compassView=label("N ---°",10,sand)
        weatherView=label("--°",10,sage)
        val cachedTemperature=weatherPrefs.getInt("last_temperature",Int.MIN_VALUE)
        expeditionHeader=ExpeditionHeaderView(this,
            onMissions={showObjectives()},
            onMap={chooseMapSource()},
            onSolar={showSolarDetails()},
            onNorth={toggleHeadingLock()}
        ).also {
            if(cachedTemperature!=Int.MIN_VALUE)it.temperature=cachedTemperature
            root.addView(it,LinearLayout.LayoutParams(-1,dp(45)))
        }
        // The status text remains available to services; no extra row obscures the map.
        status=label("GPS OFFLINE  |  FOG 82%  |  R 20m",10,sage).apply{
            setPadding(dp(12),dp(1),dp(8),dp(3))
            setBackgroundColor(ExpeditionThemes.current.dark)
        }
        status.isSingleLine=true
        status.ellipsize=android.text.TextUtils.TruncateAt.END
        val statusRow=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setBackgroundColor(ExpeditionThemes.current.dark)
        }
        statusRow.addView(status,LinearLayout.LayoutParams(0,dp(23),1f))
        statusRow.addView(action("▤ LAYERS").apply{
            textSize=10f
            setTextColor(sand)
            background=GradientDrawable().apply{
                cornerRadius=dp(4).toFloat()
                setColor(ExpeditionThemes.current.surface)
                setStroke(dp(1),ExpeditionThemes.current.gold)
            }
            setOnClickListener{showOverlayFilters()}
        },LinearLayout.LayoutParams(dp(84),dp(22)).apply{
            setMargins(dp(2),0,dp(8),0)
        })
        root.addView(statusRow,LinearLayout.LayoutParams(-1,dp(23)))
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            setBuiltInZoomControls(false)
            // Reopen where the user was last exploring. A fresh install
            // starts on a neutral world view, not the developer's Dubai map.
            val viewPrefs=getSharedPreferences("terra_map_view_v1",Context.MODE_PRIVATE)
            val lat=viewPrefs.getString("lat",null)?.toDoubleOrNull()
            val lon=viewPrefs.getString("lon",null)?.toDoubleOrNull()
            if(lat!=null && lon!=null && lat in -85.0..85.0 && lon in -180.0..180.0) {
                controller.setZoom(viewPrefs.getFloat("zoom",12f).toDouble()
                    .coerceIn(2.5,20.0))
                controller.setCenter(GeoPoint(lat,lon))
            } else {
                controller.setZoom(3.0)
                controller.setCenter(GeoPoint(18.0,0.0))
            }
        }
        // Normal play: reuse the actual lists (no 3,500-item filter/copy on
        // every map animation frame). The time-machine still filters as before.
        fogOverlay = DiscoveryFogOverlay(
            {if(historyCutoff==null)records else records.filter{it.timestamp<=historyCutoff!!}},
            {if(historyCutoff==null)demo else emptyList()},
            {if(historyCutoff==null)segments else emptyList()},
            {if(historyCutoff==null)notes else notes.filter{it.time<=historyCutoff!!}}
        )
        map.overlays.add(fogOverlay)
        val hasInitialPosition=restoreLastKnownFix()
        if(hasInitialPosition &&
            !getSharedPreferences("terra_map_view_v1",Context.MODE_PRIVATE)
                .contains("lat")) {
            fogOverlay.currentPosition?.let {
                map.controller.setZoom(15.0)
                map.controller.setCenter(it)
            }
        }
        map.overlays.add(object:Overlay() {
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(shadow)return
                val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=Color.argb(160,212,196,134)
                    strokeWidth=dp(4).toFloat();style=Paint.Style.STROKE
                    strokeCap=Paint.Cap.ROUND
                    pathEffect=android.graphics.DashPathEffect(floatArrayOf(dp(7).toFloat(),dp(6).toFloat()),0f)
                }
                if(!layerVisible("ESTIMATES"))return
                // Map-matched guesses: visible dashed indication ONLY; no fog erase.
                inferredPaths.filter {historyCutoff==null || it.endTime<=historyCutoff!!}
                    .forEach {candidate->
                    val path=android.graphics.Path()
                    candidate.points.forEachIndexed {i,pos->
                        val p=mapView.projection.toPixels(GeoPoint(pos.first,pos.second),Point())
                        if(i==0)path.moveTo(p.x.toFloat(),p.y.toFloat()) else path.lineTo(p.x.toFloat(),p.y.toFloat())
                    }
                    canvas.drawPath(path,ink)
                    val lastPoint=candidate.points.getOrNull(candidate.points.size/2)
                    if(lastPoint!=null) {
                        val labelPt=mapView.projection.toPixels(
                            GeoPoint(lastPoint.first,lastPoint.second),Point())
                        val textInk=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                            color=Color.rgb(235,213,141)
                            textSize=dp(9).toFloat()
                            textAlign=Paint.Align.CENTER
                        }
                        canvas.drawText("EST "+candidate.confidence+"/100",
                            labelPt.x.toFloat(),labelPt.y.toFloat()-dp(6),textInk)
                    }
                }
                // Short-lived weak Fused estimates, drawn as outlines, never
                // mistaken for already-visited territory.
                val now=System.currentTimeMillis()
                uncertainGps.filter{now-it.second<90000L}.forEach{(point,_)->
                    val px=mapView.projection.toPixels(point,Point())
                    val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply{
                        color=Color.argb(140,235,185,105)
                        style=Paint.Style.STROKE
                        strokeWidth=dp(1).toFloat()
                        pathEffect=android.graphics.DashPathEffect(
                            floatArrayOf(dp(3).toFloat(),dp(4).toFloat()),0f)
                    }
                    canvas.drawCircle(px.x.toFloat(),px.y.toFloat(),dp(10).toFloat(),ring)
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
                if(measure){recordRulerPoint(pos);return true}
                setMyLocation();return true
            }
        })
        map.overlays.add(object : Overlay() {
            override fun onSingleTapConfirmed(event:MotionEvent,mapView:MapView):Boolean {
                if(mode!="VIEW") return false
                val clicked=notes.asReversed().takeIf{fogOverlay.showCheckpoints}?.firstOrNull { note ->
                    val p=mapView.projection.toPixels(GeoPoint(note.latitude,note.longitude),Point())
                    kotlin.math.hypot((event.x-p.x).toDouble(),(event.y-p.y).toDouble())<dp(24)
                }
                if(measure) {
                    val point=clicked?.let{GeoPoint(it.latitude,it.longitude)}
                        ?: mapView.projection.fromPixels(event.x.toInt(),event.y.toInt())
                            .let{GeoPoint(it.latitude,it.longitude)}
                    recordRulerPoint(point)
                    return true
                }
                if(clicked!=null) {showExisting(clicked);return true}
                return false
            }
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(shadow || measurePoints.isEmpty())return
                val a=mapView.projection.toPixels(measurePoints[0],Point())
                val x=a.x.toFloat();val y=a.y.toFloat()
                val outline=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style=Paint.Style.STROKE
                    color=Color.argb(245,12,18,18)
                    strokeCap=Paint.Cap.ROUND
                    strokeWidth=dp(8).toFloat()
                    setShadowLayer(dp(3).toFloat(),0f,dp(1).toFloat(),Color.BLACK)
                }
                val luminous=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=ExpeditionThemes.current.gold
                    strokeWidth=dp(4).toFloat()
                    strokeCap=Paint.Cap.ROUND
                    style=Paint.Style.STROKE
                }
                canvas.drawCircle(x,y,dp(6).toFloat(),outline)
                canvas.drawCircle(x,y,dp(5).toFloat(),luminous)
                if(measurePoints.size==2) {
                    val b=mapView.projection.toPixels(measurePoints[1],Point())
                    val bx=b.x.toFloat();val by=b.y.toFloat()
                    canvas.drawLine(x,y,bx,by,outline)
                    canvas.drawLine(x,y,bx,by,luminous)
                    canvas.drawCircle(bx,by,dp(6).toFloat(),outline)
                    canvas.drawCircle(bx,by,dp(5).toFloat(),luminous)
                    val metres=gap(measurePoints[0],measurePoints[1])
                    val caption=if(metres>=1000f)
                        "%.2f km".format(java.util.Locale.US,metres/1000f)
                        else "%.0f m".format(java.util.Locale.US,metres)
                    val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style=Paint.Style.FILL
                        color=ExpeditionThemes.current.light
                        textSize=dp(14).toFloat()
                        textAlign=Paint.Align.CENTER
                        typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD)
                    }
                    val midX=(x+bx)/2f
                    val midY=(y+by)/2f-dp(15)
                    val tw=ink.measureText(caption)
                    val rect=android.graphics.RectF(
                        midX-tw/2f-dp(12),midY-dp(20),
                        midX+tw/2f+dp(12),midY+dp(7))
                    val back=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color=ExpeditionThemes.current.dark
                        setShadowLayer(dp(3).toFloat(),0f,dp(2).toFloat(),Color.BLACK)
                    }
                    canvas.drawRoundRect(rect,dp(8).toFloat(),dp(8).toFloat(),back)
                    back.clearShadowLayer()
                    back.style=Paint.Style.STROKE
                    back.strokeWidth=dp(1).toFloat()
                    back.color=ExpeditionThemes.current.gold
                    canvas.drawRoundRect(rect,dp(8).toFloat(),dp(8).toFloat(),back)
                    canvas.drawText(caption,midX,midY,ink)
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
                // Never show the radial menu during SNAP placement or Journey
                // planning; those modes own map selection gestures exclusively.
                if(!MapTouchPolicy.canOpenRadialMenu(mode))
                    return MapTouchPolicy.usesTapToSelect(mode)
                val p=mapView.projection.fromPixels(e.x.toInt(),e.y.toInt())
                if(measure) {
                    recordRulerPoint(GeoPoint(p.latitude,p.longitude))
                    return true
                }
                showRadialMenu(e.x.toInt(),e.y.toInt(),GeoPoint(p.latitude,p.longitude))
                return true
            }
        })
        map.overlays.add(object : Overlay() {
            private var touchStartX=0f
            private var touchStartY=0f
            override fun onTouchEvent(event:MotionEvent,mapView:MapView):Boolean {
                if(mode!="VIEW")return false
                if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                    touchStartX=event.x
                    touchStartY=event.y
                }
                // A tiny hand wobble while tapping the map must not disable
                // position lock. Only a real drag releases the follow mode.
                if(event.actionMasked==MotionEvent.ACTION_MOVE &&
                    event.pointerCount==1 && followUser &&
                    kotlin.math.hypot((event.x-touchStartX).toDouble(),
                        (event.y-touchStartY).toDouble())>dp(22)) {
                    followUser=false
                    // Panning pauses auto-centering, not the compass lock:
                    // a small accidental drag should not reset orientation.
                    if(headingUp)status.text="HEADING-UP / PAN PAUSED / TAP LOCATE TO FOLLOW"
                    if(::followButton.isInitialized)followButton.text="LOCATE ○"
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
            private var multitouchGesture = false
            private var paintStart: GeoPoint? = null
            private var paintStarted = false
            private val brushPreview=ArrayList<GeoPoint>(128)

            private fun appendBrushPoint(point:GeoPoint) {
                val previous=brushPreview.lastOrNull()
                if(previous==null) {
                    brushPreview.add(point)
                    return
                }
                val distance=gap(previous,point)
                if(distance<.4f)return
                val stride=(radius*.28f).coerceAtLeast(1.5f)
                val remaining=(1800-brushPreview.size).coerceAtLeast(0)
                if(remaining==0)return
                val steps=kotlin.math.ceil(distance/stride).toInt()
                    .coerceIn(1,minOf(remaining,250))
                for(i in 1..steps) {
                    val t=i.toFloat()/steps
                    brushPreview.add(GeoPoint(
                        FluidBrushSampling.lerp(previous.latitude,point.latitude,t),
                        FluidBrushSampling.lerp(previous.longitude,point.longitude,t)))
                }
            }
            override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
                if(mode=="PAINT" || mode=="ERASE") {
                    if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN || event.pointerCount>1) {
                        multitouchGesture=true
                        strokeLast=null
                        paintStart=null
                        paintStarted=false
                        brushPreview.clear()
                        fogOverlay.cancelFluidReveal(mapView)
                        mapView.postInvalidateOnAnimation()
                        return false // Let osmdroid handle two-finger pan and pinch zoom.
                    }
                    if(multitouchGesture) {
                        if(event.actionMasked==MotionEvent.ACTION_UP || event.actionMasked==MotionEvent.ACTION_CANCEL)multitouchGesture=false
                        return false // Do not resume painting mid-gesture.
                    }
                } else multitouchGesture=false
                // A prior non-VIEW check swallowed ACTION_UP for both SNAP
                // and Journey before osmdroid could recognize a normal tap.
                // Only manual drawing tools may consume raw gesture events.
                if(!MapTouchPolicy.drawsWithRawTouch(mode))return false
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
                if(event.actionMasked==MotionEvent.ACTION_UP ||
                    event.actionMasked==MotionEvent.ACTION_CANCEL) {
                    if(mode=="PAINT" && paintStart!=null) {
                        if(event.actionMasked==MotionEvent.ACTION_UP) {
                            val pt=mapView.projection.fromPixels(
                                event.x.toInt(),event.y.toInt())
                            val endPoint=GeoPoint(pt.latitude,pt.longitude)
                            if(brushPreview.isEmpty())brushPreview.add(paintStart!!)
                            appendBrushPoint(endPoint)
                            fogOverlay.moveFluidReveal(mapView,event.x,event.y)
                            // The original code serialized the entire history
                            // and reblurred all fog points for EVERY drag event.
                            // Commit the undo snapshot + both stores ONCE per
                            // complete stroke, not on each MotionEvent.
                            pushMark()
                            val spacing=(radius*.28f).coerceAtLeast(2.5f)
                            val savedPoints=ArrayList<GeoPoint>()
                            for(point in brushPreview) {
                                if(savedPoints.isEmpty() ||
                                    gap(savedPoints.last(),point)>=spacing)
                                    savedPoints.add(point)
                            }
                            val last=brushPreview.last()
                            if(savedPoints.isNotEmpty() && gap(savedPoints.last(),last)>1f)
                                savedPoints.add(last)
                            val stamp=System.currentTimeMillis()
                            val committed=ArrayList<DiscoveryPoint>(savedPoints.size)
                            savedPoints.forEachIndexed{index,p->
                                committed.add(DiscoveryPoint(
                                    p.latitude,p.longitude,true,stamp+index))
                                if(index>0) {
                                    val previous=savedPoints[index-1]
                                    segments.add(ManualSegment(previous.latitude,
                                        previous.longitude,p.latitude,p.longitude))
                                }
                            }
                            // One atomic append preserves GPS fixes written
                            // by the tracking service while the pen is moving.
                            if(committed.isNotEmpty()) {
                                // Persist the small stroke journal immediately;
                                // merge into the historical JSON off the UI
                                // thread to keep subsequent gestures responsive.
                                store.addAllDeferred(committed)
                                records.addAll(committed)
                            }
                            if(savedPoints.size>1)pathStore.save(segments)
                            fogOverlay.endFluidReveal(mapView)
                            fogOverlay.invalidateData()
                        } else fogOverlay.cancelFluidReveal(mapView)
                        brushPreview.clear()
                        refresh()
                    }
                    paintStart=null;paintStarted=false;strokeLast=null
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
                    if(event.actionMasked==MotionEvent.ACTION_DOWN) {
                        paintStart=geo
                        paintStarted=false
                        strokeLast=null
                        brushPreview.clear()
                        brushPreview.add(geo)
                        fogOverlay.beginFluidReveal(mapView,event.x,event.y)
                        mapView.postInvalidateOnAnimation()
                        return true
                    }
                    // Preview in the map's existing fog layer without touching
                    // permanent discoveries or JSON. The whole stroke is
                    // persisted only on ACTION_UP.
                    if(paintStart!=null) {
                        // MotionEvent may batch historical touch points.
                        // Interpolate each segment before saving so rapid
                        // swipes cannot create disconnected reveal islands.
                        for(index in 0 until event.historySize) {
                            val hx=event.getHistoricalX(index)
                            val hy=event.getHistoricalY(index)
                            val hit=mapView.projection.fromPixels(hx.toInt(),hy.toInt())
                            appendBrushPoint(GeoPoint(hit.latitude,hit.longitude))
                            fogOverlay.moveFluidReveal(mapView,hx,hy)
                        }
                        appendBrushPoint(geo)
                        fogOverlay.moveFluidReveal(mapView,event.x,event.y)
                        paintStarted=true
                    }
                    return true
                }
                if (mode == "ERASE") {
                    val before = records.size + segments.size
                    records.removeAll {it.manual &&
                        gap(GeoPoint(it.latitude, it.longitude), geo) < radius * 1.5f }
                    segments.removeAll {
                        gap(GeoPoint(it.aLat, it.aLon), geo) < radius * 1.5f ||
                        gap(GeoPoint(it.bLat, it.bLon), geo) < radius * 1.5f
                    }
                    if (records.size + segments.size != before) {
                        // Erase against the current canonical history under
                        // the same lock as incoming foreground GPS fixes.
                        val latest=store.updateAtomic{persisted->
                            persisted.filterNot {it.manual &&
                                gap(GeoPoint(it.latitude,it.longitude),geo)<radius*1.5f
                            }
                        }
                        records.clear();records.addAll(latest)
                        pathStore.save(segments)
                        fogOverlay.invalidateData();refresh()
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
                val all=allObjectives().filter{objectiveVisible(it)}
                val journeyIds=if(mode=="JOURNEY_PLAN")
                    journeyDraft.toList() else journeyStore.ids()
                // Plan line is a straight visual sequence, NOT a navigable road.
                // Draw beneath all objective markers to keep numbers readable.
                if(journeyIds.size>=2) {
                    val objectiveById=all.associateBy{it.id}
                    val points=journeyIds.mapNotNull{id->
                        objectiveById[id]?.let{o->
                            mapView.projection.toPixels(GeoPoint(o.latitude,o.longitude),Point())
                        }
                    }
                    val dark=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color=Color.argb(235,15,20,21)
                        strokeWidth=dp(8).toFloat()
                        strokeCap=Paint.Cap.ROUND
                        style=Paint.Style.STROKE
                    }
                    val bright=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color=ExpeditionThemes.current.gold
                        strokeWidth=dp(3).toFloat()
                        strokeCap=Paint.Cap.ROUND
                        style=Paint.Style.STROKE
                        pathEffect=android.graphics.DashPathEffect(
                            floatArrayOf(dp(11).toFloat(),dp(6).toFloat()),0f)
                    }
                    points.zipWithNext().forEach{(a,b)->
                        val ax=a.x.toFloat();val ay=a.y.toFloat()
                        val bx=b.x.toFloat();val by=b.y.toFloat()
                        canvas.drawLine(ax,ay,bx,by,dark)
                        canvas.drawLine(ax,ay,bx,by,bright)
                        val length=kotlin.math.hypot((bx-ax).toDouble(),
                            (by-ay).toDouble()).toFloat()
                        if(length>dp(24)) {
                            val vx=(bx-ax)/length;val vy=(by-ay)/length
                            val midX=ax+(bx-ax)*.58f
                            val midY=ay+(by-ay)*.58f
                            val size=dp(8).toFloat()
                            val arrow=android.graphics.Path().apply {
                                moveTo(midX+vx*size,midY+vy*size)
                                lineTo(midX-vx*size*.7f-vy*size*.7f,
                                    midY-vy*size*.7f+vx*size*.7f)
                                lineTo(midX-vx*size*.7f+vy*size*.7f,
                                    midY-vy*size*.7f-vx*size*.7f)
                                close()
                            }
                            val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color=ExpeditionThemes.current.light
                                style=Paint.Style.FILL
                            }
                            canvas.drawPath(arrow,fill)
                        }
                    }
                }
                val occupied=mutableListOf<Pair<Float,Float>>()
                val rendered=ArrayList<ReconObjective>()
                all.forEach {o->
                    val location=if(o.id==movingObjectiveId) movingPreview else null
                    val p=mapView.projection.toPixels(location ?: GeoPoint(o.latitude,o.longitude),Point())
                    val x=p.x.toFloat();val y=p.y.toFloat()
                    val selected=objectiveStore.active==o.id || pendingObjective?.id==o.id
                    val journeyIndex=journeyIds.indexOf(o.id)
                    val inJourney=journeyIndex>=0
                    if(x<0f||y<0f||x>width||y>height) {
                        if(selected) {
                            val cx=width/2f;val cy=height/2f
                            val dx=x-cx;val dy=y-cy
                            val factor=minOf((width/2f-dp(25))/maxOf(kotlin.math.abs(dx),1f),(height/2f-dp(35))/maxOf(kotlin.math.abs(dy),1f))
                            painter.edge(canvas,cx+dx*factor,cy+dy*factor,kotlin.math.atan2(dy.toDouble(),dx.toDouble()),true)
                        }
                    } else {
                        // All Journey stops remain visible at low zoom; other
                        // icons are de-cluttered as before.
                        if(mapView.zoomLevelDouble<12.0 && !selected && !inJourney &&
                            !o.id.startsWith("world_") && !o.id.startsWith("osm_"))
                            return@forEach
                        val spacing=if(mapView.zoomLevelDouble<15.0)dp(66) else dp(43)
                        if(!selected && !inJourney &&
                            occupied.any{kotlin.math.hypot((it.first-x).toDouble(),(it.second-y).toDouble())<spacing})
                            return@forEach
                        val distance=user?.let {
                            val m=FloatArray(1)
                            android.location.Location.distanceBetween(it.latitude,it.longitude,o.latitude,o.longitude,m)
                            if(m[0]<1000f)"${m[0].toInt()} m" else "%.1f km".format(java.util.Locale.US,m[0]/1000f)
                        }
                        val overlap=occupied.any{kotlin.math.hypot((it.first-x).toDouble(),(it.second-y).toDouble())<dp(43)}
                        val showDistance=selected || (distance!=null && !overlap && mapView.zoomLevelDouble>=15.5)
                        painter.draw(canvas,x,y,o.category,selected,objectiveStore.completed(o.id),false,if(showDistance)distance else null)
                        rendered.add(o)
                        if(inJourney) {
                            // Ordered Journey badge: the line between stops is
                            // intentionally NOT painted as a navigable road.
                            val bx=x+dp(14)
                            val by=y-dp(19)
                            val r=dp(10).toFloat()
                            val back=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color=ExpeditionThemes.current.dark
                                style=Paint.Style.FILL
                            }
                            val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color=ExpeditionThemes.current.gold
                                style=Paint.Style.STROKE
                                strokeWidth=dp(2).toFloat()
                            }
                            val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color=ExpeditionThemes.current.light
                                textSize=dp(10).toFloat()
                                typeface=Typeface.DEFAULT_BOLD
                                textAlign=Paint.Align.CENTER
                                style=Paint.Style.FILL
                            }
                            canvas.drawCircle(bx,by,r,back)
                            canvas.drawCircle(bx,by,r,edge)
                            canvas.drawText((journeyIndex+1).toString(),bx,by+dp(3),ink)
                        }
                        occupied.add(x to y)
                    }
                }
                drawnObjectiveTargets=rendered
            }
            override fun onSingleTapConfirmed(event:MotionEvent,mapView:MapView):Boolean {
                if(!objectiveStore.enabled || mode!="VIEW")return false
                val hit=drawnObjectiveTargets.minByOrNull{o->
                    val p=mapView.projection.toPixels(GeoPoint(o.latitude,o.longitude),Point())
                    kotlin.math.hypot((p.x-event.x).toDouble(),(p.y-event.y).toDouble())
                }?.takeIf{o->
                    val p=mapView.projection.toPixels(GeoPoint(o.latitude,o.longitude),Point())
                    kotlin.math.hypot((p.x-event.x).toDouble(),(p.y-event.y).toDouble())<dp(26)
                } ?:return false
                if(measure)recordRulerPoint(GeoPoint(hit.latitude,hit.longitude))
                else showObjective(hit)
                return true
            }
            override fun onLongPress(event:MotionEvent,mapView:MapView):Boolean {
                if(mode!="VIEW")return false
                val hit=drawnObjectiveTargets.minByOrNull{o->
                    val p=mapView.projection.toPixels(GeoPoint(o.latitude,o.longitude),Point())
                    kotlin.math.hypot((p.x-event.x).toDouble(),(p.y-event.y).toDouble())
                }?.takeIf{o->
                    val p=mapView.projection.toPixels(GeoPoint(o.latitude,o.longitude),Point())
                    kotlin.math.hypot((p.x-event.x).toDouble(),(p.y-event.y).toDouble())<dp(27)
                } ?:return false
                if(measure)recordRulerPoint(GeoPoint(hit.latitude,hit.longitude))
                else showObjective(hit)
                return true
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
                if(shadow || !layerVisible("METRO"))return
                val painter=markerArt
                metroStations.forEach {station->
                    val p=mapView.projection.toPixels(GeoPoint(station.lat,station.lon),Point())
                    if(p.x>=0 && p.y>=0 && p.x<canvas.width && p.y<canvas.height)
                        painter.draw(canvas,p.x.toFloat(),p.y.toFloat(),"TRANSIT",false,false,false)
                }
            }
            override fun onSingleTapConfirmed(e:MotionEvent,mapView:MapView):Boolean {
                if(mode!="VIEW" || !layerVisible("METRO"))return false
                val hit=metroStations.firstOrNull {station->
                    val p=mapView.projection.toPixels(GeoPoint(station.lat,station.lon),Point())
                    kotlin.math.hypot((e.x-p.x).toDouble(),(e.y-p.y).toDouble())<dp(20)
                } ?: return false
                if(measure)recordRulerPoint(GeoPoint(hit.lat,hit.lon))
                else showTransitStation(hit)
                return true
            }
        })
        // OpenStreetMap shopping destinations: landmark malls appear at
        // city scale, mapped community centres become visible when zoomed in.
        // Only cached POIs are drawn; no network or geometry work per frame.
        map.overlays.add(object:Overlay() {
            private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(shadow || !layerVisible("MALLS"))return
                val visible=visibleMalls(mapView)
                val density=resources.displayMetrics.density
                for(mall in visible) {
                    val at=mapView.projection.toPixels(
                        GeoPoint(mall.latitude,mall.longitude),Point())
                    val x=at.x.toFloat()
                    val y=at.y.toFloat()
                    val featured=MallCatalog.featured(mall.name)
                    val size=(if(featured)17f else 14f)*density
                    val border=if(featured)3f*density else 2.4f*density
                    val fill=ExpeditionThemes.current.dark
                    val outline=if(featured)ExpeditionThemes.current.gold
                        else ExpeditionThemes.current.light
                    // Diamond marker, high contrast on both bright map tiles
                    // and dark fog. Full fill prevents icons disappearing
                    // against satellite imagery.
                    val diamond=android.graphics.Path().apply{
                        moveTo(x,y-size)
                        lineTo(x+size,y)
                        lineTo(x,y+size)
                        lineTo(x-size,y)
                        close()
                    }
                    ink.style=Paint.Style.FILL
                    ink.color=fill
                    canvas.drawPath(diamond,ink)
                    ink.style=Paint.Style.STROKE
                    ink.strokeWidth=border
                    ink.strokeJoin=Paint.Join.ROUND
                    ink.color=outline
                    canvas.drawPath(diamond,ink)
                    // Simple central facet differentiates it from objective
                    // diamonds and makes smaller community malls legible.
                    ink.style=Paint.Style.FILL
                    ink.color=outline
                    val facet=size*.28f
                    canvas.drawRect(x-facet,y-facet,x+facet,y+facet,ink)

                    // Names appear earlier at mid-range zoom. Avoid labels
                    // at country/city zoom where they would overlap.
                    if(mapView.zoomLevelDouble>=14.4 ||
                        (featured && mapView.zoomLevelDouble>=13.4)) {
                        val title=mall.name.take(27)
                        ink.style=Paint.Style.FILL
                        ink.textSize=10f*density
                        ink.typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
                        ink.textAlign=Paint.Align.CENTER
                        val half=ink.measureText(title)/2f+6f*density
                        ink.color=ExpeditionThemes.current.dark
                        canvas.drawRoundRect(x-half,y+size+4f*density,
                            x+half,y+size+20f*density,
                            4f*density,4f*density,ink)
                        ink.color=ExpeditionThemes.current.light
                        canvas.drawText(title,x,y+size+15f*density,ink)
                    }
                }
            }
            override fun onSingleTapConfirmed(e:MotionEvent,mapView:MapView):Boolean {
                if(mode!="VIEW" || !layerVisible("MALLS"))return false
                val hit=visibleMalls(mapView).minByOrNull {mall->
                    val at=mapView.projection.toPixels(
                        GeoPoint(mall.latitude,mall.longitude),Point())
                    kotlin.math.hypot((at.x-e.x).toDouble(),(at.y-e.y).toDouble())
                } ?:return false
                val at=mapView.projection.toPixels(
                    GeoPoint(hit.latitude,hit.longitude),Point())
                if(kotlin.math.hypot((at.x-e.x).toDouble(),(at.y-e.y).toDouble())>dp(38))
                    return false
                if(measure)recordRulerPoint(GeoPoint(hit.latitude,hit.longitude))
                else showMallPlace(hit)
                return true
            }
        })
        map.overlays.add(object:Overlay() {
            private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(shadow || !taxiStandsEnabled || mapView.zoomLevelDouble<14.8)return
                val scale=resources.displayMetrics.density
                taxiStands.forEach {stand->
                    val at=mapView.projection.toPixels(
                        GeoPoint(stand.latitude,stand.longitude),Point())
                    val x=at.x.toFloat();val y=at.y.toFloat()
                    if(x<0f || y<0f || x>canvas.width || y>canvas.height)return@forEach
                    // Square amber taxi-sign marker: distinct from blue Metro
                    // circles and gold objective diamonds.
                    ink.style=Paint.Style.FILL
                    ink.color=Color.rgb(28,33,25)
                    val w=14f*scale
                    canvas.drawRoundRect(x-w,y-w,x+w,y+w,5f*scale,5f*scale,ink)
                    ink.style=Paint.Style.STROKE
                    ink.strokeWidth=1.6f*scale
                    ink.color=Color.rgb(238,193,94)
                    canvas.drawRoundRect(x-w,y-w,x+w,y+w,5f*scale,5f*scale,ink)
                    // A clean taxi roof sign and wheels, not an animated car.
                    ink.strokeWidth=1.7f*scale
                    canvas.drawLine(x-7f*scale,y+3f*scale,x-5f*scale,y-3f*scale,ink)
                    canvas.drawLine(x-5f*scale,y-3f*scale,x+5f*scale,y-3f*scale,ink)
                    canvas.drawLine(x+5f*scale,y-3f*scale,x+7f*scale,y+3f*scale,ink)
                    canvas.drawLine(x-8f*scale,y+3f*scale,x+8f*scale,y+3f*scale,ink)
                    canvas.drawLine(x-6f*scale,y+3f*scale,x-6f*scale,y+6f*scale,ink)
                    canvas.drawLine(x+6f*scale,y+3f*scale,x+6f*scale,y+6f*scale,ink)
                    ink.style=Paint.Style.FILL
                    ink.typeface=Typeface.create("sans-serif-condensed",Typeface.BOLD)
                    ink.textAlign=Paint.Align.CENTER
                    ink.textSize=6.5f*scale
                    canvas.drawText("TAXI",x,y-6f*scale,ink)
                }
            }
            override fun onSingleTapConfirmed(event:MotionEvent,mapView:MapView):Boolean {
                if(!taxiStandsEnabled || mode!="VIEW" || mapView.zoomLevelDouble<14.8)return false
                val hit=taxiStands.firstOrNull{stand->
                    val at=mapView.projection.toPixels(
                        GeoPoint(stand.latitude,stand.longitude),Point())
                    kotlin.math.hypot((event.x-at.x).toDouble(),
                        (event.y-at.y).toDouble())<dp(20)
                } ?: return false
                if(measure)recordRulerPoint(GeoPoint(hit.latitude,hit.longitude))
                else showTaxiStand(hit)
                return true
            }
        })
        map.overlays.add(object:Overlay() {
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(shadow || !layerVisible("ANCHORS"))return
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
        // Relocation gesture has priority over map pan, markers and radial tools.
        // The dragged pin follows the finger; neither GPS data nor the original
        // saved location is changed until CONFIRM is pressed.
        map.overlays.add(object:Overlay() {
            private var dragging=false
            override fun onTouchEvent(event:MotionEvent,mapView:MapView):Boolean {
                if(mode!="MOVE" || (movingPinTime==null && movingObjectiveId==null))return false
                if(event.pointerCount>1){dragging=false;return false}
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        dragging=true
                        val p=mapView.projection.fromPixels(event.x.toInt(),event.y.toInt())
                        chooseMoveDestination(GeoPoint(p.latitude,p.longitude))
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if(dragging) {
                            val p=mapView.projection.fromPixels(event.x.toInt(),event.y.toInt())
                            val next=GeoPoint(p.latitude,p.longitude)
                            val prior=movingPreview
                            if(prior==null || gap(prior,next)>1.5f)
                                chooseMoveDestination(next)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if(dragging) {
                            val p=mapView.projection.fromPixels(event.x.toInt(),event.y.toInt())
                            chooseMoveDestination(GeoPoint(p.latitude,p.longitude))
                        }
                        dragging=false
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {dragging=false;return true}
                }
                return true
            }
        })
        // Last drawing layer: user location stays above all other markers.
        map.overlays.add(object:Overlay() {
            override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
                if(!shadow)fogOverlay.drawCurrentPosition(canvas,mapView)
            }
        })
        // The highest-priority tap listener handles temporary map workflows
        // without changing existing single-tap and long-press interactions.
        map.overlays.add(object:Overlay() {
            // Installed last: osmdroid dispatches map taps in reverse overlay
            // order, so selection outranks notes, objectives and transit.
            override fun onSingleTapConfirmed(e:MotionEvent,mapView:MapView):Boolean {
                when(mode) {
                    "SNAP_PICK" -> {
                        val geo=mapView.projection.fromPixels(e.x.toInt(),e.y.toInt())
                        saveSnapAt(GeoPoint(geo.latitude,geo.longitude))
                        return true
                    }
                    "JOURNEY_PLAN" -> {
                        toggleJourneyStopOnMap(e.x,e.y,mapView)
                        return true
                    }
                }
                return false
            }
            override fun onLongPress(e:MotionEvent,mapView:MapView):Boolean =
                MapTouchPolicy.usesTapToSelect(mode)
        })
        val mapArea=FrameLayout(this)
        mapArea.addView(map,FrameLayout.LayoutParams(-1,-1))
        // Always-visible ruler overlay with clear and exit actions. It does not
        // change saved map data and only occupies a small strip above the map.
        val measureBar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            visibility=View.GONE
            setPadding(dp(8),0,dp(4),0)
            background=GradientDrawable().apply {
                cornerRadius=dp(9).toFloat()
                setColor(Color.argb(239,23,34,27))
                setStroke(dp(1),Color.rgb(218,181,113))
            }
        }
        rulerBar=measureBar
        val rulerText=label("RULER  /  SELECT START",11,sand).apply{
            isSingleLine=true
            ellipsize=android.text.TextUtils.TruncateAt.END
        }
        rulerCaption=rulerText
        measureBar.addView(rulerText,LinearLayout.LayoutParams(0,-2,1f))
        measureBar.addView(action("CLEAR").apply{
            textSize=10f
            setOnClickListener {
                measurePoints.clear()
                updateRulerCaption()
                refresh()
            }
        },LinearLayout.LayoutParams(dp(55),dp(36)).apply{rightMargin=dp(5)})
        measureBar.addView(action("× EXIT").apply{
            textSize=10f
            setOnClickListener{toggleRuler(false)}
        },LinearLayout.LayoutParams(dp(60),dp(36)))
        mapArea.addView(measureBar,FrameLayout.LayoutParams(-1,dp(47),Gravity.TOP).apply{
            setMargins(dp(8),dp(7),dp(8),0)
        })
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
        previewControls.addView(action("⌖ SET OBJECTIVE").apply {setOnClickListener {
            val point=previewLocation ?: return@setOnClickListener
            val chosen=pendingObjective
            val target=chosen ?: objectiveStore.addPlace(
                previewLabel.ifBlank{"Map target"},point.latitude,point.longitude,"Map search target")
            objectiveStore.active=target.id
            objectiveStore.enabled=true
            clearMapPreview()
            status.text="ACTIVE OBJECTIVE / "+target.name.take(32)
            refresh()
        }},LinearLayout.LayoutParams(0,dp(48),1.2f))
        previewControls.addView(action("＋ SAVE PIN").apply {setOnClickListener {
            val point=previewLocation ?: return@setOnClickListener
            notes.add(FieldNote(point.latitude,point.longitude,
                previewLabel.ifBlank{"Saved location"},"POI","Saved from map search",
                System.currentTimeMillis(),"◆"))
            fieldNotes.save(notes)
            clearMapPreview()
            status.text="PIN SAVED / "+terms.journal
            refresh()
        }},LinearLayout.LayoutParams(0,dp(48),1f))
        mapArea.addView(previewControls,FrameLayout.LayoutParams(-1,dp(54),Gravity.BOTTOM))
        val movingBar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER
            background=tacticalPanel();visibility=View.GONE
        }
        movePinBar=movingBar
        movingBar.addView(action("× CANCEL").apply {
            setOnClickListener{cancelPinMove()}
        },LinearLayout.LayoutParams(0,dp(49),1f))
        movingBar.addView(action("✓ CONFIRM NEW LOCATION").apply {
            setOnClickListener{savePinMove()}
        },LinearLayout.LayoutParams(0,dp(49),2f))
        // The crosshair follows the touched location, never the map center.
        val moveReticle=object:View(this) {
            private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.rgb(246,211,132);strokeWidth=dp(2).toFloat();style=Paint.Style.STROKE
            }
            override fun onDraw(c:Canvas) {
                super.onDraw(c)
                val target=movingPreview ?: return
                val projected=map.projection.toPixels(target,Point())
                val x=projected.x.toFloat();val y=projected.y.toFloat()
                c.drawCircle(x,y,dp(13).toFloat(),ink)
                c.drawLine(x-dp(23),y,x-dp(16),y,ink)
                c.drawLine(x+dp(16),y,x+dp(23),y,ink)
                c.drawLine(x,y-dp(23),x,y-dp(16),ink)
                c.drawLine(x,y+dp(16),x,y+dp(23),ink)
            }
        }
        pinMoveReticle=moveReticle
        mapArea.addView(moveReticle,FrameLayout.LayoutParams(-1,-1))
        moveReticle.isClickable=false
        mapArea.addView(movingBar,FrameLayout.LayoutParams(-1,dp(54),Gravity.BOTTOM))
        // SNAP placement stays visible as a small high-contrast overlay.
        val snapBar=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(8),dp(5),dp(8),dp(5))
            background=tacticalPanel()
            visibility=View.GONE
        }
        snapPickBar=snapBar
        snapBar.addView(label("SNAP / TAP A MAP POSITION",11,sand),
            LinearLayout.LayoutParams(0,dp(42),1f))
        snapBar.addView(action("× CANCEL").apply {
            applyExpeditionStyle(this)
            setOnClickListener{cancelSnapMapPick()}
        },LinearLayout.LayoutParams(dp(100),dp(40)))
        mapArea.addView(snapBar,FrameLayout.LayoutParams(-1,dp(54),Gravity.BOTTOM))
        // A compact floating Journey planning toolbar, not a modal checklist.
        val planBar=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER_VERTICAL
            background=tacticalPanel()
            setPadding(dp(8),dp(5),dp(8),dp(6))
            visibility=View.GONE
        }
        journeyPlanBar=planBar
        val planLabel=label("",12,sand).apply{
            setTypeface(Typeface.DEFAULT,Typeface.BOLD)
            setSingleLine(true)
            ellipsize=android.text.TextUtils.TruncateAt.END
        }
        journeyPlanSummary=planLabel
        planBar.addView(planLabel,LinearLayout.LayoutParams(-1,dp(30)))
        val planButtons=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        fun plannerButton(title:String,run:()->Unit) {
            planButtons.addView(action(title).apply {
                textSize=10f
                applyExpeditionStyle(this)
                setOnClickListener{run()}
            },LinearLayout.LayoutParams(0,dp(43),1f).apply {
                setMargins(dp(2),0,dp(2),0)
            })
        }
        plannerButton("↕ ORDER"){showJourneyReorder()}
        plannerButton("× CLEAR"){clearJourneyPlan()}
        plannerButton("✓ SAVE"){saveJourneyPlan()}
        plannerButton("EXIT"){finishJourneyPlan(false)}
        planBar.addView(planButtons)
        mapArea.addView(planBar,FrameLayout.LayoutParams(-1,dp(85),Gravity.BOTTOM).apply{
            setMargins(dp(6),0,dp(6),dp(5))
        })
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
        drawerPanel.addView(label(terms.journal,16,sand).apply {
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
        drawerPanel.addView(action("▤  "+terms.journal).apply {
            applyExpeditionStyle(this)
            setOnClickListener{slideJournal(false);showExpeditionJournal()}
        },LinearLayout.LayoutParams(-1,dp(46)))
        drawerPanel.addView(action("⌖  KNOWN LOCATIONS").apply {
            applyExpeditionStyle(this)
            setOnClickListener{slideJournal(false);showKnownLocations()}
        },LinearLayout.LayoutParams(-1,dp(46)))
        drawerPanel.addView(action("◈  "+terms.statistics).apply {
            applyExpeditionStyle(this)
            setOnClickListener{slideJournal(false);showExplorationStats()}
        },LinearLayout.LayoutParams(-1,dp(46)))
        drawerPanel.addView(action("× CLOSE").apply {
            applyExpeditionStyle(this)
            setOnClickListener{slideJournal(false)}
        },LinearLayout.LayoutParams(-1,dp(42)))
        journalDrawer.addView(drawerPanel,FrameLayout.LayoutParams(journalWidth,-2,Gravity.END or Gravity.CENTER_VERTICAL))
        val journalTab=ExpeditionJournalTab(this,
            onToggle={
                val opening=journalDrawer.translationX>journalWidth/2f
                slideJournal(opening)
            },
            onLongPress={
                slideJournal(false)
                showKnownLocations()
            }
        )
        journalTabRef=journalTab
        journalDrawer.addView(journalTab,FrameLayout.LayoutParams(journalTabWidth,dp(90),Gravity.START or Gravity.CENTER_VERTICAL))
        journalDrawer.translationX=journalWidth.toFloat()
        mapArea.addView(journalDrawer,FrameLayout.LayoutParams(
            journalWidth+journalTabWidth,dp(272),Gravity.END or Gravity.CENTER_VERTICAL))
        // Always accessible on-map compass lock: N = north-up, arrow =
        // follow the phone heading and keep the user centered.
        val headingToggle=action("N").apply {
            gravity=Gravity.CENTER
            textSize=14f
            setSingleLine(false)
            contentDescription="Toggle map orientation: north up or follow phone heading"
            setOnClickListener{toggleHeadingLock()}
        }
        headingButton=headingToggle
        mapArea.addView(headingToggle,
            FrameLayout.LayoutParams(dp(49),dp(49),
                Gravity.TOP or Gravity.END).apply {
                topMargin=dp(58)
                rightMargin=dp(8)
            })
        updateHeadingLockUi()
        root.addView(mapArea,LinearLayout.LayoutParams(-1,0,1f))
        // Hidden adapter buttons preserve existing activity actions; visual chrome is Canvas based.
        trackButton=action("TRACK")
        followButton=action("LOCATE")
        val markButton=action("MARK")
        val snapButton=action("SNAP")
        val eraseButton=action("ERASE")
        val viewButton=action("VIEW")
        lateinit var palette:ExpeditionMarkPanelView
        val controls=ExpeditionBottomView(this){index->
            when(index) {
                0->trackButton.performClick()
                1->followButton.performClick()
                2->snapButton.performClick()
                3->showMapLookup()
                4->markButton.performClick()
                5->eraseButton.performClick()
                6->viewButton.performClick()
            }
        }
        expeditionBottom=controls
        controls.selected=-1
        controls.trackingActive=tracking
        controls.gpsSearching=tracking && fogOverlay.currentPosition==null
        fun chooseMark(next:String) {
            fogOverlay.cancelFluidReveal(map)
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
                3->if(mode=="PAINT")chooseMark("VIEW") else chooseMark("PAINT")
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
        // Seven transparent native buttons align with the 7 drawn slots.
        // SEARCH is exactly in the middle (index 3), including for TalkBack.
        val accessibleBottom=FrameLayout(this)
        controls.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        accessibleBottom.addView(controls,FrameLayout.LayoutParams(-1,-1))
        val semanticBar=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
        }
        val descriptions=listOf(
            "Start or stop recording my GPS journey",
            "Find my location and follow me on the map",
            "Capture a quick photo, video or voice note",
            "Search for a city, attraction, address or coordinates",
            "Choose manual map drawing and marking tools",
            "Erase a previously discovered map area",
            "Open application settings and data controls")
        descriptions.forEachIndexed{index,description->
            semanticBar.addView(Button(this).apply{
                text=""
                contentDescription=description
                background=android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                stateListAnimator=null
                minWidth=0;minimumWidth=0
                minHeight=0;minimumHeight=0
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener{controls.onSelect(index)}
            },LinearLayout.LayoutParams(0,-1,1f))
        }
        accessibleBottom.addView(semanticBar,FrameLayout.LayoutParams(-1,-1))
        root.addView(accessibleBottom,LinearLayout.LayoutParams(-1,dp(49)))
        setContentView(root)
        if(metroStations.isEmpty())handler.post{refreshMetro()}
        handler.postDelayed({maybeRecoverPaths()},12000L)
        trackButton.setOnClickListener {
            val tappedAt=android.os.SystemClock.elapsedRealtime()
            if(tappedAt-lastTrackTapElapsed<650L)return@setOnClickListener
            lastTrackTapElapsed=tappedAt
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
            val age=System.currentTimeMillis()-lastKnownFixTime
            val fresh=lastKnownFixTime>0L && age in 0L..120000L
            val point=if(fresh)fogOverlay.currentPosition else null
            if(point!=null) {
                val next=LocateZoomPolicy.next(map.zoomLevelDouble,
                    map.maxZoomLevel.toDouble())
                map.controller.setZoom(next)
                map.controller.animateTo(point)
                status.text="LOCATE / FOLLOW ACTIVE / ZOOM %.1f".format(
                    java.util.Locale.US,next)
            } else locateOnce()
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
            controls.selected=if(opening)4 else -1
            refresh()
        }
        snapButton.setOnClickListener {showSnapOptions()}
        eraseButton.setOnClickListener {
            if(mode!="ERASE"){
                mode="ERASE";palette.visibility=View.GONE
                controls.selected=5;status.text="ERASE / MANUAL MARKS ONLY / GPS SAFE"
            }else{
                mode="VIEW";controls.selected=-1;status.text="MAP READY"
            }
        }
        viewButton.setOnClickListener {showLayers(viewButton)}
        map.addMapListener(object:org.osmdroid.events.MapListener {
            override fun onScroll(event:org.osmdroid.events.ScrollEvent?):Boolean {
                // Dragging the map must not rebuild a blurred discovery mask
                // or queue one bubble calculation per scroll event.
                fogOverlay.onMapPanned()
                handler.removeCallbacks(repositionBubbleAfterPan)
                handler.postDelayed(repositionBubbleAfterPan,85L)
                scheduleTaxiStandsRefresh()
                scheduleMallRefresh()
                scheduleGlobalObjectiveRefresh()
                return false
            }
            override fun onZoom(event:org.osmdroid.events.ZoomEvent?):Boolean {
                fogOverlay.onMapPanned()
                handler.removeCallbacks(repositionBubbleAfterPan)
                handler.postDelayed(repositionBubbleAfterPan,110L)
                scheduleTaxiStandsRefresh()
                scheduleMallRefresh()
                scheduleGlobalObjectiveRefresh()
                return false
            }
        })
        map.setUseDataConnection(true)
        scheduleGlobalObjectiveRefresh(180L)
        loadDubaiTemperature()
        applyMapSource(getSharedPreferences("terra_map_source",Context.MODE_PRIVATE).getInt("selected",0))
        handler.post{if(!isFinishing && !isDestroyed)showFirstRunGuide()}
    }


    /**
     * SNAP is capture-first, location-second. Never bind a camera capture to
     * a stale phone position, and never silently clear a pending recording.
     * Existing notes, visited fog and verified objectives are not affected.
     */
    private fun persistSnapDraft() {
        val edit=getSharedPreferences("terra_snap_draft_v1",Context.MODE_PRIVATE)
            .edit()
        val draft=snapDraft
        if(draft==null)edit.clear()
        else edit.putString("file",draft.filename)
            .putString("kind",draft.kind).putString("title",draft.title)
        edit.apply()
    }

    private fun showSnapOptions() {
        if(mode=="JOURNEY_PLAN" || mode=="MOVE") {
            status.text="FINISH THE CURRENT MAP EDIT BEFORE SNAP"
            return
        }
        if(snapDraft!=null) {
            showSnapPlacement()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("SNAP / QUICK CAPTURE")
            .setItems(arrayOf("▣  TAKE PHOTO","▸  RECORD SHORT VIDEO",
                "●  RECORD VOICE MEMO")) {_,which->
                when(which) {
                    0->launchSnapCamera("PHOTO")
                    1->launchSnapCamera("VIDEO")
                    2->{
                        snapVoicePermissionPending=true
                        voiceNoteTime=null
                        startVoice(null,forSnap=true)
                    }
                }
            }.setNegativeButton("CANCEL",null).show()
    }

    private fun launchSnapCamera(kind:String) {
        snapCaptureKind=kind
        val video=kind=="VIDEO"
        val collection=if(video)
            android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val stamp=System.currentTimeMillis()
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                "terra_snap_$stamp"+if(video)".mp4" else ".jpg")
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,
                if(video)"video/mp4" else "image/jpeg")
        }
        val uri=contentResolver.insert(collection,values)
        if(uri==null) {
            snapCaptureKind=null
            status.text="SNAP / CAMERA STORAGE UNAVAILABLE"
            return
        }
        snapCameraUri=uri
        try {
            val intent=Intent(if(video)android.provider.MediaStore.ACTION_VIDEO_CAPTURE
                else android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(android.provider.MediaStore.EXTRA_OUTPUT,uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                if(video) {
                    putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT,30)
                    putExtra(android.provider.MediaStore.EXTRA_VIDEO_QUALITY,0)
                    putExtra(android.provider.MediaStore.EXTRA_SIZE_LIMIT,24_000_000L)
                }
            }
            startActivityForResult(intent,if(video)93 else 92)
            status.text="SNAP / "+(if(video)"RECORD VIDEO" else "TAKE PHOTO")
        } catch(error:Exception) {
            runCatching{contentResolver.delete(uri,null,null)}
            snapCameraUri=null
            snapCaptureKind=null
            status.text="SNAP / CAMERA UNAVAILABLE"
        }
    }

    private fun finishSnapCamera(
        requestCode:Int,resultCode:Int,data:Intent?
    ) {
        val uri=if(requestCode==93) data?.data?:snapCameraUri else snapCameraUri
        val storedUri=snapCameraUri
        if(resultCode==RESULT_OK && uri!=null) {
            val video=requestCode==93
            val file=File(filesDir,"snap_"+System.currentTimeMillis()+
                if(video)".mp4" else ".jpg")
            runCatching {
                contentResolver.openInputStream(uri)?.use {input->
                    file.outputStream().use{output->
                        val buffer=ByteArray(8192)
                        var total=0L
                        while(true) {
                            val n=input.read(buffer)
                            if(n<0)break
                            total+=n
                            require(total<=24_000_000L) {"SNAP too large"}
                            output.write(buffer,0,n)
                        }
                    }
                } ?:error("SNAP camera file unavailable")
                require(file.length()>0L) {"SNAP empty"}
                snapDraft=SnapDraft(file.name,if(video)"VIDEO" else "PHOTO",
                    if(video)"Quick video" else "Quick photo")
                persistSnapDraft()
                status.text="SNAP CAPTURED / SELECT LOCATION"
                showSnapPlacement()
            }.onFailure {
                file.delete()
                status.text="SNAP / SAVE FAILED"
            }
        } else status.text="SNAP / CAPTURE CANCELLED"
        storedUri?.let{runCatching{contentResolver.delete(it,null,null)}}
        snapCameraUri=null
        snapCaptureKind=null
    }

    private fun showSnapPlacement() {
        val draft=snapDraft?:return
        val loc=fogOverlay.currentPosition
        val fresh=SnapCapture.canUseMyLocation(System.currentTimeMillis(),
            lastKnownFixTime,fogOverlay.positionAccuracy,loc!=null)
        android.app.AlertDialog.Builder(this)
            .setTitle("SNAP / CHOOSE LOCATION")
            .setMessage(draft.title+" captured. Choose where to save it in "+
                terms.journal+". No pin will be created until you choose.")
            .setItems(arrayOf(
                if(fresh)"⌖  MY CURRENT GPS LOCATION"
                    else "⌖  MY LOCATION (WAITING FOR FRESH GPS)",
                "◎  PICK A POSITION ON THE MAP",
                "▣  KEEP CAPTURE FOR LATER"
            )) {_,which->
                when(which) {
                    0 -> {
                        if(fresh && loc!=null)saveSnapAt(loc)
                        else {
                            status.text="SNAP / FRESH GPS REQUIRED / PICK ON MAP"
                            beginSnapMapPick()
                        }
                    }
                    1 ->beginSnapMapPick()
                    2 ->status.text="SNAP SAVED AS DRAFT / TAP SNAP TO PLACE"
                }
            }
            .setNegativeButton("DISCARD") {_,_->discardSnap()}
            .setCancelable(false).show()
    }

    private fun beginSnapMapPick() {
        if(snapDraft==null)return
        radialMenu?.dismiss()
        snapPickingMap=true
        mode="SNAP_PICK"
        followUser=false
        snapPickBar?.visibility=View.VISIBLE
        status.text="SNAP / TAP MAP TO PLACE THE CAPTURE"
        refresh()
    }

    private fun cancelSnapMapPick() {
        snapPickingMap=false
        mode="VIEW"
        snapPickBar?.visibility=View.GONE
        showSnapPlacement()
        refresh()
    }

    private fun saveSnapAt(point:GeoPoint) {
        val draft=snapDraft?:return
        val file=File(filesDir,draft.filename)
        if(!file.isFile || file.length()==0L) {
            discardSnap()
            status.text="SNAP / FILE MISSING"
            return
        }
        val icon=when(draft.kind) {
            "VIDEO"->"▶"
            "VOICE"->"●"
            else->"▣"
        }
        val note=FieldNote(point.latitude,point.longitude,draft.title,
            draft.kind,"",System.currentTimeMillis(),icon,
            listOf(draft.filename))
        notes.add(note)
        fieldNotes.save(notes)
        snapDraft=null
        persistSnapDraft()
        snapPickingMap=false
        snapPickBar?.visibility=View.GONE
        mode="VIEW"
        status.text="SNAP / "+draft.kind+" ADDED TO "+terms.journal
        refresh()
        showExisting(note)
    }

    private fun discardSnap() {
        snapDraft?.let{File(filesDir,it.filename).delete()}
        snapDraft=null
        persistSnapDraft()
        snapPickingMap=false
        snapPickBar?.visibility=View.GONE
        if(mode=="SNAP_PICK")mode="VIEW"
        status.text="SNAP / DISCARDED"
        refresh()
    }

    private fun showMapPhotoOptions(where:GeoPoint) {
        android.app.AlertDialog.Builder(this)
            .setTitle("PHOTO / FIELD LOCATION")
            .setItems(arrayOf("TAKE PHOTO", "ATTACH FROM GALLERY")) {_,choice->
                attachmentPoint=where
                if(choice==1) {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type="image/*"
                    },82)
                } else {
                    val values=android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                            "terra_photo_"+System.currentTimeMillis()+".jpg")
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                    }
                    val uri=contentResolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)
                    if(uri==null) {
                        attachmentPoint=null
                        status.text="PHOTO / STORAGE UNAVAILABLE"
                    } else {
                        cameraUri=uri
                        try {
                            startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                                putExtra(android.provider.MediaStore.EXTRA_OUTPUT,uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            },88)
                        } catch(e:Exception) {
                            runCatching{contentResolver.delete(uri,null,null)}
                            cameraUri=null;attachmentPoint=null
                            status.text="PHOTO / CAMERA UNAVAILABLE"
                        }
                    }
                }
            }.setNegativeButton("CANCEL",null).show()
    }

    private fun showRadialMenu(x:Int,y:Int,where:GeoPoint) {
        if(!MapTouchPolicy.canOpenRadialMenu(mode))return
        radialMenu?.dismiss()
        val size=dp(256)
        val ring=RadialMenuView(this) { choice ->
            radialMenu?.dismiss()
            when(choice) {
                0 -> showNoteEditor(where,"CHECKPOINT")
                1 -> showMapPhotoOptions(where)
                2 -> showMapLookup()
                3 -> {
                    notes.add(FieldNote(where.latitude,where.longitude,"Quick pin","POI","",System.currentTimeMillis(),"◆"))
                    fieldNotes.save(notes);status.text="QUICK PIN SAVED";refresh()
                }
                4 -> toggleRuler(!measure)
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
        val ink=Color.rgb(67,48,28)
        val sepia=Color.rgb(118,84,53)
        val layout=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(17),dp(16),dp(17),dp(15))
            background=ExpeditionParchment()
        }
        layout.addView(label(terms.journal+" / NEW "+kind,13,ink).apply {
            setTypeface(null,Typeface.BOLD)
        })
        val preview=label("◆  SELECT MARKER",14,sepia)
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
            hint="Name / callsign";setTextColor(ink);setHintTextColor(sepia)
            textSize=15f;setSingleLine(true)
        }
        val detail=EditText(this).apply {
            hint="Add a note (optional)";minLines=2;maxLines=4
            setTextColor(ink);setHintTextColor(sepia);textSize=14f
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

    private fun photoFiles(note:FieldNote):List<String> =
        (note.attachments+
            (if(note.kind=="PHOTO" && SnapCapture.isPhoto(note.body))
                listOf(note.body) else emptyList()))
            .distinct().filter{SnapCapture.isPhoto(it) && File(filesDir,it).isFile}

    private fun videoFiles(note:FieldNote):List<String> =
        note.attachments.distinct().filter{
            SnapCapture.isVideo(it) && File(filesDir,it).isFile
        }

    private fun voiceFiles(note:FieldNote):List<String> =
        (note.attachments+
            (if(note.kind=="VOICE" && SnapCapture.isVoice(note.body))
                listOf(note.body) else emptyList()))
            .distinct().filter{SnapCapture.isVoice(it) && File(filesDir,it).isFile}

    private var audioPlayback:MediaPlayer?=null
    private var activeVoiceMeterTick:Runnable?=null
    private fun playVoiceMemo(filename:String) {
        audioPlayback?.let{runCatching{it.stop()};it.release()}
        audioPlayback=null
        val file=File(filesDir,filename)
        if(!file.isFile) {status.text="VOICE MEMO / FILE MISSING";return}
        runCatching {
            audioPlayback=MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener{it.release();audioPlayback=null}
                prepare();start()
            }
            status.text="VOICE MEMO / PLAYING"
        }.onFailure {status.text="VOICE MEMO / PLAYBACK FAILED"}
    }

    private fun playSnapVideo(filename:String) {
        val file=File(filesDir,filename)
        if(!file.isFile)return
        val uri=androidx.core.content.FileProvider.getUriForFile(
            this,packageName+".fileprovider",file)
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri,"video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure{status.text="VIDEO / NO PLAYER AVAILABLE"}
    }

    /** Decode a suitably sampled, EXIF-correct image rather than a full-res bitmap. */
    private fun loadOrientedPhoto(file:File,maxDimension:Int=1400):android.graphics.Bitmap? {
        if(!file.isFile)return null
        return runCatching {
            val bounds=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
            android.graphics.BitmapFactory.decodeFile(file.absolutePath,bounds)
            var sample=1
            while(bounds.outWidth/sample>maxDimension*2 ||
                bounds.outHeight/sample>maxDimension*2) sample*=2
            val options=android.graphics.BitmapFactory.Options().apply {
                inSampleSize=sample
                inPreferredConfig=android.graphics.Bitmap.Config.RGB_565
            }
            val bitmap=android.graphics.BitmapFactory.decodeFile(file.absolutePath,options)
                ?: return@runCatching null
            val exif=android.media.ExifInterface(file.absolutePath)
            val angle=when(exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1)) {
                6 -> 90f
                3 -> 180f
                8 -> 270f
                else -> 0f
            }
            if(angle==0f)bitmap else android.graphics.Bitmap.createBitmap(
                bitmap,0,0,bitmap.width,bitmap.height,
                android.graphics.Matrix().apply{postRotate(angle)},true)
        }.getOrNull()
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
        globalObjectiveStore.remember(o)
        // The MISSIONS list only flies to the target. Its icon opens the detail actions.
        clearMapPreview()
        followUser=false
        if(extent && map.zoomLevelDouble<17.0)map.controller.setZoom(17.0)
        map.controller.animateTo(GeoPoint(o.latitude,o.longitude))
        status.text=terms.single+" / "+o.name.take(36)
        refresh()
        // Details stay attached to the marker and open only when it is tapped.
    }
    private fun showObjectiveHint(o:ReconObjective) { showObjective(o) }
    private fun clearMapPreview() {
        previewLocation=null;previewLabel="";pendingObjective=null
        previewBar?.visibility=View.GONE
        refresh()
    }
    private fun tacticalPanel():GradientDrawable = GradientDrawable().apply {
        cornerRadius=dp(16).toFloat()
        val military=ExpeditionThemes.current.id=="MILITARY"
        setColor(if(military)Color.argb(238,38,47,38)
            else ExpeditionThemes.current.surface)
        setStroke(dp(1),if(military)Color.argb(190,155,181,134)
            else ExpeditionThemes.current.gold)
    }
    private data class MapPlaceSuggestion(
        val title:String,val subtitle:String,val lat:Double,val lon:Double
    )

    private fun fetchMapSuggestions(query:String):List<MapPlaceSuggestion> {
        val pos=if(::fogOverlay.isInitialized)fogOverlay.currentPosition else null
        val lat=pos?.latitude ?: map.mapCenter.latitude
        val lon=pos?.longitude ?: map.mapCenter.longitude
        val url="https://photon.komoot.io/api/?q="+
            java.net.URLEncoder.encode(query,"UTF-8")+
            "&limit=5&lang=en&lat="+lat+"&lon="+lon
        val conn=(java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout=8000
            readTimeout=8000
            setRequestProperty("User-Agent","TerraUnlocked/0.4 (Android; OSM Photon search)")
        }
        try {
            require(conn.responseCode==200){"Search unavailable"}
            val featureList=org.json.JSONObject(conn.inputStream.bufferedReader().use{it.readText()})
                .optJSONArray("features") ?: return emptyList()
            return (0 until featureList.length()).mapNotNull {i->
                val entry=featureList.optJSONObject(i) ?: return@mapNotNull null
                val coords=entry.optJSONObject("geometry")?.optJSONArray("coordinates")
                    ?: return@mapNotNull null
                if(coords.length()<2)return@mapNotNull null
                val x=coords.optDouble(0,Double.NaN)
                val y=coords.optDouble(1,Double.NaN)
                if(!x.isFinite() || !y.isFinite() || x !in -180.0..180.0 ||
                    y !in -90.0..90.0)return@mapNotNull null
                val p=entry.optJSONObject("properties") ?: return@mapNotNull null
                val name=p.optString("name").ifBlank {p.optString("street")}
                    .ifBlank {p.optString("city")}.ifBlank{"Unnamed location"}
                val address=listOf(p.optString("housenumber"),p.optString("street"),
                    p.optString("district"),p.optString("city"),p.optString("country"))
                    .filter{it.isNotBlank()}.distinct().joinToString(", ")
                MapPlaceSuggestion(name,address,y,x)
            }.distinctBy{it.title to it.lat}
        } finally {conn.disconnect()}
    }

    private fun showMapLookup() {
        clearMapPreview()
        mapSearchGeneration++
        val box=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(12),dp(12),dp(12))
            background=tacticalPanel()
        }
        box.addView(label("⌕ MAP / LOCATION SEARCH",13,sand))
        val query=EditText(this).apply {
            hint="Search address, place, coordinates or link..."
            setSingleLine(true)
            setTextColor(sand)
            setHintTextColor(sage)
            textSize=14f
        }
        box.addView(query,LinearLayout.LayoutParams(-1,dp(49)))
        val feedback=label("Type 3+ letters for location suggestions",11,sage)
        box.addView(feedback)
        val results=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        box.addView(android.widget.ScrollView(this).apply {
            isFillViewport=false
            addView(results)
        },LinearLayout.LayoutParams(-1,0,1f))
        box.addView(label("Search data: © OpenStreetMap contributors · Photon",9,sage))
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        box.addView(actions)
        val popup=PopupWindow(box,dp(330).coerceAtMost(resources.displayMetrics.widthPixels-dp(16)),
            dp(365).coerceAtMost(resources.displayMetrics.heightPixels-dp(160)),true).apply {
            isOutsideTouchable=true
            inputMethodMode=PopupWindow.INPUT_METHOD_NEEDED
            softInputMode=android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(7).toFloat()
        }
        var pending:Runnable?=null
        fun locate() {
            val value=query.text.toString().trim()
            if(value.isNotEmpty()) {
                pending?.let{handler.removeCallbacks(it)}
                mapSearchGeneration++
                popup.dismiss()
                resolveMapLocation(value)
            }
        }
        actions.addView(action("⌕ SEARCH").apply{setOnClickListener{locate()}},
            LinearLayout.LayoutParams(0,dp(45),1f))
        actions.addView(action("▣ PASTE").apply{setOnClickListener{
            val clipboard=getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val content=clipboard.primaryClip?.getItemAt(0)
                ?.coerceToText(this@MainActivity)?.toString()
            if(content.isNullOrBlank())status.text="CLIPBOARD / EMPTY"
            else {
                query.setText(content)
                query.setSelection(query.length())
                // Preserve the existing coordinate / Google Maps URL handling.
                locate()
            }
        }},LinearLayout.LayoutParams(0,dp(45),1f))
        actions.addView(action("× CANCEL").apply{setOnClickListener{popup.dismiss()}},
            LinearLayout.LayoutParams(0,dp(45),1f))
        query.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(t:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(t:CharSequence?,start:Int,before:Int,count:Int){
                pending?.let{handler.removeCallbacks(it)}
                val text=t?.toString()?.trim().orEmpty()
                val generation=++mapSearchGeneration
                results.removeAllViews()
                if(text.length<3 || text.startsWith("http",true) ||
                   text.matches(Regex("""^-?\d+(\.\d+)?\s*[, ]\s*-?\d+(\.\d+)?$"""))) {
                    feedback.text="Type 3+ letters for suggestions, or press SEARCH"
                    return
                }
                feedback.text="SEARCH / FINDING PLACES..."
                // Public Photon is a demo service: debounce and cap at 1 request/sec.
                val delay=maxOf(800L,1000L-(System.currentTimeMillis()-mapSearchLastRequest))
                val work=Runnable {
                    if(generation!=mapSearchGeneration || !popup.isShowing)return@Runnable
                    mapSearchLastRequest=System.currentTimeMillis()
                    Thread {
                        val response=runCatching{fetchMapSuggestions(text)}
                        runOnUiThread {
                            if(generation!=mapSearchGeneration || !popup.isShowing)return@runOnUiThread
                            results.removeAllViews()
                            response.onSuccess {places->
                                feedback.text=if(places.isEmpty())"No suggestions — try SEARCH"
                                    else "SUGGESTIONS / "+places.size
                                places.forEach {place->
                                    val row=LinearLayout(this@MainActivity).apply{
                                        orientation=LinearLayout.VERTICAL
                                        setPadding(dp(10),dp(8),dp(8),dp(8))
                                        background=GradientDrawable().apply{
                                            cornerRadius=dp(5).toFloat()
                                            setColor(Color.rgb(32,44,35))
                                        }
                                        setOnClickListener {
                                            mapSearchGeneration++
                                            popup.dismiss()
                                            showFoundLocation(
                                                if(place.subtitle.isBlank())place.title
                                                else place.title+", "+place.subtitle,
                                                place.lat,place.lon)
                                        }
                                    }
                                    row.addView(label(place.title,13,sand))
                                    if(place.subtitle.isNotBlank())row.addView(label(place.subtitle,10,sage).apply{
                                        maxLines=2
                                        ellipsize=android.text.TextUtils.TruncateAt.END
                                    })
                                    results.addView(row,LinearLayout.LayoutParams(-1,dp(63)).apply{
                                        bottomMargin=dp(3)
                                    })
                                }
                            }
                            response.onFailure{feedback.text="Suggestions unavailable — SEARCH still works"}
                        }
                    }.start()
                }
                pending=work
                handler.postDelayed(work,delay)
            }
            override fun afterTextChanged(t:android.text.Editable?){}
        })
        popup.setOnDismissListener{
            pending?.let{handler.removeCallbacks(it)}
            mapSearchGeneration++
        }
        popup.showAtLocation(map,Gravity.TOP or Gravity.CENTER_HORIZONTAL,0,dp(112))
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
        followUser=false;followButton.text="LOCATE ○"
        map.controller.setZoom(18.0)
        map.controller.animateTo(previewLocation)
        previewBar?.visibility=View.VISIBLE
        status.text="TARGET PREVIEW / "+name.take(35)
        refresh()
    }
    private fun validObjectiveIds():Set<String> =
        (knownObjectives().map{it.id}+journeyStore.ids()).toSet()

    /**
     * A Journey is one ordered collection of independently verifiable goals.
     * No changes to GPS/photo completion rules or actual road routing.
     */
    /**
     * On-map journey editor: tap objective markers in sequence. Saved objective
     * state and previously planned Journey are untouched until SAVE.
     */
    private fun showJourneyPlanner() {
        objectivesPopup?.dismiss()
        radialMenu?.dismiss()
        if(mode=="SNAP_PICK") {
            status.text="PLACE OR DISCARD YOUR SNAP FIRST"
            return
        }
        if(mode=="MOVE")cancelPinMove()
        journeyDraft.clear()
        val valid=validObjectiveIds()
        journeyDraft.addAll(journeyStore.ids())
        mode="JOURNEY_PLAN"
        followUser=false
        expeditionPalette?.visibility=View.GONE
        journeyPlanBar?.visibility=View.VISIBLE
        snapPickBar?.visibility=View.GONE
        updateJourneyPlanBar()
        status.text="PLAN "+terms.journey+" / TAP DESTINATION ICONS ON MAP"
        refresh()
    }

    private fun updateJourneyPlanBar() {
        val done=journeyDraft.count{objectiveStore.completed(it)}
        journeyPlanSummary?.text=terms.journey+" / "+journeyDraft.size+
            " STOPS / "+done+" DONE  •  TAP ICONS / ORDER"
    }

    private fun toggleJourneyStopOnMap(x:Float,y:Float,mapView:MapView) {
        val candidate=drawnObjectiveTargets
            .map {o->
                val p=mapView.projection.toPixels(GeoPoint(o.latitude,o.longitude),Point())
                val distance=kotlin.math.hypot(
                    (p.x-x).toDouble(),(p.y-y).toDouble())
                o to distance
            }.minByOrNull{it.second}
        if(candidate==null || candidate.second>dp(50)) {
            status.text="TAP DIRECTLY ON A DESTINATION MARKER"
            return
        }
        val selected=candidate.first
        globalObjectiveStore.remember(selected)
        if(selected.id in journeyDraft) {
            journeyDraft.remove(selected.id)
            status.text=terms.journey+" / REMOVED "+selected.name.take(24)
        } else if(journeyDraft.size>=JourneyPlanner.MAX_STOPS) {
            status.text=terms.journey+" / MAXIMUM 40 STOPS"
            return
        } else {
            journeyDraft.add(selected.id)
            status.text=terms.journey+" / STOP "+journeyDraft.size+
                " / "+selected.name.take(24)
        }
        updateJourneyPlanBar()
        refresh()
    }

    private fun finishJourneyPlan(saved:Boolean) {
        mode="VIEW"
        journeyPlanBar?.visibility=View.GONE
        journeyDraft.clear()
        status.text=if(saved)terms.journey+" / READY"
            else terms.journey+" / EDITING CANCELLED"
        refresh()
    }

    private fun saveJourneyPlan() {
        if(journeyDraft.size<2) {
            status.text=terms.journey+" / SELECT AT LEAST TWO STOPS"
            journeyPlanSummary?.text="CHOOSE 2+ DESTINATIONS / CURRENT: "+journeyDraft.size
            return
        }
        journeyStore.save(journeyDraft.toList(),validObjectiveIds(),true)
        objectiveStore.enabled=true
        val next=journeyStore.next{objectiveStore.completed(it)}
        objectiveStore.active=next
        val count=journeyDraft.size
        finishJourneyPlan(true)
        status.text=terms.journey+" STARTED / "+count+" STOPS"
    }

    private fun clearJourneyPlan() {
        if(journeyDraft.isEmpty() && journeyStore.ids().isEmpty())return
        android.app.AlertDialog.Builder(this)
            .setTitle("CLEAR "+terms.journey+"?")
            .setMessage("Remove the planned stop list? Completed missions, "+
                terms.journal+" and revealed map areas will be kept.")
            .setNegativeButton("KEEP",null)
            .setPositiveButton("CLEAR") {_,_->
                journeyDraft.clear()
                journeyStore.clear()
                updateJourneyPlanBar()
                status.text=terms.journey+" / STOPS CLEARED"
                refresh()
            }.show()
    }

    private fun showJourneyReorder() {
        if(mode!="JOURNEY_PLAN")return
        val all=knownObjectives().associateBy{it.id}
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(8),dp(12),dp(10))
            background=tacticalPanel()
        }
        root.addView(label("DRAG AND DROP OR USE ↑ / ↓ TO REORDER",11,sage))
        val rows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val scroller=android.widget.ScrollView(this).apply{addView(rows)}
        root.addView(scroller,LinearLayout.LayoutParams(-1,dp(320)))
        lateinit var render:()->Unit
        render={
            rows.removeAllViews()
            journeyDraft.toList().forEachIndexed{index,id->
                val item=all[id]
                val row=LinearLayout(this).apply {
                    orientation=LinearLayout.HORIZONTAL
                    gravity=Gravity.CENTER_VERTICAL
                    setPadding(dp(4),dp(3),dp(4),dp(3))
                    background=GradientDrawable().apply {
                        cornerRadius=dp(6).toFloat()
                        setColor(ExpeditionThemes.current.surface)
                        setStroke(dp(1),ExpeditionThemes.current.gold)
                    }
                }
                val name=label("${index+1}.  "+(item?.name?:id).take(23),12,sand)
                row.addView(name,LinearLayout.LayoutParams(0,dp(43),1f))
                val handle=action("≡").apply {
                    textSize=20f
                    contentDescription="Drag stop ${index+1} to a new position"
                    setOnLongClickListener {
                        row.startDragAndDrop(android.content.ClipData.newPlainText(
                            "journey_stop",id),View.DragShadowBuilder(row),id,0)
                        true
                    }
                }
                row.addView(handle,LinearLayout.LayoutParams(dp(37),dp(40)))
                row.addView(action("↑").apply {
                    textSize=15f
                    isEnabled=index>0
                    setOnClickListener {
                        if(index>0) {
                            java.util.Collections.swap(journeyDraft,index,index-1)
                            updateJourneyPlanBar();render();refresh()
                        }
                    }
                },LinearLayout.LayoutParams(dp(35),dp(40)))
                row.addView(action("↓").apply {
                    textSize=15f
                    isEnabled=index<journeyDraft.lastIndex
                    setOnClickListener {
                        if(index<journeyDraft.lastIndex) {
                            java.util.Collections.swap(journeyDraft,index,index+1)
                            updateJourneyPlanBar();render();refresh()
                        }
                    }
                },LinearLayout.LayoutParams(dp(35),dp(40)))
                row.addView(action("×").apply {
                    setOnClickListener {
                        journeyDraft.remove(id)
                        updateJourneyPlanBar();render();refresh()
                    }
                },LinearLayout.LayoutParams(dp(35),dp(40)))
                row.setOnLongClickListener {
                    row.startDragAndDrop(android.content.ClipData.newPlainText(
                        "journey_stop",id),View.DragShadowBuilder(row),id,0)
                    true
                }
                row.setOnDragListener{_,event->
                    when(event.action) {
                        android.view.DragEvent.ACTION_DRAG_STARTED->true
                        android.view.DragEvent.ACTION_DROP->{
                            val dragged=event.localState as? String
                            if(dragged!=null && dragged!=id &&
                                dragged in journeyDraft && id in journeyDraft) {
                                journeyDraft.remove(dragged)
                                journeyDraft.add(journeyDraft.indexOf(id),dragged)
                                updateJourneyPlanBar();render();refresh()
                            }
                            true
                        }
                        else->true
                    }
                }
                rows.addView(row,LinearLayout.LayoutParams(-1,dp(50)).apply{
                    bottomMargin=dp(5)
                })
            }
        }
        render()
        val pauseTitle=if(journeyStore.enabled)"PAUSE SAVED PLAN" else "RESUME SAVED PLAN"
        android.app.AlertDialog.Builder(this)
            .setTitle(terms.journey+" / STOP ORDER")
            .setView(root)
            .setNeutralButton(pauseTitle) {_,_->
                if(journeyStore.ids().isNotEmpty()) {
                    journeyStore.enabled=!journeyStore.enabled
                    status.text=terms.journey+
                        (if(journeyStore.enabled)" / RESUMED" else " / PAUSED")
                }
            }
            .setPositiveButton("DONE",null).show()
    }

    private fun focusNextJourneyStop() {
        if(!journeyStore.enabled) {
            status.text=terms.journey+" / START OR RESUME A PLAN FIRST"
            showJourneyPlanner()
            return
        }
        val all=knownObjectives().associateBy{it.id}
        val next=journeyStore.ids().firstOrNull{
            !objectiveStore.completed(it)
        }
        if(next==null) {
            status.text=terms.journey+" / ALL STOPS COMPLETE"
            return
        }
        val o=all[next]
        if(o==null) {
            status.text=terms.journey+
                " / STOP NOT LOADED. RETURN ONLINE OR VIEW SAVED GOALS."
            return
        }
        objectiveStore.active=o.id
        objectivesPopup?.dismiss()
        focusObjective(o,true)
        status.text=terms.journey+" / NEXT: "+o.name.take(26)
    }

    private fun showObjectives() {
        objectivesPopup?.dismiss()
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(11),dp(12),dp(11))
            background=GradientDrawable().apply {
                cornerRadius=dp(13).toFloat()
                setColor(ExpeditionThemes.current.surface)
                setStroke(dp(1),ExpeditionThemes.current.gold)
            }
        }
        panel.addView(label("◎ "+terms.plural+" / "+ExpeditionThemes.current.label.uppercase(),14,sand))
        val journeyInfo=label("",11,sage)
        panel.addView(journeyInfo)
        val planActions=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        planActions.addView(action("◈ "+terms.journey).apply {
            applyExpeditionStyle(this,journeyStore.enabled)
            setOnClickListener {showJourneyPlanner()}
        },LinearLayout.LayoutParams(0,dp(42),1f).apply{
            setMargins(dp(1),dp(4),dp(2),dp(4))
        })
        planActions.addView(action("⌖ NEXT STOP").apply {
            applyExpeditionStyle(this)
            setOnClickListener {focusNextJourneyStop()}
        },LinearLayout.LayoutParams(0,dp(42),1f).apply{
            setMargins(dp(2),dp(4),dp(1),dp(4))
        })
        panel.addView(planActions)
        val search=EditText(this).apply {
            hint="⌕ Search "+terms.plural.lowercase(java.util.Locale.US);setSingleLine(true)
            setTextColor(sand);setHintTextColor(sage);textSize=13f
        }
        panel.addView(search)
        val list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val scroll=android.widget.ScrollView(this).apply {addView(list)}
        panel.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        fun populate(query:String) {
            list.removeAllViews()
            val (completed,total)=journeyStore.progress{objectiveStore.completed(it)}
            journeyInfo.text=if(total==0) "NO "+terms.journey+" / BUILD A MULTI-STOP PLAN"
                else terms.journey+" / "+completed+"/"+total+
                    " COMPLETE / "+(if(journeyStore.enabled)"ACTIVE" else "PAUSED")
            val journeyIndices=if(journeyStore.enabled)journeyStore.ids()
                .withIndex().associate{it.value to it.index} else emptyMap()
            allObjectives()
                .filterNot {objectiveStore.isHidden(it.id)}
                .filter {it.name.contains(query,true)||it.category.contains(query,true)}
                .sortedWith(compareBy<ReconObjective> {
                    if(it.id in journeyIndices)0
                    else if(objectiveStore.completed(it.id))3
                    else if(objectiveStore.active==it.id)1 else 2
                }.thenBy{journeyIndices[it.id]?:Int.MAX_VALUE}.thenBy{it.name})
                .forEach {o ->
                    val state=when {
                        objectiveStore.completed(o.id)->"✓ DONE"
                        o.id in journeyIndices->
                            "◈ STOP "+(journeyIndices.getValue(o.id)+1)
                        objectiveStore.active==o.id->"● ACTIVE"
                        else->"◇ NEW"
                    }
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
        val objectivesWidth=dp(310).coerceAtMost(
            resources.displayMetrics.widthPixels-dp(16))
        val objectivesHeight=dp(480).coerceAtMost(
            (resources.displayMetrics.heightPixels-dp(150)).coerceAtLeast(dp(280)))
        objectivesPopup=PopupWindow(panel,objectivesWidth,objectivesHeight,true).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(8).toFloat()
            showAtLocation(map,Gravity.TOP or Gravity.START,dp(8),dp(84))
        }
    }
    private fun requestObjectiveProofPhoto(o:ReconObjective) {
        if(objectiveStore.completed(o.id)) {
            status.text=terms.single+" ALREADY COMPLETED / "+o.name.take(30)
            return
        }
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            objectivePhotoPermissionId=o.id
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),12)
            return
        }
        status.text=terms.single+" / VERIFYING LIVE GPS..."
        // Never use map center, an old location, or a manually placed marker as proof.
        val token=com.google.android.gms.tasks.CancellationTokenSource()
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,token.token)
            .addOnSuccessListener {fix->
                if(isFinishing || isDestroyed)return@addOnSuccessListener
                if(fix==null || !fix.hasAccuracy() || fix.accuracy>35f ||
                    !fix.latitude.isFinite() || !fix.longitude.isFinite()) {
                    status.text=terms.single+" / GPS ACCURACY REQUIRED (35m OR BETTER)"
                    showGpsProofHelp(o,"Your GPS accuracy is not yet within 35m.")
                    return@addOnSuccessListener
                }
                val fixAgeMillis=(android.os.SystemClock.elapsedRealtimeNanos()-fix.elapsedRealtimeNanos)/1000000L
                if(fixAgeMillis<0L || fixAgeMillis>30000L) {
                    status.text=terms.single+" / WAIT FOR A FRESH GPS FIX"
                    showGpsProofHelp(o,"The location reading is too old.")
                    return@addOnSuccessListener
                }
                val distance=FloatArray(1)
                android.location.Location.distanceBetween(
                    fix.latitude,fix.longitude,o.latitude,o.longitude,distance)
                if(distance[0]>100f) {
                    status.text=terms.single+" LOCKED / "+distance[0].toInt()+"m AWAY / NEED 100m"
                    showGpsProofHelp(o,"You are currently outside the 100m check-in range.")
                    return@addOnSuccessListener
                }
                val values=android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                        "terra_mission_"+System.currentTimeMillis()+".jpg")
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                }
                val uri=contentResolver.insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)
                if(uri==null) {
                    status.text=terms.single+" / CAMERA STORAGE UNAVAILABLE"
                    return@addOnSuccessListener
                }
                cameraUri=uri
                objectivePhotoTargetId=o.id
                try {
                    startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(android.provider.MediaStore.EXTRA_OUTPUT,uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    },87)
                } catch(ex:Exception) {
                    contentResolver.delete(uri,null,null)
                    cameraUri=null
                    objectivePhotoTargetId=null
                    status.text=terms.single+" / CAMERA UNAVAILABLE"
                }
            }.addOnFailureListener {
                status.text=terms.single+" / LIVE GPS UNAVAILABLE"
            }
    }

    private fun confirmDeleteObjective(o:ReconObjective) {
        if(!o.id.startsWith("saved_")) {
            status.text="BUILT-IN "+terms.plural+" CAN BE HIDDEN, NOT DELETED"
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("DELETE "+terms.single+"?")
            .setMessage("Permanently remove \""+o.name+"\" from your saved "+terms.plural.lowercase(java.util.Locale.US)+"? "+
                "This cannot be undone. "+terms.journal+" photos and notes will be preserved.")
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("DELETE") {_,_->
                if(objectiveStore.deleteCustom(o.id)) {
                    journeyStore.save(journeyStore.ids().filterNot{it==o.id},
                        validObjectiveIds(),journeyStore.enabled)
                    if(movingObjectiveId==o.id)cancelPinMove()
                    if(pendingObjective?.id==o.id)clearMapPreview()
                    objectiveBubble?.dismiss()
                    objectivesPopup?.dismiss()
                    status.text=terms.single+" DELETED / "+o.name.take(24)
                    refresh()
                } else status.text=terms.single+" / DELETE FAILED"
            }.show().getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            .setTextColor(Color.rgb(227,128,112))
    }

    private fun showObjective(o:ReconObjective) {
        globalObjectiveStore.remember(o)
        objectiveBubble?.dismiss()
        objectiveBubblePlace=o
        objectiveBubbleBelow=false
        val width=dp(328).coerceAtMost((map.width-dp(24)).coerceAtLeast(dp(220)))
        val stack=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        val body=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(11),dp(10),dp(11),dp(10))
            background=GradientDrawable().apply {
                cornerRadius=dp(11).toFloat()
                setColor(ExpeditionThemes.current.surface)
                setStroke(dp(1),ExpeditionThemes.current.gold)
            }
        }
        val stopIndex=(if(mode=="JOURNEY_PLAN")journeyDraft
            else journeyStore.ids()).indexOf(o.id)
        body.addView(label(if(stopIndex>=0)
            "◈ STOP "+(stopIndex+1)+" / "+terms.journey+
                "   •   "+o.category
            else terms.single+" / "+o.category,11,
            if(stopIndex>=0)ExpeditionThemes.current.gold else sage).apply {
                setTypeface(Typeface.DEFAULT,Typeface.BOLD)
            })
        body.addView(label("CHECK-IN / WITHIN 100m + 1 CAMERA PHOTO",10,sage))
        body.addView(label(o.name,16,sand).apply{
            setTypeface(Typeface.SERIF,Typeface.BOLD)
            maxLines=2
            ellipsize=android.text.TextUtils.TruncateAt.END
            setPadding(0,dp(4),0,dp(4))
        })
        body.addView(label(o.brief,12,sand).apply {
            maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
        })
        fogOverlay.currentPosition?.let {position->
            val out=FloatArray(1)
            android.location.Location.distanceBetween(position.latitude,position.longitude,
                o.latitude,o.longitude,out)
            val distance=if(out[0]>=1000f)
                "%.1f km".format(java.util.Locale.US,out[0]/1000f)
                else "${out[0].toInt()} m"
            body.addView(label("DISTANCE / "+distance,11,sage))
        }
        val popup=PopupWindow(stack,width,-2,true).apply{
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(7).toFloat()
        }
        fun styleActiveButton(button:Button) {
            val active=objectiveStore.active==o.id
            button.text=if(active)"✓ ACTIVE\nTAP TO REMOVE" else "⌖ SET ACTIVE"
            button.setTextColor(if(active)Color.rgb(24,31,22) else sand)
            button.background=GradientDrawable().apply {
                cornerRadius=dp(6).toFloat()
                setColor(if(active)Color.rgb(230,190,111) else Color.rgb(37,46,34))
                setStroke(dp(1),if(active)Color.rgb(250,226,171) else Color.rgb(151,125,76))
            }
            button.contentDescription=if(active)"Deactivate "+o.name else "Activate "+o.name
        }
        fun actionRow(vararg entries:Pair<String,()->Unit>) {
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            for((caption,click) in entries) {
                val button=action(caption).apply {
                    applyExpeditionStyle(this)
                    textSize=11f
                    maxLines=2
                    isSingleLine=false
                    setPadding(dp(4),dp(4),dp(4),dp(4))
                    gravity=Gravity.CENTER
                }
                if(caption=="⌖ SET ACTIVE"){
                    styleActiveButton(button)
                    button.setOnClickListener {
                        click()
                        styleActiveButton(button)
                    }
                } else button.setOnClickListener {click()}
                row.addView(button,LinearLayout.LayoutParams(0,dp(52),1f).apply{
                    setMargins(dp(2),0,dp(2),0)
                })
            }
            body.addView(row,LinearLayout.LayoutParams(-1,-2).apply {
                topMargin=dp(5)
            })
        }
        actionRow(
            "⌖ SET ACTIVE" to {
                val remove=objectiveStore.active==o.id
                objectiveStore.active=if(remove)null else o.id
                refresh()
                status.text=if(remove)terms.single+" DEACTIVATED / "+o.name.take(30)
                    else terms.single+" ACTIVE / "+o.name.take(30)
            },
            (if(objectiveStore.completed(o.id))"✓ COMPLETED" else "▣ PHOTO + COMPLETE") to {
                popup.dismiss()
                requestObjectiveProofPhoto(o)
            }
        )
        actionRow(
            (if(stopIndex>=0)"✓ STOP "+(stopIndex+1)+" / "+terms.journey
                else "＋ ADD TO "+terms.journey) to {
                val added=journeyStore.addOrRemove(o.id,validObjectiveIds())
                status.text=if(added)terms.journey+" / STOP ADDED"
                    else terms.journey+" / STOP REMOVED"
                refresh()
                popup.dismiss()
                showObjective(o)
            },
            "◈ PLAN "+terms.journey to {
                popup.dismiss()
                showJourneyPlanner()
            }
        )
        actionRow(
            (if(o.id.startsWith("saved_"))"× DELETE" else "HIDE") to {
                if(o.id.startsWith("saved_")) {
                    popup.dismiss()
                    confirmDeleteObjective(o)
                } else {
                    objectiveStore.hide(o.id)
                    if(objectiveStore.active==o.id)objectiveStore.active=null
                    if(o.id in journeyStore.ids())
                        journeyStore.save(journeyStore.ids().filterNot{it==o.id},
                            validObjectiveIds(),journeyStore.enabled)
                    refresh()
                    popup.dismiss()
                }
            },
            (if(o.id.startsWith("saved_"))"MOVE PIN" else "SOURCE") to {
                popup.dismiss()
                if(o.id.startsWith("saved_")) {
                    movingObjectiveId=o.id;movingPinTime=null;movingOriginalNote=null
                    movingPreview=GeoPoint(o.latitude,o.longitude)
                    mode="MOVE";followUser=false
                    map.controller.animateTo(GeoPoint(o.latitude,o.longitude))
                    movePinBar?.visibility=View.VISIBLE
                    pinMoveReticle?.invalidate()
                    status.text="MOVE "+terms.single+" / TOUCH MAP TO RELOCATE, THEN CONFIRM"
                } else if(o.source.startsWith("http")) {
                    startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(o.source)))
                }
            }
        )
        val tail=ObjectiveBubbleTail(this).apply {
            pointingUp=false
            minimumHeight=dp(11)
        }
        stack.addView(body,LinearLayout.LayoutParams(-1,-2))
        stack.addView(tail,LinearLayout.LayoutParams(-1,dp(11)))
        objectiveBubbleStack=stack;objectiveBubbleBody=body;objectiveBubbleTail=tail
        objectiveBubble=popup
        popup.setOnDismissListener {
            if(objectiveBubble===popup) {
                objectiveBubble=null;objectiveBubblePlace=null
                objectiveBubbleTail=null;objectiveBubbleStack=null;objectiveBubbleBody=null
            }
        }
        // First layout and viewport positioning happen after the callout enters WindowManager.
        popup.showAtLocation(map,Gravity.TOP or Gravity.START,0,0)
        handler.post {updateObjectiveBubblePosition()}
    }
    private fun showExpeditionJournal() {
        notes.clear();notes.addAll(fieldNotes.load())
        val ink=Color.rgb(65,47,29)
        val sepia=Color.rgb(121,84,50)
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(16),dp(16),dp(16))
            background=ExpeditionParchment()
        }
        panel.addView(label(terms.journal+" / PLACES",16,ink).apply {
            setTypeface(Typeface.SERIF,Typeface.BOLD)
        })
        panel.addView(label("${notes.size} PLACES / ${notes.count{it.visits.isNotEmpty()}} REVISITED",11,sepia))
        val search=EditText(this).apply {
            hint="Search the field journal";setSingleLine(true);textSize=14f
            setTextColor(ink);setHintTextColor(sepia)
        }
        panel.addView(search)
        val list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        panel.addView(android.widget.ScrollView(this).apply {addView(list)},LinearLayout.LayoutParams(-1,dp(350)))
        val dialog=android.app.AlertDialog.Builder(this).setView(panel).setNegativeButton("CLOSE",null).create()
        fun populate(query:String) {
            list.removeAllViews()
            val entries=notes.filter{it.title.contains(query,true)||it.body.contains(query,true)}
                .sortedByDescending{maxOf(it.time,it.visits.lastOrNull()?:0L)}
            if(entries.isEmpty())list.addView(label("No records yet. Add a pin on the map.",12,sepia))
            entries.forEach {entry->
                val date=java.text.SimpleDateFormat("dd MMM yyyy",java.util.Locale.getDefault()).format(java.util.Date(entry.time))
                val photos=entry.attachments.count{!it.endsWith(".m4a")}+(if(entry.kind=="PHOTO")1 else 0)
                val card=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL
                    setPadding(dp(10),dp(8),dp(10),dp(8))
                    background=ExpeditionParchment(card=true)
                }
                val contents=LinearLayout(this).apply{
                    orientation=LinearLayout.HORIZONTAL
                    gravity=Gravity.CENTER_VERTICAL
                }
                photoFiles(entry).firstOrNull()?.let{filename->
                    contents.addView(android.widget.ImageView(this).apply{
                        scaleType=android.widget.ImageView.ScaleType.CENTER_CROP
                        setImageBitmap(loadOrientedPhoto(File(filesDir,filename),180))
                    },LinearLayout.LayoutParams(dp(62),dp(62)).apply{rightMargin=dp(9)})
                }
                val summary=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
                summary.addView(label(entry.title,15,ink).apply{
                    maxLines=2
                    ellipsize=android.text.TextUtils.TruncateAt.END
                })
                summary.addView(label("$date / ${entry.visits.size} VISITS / $photos PHOTOS",11,sepia))
                if(entry.body.isNotBlank()&&entry.kind!="VOICE"&&entry.kind!="PHOTO")
                    summary.addView(label(entry.body.take(100),12,ink))
                contents.addView(summary,LinearLayout.LayoutParams(0,-2,1f))
                card.addView(contents)
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
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }
    private fun showExisting(note:FieldNote) {
        val current=notes.find { it.time==note.time } ?: return
        val ink=Color.rgb(67,48,28)
        val sepia=Color.rgb(118,84,53)
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(13),dp(12),dp(13),dp(12))
            background=ExpeditionParchment()
            isClickable=true
            alpha=1f
        }
        panel.addView(label(terms.journal+" / PIN DETAILS",14,ink).apply {
            setTypeface(Typeface.SERIF,Typeface.BOLD)
        })
        panel.addView(label("RECORDED / "+java.text.SimpleDateFormat("dd MMM yyyy HH:mm",java.util.Locale.getDefault()).format(java.util.Date(current.time))+" / "+current.visits.size+" VISITS",11,sepia))

        fogOverlay.currentPosition?.let {position->
            val out=FloatArray(1)
            android.location.Location.distanceBetween(position.latitude,position.longitude,current.latitude,current.longitude,out)
            panel.addView(label("DISTANCE / "+(if(out[0]>=1000f)"%.1f km".format(java.util.Locale.US,out[0]/1000f) else "${out[0].toInt()} m"),12,sepia))
        }
        val moveButton=action("↔ MOVE PIN").apply {
            applyExpeditionStyle(this)
        }
        panel.addView(moveButton,LinearLayout.LayoutParams(-1,dp(43)).apply{
            topMargin=dp(7);bottomMargin=dp(6)
        })
        val name=EditText(this).apply {
            setText(current.title);textSize=15f;setSingleLine(true)
            setTextColor(ink);setHintTextColor(sepia);hint="Marker title"
        }
        panel.addView(name,LinearLayout.LayoutParams(-1,dp(45)))
        val memo=EditText(this).apply {
            setText(if(current.kind=="PHOTO"||current.kind=="VOICE") "" else current.body)
            minLines=2;maxLines=4
            setTextColor(ink);setHintTextColor(sepia)
            textSize=13f
            hint="Field notes / observations..."
        }
        if(current.kind!="PHOTO"&&current.kind!="VOICE")panel.addView(memo)
        var selected=current.icon
        val iconPreview=label("PIN ICON / $selected   •   TAP TO CHANGE",11,sepia)
        panel.addView(iconPreview,LinearLayout.LayoutParams(-1,dp(27)).apply{
            topMargin=dp(4)
        })
        val choices=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        val iconButtons=mutableListOf<Pair<String,Button>>()
        fun highlightSelection() {
            iconButtons.forEach { (glyph,button) ->
                button.background=GradientDrawable().apply {
                    cornerRadius=dp(5).toFloat()
                    setColor(if(glyph==selected)Color.rgb(123,88,52)
                        else ExpeditionThemes.current.surface)
                    setStroke(dp(if(glyph==selected)2 else 1),
                        if(glyph==selected)Color.rgb(240,217,174)
                        else ExpeditionThemes.current.gold)
                }
                button.setTextColor(if(glyph==selected)Color.WHITE
                    else ExpeditionThemes.current.light)
            }
        }
        listOf("◆","⚑","★","✎","▲","●","⌖","▣").forEach {glyph->
            val button=action(glyph).apply {
                textSize=17f
                setOnClickListener {
                    selected=glyph
                    val idx=notes.indexOfFirst{it.time==current.time}
                    if(idx>=0) {
                        // Persist icon immediately; the user need not find SAVE.
                        notes[idx]=notes[idx].copy(icon=glyph)
                        fieldNotes.save(notes)
                        refresh()
                    }
                    iconPreview.text="PIN ICON / $glyph   ✓ SAVED"
                    status.text="PIN ICON UPDATED / $glyph"
                    highlightSelection()
                }
            }
            iconButtons.add(glyph to button)
            choices.addView(button,LinearLayout.LayoutParams(0,dp(38),1f).apply{
                setMargins(dp(1),0,dp(1),0)
            })
        }
        highlightSelection()
        panel.addView(choices)
        val count=label("PHOTOS / "+photoFiles(current).size+
            "    VIDEO / "+videoFiles(current).size+
            "    AUDIO / "+voiceFiles(current).size,11,sepia)
        panel.addView(count,LinearLayout.LayoutParams(-1,dp(29)).apply{
            topMargin=dp(5)
        })
        val photoStrip=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        photoFiles(current).forEach { filename ->
            val thumb=android.widget.ImageView(this).apply {
                scaleType=android.widget.ImageView.ScaleType.CENTER_CROP
                setImageBitmap(loadOrientedPhoto(File(filesDir,filename),280))
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
            photoStrip.addView(thumb,LinearLayout.LayoutParams(dp(84),dp(79)).apply{
                rightMargin=dp(5)
            })
        }
        if(photoStrip.childCount>0) {
            val scroller=android.widget.HorizontalScrollView(this)
            scroller.addView(photoStrip)
            panel.addView(scroller,LinearLayout.LayoutParams(-1,dp(85)))
        }
        lateinit var dialog:android.app.AlertDialog
        fun deleteMedia(filename:String) {
            android.app.AlertDialog.Builder(this)
                .setTitle("REMOVE ATTACHMENT?")
                .setMessage("Permanently remove this local recording from the pin?")
                .setNegativeButton("KEEP",null)
                .setPositiveButton("REMOVE") {_,_->
                    val idx=notes.indexOfFirst{it.time==current.time}
                    if(idx>=0) {
                        val entry=notes[idx]
                        val revised=entry.copy(
                            attachments=entry.attachments.filterNot{it==filename},
                            body=if(entry.body==filename)"" else entry.body)
                        notes[idx]=revised
                        fieldNotes.save(notes)
                        audioPlayback?.let{runCatching{it.stop()};it.release()}
                        audioPlayback=null
                        File(filesDir,filename).delete()
                        refresh()
                        dialog.dismiss()
                        showExisting(revised)
                    }
                }.show()
        }
        voiceFiles(current).forEachIndexed{i,file->
            val row=LinearLayout(this).apply{
                orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            }
            val playButton=action("▶ VOICE ${i+1}").apply{
                applyExpeditionStyle(this)
                setOnClickListener{playVoiceMemo(file)}
            }
            row.addView(playButton,LinearLayout.LayoutParams(0,dp(42),1f))
            row.addView(action("× REMOVE").apply{
                applyExpeditionStyle(this)
                setOnClickListener{deleteMedia(file)}
            },LinearLayout.LayoutParams(dp(104),dp(42)).apply{
                leftMargin=dp(5)
            })
            panel.addView(row,LinearLayout.LayoutParams(-1,dp(44)).apply{
                topMargin=dp(4)
            })
        }
        videoFiles(current).forEachIndexed{i,file->
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            row.addView(action("▸ PLAY VIDEO ${i+1}").apply{
                applyExpeditionStyle(this)
                setOnClickListener{playSnapVideo(file)}
            },LinearLayout.LayoutParams(0,dp(42),1f))
            row.addView(action("× REMOVE").apply{
                applyExpeditionStyle(this)
                setOnClickListener{deleteMedia(file)}
            },LinearLayout.LayoutParams(dp(104),dp(42)).apply{
                leftMargin=dp(5)
            })
            panel.addView(row,LinearLayout.LayoutParams(-1,dp(44)).apply{
                topMargin=dp(4)
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
        val journalScroll=android.widget.ScrollView(this).apply {
            isFillViewport=false
            // Opaque background fixes the transparent dialog that appeared
            // after returning from voice memo recording or media attachments.
            setBackgroundColor(Color.rgb(239,219,180))
            addView(panel)
        }
        dialog=android.app.AlertDialog.Builder(this).setView(journalScroll)
            .setNegativeButton("CLOSE",null)
            .setPositiveButton("SAVE") {_,_->updateNote()}
            .setNeutralButton("DELETE") {_,_->
                notes.removeAll{it.time==current.time}
                fieldNotes.save(notes);refresh()
            }.create()
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
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(Color.rgb(239,219,180)))
    }
    /** Record first, review sound and waveform, then explicitly save. */
    private fun startVoice(where:GeoPoint?,forSnap:Boolean=false) {
        voicePoint=where
        snapVoiceRecording=forSnap
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) {
            snapVoicePermissionPending=forSnap
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO),11)
            return
        }
        snapVoicePermissionPending=false
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(14),dp(13),dp(14),dp(13))
            background=tacticalPanel()
        }
        panel.addView(label("● VOICE MEMO / RECORD & REVIEW",15,sand))
        val state=label("MIC ACTIVE / 00:00",12,sage)
        panel.addView(state,LinearLayout.LayoutParams(-1,dp(33)))
        val meter=VoiceMeterView(this).apply{
            minimumHeight=dp(64)
            setBackgroundColor(ExpeditionThemes.current.dark)
        }
        panel.addView(meter,LinearLayout.LayoutParams(-1,dp(64)))
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val extra=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        panel.addView(row,LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(8)})
        panel.addView(extra,LinearLayout.LayoutParams(-1,dp(46)).apply{topMargin=dp(5)})
        val stop=action("■ STOP");val play=action("▶ PLAY")
        val retry=action("↻ RETAKE");val discard=action("× DELETE")
        val save=action("✓ SAVE")
        fun append(parent:LinearLayout,button:Button) {
            applyExpeditionStyle(button)
            parent.addView(button,LinearLayout.LayoutParams(0,dp(43),1f).apply{
                setMargins(dp(2),0,dp(2),0)
            })
        }
        append(row,stop);append(row,play)
        append(extra,retry);append(extra,discard);append(extra,save)
        lateinit var dialog:android.app.AlertDialog
        var recording=false;var recorded=false;var began=0L
        var player:MediaPlayer?=null
        var ticker:Runnable?=null
        fun stopTicker() {
            ticker?.let{handler.removeCallbacks(it)}
            activeVoiceMeterTick?.let{handler.removeCallbacks(it)}
            ticker=null
            activeVoiceMeterTick=null
        }
        fun stopPlayer() {player?.let{runCatching{it.stop()};it.release()};player=null}
        fun stopTake() {
            if(!recording)return
            recording=false;stopTicker()
            val stopped=runCatching{recorder?.stop()}.isSuccess
            runCatching{recorder?.release()};recorder=null
            recorded=stopped && audioFile?.let{it.isFile&&it.length()>100L}==true
            if(!recorded)audioFile?.delete()
            state.text=if(recorded)"READY / REVIEW OR SAVE" else "NO AUDIO / RETAKE"
            stop.visibility=View.GONE
            play.isEnabled=recorded;save.isEnabled=recorded
            retry.isEnabled=true
        }
        fun startTake() {
            stopPlayer();stopTake()
            audioFile?.delete()
            val file=File(filesDir,"voice_"+System.currentTimeMillis()+".m4a")
            val started=runCatching {
                recorder=MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setOutputFile(file.absolutePath);prepare();start()
                }
            }.isSuccess
            if(!started) {
                runCatching{recorder?.release()};recorder=null
                file.delete();state.text="MIC ERROR / RETAKE";save.isEnabled=false
                return
            }
            audioFile=file;recording=true;recorded=false
            began=android.os.SystemClock.elapsedRealtime()
            meter.reset();stop.visibility=View.VISIBLE
            play.isEnabled=false;save.isEnabled=false;retry.isEnabled=false
            val tick=object:Runnable {
                override fun run() {
                    if(!recording)return
                    meter.sample(runCatching{recorder?.maxAmplitude?:0}.getOrDefault(0))
                    val elapsed=(android.os.SystemClock.elapsedRealtime()-began)/1000L
                    state.text="● RECORDING / %02d:%02d".format(
                        java.util.Locale.US,elapsed/60,elapsed%60)
                    handler.postDelayed(this,95L)
                }
            }
            ticker=tick
            activeVoiceMeterTick=tick
            handler.post(tick)
        }
        fun clean(delete:Boolean) {
            stopTake();stopTicker();stopPlayer()
            runCatching{recorder?.release()};recorder=null
            if(delete)audioFile?.delete()
            audioFile=null;voicePoint=null;voiceNoteTime=null
            snapVoiceRecording=false;snapVoicePermissionPending=false
        }
        stop.setOnClickListener{stopTake()}
        retry.setOnClickListener{startTake()}
        play.setOnClickListener{
            if(recording)return@setOnClickListener
            val file=audioFile?:return@setOnClickListener
            stopPlayer()
            runCatching {
                player=MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener{it.release();player=null;state.text="PLAYBACK DONE"}
                    prepare();start()
                }
                state.text="▶ PLAYING"
            }.onFailure{stopPlayer();state.text="PLAYBACK FAILED / RETAKE"}
        }
        discard.setOnClickListener{
            clean(true);dialog.dismiss()
            status.text="VOICE MEMO / DISCARDED"
        }
        save.setOnClickListener{
            if(recording || !recorded)return@setOnClickListener
            val file=audioFile?:return@setOnClickListener
            if(snapVoiceRecording) {
                snapDraft=SnapDraft(file.name,"VOICE","Quick voice memo")
                persistSnapDraft()
                clean(false);dialog.dismiss();showSnapPlacement()
                return@setOnClickListener
            }
            val index=voiceNoteTime?.let{id->notes.indexOfFirst{it.time==id}}?:-1
            val updated=if(index>=0) {
                notes[index]=notes[index].copy(
                    attachments=notes[index].attachments+file.name)
                notes[index]
            } else {
                voicePoint?.let{point->
                    FieldNote(point.latitude,point.longitude,"Voice memo",
                        "VOICE","",System.currentTimeMillis(),"●",listOf(file.name))
                        .also{notes.add(it)}
                }
            }
            if(updated==null) {state.text="PIN LOCATION MISSING";return@setOnClickListener}
            fieldNotes.save(notes);refresh()
            clean(false);dialog.dismiss()
            status.text="VOICE MEMO / SAVED"
            showExisting(updated)
        }
        dialog=android.app.AlertDialog.Builder(this).setView(panel)
            .setCancelable(false).create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(ExpeditionThemes.current.surface))
        startTake()
    }
    /**
     * LOCATE is separate from TRACK. A one-shot permission only centres the
     * camera; it never adds any visited territory or starts background GPS.
     */
    private fun locateOnce() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION),14)
            return
        }
        status.text="LOCATE / SEARCHING FOR POSITION"
        try {
            val token=com.google.android.gms.tasks.CancellationTokenSource()
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY,token.token)
                .addOnSuccessListener {fix->
                    if(isFinishing || isDestroyed)return@addOnSuccessListener
                    val age=if(fix!=null)
                        (android.os.SystemClock.elapsedRealtimeNanos()-
                            fix.elapsedRealtimeNanos)/1000000L else Long.MAX_VALUE
                    if(fix!=null && fix.latitude.isFinite() && fix.longitude.isFinite() &&
                        age in 0..120000L) {
                        fogOverlay.currentPosition=GeoPoint(fix.latitude,fix.longitude)
                        fogOverlay.positionAccuracy=if(fix.hasAccuracy())fix.accuracy else 0f
                        followUser=true
                        lastKnownFixTime=System.currentTimeMillis()
                        val next=LocateZoomPolicy.next(map.zoomLevelDouble,
                            map.maxZoomLevel.toDouble())
                        map.controller.setZoom(next)
                        map.controller.animateTo(fogOverlay.currentPosition)
                        status.text="LOCATE / POSITION FOUND / ZOOM %.1f / NOT RECORDING"
                            .format(java.util.Locale.US,next)
                        refresh()
                    } else status.text="LOCATE / NO FRESH FIX / TRY OUTDOORS"
                }
                .addOnFailureListener {error->
                    if(!isFinishing)status.text="LOCATE / UNAVAILABLE / CHECK LOCATION SERVICES"
                }
        } catch(error:SecurityException) {
            status.text="LOCATE / PERMISSION REVOKED"
        }
    }

    private fun showFirstRunGuide() {
        val prefs=getSharedPreferences("terra_first_run_v1",Context.MODE_PRIVATE)
        if(prefs.getBoolean("seen",false))return
        prefs.edit().putBoolean("seen",true).apply()
        android.app.AlertDialog.Builder(this)
            .setTitle("WELCOME TO TERRA")
            .setMessage("Discover the world by walking.\n\n"+
                "1. START TRACKING to reveal the map as you move.\n"+
                "2. Use LOCATE to return to your position.\n"+
                "3. Open MISSIONS to discover places nearby.\n\n"+
                "GPS tracking is optional and starts only when you tap TRACK. "+
                "You can explore the map without recording.")
            .setPositiveButton("START TRACKING"){_,_->
                trackButton.performClick()
            }
            .setNeutralButton("FIND MY LOCATION"){_,_->locateOnce()}
            .setNegativeButton("EXPLORE FIRST"){_,_->
                status.text="EXPLORE / TAP TRACK WHEN READY"
            }.show()
    }

    private fun showLocationPermissionHelp() {
        android.app.AlertDialog.Builder(this)
            .setTitle("LOCATION IS OPTIONAL")
            .setMessage("TERRA needs precise location to reveal visited "+
                "territory. Without it you can still explore the map, "+
                "but walking will not be recorded. Grant permission in "+
                "Android Settings whenever you're ready.")
            .setPositiveButton("APP SETTINGS"){_,_->
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:"+packageName)))
            }
            .setNegativeButton("NOT NOW",null).show()
    }

    private fun showGpsProofHelp(o:ReconObjective,reason:String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("CHECK-IN NEEDS RELIABLE GPS")
            .setMessage(reason+"\n\nGo outdoors if possible, keep "+
                "location services enabled and wait for a better fix. "+
                "Your objective remains incomplete until verified; "+
                "your existing progress is safe.")
            .setPositiveButton("RETRY"){_,_->requestObjectiveProofPhoto(o)}
            .setNegativeButton("LATER",null).show()
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
        panel.addView(label("◈ "+terms.statistics,14,sand))
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
    /**
     * Mall pin visibility is zoom aware: distinct landmarks at city level,
     * all mapped centres on neighborhood scale, de-cluttered by screen space.
     * No approximation of customer traffic or mall popularity is made.
     */
    private fun visibleMalls(mapView:MapView):List<MallPlace> {
        if(!layerVisible("MALLS") || mapView.zoomLevelDouble<10.8)return emptyList()
        val all=if(mapView.zoomLevelDouble<12.8)
            malls.filter{MallCatalog.featured(it.name)} else malls
        val occupied=ArrayList<Pair<Float,Float>>()
        val visible=ArrayList<MallPlace>()
        for(mall in all.sortedWith(
            compareByDescending<MallPlace>{MallCatalog.featured(it.name)}
                .thenBy{it.name}
        )) {
            val pt=mapView.projection.toPixels(
                GeoPoint(mall.latitude,mall.longitude),Point())
            val x=pt.x.toFloat()
            val y=pt.y.toFloat()
            if(x<0f||y<0f||x>mapView.width||y>mapView.height)continue
            val minGap=if(mapView.zoomLevelDouble<14.5)dp(24)
                else dp(18)
            if(occupied.any{
                kotlin.math.hypot((it.first-x).toDouble(),
                    (it.second-y).toDouble())<minGap
            })continue
            visible.add(mall)
            occupied.add(x to y)
        }
        return visible
    }

    private fun scheduleMallRefresh() {
        handler.removeCallbacks(mallRefreshTask)
        if(layerVisible("MALLS"))handler.postDelayed(mallRefreshTask,2300L)
    }

    private fun maybeRefreshMalls(force:Boolean=false) {
        if(!::map.isInitialized||mallLoading)return
        if(!layerVisible("MALLS")) {
            if(force)status.text="MALLS / ENABLE THE LAYER FIRST"
            return
        }
        if(offline) {
            if(force)status.text="MALLS / OFFLINE / USING CACHED PLACES"
            return
        }
        if(map.zoomLevelDouble<11.2 && !force)return
        val now=System.currentTimeMillis()
        // Never hammer public OSM services during map drags or repeated scans.
        if(now-lastMallAttempt<(if(force)20000L else 180000L)) {
            if(force)status.text="MALLS / NEXT SCAN AVAILABLE SHORTLY"
            return
        }
        val center=GeoPoint(map.mapCenter.latitude,map.mapCenter.longitude)
        val previous=mallStore.cachedCenter()
        val recent=now-mallStore.lastFetchedAt()<8L*60L*60L*1000L
        if(!force && previous!=null && recent) {
            val distance=FloatArray(1)
            android.location.Location.distanceBetween(
                previous.latitude,previous.longitude,
                center.latitude,center.longitude,distance)
            if(distance[0]<4800f)return
        }
        mallLoading=true
        lastMallAttempt=now
        if(force)status.text="MALLS / SCANNING OPENSTREETMAP..."
        Thread {
            val result=runCatching{mallStore.fetch(center)}
            runOnUiThread {
                mallLoading=false
                if(isFinishing||isDestroyed)return@runOnUiThread
                result.onSuccess{found->
                    malls.clear()
                    malls.addAll(found)
                    refresh()
                    if(force)status.text="MALLS / "+found.size+
                        " MAPPED CENTRES CACHED"
                    if(force && found.isEmpty())android.app.AlertDialog.Builder(this)
                        .setTitle("MALLS / NO MAP DATA")
                        .setMessage("No shopping centres were returned by OpenStreetMap. This may reflect incomplete community mapping. Pan to a different district or retry.")
                        .setPositiveButton("OK",null).show()
                }
                result.onFailure{error->
                    if(force)android.app.AlertDialog.Builder(this)
                        .setTitle("MALLS / CONNECTION UNAVAILABLE")
                        .setMessage("Couldn't update OpenStreetMap malls. "+
                            "Previously cached places stay available.\n\n"+
                            (error.message?:"Network unavailable"))
                        .setPositiveButton("OK",null).show()
                    if(force)status.text="MALLS / OFFLINE CACHE: "+malls.size
                }
            }
        }.start()
    }

    private fun showMallPlace(mall:MallPlace) {
        val point=GeoPoint(mall.latitude,mall.longitude)
        val signature=MallCatalog.signature(mall.name)
        val panel=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(14),dp(12),dp(14),dp(12))
            background=tacticalPanel()
        }
        panel.addView(label("▤ MALLS / "+
            (if(signature!=null)"FEATURED DESTINATION" else "MAPPED SHOPPING CENTRE"),
            11,sage))
        panel.addView(label(mall.name,18,sand).apply{
            setTypeface(Typeface.SERIF,Typeface.BOLD)
        })
        if(mall.district.isNotBlank())
            panel.addView(label("AREA / "+mall.district.take(80),11,sage))
        panel.addView(label(signature?:
            "Each shopping destination has its own character. Explore the place and capture what makes it memorable.",
            12,sand).apply{
            setPadding(0,dp(7),0,dp(8))
        })
        fogOverlay.currentPosition?.let{here->
            val distance=FloatArray(1)
            android.location.Location.distanceBetween(
                here.latitude,here.longitude,
                mall.latitude,mall.longitude,distance)
            panel.addView(label("DISTANCE / "+(if(distance[0]>=1000f)
                "%.1f km".format(java.util.Locale.US,distance[0]/1000f)
                else "%.0f m".format(java.util.Locale.US,distance[0])),11,sage))
        }
        val memoCount=notes.count{note->
            note.kind=="MALL" && run{
                val d=FloatArray(1)
                android.location.Location.distanceBetween(
                    note.latitude,note.longitude,mall.latitude,mall.longitude,d)
                d[0]<250f
            }
        }
        panel.addView(label("YOUR FIELD NOTES NEARBY / "+memoCount,11,sage))
        lateinit var dialog:android.app.AlertDialog
        fun option(title:String,click:()->Unit) {
            panel.addView(action(title).apply{
                applyExpeditionStyle(this)
                setOnClickListener{
                    dialog.dismiss()
                    click()
                }
            },LinearLayout.LayoutParams(-1,dp(44)).apply{
                topMargin=dp(5)
            })
        }
        option("✎  WRITE A MALL FIELD NOTE") {
            showNoteEditor(point,"MALL")
        }
        option("◈  SAVE AS A DESTINATION") {
            // Check built-in landmarks too; Dubai Marina Mall is already
            // a curated objective, and should not be duplicated.
            val already=allObjectives()
                .firstOrNull{existing->
                    existing.name.equals(mall.name,true) &&
                        kotlin.math.abs(existing.latitude-mall.latitude)<.002 &&
                        kotlin.math.abs(existing.longitude-mall.longitude)<.002
                }
            val destination=already?:objectiveStore.addPlace(
                mall.name,mall.latitude,mall.longitude,
                "MALL / "+(signature?:"Shopping destination"))
            objectiveStore.hide(destination.id,false)
            objectiveStore.enabled=true
            status.text=if(already==null)"MALL / SAVED AS DESTINATION"
                else "MALL / EXISTING DESTINATION OPENED"
            refresh()
            showObjective(destination)
        }
        option("⌖  VIEW IN MAPS") {
            val query=android.net.Uri.encode(mall.name)
            val uri=Uri.parse("geo:0,0?q="+mall.latitude+","+mall.longitude+
                "("+query+")")
            runCatching{startActivity(Intent(Intent.ACTION_VIEW,uri))}
                .onFailure{status.text="MALL / NO EXTERNAL MAP APP"}
        }
        if(mall.website.isNotEmpty())option("↗  MALL WEBSITE") {
            runCatching{startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(mall.website)))}
                .onFailure{status.text="MALL / CANNOT OPEN WEBSITE"}
        }
        panel.addView(label("Place geometry from © OpenStreetMap contributors. "+
            "A map point may represent the centre of a complex, not its entrance.",
            9,sage).apply{setPadding(0,dp(7),0,0)})
        dialog=android.app.AlertDialog.Builder(this).setView(panel)
            .setNegativeButton("CLOSE",null).create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                ExpeditionThemes.current.surface))
    }

    private fun scheduleTaxiStandsRefresh() {
        handler.removeCallbacks(taxiRefreshTask)
        handler.postDelayed(taxiRefreshTask,1800L)
    }

    private fun maybeRefreshTaxiStands(force:Boolean=false) {
        if(!::map.isInitialized || taxiStandLoading)return
        if(!taxiStandsEnabled) {
            if(force)status.text="TAXI / ENABLE TAXI STANDS LAYER FIRST"
            return
        }
        if(offline) {
            if(force)status.text="TAXI / OFFLINE: RECONNECT TO LOAD STANDS"
            return
        }
        if(map.zoomLevelDouble<14.8) {
            if(force)status.text="TAXI / ZOOM IN TO VIEW STANDS"
            return
        }
        val now=System.currentTimeMillis()
        if(!force && now-lastTaxiStandAttempt<120000L)return
        val center=GeoPoint(map.mapCenter.latitude,map.mapCenter.longitude)
        val cached=taxiStandStore.cachedCenter()
        val recent=now-taxiStandStore.lastFetchedAt()<6L*60L*60L*1000L &&
            taxiStands.isNotEmpty()
        val delta=FloatArray(1)
        if(cached!=null)android.location.Location.distanceBetween(
            cached.latitude,cached.longitude,
            center.latitude,center.longitude,delta)
        if(!force && cached!=null && recent && delta[0]<2800f)return
        taxiStandLoading=true
        lastTaxiStandAttempt=now
        if(force)status.text="TAXI / SCANNING OSM STAND LOCATIONS..."
        Thread {
            val result=runCatching{taxiStandStore.fetch(center)}
            runOnUiThread {
                taxiStandLoading=false
                if(isFinishing || isDestroyed)return@runOnUiThread
                result.onSuccess {stands->
                    taxiStands.clear()
                    taxiStands.addAll(stands)
                    refresh()
                    if(force) {
                        status.text="TAXI / FOUND "+stands.size+" MAPPED STANDS"
                        if(stands.isEmpty())android.app.AlertDialog.Builder(this)
                            .setTitle("TAXI STANDS / NO MAP DATA")
                            .setMessage("No taxi stands are currently mapped in OpenStreetMap for this area. This does not mean taxis are unavailable. Try another part of Dubai, or use a taxi-booking app.")
                            .setPositiveButton("OK",null).show()
                    }
                }
                result.onFailure {error->
                    if(force) {
                        status.text="TAXI / ONLINE DATA UNAVAILABLE"
                        android.app.AlertDialog.Builder(this)
                            .setTitle("TAXI STANDS / CONNECTION ERROR")
                            .setMessage("Could not retrieve taxi stand locations from the OpenStreetMap data service. Previously cached locations remain visible. Retry later.\n\n"+
                                (error.message ?: "Unknown network error"))
                            .setPositiveButton("OK",null).show()
                    }
                }
            }
        }.start()
    }

    private fun showTaxiStand(stand:TaxiStand) {
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(13),dp(16),dp(13))
            background=tacticalPanel()
        }
        panel.addView(label("TAXI STAND / OPENSTREETMAP",12,sand))
        panel.addView(label(stand.name,16,sand))
        if(stand.operator.isNotBlank())
            panel.addView(label("OPERATOR / "+stand.operator,11,sage))
        fogOverlay.currentPosition?.let {here->
            val distance=FloatArray(1)
            android.location.Location.distanceBetween(
                here.latitude,here.longitude,stand.latitude,stand.longitude,distance)
            val formatted=if(distance[0]>=1000f)
                "%.1f km".format(java.util.Locale.US,distance[0]/1000f)
                else "${distance[0].toInt()} m"
            panel.addView(label("DISTANCE / "+formatted,12,sage))
        }
        panel.addView(label("Mapped taxi rank, not live vehicle availability.",10,sage))
        panel.addView(label("© OpenStreetMap contributors",9,sage))
        android.app.AlertDialog.Builder(this).setView(panel)
            .setNegativeButton("CLOSE",null).show()
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
    private fun updateSolarDial() {
        // Use a real GPS position when available; Dubai until first GPS lock.
        val position=fogOverlay.currentPosition
        val lat=position?.latitude ?: 25.2048
        val lon=position?.longitude ?: 55.2708
        expeditionHeader?.solarSnapshot=SolarCycle.snapshot(lat,lon)
    }

    private fun showSolarDetails() {
        solarPopup?.dismiss()
        updateSolarDial()
        val gps=fogOverlay.currentPosition
        val lat=gps?.latitude ?: 25.2048
        val lon=gps?.longitude ?: 55.2708
        val snapshot=SolarCycle.snapshot(lat,lon)
        val solar=snapshot.day
        val zone=java.time.ZoneId.systemDefault()
        val clock=java.time.format.DateTimeFormatter.ofPattern("HH:mm",java.util.Locale.getDefault())
        fun time(value:java.time.Instant?):String=
            value?.atZone(zone)?.format(clock) ?: "--:--"
        fun interval(from:java.time.Instant?,to:java.time.Instant?):String=
            if(from==null || to==null)"Unavailable" else time(from)+" – "+time(to)
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(15),dp(13),dp(15),dp(13))
            background=tacticalPanel()
        }
        panel.addView(label("☼ SOLAR DIAL / "+(if(gps!=null)"GPS LOCATION" else "DUBAI"),12,sage))
        panel.addView(label(
            (if(snapshot.isDay)"DAYLIGHT" else "NIGHT")+
            "  /  NEXT "+snapshot.nextEvent+" "+time(snapshot.nextTime),13,sand))
        panel.addView(label("↑ SUNRISE   "+time(solar.sunrise),15,sand))
        panel.addView(label("↓ SUNSET    "+time(solar.sunset),15,sand))
        val duration=snapshot.durationMinutes
        panel.addView(label("DAY LENGTH   "+(if(duration!=null)
            "${duration/60}h ${duration%60}m" else "Unavailable"),12,sage))
        panel.addView(label("GOLDEN HOUR / MORNING",11,sage))
        panel.addView(label(interval(solar.sunrise,solar.morningGoldenEnd),12,sand))
        panel.addView(label("GOLDEN HOUR / EVENING",11,sage))
        panel.addView(label(interval(solar.eveningGoldenStart,solar.sunset),12,sand))
        panel.addView(label("BLUE HOUR / MORNING",11,sage))
        panel.addView(label(interval(solar.morningBlueStart,solar.morningBlueEnd),12,sand))
        panel.addView(label("BLUE HOUR / EVENING",11,sage))
        panel.addView(label(interval(solar.eveningBlueStart,solar.eveningBlueEnd),12,sand))
        panel.addView(label("Approximate astronomical times • offline • device timezone",10,sage))
        val popup=PopupWindow(panel,dp(300),-2,true).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(7).toFloat()
        }
        panel.addView(action("CLOSE").apply {setOnClickListener{popup.dismiss()}})
        solarPopup=popup
        popup.setOnDismissListener{if(solarPopup===popup)solarPopup=null}
        popup.showAtLocation(map,Gravity.TOP or Gravity.RIGHT,dp(9),dp(105))
    }

    private fun loadDubaiTemperature() {
        val now=System.currentTimeMillis()
        if(now-weatherLastFetch<5L*60L*1000L)return
        weatherLastFetch=now
        val loc=if(::fogOverlay.isInitialized)fogOverlay.currentPosition else null
        val latitude=loc?.latitude ?: 25.2048
        val longitude=loc?.longitude ?: 55.2708
        Thread {
            val result=runCatching {
                val url="https://api.open-meteo.com/v1/forecast?latitude="+latitude+
                    "&longitude="+longitude+"&current=temperature_2m"
                val conn=(java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    connectTimeout=8000;readTimeout=8000
                    setRequestProperty("User-Agent","TerraUnlocked/Android")
                }
                try {
                    if(conn.responseCode!=200)throw java.io.IOException("Weather unavailable")
                    org.json.JSONObject(conn.inputStream.bufferedReader().use{it.readText()})
                        .getJSONObject("current").getDouble("temperature_2m")
                } finally {conn.disconnect()}
            }
            runOnUiThread {
                result.onSuccess {temperature->
                    if(!temperature.isFinite())return@onSuccess
                    val rounded=kotlin.math.round(temperature).toInt()
                    weatherPrefs.edit().putInt("last_temperature",rounded).apply()
                    if(::weatherView.isInitialized)weatherView.text="${rounded}°"
                    expeditionHeader?.temperature=rounded
                    weatherLastFetch=System.currentTimeMillis()+25L*60L*1000L
                }
                result.onFailure {
                    // Keep cached temperature visible; retry next time the app resumes.
                    weatherLastFetch=System.currentTimeMillis()
                }
            }
        }.start()
    }
    private fun chooseMapSource() {
        val names=arrayOf("OpenStreetMap · DEFAULT","OpenTopoMap","CyclOSM · BIKE / SCOOTER")
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
        movingOriginalNote=note
        movingPinTime=note.time
        movingPreview=GeoPoint(note.latitude,note.longitude)
        mode="MOVE"
        followUser=false
        clearMapPreview()
        map.controller.animateTo(GeoPoint(note.latitude,note.longitude))
        movePinBar?.visibility=View.VISIBLE
        pinMoveReticle?.invalidate()
        status.text="MOVE PIN / TOUCH OR DRAG TARGET, THEN CONFIRM"
    }
    /**
     * Long-press Journal or choose Known Locations in its drawer.
     * Stage 1 chooses HOME or OFFICE; stage 2 offers clear, non-destructive
     * actions on that specific saved destination.
     */
    private fun showKnownLocations() {
        val prefs=getSharedPreferences("terra_anchors_v1",Context.MODE_PRIVATE)
        val names=arrayOf("HOME","OFFICE")
        val choices=names.map { name ->
            val saved=prefs.getString(name+"_lat",null)?.toDoubleOrNull()!=null &&
                prefs.getString(name+"_lon",null)?.toDoubleOrNull()!=null
            (if(name=="HOME")"⌂  " else "▣  ")+name+
                (if(saved)"  /  SAVED" else "  /  NOT SET")
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("KNOWN LOCATIONS / "+terms.savedPlaces)
            .setItems(choices) {_,index-> showKnownLocationActions(names[index]) }
            .setNegativeButton("CLOSE",null).show()
    }
    private fun showKnownLocationActions(name:String) {
        val prefs=getSharedPreferences("terra_anchors_v1",Context.MODE_PRIVATE)
        val lat=prefs.getString(name+"_lat",null)?.toDoubleOrNull()
        val lon=prefs.getString(name+"_lon",null)?.toDoubleOrNull()
        val current=fogOverlay.currentPosition
        val options=arrayOf(
            "⌖  GO TO $name",
            "◎  SAVE GPS POSITION AS $name",
            "▣  SAVE MAP CENTER AS $name",
            "×  REMOVE $name"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("$name / LOCATION")
            .setItems(options) {_,chosen->
                when(chosen) {
                    0 -> {
                        if(lat!=null && lon!=null) {
                            followUser=false
                            map.controller.animateTo(GeoPoint(lat,lon))
                            status.text="$name / TARGET"
                        } else {
                            status.text="$name / NOT YET SET"
                            showKnownLocationActions(name)
                        }
                    }
                    1 -> {
                        if(current!=null) {
                            prefs.edit().putString(name+"_lat",current.latitude.toString())
                                .putString(name+"_lon",current.longitude.toString()).apply()
                            status.text="$name / GPS POSITION SAVED";refresh()
                        } else status.text="$name / NO CURRENT GPS"
                    }
                    2 -> anchor(name,true)
                    3 -> android.app.AlertDialog.Builder(this)
                        .setTitle("REMOVE $name?")
                        .setMessage("Only the saved HOME/OFFICE marker will be removed. Notes and discovered map remain unchanged.")
                        .setNegativeButton("KEEP",null)
                        .setPositiveButton("REMOVE") {_,_->
                            prefs.edit().remove(name+"_lat").remove(name+"_lon").apply()
                            status.text="$name / REMOVED";refresh()
                        }.show()
                }
            }.setNegativeButton("BACK") {_,_->showKnownLocations()}.show()
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
                        .setMessage("GitHub update check failed: "+(result.exceptionOrNull()?.message ?: "Unknown error")+"\n\nYou can open the official releases page in your browser.")
                        .setPositiveButton("OPEN RELEASES") {_,_->
                            startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/nemanjaz1343-crypto/TerraUnlocked/releases/latest")))
                        }
                        .setNegativeButton("CLOSE",null).show()
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

    private fun confirmExplorationReset() {
        android.app.AlertDialog.Builder(this)
            .setTitle("RESET EXPLORATION?")
            .setMessage("All GPS discoveries, manually revealed areas and drawn paths will be permanently erased.\n\nSaved objectives, pins, field notes, attachments and settings will remain untouched. GPS tracking will stop.")
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("CONTINUE") {_,_->
                val warning=android.app.AlertDialog.Builder(this)
                    .setTitle("FINAL CONFIRMATION")
                    .setMessage("Return the entire map to fog? This action CANNOT be undone. Only exploration history will be deleted.")
                    .setNegativeButton("KEEP MAP",null)
                    .setPositiveButton("ERASE MAP") {_,_->
                        status.text="RESETTING EXPLORATION / STOPPING GPS..."
                        // The service stops location callbacks and erases both persistent
                        // discovery stores, then acknowledges completion to the UI.
                        startService(Intent(this,TrackingService::class.java).apply {
                            action=TrackingService.ACTION_RESET_EXPLORATION
                        })
                    }.show()
                warning.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(Color.rgb(215,112,93))
            }.show()
    }

    private fun backgroundGpsGranted():Boolean =
        android.os.Build.VERSION.SDK_INT<29 ||
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)==PackageManager.PERMISSION_GRANTED

    private fun explainBackgroundGps() {
        if(android.os.Build.VERSION.SDK_INT<29) {
            status.text="BACKGROUND GPS / FOREGROUND SERVICE ACTIVE"
            return
        }
        if(backgroundGpsGranted()) {
            android.app.AlertDialog.Builder(this).setTitle("TERRA / BACKGROUND GPS")
                .setMessage("Always-allow location permission is already granted. TRACK uses a visible Android notification; STOP ends tracking.")
                .setPositiveButton("OK",null).show()
            return
        }
        android.app.AlertDialog.Builder(this).setTitle("TERRA / BACKGROUND GPS")
            .setMessage("For tracking after closing Terra and for service recovery, enable 'Allow all the time' in Android location permissions. Tracking always shows an Android notification and can be stopped there. Android battery restrictions and Force stop may still interrupt it.")
            .setPositiveButton("OPEN SETTINGS") {_,_->
                if(android.os.Build.VERSION.SDK_INT==29) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),13)
                } else {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:"+packageName)))
                }
            }
            .setNegativeButton("LATER",null).show()
    }

    private fun showDataMenu() {
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12));background=tacticalPanel()
        }
        panel.addView(label("▣ DATA / SOURCES & BACKUP",14,sand))
        fun option(name:String,fn:()->Unit) {panel.addView(action(name).apply {setOnClickListener {fn()}})}
        option("CHECK FOR UPDATES") {checkForUpdates()}
        option("REPORT A PROBLEM") {
            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(
                "https://github.com/nemanjaz1343-crypto/TerraUnlocked/issues/new")))
        }
        option("BACKGROUND GPS PERMISSION") {explainBackgroundGps()}
        option("GPS DIAGNOSTICS / EXPORT CSV") {
            android.app.AlertDialog.Builder(this).setTitle("TERRA / GPS DIAGNOSTICS")
                .setMessage(GpsDiagnostics(this).summary()+"\n\nThe CSV includes precise locations and is saved only on your phone until you export it.")
                .setPositiveButton("EXPORT CSV") {_,_->
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type="text/csv"
                        putExtra(Intent.EXTRA_TITLE,"Terra-GPS-diagnostics.csv")
                    },91)
                }.setNegativeButton("CLOSE",null).show()
        }
        option("CLEAR GPS DIAGNOSTICS") {
            android.app.AlertDialog.Builder(this).setTitle("CLEAR GPS DIAGNOSTICS?")
                .setMessage("Delete the local GPS diagnostic history? Your discovered map is not affected.")
                .setPositiveButton("CLEAR") {_,_->
                    GpsDiagnostics(this).clear()
                    status.text="GPS DIAGNOSTICS / CLEARED"
                }.setNegativeButton("CANCEL",null).show()
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

        val scroll=android.widget.ScrollView(this).apply{
            isFillViewport=true
            addView(panel)
        }
        val window=PopupWindow(scroll,
            dp(305).coerceAtMost(resources.displayMetrics.widthPixels-dp(20)),
            dp(430).coerceAtMost(
                (resources.displayMetrics.heightPixels-dp(130)).coerceAtLeast(dp(240))),
            true).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        window.showAtLocation(map,Gravity.CENTER,0,0)
    }
    private fun maybeRecoverPaths() {
        val now=System.currentTimeMillis()
        if(recoveryRunning || offline || records.size<3 ||
            records.size<lastRecoveryPointCount+10)return
        if(now-lastRecoveryScan<480000L)return
        lastRecoveryScan=now
        lastRecoveryPointCount=records.size
        scanSmartPaths(silent=true)
    }
    private fun scanSmartPaths(silent:Boolean=false) {
        if(recoveryRunning || offline)return
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
        button.setTextColor(if(selected)ExpeditionThemes.current.light else sand)
        button.setTypeface(Typeface.SERIF,Typeface.BOLD)
        button.background=GradientDrawable().apply {
            cornerRadius=dp(6).toFloat()
            setColor(if(selected)ExpeditionThemes.current.raised else ExpeditionThemes.current.surface)
            setStroke(dp(1),if(ExpeditionThemes.current.id=="MILITARY")
                Color.rgb(151,125,76) else ExpeditionThemes.current.gold)
        }
    }

    private fun showLayers(anchor: View) {
        if(layersPopup?.isShowing==true){layersPopup?.dismiss();return}
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            background=GradientDrawable().apply {
                cornerRadius=dp(12).toFloat()
                setColor(ExpeditionThemes.current.surface)
                setStroke(dp(1),ExpeditionThemes.current.gold)
            }
            setPadding(dp(11),dp(10),dp(11),dp(10))
        }
        panel.addView(label("SETTINGS",19,sand).apply {
            gravity=Gravity.CENTER
            setTypeface(Typeface.SERIF,Typeface.BOLD)
            letterSpacing=.11f
        },LinearLayout.LayoutParams(-1,dp(30)))
        panel.addView(label("EXPEDITION / TOOLS & DISPLAY",10,sage).apply {
            gravity=Gravity.CENTER
        },LinearLayout.LayoutParams(-1,dp(20)))
        val tabBar=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        panel.addView(tabBar,LinearLayout.LayoutParams(-1,dp(40)))
        // Quick updater stays visible in FIELD, DISPLAY and DATA tabs.
        // Keep the existing update checker and install flow unchanged.
        panel.addView(action("↻  CHECK FOR UPDATES").apply {
            applyExpeditionStyle(this,true)
            textSize=11f
            setOnClickListener {
                layersPopup?.dismiss()
                checkForUpdates()
            }
        },LinearLayout.LayoutParams(-1,dp(44)).apply {
            setMargins(dp(2),dp(6),dp(2),dp(6))
        })
        val optionGrid=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        panel.addView(optionGrid,LinearLayout.LayoutParams(-1,-2))
        val tabs=listOf("FIELD","DISPLAY","DATA")
        val tabButtons=mutableListOf<Button>()
        var selectedTab="FIELD"
        fun render(tab:String) {
            selectedTab=tab
            tabButtons.forEachIndexed {i,button->applyExpeditionStyle(button,tabs[i]==tab)}
            optionGrid.removeAllViews()
            val operations:List<Pair<String,()->Unit>> = when(tab) {
                "FIELD"->listOf(
                    "⌁  RECOVER GPS" to {scanSmartPaths()},
                    "×  CLEAR PREVIEW" to {inferredPaths=emptyList();refresh()},
                    "◈  STATISTICS" to {showExplorationStats()},
                    "◷  TIME MACHINE" to {showTimeMachine()}
                )
                "DISPLAY"->listOf(
                    "FOG  ${if(fogEnabled) "ON" else "OFF"}" to {
                        fogEnabled=!fogEnabled;fogOverlay.fogVisible=fogEnabled;refresh()
                    },
                    "MIST  ${fogOverlay.opacity}" to {
                        fogOverlay.opacity=if(fogOverlay.opacity>=235)125 else fogOverlay.opacity+35;refresh()
                    },
                    "MARKERS  ${if(fogOverlay.showCheckpoints) "ON" else "OFF"}" to {
                        fogOverlay.showCheckpoints=!fogOverlay.showCheckpoints;refresh()
                    },
                    "BACKTRACK  ${if(fogOverlay.backtrack) "ON" else "OFF"}" to {
                        fogOverlay.backtrack=!fogOverlay.backtrack;refresh()
                    },
                    "ROUTE  ${if(fogOverlay.showRoute) "ON" else "OFF"}" to {
                        fogOverlay.showRoute=!fogOverlay.showRoute;refresh()
                    },
                    "MALLS  ${if(layerVisible("MALLS"))"ON" else "OFF"}" to {
                        setLayerVisible("MALLS",!layerVisible("MALLS"))
                    },
                    "TAXI STANDS  ${if(taxiStandsEnabled) "ON" else "OFF"}" to {
                        taxiStandsEnabled=!taxiStandsEnabled
                        getSharedPreferences("terra_taxi_settings",Context.MODE_PRIVATE)
                            .edit().putBoolean("enabled",taxiStandsEnabled).apply()
                        if(taxiStandsEnabled)scheduleTaxiStandsRefresh()
                        refresh()
                    }
                )
                else->listOf(
                    "OFFLINE  ${if(offline) "ON" else "OFF"}" to {
                        offline=!offline
                        map.setUseDataConnection(!offline)
                        status.text=if(offline)"OFFLINE / LOCAL TILES ONLY"
                            else "ONLINE / MAP TILES ENABLED"
                    },
                    "CACHE AREA" to {preloadOfflineArea()},
                    "BACKUP / DATA" to {showDataMenu()},
                    "RESET EXPLORATION" to {confirmExplorationReset()}
                )
            }
            operations.chunked(2).forEach {items->
                val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
                items.forEach {(name,callback)->
                    val button=action(name).apply {
                        applyExpeditionStyle(this)
                        textSize=10f
                        if(name=="RESET EXPLORATION")setTextColor(Color.rgb(234,146,120))
                        setOnClickListener {
                            callback()
                            if(tab=="DISPLAY" || (tab=="DATA" && name.startsWith("OFFLINE"))) {
                                render(tab)
                            } else {
                                layersPopup?.dismiss()
                            }
                        }
                    }
                    val params=LinearLayout.LayoutParams(0,dp(49),1f).apply {
                        setMargins(dp(2),dp(3),dp(2),dp(3))
                    }
                    row.addView(button,params)
                }
                if(items.size==1)row.addView(View(this),LinearLayout.LayoutParams(0,dp(49),1f))
                optionGrid.addView(row)
            }
            if(tab=="DISPLAY") {
                optionGrid.addView(label("INTERFACE COLOR / CHOOSE STYLE",12,sand).apply {
                    setPadding(dp(5),dp(7),0,dp(4))
                    setTypeface(Typeface.SERIF,Typeface.BOLD)
                })
                val themes=LinearLayout(this).apply {
                    orientation=LinearLayout.HORIZONTAL
                }
                ExpeditionThemes.all().forEach {palette ->
                    val selected=palette.id==ExpeditionThemes.current.id
                    val button=action((if(selected)"✓ " else "")+palette.label.uppercase()).apply {
                        textSize=10f
                        setTextColor(palette.light)
                        background=GradientDrawable().apply {
                            cornerRadius=dp(8).toFloat()
                            setColor(palette.surface)
                            setStroke(dp(if(selected)3 else 1),palette.gold)
                        }
                        setOnClickListener {
                            if(!selected) {
                                ExpeditionThemes.select(this@MainActivity,palette.id)
                                layersPopup?.dismiss()
                                recreate()
                            }
                        }
                    }
                    themes.addView(button,LinearLayout.LayoutParams(0,dp(48),1f).apply {
                        setMargins(dp(2),dp(2),dp(2),dp(2))
                    })
                }
                optionGrid.addView(themes)
                optionGrid.addView(label("MILITARY: OLIVE / BRASS   •   EXPLORER: PINE / GOLD   •   CIVIL: NAVY / COPPER",9,sage).apply {
                    setPadding(dp(4),dp(2),dp(2),dp(5))
                    maxLines=2
                })
            }
        }
        tabs.forEach {tab->
            val button=action(tab).apply{
                textSize=10f
                setOnClickListener {render(tab)}
            }
            tabButtons.add(button)
            tabBar.addView(button,LinearLayout.LayoutParams(0,dp(35),1f).apply{
                setMargins(dp(2),dp(1),dp(2),dp(1))
            })
        }
        render("FIELD")
        panel.addView(label("TAP OUTSIDE TO CLOSE",9,sage).apply{
            gravity=Gravity.CENTER
        },LinearLayout.LayoutParams(-1,dp(21)))
        val viewport=android.widget.ScrollView(this).apply {
            isFillViewport=false
            addView(panel)
        }
        val width=dp(312).coerceAtMost(resources.displayMetrics.widthPixels-dp(22))
        layersPopup=PopupWindow(viewport,width,-2,true).apply {
            isOutsideTouchable=true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            elevation=dp(8).toFloat()
            setOnDismissListener {
                if(expeditionBottom?.selected==6)expeditionBottom?.selected=-1
            }
            expeditionBottom?.selected=6
            showAtLocation(map,Gravity.END or Gravity.BOTTOM,dp(9),dp(66))
        }
    }
    override fun onSaveInstanceState(outState:Bundle) {
        super.onSaveInstanceState(outState)
        attachmentPoint?.let {
            outState.putDouble("field_photo_lat",it.latitude)
            outState.putDouble("field_photo_lon",it.longitude)
        }
        if(attachmentNoteTime!=null)outState.putLong("attachment_note_time",attachmentNoteTime!!)
        outState.putString("objective_photo_target",objectivePhotoTargetId)
        outState.putString("objective_photo_permission",objectivePhotoPermissionId)
        cameraUri?.let {outState.putString("objective_camera_uri",it.toString())}
        snapCameraUri?.let{outState.putString("snap_camera_uri",it.toString())}
        outState.putString("snap_capture_kind",snapCaptureKind)
        snapDraft?.let{
            outState.putString("snap_draft_filename",it.filename)
            outState.putString("snap_draft_kind",it.kind)
            outState.putString("snap_draft_title",it.title)
        }
    }

    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)

        if(requestCode==92 || requestCode==93) {
            finishSnapCamera(requestCode,resultCode,data)
            return
        }
        if(requestCode==87) {
            val id=objectivePhotoTargetId
            val uri=cameraUri
            if(resultCode==RESULT_OK && id!=null && uri!=null) {
                val objective=allObjectives()
                    .firstOrNull{it.id==id}
                if(objective!=null) {
                    val file=File(filesDir,"mission_"+System.currentTimeMillis()+".jpg")
                    runCatching {
                        contentResolver.openInputStream(uri)?.use {input->
                            file.outputStream().use {output->input.copyTo(output)}
                        } ?: error("Cannot read camera image")
                        require(file.length()>0L) {"Empty camera image"}
                        val idx=notes.indexOfFirst{it.kind=="CHECKPOINT" && it.title==objective.name}
                        if(idx>=0) {
                            notes[idx]=notes[idx].copy(attachments=notes[idx].attachments+file.name)
                        } else {
                            notes.add(FieldNote(objective.latitude,objective.longitude,
                                objective.name,"CHECKPOINT",objective.brief,
                                System.currentTimeMillis(),"⚑",listOf(file.name)))
                        }
                        // Completion is still gated by fresh GPS + a newly
                        // captured, successfully saved photo for EACH stop.
                        fieldNotes.save(notes)
                        objectiveStore.complete(id)
                        if(journeyStore.enabled && id in journeyStore.ids()) {
                            val next=journeyStore.next{objectiveStore.completed(it)}
                            objectiveStore.active=next
                            val (done,total)=journeyStore.progress{objectiveStore.completed(it)}
                            status.text=if(next==null)
                                terms.journey+" COMPLETE / "+done+"/"+total+" STOPS"
                            else terms.single+" COMPLETE / "+terms.journey+
                                " "+done+"/"+total+" / NEXT READY"
                        } else status.text=terms.single+" COMPLETED / GPS + PHOTO VERIFIED"
                        refresh()
                    }.onFailure {
                        file.delete()
                        status.text=terms.single+" NOT COMPLETED / PHOTO SAVE FAILED"
                    }
                } else status.text=terms.single+" NOT COMPLETED / DESTINATION NOT FOUND"
            } else status.text=terms.single+" NOT COMPLETED / PHOTO CANCELLED"
            uri?.let {runCatching{contentResolver.delete(it,null,null)}}
            cameraUri=null
            objectivePhotoTargetId=null
            return
        }

        if(requestCode==88) {
            val uri=cameraUri
            val point=attachmentPoint
            if(resultCode==RESULT_OK && uri!=null && point!=null) {
                val file=File(filesDir,"field_photo_"+System.currentTimeMillis()+".jpg")
                runCatching {
                    contentResolver.openInputStream(uri)?.use {source->
                        file.outputStream().use{destination->source.copyTo(destination)}
                    } ?: error("Photo unavailable")
                    require(file.length()>0L){"Empty photo"}
                    val newPhoto=FieldNote(point.latitude,point.longitude,"Field photo",
                        "PHOTO",file.name,System.currentTimeMillis(),"▣")
                    notes.add(newPhoto)
                    fieldNotes.save(notes)
                    status.text="PHOTO SAVED / THUMBNAIL READY"
                    refresh()
                    handler.post{if(!isFinishing && !isDestroyed)showExisting(newPhoto)}
                }.onFailure {
                    file.delete()
                    status.text="PHOTO / SAVE FAILED"
                }
            } else status.text="PHOTO / CANCELLED"
            uri?.let{runCatching{contentResolver.delete(it,null,null)}}
            cameraUri=null
            attachmentPoint=null
            return
        }
        if(requestCode==91) {
            val uri=data?.data
            if(resultCode==RESULT_OK && uri!=null) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use{GpsDiagnostics(this).exportTo(it)}
                        ?: error("Cannot write diagnostics")
                    status.text="GPS DIAGNOSTICS / CSV EXPORTED"
                }.onFailure {status.text="GPS DIAGNOSTICS / EXPORT FAILED"}
            }
            return
        }
        if(requestCode==85 || requestCode==86) {
            val uri=data?.data
            if(resultCode==RESULT_OK && uri!=null) {
                if(requestCode==85) {
                    status.text="BACKUP / EXPORTING IN BACKGROUND"
                    Thread {
                        val result=runCatching {
                            contentResolver.openOutputStream(uri)?.use{
                                TerraArchive(this).exportTo(it)
                            }?:error("Cannot write backup")
                        }
                        runOnUiThread {
                            if(!isFinishing && !isDestroyed)status.text=
                                if(result.isSuccess)"BACKUP / EXPORTED"
                                else "BACKUP / EXPORT FAILED"
                        }
                    }.start()
                } else {
                    // Never import while GPS can modify the same preferences.
                    if(tracking || TrackingService.running) {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("STOP TRACKING BEFORE RESTORE")
                            .setMessage("Stop TRACK, then choose your backup again. "+
                                "This prevents new GPS fixes mixing with restored data.")
                            .setPositiveButton("OK",null).show()
                        return
                    }
                    status.text="BACKUP / CHECKING ARCHIVE"
                    Thread {
                        val inspect=runCatching {
                            contentResolver.openInputStream(uri)?.use{
                                TerraArchive(this).preview(it)
                            }?:error("Cannot open backup")
                        }
                        runOnUiThread {
                            if(isFinishing || isDestroyed)return@runOnUiThread
                            inspect.onFailure {
                                status.text="BACKUP / INVALID FILE"
                                android.app.AlertDialog.Builder(this)
                                    .setTitle("BACKUP NOT VALID")
                                    .setMessage("Could not validate that ZIP. "+
                                        "Nothing was changed. "+(it.message?:""))
                                    .setPositiveButton("OK",null).show()
                            }
                            inspect.onSuccess {summary->
                                android.app.AlertDialog.Builder(this)
                                    .setTitle("REPLACE TERRA DATA?")
                                    .setMessage("Selected archive contains:\n"+
                                        summary.describe()+"\n\n"+
                                        "Restoring replaces your current map, "+
                                        "Journal, Journey and objective progress. "+
                                        "A recovery snapshot will be staged first. "+
                                        "Export a separate backup before continuing.")
                                    .setNegativeButton("CANCEL",null)
                                    .setPositiveButton("REPLACE DATA"){_,_->
                                        if(tracking || TrackingService.running) {
                                            status.text="BACKUP / STOP TRACK FIRST"
                                        } else {
                                            status.text="BACKUP / RESTORING / DO NOT EXIT"
                                            restoreProgressDialog?.dismiss()
                                            restoreProgressDialog=android.app.AlertDialog.Builder(this)
                                                .setTitle("RESTORING TERRA PROFILE")
                                                .setMessage("Your data is being restored and verified. "+
                                                    "Map tools are temporarily unavailable.")
                                                .setCancelable(false).create().also{it.show()}
                                            Thread {
                                                val restored=runCatching {
                                                    contentResolver.openInputStream(uri)?.use{
                                                        TerraArchive(this).importFrom(it)
                                                    }?:error("Cannot read backup")
                                                }
                                                runOnUiThread {
                                                    restoreProgressDialog?.dismiss()
                                                    restoreProgressDialog=null
                                                    if(isFinishing || isDestroyed)return@runOnUiThread
                                                    if(restored.isSuccess) {
                                                        // A profile restore also changes cached
                                                        // world objectives, Journey, themes and
                                                        // mission progress. Recreate the Activity
                                                        // instead of keeping stale in-memory stores
                                                        // and overlays from the previous profile.
                                                        android.widget.Toast.makeText(this,
                                                            "Backup restored — reloading map",
                                                            android.widget.Toast.LENGTH_SHORT).show()
                                                        recreate()
                                                    } else {
                                                        val rollbackPending=getSharedPreferences(
                                                            "terra_restore_transaction_v1",Context.MODE_PRIVATE)
                                                            .getBoolean("in_progress",false)
                                                        status.text=if(rollbackPending)
                                                            "BACKUP / RECOVERY REQUIRED"
                                                            else "BACKUP / ORIGINAL DATA RESTORED"
                                                        android.app.AlertDialog.Builder(this)
                                                            .setTitle(if(rollbackPending)
                                                                "RESTORE INCOMPLETE — CLOSE TERRA"
                                                                else "RESTORE FAILED — ORIGINAL DATA PRESERVED")
                                                            .setMessage(if(rollbackPending)
                                                                "Rollback is still pending. Close TERRA "+
                                                                "to prevent editing a partially restored "+
                                                                "profile. Reopen to retry recovery. "+
                                                                "Do not clear application data."
                                                                else "The archive could not be imported. "+
                                                                "Your previous profile was restored.")
                                                            .setCancelable(!rollbackPending)
                                                            .setPositiveButton(if(rollbackPending)
                                                                "CLOSE TERRA" else "OK"){_,_->
                                                                if(rollbackPending)finishAffinity()
                                                            }.show()
                                                    }
                                                }
                                            }.start()
                                        }
                                    }.show()
                            }
                        }
                    }.start()
                }
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
                        status.text="PHOTO ATTACHED / THUMBNAIL READY"
                        val updated=notes[idx]
                        handler.post {if(!isFinishing && !isDestroyed)showExisting(updated)}
                    } else file.delete()
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
                    val newPhoto=FieldNote(point.latitude,point.longitude,"Photo",
                        "PHOTO",file.name,System.currentTimeMillis(),"▣")
                    notes.add(newPhoto)
                    fieldNotes.save(notes)
                    status.text="PHOTO ATTACHED / THUMBNAIL READY"
                    refresh()
                    handler.post{if(!isFinishing && !isDestroyed)showExisting(newPhoto)}
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
    private fun updateHeadingLockUi() {
        headingButton?.apply {
            text=if(headingUp)"⇧\nUP" else "N"
            contentDescription=if(headingUp)
                "Map locked to phone direction. Tap to return north up"
                else "Map north up. Tap to lock map to phone direction"
            setTextColor(ExpeditionThemes.current.light)
            background=GradientDrawable().apply{
                cornerRadius=dp(10).toFloat()
                setColor(ExpeditionThemes.current.surface)
                setStroke(dp(if(headingUp)3 else 1),
                    if(headingUp)ExpeditionThemes.current.gold
                    else ExpeditionThemes.current.secondary)
            }
        }
        expeditionHeader?.headingUp=headingUp
    }

    private fun toggleHeadingLock() {
        if(!compassEnabled) {
            status.text="HEADING-UP / COMPASS SENSOR NOT AVAILABLE"
            return
        }
        headingUp=!headingUp
        getSharedPreferences("terra_map_orientation_v1",Context.MODE_PRIVATE)
            .edit().putBoolean("heading_up",headingUp).apply()
        if(headingUp) {
            followUser=true
            fogOverlay.currentPosition?.let{map.controller.setCenter(it)}
            lastHeadingRotationAt=0L
            headingTarget=map.mapOrientation
            postHeadingFrame()
            status.text=if(fogOverlay.currentPosition==null)
                "HEADING-UP / WAITING FOR GPS FIX"
                else "HEADING-UP / MAP FOLLOWS YOUR DIRECTION"
        } else {
            map.removeCallbacks(headingFrame)
            headingFramePosted=false
            map.mapOrientation=0f
            status.text="NORTH-UP / FIXED COMPASS"
        }
        updateHeadingLockUi()
        map.postInvalidateOnAnimation()
    }

    private fun trueHeading(magneticDegrees:Float,now:Long):Float {
        val position=fogOverlay.currentPosition
        if(position!=null && (now-lastDeclinationLookup>15L*60_000L ||
                lastDeclinationLookup==0L)) {
            trueNorthDeclination=android.hardware.GeomagneticField(
                position.latitude.toFloat(),position.longitude.toFloat(),0f,
                System.currentTimeMillis()).declination
            lastDeclinationLookup=now
        }
        return MapHeadingPolicy.normalize(magneticDegrees+trueNorthDeclination)
    }

    override fun onSensorChanged(event:SensorEvent) {
        if(event.sensor.type!=Sensor.TYPE_ROTATION_VECTOR)return
        SensorManager.getRotationMatrixFromVector(rotation,event.values)
        // Compass azimuth is in device coordinates. Remap it to screen-top
        // when Android rotates the UI, so left/right remain intuitive.
        val displayRotation=window.decorView.display?.rotation
            ?:android.view.Surface.ROTATION_0
        val adjusted=when(displayRotation) {
            android.view.Surface.ROTATION_90 ->
                SensorManager.remapCoordinateSystem(rotation,
                    SensorManager.AXIS_Y,SensorManager.AXIS_MINUS_X,correctedRotation)
            android.view.Surface.ROTATION_180 ->
                SensorManager.remapCoordinateSystem(rotation,
                    SensorManager.AXIS_MINUS_X,SensorManager.AXIS_MINUS_Y,correctedRotation)
            android.view.Surface.ROTATION_270 ->
                SensorManager.remapCoordinateSystem(rotation,
                    SensorManager.AXIS_MINUS_Y,SensorManager.AXIS_X,correctedRotation)
            else->false
        }
        SensorManager.getOrientation(if(adjusted)correctedRotation else rotation,orientation)
        val magnetic=MapHeadingPolicy.normalize(
            Math.toDegrees(orientation[0].toDouble()).toFloat())
        val now=android.os.SystemClock.uptimeMillis()
        val current=trueHeading(magnetic,now)
        val degrees=current.toInt()
        val change=if(lastCompassHeading<0)360
            else kotlin.math.abs((degrees-lastCompassHeading+540)%360-180)
        if(change<2 && now-lastCompassPaintElapsed<240L)return
        lastCompassHeading=degrees
        lastCompassPaintElapsed=now
        compassView.text="▲ N %03d°".format(degrees)
        expeditionHeader?.bearing=degrees
        fogOverlay.northAzimuth=current
        if(headingUp) {
            headingTarget=MapHeadingPolicy.headingUpOrientation(current)
            postHeadingFrame()
        }
        // Map repaint is paced by animation frames rather than sensor spikes.
        if(!headingUp && !map.isInLayout)map.postInvalidateOnAnimation()
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int) {}
    private fun startTracking() {
        if(TerraArchive.importInProgress ||
            getSharedPreferences("terra_restore_transaction_v1",Context.MODE_PRIVATE)
                .getBoolean("in_progress",false)) {
            status.text="TRACK / WAIT FOR BACKUP RESTORE TO FINISH"
            return
        }
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return
        try {
            startForegroundService(Intent(this,TrackingService::class.java).apply {
                action=TrackingService.ACTION_START
            })
        } catch(error:Exception) {
            tracking=false
            expeditionBottom?.trackingActive=false
            status.text="TRACK COULD NOT START / CHECK LOCATION PERMISSION"
            showLocationPermissionHelp()
            return
        }
        tracking=true
        lastVerifiedGpsElapsed=android.os.SystemClock.elapsedRealtime()
        expeditionBottom?.trackingActive=true
        expeditionBottom?.gpsSearching=true
        trackButton.text="STOP GPS"
        status.text="RECON ACTIVE / BACKGROUND ENABLED"
        if(android.os.Build.VERSION.SDK_INT>=29 && !backgroundGpsGranted()) {
            val prefs=getSharedPreferences("terra_tracking_state",Context.MODE_PRIVATE)
            if(!prefs.getBoolean("background_explained",false)) {
                prefs.edit().putBoolean("background_explained",true).apply()
                handler.postDelayed({if(!isFinishing)explainBackgroundGps()},650L)
            }
        }
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
        text=t
        textSize=11.5f
        isAllCaps=false
        gravity=Gravity.CENTER
        includeFontPadding=false
        setTextColor(sand)
        minHeight=0
        minimumHeight=0
        minWidth=0
        minimumWidth=0
        setPadding(dp(6),dp(3),dp(6),dp(3))
        background=GradientDrawable().apply {
            cornerRadius=dp(7).toFloat()
            setColor(ExpeditionThemes.current.surface)
            setStroke(dp(1),ExpeditionThemes.current.raised)
        }
    }
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, grants: IntArray) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 10) {
            if(grants.isNotEmpty() && grants[0]==PackageManager.PERMISSION_GRANTED)
                startTracking()
            else showLocationPermissionHelp()
        }
        if (code==14) {
            if(grants.isNotEmpty() && grants[0]==PackageManager.PERMISSION_GRANTED)
                locateOnce()
            else showLocationPermissionHelp()
        }
        if (code == 13)status.text=if(backgroundGpsGranted())
            "BACKGROUND GPS / PERMISSION GRANTED" else "BACKGROUND GPS / PERMISSION LIMITED"
        if(code==11) {
            val permitted=grants.isNotEmpty() && grants[0]==PackageManager.PERMISSION_GRANTED
            if(permitted) {
                if(snapVoicePermissionPending)startVoice(null,forSnap=true)
                else voicePoint?.let{startVoice(it)}
            } else status.text="MICROPHONE PERMISSION REQUIRED"
            snapVoicePermissionPending=false
        }
        if(code==12) {
            val id=objectivePhotoPermissionId
            objectivePhotoPermissionId=null
            if(grants.isNotEmpty() && grants[0]==PackageManager.PERMISSION_GRANTED && id!=null) {
                allObjectives().firstOrNull{it.id==id}
                    ?.let {requestObjectiveProofPhoto(it)}
            } else status.text=terms.single+" / LOCATION PERMISSION REQUIRED"
        }
    }
    override fun onResume() {
        super.onResume()
        if(!::map.isInitialized)return
        map.onResume()
        if(headingUp) {
            lastHeadingRotationAt=0L
            postHeadingFrame()
        }
        lastVerifiedGpsElapsed=android.os.SystemClock.elapsedRealtime()
        handler.removeCallbacks(gpsStatusTick)
        handler.postDelayed(gpsStatusTick,15000L)
        handler.removeCallbacks(solarTick)
        updateSolarDial()
        loadDubaiTemperature()
        scheduleTaxiStandsRefresh()
        scheduleMallRefresh()
        scheduleGlobalObjectiveRefresh(180L)
        handler.postDelayed(solarTick,60000L)
        tracking=TrackingService.running
        val interrupted=!tracking &&
            getSharedPreferences("terra_tracking_state",Context.MODE_PRIVATE)
                .getBoolean("requested",false)
        expeditionBottom?.trackingActive=tracking
        val hasCachedFix=restoreLastKnownFix()
        val fixAge=if(hasCachedFix)
            (System.currentTimeMillis()-lastKnownFixTime).coerceAtLeast(0L)
            else Long.MAX_VALUE
        expeditionBottom?.gpsSearching=tracking && fixAge>25000L
        status.text=when {
            interrupted -> "TRACK INTERRUPTED / TAP TRACK TO RESUME"
            tracking && TrackingService.resting -> "GPS REST / BATTERY SAVER / AUTO WAKE"
            !hasCachedFix && tracking -> "GPS SEARCHING / AWAITING FIRST FIX"
            hasCachedFix && tracking && fixAge<=25000L ->
                "GPS RECENT / ${fixAge/1000L}s AGO / ±${fogOverlay.positionAccuracy.toInt()}m"
            hasCachedFix && tracking -> lastKnownLabel()+" / AWAITING FRESH GPS"
            hasCachedFix -> lastKnownLabel()+" / TRACK STOPPED"
            else -> "TRACK STOPPED"
        }
        if(::trackButton.isInitialized)trackButton.text=if(tracking)"STOP GPS" else "TRACK"
        if(::store.isInitialized) {
            records.clear();records.addAll(store.load())
            segments.clear();segments.addAll(pathStore.load())
            notes.clear();notes.addAll(fieldNotes.load())
            refresh()
        }
        val filter=IntentFilter(TrackingService.ACTION_FIX).apply {
            addAction(TrackingService.ACTION_RESET_COMPLETE)
            addAction(TrackingService.ACTION_MODE)
            addAction(TrackingService.ACTION_STATE)
        }
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
        if(::map.isInitialized)map.removeCallbacks(headingFrame)
        headingFramePosted=false
        lastHeadingRotationAt=0L
        handler.removeCallbacks(gpsStatusTick)
        if(::map.isInitialized) {
            val loc=map.mapCenter
            if(loc.latitude.isFinite() && loc.longitude.isFinite()) {
                getSharedPreferences("terra_map_view_v1",Context.MODE_PRIVATE).edit()
                    .putString("lat",loc.latitude.toString())
                    .putString("lon",loc.longitude.toString())
                    .putFloat("zoom",map.zoomLevelDouble.toFloat()).apply()
            }
        }
        handler.removeCallbacks(globalRefreshTask)
        handler.removeCallbacks(mallRefreshTask)
        handler.removeCallbacks(taxiRefreshTask)
        handler.removeCallbacks(repositionBubbleAfterPan)
        handler.removeCallbacks(solarTick)
        super.onPause()
        if(::sensors.isInitialized)sensors.unregisterListener(this)
        if(::map.isInitialized)map.onPause()
        if(receiverRegistered) {unregisterReceiver(trackingReceiver);receiverRegistered=false}
    }
    override fun onDestroy() {
        if(::map.isInitialized)map.removeCallbacks(headingFrame)
        headingFramePosted=false
        restoreProgressDialog?.dismiss()
        restoreProgressDialog=null
        handler.removeCallbacks(gpsStatusTick)
        if(::fogOverlay.isInitialized)fogOverlay.release()
        activeVoiceMeterTick?.let{handler.removeCallbacks(it)}
        activeVoiceMeterTick=null
        runCatching{recorder?.stop()}
        runCatching{recorder?.release()}
        recorder=null
        audioFile?.delete()
        audioFile=null
        audioPlayback?.let{runCatching{it.stop()};it.release()}
        audioPlayback=null
        super.onDestroy()
        handler.removeCallbacks(simulationStep)
        handler.removeCallbacks(taxiRefreshTask)
        handler.removeCallbacks(mallRefreshTask)
        handler.removeCallbacks(globalRefreshTask)
        globalObjectiveWorker.shutdown()
        handler.removeCallbacks(solarTick)
        solarPopup?.dismiss()
        overlayFiltersPopup?.dismiss()
        layersPopup?.dismiss()
        poiPopup?.dismiss()
        radialMenu?.dismiss()
        objectivesPopup?.dismiss()
        paintPopup?.dismiss()
    }
}
