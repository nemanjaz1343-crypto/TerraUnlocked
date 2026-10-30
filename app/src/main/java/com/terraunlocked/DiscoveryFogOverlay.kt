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
    private val notes: () -> List<FieldNote>,
    var radiusMeters: Double = 20.0
) : Overlay() {
    var fogVisible = true
    var opacity = 205
    var showRoute = false
    var showCheckpoints = true
    var backtrack = false
    var currentPosition: GeoPoint? = null
    var positionAccuracy = 0f
    var northAzimuth = 0f
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
            // One offscreen fog layer; DST_OUT creates feathered transparent openings.
            val layer = canvas.saveLayer(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), null)
            fogPaint.alpha = opacity.coerceIn(0,255)
            canvas.drawRect(0f,0f,canvas.width.toFloat(),canvas.height.toFloat(),fogPaint)
            val erase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
            }
            fun stamp(x: Float, y: Float, r: Float) {
                val size = r.coerceAtLeast(2f)
                erase.shader = RadialGradient(x,y,size,
                    intArrayOf(Color.WHITE,Color.argb(242,255,255,255),Color.argb(170,255,255,255),Color.argb(72,255,255,255),Color.TRANSPARENT),
                    floatArrayOf(0f,0.22f,0.48f,0.75f,1f),Shader.TileMode.CLAMP)
                canvas.drawCircle(x,y,size,erase)
            }
            fun stroke(ax:Float,ay:Float,bx:Float,by:Float,r:Float) {
                val length = hypot((bx-ax).toDouble(),(by-ay).toDouble()).toFloat()
                val steps = (length / (r * .45f).coerceAtLeast(5f)).toInt().coerceIn(1,500)
                for (j in 0..steps) {
                    val t = j.toFloat()/steps
                    stamp(ax+(bx-ax)*t,ay+(by-ay)*t,r)
                }
            }
            for (i in points.indices) {
                val p = points[i]
                val pix = toPoint(mapView,p.latitude,p.longitude)
                val r = radiusPx(mapView,p.latitude,p.longitude)
                stamp(pix.x.toFloat(),pix.y.toFloat(),r)
                if(i>0 && canJoin(points[i-1],p)) {
                    val a=toPoint(mapView,points[i-1].latitude,points[i-1].longitude)
                    stroke(a.x.toFloat(),a.y.toFloat(),pix.x.toFloat(),pix.y.toFloat(),r)
                }
            }
            for (seg in segments()) {
                val a=toPoint(mapView,seg.aLat,seg.aLon)
                val b=toPoint(mapView,seg.bLat,seg.bLon)
                stroke(a.x.toFloat(),a.y.toFloat(),b.x.toFloat(),b.y.toFloat(),radiusPx(mapView,seg.aLat,seg.aLon))
            }
            erase.shader=null
            erase.xfermode=null
            canvas.restoreToCount(layer)
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
        if (showCheckpoints) {
            val palette = mapOf("CHECKPOINT" to Color.rgb(228,183,85),
                "POI" to Color.rgb(91,204,210), "MEMORY" to Color.rgb(203,150,220),
                "CAMP" to Color.rgb(147,198,105), "PHOTO" to Color.rgb(237,174,118), "VOICE" to Color.rgb(239,118,126))
            notes().forEach { note ->
                val p = toPoint(mapView,note.latitude,note.longitude)
                val color = palette[note.kind] ?: Color.rgb(228,183,85)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
                val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.rgb(25,35,29);style=Paint.Style.STROKE;strokeWidth=3f
                }
                canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),11f,fill)
                canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),11f,outline)
                val glyph=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color=Color.rgb(20,30,25);textSize=12f;textAlign=Paint.Align.CENTER
                }
                val symbol=when(note.kind) {"CHECKPOINT"->"⚑";"POI"->"★";"MEMORY"->"●";"CAMP"->"▲";"PHOTO"->"▣";"VOICE"->"◉";else->"◆"}
                canvas.drawText(if(note.icon=="◆" && note.kind!="POI") symbol else note.icon,p.x.toFloat(),p.y+4f,glyph)
            }
        }
        if (backtrack && records().size > 1) {
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.rgb(120,210,246);strokeWidth=5f;style=Paint.Style.STROKE
            }
            val trail=records().filter { !it.manual }.takeLast(600)
            for(i in 1 until trail.size) if(canJoin(trail[i-1],trail[i])) {
                val a=toPoint(mapView,trail[i-1].latitude,trail[i-1].longitude)
                val b=toPoint(mapView,trail[i].latitude,trail[i].longitude)
                canvas.drawLine(a.x.toFloat(),a.y.toFloat(),b.x.toFloat(),b.y.toFloat(),paint)
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
            val angle=Math.toRadians(northAzimuth.toDouble())
            val nx=kotlin.math.sin(angle).toFloat()
            val ny=-kotlin.math.cos(angle).toFloat()
            val arrow=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.rgb(255,210,116);strokeWidth=3.5f;strokeCap=Paint.Cap.ROUND
            }
            canvas.drawLine(p.x.toFloat(),p.y.toFloat(),p.x+nx*23f,p.y+ny*23f,arrow)
        }
    }
}
