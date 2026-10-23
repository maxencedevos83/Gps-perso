package fr.maxence.gpsperso

import org.json.JSONObject

class TrafficEta(val travelTime: Double, val delay: Double)

/**
 * Trafic en temps réel TomTom (offre gratuite, clé personnelle) :
 * tuiles de fluidité, incidents et durée de trajet tenant compte des bouchons.
 */
object TomTom {
    /** Tuiles raster : vert = fluide, orange/rouge = ralenti, bordeaux = bouché. */
    fun flowTileUrl(key: String) =
        "https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?key=$key&tileSize=256"

    private const val INCIDENT_FIELDS =
        "{incidents{type,geometry{type,coordinates},properties{id,iconCategory,magnitudeOfDelay,events{description},from,to,delay,length,roadNumbers}}}"

    /** Incidents dans un rectangle (au plus 10 000 km² par requête). */
    fun incidents(key: String, south: Double, west: Double, north: Double, east: Double): List<Alert> {
        val url = "https://api.tomtom.com/traffic/services/5/incidentDetails?key=${Net.enc(key)}" +
            "&bbox=$west,$south,$east,$north&fields=${Net.enc(INCIDENT_FIELDS)}" +
            "&language=fr-FR&timeValidityFilter=present"
        val incidents = JSONObject(Net.request(url)).optJSONArray("incidents") ?: return emptyList()
        val list = ArrayList<Alert>()
        for (i in 0 until incidents.length()) {
            val inc = incidents.getJSONObject(i)
            val props = inc.optJSONObject("properties") ?: continue
            val type = when (props.optInt("iconCategory")) {
                1 -> AlertType.ACCIDENT
                6 -> AlertType.BOUCHON
                7, 9 -> AlertType.TRAVAUX
                8 -> AlertType.ROUTE_FERMEE
                else -> AlertType.DANGER
            }
            val geometry = inc.optJSONObject("geometry") ?: continue
            val coords = geometry.optJSONArray("coordinates") ?: continue
            // Pour une ligne (bouchon, travaux), on retient le début de l'incident.
            val first = if (geometry.optString("type") == "Point") coords else coords.optJSONArray(0) ?: continue
            val events = props.optJSONArray("events")
            val description = (0 until (events?.length() ?: 0))
                .mapNotNull { events?.optJSONObject(it)?.optString("description")?.takeIf { d -> d.isNotBlank() } }
                .joinToString(", ")
            val delayMin = (props.optDouble("delay", 0.0) / 60).toInt()
            val lengthKm = props.optDouble("length", 0.0) / 1000
            val detail = when {
                type == AlertType.BOUCHON && lengthKm >= 0.5 -> String.format(java.util.Locale.FRANCE, "%.1f km", lengthKm)
                else -> null
            }
            val from = props.optString("from").takeIf { it.isNotBlank() }
            val fullDescription = listOfNotNull(
                description.ifBlank { null },
                from?.let { "depuis $it" },
                if (delayMin > 0) "+$delayMin min" else null,
            ).joinToString(" · ").ifBlank { null }
            list.add(
                Alert(
                    id = "tt-${props.optString("id", "$i")}",
                    type = type,
                    lat = first.getDouble(1),
                    lon = first.getDouble(0),
                    createdAt = System.currentTimeMillis(),
                    detail = detail,
                    source = "tomtom",
                    description = fullDescription,
                )
            )
        }
        return list
    }

    /** Durée de trajet avec le trafic actuel, et retard dû aux bouchons. */
    fun eta(key: String, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, via: List<Place> = emptyList()): TrafficEta {
        val stops = via.joinToString("") { "${it.lat},${it.lon}:" }
        val url = "https://api.tomtom.com/routing/1/calculateRoute/$fromLat,$fromLon:$stops$toLat,$toLon/json" +
            "?key=${Net.enc(key)}&traffic=true&travelMode=car&routeType=fastest&routeRepresentation=summaryOnly"
        val summary = JSONObject(Net.request(url)).getJSONArray("routes").getJSONObject(0).getJSONObject("summary")
        return TrafficEta(summary.getDouble("travelTimeInSeconds"), summary.optDouble("trafficDelayInSeconds", 0.0))
    }

    /**
     * Découpe un itinéraire en rectangles d'environ 90 km de côté pour les requêtes d'incidents.
     * fromAlong/toAlong limitent la portion couverte (en mètres depuis le départ).
     */
    fun boxesAlong(route: Route, fromAlong: Double = 0.0, toAlong: Double = Double.MAX_VALUE, maxBoxes: Int = 20): List<DoubleArray> {
        val boxes = ArrayList<DoubleArray>()
        var box: DoubleArray? = null // south, west, north, east
        for (i in route.points.indices) {
            val along = route.cumulative[i]
            if (along < fromAlong) continue
            if (along > toAlong) break
            val p = route.points[i]
            val b = box
            if (b == null) {
                box = doubleArrayOf(p[0], p[1], p[0], p[1])
                continue
            }
            val s = minOf(b[0], p[0]); val w = minOf(b[1], p[1])
            val n = maxOf(b[2], p[0]); val e = maxOf(b[3], p[1])
            if (n - s > 0.75 || e - w > 1.1) {
                boxes.add(b)
                if (boxes.size >= maxBoxes) return boxes.map { pad(it) }
                box = doubleArrayOf(p[0], p[1], p[0], p[1])
            } else {
                b[0] = s; b[1] = w; b[2] = n; b[3] = e
            }
        }
        box?.let { boxes.add(it) }
        return boxes.map { pad(it) }
    }

    private fun pad(b: DoubleArray) = doubleArrayOf(b[0] - 0.02, b[1] - 0.03, b[2] + 0.02, b[3] + 0.03)
}
