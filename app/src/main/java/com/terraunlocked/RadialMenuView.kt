package com.terraunlocked

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Six translucent ring segments with a transparent, non-interactive aiming centre. */
class RadialMenuView(context:Context,private val onChoice:(Int)->Unit):View(context) {
    private val labels=listOf("FLAG","PHOTO","SEARCH","PIN","VOICE","NOTE")
    private val glyphs=listOf("⚑","▣","⌕","◆","●","✎")
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressed=-1
    private fun sector(x:Float,y:Float):Int {
        val dx=x-width/2f;val dy=y-height/2f
        val radius=hypot(dx,dy)
        val outer=min(width,height)*.49f
        if(radius<outer*.29f || radius>outer)return -1
        val degrees=(Math.toDegrees(atan2(dy.toDouble(),dx.toDouble()))+450.0)%360.0
        return ((degrees+30.0)/60.0).toInt()%6
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val cx=width/2f;val cy=height/2f
        val outer=min(width,height)*.49f
        val inner=outer*.29f
        for(i in 0 until 6) {
            val start=-120f+i*60f
            val path=Path().apply {
                addArc(cx-outer,cy-outer,cx+outer,cy+outer,start,59.4f)
                arcTo(cx-inner,cy-inner,cx+inner,cy+inner,start+59.4f,-59.4f,false)
                close()
            }
            ink.style=Paint.Style.FILL
            ink.color=if(pressed==i) Color.argb(210,111,132,90) else Color.argb(157,28,44,34)
            canvas.drawPath(path,ink)
            ink.style=Paint.Style.STROKE
            ink.strokeWidth=1.5f
            ink.color=Color.argb(200,173,194,146)
            canvas.drawPath(path,ink)
            val angle=Math.toRadians((-90+i*60).toDouble())
            val radius=(inner+outer)*.53f
            val x=cx+cos(angle).toFloat()*radius
            val y=cy+sin(angle).toFloat()*radius
            ink.style=Paint.Style.FILL
            ink.color=Color.rgb(232,217,176)
            ink.textAlign=Paint.Align.CENTER
            ink.typeface=Typeface.DEFAULT_BOLD
            ink.textSize=outer*.155f
            canvas.drawText(glyphs[i],x,y-outer*.025f,ink)
            ink.textSize=outer*.072f
            canvas.drawText(labels[i],x,y+outer*.12f,ink)
        }
        ink.style=Paint.Style.STROKE
        ink.strokeWidth=2f
        ink.color=Color.argb(220,231,212,157)
        canvas.drawCircle(cx,cy,inner*.63f,ink)
        canvas.drawLine(cx-inner*.3f,cy,cx+inner*.3f,cy,ink)
        canvas.drawLine(cx,cy-inner*.3f,cx,cy+inner*.3f,ink)
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {pressed=sector(event.x,event.y);invalidate();return true}
            MotionEvent.ACTION_UP -> {
                val hit=sector(event.x,event.y)
                val chosen=pressed
                pressed=-1;invalidate()
                if(chosen>=0 && chosen==hit)onChoice(chosen)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {pressed=-1;invalidate();return true}
        }
        return true
    }
}
