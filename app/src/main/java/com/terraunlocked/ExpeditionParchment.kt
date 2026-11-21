package com.terraunlocked

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/** Drawn entirely on device; a subtle parchment rather than an image download. */
internal class ExpeditionParchment(private val card:Boolean=false):Drawable() {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c:Canvas) {
        val b=bounds
        if(b.isEmpty)return
        val l=b.left.toFloat();val t=b.top.toFloat()
        val r=b.right.toFloat();val bottom=b.bottom.toFloat()
        val radius=if(card)12f else 22f
        p.style=Paint.Style.FILL
        p.shader=LinearGradient(l,t,r,bottom,
            intArrayOf(Color.rgb(247,230,195),Color.rgb(232,207,164),
                Color.rgb(213,179,128)),null,Shader.TileMode.CLAMP)
        c.drawRoundRect(l,t,r,bottom,radius,radius,p)
        p.shader=null
        // Aged edges and restrained brown specks; fixed seed avoids shimmer.
        p.style=Paint.Style.STROKE
        p.strokeWidth=if(card)1.5f else 2.5f
        p.color=Color.rgb(129,95,58)
        c.drawRoundRect(l+1.5f,t+1.5f,r-1.5f,bottom-1.5f,radius,radius,p)
        p.strokeWidth=1f
        p.color=Color.argb(75,125,91,58)
        c.drawRoundRect(l+6f,t+6f,r-6f,bottom-6f,
            radius-4f,radius-4f,p)
        val w=(r-l).coerceAtLeast(1f)
        val h=(bottom-t).coerceAtLeast(1f)
        p.style=Paint.Style.FILL
        val count=if(card)38 else 180
        for(i in 0 until count) {
            val x=l+10f+(i*79%977)/977f*(w-20f).coerceAtLeast(1f)
            val y=t+9f+(i*173%983)/983f*(h-18f).coerceAtLeast(1f)
            p.color=Color.argb(if(i%4==0)29 else 16,107,74,44)
            c.drawCircle(x,y,if(i%5==0)1.15f else .55f,p)
        }
        if(!card) {
            p.style=Paint.Style.STROKE;p.strokeWidth=.7f
            p.color=Color.argb(31,120,82,51)
            var y=t+54f
            while(y<bottom-16f) {
                c.drawLine(l+18f,y,r-18f,y,p)
                y+=34f
            }
        }
    }
    override fun setAlpha(alpha:Int){p.alpha=alpha;invalidateSelf()}
    override fun setColorFilter(filter:android.graphics.ColorFilter?){p.colorFilter=filter;invalidateSelf()}
    @Deprecated("Deprecated in Java")
    override fun getOpacity():Int=PixelFormat.TRANSLUCENT
}
