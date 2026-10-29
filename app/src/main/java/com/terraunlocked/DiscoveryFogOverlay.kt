package com.terraunlocked

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import kotlin.math.cos
import kotlin.math.hypot

/** Screen-space rendering of the complement of all explored buffered paths.
 * Geometry remains in geographic coordinates; nothing is rasterized to persistent tiles.
 */
class DiscoveryFogOverlay(
    private val records: () -> List<DiscoveryPoint>,
    private val demo: () -> List<DiscoveryPoint>,
    var radiusMeters: Double = 20.0
) : Overlay() {
    var fogVisible = true
    var opacity = 215
    var showRoute = true
    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(17, 23, 19); style = Paint.Style.FILL }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 188, 132); strokeWidth = 2.5f; style = Paint.Style.STROKE }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val points = records() + demo()
        val projection = mapView.projection
        if (fogVisible) {
            canvas.save()
            // Subtract explored shapes from dark overlay; round caps connect vertices.
            for (i in points.indices) {
                val p = points[i]
                val pix = projection.toPixels(GeoPoint(p.latitude, p.longitude), Point())
                val east = projection.toPixels(
                    GeoPoint(p.latitude, p.longitude + radiusMeters / (111320.0 * cos(Math.toRadians(p.latitude)).coerceAtLeast(0.01))), Point())
                val r = hypot((east.x - pix.x).toDouble(), (east.y - pix.y).toDouble()).toFloat().coerceAtLeast(1f)
                val circle = Path().apply { addCircle(pix.x.toFloat(), pix.y.toFloat(), r, Path.Direction.CW) }
                canvas.clipOutPath(circle)
                if (i > 0 && !p.manual && canJoin(points[i - 1], p)) {
                    val a = points[i - 1]
                    val pa = projection.toPixels(GeoPoint(a.latitude, a.longitude), Point())
                    val corridor = Path()
                    val dx = (pix.x - pa.x).toFloat()
                    val dy = (pix.y - pa.y).toFloat()
                    val d = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    if (d > 0.01f) {
                        val nx = -dy / d * r; val ny = dx / d * r
                        corridor.moveTo(pa.x + nx, pa.y + ny)
                        corridor.lineTo(pix.x + nx, pix.y + ny)
                        corridor.lineTo(pix.x - nx, pix.y - ny)
                        corridor.lineTo(pa.x - nx, pa.y - ny)
                        corridor.close()
                        canvas.clipOutPath(corridor)
                    }
                }
            }
            fogPaint.alpha = opacity.coerceIn(0, 255)
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), fogPaint)
            canvas.restore()
        }
        if (showRoute) {
            for (i in 1 until points.size) {
                val p = points[i]; val a = points[i - 1]
                if (!p.manual && canJoin(a, p)) {
                    val p1 = projection.toPixels(GeoPoint(a.latitude, a.longitude), Point())
                    val p2 = projection.toPixels(GeoPoint(p.latitude, p.longitude), Point())
                    canvas.drawLine(p1.x.toFloat(), p1.y.toFloat(), p2.x.toFloat(), p2.y.toFloat(), routePaint)
                }
            }
        }
    }
    private fun canJoin(a: DiscoveryPoint, b: DiscoveryPoint): Boolean {
        if (a.manual || b.manual || b.timestamp < a.timestamp || b.timestamp - a.timestamp > 90_000L) return false
        val results = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
        return results[0] < 120f
    }
}
