package com.terraunlocked

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.BitmapShader
import android.graphics.Matrix
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
    var radiusMeters: Double = 40.0
) : Overlay() {
    private data class RevealPulse(val lat:Double,val lon:Double,val start:Long)
    private val pulses=ArrayDeque<RevealPulse>()
    fun pulseReveal(lat:Double,lon:Double) {
        val now=android.os.SystemClock.uptimeMillis()
        pulses.addLast(RevealPulse(lat,lon,now))
        while(pulses.size>6)pulses.removeFirst()
    }
    var fogVisible = true
    var opacity = 205
    var showRoute = true
    var showCheckpoints = true
    var backtrack = false
    var currentPosition: GeoPoint? = null
    var positionAccuracy = 0f
    var northAzimuth = 0f
    private val fogPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ExpeditionThemes.current.dark
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ExpeditionThemes.current.gold; strokeWidth = 2.5f; style = Paint.Style.STROKE
    }
    private fun toPoint(map: MapView, lat: Double, lon: Double): Point =
        map.projection.toPixels(GeoPoint(lat, lon), Point())
    private fun radiusPx(map: MapView, lat: Double, lon: Double): Float {
        val p = toPoint(map, lat, lon)
        val east = toPoint(map, lat, lon + radiusMeters / (111320.0 * cos(Math.toRadians(lat)).coerceAtLeast(.01)))
        return kotlin.math.abs(east.x - p.x).toFloat().coerceAtLeast(1f)
    }
    private fun canJoin(points:List<DiscoveryPoint>, index:Int):Boolean {
        val a=points[index-1]
        val b=points[index]
        if(a.manual || b.manual)return false
        fun converted(p:DiscoveryPoint)=TraceReconstruction.Fix(
            p.latitude,p.longitude,p.timestamp,p.accuracy,p.speed
        )
        val before=if(index>=2 && !points[index-2].manual)converted(points[index-2]) else null
        val after=if(index+1<points.size && !points[index+1].manual)
            converted(points[index+1]) else null
        return TraceReconstruction.shouldJoin(
            converted(a),converted(b),before,after)
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

    // A mask and route raster are produced on a single background worker.
    // onDraw NEVER loops through old GPS positions or blurs CPU pixels.
    private val mainThread=android.os.Handler(android.os.Looper.getMainLooper())
    private val rasterWorker=java.util.concurrent.Executors.newSingleThreadExecutor{
        job->Thread(job,"terra-fog-raster").apply{isDaemon=true}
    }
    private var organicMask:android.graphics.Bitmap?=null
    private var fogShader:BitmapShader?=null
    private val shaderMatrix=Matrix()
    private var routeRaster:android.graphics.Bitmap?=null
    private var cachedOrigin:GeoPoint?=null
    private var cachedOriginPixel:Point?=null
    private var cachedShiftX=0f
    private var cachedShiftY=0f
    private var cachedZoom=Double.NaN
    private var cachedScale=1f
    private var cachedPad=320
    private var cachedWidth=0
    private var cachedHeight=0
    private var activeMap:MapView?=null
    private var pendingBuild=false
    private var nextBuildAt=0L
    private var inFlightBuilds=0
    private var generation=0
    private var lastSignature=""
    private var lastPanElapsed=0L
    private val fluidBrush=FluidRevealPreview()
    private var released=false

    /** Match raster worker/bitmap lifetime to the Activity, not the process. */
    fun release() {
        if(released)return
        released=true
        generation++
        mainThread.removeCallbacks(rebuild)
        rasterWorker.shutdownNow()
        organicMask?.recycle()
        routeRaster?.recycle()
        organicMask=null
        routeRaster=null
        fogShader=null
        activeMap=null
        fluidBrush.release()
    }

    /** Direct finger-to-map drawing. No history bitmap rebuild per move. */
    fun beginFluidReveal(map:MapView,x:Float,y:Float) {
        val loc=map.projection.fromPixels(x.toInt(),y.toInt())
        val r=radiusPx(map,loc.latitude,loc.longitude).coerceAtLeast(4f)
        fluidBrush.begin(map.width,map.height,
            {base->drawCachedFog(base,map)},x,y,r)
        map.postInvalidateOnAnimation()
    }
    fun moveFluidReveal(map:MapView,x:Float,y:Float) {
        val loc=map.projection.fromPixels(x.toInt(),y.toInt())
        val r=radiusPx(map,loc.latitude,loc.longitude).coerceAtLeast(4f)
        fluidBrush.move(x,y,r)
        map.postInvalidateOnAnimation()
    }
    fun endFluidReveal(map:MapView) {
        fluidBrush.finish()
        map.postInvalidateOnAnimation()
    }
    fun cancelFluidReveal(map:MapView) {
        fluidBrush.cancel()
        map.postInvalidateOnAnimation()
    }

    private val maskPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{
        colorFilter=android.graphics.PorterDuffColorFilter(
            ExpeditionThemes.current.dark,android.graphics.PorterDuff.Mode.SRC_IN)
    }
    private val bitmapRoutePaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{
        colorFilter=android.graphics.PorterDuffColorFilter(
            ExpeditionThemes.current.gold,android.graphics.PorterDuff.Mode.SRC_IN)
    }
    private val rebuild=Runnable{
        val map=activeMap
        if(map!=null)prepareSnapshot(map)
        else pendingBuild=false
    }
    /**
     * A cached geographical mask can be reprojected while the map moves.
     * Do not invalidate a worker on EVERY pan event: that left the cached
     * raster stranded and created visible seams at the overscan boundary.
     * Trigger a near-term rebuild when reaching the safe outer margin.
     */
    fun onMapPanned() {
        lastPanElapsed=android.os.SystemClock.uptimeMillis()
        val map=activeMap
        val urgent=if(map!=null && organicMask!=null) {
            val bounds=cachedBounds(map)
            val margin=(minOf(map.width,map.height)*.16f).coerceAtLeast(65f)
            bounds.left> -margin || bounds.top> -margin ||
                bounds.right<map.width+margin ||
                bounds.bottom<map.height+margin ||
                kotlin.math.abs(map.zoomLevelDouble-cachedZoom)>.12
        }else true
        if(urgent) {
            if(inFlightBuilds==0)scheduleRaster(35L)
        } else scheduleRaster(170L)
    }

    /** History import, time-machine or brush committed: use the same debounce. */
    fun invalidateData() {
        generation++
        lastSignature=""
        scheduleRaster(110L)
    }

    private fun scheduleRaster(delayMs:Long) {
        if(released)return
        val target=android.os.SystemClock.uptimeMillis()+delayMs
        if(pendingBuild && nextBuildAt<=target)return
        pendingBuild=true
        nextBuildAt=target
        mainThread.removeCallbacks(rebuild)
        mainThread.postDelayed(rebuild,delayMs)
    }

    private fun prepareSnapshot(map:MapView) {
        if(released)return
        // Never project the full history mid-drag or while brush samples are
        // coming in. The clamped shader can safely span any exposed edges
        // until movement settles. Preserve 60Hz gesture responsiveness.
        pendingBuild=false
        nextBuildAt=0L
        if(fluidBrush.isActive ||
            (organicMask!=null &&
                android.os.SystemClock.uptimeMillis()-lastPanElapsed<100L)) {
            scheduleRaster(115L)
            return
        }
        // Projection metadata is captured on UI thread only when idle.
        // Rasterization/blur stays on the dedicated worker.
        val visible=records()
        val simulation=demo()
        val points=if(simulation.isEmpty())visible else visible+simulation
        val manual=segments()
        val w=map.width.coerceAtLeast(1)
        val h=map.height.coerceAtLeast(1)
        val pad=(maxOf(w,h)/2).coerceAtLeast(320)
        val center=map.mapCenter
        val origin=GeoPoint(center.latitude,center.longitude)
        val projectedOrigin=map.projection.toPixels(origin,Point())
        val zoom=map.zoomLevelDouble
        val radius=radiusPx(map,center.latitude,center.longitude)
        // Cull history outside the padded visible viewport BEFORE projecting
        // expensive map coordinates. Keep source indices so route segments
        // never join across skipped points.
        val corners=listOf(
            map.projection.fromPixels(-pad,-pad),
            map.projection.fromPixels(w+pad,-pad),
            map.projection.fromPixels(-pad,h+pad),
            map.projection.fromPixels(w+pad,h+pad))
        val minLat=corners.minOf{it.latitude}.coerceAtLeast(-90.0)
        val maxLat=corners.maxOf{it.latitude}.coerceAtMost(90.0)
        val lonMin=corners.minOf{it.longitude}
        val lonMax=corners.maxOf{it.longitude}
        val canCullLongitude=lonMax-lonMin<180.0
        val projected=ArrayList<FogProjectedPoint>(
            minOf(points.size,2048))
        var lastIndex=-2
        for(i in points.indices) {
            val p=points[i]
            if(p.latitude !in minLat..maxLat ||
                (canCullLongitude && p.longitude !in lonMin..lonMax))continue
            val xy=toPoint(map,p.latitude,p.longitude)
            projected.add(FogProjectedPoint(xy.x.toFloat(),xy.y.toFloat(),
                i>0 && lastIndex==i-1 && canJoin(points,i)))
            lastIndex=i
        }
        val lines=manual.map{seg->
            val a=toPoint(map,seg.aLat,seg.aLon)
            val b=toPoint(map,seg.bLat,seg.bLon)
            FogProjectedLine(a.x.toFloat(),a.y.toFloat(),
                b.x.toFloat(),b.y.toFloat())
        }
        val request=++generation
        inFlightBuilds++
        val snapshot=FogRasterInput(w,h,pad,radius,projected,lines)
        rasterWorker.execute {
            val raster=runCatching{FogRasterizer.render(snapshot)}.getOrNull()
            mainThread.post {
                inFlightBuilds=(inFlightBuilds-1).coerceAtLeast(0)
                if(released || request!=generation) {
                    raster?.mask?.recycle()
                    raster?.route?.recycle()
                    return@post
                }
                if(raster==null) {
                    // Keep the previous bitmap; don't block the map if
                    // allocation fails or Android is low on memory.
                    return@post
                }
                val priorMask=organicMask
                val priorRoute=routeRaster
                organicMask=raster.mask
                fogShader=BitmapShader(raster.mask,
                    Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)
                routeRaster=raster.route
                if(priorMask!==raster.mask)priorMask?.recycle()
                if(priorRoute!==raster.route)priorRoute?.recycle()
                fluidBrush.rasterUpdated()
                cachedWidth=w;cachedHeight=h;cachedPad=pad
                cachedZoom=zoom;cachedScale=1f
                cachedOrigin=origin
                cachedOriginPixel=projectedOrigin
                cachedShiftX=0f;cachedShiftY=0f
                activeMap?.postInvalidateOnAnimation()
            }
        }
    }

    private fun cachedBounds(map:MapView):android.graphics.RectF {
        val origin=cachedOrigin
        val initial=cachedOriginPixel
        if(origin!=null && initial!=null && cachedZoom.isFinite()) {
            val now=map.projection.toPixels(origin,Point())
            val scale=Math.pow(2.0,map.zoomLevelDouble-cachedZoom).toFloat()
            cachedScale=scale
            cachedShiftX=now.x-initial.x*scale
            cachedShiftY=now.y-initial.y*scale
        }
        return android.graphics.RectF(
            cachedShiftX-cachedPad*cachedScale,
            cachedShiftY-cachedPad*cachedScale,
            cachedShiftX+(cachedWidth+cachedPad)*cachedScale,
            cachedShiftY+(cachedHeight+cachedPad)*cachedScale)
    }

    /**
     * One shader-filled quad covers the ENTIRE viewport. CLAMP repeats the
     * opaque edge pixels of the cached inverse fog outside the bitmap, so
     * there are no mismatched hard strips or half-transparent bitmap seams
     * as the viewport pans and zooms beyond its original padded bounds.
     */
    private fun drawCachedFog(canvas:Canvas,mapView:MapView) {
        val angle=Math.toRadians(mapView.mapOrientation.toDouble())
        // Use the real viewport dimensions, not the brush's optional
        // half-resolution offscreen canvas dimensions.
        val w=mapView.width.toFloat();val h=mapView.height.toFloat()
        val ca=kotlin.math.abs(kotlin.math.cos(angle)).toFloat()
        val sa=kotlin.math.abs(kotlin.math.sin(angle)).toFloat()
        val dx=((ca*w+sa*h-w)*.5f).coerceAtLeast(0f)+8f
        val dy=((ca*h+sa*w-h)*.5f).coerceAtLeast(0f)+8f
        val extent=android.graphics.RectF(-dx,-dy,w+dx,h+dy)
        fogPaint.alpha=opacity.coerceIn(0,255)
        maskPaint.alpha=opacity.coerceIn(0,255)
        val fog=organicMask
        val shader=fogShader
        if(fog==null || shader==null || fog.isRecycled) {
            canvas.drawRect(extent,fogPaint)
            return
        }
        val bounds=cachedBounds(mapView)
        shaderMatrix.setScale(bounds.width()/fog.width,
            bounds.height()/fog.height)
        shaderMatrix.postTranslate(bounds.left,bounds.top)
        shader.setLocalMatrix(shaderMatrix)
        maskPaint.shader=shader
        canvas.drawRect(extent,maskPaint)
        maskPaint.shader=null
    }

    override fun draw(canvas:Canvas,mapView:MapView,shadow:Boolean) {
        if(shadow || released)return
        activeMap=mapView
        val live=records()
        val simulated=demo()
        val points=if(simulated.isEmpty())live else live+simulated
        val existingSegments=segments()
        val last=points.lastOrNull()
        val end=existingSegments.lastOrNull()
        val signature="${canvas.width}:${canvas.height}:${points.size}:"+
            "${last?.latitude}:${last?.longitude}:${last?.timestamp}:"+
            "${existingSegments.size}:${end?.aLat}:${end?.bLon}:$radiusMeters"
        if(signature!=lastSignature) {
            lastSignature=signature
            generation++
            scheduleRaster(140L)
        } else if(organicMask==null && !pendingBuild && inFlightBuilds==0) {
            scheduleRaster(140L)
        }
        val bounds=cachedBounds(mapView)
        if(fogVisible) {
            // During a pen gesture the brush owns one cached fog bitmap and
            // updates only the small feathered circle under the finger.
            // This replaces full-screen per-frame saveLayer + all-point path
            // reprojection and removes the old hard white preview marker.
            if(!fluidBrush.draw(canvas,ExpeditionThemes.current.gold))
                drawCachedFog(canvas,mapView)
            if(fluidBrush.hasPendingWork())mapView.postInvalidateOnAnimation()
        }
        // A single textured quad replaces thousands of per-frame GPS line
        // projections, TraceReconstruction checks and Canvas drawLine calls.
        if(showRoute)routeRaster?.let{canvas.drawBitmap(it,null,bounds,bitmapRoutePaint)}
        // Gentle expanding liquid edge, rendered above the revealed map.
        val now=android.os.SystemClock.uptimeMillis()
        while(pulses.isNotEmpty() && now-pulses.first().start>1100L)pulses.removeFirst()
        val pulsePaint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.STROKE;strokeWidth=3f
        }
        for(pulse in pulses) {
            val progress=((now-pulse.start)/1100f).coerceIn(0f,1f)
            val xy=toPoint(mapView,pulse.lat,pulse.lon)
            val base=radiusPx(mapView,pulse.lat,pulse.lon)
            pulsePaint.color=Color.argb(((1f-progress)*(1f-progress)*105f).toInt(),228,242,208)
            pulsePaint.strokeWidth=1.5f+3f*(1f-progress)
            canvas.drawCircle(xy.x.toFloat(),xy.y.toFloat(),base*(0.45f+0.62f*progress),pulsePaint)
        }
        if(pulses.isNotEmpty())mapView.postInvalidateDelayed(45L)
        if (showCheckpoints) {
            val palette = mapOf("CHECKPOINT" to Color.rgb(228,183,85),
                "POI" to Color.rgb(91,204,210), "MEMORY" to Color.rgb(203,150,220),
                "CAMP" to Color.rgb(147,198,105), "PHOTO" to Color.rgb(237,174,118), "VOICE" to Color.rgb(239,118,126))
            notes().forEach { note ->
                val p=toPoint(mapView,note.latitude,note.longitude)
                // Do not create multiple Paints, distance badges or strings
                // for journal markers completely outside the viewport.
                if(p.x < -90 || p.y < -90 ||
                    p.x > canvas.width+90 || p.y > canvas.height+90)
                    return@forEach
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
            for(i in 1 until trail.size) if(canJoin(trail,i)) {
                val a=toPoint(mapView,trail[i-1].latitude,trail[i-1].longitude)
                val b=toPoint(mapView,trail[i].latitude,trail[i].longitude)
                canvas.drawLine(a.x.toFloat(),a.y.toFloat(),b.x.toFloat(),b.y.toFloat(),paint)
            }
        }
    }
    /** Called by the final map overlay so the user's marker always wins z-order. */
    fun drawCurrentPosition(canvas:Canvas,mapView:MapView) {
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
