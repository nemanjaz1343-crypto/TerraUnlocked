package com.terraunlocked

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver

class MainActivity : Activity() {
    private lateinit var map: MapView
    private lateinit var store: DiscoveryStore
    private lateinit var status: TextView
    private val client by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var tracking = false
    private var manual = false
    private var last: DiscoveryPoint? = null
    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { location ->
                if (location.accuracy > 60f) return@forEach
                val point = DiscoveryPoint(location.latitude, location.longitude, false, System.currentTimeMillis())
                if (last == null || distance(last!!, point) >= 12f) addDiscovery(point)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().userAgentValue = packageName
        store = DiscoveryStore(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = "TerraUnlocked • GPS isključen"; setPadding(16, 16, 16, 16) }
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        val trackingButton = Button(this).apply { text = "Pokreni GPS" }
        val manualButton = Button(this).apply { text = "Ručno otkrij" }
        controls.addView(trackingButton)
        controls.addView(manualButton)
        root.addView(status)
        root.addView(controls)
        map = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(25.0657, 55.1403))
        }
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        store.load().forEach { drawCircle(it) }
        last = store.load().lastOrNull { !it.manual }
        map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (!manual) return false
                addDiscovery(DiscoveryPoint(p.latitude, p.longitude, true, System.currentTimeMillis()))
                manual = false
                manualButton.text = "Ručno otkrij"
                return true
            }
            override fun longPressHelper(p: GeoPoint): Boolean = false
        }))
        trackingButton.setOnClickListener {
            if (tracking) {
                stopTracking()
                trackingButton.text = "Pokreni GPS"
            } else {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    startTracking()
                    trackingButton.text = "Zaustavi GPS"
                } else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
            }
        }
        manualButton.setOnClickListener {
            manual = !manual
            manualButton.text = if (manual) "Dodirni mapu…" else "Ručno otkrij"
        }
    }
    private fun distance(a: DiscoveryPoint, b: DiscoveryPoint): Float {
        val distances = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, distances)
        return distances[0]
    }
    private fun addDiscovery(point: DiscoveryPoint) {
        store.add(point)
        if (!point.manual) last = point
        drawCircle(point)
        if (!point.manual) map.controller.animateTo(GeoPoint(point.latitude, point.longitude))
    }
    private fun drawCircle(point: DiscoveryPoint) {
        val circle = Polygon().apply {
            points = Polygon.pointsAsCircle(GeoPoint(point.latitude, point.longitude), 20.0)
            fillPaint.color = if (point.manual) Color.argb(80, 255, 176, 32) else Color.argb(75, 40, 170, 115)
            outlinePaint.color = Color.TRANSPARENT
        }
        map.overlays.add(circle)
        map.invalidate()
    }
    private fun startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 6000L)
            .setMinUpdateDistanceMeters(10f).build()
        client.requestLocationUpdates(request, callback, mainLooper)
        tracking = true
        status.text = "GPS aktivan • otkrivanje 20 m"
    }
    private fun stopTracking() {
        client.removeLocationUpdates(callback)
        tracking = false
        status.text = "GPS isključen • podaci su sačuvani"
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startTracking()
            (findViewById<LinearLayout>(android.R.id.content).getChildAt(0) as LinearLayout)
                .let { ((it.getChildAt(1) as LinearLayout).getChildAt(0) as Button).text = "Zaustavi GPS" }
        }
    }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause(); if (tracking) stopTracking() }
    override fun onDestroy() { super.onDestroy(); client.removeLocationUpdates(callback) }
}
