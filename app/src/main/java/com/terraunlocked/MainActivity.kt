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
import android.view.MotionEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
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
import org.osmdroid.views.overlay.Overlay

class MainActivity : Activity() {
    private val olive = Color.rgb(31, 40, 32)
    private val sand = Color.rgb(218, 201, 156)
    private val sage = Color.rgb(155, 181, 134)
    private lateinit var map: MapView
    private lateinit var store: DiscoveryStore
    private lateinit var status: TextView
    private lateinit var trackButton: Button
    private lateinit var modeButton: Button
    private lateinit var fogOverlay: DiscoveryFogOverlay
    private val records = mutableListOf<DiscoveryPoint>()
    private val demo = mutableListOf<DiscoveryPoint>()
    private var mode = "VIEW"
    private var tracking = false
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
                if (loc.accuracy > 35f) return@forEach
                val point = DiscoveryPoint(loc.latitude, loc.longitude, false, System.currentTimeMillis())
                val previous = last
                if (previous == null || metres(previous, point) >= 8f) {
                    records.add(point)
                    store.add(point)
                    last = point
                    map.controller.animateTo(GeoPoint(point.latitude, point.longitude))
                    refresh()
                }
                status.text = "GPS LOCK  |  ACC ±${loc.accuracy.toInt()}m  |  R ${radius}m"
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
        records.addAll(store.load())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(olive) }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(9), 0)
            setBackgroundColor(olive)
        }
        val brand = label("TERRA / UNLOCKED", 12, sand).apply { setTypeface(null, Typeface.BOLD) }
        header.addView(brand, LinearLayout.LayoutParams(0, dp(40), 1f))
        val compass = label("◇ N 000°", 12, sand)
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
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(25.0657, 55.1403))
        }
        fogOverlay = DiscoveryFogOverlay({ records }, { demo })
        map.overlays.add(fogOverlay)
        map.overlays.add(object : Overlay() {
            override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
                if (mode != "PAINT") return false
                if (event.actionMasked == MotionEvent.ACTION_DOWN ||
                    event.actionMasked == MotionEvent.ACTION_MOVE) {
                    val now = System.currentTimeMillis()
                    if (now - lastPaintTime > 65L) {
                        val point = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt())
                        val p = DiscoveryPoint(point.latitude, point.longitude, true, now)
                        if (records.lastOrNull()?.let { metres(it, p) >= radius * 0.35 } != false) {
                            records.add(p)
                            store.add(p)
                            refresh()
                        }
                        lastPaintTime = now
                    }
                    return true
                }
                return event.actionMasked == MotionEvent.ACTION_UP
            }
        })
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this).apply { setBackgroundColor(olive); gravity = Gravity.CENTER }
        trackButton = action("TRACK")
        modeButton = action("PAINT")
        val layers = action("LAYERS")
        val mark = action("MARK")
        val sim = action("SIM")
        listOf(trackButton, modeButton, layers, mark, sim).forEach {
            controls.addView(it, LinearLayout.LayoutParams(0, dp(45), 1f))
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
        modeButton.setOnClickListener {
            mode = if (mode == "PAINT") "VIEW" else "PAINT"
            modeButton.text = if (mode == "PAINT") "PAINT ●" else "PAINT"
            status.text = if (mode == "PAINT") "PAINT MODE  |  DRAG TO REVEAL" else "MAP READY"
        }
        mark.setOnClickListener {
            val p = map.mapCenter
            val point = DiscoveryPoint(p.latitude, p.longitude, true, System.currentTimeMillis())
            records.add(point); store.add(point); refresh()
            status.text = "WAYPOINT REVEALED AT MAP CENTER"
        }
        layers.setOnClickListener { showLayers() }
        sim.setOnClickListener {
            if (simulating) stopSimulation() else {
                if (tracking) stopTracking()
                simulating = true
                simulationIndex = 0
                simulationOrigin = GeoPoint(map.mapCenter.latitude, map.mapCenter.longitude)
                sim.text = "STOP"
                status.text = "SIMULATION ACTIVE  |  TEST DATA ONLY"
                handler.post(simulationStep)
            }
        }
        compass.setOnClickListener { map.mapOrientation = 0f }
    }

    private fun showLayers() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        fun addOption(name: String, action: () -> Unit) {
            panel.addView(Button(this).apply { text = name; setOnClickListener { action() } })
        }
        addOption("FOG: ${if (fogEnabled) "ON" else "OFF"}") {
            fogEnabled = !fogEnabled; fogOverlay.fogVisible = fogEnabled; refresh()
        }
        addOption("FOG OPACITY: ${fogOverlay.opacity}") {
            fogOverlay.opacity = if (fogOverlay.opacity >= 230) 120 else fogOverlay.opacity + 35
            refresh()
        }
        addOption("ROUTE: ${if (fogOverlay.showRoute) "ON" else "OFF"}") {
            fogOverlay.showRoute = !fogOverlay.showRoute; refresh()
        }
        addOption("RADIUS: ${radius}m (tap to cycle)") {
            radius = when (radius) { 10 -> 20; 20 -> 30; 30 -> 50; 50 -> 100; else -> 10 }
            fogOverlay.radiusMeters = radius.toDouble(); refresh()
        }
        addOption("MAP: OSM / TOPO") {
            map.setTileSource(if (map.tileProvider.tileSource == TileSourceFactory.MAPNIK)
                TileSourceFactory.OpenTopo else TileSourceFactory.MAPNIK)
        }
        addOption("UNDO LAST MANUAL") {
            val i = records.indexOfLast { it.manual }
            if (i >= 0) {
                records.removeAt(i)
                store.replaceAll(records)
                refresh()
            }
        }
        android.app.AlertDialog.Builder(this).setTitle("TACTICAL / LAYERS")
            .setView(panel).setPositiveButton("CLOSE", null).show()
    }
    private fun startTracking() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        last = null
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
    }
    override fun onResume() { super.onResume(); map.onResume() }
    override fun onPause() { super.onPause(); map.onPause(); if (tracking) stopTracking() }
    override fun onDestroy() {
        super.onDestroy()
        if (tracking) stopTracking()
        handler.removeCallbacks(simulationStep)
    }
}
