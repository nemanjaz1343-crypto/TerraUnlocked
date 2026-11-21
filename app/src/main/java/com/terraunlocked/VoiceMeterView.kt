package com.terraunlocked

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.max

/** Lightweight recording activity indicator updated from MediaRecorder.maxAmplitude. */
internal class VoiceMeterView(context:Context):View(context) {
    private val samples=FloatArray(32){0.04f}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density=resources.displayMetrics.density
    fun sample(value:Int) {
        val signal=(value.coerceAtLeast(0).toFloat()/32767f).coerceIn(0f,1f)
        System.arraycopy(samples,1,samples,0,samples.size-1)
        samples[samples.lastIndex]=max(0.05f,kotlin.math.sqrt(signal))
        postInvalidateOnAnimation()
    }
    fun reset() {
        samples.fill(0.04f)
        invalidate()
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val w=width.toFloat()
        val h=height.toFloat()
        val step=w/samples.size
        p.style=Paint.Style.FILL
        p.color=ExpeditionThemes.current.gold
        for(i in samples.indices) {
            val barHeight=max(3f*density,h*samples[i]*0.88f)
            val x=i*step+step*0.16f
            canvas.drawRoundRect(x,(h-barHeight)/2f,x+step*.67f,
                (h+barHeight)/2f,step*.2f,step*.2f,p)
        }
    }
}
