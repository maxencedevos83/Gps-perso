package fr.maxence.gpsperso

import org.json.JSONObject
import java.util.Locale

/** HERE Traffic (offre gratuite, clé sur platform.here.com) : deuxième source d'incidents. */
object Here {
    fun incidents(key: String, south: Double, west: Double, north: Double, east: Double): List<Alert> {
        val bbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", west, south, east, north)
        val url = "https://data.traffic.hereapi.com/v7/incidents?in=bbox:$bbox&locationReferencing=shape&lang=fr-FR&apiKey=${Net.enc(key)}"
        val results = JSONObject(Net.request(url)).optJSONArray("results") ?: return emptyList()
        val list = ArrayList<Alert>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val d = r.optJSONObject("incidentDetails") ?: continue
            val closed = d.optBoolean("roadClosed")
            val (type, label) = when (d.optString("type")) {
                "accident" -> AlertType.ACCIDENT to "Accident"
                "construction" -> AlertType.TRAVAUX to "Travaux"
                "congestion" -> AlertType.BOUCHON to "Bouchon"
                "roadClosure" -> AlertType.ROUTE_FERMEE to "Route fermée"
                "disabledVehicle" -> AlertType.DANGER to "Véhicule arrêté"
                "roadHazard" -> AlertType.DANGER to "Danger sur la route"
                "weather" -> AlertType.DANGER to "Conditions météo"
                "laneRestriction" -> AlertType.TRAVAUX to "Voie fermée"
                "massEvent", "plannedEvent" -> AlertType.BOUCHON to "Événement"
                else -> AlertType.DANGER to "Incident"
            }.let { if (closed) AlertType.ROUTE_FERMEE to "Route fermée" else it }
            val link = r.optJSONObject("location")?.optJSONObject("shape")?.optJSONArray("links")?.optJSONObject(0)
            val p = link?.optJSONArray("points")?.optJSONObject(0) ?: continue
            val description = d.optJSONObject("description")?.optString("value")?.ifBlank { null }
                ?: d.optJSONObject("summary")?.optString("value")?.ifBlank { null }
            list += Alert(
                id = "here-" + d.optString("id", "${p.optDouble("lat")}_${p.optDouble("lng")}"),
                type = type,
                lat = p.optDouble("lat"),
                lon = p.optDouble("lng"),
                createdAt = System.currentTimeMillis(),
                detail = null,
                source = "here",
                description = listOfNotNull(label, description).joinToString("\n"),
            )
        }
        return list
    }

    /**
     * Limitations de vitesse HERE le long d'un trajet (données des constructeurs automobiles) :
     * une seule requête par itinéraire. Renvoie les points du tracé HERE et la limite (km/h,
     * 0 si inconnue) du tronçon qui part de chaque point.
     */
    fun speedLimits(key: String, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, via: List<Place>): Pair<List<DoubleArray>, IntArray> {
        val sb = StringBuilder("https://router.hereapi.com/v8/routes?transportMode=car")
        sb.append(String.format(Locale.US, "&origin=%.6f,%.6f&destination=%.6f,%.6f", fromLat, fromLon, toLat, toLon))
        via.forEach { sb.append(String.format(Locale.US, "&via=%.6f,%.6f!passThrough=true", it.lat, it.lon)) }
        sb.append("&return=polyline&spans=speedLimit&apiKey=").append(Net.enc(key))
        val route = JSONObject(Net.request(sb.toString(), timeoutMs = 15_000)).optJSONArray("routes")?.optJSONObject(0)
            ?: throw java.io.IOException("HERE : aucun itinéraire")
        val points = ArrayList<DoubleArray>()
        val limits = ArrayList<Int>()
        val sections = route.getJSONArray("sections")
        for (i in 0 until sections.length()) {
            val section = sections.getJSONObject(i)
            val pts = decodeFlexiblePolyline(section.getString("polyline"))
            val sectionLimits = IntArray(pts.size)
            val spans = section.optJSONArray("spans")
            if (spans != null) for (k in 0 until spans.length()) {
                val span = spans.getJSONObject(k)
                val start = span.optInt("offset", 0)
                val end = spans.optJSONObject(k + 1)?.optInt("offset", pts.size) ?: pts.size
                val ms = span.optDouble("speedLimit", Double.NaN)
                val kmh = if (ms.isNaN() || ms <= 0) 0 else Math.round(ms * 3.6).toInt()
                for (p in start until minOf(end, pts.size)) sectionLimits[p] = kmh
            }
            points.addAll(pts)
            sectionLimits.forEach { limits.add(it) }
        }
        return points to limits.toIntArray()
    }

    /** Décodage du format « flexible polyline » de HERE. */
    fun decodeFlexiblePolyline(encoded: String): List<DoubleArray> {
        val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var pos = 0
        fun unsigned(): Long {
            var result = 0L
            var shift = 0
            while (pos < encoded.length) {
                val value = table.indexOf(encoded[pos++]).toLong()
                if (value < 0) throw IllegalArgumentException("polyline invalide")
                result = result or ((value and 0x1FL) shl shift)
                if ((value and 0x20L) == 0L) return result
                shift += 5
            }
            return result
        }
        fun signed(): Long {
            val v = unsigned()
            return if ((v and 1L) != 0L) (v shr 1).inv() else v shr 1
        }
        unsigned() // version
        val header = unsigned()
        val precision = (header and 15L).toInt()
        val thirdDim = ((header shr 4) and 7L).toInt()
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<DoubleArray>()
        var lat = 0L
        var lng = 0L
        while (pos < encoded.length) {
            lat += signed()
            lng += signed()
            if (thirdDim != 0) signed()
            out += doubleArrayOf(lat / factor, lng / factor)
        }
        return out
    }
}
