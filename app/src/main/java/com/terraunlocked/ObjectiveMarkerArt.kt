package com.terraunlocked

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.content.Context
import kotlin.math.*

/** Compact vector objective HUD; no bitmap assets or emoji fonts required. */
class ObjectiveMarkerArt(context:Context) {
    private val density=context.resources.displayMetrics.density
    private fun d(v:Float)=v*density
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun stroke(color:Int,width:Float=1.7f) {
        paint.color=color;paint.style=Paint.Style.STROKE;paint.strokeWidth=d(width)
        paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND
        paint.clearShadowLayer()
    }
    private fun line(c:Canvas,x:Float,y:Float,a:Float,b:Float,e:Float,f:Float) {
        c.drawLine(x+d(a),y+d(b),x+d(e),y+d(f),paint)
    }
    private fun glyph(c:Canvas,x:Float,y:Float,type:String,color:Int) {
        stroke(color,1.8f)
        val path=Path()
        fun poly(vararg pts:Float) {
            path.reset();path.moveTo(x+d(pts[0]),y+d(pts[1]))
            var i=2
            while(i<pts.size){path.lineTo(x+d(pts[i]),y+d(pts[i+1]));i+=2}
            c.drawPath(path,paint)
        }
        when(type.uppercase()) {
            "ARCH","LANDMARK" -> {poly(-7f,7f,-7f,-3f,0f,-8f,7f,-3f,7f,7f);line(c,x,y,-3f,-3f,-3f,7f);line(c,x,y,3f,-3f,3f,7f)}
            "HERITAGE" -> {poly(-8f,-4f,0f,-9f,8f,-4f);line(c,x,y,-7f,-4f,-7f,7f);line(c,x,y,7f,-4f,7f,7f);line(c,x,y,-8f,7f,8f,7f);line(c,x,y,0f,-4f,0f,7f)}
            "NATURE" -> {poly(0f,8f,0f,-2f);poly(0f,-7f,-7f,1f,0f,0f,7f,-5f);poly(0f,3f,-7f,-2f)}
            "ART","PHOTO" -> {c.drawRoundRect(RectF(x-d(8f),y-d(5f),x+d(8f),y+d(7f)),d(2f),d(2f),paint);c.drawCircle(x,y+d(1f),d(3f),paint);poly(-5f,-5f,-3f,-8f,3f,-8f,5f,-5f)}
            "FOOD" -> {line(c,x,y,-6f,-8f,-6f,8f);line(c,x,y,-9f,-8f,-9f,-1f);line(c,x,y,-3f,-8f,-3f,-1f);line(c,x,y,-9f,-1f,-3f,-1f);line(c,x,y,5f,-8f,5f,8f);poly(5f,-8f,9f,-4f,9f,2f,5f,2f)}
            "CAFE" -> {poly(-8f,-4f,5f,-4f,5f,3f,2f,7f,-5f,7f,-8f,3f,-8f,-4f);poly(5f,-2f,9f,-2f,9f,2f,5f,3f)}
            "TRANSIT" -> {c.drawRoundRect(RectF(x-d(7f),y-d(8f),x+d(7f),y+d(5f)),d(3f),d(3f),paint);line(c,x,y,-5f,-2f,5f,-2f);line(c,x,y,-5f,8f,-2f,5f);line(c,x,y,5f,8f,2f,5f)}
            "HOME" -> {poly(-8f,0f,0f,-7f,8f,0f);poly(-6f,-1f,-6f,7f,6f,7f,6f,-1f);poly(-2f,7f,-2f,2f,2f,2f,2f,7f)}
            "OFFICE" -> {c.drawRoundRect(RectF(x-d(8f),y-d(3f),x+d(8f),y+d(7f)),d(1f),d(1f),paint);poly(-4f,-3f,-4f,-7f,4f,-7f,4f,-3f);line(c,x,y,-8f,1f,8f,1f)}
            "MISSION" -> {poly(0f,-9f,8f,0f,0f,9f,-8f,0f,0f,-9f)}
            else -> {poly(0f,8f,-7f,-1f,-7f,-4f,-4f,-8f,4f,-8f,7f,-4f,7f,-1f,0f,8f);c.drawCircle(x,y-d(3f),d(2f),paint)}
        }
    }
    fun draw(c:Canvas,x:Float,y:Float,type:String,active:Boolean,done:Boolean,muted:Boolean,distance:String?=null) {
        val color=when {
            done -> Color.rgb(139,204,146)
            active -> Color.rgb(248,212,129)
            type=="NATURE" -> Color.rgb(147,196,155)
            type=="ART" || type=="PHOTO" -> Color.rgb(191,168,218)
            type=="FOOD" || type=="CAFE" -> Color.rgb(231,170,112)
            type=="TRANSIT" -> Color.rgb(141,201,230)
            type=="HOME" || type=="OFFICE" -> Color.rgb(224,202,159)
            type=="ARCH" -> Color.rgb(145,193,207)
            else -> Color.rgb(202,207,190)
        }
        c.save()
        c.scale(0.78f,0.78f,x,y)
        val r=d(if(active)19f else 16f)
        paint.style=Paint.Style.FILL;paint.clearShadowLayer()
        paint.color=Color.argb(if(muted)130 else 220,20,32,29)
        c.drawCircle(x,y,r,paint)
        if(active){paint.color=Color.argb(50,248,212,129);c.drawCircle(x,y,r+d(7f),paint)}
        stroke(if(muted)Color.argb(120,180,180,175) else color,if(active)2.3f else 1.2f)
        c.drawCircle(x,y,r-d(1.5f),paint)
        glyph(c,x,y,type,if(muted)Color.argb(150,200,200,195) else color)
        if(done) {
            paint.style=Paint.Style.FILL;paint.color=Color.rgb(139,204,146)
            c.drawCircle(x+d(12f),y-d(13f),d(6.5f),paint)
            stroke(Color.rgb(22,38,27),1.7f)
            line(c,x+d(12f),y-d(13f),-3f,0f,-1f,2f)
            line(c,x+d(12f),y-d(13f),-1f,2f,3f,-2f)
        }
        if(distance!=null) {
            paint.clearShadowLayer();paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            paint.textSize=d(10f);paint.textAlign=Paint.Align.CENTER
            val w=paint.measureText(distance)+d(12f)
            val top=y+r+d(3f)
            paint.color=Color.argb(195,20,30,27);paint.style=Paint.Style.FILL
            c.drawRoundRect(x-w/2,top,x+w/2,top+d(17f),d(5f),d(5f),paint)
            paint.color=if(muted)Color.LTGRAY else color
            c.drawText(distance,x,top+d(12f),paint)
        }
        c.restore()
    }
    fun edge(c:Canvas,x:Float,y:Float,angle:Double,active:Boolean) {
        val color=if(active)Color.rgb(248,212,129) else Color.rgb(160,186,170)
        c.save();c.translate(x,y);c.rotate(Math.toDegrees(angle).toFloat()+90f)
        stroke(color,1.8f)
        val path=Path().apply {moveTo(0f,-d(10f));lineTo(-d(6f),d(5f));lineTo(d(6f),d(5f));close()}
        c.drawPath(path,paint);c.restore()
    }
}
