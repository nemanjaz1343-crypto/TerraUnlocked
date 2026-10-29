package com.terraunlocked

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.Shader
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.cos
import kotlin.math.hypot

class DiscoveryFogOverlay(
    private val records: () -> List<DiscoveryPoint>,
    private val demo: () -> List<DiscoveryPoint>,
    private val segments: () -> List<ManualSegment>,
    var radiusMeters: Double = 20.0
) : Overlay() {
    var fogVisible = true
    var opacity = 205
    var showRoute = true
    var currentPosition: GeoPoint? = null
    var positionAccuracy = 0f
    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(15, 22, 17) }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(218, 199, 148); strokeWidth = 2.5f; style = Paint.Style.STROKE
    }
    private fun toPoint(map: MapView, lat: Double, lon: Double): Point =
        map.projection.toPixels(GeoPoint(lat, lon), Point())
    private fun radiusPx(map: MapView, lat: Double, lon: Double): Float {
        val p = toPoint(map, lat, lon)
        val east = toPoint(map, lat, lon + radiusMeters / (111320.0 * cos(Math.toRadians(lat)).coerceAtLeast(.01)))
        return kotlin.math.abs(east.x - p.x).toFloat().coerceAtLeast(1f)
    }
    private fun canJoin(a: DiscoveryPoint, b: DiscoveryPoint): Boolean {
        if (a.manual || b.manual || b.timestamp < a.timestamp || b.timestamp - a.timestamp > 90000L) return false
        val out = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, out)
        return out[0] <= 120f
    }
    private fun segmentPath(a: Point, b: Point): Path =
        Path().apply { moveTo(a.x.toFloat(), a.y.toFloat()); lineTo(b.x.toFloat(), b.y.toFloat()) }
    private fun buffered(path: Path, radius: Float): Path {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = radius * 2
        }
        return Path().also { paint.getFillPath(path, it) }
    }
    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val points = records() + demo()
        if (fogVisible) {
            canvas.save()
            val visible = Path()
            for (i in points.indices) {
                val p = points[i]
                val pix = toPoint(mapView, p.latitude, p.longitude)
                val r = radiusPx(mapView, p.latitude, p.longitude)
                visible.addCircle(pix.x.toFloat(), pix.y.toFloat(), r, Path.Direction.CW)
                if (i > 0 && canJoin(points[i-1],p)) {
                    val a = toPoint(mapView,points[i-1].latitude,points[i-1].longitude)
                    visible.addPath(buffered(segmentPath(a,pix),r))
                }
            }
            for (s in segments()) {
                val a = toPoint(mapView,s.aLat,s.aLon)
                val b = toPoint(mapView,s.bLat,s.bLon)
                visible.addPath(buffered(segmentPath(a,b),radiusPx(mapView,s.aLat,s.aLon)))
            }
            canvas.clipOutPath(visible)
            fogPaint.alpha = opacity.coerceIn(0,255)
            canvas.drawRect(0f,0f,canvas.width.toFloat(),canvas.height.toFloat(),fogPaint)
            canvas.restore()

            // Feathered transition: translucent dark halo just inside the reveal boundary.
            val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 4f
                color = Color.argb((opacity * .28f).toInt(),15,22,17)
            }
            for (p in points) {
                val c = toPoint(mapView,p.latitude,p.longitude)
                val r = radiusPx(mapView,p.latitude,p.longitude)
                canvas.drawCircle(c.x.toFloat(),c.y.toFloat(),(r-2f).coerceAtLeast(.5f),edge)
            }
        }
        if (showRoute) {
            for (i in 1 until points.size) {
                val a = points[i-1]; val b = points[i]
                if (canJoin(a,b)) {
                    val x = toPoint(mapView,a.latitude,a.longitude)
                    val y = toPoint(mapView,b.latitude,b.longitude)
                    canvas.drawLine(x.x.toFloat(),x.y.toFloat(),y.x.toFloat(),y.y.toFloat(),routePaint)
                }
            }
            for (s in segments()) {
                val a = toPoint(mapView,s.aLat,s.aLon)
                val b = toPoint(mapView,s.bLat,s.bLon)
                canvas.drawLine(a.x.toFloat(),a.y.toFloat(),b.x.toFloat(),b.y.toFloat(),routePaint)
            }
        }
        currentPosition?.let { loc ->
            val p = toPoint(mapView,loc.latitude,loc.longitude)
            val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.argb(85,107,185,226); style=Paint.Style.FILL
            }
            canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),15f,ring)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(95,202,255);style=Paint.Style.FILL }
            canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),7f,dot)
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.WHITE;style=Paint.Style.STROKE;strokeWidth=2f}
            canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),7f,border)
        }
    }
}
