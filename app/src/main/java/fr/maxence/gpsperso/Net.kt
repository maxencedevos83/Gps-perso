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
    private const val VALHALLA_TRACE = "https://valhalla1.openstreetmap.de/trace_attributes"

    /**
     * Limitation de la route où l'on roule : les dernières positions GPS sont recalées sur la carte
     * par Valhalla (rapide, ~1 s), qui renvoie la vitesse maximale OpenStreetMap de la route.
     * Renvoie null si la route n'a pas de limitation connue.
     */
    fun matchedSpeedLimit(points: List<DoubleArray>): Int? {
        val shape = JSONArray()
        points.forEach { shape.put(JSONObject().put("lat", it[0]).put("lon", it[1])) }
        val body = JSONObject()
            .put("shape", shape)
            .put("costing", "auto")
            .put("shape_match", "map_snap")
            .put("filters", JSONObject()
                .put("attributes", JSONArray(listOf("edge.speed_limit", "edge.road_class", "matched.edge_index", "matched.type")))
                .put("action", "include"))
        val json = JSONObject(request(VALHALLA_TRACE, body.toString(), timeoutMs = 10_000))
        val edges = json.optJSONArray("edges") ?: return null
        val matched = json.optJSONArray("matched_points") ?: return null
        var edgeIndex = -1
        for (i in matched.length() - 1 downTo 0) {
            val m = matched.getJSONObject(i)
            if (m.optString("type") == "matched" && m.has("edge_index")) { edgeIndex = m.getInt("edge_index"); break }
        }
        val edge = edges.optJSONObject(edgeIndex) ?: return null
        val limit = edge.optInt("speed_limit", 0)
        if (limit in 5..200) return limit
        return if (edge.optString("road_class") == "motorway") 130 else null
    }
    /** Plusieurs serveurs Overpass publics : si l'un est saturé, on passe au suivant. */
    private val OVERPASS_SERVERS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
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
    fun overpassQuick(query: String, timeoutMs: Int = 7_000): JSONObject {
        var lastError: Exception? = null
        for (server in OVERPASS_SERVERS) {
            try {
                return JSONObject(request(server, "data=${enc(query)}", "application/x-www-form-urlencoded", timeoutMs = timeoutMs))
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
    /** Options Valhalla : éviter les péages et/ou les autoroutes (0 = à éviter autant que possible). */
    var avoidTolls = false
    var avoidHighways = false

    private fun costingOptions(): JSONObject {
        val auto = JSONObject()
        if (avoidTolls) auto.put("use_tolls", 0.0)
        if (avoidHighways) auto.put("use_highways", 0.0)
        return JSONObject().put("auto", auto)
    }

    fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, heading: Float?, via: List<Place> = emptyList()): Route =
        routes(fromLat, fromLon, toLat, toLon, heading, via, 0).first()

    /** Itinéraire principal, puis jusqu'à [alternates] trajets différents (Valhalla). */
    fun routes(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, heading: Float?, via: List<Place>, alternates: Int, timeoutMs: Int = 30_000): List<Route> {
        val origin = JSONObject().put("lat", fromLat).put("lon", fromLon)
        if (heading != null) origin.put("heading", heading.toInt()).put("heading_tolerance", 45)
        val locations = JSONArray().put(origin)
        via.forEach { locations.put(JSONObject().put("lat", it.lat).put("lon", it.lon)) }
        locations.put(JSONObject().put("lat", toLat).put("lon", toLon))
        val body = JSONObject()
            .put("locations", locations)
            .put("costing", "auto")
            .put("costing_options", costingOptions())
            .put("directions_options", JSONObject().put("language", "fr-FR").put("units", "kilometers"))
        if (alternates > 0) body.put("alternates", alternates)
        val json = JSONObject(request(VALHALLA, body.toString(), timeoutMs = timeoutMs))
        val list = arrayListOf(parseTrip(json.getJSONObject("trip")))
        json.optJSONArray("alternates")?.let { alts ->
            for (i in 0 until alts.length()) alts.optJSONObject(i)?.optJSONObject("trip")?.let { list += parseTrip(it) }
        }
        return list
    }

    private fun parseTrip(trip: JSONObject): Route {
        var toll = trip.optJSONObject("summary")?.optBoolean("has_toll", false) == true
        // Un trajet avec étape a plusieurs « legs » : on les met bout à bout.
        val legs = trip.getJSONArray("legs")
        val points = ArrayList<DoubleArray>()
        val maneuvers = ArrayList<Maneuver>()
        val roadLengths = HashMap<String, Double>()
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val legPoints = Geo.decodePolyline6(leg.getString("shape"))
            val offset = if (points.isEmpty()) 0 else points.size - 1
            if (points.isEmpty()) points.addAll(legPoints) else points.addAll(legPoints.drop(1))
            val maneuversJson = leg.getJSONArray("maneuvers")
            for (i in 0 until maneuversJson.length()) {
                val m = maneuversJson.getJSONObject(i)
                if (m.optBoolean("toll")) toll = true
                m.optJSONArray("street_names")?.optString(0)?.takeIf { it.isNotBlank() }?.let {
                    roadLengths[it] = (roadLengths[it] ?: 0.0) + m.optDouble("length", 0.0)
                }
                maneuvers.add(
                    Maneuver(
                        type = m.optInt("type"),
                        instruction = m.optString("instruction"),
                        verbalAlert = m.optString("verbal_transition_alert_instruction").ifBlank { null },
                        verbalPre = m.optString("verbal_pre_transition_instruction").ifBlank { null },
                        beginIndex = (m.optInt("begin_shape_index") + offset).coerceIn(0, (points.size - 1).coerceAtLeast(0)),
                        sign = m.optJSONObject("sign")?.let { signText(it) },
                    )
                )
            }
        }
        // « par A7, N113 » : les deux routes les plus longues du trajet
        val label = roadLengths.entries.sortedByDescending { it.value }.take(2).joinToString(", ") { it.key }.ifBlank { null }
        return Route(points, maneuvers, trip.getJSONObject("summary").optDouble("time", 0.0), toll, label)
    }

    private fun signText(sign: JSONObject): String? {
        fun texts(key: String) = sign.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("text")?.ifBlank { null } } }.orEmpty()
        val exit = texts("exit_number_elements").firstOrNull()?.let { "Sortie $it" }
        val toward = (texts("exit_toward_elements") + texts("exit_branch_elements")).distinct().take(2).joinToString(", ").ifBlank { null }
        return listOfNotNull(exit, toward).joinToString(" · ").ifBlank { null }
    }

    /**
     * Voies conseillées : même trajet demandé au format OSRM, qui contient les voies aux carrefours.
     * Elles sont rattachées aux manœuvres par leur position. Facultatif : en cas d'échec, pas de voies.
     */
    fun addLanes(r: Route, fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, heading: Float?, via: List<Place>) {
        val origin = JSONObject().put("lat", fromLat).put("lon", fromLon)
        if (heading != null) origin.put("heading", heading.toInt()).put("heading_tolerance", 45)
        val locations = JSONArray().put(origin)
        via.forEach { locations.put(JSONObject().put("lat", it.lat).put("lon", it.lon)) }
        locations.put(JSONObject().put("lat", toLat).put("lon", toLon))
        val body = JSONObject()
            .put("locations", locations)
            .put("costing", "auto")
            .put("costing_options", costingOptions())
            .put("format", "osrm")
            .put("directions_options", JSONObject().put("language", "fr-FR").put("units", "kilometers"))
        val json = JSONObject(request(VALHALLA, body.toString()))
        val legs = json.getJSONArray("routes").getJSONObject(0).getJSONArray("legs")
        val found = ArrayList<Pair<DoubleArray, List<Lane>>>()
        for (l in 0 until legs.length()) {
            val steps = legs.getJSONObject(l).getJSONArray("steps")
            for (s in 0 until steps.length()) {
                val inters = steps.getJSONObject(s).optJSONArray("intersections") ?: continue
                for (i in 0 until inters.length()) {
                    val it = inters.getJSONObject(i)
                    val lanes = it.optJSONArray("lanes") ?: continue
                    val loc = it.getJSONArray("location")
                    val list = (0 until lanes.length()).map { k ->
                        val lane = lanes.getJSONObject(k)
                        val ind = lane.optJSONArray("indications")
                        Lane((0 until (ind?.length() ?: 0)).map { ind!!.getString(it) }, lane.optBoolean("valid") || lane.optBoolean("active"))
                    }
                    if (list.size >= 2 && list.any { it.valid } && list.any { !it.valid }) found += doubleArrayOf(loc.getDouble(1), loc.getDouble(0)) to list
                }
            }
        }
        for (m in r.maneuvers) {
            val p = r.points.getOrNull(m.beginIndex) ?: continue
            m.lanes = found.firstOrNull { Geo.distance(p[0], p[1], it.first[0], it.first[1]) < 15 }?.second
        }
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
