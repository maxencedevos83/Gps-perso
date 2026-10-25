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
    class RouteInfo(val points: List<DoubleArray>, val limits: IntArray, val tollCost: Double?)

    fun speedLimits(
        key: String, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, via: List<Place>,
        avoidTolls: Boolean = false, avoidHighways: Boolean = false,
    ): RouteInfo {
        val sb = StringBuilder("https://router.hereapi.com/v8/routes?transportMode=car")
        sb.append(String.format(Locale.US, "&origin=%.6f,%.6f&destination=%.6f,%.6f", fromLat, fromLon, toLat, toLon))
        via.forEach { sb.append(String.format(Locale.US, "&via=%.6f,%.6f!passThrough=true", it.lat, it.lon)) }
        val avoid = listOfNotNull(if (avoidTolls) "tollRoad" else null, if (avoidHighways) "controlledAccessHighway" else null)
        if (avoid.isNotEmpty()) sb.append("&avoid[features]=").append(avoid.joinToString(","))
        sb.append("&return=polyline,tolls&spans=speedLimit&currency=EUR&apiKey=").append(Net.enc(key))
        val route = JSONObject(Net.request(sb.toString(), timeoutMs = 15_000)).optJSONArray("routes")?.optJSONObject(0)
            ?: throw java.io.IOException("HERE : aucun itinéraire")
        val points = ArrayList<DoubleArray>()
        val limits = ArrayList<Int>()
        val sections = route.getJSONArray("sections")
        var toll = 0.0
        var tollKnown = false
        for (i in 0 until sections.length()) {
            val section = sections.getJSONObject(i)
            // Péages : pour chaque gare, le tarif du premier mode de paiement (voiture, classe 1)
            section.optJSONArray("tolls")?.let { tolls ->
                for (t in 0 until tolls.length()) {
                    val fare = tolls.getJSONObject(t).optJSONArray("fares")?.optJSONObject(0) ?: continue
                    val price = fare.optJSONObject("convertedPrice") ?: fare.optJSONObject("price") ?: continue
                    val v = price.optDouble("value", Double.NaN)
                    if (!v.isNaN()) { toll += v; tollKnown = true }
                }
            }
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
        return RouteInfo(points, limits.toIntArray(), if (tollKnown) toll else if (sections.length() > 0) 0.0 else null)
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
