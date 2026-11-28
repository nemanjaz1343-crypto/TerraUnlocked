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
    // Icons use few separated strokes; everything fits inside an 11dp square.
    // No paths overlap accidentally at small map zoom levels.
    private fun glyph(c:Canvas,x:Float,y:Float,type:String,color:Int) {
        stroke(color,1.9f)
        val path=Path()
        fun poly(vararg pts:Float) {
            path.reset()
            path.moveTo(x+d(pts[0]),y+d(pts[1]))
            var i=2
            while(i<pts.size) {
                path.lineTo(x+d(pts[i]),y+d(pts[i+1]))
                i+=2
            }
            c.drawPath(path,paint)
        }
        when(type.uppercase()) {
            "NATURE" -> {
                // Two clear mountain summits with one shared valley, no crossing.
                poly(-10f,8f,-4f,-4f,0f,2f,5f,-8f,11f,8f)
            }
            "ARCH","LANDMARK" -> {
                // Architectural portal; jambs and arch are one continuous silhouette.
                path.reset()
                path.moveTo(x-d(9f),y+d(8f))
                path.lineTo(x-d(9f),y-d(1f))
                path.cubicTo(x-d(9f),y-d(11f),x+d(9f),y-d(11f),x+d(9f),y-d(1f))
                path.lineTo(x+d(9f),y+d(8f))
                c.drawPath(path,paint)
                poly(-4f,8f,-4f,0f,4f,0f,4f,8f)
            }
            "HERITAGE" -> {
                // Pediment, two columns and plinth; air gaps keep them legible.
                poly(-10f,-4f,0f,-10f,10f,-4f)
                line(c,x,y,-8f,-1f,8f,-1f)
                line(c,x,y,-6f,2f,-6f,6f)
                line(c,x,y,6f,2f,6f,6f)
                line(c,x,y,-9f,9f,9f,9f)
            }
            "ART","PHOTO" -> {
                // Picture frame with a small sun and simple landscape silhouette.
                c.drawRoundRect(RectF(x-d(10f),y-d(8f),x+d(10f),y+d(8f)),
                    d(1.2f),d(1.2f),paint)
                c.drawCircle(x-d(4.5f),y-d(3f),d(1.2f),paint)
                poly(-7f,5f,-1f,-1f,3f,3f,6f,0f,8f,3f)
            }
            "TRANSIT" -> {
                // Metro symbol is used ONLY in a blue circular marker.
                c.drawRoundRect(RectF(x-d(8f),y-d(9f),x+d(8f),y+d(5f)),
                    d(3f),d(3f),paint)
                line(c,x,y,-5f,-3f,5f,-3f)
                line(c,x,y,-5f,1f,-3f,1f)
                line(c,x,y,3f,1f,5f,1f)
                line(c,x,y,-5f,9f,-2f,5f)
                line(c,x,y,5f,9f,2f,5f)
            }
            "FOOD" -> {
                line(c,x,y,-5f,-9f,-5f,9f)
                line(c,x,y,-8f,-9f,-8f,-2f)
                line(c,x,y,-2f,-9f,-2f,-2f)
                line(c,x,y,-8f,-2f,-2f,-2f)
                line(c,x,y,5f,-9f,5f,9f)
                poly(5f,-9f,9f,-4f,9f,2f,5f,2f)
            }
            "CAFE" -> {
                poly(-9f,-4f,5f,-4f,5f,4f,1f,8f,-5f,8f,-9f,4f,-9f,-4f)
                poly(5f,-2f,9f,-2f,9f,2f,5f,4f)
            }
            "HOME" -> {
                poly(-9f,0f,0f,-8f,9f,0f)
                poly(-7f,1f,-7f,8f,7f,8f,7f,1f)
                poly(-2f,8f,-2f,3f,2f,3f,2f,8f)
            }
            "OFFICE" -> {
                c.drawRoundRect(RectF(x-d(9f),y-d(4f),x+d(9f),y+d(8f)),d(1f),d(1f),paint)
                poly(-4f,-4f,-4f,-8f,4f,-8f,4f,-4f)
                line(c,x,y,-9f,2f,9f,2f)
            }
            "MISSION" -> {
                poly(-8f,8f,-8f,-8f,3f,-8f,9f,-2f,3f,3f,-8f,3f)
            }
            else -> {
                // Saved/custom destinations: single clean survey flag.
                line(c,x,y,-6f,-9f,-6f,9f)
                poly(-6f,-9f,8f,-6f,1f,1f,-6f,0f)
            }
        }
    }

    fun draw(c:Canvas,x:Float,y:Float,type:String,active:Boolean,done:Boolean,muted:Boolean,distance:String?=null) {
        val kind=type.uppercase()
        val transit=kind=="TRANSIT"
        val anchor=kind=="HOME" || kind=="OFFICE"
        val circular=transit || anchor
        val theme=ExpeditionThemes.current
        val military=theme.id=="MILITARY"
        val gold=if(military)Color.rgb(218,181,113) else theme.gold
        val color=when {
            done -> Color.rgb(144,206,151)
            transit -> Color.rgb(126,205,235)
            active -> if(military)Color.rgb(249,216,145) else theme.light
            anchor -> if(military)Color.rgb(225,202,157) else theme.secondary
            else -> gold
        }
        val visibleColor=if(muted)Color.argb(145,200,200,195) else color
        c.save()
        c.scale(0.84f,0.84f,x,y)
        val radius=d(if(active)21f else 19.5f)
        paint.style=Paint.Style.FILL
        paint.clearShadowLayer()
        paint.color=if(military)
            (if(transit)Color.argb(242,15,32,43) else Color.argb(240,20,32,27))
            else theme.dark

        // Rounded/chamfered diamond: geometry is drawn as a rounded square
        // rotated by 45°. No software BlurMaskFilter or shadow allocations
        // per frame; the low-alpha outline creates a soft lightweight halo.
        fun badge(r:Float) {
            if(circular) {
                c.drawCircle(x,y,r,paint)
            } else {
                val half=r*0.70710678f
                c.save()
                c.rotate(45f,x,y)
                c.drawRoundRect(RectF(x-half,y-half,x+half,y+half),
                    d(4.9f),d(4.9f),paint)
                c.restore()
            }
        }
        badge(radius)
        if(active) {
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=d(5f)
            paint.color=Color.argb(50,249,216,145)
            badge(radius+d(4f))
        } else {
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=d(2.8f)
            paint.color=Color.argb(41,200,181,138)
            badge(radius+d(.8f))
        }
        stroke(visibleColor,if(active)2.0f else 1.55f)
        badge(radius-d(1.4f))

        glyph(c,x,y,type,visibleColor)
        if(done) {
            val tickX=x+d(14f)
            val tickY=y-d(15f)
            paint.style=Paint.Style.FILL
            paint.color=Color.rgb(144,206,151)
            c.drawCircle(tickX,tickY,d(6f),paint)
            stroke(Color.rgb(22,38,27),1.8f)
            line(c,tickX,tickY,-3f,0f,-1f,2f)
            line(c,tickX,tickY,-1f,2f,3f,-2f)
        }
        if(distance!=null) {
            paint.clearShadowLayer()
            paint.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            paint.textSize=d(10f)
            paint.textAlign=Paint.Align.CENTER
            val labelWidth=paint.measureText(distance)+d(12f)
            val top=y+radius+d(3.5f)
            paint.color=Color.argb(215,20,30,27)
            paint.style=Paint.Style.FILL
            c.drawRoundRect(x-labelWidth/2,top,x+labelWidth/2,top+d(17f),d(5f),d(5f),paint)
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
