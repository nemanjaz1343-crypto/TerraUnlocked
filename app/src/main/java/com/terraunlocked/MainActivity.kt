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
                fogOverlay.currentPosition = GeoPoint(loc.latitude, loc.longitude)
                fogOverlay.positionAccuracy = loc.accuracy
                map.invalidate()
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
        fogOverlay = DiscoveryFogOverlay({ records }, { demo }, { segments })
        map.overlays.add(fogOverlay)
        map.overlays.add(object : Overlay() {
            private var strokeLast: GeoPoint? = null
            override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
                if (mode == "VIEW") return false
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    strokeLast = null
                    return true
                }
                if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_MOVE) return false
                val geo = mapView.projection.fromPixels(event.x.toInt(), event.y.toInt())
                if (mode == "PATH") {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        val previous = pathStart
                        if (previous != null) {
                            segments.add(ManualSegment(previous.latitude, previous.longitude, geo.latitude, geo.longitude))
                            pathStore.save(segments)
                        }
                        pathStart = GeoPoint(geo.latitude, geo.longitude)
                        status.text = "PATH / TAP NEXT VERTEX · ${segments.size} SEGMENTS"
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
        root.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        val controls = LinearLayout(this).apply { setBackgroundColor(olive); gravity = Gravity.CENTER }
        trackButton = action("TRACK")
        modeButton = action("PAINT")
        val pathButton = action("PATH")
        val eraseButton = action("ERASE")
        val layers = action("LAYERS")
        listOf(trackButton, pathButton, modeButton, eraseButton, layers).forEach {
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
        fun activate(selected: String, button: Button) {
            mode = if (mode == selected) "VIEW" else selected
            pathStart = null
            pathButton.text = if (mode == "PATH") "PATH ●" else "PATH"
            modeButton.text = if (mode == "PAINT") "PAINT ●" else "PAINT"
            eraseButton.text = if (mode == "ERASE") "ERASE ●" else "ERASE"
            status.text = when(mode) {
                "PATH" -> "PATH / TAP VERTICES TO DRAW VECTOR"
                "PAINT" -> "PAINT / DRAG TO REVEAL"
                "ERASE" -> "ERASE / DRAG TO REMOVE MANUAL REVEALS"
                else -> "MAP READY"
            }
        }
        pathButton.setOnClickListener { activate("PATH", pathButton) }
        modeButton.setOnClickListener { activate("PAINT", modeButton) }
        eraseButton.setOnClickListener { activate("ERASE", eraseButton) }
        layers.setOnClickListener { showLayers(layers) }
        compass.setOnClickListener { map.mapOrientation = 0f }
    }

    private fun gap(a: GeoPoint, b: GeoPoint): Float {
        val out = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
        return out[0]
    }
    private fun showLayers(anchor: View) {
        if (layersPopup?.isShowing == true) { layersPopup?.dismiss(); return }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(43, 54, 43))
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        fun option(name: String, callback: () -> Unit) {
            panel.addView(action(name).apply { setOnClickListener { callback(); layersPopup?.dismiss() } },
                LinearLayout.LayoutParams(-1, dp(43)))
        }
        option("FOG ${if (fogEnabled) "ON" else "OFF"}") {
            fogEnabled = !fogEnabled; fogOverlay.fogVisible = fogEnabled; refresh()
        }
        option("FOG OPACITY ${fogOverlay.opacity}") {
            fogOverlay.opacity = if (fogOverlay.opacity >= 235) 125 else fogOverlay.opacity + 35; refresh()
        }
        option("ROUTE ${if (fogOverlay.showRoute) "ON" else "OFF"}") {
            fogOverlay.showRoute = !fogOverlay.showRoute; refresh()
        }
        option("RADIUS ${radius}m") {
            radius = when(radius) { 10 -> 20; 20 -> 30; 30 -> 50; 50 -> 100; else -> 10 }
            fogOverlay.radiusMeters = radius.toDouble(); refresh()
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
        layersPopup = PopupWindow(panel, dp(230), dp(310), true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(43, 54, 43)))
            elevation = dp(8).toFloat()
            showAtLocation(map, Gravity.BOTTOM or Gravity.END, dp(8), dp(60))
        }
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
        layersPopup?.dismiss()
    }
}
