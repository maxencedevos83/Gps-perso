package fr.maxence.gpsperso

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Météo sur le trajet (Open-Meteo, gratuit, sans clé) : pour des points tous les ~25 km,
 * prévision à l'heure où l'on y passera. Ne remonte que ce qui compte en voiture.
 */
object Weather {

    class Warning(val icon: String, val text: String, val place: String, val atMillis: Long, val distance: Double) {
        val line: String get() = "$icon $text vers ${SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(atMillis))} ($place)"
    }

    fun alongRoute(r: Route, departure: Long, places: (Double, Double) -> String): List<Warning> {
        if (r.points.size < 2) return emptyList()
        val total = r.totalDistance
        val count = (total / 25_000).toInt().coerceIn(1, 20)
        val samples = (0..count).map { i ->
            val target = total * i / count
            val idx = r.cumulative.indexOfFirst { it >= target }.let { if (it < 0) r.points.size - 1 else it }
            Triple(r.points[idx], target, departure + (r.totalTime * target / total.coerceAtLeast(1.0) * 1000).toLong())
        }
        val lats = samples.joinToString(",") { String.format(Locale.US, "%.3f", it.first[0]) }
        val lons = samples.joinToString(",") { String.format(Locale.US, "%.3f", it.first[1]) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
            "&hourly=precipitation,snowfall,temperature_2m,wind_gusts_10m,weather_code&forecast_days=2&timezone=GMT"
        val text = Net.request(url, timeoutMs = 15_000)
        val arr: JSONArray = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONArray().put(JSONObject(text))
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
        val warnings = ArrayList<Warning>()
        for (i in samples.indices) {
            val hourly = arr.optJSONObject(i)?.optJSONObject("hourly") ?: continue
            val times = hourly.getJSONArray("time")
            val at = samples[i].third
            var best = 0
            var bestDiff = Long.MAX_VALUE
            for (k in 0 until times.length()) {
                val t = fmt.parse(times.getString(k))?.time ?: continue
                val diff = Math.abs(t - at)
                if (diff < bestDiff) { bestDiff = diff; best = k }
            }
            fun v(name: String) = hourly.optJSONArray(name)?.optDouble(best) ?: Double.NaN
            val rain = v("precipitation")
            val snow = v("snowfall")
            val temp = v("temperature_2m")
            val gust = v("wind_gusts_10m")
            val code = hourly.optJSONArray("weather_code")?.optInt(best) ?: 0
            val w = when {
                snow > 0.1 -> "❄️" to "Neige"
                temp <= 1 && rain > 0.1 -> "🧊" to "Pluie verglaçante possible"
                temp <= 0 -> "🧊" to "Risque de verglas (${Math.round(temp)}°C)"
                code in 95..99 -> "⛈️" to "Orage"
                rain >= 4 -> "🌧️" to "Fortes pluies"
                code == 45 || code == 48 -> "🌫️" to "Brouillard"
                gust >= 70 -> "💨" to "Rafales à ${Math.round(gust)} km/h"
                else -> null
            } ?: continue
            // Un seul avertissement par phénomène consécutif
            if (warnings.lastOrNull()?.text == w.second) continue
            val p = samples[i].first
            warnings += Warning(w.first, w.second, places(p[0], p[1]), at, samples[i].second)
        }
        return warnings
    }
}
