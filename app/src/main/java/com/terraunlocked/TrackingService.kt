package com.terraunlocked

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import com.google.android.gms.location.*
import org.osmdroid.util.GeoPoint

class TrackingService:Service() {
    companion object {
        const val ACTION_START="com.terraunlocked.START_TRACK"
        const val ACTION_STOP="com.terraunlocked.STOP_TRACK"
        const val ACTION_FIX="com.terraunlocked.LOCATION_FIX"
        const val ACTION_STATE="com.terraunlocked.TRACK_STATE"
        const val ACTION_RESET_EXPLORATION="com.terraunlocked.RESET_EXPLORATION"
        const val ACTION_RESET_COMPLETE="com.terraunlocked.RESET_EXPLORATION_COMPLETE"
        var running=false
    }
    private val client by lazy {LocationServices.getFusedLocationProviderClient(this)}
    private val store by lazy {DiscoveryStore(this)}
    private val fieldNotes by lazy {FieldNotes(this)}
    private var lastVisitCheck=0L
    private var previous:Location?=null
    private var lastSaved:Location?=null
    private var motionAnchor:Location?=null
    private var stationarySince=0L
    private var lastSwitch=0L
    private var ecoMode=false
    private var lastBroadcast=0L
    private var lastVisual:Location?=null
    private fun broadcastFix(loc:Location,revealed:Boolean,eco:Boolean,weak:Boolean=false,
        sampleTime:Long=System.currentTimeMillis()) {
        val previous=lastVisual
        // Filter mild stationary wander for the on-screen marker only, never recorded GPS.
        val stationary=!loc.hasSpeed() || loc.speed<1.5f
        val close=previous!=null && previous.distanceTo(loc)<maxOf(12f,loc.accuracy*1.5f)
        val shown=Location(loc)
        if(stationary && close && previous!=null && loc.accuracy<=35f) {
            shown.latitude=previous.latitude*.65+loc.latitude*.35
            shown.longitude=previous.longitude*.65+loc.longitude*.35
        }
        if(!weak)lastVisual=shown
        sendBroadcast(Intent(ACTION_FIX).setPackage(packageName).apply {
            putExtra("lat",shown.latitude);putExtra("lon",shown.longitude)
            putExtra("rawLat",loc.latitude);putExtra("rawLon",loc.longitude)
            putExtra("accuracy",loc.accuracy);putExtra("revealed",revealed)
            putExtra("eco",eco);putExtra("weak",weak)
            putExtra("timestamp",sampleTime)
            putExtra("speed",if(loc.hasSpeed())loc.speed else -1f)
        })
    }


    private fun switchMode(eco:Boolean) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(!running || ecoMode==eco || now-lastSwitch<20000L)return
        ecoMode=eco
        lastSwitch=now
        val request=if(eco) {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,15000L)
                .setMinUpdateIntervalMillis(5000L).setMaxUpdateDelayMillis(15000L)
                .setMinUpdateDistanceMeters(0f).build()
        } else {
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,5000L)
                .setMinUpdateIntervalMillis(2500L).setMaxUpdateDelayMillis(10000L)
                .setMinUpdateDistanceMeters(5f).build()
        }
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) {
            client.requestLocationUpdates(request,callback,mainLooper)
        }
        val text=if(eco)"Stationary / battery saver" else "Moving / high accuracy"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1801,makeNotification(text))
    }

    private fun makeNotification(message:String):Notification {
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,TrackingService::class.java).apply {
            action=ACTION_STOP
        },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this,"terra_recon")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TerraUnlocked / RECON ACTIVE")
            .setContentText(message).setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause,"STOP",stop)
            .setOngoing(true).build()
    }
    private val callback=object:LocationCallback() {
        override fun onLocationResult(result:LocationResult) {
            if(!running)return
            result.locations.forEach {loc->
                if(!loc.hasAccuracy() || !loc.latitude.isFinite() || !loc.longitude.isFinite())return@forEach
                // Ignore stale batched/cached positions; a delayed old fix can appear
                // as a wild GPS jump beside skyscrapers.
                val fixAgeMs=(android.os.SystemClock.elapsedRealtimeNanos()-loc.elapsedRealtimeNanos)/1000000L
                if(fixAgeMs>25000L || fixAgeMs< -5000L)return@forEach
                val prev=previous
                val seconds=if(prev!=null)(loc.elapsedRealtimeNanos-prev.elapsedRealtimeNanos)/1e9 else 0.0
                val jump=prev!=null && seconds>0.5 &&
                    prev.distanceTo(loc)>maxOf(45f,2f*maxOf(prev.accuracy,loc.accuracy)) &&
                    prev.distanceTo(loc)/seconds>55.0
                // Re-enter active mode from movement, even with temporarily weak accuracy.
                // Do this before rejecting locations to prevent a stuck ECO mode in cars.
                val anchorBeforeFilter=motionAnchor
                if(ecoMode && anchorBeforeFilter!=null) {
                    val traveled=anchorBeforeFilter.distanceTo(loc)
                    if((loc.hasSpeed() && loc.speed>3f) ||
                       (loc.accuracy<=100f && traveled>maxOf(35f,2f*loc.accuracy))) {
                        switchMode(false)
                        motionAnchor=loc
                        stationarySince=android.os.SystemClock.elapsedRealtime()
                    }
                }
                if(loc.accuracy>65f || jump) {
                    if(prev!=null && seconds>45.0)previous=null
                    // Report the uncertainty without recording inaccurate exploration.
                    val now=System.currentTimeMillis()
                    if(now-lastBroadcast>12000L && loc.accuracy<=120f) {
                        lastBroadcast=now
                        broadcastFix(loc,false,ecoMode,weak=true,sampleTime=now)
                    }
                    return@forEach
                }
                val nowElapsed=android.os.SystemClock.elapsedRealtime()
                val anchor=motionAnchor
                if(anchor==null) {
                    motionAnchor=loc
                    stationarySince=nowElapsed
                } else {
                    val threshold=maxOf(22f,2f*maxOf(anchor.accuracy,loc.accuracy))
                    val moving=anchor.distanceTo(loc)>threshold || (loc.hasSpeed() && loc.speed>2.5f)
                    if(moving) {
                        motionAnchor=loc
                        stationarySince=nowElapsed
                        if(ecoMode)switchMode(false)
                    } else if(!ecoMode && nowElapsed-stationarySince>=120000L) {
                        switchMode(true)
                    }
                }
                previous=loc
                val now=System.currentTimeMillis()
                // Do not make permanent discoveries from poor urban GPS readings.
                if(loc.accuracy>35f) {
                    if(now-lastBroadcast>=12000L) {
                        lastBroadcast=now
                        broadcastFix(loc,false,ecoMode,weak=true,sampleTime=now)
                    }
                    return@forEach
                }
                if(now-lastVisitCheck>60000L && loc.accuracy<=30f) {
                    lastVisitCheck=now
                    val entries=fieldNotes.load()
                    var changed=false
                    val updated=entries.map { note ->
                        val d=FloatArray(1)
                        Location.distanceBetween(loc.latitude,loc.longitude,note.latitude,note.longitude,d)
                        val lastVisit=note.visits.lastOrNull() ?: 0L
                        if(d[0]<=35f && now-lastVisit>21600000L) {
                            changed=true;note.copy(visits=note.visits+now)
                        } else note
                    }
                    if(changed)fieldNotes.save(updated)
                }
                val saved=lastSaved
                // Don't permanently reveal random stationary GPS wandering.
                // Moving users still get regular samples, including fast scooter/car trips.
                val step=saved?.distanceTo(loc) ?: Float.MAX_VALUE
                val movingSpeed=loc.hasSpeed() && loc.speed>=0.9f
                val threshold=if(movingSpeed)maxOf(12f,1.25f*loc.accuracy)
                    else maxOf(24f,1.9f*loc.accuracy)
                val moved=saved==null || step>=threshold
                if(moved) {
                    store.add(DiscoveryPoint(loc.latitude,loc.longitude,false,now))
                    lastSaved=loc
                }
                if(moved || now-lastBroadcast>=15000L) {
                    lastBroadcast=now
                    broadcastFix(loc,moved,ecoMode,sampleTime=now)
                }
            }
        }
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_RESET_EXPLORATION) {
            // Reset is handled on the service main thread, serialized with GPS fixes.
            // Disable tracking before clearing so no late callback can reveal the map.
            running=false
            client.removeLocationUpdates(callback)
            store.replaceAll(emptyList())
            PathStore(this).save(emptyList())
            sendBroadcast(Intent(ACTION_RESET_COMPLETE).setPackage(packageName))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if(intent?.action==ACTION_STOP) {stopTracking();return START_NOT_STICKY}
        if(running)return START_STICKY
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            stopSelf();return START_NOT_STICKY
        }
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("terra_recon","TerraUnlocked tracking",NotificationManager.IMPORTANCE_LOW))
        if(Build.VERSION.SDK_INT>=29)
            startForeground(1801,makeNotification("Moving / high accuracy"),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1801,makeNotification("Moving / high accuracy"))
        running=true
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("running",true))
        ecoMode=false
        lastVisual=null
        previous=null
        lastSaved=null
        motionAnchor=null
        stationarySince=0L
        lastSwitch=0L
        val request=LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,5000L)
            .setMinUpdateIntervalMillis(2500L)
            .setMaxUpdateDelayMillis(10000L)
            .setMinUpdateDistanceMeters(5f).build()
        client.requestLocationUpdates(request,callback,mainLooper)
        return START_STICKY
    }
    private fun stopTracking() {
        running=false
        client.removeLocationUpdates(callback)
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("running",false))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        running=false
        client.removeLocationUpdates(callback)
        super.onDestroy()
    }
}
