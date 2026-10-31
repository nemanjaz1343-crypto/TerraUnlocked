package com.terraunlocked

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Native hand-drawn expedition controls. The map itself is never repainted by these views. */
internal object ExpeditionArt {
    val gold=Color.rgb(213,175,106)
    val light=Color.rgb(240,218,166)
    val dark=Color.rgb(14,20,16)
    val moss=Color.rgb(43,47,32)
    val muted=Color.rgb(105,111,91)
    val p=Paint(Paint.ANTI_ALIAS_FLAG)
    fun fill(c:Canvas,l:Float,t:Float,r:Float,b:Float,col:Int) {
        p.style=Paint.Style.FILL;p.color=col;p.clearShadowLayer();c.drawRect(l,t,r,b,p)
    }
    fun panel(c:Canvas,rect:RectF,highlight:Boolean=false,radius:Float=10f) {
        p.style=Paint.Style.FILL;p.color=if(highlight)Color.rgb(54,49,31) else dark
        c.drawRoundRect(rect,radius,radius,p)
        // Faint leather/grain and military-map scuffs; deterministic, inexpensive.
        p.style=Paint.Style.STROKE;p.strokeWidth=.45f
        for(i in 0..75) {
            val x=rect.left+(i*47%997)/997f*rect.width()
            val y=rect.top+(i*73%991)/991f*rect.height()
            p.color=if(i%3==0)Color.argb(26,205,179,122) else Color.argb(21,117,131,103)
            if(x<rect.right-6f && y<rect.bottom-4f)c.drawLine(x,y,x+(i%5+1)*.9f,y+.65f,p)
        }
        p.style=Paint.Style.STROKE;p.strokeWidth=1.25f;p.color=if(highlight)light else gold
        c.drawRoundRect(RectF(rect.left+1,rect.top+1,rect.right-1,rect.bottom-1),radius,radius,p)
    }
    fun rule(c:Canvas,x1:Float,y1:Float,x2:Float,y2:Float,color:Int=gold,w:Float=1f) {
        p.style=Paint.Style.STROKE;p.strokeWidth=w;p.strokeCap=Paint.Cap.ROUND;p.color=color
        c.drawLine(x1,y1,x2,y2,p)
    }
    fun label(c:Canvas,txt:String,x:Float,y:Float,px:Float=13f,col:Int=light,serif:Boolean=false,spacing:Float=0f) {
        p.style=Paint.Style.FILL;p.color=col;p.textAlign=Paint.Align.CENTER;p.textSize=px
        p.typeface=if(serif)Typeface.create(Typeface.SERIF,Typeface.BOLD)
            else Typeface.create("sans-serif-condensed",Typeface.BOLD)
        if(spacing==0f)c.drawText(txt,x,y,p)
        else {
            val n=txt.length
            val widths=(0 until n).sumOf{p.measureText(txt[it].toString()).toDouble()}.toFloat()+spacing*(n-1)
            var at=x-widths/2f
            txt.forEach {ch->
                val word=ch.toString();val width=p.measureText(word)
                c.drawText(word,at+width/2f,y,p);at+=width+spacing
            }
        }
    }
    fun icon(c:Canvas,name:String,x:Float,y:Float,size:Float,col:Int=light) {
        c.save();c.translate(x,y);c.scale(size/24f,size/24f)
        p.style=Paint.Style.STROKE;p.strokeWidth=1.9f;p.strokeCap=Paint.Cap.ROUND
        p.strokeJoin=Paint.Join.ROUND;p.color=col
        fun line(x1:Float,y1:Float,x2:Float,y2:Float){c.drawLine(x1,y1,x2,y2,p)}
        fun path(vararg coords:Float) {
            val v=Path();v.moveTo(coords[0],coords[1])
            var i=2;while(i<coords.size){v.lineTo(coords[i],coords[i+1]);i+=2}
            c.drawPath(v,p)
        }
        when(name) {
            "track" -> {
                c.drawCircle(0f,0f,8f,p);c.drawCircle(0f,0f,3f,p)
                line(-12f,0f,-5f,0f);line(5f,0f,12f,0f)
                line(0f,-12f,0f,-5f);line(0f,5f,0f,12f)
            }
            "follow" -> {
                p.style=Paint.Style.FILL
                val v=Path().apply{moveTo(-7f,7f);lineTo(10f,-10f);lineTo(3f,10f);lineTo(-1f,2f);close()}
                c.drawPath(v,p)
            }
            "mark","paint" -> {
                c.save();c.rotate(37f)
                p.style=Paint.Style.FILL
                val v=Path().apply{moveTo(-3f,-11f);lineTo(3f,-11f);lineTo(4f,3f);lineTo(0f,12f);lineTo(-4f,3f);close()}
                c.drawPath(v,p);c.restore()
                if(name=="mark")line(-8f,7f,-11f,10f)
            }
            "erase" -> {
                c.save();c.rotate(-42f)
                c.drawRoundRect(-6f,-10f,6f,10f,2f,2f,p)
                line(-6f,2f,6f,2f);c.restore()
            }
            "kit" -> {
                // Expedition field bag, distinct from the map-source button above.
                c.drawRoundRect(-10f,-5f,10f,10f,2.2f,2.2f,p)
                path(-4f,-5f,-4f,-9f,4f,-9f,4f,-5f)
                line(-10f,1f,10f,1f)
                c.drawRoundRect(-2.2f,-1.8f,2.2f,4f,1f,1f,p)
            }
            "map" -> {
                path(-9f,-4f,0f,-9f,9f,-4f,0f,1f,-9f,-4f)
                path(-9f,2f,0f,7f,9f,2f)
                path(-9f,8f,0f,13f,9f,8f)
            }
            "undo","redo" -> {
                val d=if(name=="undo")-1f else 1f
                val v=Path().apply {
                    moveTo(-d*9f,-5f);lineTo(-d*3f,-11f);moveTo(-d*9f,-5f);lineTo(-d*2f,1f)
                    moveTo(-d*9f,-5f);lineTo(d*2f,-5f)
                    cubicTo(d*13f,-5f,d*12f,11f,d*1f,10f)
                }
                c.drawPath(v,p)
            }
            "path" -> {
                path(-9f,7f,-4f,0f,4f,2f,9f,-7f)
                for(pos in arrayOf(-9f to 7f,-4f to 0f,4f to 2f,9f to -7f)){
                    p.style=Paint.Style.FILL;c.drawCircle(pos.first,pos.second,2.4f,p)
                    p.style=Paint.Style.STROKE
                }
            }
            "lasso" -> {
                p.pathEffect=android.graphics.DashPathEffect(floatArrayOf(3.2f,3f),0f)
                c.drawCircle(0f,-1f,9f,p);p.pathEffect=null
                path(7f,7f,10f,12f)
            }
            "compass" -> {
                c.drawCircle(0f,0f,10f,p)
                p.style=Paint.Style.FILL
                val v=Path().apply{moveTo(0f,-9f);lineTo(5f,8f);lineTo(0f,4f);lineTo(-5f,8f);close()}
                c.drawPath(v,p)
            }
            "sun" -> {
                c.drawCircle(0f,0f,5f,p)
                for(i in 0..7){val th=i*Math.PI/4
                    line((8*cos(th)).toFloat(),(8*sin(th)).toFloat(),(12*cos(th)).toFloat(),(12*sin(th)).toFloat())
                }
            }
            "book" -> {
                path(-11f,-8f,-2f,-8f,0f,-5f,0f,10f,-3f,7f,-11f,7f,-11f,-8f)
                path(0f,-5f,3f,-8f,11f,-8f,11f,7f,3f,7f,0f,10f)
            }
            "chevron" -> {path(3f,-7f,-3f,0f,3f,7f)}
            "up" -> {
                p.style=Paint.Style.FILL;path(0f,-10f,8f,6f,-8f,6f,0f,-10f)
            }
            "check" -> {path(-9f,0f,-2f,7f,10f,-8f)}
        }
        c.restore()
    }
}

internal class ExpeditionHeaderView(context:Context,
    val onMissions:()->Unit,val onMap:()->Unit,val onUpdate:()->Unit,
    val onNorth:()->Unit):View(context) {
    var bearing:Int?=null;set(value){field=value;invalidate()}
    var temperature:Int?=null;set(value){field=value;invalidate()}
    private val d=resources.displayMetrics.density
    private val crest by lazy { android.graphics.BitmapFactory.decodeResource(resources,R.drawable.terra_mark) }
    override fun onDraw(c:Canvas) {
        val w=width.toFloat();val h=height.toFloat();if(w==0f)return
        val scale=w/393f
        c.save();c.scale(scale,scale)
        val hh=h/scale
        ExpeditionArt.fill(c,0f,0f,393f,hh,ExpeditionArt.dark)
        ExpeditionArt.panel(c,RectF(10f,6f,94f,hh-8f),false,5f)
        ExpeditionArt.label(c,"MISSIONS ›",52f,hh/2f+5f,12f,ExpeditionArt.light,true,.3f)
        ExpeditionArt.rule(c,108f,hh/2f,130f,hh/2f)
        // Original crest clipped outside its circular silhouette, supplied by the user.
        c.drawBitmap(crest,null,RectF(128f,hh/2f-22f,168f,hh/2f+22f),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        ExpeditionArt.label(c,"T E R R A",213f,hh/2f+6f,19f,ExpeditionArt.light,true,.4f)
        ExpeditionArt.rule(c,182f,hh/2f+11f,247f,hh/2f+11f,ExpeditionArt.gold,.9f)
        ExpeditionArt.rule(c,265f,hh/2f,273f,hh/2f)
        ExpeditionArt.icon(c,"compass",288f,hh/2f-7f,20f)
        ExpeditionArt.label(c,"N "+(bearing?.let{"%03d°".format(it)} ?: "---°"),288f,hh/2f+19f,10f)
        ExpeditionArt.rule(c,313f,7f,313f,hh-7f,ExpeditionArt.muted)
        ExpeditionArt.icon(c,"sun",339f,hh/2f-7f,20f)
        ExpeditionArt.label(c,(temperature?.toString()?:"--")+"°",339f,hh/2f+19f,10f)
        ExpeditionArt.rule(c,359f,7f,359f,hh-7f,ExpeditionArt.muted)
        ExpeditionArt.icon(c,"map",375f,hh/2f-7f,21f)
        ExpeditionArt.label(c,"MAP",375f,hh/2f+19f,10f)
        ExpeditionArt.rule(c,0f,hh-1f,393f,hh-1f,ExpeditionArt.gold,1.25f)
        c.restore()
    }
    private var down=0f
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked==MotionEvent.ACTION_DOWN){down=event.x;return true}
        if(event.actionMasked==MotionEvent.ACTION_UP) {
            val frac=event.x/width
            if(abs(event.x-down)<60*d) {
                when {
                    frac<.25f->onMissions()
                    frac in .7f.. .805f->onNorth()
                    frac>=.895f->onMap()
                    frac in .805f.. .895f->onUpdate()
                }
            }
            return true
        }
        return true
    }
}
internal class ExpeditionBottomView(context:Context,val onSelect:(Int)->Unit):View(context) {
    var selected:Int=2;set(value){field=value;invalidate()}
    var trackingActive:Boolean=false;set(value){field=value;invalidate()}
    var gpsSearching:Boolean=false;set(value){
        if(field==value)return
        field=value
        if(value){if(isAttachedToWindow)radar.start()}
        else {radar.cancel();scanPhase=0f}
        invalidate()
    }
    private var scanPhase=0f
    private val radar=android.animation.ValueAnimator.ofFloat(0f,1f).apply {
        duration=1450L
        repeatCount=android.animation.ValueAnimator.INFINITE
        interpolator=android.view.animation.LinearInterpolator()
        addUpdateListener{scanPhase=it.animatedValue as Float;invalidate()}
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(gpsSearching)radar.start()}
    override fun onDetachedFromWindow(){radar.cancel();super.onDetachedFromWindow()}
    private var pressedIndex=-1
    private val d=resources.displayMetrics.density
    private val labels=arrayOf("TRACK","FOLLOW","MARK","ERASE","KIT")
    private val icons=arrayOf("track","follow","mark","erase","kit")
    override fun onDraw(c:Canvas) {
        val w=width.toFloat();val h=height.toFloat()
        ExpeditionArt.fill(c,0f,0f,w,h,ExpeditionArt.dark)
        ExpeditionArt.panel(c,RectF(3*d,3*d,w-3*d,h-3*d),false,8*d)
        val step=(w-6*d)/5
        for(i in 0..4){
            val x=3*d+(i+.5f)*step
            if(i==selected || i==pressedIndex || (i==0 && trackingActive)){
                ExpeditionArt.panel(c,RectF(3*d+i*step,3*d,3*d+(i+1)*step,h-3*d),true,7*d)
                ExpeditionArt.rule(c,x-step*.27f,h-8*d,x+step*.27f,h-8*d,ExpeditionArt.light,2*d)
            }
            if(i>0 && i!=selected && i-1!=selected)
                ExpeditionArt.rule(c,3*d+i*step,11*d,3*d+i*step,h-10*d,ExpeditionArt.muted,.75f*d)
            ExpeditionArt.icon(c,icons[i],x,17*d,21*d,ExpeditionArt.light)
            if(i==0 && gpsSearching){
                // Two widening radar rings show that Android has not delivered its first fix.
                val ring=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style=Paint.Style.STROKE;strokeWidth=1.2f*d
                    color=Color.argb(((1f-scanPhase)*210).toInt(),230,193,107)
                }
                c.drawCircle(x,17*d,(10f+10f*scanPhase)*d,ring)
                ring.alpha=((scanPhase)*135).toInt()
                c.drawCircle(x,17*d,(10f+10f*((scanPhase+0.5f)%1f))*d,ring)
            }
            ExpeditionArt.label(c,if(i==0 && gpsSearching)"SEARCH" else if(i==0 && trackingActive)"STOP" else labels[i],x,h-8*d,10.5f*d,ExpeditionArt.light,false,.4f*d)
            if(i==0 && trackingActive){
                ExpeditionArt.p.style=Paint.Style.FILL
                ExpeditionArt.p.color=Color.rgb(181,216,132)
                c.drawCircle(x+17*d,13*d,3*d,ExpeditionArt.p)
            }
        }
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex=(5*e.x/width).toInt().coerceIn(0,4)
                invalidate();return true
            }
            MotionEvent.ACTION_UP -> {
                val idx=(5*e.x/width).toInt().coerceIn(0,4)
                val shouldClick=idx==pressedIndex
                pressedIndex=-1;invalidate()
                if(shouldClick)onSelect(idx)
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {pressedIndex=-1;invalidate();return true}
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}
internal class ExpeditionMarkPanelView(context:Context,val onSelect:(Int)->Unit):View(context) {
    var selected:Int=-1;set(value){field=value;invalidate()}
    var canUndo:Boolean=false;set(value){field=value;invalidate()}
    var canRedo:Boolean=false;set(value){field=value;invalidate()}
    private val d=resources.displayMetrics.density
    override fun onDraw(c:Canvas) {
        val w=width.toFloat();val h=height.toFloat()
        val bottom=h-15*d
        ExpeditionArt.panel(c,RectF(1.5f*d,1.5f*d,w-1.5f*d,bottom),false,9*d)
        val step=(w-4*d)/5
        val names=arrayOf("undo","redo","path","paint","lasso")
        for(i in 0..4){
            val x=2*d+(i+.5f)*step
            if(i==selected && i>=2)
                ExpeditionArt.panel(c,RectF(x-step*.36f,5*d,x+step*.36f,bottom-5*d),true,6*d)
            if(i==2)ExpeditionArt.rule(c,x-step/2,10*d,x-step/2,bottom-8*d,ExpeditionArt.muted,.8f*d)
            ExpeditionArt.icon(c,names[i],x,21*d,21*d,if((i==0&&!canUndo)||(i==1&&!canRedo))ExpeditionArt.muted else ExpeditionArt.light)
            if(i>=2)ExpeditionArt.label(c,names[i].uppercase(),x,bottom-7*d,9.5f*d)
        }
        val tri=Path().apply {moveTo(w/2-12*d,bottom);lineTo(w/2,bottom+13*d);lineTo(w/2+12*d,bottom)}
        ExpeditionArt.p.style=Paint.Style.STROKE;ExpeditionArt.p.color=ExpeditionArt.gold
        ExpeditionArt.p.strokeWidth=1.5f*d;c.drawPath(tri,ExpeditionArt.p)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(e.actionMasked==MotionEvent.ACTION_UP) {
            onSelect((e.x/width*5).toInt().coerceIn(0,4));return true
        }
        return true
    }
}
internal class ExpeditionJournalTab(context:Context,val onToggle:()->Unit):View(context) {
    private val d=resources.displayMetrics.density
    var open:Boolean=false;set(value){field=value;invalidate()}
    override fun onDraw(c:Canvas) {
        val w=width.toFloat();val h=height.toFloat()
        ExpeditionArt.panel(c,RectF(0f,2*d,w+5*d,h-2*d),false,7*d)
        c.save()
        if(open) {c.rotate(180f,w*.46f,h*.35f)}
        ExpeditionArt.icon(c,"chevron",w*.46f,h*.35f,16*d,ExpeditionArt.light)
        c.restore()
        ExpeditionArt.icon(c,"book",w*.46f,h*.70f,18*d,ExpeditionArt.light)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(e.actionMasked==MotionEvent.ACTION_UP){onToggle();return true}
        return true
    }
}
