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

    // Merge real visited discs and their existing route segments into one silhouette.
    // A small feather at its outer edge adds mist without overlapping bright halos.
    private var organicMask:android.graphics.Bitmap?=null
    private var maskKey:String=""
    private val maskPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode=android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
    }
    private fun organicMaskFor(map:MapView,width:Int,height:Int,
        points:List<DiscoveryPoint>,manualSegments:List<ManualSegment>):android.graphics.Bitmap {
        val mw=(width/4).coerceAtLeast(1)
        val mh=(height/4).coerceAtLeast(1)
        val center=map.mapCenter
        val last=points.lastOrNull()
        val end=manualSegments.lastOrNull()
        val key="$mw:$mh:${map.zoomLevelDouble}:${map.mapOrientation}:${center.latitude}:${center.longitude}:"+
            "${points.size}:${last?.latitude}:${last?.longitude}:${last?.timestamp}:"+
            "${manualSegments.size}:${end?.aLat}:${end?.bLon}:$radiusMeters"
        organicMask?.let{if(key==maskKey && !it.isRecycled)return it}
        val bitmap=android.graphics.Bitmap.createBitmap(mw,mh,android.graphics.Bitmap.Config.ARGB_8888)
        val maskCanvas=Canvas(bitmap)
        val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.WHITE
            strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND
        }
        fun pt(lat:Double,lon:Double):android.graphics.Point =
            map.projection.toPixels(GeoPoint(lat,lon),android.graphics.Point())
        fun r(lat:Double,lon:Double)=radiusPx(map,lat,lon)/4f
        for(i in points.indices) {
            val p=points[i]
            val xy=pt(p.latitude,p.longitude)
            val rr=r(p.latitude,p.longitude).coerceAtLeast(.5f)
            ink.style=Paint.Style.FILL
            maskCanvas.drawCircle(xy.x/4f,xy.y/4f,rr,ink)
            if(i>0 && canJoin(points[i-1],p)) {
                val prev=points[i-1];val ab=pt(prev.latitude,prev.longitude)
                ink.style=Paint.Style.STROKE;ink.strokeWidth=rr*2f
                maskCanvas.drawLine(ab.x/4f,ab.y/4f,xy.x/4f,xy.y/4f,ink)
            }
        }
        for(seg in manualSegments) {
            val a=pt(seg.aLat,seg.aLon);val b=pt(seg.bLat,seg.bLon)
            ink.style=Paint.Style.STROKE
            ink.strokeWidth=r(seg.aLat,seg.aLon).coerceAtLeast(.5f)*2f
            maskCanvas.drawLine(a.x/4f,a.y/4f,b.x/4f,b.y/4f,ink)
            ink.style=Paint.Style.FILL
            maskCanvas.drawCircle(a.x/4f,a.y/4f,ink.strokeWidth/2f,ink)
            maskCanvas.drawCircle(b.x/4f,b.y/4f,ink.strokeWidth/2f,ink)
        }
        val pixels=IntArray(mw*mh)
        bitmap.getPixels(pixels,0,mw,0,0,mw,mh)
        val horizontal=IntArray(mw*mh)
        val blurRadius=2
        for(y in 0 until mh)for(x in 0 until mw) {
            var sum=0;var n=0
            for(dx in -blurRadius..blurRadius) {
                val xx=x+dx
                if(xx in 0 until mw){sum+=pixels[y*mw+xx] ushr 24;n++}
            }
            horizontal[y*mw+x]=sum/n
        }
        for(y in 0 until mh)for(x in 0 until mw) {
            var sum=0;var n=0
            for(dy in -blurRadius..blurRadius) {
                val yy=y+dy
                if(yy in 0 until mh){sum+=horizontal[yy*mw+x];n++}
            }
            val alpha=(sum/n).coerceIn(0,255)
            pixels[y*mw+x]=if(alpha<8)0 else (alpha shl 24) or 0x00FFFFFF
        }
        bitmap.setPixels(pixels,0,mw,0,0,mw,mh)
        organicMask?.recycle()
        organicMask=bitmap;maskKey=key
        return bitmap
    }

    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val points = records() + demo()
        if (fogVisible) {
            val blended=organicMaskFor(mapView,canvas.width,canvas.height,points,segments())
            val layer=canvas.saveLayer(0f,0f,canvas.width.toFloat(),canvas.height.toFloat(),null)
            fogPaint.alpha=opacity.coerceIn(0,255)
            canvas.drawRect(0f,0f,canvas.width.toFloat(),canvas.height.toFloat(),fogPaint)
            canvas.drawBitmap(blended,null,
                android.graphics.RectF(0f,0f,canvas.width.toFloat(),canvas.height.toFloat()),maskPaint)
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
                val p=toPoint(mapView,note.latitude,note.longitude)
                val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=palette[note.kind] ?: Color.rgb(228,183,85)
                }
                val symbol=if(note.icon=="◆" && note.kind!="POI") when(note.kind) {
                    "CHECKPOINT"->"⚑";"MEMORY"->"✎";"PHOTO"->"▣";"VOICE"->"◉";"CAMP"->"▲";else->"◆"
                } else note.icon
                val iconPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=Color.WHITE; textSize=29f; textAlign=Paint.Align.CENTER
                    typeface=android.graphics.Typeface.DEFAULT_BOLD
                    setShadowLayer(3f,0f,1f,Color.BLACK)
                }
                canvas.drawCircle(p.x.toFloat(),p.y.toFloat(),18f,Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color=Color.argb(220,25,36,30)
                })
                canvas.drawText(symbol,p.x.toFloat(),p.y+10f,iconPaint)
                currentPosition?.let { origin ->
                    val result=FloatArray(1)
                    android.location.Location.distanceBetween(origin.latitude,origin.longitude,
                        note.latitude,note.longitude,result)
                    val distance=if(result[0]<1000) "${result[0].toInt()} m" else "%.1f km".format(result[0]/1000f)
                    val badge=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color=Color.argb(215,28,41,33)
                    }
                    val text=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color=Color.rgb(238,224,180);textSize=24f
                        textAlign=Paint.Align.CENTER;typeface=android.graphics.Typeface.DEFAULT_BOLD
                    }
                    val width=text.measureText(distance)+24f
                    canvas.drawRoundRect(p.x-width/2f,p.y+23f,p.x+width/2f,p.y+55f,11f,11f,badge)
                    canvas.drawText(distance,p.x.toFloat(),p.y+46f,text)
                }
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
            val pixel=toPoint(mapView,loc.latitude,loc.longitude)
            val x=pixel.x.toFloat();val y=pixel.y.toFloat()
            val red=Color.rgb(226,76,65)
            // Compact red expedition pointer with a soft accuracy aura.
            val halo=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.argb(58,226,76,65);style=Paint.Style.FILL
            }
            canvas.drawCircle(x,y,19f,halo)
            val shadow=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.argb(210,22,25,24);style=Paint.Style.FILL
            }
            canvas.drawCircle(x,y,12.5f,shadow)
            val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.WHITE;strokeWidth=2.0f;style=Paint.Style.STROKE
            }
            canvas.drawCircle(x,y,9.0f,ring)
            val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=red;style=Paint.Style.FILL}
            canvas.drawCircle(x,y,8f,fill)
            // The north-facing directional chevron is rotated with sensor heading.
            canvas.save()
            canvas.rotate(northAzimuth,x,y)
            val pointer=Path().apply {
                moveTo(x,y-21f)
                lineTo(x-5.6f,y-7f)
                lineTo(x,y-10f)
                lineTo(x+5.6f,y-7f)
                close()
            }
            val outline=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.argb(230,248,235,212);style=Paint.Style.STROKE
                strokeWidth=1.3f;strokeJoin=Paint.Join.ROUND
            }
            canvas.drawPath(pointer,fill)
            canvas.drawPath(pointer,outline)
            canvas.restore()
            canvas.drawCircle(x,y,2.4f,Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=Color.WHITE;style=Paint.Style.FILL
            })
        }
    }
}
