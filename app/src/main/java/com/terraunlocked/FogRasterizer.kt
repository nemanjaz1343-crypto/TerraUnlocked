package com.terraunlocked

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/**
 * Immutable map projection snapshot created on the UI thread *after* map
 * gestures settle. No MapView/Projection reference reaches the worker.
 * Rasterization and two-pass smoothing run on a dedicated background thread.
 */
internal data class FogProjectedPoint(
    val x:Float,val y:Float,val joinPrevious:Boolean
)
internal data class FogProjectedLine(
    val x1:Float,val y1:Float,val x2:Float,val y2:Float
)
internal data class FogRasterInput(
    val width:Int,val height:Int,val pad:Int,
    val radiusPx:Float,
    val points:List<FogProjectedPoint>,
    val manualLines:List<FogProjectedLine>
)
internal data class FogRasterOutput(val mask:Bitmap,val route:Bitmap)

/** No Android UI thread operations are allowed inside render(). */
internal object FogRasterizer {
    private const val MASK_DIV=6f
    private const val ROUTE_DIV=3f

    fun render(data:FogRasterInput):FogRasterOutput {
        val pad=data.pad
        val mw=((data.width+2*pad)/MASK_DIV).toInt().coerceAtLeast(1)
        val mh=((data.height+2*pad)/MASK_DIV).toInt().coerceAtLeast(1)
        val rw=((data.width+2*pad)/ROUTE_DIV).toInt().coerceAtLeast(1)
        val rh=((data.height+2*pad)/ROUTE_DIV).toInt().coerceAtLeast(1)
        val bmp=Bitmap.createBitmap(mw,mh,Bitmap.Config.ARGB_8888)
        val route=Bitmap.createBitmap(rw,rh,Bitmap.Config.ARGB_8888)
        val c=Canvas(bmp)
        val routes=Canvas(route)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{
            color=Color.WHITE
            strokeJoin=Paint.Join.ROUND
            strokeCap=Paint.Cap.ROUND
        }
        val routeInk=Paint(Paint.ANTI_ALIAS_FLAG).apply{
            color=Color.WHITE
            style=Paint.Style.STROKE
            strokeWidth=(2.5f/ROUTE_DIV).coerceAtLeast(.85f)
            strokeCap=Paint.Cap.ROUND
        }
        val r=(data.radiusPx/MASK_DIV).coerceAtLeast(.5f)
        for(i in data.points.indices) {
            val p=data.points[i]
            val x=(p.x+pad)/MASK_DIV
            val y=(p.y+pad)/MASK_DIV
            paint.style=Paint.Style.FILL
            c.drawCircle(x,y,r,paint)
            if(i>0 && p.joinPrevious) {
                val prior=data.points[i-1]
                paint.style=Paint.Style.STROKE
                paint.strokeWidth=r*2f
                c.drawLine((prior.x+pad)/MASK_DIV,
                    (prior.y+pad)/MASK_DIV,x,y,paint)
                routes.drawLine((prior.x+pad)/ROUTE_DIV,
                    (prior.y+pad)/ROUTE_DIV,
                    (p.x+pad)/ROUTE_DIV,(p.y+pad)/ROUTE_DIV,routeInk)
            }
        }
        // Organic short-range bridges only; they change fog silhouette, not
        // GPS visit history or route lines. Spatial bucket avoids O(n²).
        val cell=(r*2.7f).coerceAtLeast(4f)
        val buckets=HashMap<Pair<Int,Int>,MutableList<Int>>()
        val bridge=Paint(Paint.ANTI_ALIAS_FLAG).apply{
            color=Color.WHITE
            style=Paint.Style.STROKE
            strokeWidth=r*1.85f
            strokeCap=Paint.Cap.ROUND
            strokeJoin=Paint.Join.ROUND
        }
        for(i in data.points.indices) {
            val p=data.points[i]
            val px=(p.x+pad)/MASK_DIV
            val py=(p.y+pad)/MASK_DIV
            val cx=floor(px/cell).toInt()
            val cy=floor(py/cell).toInt()
            for(gx in cx-1..cx+1)for(gy in cy-1..cy+1) {
                for(index in buckets[gx to gy].orEmpty()) {
                    val previous=data.points[index]
                    val qx=(previous.x+pad)/MASK_DIV
                    val qy=(previous.y+pad)/MASK_DIV
                    val distance=hypot(px-qx,py-qy)
                    if(distance>r*2f && distance<=r*2.8f)
                        c.drawLine(px,py,qx,qy,bridge)
                }
            }
            buckets.getOrPut(cx to cy){ArrayList()}.add(i)
        }
        for(line in data.manualLines) {
            val x=(line.x1+pad)/MASK_DIV
            val y=(line.y1+pad)/MASK_DIV
            val xx=(line.x2+pad)/MASK_DIV
            val yy=(line.y2+pad)/MASK_DIV
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=r*2f
            c.drawLine(x,y,xx,yy,paint)
            paint.style=Paint.Style.FILL
            c.drawCircle(x,y,r,paint)
            c.drawCircle(xx,yy,r,paint)
            routes.drawLine((line.x1+pad)/ROUTE_DIV,
                (line.y1+pad)/ROUTE_DIV,
                (line.x2+pad)/ROUTE_DIV,
                (line.y2+pad)/ROUTE_DIV,routeInk)
        }
        // Two bounded linear box-blur passes. This is intentionally outside
        // onDraw: the old implementation paused pan/pinch to do pixel loops.
        val pixels=IntArray(mw*mh)
        bmp.getPixels(pixels,0,mw,0,0,mw,mh)
        val intermediate=IntArray(mw*mh)
        val blur=(data.radiusPx*.65f/MASK_DIV).toInt().coerceIn(3,16)
        for(y in 0 until mh) {
            val offset=y*mw
            var sum=0
            for(x in 0..min(blur,mw-1))sum+=pixels[offset+x] ushr 24
            for(x in 0 until mw) {
                val count=min(mw-1,x+blur)-
                    (x-blur).coerceAtLeast(0)+1
                intermediate[offset+x]=sum/count
                val leave=x-blur
                val enter=x+blur+1
                if(leave>=0)sum-=pixels[offset+leave] ushr 24
                if(enter<mw)sum+=pixels[offset+enter] ushr 24
            }
        }
        for(x in 0 until mw) {
            var sum=0
            for(y in 0..min(blur,mh-1))sum+=intermediate[y*mw+x]
            for(y in 0 until mh) {
                val count=min(mh-1,y+blur)-
                    (y-blur).coerceAtLeast(0)+1
                val alpha=(((sum.toFloat()/count)-30f)*4.6f).toInt()
                    .coerceIn(0,255)
                pixels[y*mw+x]=if(alpha<10)0 else (alpha shl 24) or 0x00FFFFFF
                val leave=y-blur
                val enter=y+blur+1
                if(leave>=0)sum-=intermediate[leave*mw+x]
                if(enter<mh)sum+=intermediate[enter*mw+x]
            }
        }
        bmp.setPixels(pixels,0,mw,0,0,mw,mh)
        return FogRasterOutput(bmp,route)
    }
}

/** Pure geometry gates for deciding when a cached mask can be reused. */
internal object FogCachePolicy {
    fun needsFreshRaster(dataChanged:Boolean,zoomDelta:Double,
        shiftX:Float,shiftY:Float,pad:Int):Boolean =
        dataChanged || abs(zoomDelta)>.035 ||
            abs(shiftX)>pad*.30f || abs(shiftY)>pad*.30f
}
