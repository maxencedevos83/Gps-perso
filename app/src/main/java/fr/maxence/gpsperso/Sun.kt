package fr.maxence.gpsperso

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Position du soleil, pour basculer la carte en mode nuit au coucher du soleil. */
object Sun {
    /** Hauteur du soleil au-dessus de l'horizon, en degrés. */
    fun elevation(lat: Double, lon: Double, millis: Long = System.currentTimeMillis()): Double {
        val rad = Math.PI / 180
        val d = millis / 86_400_000.0 - 10_957.5
        val g = (357.529 + 0.98560028 * d) * rad
        val q = 280.459 + 0.98564736 * d
        val l = (q + 1.915 * sin(g) + 0.020 * sin(2 * g)) * rad
        val e = (23.439 - 0.00000036 * d) * rad
        val ra = atan2(cos(e) * sin(l), cos(l)) / rad
        val decl = asin(sin(e) * sin(l))
        val gmst = 18.697374558 + 24.06570982441908 * d
        val lha = (gmst * 15 + lon - ra) * rad
        return asin(sin(lat * rad) * sin(decl) + cos(lat * rad) * cos(decl) * cos(lha)) / rad
    }

    /** Nuit dès que le soleil passe sous l'horizon (crépuscule compris). */
    fun isNight(lat: Double, lon: Double) = elevation(lat, lon) < -1.0
}
