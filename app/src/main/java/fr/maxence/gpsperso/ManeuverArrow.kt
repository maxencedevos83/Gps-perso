package fr.maxence.gpsperso

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Flèche de la prochaine manœuvre dessinée directement sur la carte, comme sur Google Maps :
 * un trait blanc qui suit le trajet à travers l'intersection (ou tout le rond-point) avec une pointe.
 * Partagée entre le téléphone et Android Auto.
 */
object ManeuverArrow {
    private const val SRC = "maneuver-src"
    private const val HEAD_SRC = "maneuver-head-src"
    private const val HEAD_ICON = "maneuver-head"
    /** Distance avant la manœuvre à partir de laquelle la flèche apparaît. */
    private const val SHOW_WITHIN = 1000.0
    private const val BEFORE = 35.0
    private const val AFTER = 35.0
    private const val ROUNDABOUT_MAX = 300.0

    /** Types Valhalla pour lesquels une flèche n'apporte rien (départ, arrivée, tout droit, ferry…). */
    private val SKIP = setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 25, 28, 29, 30, 31, 32, 33, 34, 35, 36)

    fun addLayers(s: Style, density: Float, scale: Float = 1f) {
        s.addImage(HEAD_ICON, headBitmap(density * scale))
        s.addSource(GeoJsonSource(SRC))
        s.addSource(GeoJsonSource(HEAD_SRC))
        s.addLayer(
            LineLayer("maneuver-casing", SRC).withProperties(
                PropertyFactory.lineColor("#14233A"),
                PropertyFactory.lineWidth(13f * scale),
                PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).apply { minZoom = 14.5f }
        )
        s.addLayer(
            LineLayer("maneuver-line", SRC).withProperties(
                PropertyFactory.lineColor("#FFFFFF"),
                PropertyFactory.lineWidth(8f * scale),
                PropertyFactory.lineCap(Property.LINE_CAP_BUTT),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            ).apply { minZoom = 14.5f }
        )
        s.addLayer(
            SymbolLayer("maneuver-head", HEAD_SRC).withProperties(
                PropertyFactory.iconImage(HEAD_ICON),
                PropertyFactory.iconRotate(org.maplibre.android.style.expressions.Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ).apply { minZoom = 14.5f }
        )
    }

    /** Met à jour la flèche d'après l'avancement du guidage (null = l'effacer). */
    fun update(s: Style?, r: Route?, p: Nav.Progress?) {
        s ?: return
        val src = s.getSourceAs<GeoJsonSource>(SRC) ?: return
        val head = s.getSourceAs<GeoJsonSource>(HEAD_SRC) ?: return
        val line = if (r != null && p != null) geometry(r, p) else null
        if (line == null || line.size < 2) {
            val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
            src.setGeoJson(empty)
            head.setGeoJson(empty)
            return
        }
        src.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it[1], it[0]) })))
        val a = line[line.size - 2]
        val b = line.last()
        val f = Feature.fromGeometry(Point.fromLngLat(b[1], b[0]))
        f.addNumberProperty("bearing", Geo.bearing(a[0], a[1], b[0], b[1]))
        head.setGeoJson(f)
    }

    private fun geometry(r: Route, p: Nav.Progress): List<DoubleArray>? {
        var m = p.maneuver ?: return null
        if (p.maneuverDistance > SHOW_WITHIN) return null
        var next = p.nextManeuver
        // Dans le rond-point, la manœuvre courante devient « sortir » : on garde la flèche de l'entrée.
        if (m.type == 27) {
            val i = r.maneuvers.indexOf(m)
            val prev = r.maneuvers.getOrNull(i - 1)
            if (prev == null || prev.type != 26) return null
            next = m
            m = prev
        }
        if (m.type in SKIP) return null
        val c = r.cumulative
        if (c.isEmpty() || m.beginIndex !in c.indices) return null
        val at = c[m.beginIndex]
        val end = if (m.type == 26 && next != null && next.beginIndex in c.indices) {
            minOf(c[next.beginIndex] + AFTER, at + ROUNDABOUT_MAX)
        } else at + AFTER
        return slice(r, at - BEFORE, end)
    }

    private fun slice(r: Route, from: Double, to: Double): List<DoubleArray> {
        val c = r.cumulative
        val a = from.coerceIn(0.0, c.last())
        val b = to.coerceIn(a, c.last())
        val out = ArrayList<DoubleArray>()
        out.add(pointAt(r, a))
        var i = firstAtOrAfter(c, a)
        while (i < c.size && c[i] < b) {
            if (c[i] > a) out.add(r.points[i])
            i++
        }
        out.add(pointAt(r, b))
        return out
    }

    private fun firstAtOrAfter(c: DoubleArray, d: Double): Int {
        var lo = 0
        var hi = c.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (c[mid] < d) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun pointAt(r: Route, d: Double): DoubleArray {
        val c = r.cumulative
        val i = firstAtOrAfter(c, d).coerceAtMost(c.size - 1)
        if (i == 0) return r.points[0]
        val span = c[i] - c[i - 1]
        val t = if (span <= 0) 0.0 else ((d - c[i - 1]) / span).coerceIn(0.0, 1.0)
        val p = r.points[i - 1]
        val q = r.points[i]
        return doubleArrayOf(p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t)
    }

    /** Pointe de flèche dirigée vers le haut, dans la moitié haute de l'image (le centre = bout du trait). */
    private fun headBitmap(density: Float): Bitmap {
        val size = (30 * density).toInt().coerceAtLeast(16)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = size.toFloat()
        val path = Path().apply {
            moveTo(w / 2f, w * 0.06f)
            lineTo(w * 0.9f, w * 0.54f)
            lineTo(w * 0.1f, w * 0.54f)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = w * 0.07f
            color = Color.parseColor("#14233A")
        }
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        c.drawPath(path, paint)
        return bmp
    }
}
