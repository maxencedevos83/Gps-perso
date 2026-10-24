package fr.maxence.gpsperso

import android.annotation.SuppressLint
import android.app.Presentation
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.location.Location
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Carte affichée sur l'écran de la voiture : une MapView MapLibre dessinée dans la surface
 * fournie par Android Auto (via un écran virtuel), qui suit la position et l'itinéraire de Nav.
 */
class CarMap(private val carContext: CarContext) : SurfaceCallback, Nav.Listener {

    companion object {
        private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
    }

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var locationReady = false
    private var alertView: TextView? = null
    private var speedView: TextView? = null
    private var width = 0
    private var height = 0
    private var visible = Rect()
    private var density = 1f

    /** false quand le conducteur a déplacé la carte : bouton « recentrer ». */
    var following = true
        private set
    var onFollowingChanged: (() -> Unit)? = null

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        val surface = container.surface ?: return
        release()
        width = container.width
        height = container.height
        if (visible.isEmpty) visible = Rect(0, 0, width, height)
        density = container.dpi / 160f
        val dm = carContext.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(
            "gps-perso-auto", width, height, container.dpi, surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
        virtualDisplay = vd
        val pres = Presentation(carContext, vd.display)
        val ctx = pres.context
        MapLibre.getInstance(carContext.applicationContext)
        val root = FrameLayout(ctx)
        val options = MapLibreMapOptions.createFromAttributes(ctx)
            .textureMode(true)
            .logoEnabled(false)
            .attributionEnabled(false)
            .compassEnabled(false)
        val mv = MapView(ctx, options)
        root.addView(mv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        alertView = TextView(ctx).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            visibility = View.GONE
        }
        root.addView(alertView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        speedView = TextView(ctx).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#202124"))
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(3), Color.parseColor("#DADCE0"))
            }
        }
        root.addView(speedView, FrameLayout.LayoutParams(dp(64), dp(64)))
        layoutOverlays()

        pres.setContentView(root)
        pres.show()
        presentation = pres
        mapView = mv
        mv.onCreate(null)
        mv.onStart()
        mv.onResume()
        mv.getMapAsync { m ->
            map = m
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.uiSettings.isCompassEnabled = false
            val start = Nav.lastLocation?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(46.6, 2.4)
            m.cameraPosition = CameraPosition.Builder().target(start).zoom(if (Nav.lastLocation != null) 15.0 else 5.0).build()
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                style = s
                setupLayers(s)
                enableLocation(s)
                drawRoute()
                drawAlerts()
                applyCamera(animate = false)
            }
        }
        Nav.addListener(this)
        Nav.lastLocation?.let { updateSpeed(it) }
        Nav.currentAlert?.let { onAlert(it.first, it.second) }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = Rect(visibleArea)
        layoutOverlays()
        applyCamera(animate = true)
    }

    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(container: SurfaceContainer) = release()

    /** Déplacement de la carte au doigt (ou à la molette) en mode « déplacer ». */
    override fun onScroll(distanceX: Float, distanceY: Float) {
        val m = map ?: return
        setFollowing(false)
        m.scrollBy(-distanceX, -distanceY, 0)
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        val m = map ?: return
        val delta = Math.log(scaleFactor.toDouble()) / Math.log(2.0)
        if (following && locationReady) m.locationComponent.zoomWhileTracking(m.cameraPosition.zoom + delta, 0)
        else m.moveCamera(CameraUpdateFactory.zoomBy(delta))
    }

    fun zoom(delta: Double) {
        val m = map ?: return
        if (following && locationReady) m.locationComponent.zoomWhileTracking(m.cameraPosition.zoom + delta, 300)
        else m.animateCamera(CameraUpdateFactory.zoomBy(delta), 300)
    }

    fun recenter() {
        setFollowing(true)
        applyCamera(animate = true)
    }

    private fun setFollowing(value: Boolean) {
        if (following == value) return
        following = value
        if (!value && locationReady) map?.locationComponent?.cameraMode = CameraMode.NONE
        onFollowingChanged?.invoke()
    }

    fun release() {
        Nav.removeListener(this)
        mapView?.let {
            it.onPause()
            it.onStop()
            it.onDestroy()
        }
        mapView = null
        map = null
        style = null
        locationReady = false
        presentation?.dismiss()
        presentation = null
        virtualDisplay?.release()
        virtualDisplay = null
    }

    // ---------- Nav ----------

    override fun onLocation(loc: Location, first: Boolean) {
        if (locationReady) map?.locationComponent?.forceLocationUpdate(loc)
        updateSpeed(loc)
        if (first) applyCamera(animate = false)
    }

    override fun onRouteReady(r: Route, isReroute: Boolean) {
        drawRoute()
        if (!isReroute && !Nav.navigating) showWholeRoute(r)
    }

    override fun onRouteCleared() {
        drawRoute()
        applyCamera(animate = true)
    }

    override fun onNavigationStarted() {
        following = true
        applyCamera(animate = true)
    }

    override fun onNavigationStopped() = applyCamera(animate = true)

    override fun onAlertsChanged() = drawAlerts()

    override fun onAlert(alert: Alert?, distance: Double?) {
        val v = alertView ?: return
        if (alert == null || distance == null) {
            v.visibility = View.GONE
            return
        }
        v.text = "⚠  ${alert.title} · ${Geo.formatDistance(distance.coerceAtLeast(0.0))}"
        v.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.parseColor(alert.type.color))
        }
        v.visibility = View.VISIBLE
    }

    // ---------- Dessin ----------

    private fun dp(v: Int) = (v * density).toInt()

    private fun updateSpeed(loc: Location) {
        speedView?.text = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt().toString() else "0"
    }

    /** Pastilles d'alerte et de vitesse dans la zone laissée libre par Android Auto. */
    private fun layoutOverlays() {
        val margin = dp(12)
        alertView?.layoutParams = (alertView?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = visible.left + margin
            topMargin = visible.top + margin
        }
        speedView?.layoutParams = (speedView?.layoutParams as? FrameLayout.LayoutParams)?.apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = visible.left + margin
            topMargin = (visible.bottom - margin - dp(64)).coerceAtLeast(0)
        }
        alertView?.requestLayout()
        speedView?.requestLayout()
    }

    private fun setupLayers(s: Style) {
        s.addSource(GeoJsonSource("route-src"))
        s.addLayer(
            LineLayer("route-casing", "route-src").withProperties(
                PropertyFactory.lineColor(Color.parseColor("#0B57D0")),
                PropertyFactory.lineWidth(13f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        s.addLayer(
            LineLayer("route-line", "route-src").withProperties(
                PropertyFactory.lineColor(Color.parseColor("#4C8DF6")),
                PropertyFactory.lineWidth(9f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        s.addSource(GeoJsonSource("dest-src"))
        s.addLayer(
            CircleLayer("dest-layer", "dest-src").withProperties(
                PropertyFactory.circleRadius(10f),
                PropertyFactory.circleColor(Color.parseColor("#D93025")),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(3f),
            )
        )
        // Radars et alertes : petites pastilles colorées, visibles seulement en zoom proche.
        s.addSource(GeoJsonSource("alerts-src"))
        s.addLayer(
            CircleLayer("alerts-layer", "alerts-src").withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(2f),
            ).apply { minZoom = 11f }
        )
    }

    private fun drawRoute() {
        val s = style ?: return
        val r = Nav.route
        val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
        val routeSrc = s.getSourceAs<GeoJsonSource>("route-src")
        if (r == null || r.points.size < 2) routeSrc?.setGeoJson(empty)
        else routeSrc?.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(r.points.map { Point.fromLngLat(it[1], it[0]) })))
        val d = Nav.destination
        val destSrc = s.getSourceAs<GeoJsonSource>("dest-src")
        if (d == null) destSrc?.setGeoJson(empty) else destSrc?.setGeoJson(Feature.fromGeometry(Point.fromLngLat(d.lon, d.lat)))
    }

    private fun drawAlerts() {
        val s = style ?: return
        val features = Nav.allAlerts().map { a ->
            Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat)).apply { addStringProperty("color", a.type.color) }
        }
        s.getSourceAs<GeoJsonSource>("alerts-src")?.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun positionIconRes(): Int? = when (Nav.settings.positionIcon) {
        "voiture_rouge" -> R.drawable.pos_car_red
        "voiture_noire" -> R.drawable.pos_car_black
        "voiture_bleue" -> R.drawable.pos_car_blue
        "moto" -> R.drawable.pos_moto
        "camion" -> R.drawable.pos_truck
        else -> null
    }

    @SuppressLint("MissingPermission")
    private fun enableLocation(s: Style) {
        val m = map ?: return
        if (!Nav.hasLocationPermission()) return
        val ctx = presentation?.context ?: return
        val icon = positionIconRes()
        val opts = if (icon == null) LocationComponentOptions.builder(ctx).build() else LocationComponentOptions.builder(ctx)
            .gpsDrawable(icon)
            .foregroundDrawable(icon)
            .foregroundDrawableStale(icon)
            .backgroundDrawable(R.drawable.pos_none)
            .backgroundDrawableStale(R.drawable.pos_none)
            .bearingDrawable(R.drawable.pos_none)
            .accuracyAlpha(0.1f)
            .build()
        val lc = m.locationComponent
        lc.activateLocationComponent(
            LocationComponentActivationOptions.Builder(ctx, s)
                .useDefaultLocationEngine(false)
                .locationComponentOptions(opts)
                .build()
        )
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.GPS
        locationReady = true
        Nav.lastLocation?.let { lc.forceLocationUpdate(it) }
    }

    /** Suivi de la position : vue inclinée et rapprochée en guidage, vue du dessus sinon. */
    private fun applyCamera(animate: Boolean) {
        val m = map ?: return
        if (!following || !locationReady || Nav.lastLocation == null) return
        val lc = m.locationComponent
        val nav = Nav.navigating
        // La voiture est placée dans le bas de la zone visible pour voir la route devant.
        val top = visible.top + (if (nav) visible.height() / 3 else 0)
        val padding = doubleArrayOf(
            visible.left.toDouble(), top.toDouble(),
            (width - visible.right).coerceAtLeast(0).toDouble(), (height - visible.bottom).coerceAtLeast(0).toDouble()
        )
        lc.cameraMode = if (nav) CameraMode.TRACKING_GPS else CameraMode.TRACKING
        val duration = if (animate) 600L else 0L
        lc.paddingWhileTracking(padding, duration)
        lc.zoomWhileTracking(if (nav) 16.5 else 15.0, duration)
        lc.tiltWhileTracking(if (nav) 45.0 else 0.0, duration)
    }

    /** Aperçu du trajet complet avant le départ. */
    private fun showWholeRoute(r: Route) {
        val m = map ?: return
        if (r.points.size < 2) return
        setFollowing(false)
        val b = org.maplibre.android.geometry.LatLngBounds.Builder()
        val step = (r.points.size / 200).coerceAtLeast(1)
        r.points.filterIndexed { i, _ -> i % step == 0 || i == r.points.size - 1 }.forEach { b.include(LatLng(it[0], it[1])) }
        try {
            m.animateCamera(
                CameraUpdateFactory.newLatLngBounds(
                    b.build(), visible.left + dp(24), visible.top + dp(24),
                    (width - visible.right) + dp(24), (height - visible.bottom) + dp(24)
                )
            )
        } catch (e: Exception) {
            // trajet trop court
        }
    }
}
