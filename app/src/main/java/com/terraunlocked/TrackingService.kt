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
    private var previous:Location?=null
    private var lastSaved:Location?=null
    private val callback=object:LocationCallback() {
        override fun onLocationResult(result:LocationResult) {
            result.locations.forEach {loc->
                if(!loc.hasAccuracy() || !loc.latitude.isFinite() || !loc.longitude.isFinite())return@forEach
                val prev=previous
                val seconds=if(prev!=null)(loc.elapsedRealtimeNanos-prev.elapsedRealtimeNanos)/1e9 else 0.0
                val jump=prev!=null && seconds>0.5 &&
                    prev.distanceTo(loc)>maxOf(45f,2f*maxOf(prev.accuracy,loc.accuracy)) &&
                    prev.distanceTo(loc)/seconds>15.0
                if(loc.accuracy>50f || jump)return@forEach
                previous=loc
                val saved=lastSaved
                val moved=saved==null || saved.distanceTo(loc)>=
                    maxOf(10f,1.1f*maxOf(saved.accuracy,loc.accuracy))
                if(moved) {
                    store.add(DiscoveryPoint(loc.latitude,loc.longitude,false,System.currentTimeMillis()))
                    lastSaved=loc
                }
                sendBroadcast(Intent(ACTION_FIX).setPackage(packageName).apply {
                    putExtra("lat",loc.latitude);putExtra("lon",loc.longitude)
                    putExtra("accuracy",loc.accuracy)
                    putExtra("revealed",moved)
                })
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
        val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,1,Intent(this,TrackingService::class.java).apply {
            action=ACTION_STOP
        },PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=Notification.Builder(this,"terra_recon")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("TerraUnlocked • RECON ACTIVE")
            .setContentText("Exploration continues while the screen is off")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause,"STOP",stop)
            .setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=29)
            startForeground(1801,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1801,notification)
        running=true
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("running",true))
        val request=LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,5000L)
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
