package fr.maxence.gpsperso

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class Place(val name: String, val address: String, val lat: Double, val lon: Double)

/**
 * Services gratuits utilisés :
 * - Photon (komoot) pour la recherche d'adresses,
 * - Valhalla (serveur public FOSSGIS) pour le calcul d'itinéraire,
 * - Overpass (OpenStreetMap) pour les radars fixes.
 */
object Net {
    private const val USER_AGENT = "GPSPerso/0.1 (application personnelle Android)"
    private const val PHOTON = "https://photon.komoot.io/api/"
    private const val VALHALLA = "https://valhalla1.openstreetmap.de/route"
    /** Plusieurs serveurs Overpass publics : si l'un est saturé, on passe au suivant. */
    private val OVERPASS_SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )
    private val overpassLock = Any()

    fun request(
        url: String,
        body: String? = null,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 30_000,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = minOf(15_000, timeoutMs)
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code : ${text.take(200)}")
            return text
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Requête OpenStreetMap Overpass. Une seule à la fois (les serveurs refusent les rafales),
     * avec bascule sur un autre serveur en cas d'erreur.
     */
    fun overpass(query: String): JSONObject = synchronized(overpassLock) {
        var lastError: Exception? = null
        for (server in OVERPASS_SERVERS) {
            try {
                val text = request(server, "data=${enc(query)}", "application/x-www-form-urlencoded")
                return JSONObject(text)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("Serveurs OpenStreetMap indisponibles, réessayez dans un instant (${lastError?.message?.take(60)})")
    }

    /**
     * Petite requête Overpass pour une fiche lieu : sans attendre les grosses requêtes en cours,
     * délai court, et on tente le serveur suivant si le premier traîne.
     */
    fun overpassQuick(query: String): JSONObject {
        var lastError: Exception? = null
        for (server in OVERPASS_SERVERS) {
            try {
                return JSONObject(request(server, "data=${enc(query)}", "application/x-www-form-urlencoded", timeoutMs = 7_000))
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("OpenStreetMap indisponible (${lastError?.message?.take(60)})")
    }

    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun search(query: String, nearLat: Double?, nearLon: Double?): List<Place> {
        var url = "$PHOTON?q=${enc(query)}&lang=fr&limit=8"
        if (nearLat != null && nearLon != null) url += "&lat=$nearLat&lon=$nearLon"
        val features = JSONObject(request(url)).getJSONArray("features")
        val places = ArrayList<Place>()
        for (i in 0 until features.length()) {
            val f = features.getJSONObject(i)
            val coords = f.getJSONObject("geometry").getJSONArray("coordinates")
            val p = f.getJSONObject("properties")
            val street = listOf(p.optString("housenumber"), p.optString("street"))
                .filter { it.isNotBlank() }.joinToString(" ")
            val city = listOf(p.optString("postcode"), p.optString("city"))
                .filter { it.isNotBlank() }.joinToString(" ")
            val name = p.optString("name").ifBlank { street.ifBlank { city } }
            val address = listOf(if (name == street) "" else street, city, p.optString("country"))
                .filter { it.isNotBlank() && it != name }.joinToString(", ")
            places.add(Place(name, address, coords.getDouble(1), coords.getDouble(0)))
        }
        return places
    }

    /** Adresse la plus proche d'un point (pour nommer un POI). */
    fun reverse(lat: Double, lon: Double): String? {
        val features = JSONObject(request("${PHOTON.removeSuffix("api/")}reverse?lat=$lat&lon=$lon&lang=fr&limit=1"))
            .getJSONArray("features")
        if (features.length() == 0) return null
        val p = features.getJSONObject(0).getJSONObject("properties")
        val street = listOf(p.optString("housenumber"), p.optString("street")).filter { it.isNotBlank() }.joinToString(" ")
        return p.optString("name").ifBlank { street.ifBlank { p.optString("city") } }.ifBlank { null }
    }

    /**
     * Calcule un itinéraire en voiture. heading (cap actuel) évite de proposer un demi-tour
     * quand on recalcule en roulant.
     */
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, heading: Float?, via: List<Place> = emptyList()): Route {
        val origin = JSONObject().put("lat", fromLat).put("lon", fromLon)
        if (heading != null) origin.put("heading", heading.toInt()).put("heading_tolerance", 45)
        val locations = JSONArray().put(origin)
        via.forEach { locations.put(JSONObject().put("lat", it.lat).put("lon", it.lon)) }
        locations.put(JSONObject().put("lat", toLat).put("lon", toLon))
        val body = JSONObject()
            .put("locations", locations)
            .put("costing", "auto")
            .put("directions_options", JSONObject().put("language", "fr-FR").put("units", "kilometers"))
        val trip = JSONObject(request(VALHALLA, body.toString())).getJSONObject("trip")
        // Un trajet avec étape a plusieurs « legs » : on les met bout à bout.
        val legs = trip.getJSONArray("legs")
        val points = ArrayList<DoubleArray>()
        val maneuvers = ArrayList<Maneuver>()
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val legPoints = Geo.decodePolyline6(leg.getString("shape"))
            val offset = if (points.isEmpty()) 0 else points.size - 1
            if (points.isEmpty()) points.addAll(legPoints) else points.addAll(legPoints.drop(1))
            val maneuversJson = leg.getJSONArray("maneuvers")
            for (i in 0 until maneuversJson.length()) {
                val m = maneuversJson.getJSONObject(i)
                maneuvers.add(
                    Maneuver(
                        type = m.optInt("type"),
                        instruction = m.optString("instruction"),
                        verbalAlert = m.optString("verbal_transition_alert_instruction").ifBlank { null },
                        verbalPre = m.optString("verbal_pre_transition_instruction").ifBlank { null },
                        beginIndex = (m.optInt("begin_shape_index") + offset).coerceIn(0, (points.size - 1).coerceAtLeast(0)),
                    )
                )
            }
        }
        return Route(points, maneuvers, trip.getJSONObject("summary").optDouble("time", 0.0))
    }

    /**
     * Radars (et bornes de recharge si demandé) cartographiés dans OpenStreetMap
     * à l'intérieur d'un rectangle.
     */
    fun osmAlerts(south: Double, west: Double, north: Double, east: Double, chargers: Boolean): List<Alert> {
        val box = "($south,$west,$north,$east)"
        val parts = StringBuilder("node[\"highway\"=\"speed_camera\"]$box;")
        if (chargers) parts.append("node[\"amenity\"=\"charging_station\"]$box;")
        val query = "[out:json][timeout:25];($parts);out;"
        val json = overpass(query)
        val elements = json.getJSONArray("elements")
        val list = ArrayList<Alert>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags")
            val isCharger = tags?.optString("amenity") == "charging_station"
            val detail = if (isCharger) {
                tags?.optString("capacity")?.takeIf { it.isNotBlank() }?.let { "($it places)" }
            } else {
                tags?.optString("maxspeed")?.takeIf { it.isNotBlank() }
            }
            val description = if (isCharger) {
                listOf(tags?.optString("name"), tags?.optString("operator"), tags?.optString("network"))
                    .filter { !it.isNullOrBlank() }.distinct().joinToString(" · ").ifBlank { null }
            } else null
            list.add(
                Alert(
                    id = "osm-${e.getLong("id")}",
                    type = if (isCharger) AlertType.BORNE else AlertType.RADAR_FIXE,
                    lat = e.getDouble("lat"),
                    lon = e.getDouble("lon"),
                    createdAt = 0,
                    detail = detail,
                    source = "osm",
                    description = description,
                )
            )
        }
        return list
    }
}
