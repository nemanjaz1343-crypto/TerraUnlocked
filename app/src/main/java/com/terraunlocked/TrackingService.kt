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

    private fun switchMode(eco:Boolean) {
        val now=android.os.SystemClock.elapsedRealtime()
        if(!running || ecoMode==eco || now-lastSwitch<20000L)return
        ecoMode=eco
        lastSwitch=now
        val request=if(eco) {
            LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY,20000L)
                .setMinUpdateIntervalMillis(10000L).setMaxUpdateDelayMillis(30000L)
                .setMinUpdateDistanceMeters(10f).build()
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
            result.locations.forEach {loc->
                if(!loc.hasAccuracy() || !loc.latitude.isFinite() || !loc.longitude.isFinite())return@forEach
                val prev=previous
                val seconds=if(prev!=null)(loc.elapsedRealtimeNanos-prev.elapsedRealtimeNanos)/1e9 else 0.0
                val jump=prev!=null && seconds>0.5 &&
                    prev.distanceTo(loc)>maxOf(45f,2f*maxOf(prev.accuracy,loc.accuracy)) &&
                    prev.distanceTo(loc)/seconds>55.0
                if(loc.accuracy>65f || jump) {
                    if(prev!=null && seconds>45.0)previous=null
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
                val moved=saved==null || saved.distanceTo(loc)>=
                    maxOf(12f,1.1f*maxOf(saved.accuracy,loc.accuracy))
                if(moved) {
                    store.add(DiscoveryPoint(loc.latitude,loc.longitude,false,System.currentTimeMillis()))
                    lastSaved=loc
                }
                if(moved || now-lastBroadcast>=15000L) {
                lastBroadcast=now
                sendBroadcast(Intent(ACTION_FIX).setPackage(packageName).apply {
                    putExtra("lat",loc.latitude);putExtra("lon",loc.longitude)
                    putExtra("accuracy",loc.accuracy)
                    putExtra("revealed",moved)
                    putExtra("eco",ecoMode)
                })
                }
            }
        }
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
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
