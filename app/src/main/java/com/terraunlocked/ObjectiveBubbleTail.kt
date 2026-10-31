package com.terraunlocked

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Small map-anchored expedition callout pointer. It never covers the entire map. */
internal class ObjectiveBubbleTail(context:Context):View(context) {
    var anchorX:Float=0f
        set(value){field=value;invalidate()}
    var pointingUp=false
        set(value){field=value;invalidate()}
    private val d=resources.displayMetrics.density
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val cx=anchorX.coerceIn(17*d,width-17*d)
        val tip=if(pointingUp)1*d else height-1*d
        val base=if(pointingUp)height-2*d else 2*d
        val triangle=Path().apply{
            moveTo(cx-11*d,base);lineTo(cx,tip);lineTo(cx+11*d,base);close()
        }
        val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.rgb(25,34,27);style=Paint.Style.FILL
        }
        canvas.drawPath(triangle,ink)
        ink.color=Color.rgb(195,160,93);ink.style=Paint.Style.STROKE;ink.strokeWidth=1*d
        canvas.drawPath(triangle,ink)
    }
}
