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
    private const val OVERPASS = "https://overpass-api.de/api/interpreter"

    private fun request(url: String, body: String? = null, contentType: String = "application/json"): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
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

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

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

    /**
     * Calcule un itinéraire en voiture. heading (cap actuel) évite de proposer un demi-tour
     * quand on recalcule en roulant.
     */
    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, heading: Float?): Route {
        val origin = JSONObject().put("lat", fromLat).put("lon", fromLon)
        if (heading != null) origin.put("heading", heading.toInt()).put("heading_tolerance", 45)
        val body = JSONObject()
            .put("locations", JSONArray().put(origin).put(JSONObject().put("lat", toLat).put("lon", toLon)))
            .put("costing", "auto")
            .put("directions_options", JSONObject().put("language", "fr-FR").put("units", "kilometers"))
        val trip = JSONObject(request(VALHALLA, body.toString())).getJSONObject("trip")
        val leg = trip.getJSONArray("legs").getJSONObject(0)
        val points = Geo.decodePolyline6(leg.getString("shape"))
        val maneuversJson = leg.getJSONArray("maneuvers")
        val maneuvers = ArrayList<Maneuver>()
        for (i in 0 until maneuversJson.length()) {
            val m = maneuversJson.getJSONObject(i)
            maneuvers.add(
                Maneuver(
                    type = m.optInt("type"),
                    instruction = m.optString("instruction"),
                    verbalAlert = m.optString("verbal_transition_alert_instruction").ifBlank { null },
                    verbalPre = m.optString("verbal_pre_transition_instruction").ifBlank { null },
                    beginIndex = m.optInt("begin_shape_index").coerceIn(0, (points.size - 1).coerceAtLeast(0)),
                )
            )
        }
        return Route(points, maneuvers, trip.getJSONObject("summary").optDouble("time", 0.0))
    }

    /** Radars fixes cartographiés dans OpenStreetMap à l'intérieur d'un rectangle. */
    fun speedCameras(south: Double, west: Double, north: Double, east: Double): List<Alert> {
        val query = "[out:json][timeout:25];node[\"highway\"=\"speed_camera\"]($south,$west,$north,$east);out;"
        val json = JSONObject(request(OVERPASS, "data=${enc(query)}", "application/x-www-form-urlencoded"))
        val elements = json.getJSONArray("elements")
        val list = ArrayList<Alert>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val maxspeed = e.optJSONObject("tags")?.optString("maxspeed")?.takeIf { it.isNotBlank() }
            list.add(
                Alert(
                    id = "osm-${e.getLong("id")}",
                    type = AlertType.RADAR_FIXE,
                    lat = e.getDouble("lat"),
                    lon = e.getDouble("lon"),
                    createdAt = 0,
                    detail = maxspeed,
                )
            )
        }
        return list
    }
}
