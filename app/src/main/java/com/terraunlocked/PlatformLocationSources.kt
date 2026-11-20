package com.terraunlocked

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Independent Android platform providers complement Google's Fused provider.
 * GPS_PROVIDER delegates to every GNSS constellation supported by the phone's
 * chipset/firmware. NETWORK_PROVIDER is context only: it must not unlock fog.
 *
 * Keep callbacks on the service's main looper so its existing location filter,
 * discovery store and watchdog remain serialized.
 */
internal class PlatformLocationSources(
    context:Context,
    private val onLocation:(Location,String)->Unit,
    private val onEvent:(String)->Unit
) {
    private val appContext=context.applicationContext
    private val manager by lazy {
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }
    private val handler=Handler(Looper.getMainLooper())
    private var gpsRegistered=false
    private var networkRegistered=false
    private var statusRegistered=false
    private var lastSatelliteLogElapsed=0L
    private var lastSatelliteActivityElapsed=0L
    private var lastRecoveryAttemptElapsed=0L

    private val gpsListener=object:LocationListener {
        override fun onLocationChanged(location:Location) {
            onLocation(location,"GNSS")
        }
        override fun onProviderEnabled(provider:String) {onEvent("GNSS_PROVIDER_ENABLED")}
        override fun onProviderDisabled(provider:String) {onEvent("GNSS_PROVIDER_DISABLED")}
    }
    private val networkListener=object:LocationListener {
        override fun onLocationChanged(location:Location) {
            onLocation(location,"NETWORK")
        }
        override fun onProviderEnabled(provider:String) {onEvent("NETWORK_PROVIDER_ENABLED")}
        override fun onProviderDisabled(provider:String) {onEvent("NETWORK_PROVIDER_DISABLED")}
    }
    private val statusCallback=object:GnssStatus.Callback() {
        override fun onStarted() {
            lastSatelliteActivityElapsed=SystemClock.elapsedRealtime()
            onEvent("GNSS_ENGINE_STARTED")
        }
        override fun onStopped() {
            lastSatelliteActivityElapsed=SystemClock.elapsedRealtime()
            onEvent("GNSS_ENGINE_STOPPED")
        }
        override fun onFirstFix(ttffMillis:Int) {
            onEvent("GNSS_FIRST_FIX_${ttffMillis}MS")
        }
        override fun onSatelliteStatusChanged(status:GnssStatus) {
            val now=SystemClock.elapsedRealtime()
            // Every status callback proves the GNSS engine is active, even if it
            // cannot yet compute a position from obstructed satellites.
            lastSatelliteActivityElapsed=now
            if(now-lastSatelliteLogElapsed<45000L)return
            lastSatelliteLogElapsed=now
            val families=linkedSetOf<String>()
            var used=0
            for(i in 0 until status.satelliteCount) {
                if(status.usedInFix(i))used++
                val family=when(status.getConstellationType(i)) {
                    GnssStatus.CONSTELLATION_GPS -> "GPS"
                    GnssStatus.CONSTELLATION_GLONASS -> "GLO"
                    GnssStatus.CONSTELLATION_GALILEO -> "GAL"
                    GnssStatus.CONSTELLATION_BEIDOU -> "BDS"
                    GnssStatus.CONSTELLATION_QZSS -> "QZSS"
                    GnssStatus.CONSTELLATION_SBAS -> "SBAS"
                    else -> "OTHER"
                }
                families.add(family)
            }
            onEvent("GNSS_SATS_${status.satelliteCount}_USED_${used}_"+
                families.sorted().joinToString("+"))
        }
    }

    private fun permissionGranted():Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==
            PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun registerGps() {
        if(!permissionGranted()) {onEvent("GNSS_PERMISSION_MISSING");return}
        try {
            if(!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                onEvent("GNSS_PROVIDER_DISABLED")
                return
            }
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,1000L,0f,gpsListener,Looper.getMainLooper())
            gpsRegistered=true
            onEvent("GNSS_PROVIDER_REGISTERED")
        } catch(error:Exception) {
            gpsRegistered=false
            onEvent("GNSS_PROVIDER_REGISTER_FAILED_${error.javaClass.simpleName}")
        }
    }

    @Suppress("MissingPermission")
    private fun registerNetwork() {
        if(!permissionGranted())return
        try {
            if(!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                onEvent("NETWORK_PROVIDER_DISABLED")
                return
            }
            manager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,4000L,0f,networkListener,Looper.getMainLooper())
            networkRegistered=true
            onEvent("NETWORK_PROVIDER_REGISTERED")
        } catch(error:Exception) {
            networkRegistered=false
            onEvent("NETWORK_PROVIDER_REGISTER_FAILED_${error.javaClass.simpleName}")
        }
    }

    @Suppress("MissingPermission")
    fun start() {
        if(!permissionGranted())return
        val now=SystemClock.elapsedRealtime()
        lastSatelliteActivityElapsed=now
        lastRecoveryAttemptElapsed=now
        registerGps()
        registerNetwork()
        try {
            statusRegistered=manager.registerGnssStatusCallback(statusCallback,handler)
            if(!statusRegistered)onEvent("GNSS_STATUS_UNAVAILABLE")
        } catch(error:Exception) {
            statusRegistered=false
            onEvent("GNSS_STATUS_REGISTER_FAILED_${error.javaClass.simpleName}")
        }
    }

    /**
     * Never reset GNSS while satellite-status callbacks continue to arrive:
     * searching with poor sky visibility is not the same as a dead listener.
     */
    fun recoverIfSilent(gnssFixAgeMs:Long) {
        if(!permissionGranted())return
        val now=SystemClock.elapsedRealtime()
        if(!GpsRecoveryPolicy.recoverGnss(
                gnssFixAgeMs,now-lastSatelliteActivityElapsed,
                now-lastRecoveryAttemptElapsed))return
        lastRecoveryAttemptElapsed=now
        if(gpsRegistered)runCatching {manager.removeUpdates(gpsListener)}
        gpsRegistered=false
        registerGps()
        onEvent("GNSS_RESUBSCRIBE_AFTER_STATUS_SILENCE")
    }

    fun stop() {
        if(gpsRegistered)runCatching {manager.removeUpdates(gpsListener)}
        if(networkRegistered)runCatching {manager.removeUpdates(networkListener)}
        if(statusRegistered)runCatching {manager.unregisterGnssStatusCallback(statusCallback)}
        gpsRegistered=false
        networkRegistered=false
        statusRegistered=false
    }
}
