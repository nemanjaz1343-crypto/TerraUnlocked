package com.terraunlocked

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.*
import org.osmdroid.util.GeoPoint

class TrackingService:Service(),SensorEventListener {
    private lateinit var motionSensors:SensorManager
    private var accelerationEnergy=0.0
    private var lastMotionElapsed=0L
    private var lastSensorElapsed=0L
    private fun inertialMoving():Boolean {
        // Motion is supporting context, never a substitute for a valid GPS fix.
        val now=android.os.SystemClock.elapsedRealtime()
        return lastSensorElapsed>0 && now-lastSensorElapsed<5000L &&
            now-lastMotionElapsed<6500L
    }
    override fun onSensorChanged(event:SensorEvent) {
        if(event.sensor.type!=Sensor.TYPE_LINEAR_ACCELERATION || !running)return
        if(event.values.size<3)return
        val magnitude=kotlin.math.sqrt(
            (event.values[0]*event.values[0]+event.values[1]*event.values[1]+
                event.values[2]*event.values[2]).toDouble())
        accelerationEnergy=accelerationEnergy*.82+magnitude*.18
        val now=android.os.SystemClock.elapsedRealtime()
        lastSensorElapsed=now
        if(accelerationEnergy>.48) {
            lastMotionElapsed=now
            lastActiveMovementElapsed=now
        }
    }
    override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){}
    private fun startMotionSensors() {
        motionSensors=getSystemService(SENSOR_SERVICE) as SensorManager
        motionSensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            motionSensors.registerListener(this,it,200000)
        }
    }
    private fun stopMotionSensors() {
        if(::motionSensors.isInitialized)motionSensors.unregisterListener(this)
    }
    companion object {
        const val ACTION_START="com.terraunlocked.START_TRACK"
        const val ACTION_STOP="com.terraunlocked.STOP_TRACK"
        const val ACTION_FIX="com.terraunlocked.LOCATION_FIX"
        const val ACTION_STATE="com.terraunlocked.TRACK_STATE"
        const val ACTION_RESET_EXPLORATION="com.terraunlocked.RESET_EXPLORATION"
        const val ACTION_RESET_COMPLETE="com.terraunlocked.RESET_EXPLORATION_COMPLETE"
        var running=false
    }
    private val trackingPrefs by lazy {getSharedPreferences("terra_tracking_state",MODE_PRIVATE)}
    private val diagnostics by lazy {GpsDiagnostics(this)}
    private val classifier=MotionClassifier()
    private val heartbeatHandler=Handler(Looper.getMainLooper())
    private var lastFixElapsed=0L
    private var lastCallbackElapsed=0L
    private var lastMeasurementNanos=0L
    private var lastGapAlertElapsed=0L
    private var lastRecoveryElapsed=0L
    private var lastGoodFixElapsed=0L
    private var lastVerifiedFixElapsed=0L
    private var lastNoFixLogElapsed=0L
    private var stationaryRecoveryCount=0
    private var lastActiveMovementElapsed=0L
    private var lastProviderStatusElapsed=0L
    private var lastProviderAvailable:Boolean?=null
    private var pendingRecoveryAt=0L
    private var recoveryPending=false
    // Keep active GNSS frequent while the screen is locked. Android still controls
    // actual provider availability and may deliver fewer updates in urban canyons.
    private fun activeRequest():LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,3000L)
            .setMinUpdateIntervalMillis(1500L)
            .setMaxUpdateDelayMillis(0L)
            .setMinUpdateDistanceMeters(0f).build()

    private fun recoverGps(reason:String) {
        if(!running || recoveryPending)return
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            diagnostics.record(null,-1L,0.0,0.0,fixConfidence,motionProfile,
                "EVENT","GPS_PERMISSION_MISSING")
            return
        }
        recoveryPending=true
        pendingRecoveryAt=SystemClock.elapsedRealtime()
        diagnostics.record(null,-1L,0.0,0.0,fixConfidence,motionProfile,
            "EVENT","GPS_RESTART_"+reason)
        try {
            client.requestLocationUpdates(activeRequest(),callback,mainLooper)
                .addOnCompleteListener { task ->
                    recoveryPending=false
                    diagnostics.record(null,-1L,0.0,0.0,fixConfidence,motionProfile,
                        "EVENT",if(task.isSuccessful)"GPS_RESTART_OK" else "GPS_RESTART_FAILED")
                    if(!task.isSuccessful)
                        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                            .notify(1801,makeNotification("GPS unavailable / check location"))
                }
        } catch(error:Exception) {
            recoveryPending=false
            diagnostics.record(null,-1L,0.0,0.0,fixConfidence,motionProfile,
                "EVENT","GPS_RESTART_EXCEPTION")
        }
    }
    private val heartbeat=object:Runnable {
        override fun run() {
            if(!running)return
            val elapsed=SystemClock.elapsedRealtime()
            val age=elapsed-lastFixElapsed
            val callbackAge=elapsed-lastCallbackElapsed
            // A callback containing only cached measurements is not fresh GPS.
            if(callbackAge>90000L && elapsed-lastNoFixLogElapsed>=120000L) {
                diagnostics.record(null,callbackAge,0.0,0.0,fixConfidence,
                    motionProfile,"EVENT","NO_LOCATION_CALLBACK_90S")
            }
            // Distinguish missing callbacks from callbacks that contain unusable fixes.
            val goodAge=if(lastGoodFixElapsed>0L)elapsed-lastGoodFixElapsed else -1L
            if(goodAge>90000L && elapsed-lastNoFixLogElapsed>=120000L) {
                lastNoFixLogElapsed=elapsed
                diagnostics.record(null,goodAge,0.0,0.0,fixConfidence,
                    motionProfile,"EVENT","NO_USABLE_FIX_90S")
            }
            if(recoveryPending && elapsed-pendingRecoveryAt>45000L) {
                recoveryPending=false
                diagnostics.record(null,-1L,0.0,0.0,fixConfidence,
                    motionProfile,"EVENT","GPS_REQUEST_TIMEOUT")
            }
            if(lastFixElapsed>0L && age>45000L &&
                elapsed-lastGapAlertElapsed>60000L) {
                lastGapAlertElapsed=elapsed
                diagnostics.record(null,age,0.0,0.0,fixConfidence,
                    motionProfile,"NO_FIX","NO_CALLBACK_45S")
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(1801,makeNotification("GPS signal missing / "+(age/1000L)+"s"))
            }
            // A stream of inaccurate fixes is not a healthy tracking session:
            // restart the provider when no verified fix arrives for 90 seconds.
            // Do not let a stationary back-off delay recovery of a missing callback.
            val movingRecently=elapsed-lastActiveMovementElapsed<90000L
            val stationaryLong=elapsed-lastActiveMovementElapsed>180000L
            val callbackMissing=callbackAge>=60000L
            val verifiedAge=if(lastVerifiedFixElapsed>0L)
                elapsed-lastVerifiedFixElapsed else 0L
            val verifiedMissing=verifiedAge>=90000L && !ecoMode
            val recoveryInterval=when {
                callbackMissing -> 60000L
                verifiedMissing -> 90000L
                movingRecently -> 60000L
                stationaryLong -> 300000L
                else -> 180000L
            }
            if((age>=60000L || callbackMissing || verifiedMissing) &&
                elapsed-lastRecoveryElapsed>=recoveryInterval && !recoveryPending) {
                lastRecoveryElapsed=elapsed
                stationaryRecoveryCount++
                recoverGps(when {
                    callbackMissing -> "NO_CALLBACK"
                    verifiedMissing -> "NO_VERIFIED_FIX"
                    movingRecently -> "MOVEMENT"
                    else -> "WATCHDOG"
                })
            }
            heartbeatHandler.postDelayed(this,30000L)
        }
    }
    private fun saveTrackingRequested(active:Boolean) {
        trackingPrefs.edit().putBoolean("requested",active).commit()
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
    private var motionProfile="WALK"
    private var lastSmoothed:Location?=null
    private var lastRecordedTimestamp=0L
    private var fixConfidence=0
    private var lastBroadcast=0L
    private var lastVisual:Location?=null
    private fun broadcastFix(loc:Location,revealed:Boolean,eco:Boolean,weak:Boolean=false,
        sampleTime:Long=System.currentTimeMillis(),discoveryPosition:Location?=null) {
        val previous=lastVisual
        // Filter mild stationary wander for the on-screen marker only, never recorded GPS.
        val stationary=!loc.hasSpeed() || loc.speed<1.5f
        val close=previous!=null && previous.distanceTo(loc)<maxOf(12f,loc.accuracy*1.5f)
        val shown=Location(loc)
        if(stationary && close && previous!=null && loc.accuracy<=35f) {
            shown.latitude=previous.latitude*.65+loc.latitude*.35
            shown.longitude=previous.longitude*.65+loc.longitude*.35
        }
        if(!weak) {
            lastVisual=shown
            // Cache display position independently of Activity lifecycle.
            getSharedPreferences("terra_last_fix",MODE_PRIVATE).edit()
                .putLong("time",System.currentTimeMillis())
                .putLong("latBits",java.lang.Double.doubleToRawLongBits(shown.latitude))
                .putLong("lonBits",java.lang.Double.doubleToRawLongBits(shown.longitude))
                .putFloat("accuracy",loc.accuracy)
                .apply()
        }
        sendBroadcast(Intent(ACTION_FIX).setPackage(packageName).apply {
            putExtra("lat",shown.latitude);putExtra("lon",shown.longitude)
            putExtra("rawLat",discoveryPosition?.latitude ?: loc.latitude)
            putExtra("rawLon",discoveryPosition?.longitude ?: loc.longitude)
            putExtra("accuracy",loc.accuracy);putExtra("revealed",revealed)
            putExtra("confidence",fixConfidence)
            putExtra("inertialActive",inertialMoving())
            putExtra("eco",eco);putExtra("weak",weak)
            putExtra("motionProfile",motionProfile)
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
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,12000L)
                .setMinUpdateIntervalMillis(4000L).setMaxUpdateDelayMillis(0L)
                .setMinUpdateDistanceMeters(0f).build()
        } else activeRequest()
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
        override fun onLocationAvailability(availability:LocationAvailability) {
            if(!running)return
            val now=SystemClock.elapsedRealtime()
            val available=availability.isLocationAvailable
            if(lastProviderAvailable!=available && now-lastProviderStatusElapsed>=30000L) {
                lastProviderAvailable=available
                lastProviderStatusElapsed=now
                diagnostics.record(null,-1L,0.0,0.0,fixConfidence,motionProfile,
                    "EVENT",if(available)"PROVIDER_AVAILABLE" else "PROVIDER_UNAVAILABLE")
            }
        }
        override fun onLocationResult(result:LocationResult) {
            if(!running)return
            lastCallbackElapsed=SystemClock.elapsedRealtime()
            // Batched fixes must be processed in measurement order, not arrival order.
            result.locations.sortedBy { it.elapsedRealtimeNanos }.forEach {loc->
                if(!loc.hasAccuracy() || !loc.latitude.isFinite() || !loc.longitude.isFinite()) {
                    diagnostics.record(loc,-1L,0.0,0.0,0,motionProfile,
                        "REJECTED","INVALID_FIX")
                    return@forEach
                }
                // Ignore stale batched/cached positions; a delayed old fix can appear
                // as a wild GPS jump beside skyscrapers.
                val fixAgeMs=(android.os.SystemClock.elapsedRealtimeNanos()-loc.elapsedRealtimeNanos)/1000000L
                if(loc.elapsedRealtimeNanos<=lastMeasurementNanos) {
                    diagnostics.record(loc,fixAgeMs,0.0,0.0,0,motionProfile,
                        "REJECTED","DUPLICATE_OR_OUT_OF_ORDER")
                    return@forEach
                }
                lastMeasurementNanos=loc.elapsedRealtimeNanos
                if(fixAgeMs>25000L || fixAgeMs< -5000L) {
                    diagnostics.record(loc,fixAgeMs,0.0,0.0,0,motionProfile,
                        "REJECTED","STALE_FIX")
                    return@forEach
                }
                lastFixElapsed=SystemClock.elapsedRealtime()
                stationaryRecoveryCount=0
                if(loc.hasSpeed() && loc.speed>=0.85f)lastActiveMovementElapsed=lastFixElapsed
                // Recovery remains rate-limited until a trustworthy fix arrives;
                // inaccurate callbacks must not reset the watchdog cooldown.
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
                    // Walking at 1.3m/s previously could not exit ECO while GPS
                    // reported a 40-100m uncertainty. Wake high-accuracy updates
                    // even on weak movement, without accepting weak discoveries.
                    if(loc.accuracy>35f ||
                       (loc.hasSpeed() && loc.speed>=0.85f) ||
                       (loc.accuracy<=25f &&
                           traveled>maxOf(13f,1.25f*loc.accuracy))) {
                        switchMode(false)
                        motionAnchor=loc
                        stationarySince=android.os.SystemClock.elapsedRealtime()
                    }
                }
                if(loc.accuracy>65f || jump) {
                    fixConfidence=PathIntelligence.assess(
                        loc.accuracy,fixAgeMs,
                        if(loc.hasSpeed())loc.speed else Float.NaN,
                        if(prev!=null)prev.distanceTo(loc).toDouble() else 0.0,
                        seconds,inertialMoving()).score
                    diagnostics.record(loc,fixAgeMs,seconds,
                        if(prev!=null)prev.distanceTo(loc).toDouble() else 0.0,
                        fixConfidence,motionProfile,"REJECTED",
                        if(jump)"GPS_JUMP" else "POOR_ACCURACY")
                    if(prev!=null && seconds>45.0)previous=null
                    // Report the uncertainty without recording inaccurate exploration.
                    val now=System.currentTimeMillis()
                    if(now-lastBroadcast>12000L && loc.accuracy<=120f) {
                        lastBroadcast=now
                        broadcastFix(loc,false,ecoMode,weak=true,sampleTime=now)
                    }
                    return@forEach
                }
                lastGoodFixElapsed=SystemClock.elapsedRealtime()
                val nowElapsed=android.os.SystemClock.elapsedRealtime()
                val anchor=motionAnchor
                if(anchor==null) {
                    motionAnchor=loc
                    stationarySince=nowElapsed
                } else {
                    val threshold=maxOf(14f,1.25f*maxOf(anchor.accuracy,loc.accuracy))
                    val moving=(loc.hasSpeed() && loc.speed>=0.85f) ||
                        (loc.accuracy<=25f && anchor.distanceTo(loc)>threshold)
                    if(moving) {
                        motionAnchor=loc
                        stationarySince=nowElapsed
                        if(ecoMode)switchMode(false)
                    } else if(!ecoMode && loc.accuracy<=20f &&
                        nowElapsed-lastActiveMovementElapsed>=180000L &&
                        nowElapsed-stationarySince>=180000L) {
                        // Enter battery saver only after a confident 3-minute stop.
                        switchMode(true)
                    }
                }
                val now=System.currentTimeMillis()
                val velocity=if(loc.hasSpeed())loc.speed else Float.NaN
                val distance=if(prev!=null)prev.distanceTo(loc).toDouble() else 0.0
                val evidence=PathIntelligence.assess(loc.accuracy,fixAgeMs,
                    if(velocity.isFinite() && velocity>=0.4f)velocity
                    else if(motionProfile=="SCOOTER")5f else Float.NaN,
                    distance,seconds,inertialMoving())
                fixConfidence=evidence.score
                // Weak estimates are preview-only. They cannot unlock fog.
                if(!evidence.accepted) {
                    diagnostics.record(loc,fixAgeMs,seconds,distance,
                        fixConfidence,motionProfile,"REJECTED","LOW_CONFIDENCE")
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
                // Reject a very fast positional jump when Android itself reports
                // walking speed. We do not use this jump to anchor the next sample.
                val priorStable=lastSmoothed
                val rawDelta=if(priorStable!=null)
                    (loc.elapsedRealtimeNanos-priorStable.elapsedRealtimeNanos)/1e9 else 0.0
                if(priorStable!=null && rawDelta in 0.2..20.0 &&
                    loc.hasSpeed() && loc.speed<3f &&
                    priorStable.distanceTo(loc)>maxOf(45f,
                        (4.0*rawDelta).toFloat()+loc.accuracy)) {
                    diagnostics.record(loc,fixAgeMs,rawDelta,
                        priorStable.distanceTo(loc).toDouble(),
                        fixConfidence,motionProfile,"REJECTED","SPEED_CONFLICT")
                    return@forEach
                }

                val smooth=Location(loc)
                if(priorStable!=null && rawDelta in 0.2..12.0 &&
                    loc.accuracy>12f && (!loc.hasSpeed() || loc.speed<4f) &&
                    priorStable.distanceTo(loc)<=maxOf(35f,
                        (3.5*rawDelta).toFloat()+loc.accuracy)) {
                    // Accuracy-weighted short-range smoothing reduces reflected
                    // skyscraper signals without dragging the trail over long gaps.
                    val weight=if(loc.accuracy<=22f)0.65 else 0.48
                    smooth.latitude=priorStable.latitude*(1.0-weight)+loc.latitude*weight
                    smooth.longitude=priorStable.longitude*(1.0-weight)+loc.longitude*weight
                }
                lastSmoothed=smooth
                previous=loc

                val saved=lastSaved
                val step=saved?.distanceTo(smooth) ?: Float.MAX_VALUE
                val secondsSinceSaved=if(saved!=null)
                    (loc.elapsedRealtimeNanos-saved.elapsedRealtimeNanos)/1e9 else 0.0
                val rawSpeed=if(loc.hasSpeed() && loc.speed>=0f)loc.speed
                    else Float.NaN
                // A zero or absent speed is common after urban GNSS interruptions.
                // Derive speed only from sufficiently accurate, recent verified fixes.
                val positionalSpeed=if(saved!=null)
                    SpeedEvidence.infer(step,secondsSinceSaved,
                        loc.accuracy,saved.accuracy)
                    else Float.NaN
                val speedMps=SpeedEvidence.combine(
                    rawSpeed,positionalSpeed,secondsSinceSaved,step,
                    loc.accuracy,saved?.accuracy ?: Float.NaN)
                if(speedMps.isFinite() && speedMps>=1.8f && loc.accuracy<=35f)
                    lastActiveMovementElapsed=nowElapsed
                val nextProfile=classifier.update(speedMps,loc.accuracy)
                if(nextProfile!=motionProfile) {
                    diagnostics.record(loc,fixAgeMs,secondsSinceSaved,
                        if(step.isFinite())step.toDouble() else 0.0,
                        fixConfidence,nextProfile,"EVENT",
                        "MODE_"+motionProfile+"_TO_"+nextProfile)
                }
                motionProfile=nextProfile
                val spacing=TraceReconstruction.sampleSpacing(loc.accuracy,speedMps)
                // A single erroneous 'good accuracy' result should not draw a
                // false isolated discovery 100m away from a walking trajectory.
                val estimatedTravelSpeed=if(secondsSinceSaved>0.0 && saved!=null)
                    step/secondsSinceSaved else 0.0
                val plausible= saved==null || secondsSinceSaved>20.0 ||
                    estimatedTravelSpeed<=when {
                        speedMps.isFinite() && speedMps>=7f ->
                            maxOf(20.0,speedMps*2.0+5.0)
                        speedMps.isFinite() && speedMps>=2f ->
                            maxOf(8.0,speedMps*2.0+3.0)
                        else -> 6.0
                    }
                if(plausible) {
                    // Only coherent verified fixes reset the no-verified-fix watchdog.
                    if(lastVerifiedFixElapsed<lastRecoveryElapsed &&
                        lastRecoveryElapsed>0L) {
                        diagnostics.record(loc,fixAgeMs,secondsSinceSaved,
                            if(step.isFinite())step.toDouble() else 0.0,
                            fixConfidence,motionProfile,"EVENT",
                            "VERIFIED_GPS_AFTER_RECOVERY")
                    }
                    lastVerifiedFixElapsed=nowElapsed
                }
                // Record trustworthy intermediate fixes more frequently while moving.
                // Avoid excessive stationary samples and retain jump protection.
                val movingSpacing=if(speedMps.isFinite() && speedMps>=0.85f &&
                    loc.accuracy<=25f)kotlin.math.min(spacing,18f) else spacing
                val moved=plausible && (saved==null ||
                    (secondsSinceSaved>0.0 && step>=movingSpacing))
                if(moved) {
                    // Use the measurement timestamp, NOT the shared callback time.
                    // Batched fixes arriving together must remain ordered and joinable.
                    val measuredAt=(now-fixAgeMs.coerceAtLeast(0L))
                        .coerceAtLeast(lastRecordedTimestamp+1L)
                    lastRecordedTimestamp=measuredAt
                    store.add(DiscoveryPoint(smooth.latitude,smooth.longitude,
                        false,measuredAt,loc.accuracy,
                        if(speedMps.isFinite())speedMps else Float.NaN,
                        fixConfidence))
                    lastSaved=Location(smooth)
                    diagnostics.record(loc,fixAgeMs,secondsSinceSaved,
                        if(saved!=null && step.isFinite())step.toDouble() else 0.0,
                        fixConfidence,motionProfile,"SAVED","VERIFIED")
                    if(now-lastBroadcast>=1000L) {
                        lastBroadcast=now
                        broadcastFix(loc,true,ecoMode,sampleTime=measuredAt,
                            discoveryPosition=smooth)
                    } else {
                        // Preserve UI sync even if several batched GPS fixes
                        // are stored in the same callback.
                        broadcastFix(loc,true,ecoMode,sampleTime=measuredAt,
                            discoveryPosition=smooth)
                    }
                } else {
                    diagnostics.record(loc,fixAgeMs,secondsSinceSaved,
                        if(saved!=null && step.isFinite())step.toDouble() else 0.0,
                        fixConfidence,motionProfile,"SKIPPED",
                        if(!plausible)"IMPLAUSIBLE_STEP" else "BELOW_SPACING")
                    if(now-lastBroadcast>=15000L) {
                    lastBroadcast=now
                    broadcastFix(loc,false,ecoMode,sampleTime=now)
                    }
                }
            }
        }
    }
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_RESET_EXPLORATION) {
            // Reset is handled on the service main thread, serialized with GPS fixes.
            // Disable tracking before clearing so no late callback can reveal the map.
            saveTrackingRequested(false)
            running=false
            heartbeatHandler.removeCallbacks(heartbeat)
            stopMotionSensors()
            client.removeLocationUpdates(callback)
            store.replaceAll(emptyList())
            PathStore(this).save(emptyList())
            sendBroadcast(Intent(ACTION_RESET_COMPLETE).setPackage(packageName))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if(intent?.action==ACTION_STOP) {stopTracking();return START_NOT_STICKY}
        // Android may restart a sticky service with a null intent after a kill.
        // Never resume unless TRACK was explicitly enabled by the user.
        if(intent?.action==null && !trackingPrefs.getBoolean("requested",false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if(running)return START_STICKY
        diagnostics.record(null,-1L,0.0,0.0,0,motionProfile,
            "EVENT",if(intent?.action==null)"STICKY_RESTART" else "TRACK_START_REQUEST")
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            stopSelf();return START_NOT_STICKY
        }
        val manager=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("terra_recon","TerraUnlocked tracking",NotificationManager.IMPORTANCE_LOW))
        try {
            if(Build.VERSION.SDK_INT>=29)
                startForeground(1801,makeNotification("Moving / high accuracy"),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(1801,makeNotification("Moving / high accuracy"))
        } catch(error:Exception) {
            diagnostics.record(null,-1L,0.0,0.0,0,motionProfile,
                "REJECTED","SERVICE_START_DENIED")
            stopSelf()
            return START_NOT_STICKY
        }
        running=true
        saveTrackingRequested(true)
        lastFixElapsed=SystemClock.elapsedRealtime()
        lastCallbackElapsed=lastFixElapsed
        lastMeasurementNanos=0L
        lastGapAlertElapsed=0L
        lastRecoveryElapsed=0L
        lastGoodFixElapsed=0L
        lastVerifiedFixElapsed=lastFixElapsed
        lastNoFixLogElapsed=0L
        stationaryRecoveryCount=0
        lastActiveMovementElapsed=SystemClock.elapsedRealtime()
        lastProviderStatusElapsed=0L
        lastProviderAvailable=null
        recoveryPending=false
        heartbeatHandler.removeCallbacks(heartbeat)
        heartbeatHandler.postDelayed(heartbeat,30000L)
        startMotionSensors()
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("running",true))
        ecoMode=false
        lastVisual=null
        previous=null
        lastSaved=null
        lastSmoothed=null
        lastRecordedTimestamp=0L
        motionAnchor=null
        classifier.reset()
        motionProfile="WALK"
        stationarySince=0L
        lastSwitch=0L
        val request=activeRequest()
        try {
            client.requestLocationUpdates(request,callback,mainLooper)
                .addOnFailureListener {
                    diagnostics.record(null,-1L,0.0,0.0,0,motionProfile,
                        "REJECTED","LOCATION_REQUEST_FAILED")
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(1801,makeNotification("GPS unavailable / open Terra"))
                }
        } catch(error:SecurityException) {
            stopTracking()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    private fun stopTracking() {
        diagnostics.record(null,-1L,0.0,0.0,fixConfidence,motionProfile,
            "EVENT","TRACK_STOPPED")
        saveTrackingRequested(false)
        running=false
        heartbeatHandler.removeCallbacks(heartbeat)
        stopMotionSensors()
        client.removeLocationUpdates(callback)
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra("running",false))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onTaskRemoved(rootIntent:Intent?) {
        // Task dismissal is not STOP; the visible foreground notification remains.
        diagnostics.record(null,-1L,0.0,0.0,fixConfidence,motionProfile,
            "EVENT","TASK_REMOVED")
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        if(running)diagnostics.record(null,-1L,0.0,0.0,fixConfidence,motionProfile,
            "EVENT","SERVICE_DESTROYED")
        running=false
        heartbeatHandler.removeCallbacks(heartbeat)
        stopMotionSensors()
        client.removeLocationUpdates(callback)
        super.onDestroy()
    }
}
