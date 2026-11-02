package com.terraunlocked

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** Offline solar events. NOAA solar-declination/equation-of-time approximation.
 * All event instants are UTC; UI formats them in the phone's local timezone.
 * Sunrise and sunset use the standard -0.833 degree apparent altitude.
 */
internal object SolarCycle {
    data class Day(
        val sunrise:Instant?,
        val sunset:Instant?,
        val morningGoldenEnd:Instant?,
        val eveningGoldenStart:Instant?,
        val morningBlueStart:Instant?,
        val morningBlueEnd:Instant?,
        val eveningBlueStart:Instant?,
        val eveningBlueEnd:Instant?
    )

    data class Snapshot(
        val day:Day,
        val isDay:Boolean,
        val progress:Float,
        val nextEvent:String,
        val nextTime:Instant?,
        val durationMinutes:Long?,
        val approximate:Boolean=true
    )

    private fun event(
        date:LocalDate, latitude:Double, longitude:Double,
        altitudeDegrees:Double, rising:Boolean
    ):Instant? {
        val n=date.dayOfYear.toDouble()
        val gamma=2.0*PI/365.0*(n-1.0)
        val equationOfTime=229.18*(0.000075+0.001868*cos(gamma)
            -0.032077*sin(gamma)-0.014615*cos(2.0*gamma)-0.040849*sin(2.0*gamma))
        val declination=0.006918-0.399912*cos(gamma)+0.070257*sin(gamma)
            -0.006758*cos(2.0*gamma)+0.000907*sin(2.0*gamma)
            -0.002697*cos(3.0*gamma)+0.00148*sin(3.0*gamma)
        val phi=Math.toRadians(latitude.coerceIn(-89.99,89.99))
        val altitude=Math.toRadians(altitudeDegrees)
        val divisor=cos(phi)*cos(declination)
        if(kotlin.math.abs(divisor)<1e-10)return null
        val ratio=(sin(altitude)-sin(phi)*sin(declination))/divisor
        if(!ratio.isFinite() || ratio < -1.0 || ratio > 1.0)return null
        val hourAngle=Math.toDegrees(acos(ratio))
        val minutesUtc=720.0-4.0*longitude-equationOfTime +
            (if(rising)-4.0*hourAngle else 4.0*hourAngle)
        return date.atStartOfDay(ZoneOffset.UTC).toInstant()
            .plusMillis((minutesUtc*60000.0).roundToLong())
    }

    private fun day(date:LocalDate, lat:Double, lon:Double):Day = Day(
        sunrise=event(date,lat,lon,-0.833,true),
        sunset=event(date,lat,lon,-0.833,false),
        morningGoldenEnd=event(date,lat,lon,6.0,true),
        eveningGoldenStart=event(date,lat,lon,6.0,false),
        morningBlueStart=event(date,lat,lon,-6.0,true),
        morningBlueEnd=event(date,lat,lon,-4.0,true),
        eveningBlueStart=event(date,lat,lon,-4.0,false),
        eveningBlueEnd=event(date,lat,lon,-6.0,false)
    )

    fun snapshot(
        latitude:Double, longitude:Double,
        now:Instant=Instant.now(), zone:ZoneId=ZoneId.systemDefault()
    ):Snapshot {
        val date=now.atZone(zone).toLocalDate()
        val today=day(date,latitude,longitude)
        val yesterday=day(date.minusDays(1),latitude,longitude)
        val tomorrow=day(date.plusDays(1),latitude,longitude)
        val sunrise=today.sunrise
        val sunset=today.sunset
        val daylight=sunrise!=null && sunset!=null && !now.isBefore(sunrise) && now.isBefore(sunset)
        val (start,end)=if(daylight) {
            sunrise to sunset
        } else if(sunrise!=null && now.isBefore(sunrise)) {
            yesterday.sunset to sunrise
        } else {
            sunset to tomorrow.sunrise
        }
        val fraction=if(start!=null && end!=null && end.isAfter(start)) {
            ((now.toEpochMilli()-start.toEpochMilli()).toDouble()/
                (end.toEpochMilli()-start.toEpochMilli()).toDouble()).coerceIn(0.0,1.0).toFloat()
        } else 0f
        val events=listOf(
            "SUNRISE" to today.sunrise, "SUNSET" to today.sunset,
            "SUNRISE" to tomorrow.sunrise, "SUNSET" to tomorrow.sunset
        ).mapNotNull { (label,time)->if(time!=null && time.isAfter(now))label to time else null }
        val next=events.minByOrNull {it.second}
        val length=if(sunrise!=null && sunset!=null)
            java.time.Duration.between(sunrise,sunset).toMinutes() else null
        return Snapshot(today,daylight,fraction,next?.first ?: "SOLAR EVENT",
            next?.second,length)
    }
}
