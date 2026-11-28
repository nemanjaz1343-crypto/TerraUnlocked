package com.terraunlocked

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.SystemClock
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * Pure sampling logic, shared by the preview and regression tests. A fast
 * finger cannot leave an unpainted gap even when MotionEvent batches moves.
 */
internal object FluidBrushSampling {
    fun steps(distancePx:Double,radiusPx:Float):Int {
        if(distancePx<=0.0)return 0
        val stride=(radiusPx*.48f).coerceAtLeast(1.5f)
        return ceil(distancePx/stride).toInt().coerceAtMost(800)
    }
    fun lerp(a:Double,b:Double,t:Float):Double=a+(b-a)*t
}

/**
 * Bitmap-backed soft fog brush. During a stroke we draw the old fog into ONE
 * mutable screen-space bitmap and fade radial holes into that bitmap at
 * 60Hz. Each frame only draws one prepared bitmap to the map. No rerasterized
 * GPS history, no full-screen GPU saveLayer and no stroke-path reprojection.
 */
internal class FluidRevealPreview {
    // Half-resolution screen-space mask has feathered edges by design and
    // uploads only 1/4 as many pixels per frame as a full-screen ARGB image.
    private val bufferScale=.5f
    private var screenWidth=0
    private var screenHeight=0
    private data class Stamp(
        val x:Float,val y:Float,val radius:Float,val at:Long,
        val gradient:RadialGradient,var removed:Float=0f
    )
    private val stamps=ArrayDeque<Stamp>()
    private val erase=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap=true
        xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val bitmapInk=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scanner=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style=Paint.Style.STROKE
        strokeWidth=1.7f
    }
    private var bitmap:Bitmap?=null
    private var rasterCanvas:Canvas?=null
    private var running=false
    private var waitingForRaster=false
    private var rasterReady=false
    private var cursorX=0f
    private var cursorY=0f
    private var cursorRadius=1f
    private var touchedAt=0L
    val visible:Boolean get()=bitmap!=null
    val isActive:Boolean get()=running
    val needsFrame:Boolean get()=running || stamps.isNotEmpty()

    fun begin(width:Int,height:Int,renderBase:(Canvas)->Unit,
        x:Float,y:Float,radius:Float) {
        if(width<=0||height<=0)return
        screenWidth=width
        screenHeight=height
        val bw=(width*bufferScale).toInt().coerceAtLeast(1)
        val bh=(height*bufferScale).toInt().coerceAtLeast(1)
        val old=bitmap
        if(old==null || old.isRecycled || old.width!=bw || old.height!=bh) {
            old?.recycle()
            bitmap=Bitmap.createBitmap(bw,bh,Bitmap.Config.ARGB_8888)
            rasterCanvas=bitmap?.let{Canvas(it).apply {
                scale(bufferScale,bufferScale)
            }}
        }
        bitmap?.eraseColor(Color.TRANSPARENT)
        rasterCanvas?.let(renderBase)
        stamps.clear()
        running=true
        waitingForRaster=false
        rasterReady=false
        showBitmap=true
        touchedAt=SystemClock.uptimeMillis()
        cursorX=x;cursorY=y;cursorRadius=radius.coerceAtLeast(1f)
        add(x,y,cursorRadius)
        advance(SystemClock.uptimeMillis())
    }

    private fun add(x:Float,y:Float,r:Float) {
        val radius=r.coerceAtLeast(1f)
        val gradient=RadialGradient(x,y,radius,
            intArrayOf(Color.WHITE,Color.WHITE,Color.TRANSPARENT),
            floatArrayOf(0f,.58f,1f),Shader.TileMode.CLAMP)
        stamps.addLast(Stamp(x,y,radius,SystemClock.uptimeMillis(),gradient))
        // Don't let unexpected batches monopolize the UI. Older stamps are
        // permanently applied, so the result still has no empty sections.
        while(stamps.size>140)apply(stamps.removeFirst(),1f)
    }

    fun move(x:Float,y:Float,radius:Float) {
        if(!running)return
        val dx=x-cursorX
        val dy=y-cursorY
        val count=FluidBrushSampling.steps(hypot(dx.toDouble(),dy.toDouble()),
            radius.coerceAtLeast(1f))
        val px=cursorX;val py=cursorY
        for(i in 1..count) {
            val t=i.toFloat()/count
            add(px+dx*t,py+dy*t,radius)
        }
        cursorX=x;cursorY=y;cursorRadius=radius
        advance(SystemClock.uptimeMillis())
    }

    private fun apply(stamp:Stamp,target:Float) {
        val old=stamp.removed
        if(target<=old)return
        // Calculate just the incremental alpha needed to reach a given
        // reveal opacity; prevents repeated frames overdrawing the hole.
        val increment=((target-old)/(1f-old).coerceAtLeast(.0001f)).coerceIn(0f,1f)
        erase.shader=stamp.gradient
        erase.alpha=(increment*255).toInt().coerceIn(0,255)
        rasterCanvas?.drawCircle(stamp.x,stamp.y,stamp.radius,erase)
        erase.shader=null
        stamp.removed=target
    }

    private fun advance(now:Long) {
        for(stamp in stamps) {
            val time=((now-stamp.at)/210f).coerceIn(0f,1f)
            val eased=time*time*(3f-2f*time)
            apply(stamp,(0.20f+0.80f*eased).coerceIn(0f,1f))
        }
        while(stamps.isNotEmpty() && stamps.first().removed>=.999f)
            stamps.removeFirst()
        if(waitingForRaster && rasterReady && stamps.isEmpty())cancel()
    }

    fun finish() {
        running=false
        waitingForRaster=true
        rasterReady=false
    }

    fun rasterUpdated() {
        // A new mask now includes the persisted discoveries. Do not release
        // before the fade completes or the user will see the old hard fog.
        if(waitingForRaster && !running)rasterReady=true
        if(rasterReady && stamps.isEmpty())cancel()
    }

    fun cancel() {
        running=false
        waitingForRaster=false
        rasterReady=false
        stamps.clear()
        // Keep bitmap allocation for the next stroke; mark it not visible.
        showBitmap=false
    }

    private var showBitmap=false

    fun draw(canvas:Canvas,accent:Int):Boolean {
        if(!showBitmap) return false
        val current=bitmap ?:return false
        val now=SystemClock.uptimeMillis()
        advance(now)
        if(!showBitmap)return false
        canvas.drawBitmap(current,null,
            RectF(0f,0f,screenWidth.toFloat(),screenHeight.toFloat()),bitmapInk)
        if(running) {
            val pulse=((now-touchedAt)%600L)/600f
            scanner.color=Color.argb((90f*(1f-pulse)).toInt(), 
                Color.red(accent),Color.green(accent),Color.blue(accent))
            canvas.drawCircle(cursorX,cursorY,
                cursorRadius*(.72f+.23f*pulse),scanner)
        }
        return true
    }

    /** Activity recreation must release old GPU-backed bitmap allocations. */
    fun release() {
        cancel()
        bitmap?.recycle()
        bitmap=null
        rasterCanvas=null
        screenWidth=0
        screenHeight=0
    }

    fun hasPendingWork():Boolean=showBitmap && needsFrame

}
