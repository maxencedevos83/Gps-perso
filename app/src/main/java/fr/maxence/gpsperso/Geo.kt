package fr.maxence.gpsperso

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val EARTH_RADIUS = 6_371_000.0
    private const val METERS_PER_DEG_LAT = 111_320.0

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Cap (0-360°) pour aller du point 1 au point 2. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun angleDiff(a: Double, b: Double): Double {
        val d = Math.abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    /** Décode une polyline Valhalla (précision 6). Retourne des paires [lat, lon]. */
    fun decodePolyline6(encoded: String): List<DoubleArray> {
        val points = ArrayList<DoubleArray>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            for (coord in 0..1) {
                var shift = 0
                var result = 0L
                var b: Int
                do {
                    b = encoded[index++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20)
                val delta = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (coord == 0) lat += delta else lon += delta
            }
            points.add(doubleArrayOf(lat / 1e6, lon / 1e6))
        }
        return points
    }

    /**
     * Projette (lat, lon) sur le segment [a, b].
     * Retourne la position t (0..1) sur le segment et la distance en mètres au segment.
     */
    fun projectOnSegment(lat: Double, lon: Double, a: DoubleArray, b: DoubleArray): Pair<Double, Double> {
        val k = cos(Math.toRadians(lat)) * METERS_PER_DEG_LAT
        val ax = (a[1] - lon) * k
        val ay = (a[0] - lat) * METERS_PER_DEG_LAT
        val bx = (b[1] - lon) * k
        val by = (b[0] - lat) * METERS_PER_DEG_LAT
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return t to hypot(ax + t * dx, ay + t * dy)
    }

    fun formatDistance(m: Double): String = when {
        m < 100 -> "${(m / 5).roundToInt() * 5} m"
        m < 1000 -> "${(m / 10).roundToInt() * 10} m"
        m < 10_000 -> String.format(java.util.Locale.FRANCE, "%.1f km", m / 1000)
        else -> "${(m / 1000).roundToInt()} km"
    }

    fun spokenDistance(m: Double): String = when {
        m < 1000 -> "${((m / 50).roundToInt() * 50).coerceAtLeast(50)} mètres"
        m < 1500 -> "1 kilomètre"
        else -> "${(m / 1000).roundToInt()} kilomètres"
    }

    fun formatDuration(seconds: Double): String {
        val totalMin = (seconds / 60).roundToInt().coerceAtLeast(1)
        val h = totalMin / 60
        val m = totalMin % 60
        return if (h > 0) String.format(java.util.Locale.FRANCE, "%d h %02d", h, m) else "$m min"
    }
}

class Maneuver(
    val type: Int,
    val instruction: String,
    val verbalAlert: String?,
    val verbalPre: String?,
    val beginIndex: Int,
    /** Panneau de sortie : « Sortie 12 · Marseille ». */
    val sign: String? = null,
) {
    /** Voies à l'approche de la manœuvre (rempli après coup, si connu). */
    @Volatile var lanes: List<Lane>? = null

    /** Flèche affichée dans le bandeau, d'après les types de manœuvre Valhalla. */
    val arrow: String
        get() = when (type) {
            9, 20, 23 -> "↗"
            10, 18 -> "→"
            11 -> "↘"
            12, 13 -> "↶"
            14 -> "↙"
            15, 19 -> "←"
            16, 21, 24 -> "↖"
            26, 27 -> "⟳"
            4, 5, 6 -> "⚑"
            else -> "↑"
        }
}

/** Une voie de circulation : flèches peintes au sol et si elle mène à la bonne direction. */
class Lane(val indications: List<String>, val valid: Boolean) {
    /** Flèche principale de la voie (celle à suivre si la voie est conseillée). */
    fun arrow(preferred: String? = null): String = laneArrow(preferred?.takeIf { it in indications } ?: indications.firstOrNull() ?: "straight")

    companion object {
        fun laneArrow(i: String) = when (i) {
            "slight right" -> "↗"
            "right" -> "→"
            "sharp right" -> "↘"
            "slight left" -> "↖"
            "left" -> "←"
            "sharp left" -> "↙"
            "uturn" -> "↶"
            else -> "↑"
        }
    }
}

class Projection(val segment: Int, val along: Double, val offRoute: Double)

class Route(
    val points: List<DoubleArray>,
    val maneuvers: List<Maneuver>,
    val totalTime: Double,
) {
    private companion object {
        const val GRID = 0.01
    }

    val cumulative = DoubleArray(points.size)

    init {
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            cumulative[i] = cumulative[i - 1] + Geo.distance(a[0], a[1], b[0], b[1])
        }
    }

    val totalDistance: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative.last()

    /**
     * Trouve la position la plus proche sur l'itinéraire. On cherche d'abord autour
     * du dernier segment connu (hint) pour ne pas sauter sur une portion parallèle.
     */
    fun project(lat: Double, lon: Double, hint: Int = 0): Projection {
        if (points.size < 2) return Projection(0, 0.0, Double.MAX_VALUE)
        val local = projectRange(lat, lon, (hint - 3).coerceAtLeast(0), (hint + 300).coerceAtMost(points.size - 2))
        if (local.offRoute < 80) return local
        val full = projectRange(lat, lon, 0, points.size - 2)
        return if (full.offRoute < local.offRoute) full else local
    }

    /** Index spatial grossier (cases de ~1 km) des segments, pour placer vite des milliers d'alertes. */
    private val grid: Map<Long, List<Int>> by lazy {
        val map = HashMap<Long, MutableList<Int>>()
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val cells = HashSet<Long>()
            // Les longs segments (autoroutes) traversent plusieurs cases : on les échantillonne.
            val steps = Math.ceil(maxOf(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])) / (GRID / 2)).toInt().coerceAtLeast(1)
            for (s in 0..steps) {
                val f = s.toDouble() / steps
                cells.add(cell(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f))
            }
            cells.forEach { map.getOrPut(it) { ArrayList() }.add(i) }
        }
        map
    }

    private fun cell(lat: Double, lon: Double): Long =
        Math.floor(lat / GRID).toLong() * 1_000_000L + Math.floor(lon / GRID).toLong()

    /** Position sur l'itinéraire d'un point situé à moins de ~maxDist mètres, sinon null. */
    fun projectNear(lat: Double, lon: Double, maxDist: Double): Projection? {
        if (points.size < 2) return null
        val cy = Math.floor(lat / GRID).toLong()
        val cx = Math.floor(lon / GRID).toLong()
        var best: Projection? = null
        for (dy in -1L..1L) for (dx in -1L..1L) {
            val segs = grid[(cy + dy) * 1_000_000L + (cx + dx)] ?: continue
            for (i in segs) {
                val (t, d) = Geo.projectOnSegment(lat, lon, points[i], points[i + 1])
                if (d <= maxDist && (best == null || d < best.offRoute)) {
                    best = Projection(i, cumulative[i] + t * (cumulative[i + 1] - cumulative[i]), d)
                }
            }
        }
        return best
    }

    private fun projectRange(lat: Double, lon: Double, from: Int, to: Int): Projection {
        var bestSeg = from
        var bestT = 0.0
        var bestDist = Double.MAX_VALUE
        for (i in from..to) {
            val (t, d) = Geo.projectOnSegment(lat, lon, points[i], points[i + 1])
            if (d < bestDist) {
                bestDist = d
                bestSeg = i
                bestT = t
            }
        }
        val segLen = cumulative[bestSeg + 1] - cumulative[bestSeg]
        return Projection(bestSeg, cumulative[bestSeg] + bestT * segLen, bestDist)
    }
}
