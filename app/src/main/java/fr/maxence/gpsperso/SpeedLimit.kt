package fr.maxence.gpsperso

import android.location.Location
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Vitesse maximale de la route actuelle, d'après OpenStreetMap (tag maxspeed).
 * On charge d'un coup les routes à 1 km autour, puis on retrouve localement celle où l'on roule.
 */
object SpeedLimit {

    private class Way(val points: List<DoubleArray>, val limit: Int?, val forward: Int?, val backward: Int?, val oneway: Boolean)

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var ways: List<Way> = emptyList()
    private var center: Location? = null
    private var loading = false
    private var lastFailAt = 0L
    /** Dernière limite trouvée : gardée quelques secondes pour éviter le clignotement. */
    private var last: Int? = null
    private var lastFoundAt = 0L

    private const val RADIUS = 1000
    private const val RELOAD_DISTANCE = 600f

    /** Limite en km/h pour la position donnée, ou null si inconnue. Appelé sur le fil principal. */
    fun update(loc: Location): Int? {
        maybeLoad(loc)
        val found = match(loc)
        val now = System.currentTimeMillis()
        if (found != null) {
            last = found
            lastFoundAt = now
        } else if (now - lastFoundAt > 8000) {
            last = null
        }
        return last
    }

    private fun maybeLoad(loc: Location) {
        val c = center
        if (loading) return
        if (c != null && c.distanceTo(loc) < RELOAD_DISTANCE) return
        if (System.currentTimeMillis() - lastFailAt < 20_000) return
        loading = true
        val lat = loc.latitude
        val lon = loc.longitude
        io.execute {
            try {
                val q = "[out:json][timeout:10];way(around:$RADIUS,$lat,$lon)[highway~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|road)(_link)?$\"];out tags geom;"
                val json = Net.overpassQuick(q)
                ways = parse(json)
                center = Location("osm").apply { latitude = lat; longitude = lon }
            } catch (e: Exception) {
                lastFailAt = System.currentTimeMillis()
            } finally {
                loading = false
            }
        }
    }

    private fun parse(json: JSONObject): List<Way> {
        val elements = json.optJSONArray("elements") ?: return emptyList()
        val result = ArrayList<Way>()
        for (i in 0 until elements.length()) {
            val e = elements.getJSONObject(i)
            val tags = e.optJSONObject("tags") ?: continue
            val geom = e.optJSONArray("geometry") ?: continue
            val pts = (0 until geom.length()).map { geom.getJSONObject(it).let { g -> doubleArrayOf(g.getDouble("lat"), g.getDouble("lon")) } }
            if (pts.size < 2) continue
            val highway = tags.optString("highway")
            val limit = parseSpeed(tags.optString("maxspeed"))
                ?: parseSpeed(tags.optString("zone:maxspeed"))
                ?: parseSpeed(tags.optString("source:maxspeed"))
                ?: when (highway) {
                    "motorway" -> 130 // autoroute française sans tag explicite
                    "living_street" -> 20
                    else -> null
                }
            val oneway = tags.optString("oneway") == "yes" || highway == "motorway" || tags.optString("junction") == "roundabout"
            result += Way(pts, limit, parseSpeed(tags.optString("maxspeed:forward")), parseSpeed(tags.optString("maxspeed:backward")), oneway)
        }
        return result
    }

    /** "50", "50 mph", "FR:urban", "FR:zone30"... */
    private fun parseSpeed(v: String): Int? {
        if (v.isBlank()) return null
        v.trim().toIntOrNull()?.let { return it }
        Regex("^(\\d+)\\s*mph$").find(v.trim())?.let { return (it.groupValues[1].toInt() * 1.609).toInt() }
        val t = v.lowercase()
        return when {
            "zone30" in t || "zone:30" in t -> 30
            "zone20" in t -> 20
            "urban" in t -> 50
            "rural" in t -> 80
            "motorway" in t -> 130
            "trunk" in t -> 110
            "walk" in t || "living_street" in t -> 20
            else -> Regex("(\\d+)").find(t)?.value?.toIntOrNull()
        }
    }

    /** Route la plus proche (moins de 30 m) dans le sens de la marche. */
    private fun match(loc: Location): Int? {
        val list = ways
        if (list.isEmpty()) return null
        val heading = if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 2) loc.bearing.toDouble() else null
        var best: Way? = null
        var bestScore = Double.MAX_VALUE
        var bestForward = true
        for (w in list) {
            for (i in 0 until w.points.size - 1) {
                val a = w.points[i]
                val b = w.points[i + 1]
                // Filtre rapide : segment loin de la position
                if (Math.abs(a[0] - loc.latitude) > 0.01 && Math.abs(b[0] - loc.latitude) > 0.01) continue
                val (_, dist) = Geo.projectOnSegment(loc.latitude, loc.longitude, a, b)
                if (dist > 30) continue
                var score = dist
                var forward = true
                if (heading != null) {
                    val segBearing = Geo.bearing(a[0], a[1], b[0], b[1])
                    val diffFwd = Geo.angleDiff(heading, segBearing)
                    val diffBack = Geo.angleDiff(heading, (segBearing + 180) % 360)
                    forward = diffFwd <= diffBack
                    val diff = if (w.oneway) diffFwd else minOf(diffFwd, diffBack)
                    if (diff > 60) continue
                    score += diff / 3
                }
                if (score < bestScore) {
                    bestScore = score
                    best = w
                    bestForward = forward
                }
            }
        }
        val w = best ?: return null
        return (if (bestForward) w.forward else w.backward) ?: w.limit
    }
}
