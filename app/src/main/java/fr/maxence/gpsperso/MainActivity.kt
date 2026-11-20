package fr.maxence.gpsperso

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity(), Nav.Listener {

    companion object {
        private const val STYLE_URL = Offline.STYLE
        private const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"
        private const val REFOLLOW_DELAY_MS = 7_000L
        private const val REQ_LOCATION = 1
        private const val REQ_CALENDAR = 2
        private const val REQ_IMPORT = 3
        private const val REQ_NOTIF = 4
    }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var locationReady = false

    private lateinit var topContainer: View
    private lateinit var bottomContainer: View
    private lateinit var navBarSpacer: View
    private lateinit var searchInput: EditText
    private lateinit var searchResults: ListView
    private lateinit var maneuverBanner: View
    private lateinit var maneuverArrow: TextView
    private lateinit var maneuverDistance: TextView
    private lateinit var maneuverText: TextView
    private lateinit var alertBanner: TextView
    private lateinit var speedText: TextView
    private lateinit var agendaChip: TextView
    private lateinit var routePanel: View
    private lateinit var routeTitle: TextView
    private lateinit var routeSummary: TextView
    private lateinit var routeDetails: TextView
    private lateinit var navPanel: View
    private lateinit var navEta: TextView
    private lateinit var navRemaining: TextView

    private val io = Bg.pool("ui", 3)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var alertStore: AlertStore
    private lateinit var poiStore: PoiStore
    private lateinit var recentStore: RecentStore
    private lateinit var lufopStore: LufopStore
    private var insetTop = 0
    private var insetBottom = 0

    // État du guidage : partagé avec Android Auto, il vit dans Nav.
    private val lastLocation: Location? get() = Nav.lastLocation
    private val destination: Place? get() = Nav.destination
    private val route: Route? get() = Nav.route
    private val navigating: Boolean get() = Nav.navigating
    private val segmentHint: Int get() = Nav.segmentHint
    private val etaSeconds: Double get() = Nav.etaSeconds
    private val trafficDelay: Double get() = Nav.trafficDelay

    private var searchPlaces: List<Place> = emptyList()
    /** McDonald's et Burger King connus (autour de soi et le long du trajet). */
    private var brandPlaces: Map<String, NearbyPlace> = emptyMap()
    private var brandsCenter: Location? = null
    /** Derniers résultats « à proximité » affichés sur la carte. */
    private var nearbyShown: List<NearbyPlace> = emptyList()
    private var agendaEvents: List<AgendaEvent> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Écran toujours allumé tant que l'appli est au premier plan
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        Nav.init(this)
        Nav.addListener(this)
        Voice.onCloudError = { msg ->
            val eleven = settings.voiceSource == "eleven"
            Sheet(this).title("Voix ${Voice.cloudName()} indisponible", "L'appli utilise la voix du téléphone en attendant")
                .text(if (eleven) msg else googleErrorLabel(Exception(msg)).replace("Places API (New)", "Cloud Text-to-Speech API"))
                .text("Détail : ${msg.take(200)}", "#5F6368", 12f)
                .secondary("Fermer") {}
                .show()
        }
        settings = Nav.settings
        alertStore = Nav.alertStore
        poiStore = Nav.poiStore
        recentStore = Nav.recentStore
        lufopStore = Nav.lufopStore
        bindViews()
        setupEdgeToEdge()
        updateTrafficButton()

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isLogoEnabled = false
            applyMapMargins()
            loadStyle(wantDark())
            m.addOnMapLongClickListener { latLng ->
                if (!navigating) showLongPressMenu(latLng.latitude, latLng.longitude)
                true
            }
            m.addOnMapClickListener { latLng ->
                hideSearchResults()
                handleMapTap(latLng)
            }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) lastGestureAt = System.currentTimeMillis()
            }
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(46.6, 2.4), 5.0))
        }

        requestLocationPermission()
        Bg.takeLastCrash()?.let { report -> main.postDelayed({ showCrashReport(report) }, 2500) }
        io.execute {
            importBundledRadars()
            lufopStore.all() // préchargement du cache
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (settings.hasLufop && settings.lufopLastUpdate != today) updateFromLufopApi(manual = false)
        if (settings.seenVersion != BuildConfig.VERSION_CODE) main.postDelayed({ showHelp() }, 1500)
    }

    private fun bindViews() {
        mapView = findViewById(R.id.mapView)
        topContainer = findViewById(R.id.topContainer)
        topContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGuidancePadding(500) }
        bottomContainer = findViewById(R.id.bottomContainer)
        navBarSpacer = findViewById(R.id.navBarSpacer)
        searchInput = findViewById(R.id.searchInput)
        searchResults = findViewById(R.id.searchResults)
        maneuverBanner = findViewById(R.id.maneuverBanner)
        maneuverArrow = findViewById(R.id.maneuverArrow)
        maneuverDistance = findViewById(R.id.maneuverDistance)
        maneuverText = findViewById(R.id.maneuverText)
        alertBanner = findViewById(R.id.alertBanner)
        speedText = findViewById(R.id.speedText)
        agendaChip = findViewById(R.id.agendaChip)
        routePanel = findViewById(R.id.routePanel)
        routeTitle = findViewById(R.id.routeTitle)
        routeSummary = findViewById(R.id.routeSummary)
        routeDetails = findViewById(R.id.routeDetails)
        navPanel = findViewById(R.id.navPanel)
        navEta = findViewById(R.id.navEta)
        navRemaining = findViewById(R.id.navRemaining)

        searchInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                runSearch(); true
            } else false
        }
        findViewById<View>(R.id.searchButton).setOnClickListener { runSearch() }
        findViewById<View>(R.id.btnMenu).setOnClickListener { showMainMenu() }
        searchResults.setOnItemClickListener { _, _, position, _ ->
            searchPlaces.getOrNull(position)?.let {
                hideSearchResults()
                setDestination(it)
            }
        }
        findViewById<View>(R.id.btnRecenter).setOnClickListener { recenter() }
        findViewById<View>(R.id.btnReport).setOnClickListener { showReportDialog() }
        findViewById<View>(R.id.btnHome).setOnClickListener { goHome() }
        findViewById<View>(R.id.btnHome).setOnLongClickListener { showHomeSettings(); true }
        findViewById<View>(R.id.btnRecents).setOnClickListener { showRecents() }
        findViewById<View>(R.id.btnMute).setOnClickListener { showMuteMenu() }
        updateMuteButton()
        findViewById<View>(R.id.btnStart).setOnClickListener { startNavigation() }
        routeSummary.setOnClickListener { if (Nav.routeOptions.size > 1) showRouteChoice() }
        routeDetails.setOnClickListener { if (Nav.routeOptions.size > 1) showRouteChoice() }
        findViewById<View>(R.id.btnCancel).setOnClickListener { clearRoute() }
        findViewById<View>(R.id.btnStop).setOnClickListener { stopNavigation() }
        findViewById<View>(R.id.btnWaze).setOnClickListener { saveDestination() }
    }

    // ---------- Affichage plein écran : on décale l'interface sous les barres Android ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun setupEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                insetTop = bars.top
                insetBottom = bars.bottom
            } else {
                insetTop = insets.systemWindowInsetTop
                insetBottom = insets.systemWindowInsetBottom
            }
            topContainer.setPadding(dp(12), dp(12) + insetTop, dp(12), dp(12))
            navBarSpacer.layoutParams = navBarSpacer.layoutParams.also { it.height = insetBottom }
            applyMapMargins()
            insets
        }
        updateBottomSpacer()
    }

    private fun applyMapMargins() {
        val m = map ?: return
        m.uiSettings.setCompassMargins(0, insetTop + dp(76), dp(16), 0)
        m.uiSettings.setAttributionMargins(dp(8), 0, 0, insetBottom + dp(4))
    }

    /** Sous un panneau, la barre de navigation garde le fond blanc du panneau. */
    private fun updateBottomSpacer() {
        val panelVisible = routePanel.visibility == View.VISIBLE || navPanel.visibility == View.VISIBLE
        navBarSpacer.setBackgroundColor(if (panelVisible) Color.WHITE else Color.TRANSPARENT)
    }

    // ---------- Carte ----------

    // ---------- Mode nuit ----------

    private var darkStyle: Boolean? = null
    private val nightCheck = object : Runnable {
        override fun run() {
            if (map != null && darkStyle != null && wantDark() != darkStyle) loadStyle(wantDark())
            main.postDelayed(this, 60_000)
        }
    }

    private fun wantDark(): Boolean = when (settings.nightMode) {
        "on" -> true
        "off" -> false
        else -> {
            val loc = lastLocation
            if (loc != null) Sun.isNight(loc.latitude, loc.longitude) else Sun.isNight(46.6, 2.4)
        }
    }

    /** Charge le fond de carte (clair ou nuit) puis redessine tout ce que l'appli ajoute dessus. */
    private fun loadStyle(dark: Boolean) {
        val m = map ?: return
        darkStyle = dark
        style = null
        m.setStyle(Style.Builder().fromUri(if (dark) STYLE_DARK else STYLE_URL)) { s ->
            style = s
            setupLayers(s)
            updateTrafficLayer()
            enableLocation()
            refreshAlertLayer()
            refreshPoiLayer()
            refreshBrandLayer()
            refreshNearbyLayer()
            refreshParkingLayer()
            restoreNavigationUi()
            onAltitude(Nav.altitude)
            onSpeedLimit(Nav.speedLimit)
        }
    }

    private fun setupLayers(s: Style) {
        // Autres trajets proposés, en gris sous le trajet choisi
        s.addSource(GeoJsonSource("alt-src"))
        s.addLayer(
            LineLayer("alt-line", "alt-src").withProperties(
                PropertyFactory.lineColor("#9AA0A6"),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineOpacity(0.8f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        s.addSource(GeoJsonSource("route-src"))
        s.addLayer(
            LineLayer("route-casing", "route-src").withProperties(
                PropertyFactory.lineColor("#0B57D0"),
                PropertyFactory.lineWidth(11f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        s.addLayer(
            LineLayer("route-line", "route-src").withProperties(
                PropertyFactory.lineColor("#4C8DF6"),
                PropertyFactory.lineWidth(7f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        s.addSource(GeoJsonSource("dest-src"))
        s.addLayer(
            CircleLayer("dest-layer", "dest-src").withProperties(
                PropertyFactory.circleColor("#D93025"),
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            )
        )
        s.addSource(GeoJsonSource("park-src"))
        s.addLayer(
            CircleLayer("park-layer", "park-src").withProperties(
                PropertyFactory.circleColor("#1A73E8"),
                PropertyFactory.circleRadius(12f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(3f),
            )
        )
        s.addLayer(
            SymbolLayer("park-label", "park-src").withProperties(
                PropertyFactory.textField("P"),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(15f),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            )
        )
        addPointLayers(s, "nearby", 7f)
        addAlertLayers(s)
        addPointLayers(s, "pois", 9f)
        // McDonald's / Burger King bien visibles : gros rond aux couleurs de l'enseigne
        s.addSource(GeoJsonSource("brands-src"))
        s.addLayer(
            CircleLayer("brands-layer", "brands-src").withProperties(
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleRadius(12f),
                PropertyFactory.circleStrokeColor(Expression.get("stroke")),
                PropertyFactory.circleStrokeWidth(3f),
            )
        )
        s.addLayer(
            SymbolLayer("brands-labels", "brands-src").withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(13f),
                PropertyFactory.textOffset(arrayOf(0f, 1.4f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textColor(Expression.get("stroke")),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(2f),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.iconAllowOverlap(true),
            )
        )
    }

    private fun refreshBrandLayer() {
        val src = style?.getSourceAs<GeoJsonSource>("brands-src") ?: return
        val features = if (!settings.showBrands) emptyList() else brandPlaces.values.map { b ->
            Feature.fromGeometry(Point.fromLngLat(b.lon, b.lat)).also {
                val mcdo = b.brand == "mcdo"
                it.addStringProperty("color", if (mcdo) "#FFC72C" else "#F5EBDC")
                it.addStringProperty("stroke", if (mcdo) "#DA291C" else "#D62300")
                it.addStringProperty("label", if (mcdo) "McDo" else "BK")
                it.addStringProperty("id", b.id)
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun refreshNearbyLayer() {
        val src = style?.getSourceAs<GeoJsonSource>("nearby-src") ?: return
        val features = nearbyShown.filter { it.brand == null || !settings.showBrands }.map { n ->
            Feature.fromGeometry(Point.fromLngLat(n.lon, n.lat)).also {
                it.addStringProperty("color", if (n.kind == NearbyKind.STATION) "#DB4437" else "#FF7043")
                val label = if (n.kind == NearbyKind.STATION) {
                    n.price?.let { p -> String.format(Locale.FRANCE, "%.3f €", p) } ?: "Station"
                } else n.name
                it.addStringProperty("label", label)
                it.addStringProperty("id", n.id)
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun addPointLayers(s: Style, name: String, radius: Float) {
        s.addSource(GeoJsonSource("$name-src"))
        s.addLayer(
            CircleLayer("$name-layer", "$name-src").withProperties(
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleRadius(radius),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            )
        )
        s.addLayer(
            SymbolLayer("$name-labels", "$name-src").withProperties(
                PropertyFactory.textField(Expression.get("label")),
                PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
                PropertyFactory.textSize(12f),
                PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textColor("#202124"),
                PropertyFactory.textHaloColor("#FFFFFF"),
                PropertyFactory.textHaloWidth(1.5f),
                PropertyFactory.textOptional(true),
            )
        )
    }

    private fun drawRoute(r: Route?) {
        val src = style?.getSourceAs<GeoJsonSource>("route-src") ?: return
        if (r == null || r.points.size < 2) {
            src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
            return
        }
        val line = LineString.fromLngLats(r.points.map { Point.fromLngLat(it[1], it[0]) })
        src.setGeoJson(Feature.fromGeometry(line))
    }

    private fun drawDestination(p: Place?) {
        val src = style?.getSourceAs<GeoJsonSource>("dest-src") ?: return
        if (p == null) src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)))
    }

    private fun allAlerts(): List<Alert> = Nav.allAlerts()

    private fun isAnnounced(a: Alert): Boolean = Nav.isAnnounced(a)

    /**
     * Alertes : pictogrammes (📷 radar, 🚦 feu rouge, 👮 police...) regroupés en pastilles
     * numérotées quand la carte est dézoomée, pour garder une carte lisible.
     */
    private fun addAlertLayers(s: Style) {
        AlertType.entries.forEach { t -> s.addImage("alert-${t.name}", alertBitmap(alertIcon(t), t.color)) }
        s.addSource(GeoJsonSource("alerts-src", GeoJsonOptions().withCluster(true).withClusterMaxZoom(12).withClusterRadius(45)))
        s.addLayer(
            CircleLayer("alerts-clusters", "alerts-src")
                .withFilter(Expression.has("point_count"))
                .withProperties(
                    PropertyFactory.circleColor("#5F6368"),
                    PropertyFactory.circleOpacity(0.85f),
                    PropertyFactory.circleRadius(
                        Expression.step(Expression.get("point_count"), Expression.literal(13f),
                            Expression.stop(10, 16f), Expression.stop(100, 20f), Expression.stop(1000, 25f))
                    ),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(2f),
                )
        )
        s.addLayer(
            SymbolLayer("alerts-count", "alerts-src")
                .withFilter(Expression.has("point_count"))
                .withProperties(
                    PropertyFactory.textField(Expression.toString(Expression.get("point_count_abbreviated"))),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(12f),
                    PropertyFactory.textColor("#FFFFFF"),
                    PropertyFactory.textAllowOverlap(true),
                )
        )
        s.addLayer(
            SymbolLayer("alerts-layer", "alerts-src")
                .withFilter(Expression.not(Expression.has("point_count")))
                .withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconSize(
                        Expression.interpolate(Expression.linear(), Expression.zoom(),
                            Expression.stop(10, 0.6f), Expression.stop(15, 1f))
                    ),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                )
        )
        s.addLayer(
            SymbolLayer("alerts-labels", "alerts-src")
                .withFilter(Expression.not(Expression.has("point_count")))
                .withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textOffset(arrayOf(0f, 1.7f)),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textColor("#202124"),
                    PropertyFactory.textHaloColor("#FFFFFF"),
                    PropertyFactory.textHaloWidth(1.5f),
                    PropertyFactory.textOptional(true),
                ).also { it.minZoom = 14f }
        )
    }

    /** Pictogramme rond : fond blanc, liseré de la couleur du type, icône au centre. */
    private fun alertBitmap(icon: String, color: String): android.graphics.Bitmap {
        val size = dp(30)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.density = resources.displayMetrics.densityDpi
        val c = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.parseColor(color)
        c.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = Color.WHITE
        c.drawCircle(size / 2f, size / 2f, size / 2f - dp(3), paint)
        val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * 0.5f
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val y = size / 2f - (text.descent() + text.ascent()) / 2f
        c.drawText(icon, size / 2f, y, text)
        return bmp
    }

    private val layerIo = Bg.pool("layers")
    private var alertLayerGeneration = 0

    /** Les milliers de points sont préparés en arrière-plan pour ne pas figer la carte. */
    private fun refreshAlertLayer() {
        if (style == null) return
        val generation = ++alertLayerGeneration
        layerIo.execute {
            val features = allAlerts().map { a ->
                Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat)).also {
                    it.addStringProperty("icon", "alert-${a.type.name}")
                    // Libellé court : la vitesse pour un radar, sinon rien (le pictogramme suffit)
                    it.addStringProperty("label", a.detail ?: "")
                    it.addStringProperty("id", a.id)
                }
            }
            val collection = FeatureCollection.fromFeatures(features)
            main.post {
                if (generation != alertLayerGeneration) return@post
                style?.getSourceAs<GeoJsonSource>("alerts-src")?.setGeoJson(collection)
            }
        }
    }

    private fun refreshPoiLayer() {
        val src = style?.getSourceAs<GeoJsonSource>("pois-src") ?: return
        val features = poiStore.all().map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).also {
                it.addStringProperty("color", p.category.color)
                it.addStringProperty("label", p.name)
                it.addStringProperty("id", p.id)
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Appui sur un POI ou une alerte de la carte : on affiche sa fiche. */
    /** Appui sur la carte : fiche du lieu touché (nos points ou les lieux de la carte elle-même). */
    private fun handleMapTap(latLng: LatLng): Boolean {
        val m = map ?: return false
        val point = m.projection.toScreenLocation(latLng)
        val r = dp(14).toFloat()
        val rect = RectF(point.x - r, point.y - r, point.x + r, point.y + r)
        m.queryRenderedFeatures(rect, "alerts-clusters").firstOrNull()?.let { cluster ->
            val p = cluster.geometry() as? Point
            if (p != null) {
                lastGestureAt = System.currentTimeMillis()
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude(), p.longitude()), m.cameraPosition.zoom + 2))
                return true
            }
        }
        if (m.queryRenderedFeatures(rect, "park-layer").isNotEmpty()) { showParking(); return true }
        val hit = m.queryRenderedFeatures(rect, "pois-layer", "brands-layer", "nearby-layer", "alerts-layer").firstOrNull()
        if (hit != null && hit.hasProperty("id")) {
            val id = hit.getStringProperty("id")
            (brandPlaces[id] ?: nearbyShown.firstOrNull { it.id == id })?.let { showPlaceCard(it); return true }
            poiStore.all().firstOrNull { it.id == id }?.let { showPoiActions(it); return true }
            allAlerts().firstOrNull { it.id == id }?.let { showAlertDetails(it); return true }
        }
        // Lieux dessinés par le fond de carte OpenStreetMap (stations, restos, commerces...)
        val base = m.queryRenderedFeatures(rect).firstOrNull {
            it.hasProperty("class") && it.hasProperty("name") && it.geometry() is Point
        } ?: return false
        val p = base.geometry() as Point
        val cls = base.getStringProperty("class") ?: ""
        val name = base.getStringProperty("name") ?: return false
        val brand = when {
            name.contains("mcdonald", ignoreCase = true) -> "mcdo"
            name.contains("burger king", ignoreCase = true) -> "bk"
            else -> null
        }
        val kind = when (cls) {
            "fuel" -> NearbyKind.STATION
            else -> if (brand != null) NearbyKind.FAST_FOOD_BRAND else NearbyKind.RESTAURANT
        }
        val subclass = if (base.hasProperty("subclass")) base.getStringProperty("subclass") else null
        val place = NearbyPlace(
            id = "base-${p.latitude()}-${p.longitude()}",
            kind = kind,
            name = name,
            lat = p.latitude(),
            lon = p.longitude(),
            detail = placeTypeLabel(subclass ?: cls),
            brand = brand,
            category = cls,
        )
        if (kind == NearbyKind.STATION) showStationCard(place) else showPlaceCard(place)
        return true
    }

    private fun placeTypeLabel(cls: String): String = when (cls) {
        "fuel" -> "Station-service"
        "fast_food" -> "Fast-food"
        "restaurant" -> "Restaurant"
        "cafe" -> "Café"
        "bar", "pub" -> "Bar"
        "bakery" -> "Boulangerie"
        "supermarket", "grocery", "convenience" -> "Supermarché"
        "parking" -> "Parking"
        "pharmacy" -> "Pharmacie"
        "hospital" -> "Hôpital"
        "lodging", "hotel" -> "Hôtel"
        "charging_station" -> "Borne de recharge"
        "shop" -> "Commerce"
        else -> cls.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.FRANCE) }
    }

    private fun alertIcon(t: AlertType) = when (t) {
        AlertType.RADAR_FIXE -> "📷"
        AlertType.RADAR_FEU -> "🚦"
        AlertType.RADAR_MOBILE -> "📸"
        AlertType.POLICE -> "👮"
        AlertType.ACCIDENT -> "💥"
        AlertType.DANGER -> "⚠️"
        AlertType.TRAVAUX -> "🚧"
        AlertType.ROUTE_FERMEE -> "⛔"
        AlertType.BOUCHON -> "🚗"
        AlertType.BORNE -> "🔌"
    }

    /** Couleur pâle (pastille d'icône) dérivée d'une couleur vive. */
    private fun pale(hex: String): String {
        val c = Color.parseColor(hex)
        fun mix(v: Int) = (v + (255 - v) * 0.82).toInt()
        return String.format("#%02X%02X%02X", mix(Color.red(c)), mix(Color.green(c)), mix(Color.blue(c)))
    }

    private fun distanceLabel(lat: Double, lon: Double): String? =
        lastLocation?.let { "à " + Geo.formatDistance(Geo.distance(it.latitude, it.longitude, lat, lon)) }

    private fun showAlertDetails(a: Alert) {
        val source = when (a.source) {
            "lufop" -> "Lufop"
            "osm" -> "OpenStreetMap"
            "tomtom" -> "Trafic TomTom"
            "here" -> "Trafic HERE"
            "bisonfute" -> "Bison Futé"
            else -> "Mon signalement"
        }
        val sheet = Sheet(this).title("${alertIcon(a.type)}  ${a.title}", listOfNotNull(source, distanceLabel(a.lat, a.lon)).joinToString(" · "))
        a.description?.let { sheet.text(it) }
        if (!navigating) sheet.primary("▲  Y aller") { setDestination(Place(a.title, a.description ?: "", a.lat, a.lon)) }
        if (a.source == "moi") sheet.secondary("Supprimer ce signalement") {
            alertStore.remove(a.id)
            Nav.alertsChanged()
        }
        sheet.show()
    }

    // ---------- Trafic TomTom ----------

    private fun updateTrafficButton() {
        // Le trafic se règle désormais depuis le menu ☰
    }

    private fun toggleTraffic() {
        if (!settings.hasTomTom && !settings.hasHere) {
            Sheet(this)
                .title("🚦  Trafic en temps réel")
                .text("Ajoutez une clé TomTom ou HERE gratuite dans les Réglages pour afficher le trafic, les bouchons et les incidents.")
                .primary("Ouvrir les Réglages") { showSettings() }
                .show()
            return
        }
        settings.trafficOn = !settings.trafficOn
        updateTrafficButton()
        updateTrafficLayer()
        if (settings.trafficOn) Nav.refreshTrafficNow() else Nav.clearIncidents()
        toast(if (settings.trafficOn) "Trafic affiché" else "Trafic masqué")
    }

    private fun updateTrafficLayer() {
        val s = style ?: return
        s.removeLayer("traffic-layer")
        s.removeSource("traffic-src")
        if (!settings.trafficOn || !settings.hasTomTom) return
        val tiles = TileSet("2.1.0", TomTom.flowTileUrl(settings.tomtomKey))
        s.addSource(RasterSource("traffic-src", tiles, 256))
        s.addLayerBelow(RasterLayer("traffic-layer", "traffic-src").withProperties(PropertyFactory.rasterOpacity(0.85f)), "route-casing")
    }

    private fun trafficActive() = Nav.trafficActive()

    // ---------- Localisation ----------

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasCalendarPermission() =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        val missing = ArrayList<String>()
        if (hasLocationPermission()) startLocationUpdates()
        else missing += listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (hasCalendarPermission()) refreshAgenda()
        else missing += Manifest.permission.READ_CALENDAR
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_LOCATION)
    }

    /** Notification de guidage (écran verrouillé) : demandée au premier guidage si besoin. */
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                startLocationUpdates()
                enableLocation()
            } else if (Manifest.permission.ACCESS_FINE_LOCATION in permissions) {
                toast("Sans la localisation, le GPS ne peut pas vous guider.")
            }
            if (hasCalendarPermission()) refreshAgenda()
        }
        if (requestCode == REQ_CALENDAR && hasCalendarPermission()) {
            refreshAgenda()
            showAgenda()
        }
    }

    private fun startLocationUpdates() = Nav.attach(this)

    @SuppressLint("MissingPermission")
    private fun enableLocation() {
        val m = map ?: return
        val s = style ?: return
        if (locationReady || !hasLocationPermission()) return
        val lc = m.locationComponent
        lc.activateLocationComponent(
            LocationComponentActivationOptions.Builder(this, s)
                .useDefaultLocationEngine(false)
                .locationComponentOptions(positionOptions())
                .build()
        )
        lc.isLocationComponentEnabled = true
        locationReady = true
        lc.renderMode = idleRenderMode()
        lastLocation?.let {
            lc.forceLocationUpdate(it)
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 14.0))
        }
    }

    private fun positionIconRes(): Int? = when (settings.positionIcon) {
        "voiture_rouge" -> R.drawable.pos_car_red
        "voiture_noire" -> R.drawable.pos_car_black
        "voiture_bleue" -> R.drawable.pos_car_blue
        "voiture_blanche" -> R.drawable.pos_car_white
        "moto" -> R.drawable.pos_moto
        "camion" -> R.drawable.pos_truck
        else -> null
    }

    /** Icône de position : flèche bleue d'origine, ou véhicule qui tourne avec le cap. */
    private fun positionOptions(): LocationComponentOptions {
        val icon = positionIconRes() ?: return LocationComponentOptions.builder(this).minZoomIconScale(1.0f).maxZoomIconScale(1.5f).build()
        return LocationComponentOptions.builder(this)
            .gpsDrawable(icon)
            .foregroundDrawable(icon)
            .foregroundDrawableStale(icon)
            .backgroundDrawable(R.drawable.pos_none)
            .backgroundDrawableStale(R.drawable.pos_none)
            .bearingDrawable(R.drawable.pos_none)
            .accuracyAlpha(0.1f)
            .minZoomIconScale(0.9f)
            .maxZoomIconScale(1.2f)
            .build()
    }

    /** Hors guidage : boussole pour la flèche, mode GPS (icône orientée selon le cap) pour un véhicule. */
    private fun idleRenderMode() = if (positionIconRes() != null) RenderMode.GPS else RenderMode.COMPASS

    private fun applyPositionIcon() {
        val m = map ?: return
        if (!locationReady) return
        m.locationComponent.applyStyle(positionOptions())
        m.locationComponent.renderMode = if (navigating) RenderMode.GPS else idleRenderMode()
    }

    override fun onLocation(loc: Location, first: Boolean) {
        if (locationReady) map?.locationComponent?.forceLocationUpdate(loc)
        if (first && locationReady && !navigating) {
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 14.0))
        }
        val kmh = if (loc.hasSpeed()) (loc.speed * 3.6f).toInt() else 0
        speedText.text = kmh.toString()
        // Compteur en rouge au-delà de la limite (petite marge pour l'imprécision du GPS)
        val over = Nav.speedLimit?.let { kmh > it + 3 } == true
        speedText.setTextColor(Color.parseColor(if (over) "#D93025" else "#202124"))
        maybeLoadBrandsAround(loc)
        maybeRefollow()
        applyAutoZoom()
    }

    /** Dernier déplacement de la carte au doigt (ou vue demandée sur un lieu). */
    private var lastGestureAt = 0L

    /**
     * Pendant le guidage, la carte revient d'elle-même sur la voiture quelques secondes
     * après avoir été déplacée au doigt, comme sur Google Maps.
     */
    private fun maybeRefollow() {
        val m = map ?: return
        if (!locationReady) return
        // Hors guidage, on laisse la carte libre tant qu'un trajet est affiché en aperçu.
        if (!navigating && route != null) return
        if (m.locationComponent.cameraMode == CameraMode.TRACKING_GPS) return
        if (System.currentTimeMillis() - lastGestureAt < REFOLLOW_DELAY_MS) return
        if (navigating) followVehicle() else followFree()
    }

    /** Navigation libre : la voiture reste au centre de l'écran, carte à plat. */
    private fun followFree() {
        val m = map ?: return
        if (!locationReady) return
        val lc = m.locationComponent
        lc.cameraMode = CameraMode.TRACKING_GPS
        lc.paddingWhileTracking(doubleArrayOf(0.0, 0.0, 0.0, 0.0), 600)
        lc.tiltWhileTracking(0.0, 600)
        if (m.cameraPosition.zoom < 14.0) lc.zoomWhileTracking(16.0, 1000)
    }

    /** L'appli a eu une erreur la dernière fois : on la montre pour pouvoir me l'envoyer. */
    private fun showCrashReport(report: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Problème détecté")
            .setMessage("L'appli a rencontré une erreur lors de la dernière utilisation. Tu peux copier le rapport pour l'envoyer.\n\n" + report.take(1500))
            .setPositiveButton("Copier") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Rapport GPS Perso", report))
                toast("Rapport copié")
            }
            .setNegativeButton("Fermer", null)
            .show()
    }


    private var autoZoomAt = 0L
    private var autoZoomValue = 0.0
    private var autoTiltValue = 0.0
    private var guidanceTopPadding = -1.0

    /**
     * Caméra de guidage : le véhicule est placé dans le bas de la partie de carte réellement visible
     * (sous les bandeaux de manœuvre, voies et radar), vue inclinée, zoom réglé selon la vitesse et
     * la prochaine manœuvre.
     */
    private fun followVehicle() {
        val m = map ?: return
        if (!locationReady) return
        val lc = m.locationComponent
        if (mapView.height <= 0) return
        lc.cameraMode = CameraMode.TRACKING_GPS
        guidanceTopPadding = -1.0
        updateGuidancePadding(600)
        autoZoomValue = 0.0
        autoTiltValue = 0.0
        applyAutoZoom(force = true)
    }

    /**
     * Les bandeaux du haut grandissent à l'approche d'une sortie ou d'un radar : on recalcule la place
     * de la voiture pour que la route devant elle reste toujours visible entre les bandeaux et le bas.
     */
    private fun updateGuidancePadding(duration: Long) {
        val m = map ?: return
        if (!navigating || !locationReady || m.locationComponent.cameraMode != CameraMode.TRACKING_GPS) return
        val h = mapView.height.takeIf { it > 0 } ?: return
        val bottom = (if (navPanel.visibility == View.VISIBLE) navPanel.height else 0) + insetBottom + dp(8)
        val overlayTop = (topContainer.bottom - topContainer.paddingBottom).coerceIn(0, h)
        val visibleBottom = h - bottom
        val free = (visibleBottom - overlayTop).coerceAtLeast(dp(120))
        // Voiture aux 3/4 de la zone libre : on voit la route devant, et un peu derrière
        val carY = (overlayTop + free * 0.72).coerceAtMost(visibleBottom - dp(40).toDouble())
        val top = (2 * carY - visibleBottom).coerceAtLeast(0.0)
        if (Math.abs(top - guidanceTopPadding) < dp(12)) return
        guidanceTopPadding = top
        m.locationComponent.paddingWhileTracking(doubleArrayOf(0.0, top, 0.0, bottom.toDouble()), duration)
    }

    private fun applyAutoZoom(force: Boolean = false) {
        val m = map ?: return
        if (!navigating || !locationReady || m.locationComponent.cameraMode != CameraMode.TRACKING_GPS) return
        val now = System.currentTimeMillis()
        val dist = Nav.lastProgress?.maneuverDistance ?: Double.MAX_VALUE
        // Près d'une intersection on réagit plus vite (toutes les secondes au lieu de 3 s)
        val every = if (dist < 600) 1000 else 3000
        if (!force && now - autoZoomAt < every) return
        val speed = lastLocation?.takeIf { it.hasSpeed() }?.speed ?: 0f
        val target = Geo.autoZoom(speed, dist, settings.guidanceZoomBias)
        val tilt = Geo.autoTilt(dist)
        if (tilt != autoTiltValue) {
            autoTiltValue = tilt
            m.locationComponent.tiltWhileTracking(tilt, 1200)
        }
        if (!force && Math.abs(target - autoZoomValue) < 0.3) return
        autoZoomAt = now
        autoZoomValue = target
        m.locationComponent.zoomWhileTracking(target, 1500)
    }

    private fun recenter() {
        val m = map ?: return
        val loc = lastLocation ?: run { toast("Position GPS pas encore disponible"); return }
        lastGestureAt = 0L
        if (navigating && locationReady) {
            followVehicle()
        } else if (locationReady && route == null) {
            followFree()
        } else {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(loc.latitude, loc.longitude), 15.0))
        }
    }

    // ---------- Recherche et itinéraire ----------

    private fun runSearch() {
        val q = searchInput.text.toString().trim()
        if (q.length < 2) return
        hideKeyboard()
        val loc = lastLocation
        val pois = poiStore.search(q).map { it.toPlace() }
        io.execute {
            val online = try {
                Net.search(q, loc?.latitude, loc?.longitude)
            } catch (e: Exception) {
                main.post { if (pois.isEmpty()) toast("Recherche impossible : ${e.message}") }
                emptyList()
            }
            val places = pois + online
            main.post { showSearchResults(places) }
        }
    }

    private fun showSearchResults(places: List<Place>) {
        searchPlaces = places
        if (places.isEmpty()) {
            toast("Aucun résultat")
            hideSearchResults()
            return
        }
        searchResults.adapter = object : ArrayAdapter<Place>(this, android.R.layout.simple_list_item_2, android.R.id.text1, places) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val p = places[position]
                v.findViewById<TextView>(android.R.id.text1).text = p.name
                v.findViewById<TextView>(android.R.id.text2).text = p.address
                return v
            }
        }
        searchResults.visibility = View.VISIBLE
    }

    private fun hideSearchResults() {
        searchResults.visibility = View.GONE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }

    /** Une destination est déjà choisie : on propose d'en faire une étape plutôt que de la remplacer. */
    private fun setDestination(place: Place) {
        val current = destination
        if (current != null && route != null && Geo.distance(current.lat, current.lon, place.lat, place.lon) > 50) {
            Sheet(this).title("📍  ${place.name}", "Vous allez déjà vers ${current.name}")
                .primary("➕  Ajouter comme étape") { addStop(place) }
                .secondary("Remplacer la destination") { replaceDestination(place) }
                .show()
            return
        }
        replaceDestination(place)
    }

    private fun saveDestination() {
        val d = destination ?: return
        val existing = poiStore.all().firstOrNull { Geo.distance(it.lat, it.lon, d.lat, d.lon) < 30 }
        if (existing != null) { toast("Déjà enregistré : « ${existing.name} »"); return }
        showAddPoiDialog(d.lat, d.lon, d.name.takeIf { it != "Point sur la carte" })
    }

    private fun replaceDestination(place: Place) {
        if (!Nav.setDestination(place)) { toast("Position GPS pas encore disponible"); return }
        agendaChip.visibility = View.GONE
        drawDestination(place)
        routeTitle.text = place.name
        routeSummary.text = "Calcul de l'itinéraire…"
        routeDetails.text = place.address
        routePanel.visibility = View.VISIBLE
        updateBottomSpacer()
    }

    override fun onRouteReady(r: Route, isReroute: Boolean) {
        drawRoute(r)
        drawAlternatives()
        drawDestination(destination)
        io.execute { loadBrandsForRoute(r) }
        if (navigating) return
        destination?.let { routeTitle.text = it.name }
        routePanel.visibility = View.VISIBLE
        updateBottomSpacer()
        showRouteSummary(r)
        // Aperçu du trajet : la carte arrête de suivre la voiture le temps de le montrer en entier.
        if (locationReady) map?.locationComponent?.cameraMode = CameraMode.NONE
        routePanel.post { fitRoute(r) }
    }

    override fun onRouteError(message: String, isReroute: Boolean) {
        if (isReroute) {
            toast("Recalcul impossible, nouvel essai bientôt")
        } else {
            routeSummary.text = "Itinéraire introuvable"
            routeDetails.text = message
        }
    }

    override fun onEtaChanged() {
        if (!navigating) route?.let { showRouteSummary(it) }
    }

    override fun onAlertsChanged() = refreshAlertLayer()

    override fun onMessage(text: String) = toast(text)

    override fun onAltitude(meters: Int?) {
        val v = findViewById<TextView>(R.id.altitudeText)
        if (meters == null) v.visibility = View.GONE else {
            v.text = "⛰ $meters m"
            v.visibility = View.VISIBLE
        }
    }

    override fun onSpeedLimit(limit: Int?) {
        val v = findViewById<TextView>(R.id.speedLimit)
        if (limit == null) v.visibility = View.GONE else {
            v.text = limit.toString()
            v.visibility = View.VISIBLE
        }
    }

    /** L'appli revient au premier plan (ou la voiture a lancé un trajet) : on remet l'interface à jour. */
    private fun restoreNavigationUi() {
        val r = route ?: return
        drawRoute(r)
        drawDestination(destination)
        if (navigating) onNavigationStarted() else {
            destination?.let { routeTitle.text = it.name }
            routePanel.visibility = View.VISIBLE
            updateBottomSpacer()
            showRouteSummary(r)
        }
    }

    private fun showRouteSummary(r: Route) {
        val arrival = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(System.currentTimeMillis() + (etaSeconds * 1000).toLong()))
        routeSummary.text = "${Geo.formatDuration(etaSeconds)} (${Geo.formatDistance(r.totalDistance)})"
        val traffic = when {
            !trafficActive() -> ""
            trafficDelay >= 60 -> " · dont ${Geo.formatDuration(trafficDelay)} de bouchons"
            else -> " · trafic fluide"
        }
        val weather = Nav.weatherWarnings.take(3).joinToString("") { "\n" + it.line }
        val via = Nav.stop?.let { "\nÉtape : ${it.name}" } ?: ""
        val avoid = listOfNotNull(if (settings.avoidTolls) "sans péage" else null, if (settings.avoidHighways) "sans autoroute" else null)
        val tolls = when {
            r.tollCost != null && r.tollCost!! > 0.05 -> "\n💶 Péages : " + String.format(Locale.FRANCE, "%.2f €", r.tollCost)
            r.hasToll -> "\n💶 Trajet avec péages" + if (settings.hasHere) "" else " (prix : ajoutez une clé HERE)"
            avoid.isNotEmpty() -> "\n✅ Trajet " + avoid.joinToString(", ")
            else -> ""
        }
        val more = if (Nav.routeOptions.size > 1) "\n🔀 ${Nav.routeOptions.size} trajets possibles · toucher pour choisir" else ""
        routeDetails.text = "Arrivée vers $arrival$traffic$via$tolls$weather$more"
    }

    override fun onWeather(warnings: List<Weather.Warning>) {
        if (!navigating) route?.let { showRouteSummary(it) }
        else warnings.firstOrNull()?.let { toast(it.line) }
    }

    override fun onParkingChanged() = refreshParkingLayer()

    // ---------- Choix du trajet ----------

    private var choiceSheet: Sheet? = null
    private var choiceSlot: LinearLayout? = null
    private var choiceShownFor: List<Route>? = null

    private fun drawAlternatives() {
        val src = style?.getSourceAs<GeoJsonSource>("alt-src") ?: return
        val current = route
        val alts = if (navigating) emptyList() else Nav.routeOptions.filter { it !== current }
        src.setGeoJson(FeatureCollection.fromFeatures(alts.map { r ->
            Feature.fromGeometry(LineString.fromLngLats(r.points.map { Point.fromLngLat(it[1], it[0]) }))
        }))
    }

    override fun onRouteOptions(options: List<Route>) {
        drawAlternatives()
        if (navigating) return
        if (choiceSheet != null) renderRouteChoice() else if (choiceShownFor !== options) showRouteChoice()
    }

    /** Fenêtre « Choisir un trajet » : durée, péages et bouchons de chaque trajet. */
    private fun showRouteChoice() {
        val options = Nav.routeOptions
        if (options.size < 2) return
        choiceShownFor = options
        val sheet = Sheet(this).title("🛣️  Choisir un trajet", destination?.name)
        choiceSlot = sheet.slot()
        sheet.text(
            listOfNotNull(
                if (!settings.hasHere) "Prix des péages : ajoutez une clé HERE gratuite." else null,
                if (!settings.hasTomTom || !settings.trafficOn) "Bouchons : activez le trafic TomTom." else null,
            ).joinToString("\n"), "#5F6368", 12.5f
        )
        sheet.onDismiss { choiceSheet = null; choiceSlot = null }
        choiceSheet = sheet
        renderRouteChoice()
        sheet.show()
    }

    private fun renderRouteChoice() {
        val slot = choiceSlot ?: return
        val sheet = choiceSheet ?: return
        val options = Nav.routeOptions
        slot.removeAllViews()
        val times = options.map { it.trafficTime ?: it.totalTime }
        val fastest = times.minOrNull() ?: 0.0
        options.forEachIndexed { i, r ->
            val time = times[i]
            val title = "${Geo.formatDuration(time)} · ${Geo.formatDistance(r.totalDistance)}" +
                if (time - fastest >= 60) "  (+${Geo.formatDuration(time - fastest)})" else "  ⚡"
            val tolls = when {
                r.tollCost != null && r.tollCost!! > 0.05 -> "💶 " + String.format(Locale.FRANCE, "%.2f €", r.tollCost)
                r.tollCost != null || !r.hasToll -> "💶 sans péage"
                else -> "💶 péages"
            }
            val delay = r.trafficDelay
            val traffic = when {
                delay == null -> null
                delay >= 60 -> "🔴 bouchons +${Geo.formatDuration(delay)}"
                else -> "🟢 trafic fluide"
            }
            val subtitle = listOfNotNull(r.label?.let { "par $it" }, listOfNotNull(tolls, traffic).joinToString(" · ")).joinToString("\n")
            val selected = r === route
            slot.addView(sheet.itemView(if (selected) "✓" else "${i + 1}", title, subtitle, if (selected) "#D2E3FC" else "#F1F3F4") {
                Nav.chooseRoute(r)
            })
        }
    }

    private fun fitRoute(r: Route) {
        val bounds = LatLngBounds.Builder()
        val step = (r.points.size / 200).coerceAtLeast(1)
        r.points.filterIndexed { i, _ -> i % step == 0 || i == r.points.size - 1 }
            .forEach { bounds.include(LatLng(it[0], it[1])) }
        lastLocation?.let { bounds.include(LatLng(it.latitude, it.longitude)) }
        try {
            map?.animateCamera(
                CameraUpdateFactory.newLatLngBounds(
                    bounds.build(), dp(48), topContainer.height + dp(16), dp(48), bottomContainer.height + dp(16)
                )
            )
        } catch (e: Exception) {
            // itinéraire trop court pour former un rectangle
        }
    }

    private fun clearRoute() = Nav.clearRoute()

    override fun onRouteCleared() {
        choiceShownFor = null
        style?.getSourceAs<GeoJsonSource>("alt-src")?.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        drawRoute(null)
        drawDestination(null)
        routePanel.visibility = View.GONE
        updateBottomSpacer()
        updateAgendaChip()
    }

    private fun openInWaze(lat: Double, lon: Double) {
        val uri = Uri.parse("https://waze.com/ul?ll=$lat,$lon&navigate=yes")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            toast("Waze n'est pas installé")
        }
    }

    // ---------- Guidage ----------

    private fun startNavigation() = Nav.startNavigation()

    override fun onNavigationStarted() {
        askNotificationPermission()
        choiceSheet?.dismiss()
        drawAlternatives()
        routePanel.visibility = View.GONE
        navPanel.visibility = View.VISIBLE
        maneuverBanner.visibility = View.VISIBLE
        findViewById<View>(R.id.searchBar).visibility = View.GONE
        hideSearchResults()
        updateBottomSpacer()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (map == null) return
        if (locationReady) {
            map?.locationComponent?.renderMode = RenderMode.GPS
            mapView.post { followVehicle() }
        }
        Nav.lastProgress?.let { onProgress(it) }
    }

    private fun stopNavigation() = Nav.stopNavigation()

    override fun onNavigationStopped() {
        maneuverBanner.visibility = View.GONE
        findViewById<View>(R.id.maneuverThen).visibility = View.GONE
        hideRadarPanel()
        navPanel.visibility = View.GONE
        alertBanner.visibility = View.GONE
        findViewById<View>(R.id.searchBar).visibility = View.VISIBLE
        route?.let {
            routePanel.visibility = View.VISIBLE
            showRouteSummary(it)
        }
        updateBottomSpacer()
        map?.let { m ->
            if (locationReady) {
                m.locationComponent.paddingWhileTracking(doubleArrayOf(0.0, 0.0, 0.0, 0.0), 0)
                m.locationComponent.cameraMode = CameraMode.NONE
                m.locationComponent.renderMode = idleRenderMode()
            }
            m.animateCamera(CameraUpdateFactory.tiltTo(0.0))
        }
    }

    override fun onProgress(p: Nav.Progress) {
        p.maneuver?.let { man ->
            val dist = p.maneuverDistance
            maneuverArrow.text = man.arrow
            maneuverDistance.text = Geo.formatDistance(dist)
            maneuverText.text = man.instruction
            // Couleur des panneaux français : bleu autoroute, vert route nationale
            val color = Color.parseColor(man.signColor ?: "#0F7B4B")
            maneuverBanner.background.mutate().setTint(color)
            maneuverBanner.clipToOutline = true
            val sign = findViewById<TextView>(R.id.maneuverSign)
            val badge = man.exitNumber?.let { "Sortie $it" } ?: man.targetRef
            sign.text = badge
            sign.setTextColor(color)
            sign.visibility = if (badge.isNullOrBlank()) View.GONE else View.VISIBLE
            // Vue de l'échangeur à l'approche d'une sortie, d'une bifurcation ou d'un rond-point
            val junction = findViewById<JunctionView>(R.id.junctionView)
            val junctionRange = if (man.isJunction) 2000.0 else if (man.kind == Maneuver.Kind.ROUNDABOUT) 400.0 else 0.0
            if (dist <= junctionRange) {
                junction.maneuver = man
                junction.visibility = View.VISIBLE
            } else junction.visibility = View.GONE
            // Barre qui se vide à l'approche
            val bar = findViewById<ShrinkBar>(R.id.maneuverBar)
            val barRange = if (man.isJunction) 2000.0 else 800.0
            if (dist <= barRange) {
                bar.fraction = (dist / barRange).toFloat()
                bar.visibility = View.VISIBLE
            } else bar.visibility = View.GONE
            showLanes(man, dist, color)
            showThen(man, p.nextManeuver)
        }
        val arrival = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(p.arrivalMillis))
        navEta.text = "$arrival · ${Geo.formatDuration(p.remainingTime)}"
        val traffic = if (p.trafficDelay >= 60) " · bouchons +${Geo.formatDuration(p.trafficDelay)}" else ""
        navRemaining.text = "${Geo.formatDistance(p.remaining)} restants$traffic"
    }

    /** Voies à prendre (sur fond blanc) à l'approche de la manœuvre, comme sur TomTom. */
    private fun showLanes(man: Maneuver, distance: Double, color: Int) {
        val v = findViewById<LanesView>(R.id.maneuverLanes)
        val lanes = man.lanes
        if (lanes.isNullOrEmpty() || distance > 2000) { v.visibility = View.GONE; return }
        v.accent = color
        v.setLanes(lanes, man.arrow)
        v.visibility = View.VISIBLE
    }

    /** « Puis… » : la manœuvre suivante quand elle arrive juste après. */
    private fun showThen(man: Maneuver, next: Maneuver?) {
        val v = findViewById<TextView>(R.id.maneuverThen)
        val r = route
        if (next == null || r == null) { v.visibility = View.GONE; return }
        val gap = r.cumulative.getOrNull(next.beginIndex)?.minus(r.cumulative.getOrNull(man.beginIndex) ?: 0.0) ?: Double.MAX_VALUE
        if (gap > 1000) { v.visibility = View.GONE; return }
        val what = when {
            next.type in 4..6 -> "arrivée"
            next.exitNumber != null -> "sortie ${next.exitNumber}"
            next.targetRef != null -> next.targetRef
            else -> next.instruction
        }
        v.text = "Puis  ${next.arrow}  $what dans ${Geo.formatDistance(gap)}"
        v.visibility = View.VISIBLE
    }

    override fun onAlert(alert: Alert?, distance: Double?) {
        if (alert == null || distance == null) {
            alertBanner.visibility = View.GONE
            hideRadarPanel()
            return
        }
        if (alert.type == AlertType.RADAR_FIXE || alert.type == AlertType.RADAR_FEU || Nav.isDangerZone(alert)) {
            alertBanner.visibility = View.GONE
            showRadarPanel(alert, distance)
            return
        }
        hideRadarPanel()
        alertBanner.text = "⚠  ${alert.title} dans ${Geo.formatDistance(distance.coerceAtLeast(0.0))}"
        alertBanner.setBackgroundResource(R.drawable.bg_alert)
        alertBanner.background.mutate().setTint(Color.parseColor(alert.type.color))
        alertBanner.visibility = View.VISIBLE
    }

    /** Distance à laquelle le grand bandeau radar commence (la barre part pleine de là). */
    private var radarStartDistance = 0.0
    private var radarShownId: String? = null

    /**
     * Radar fixe en approche : grand bandeau comme pour une sortie, avec la vitesse.
     * Orange si la vitesse est respectée, rouge avec un cadre autour de l'écran sinon.
     */
    private fun showRadarPanel(alert: Alert, distance: Double) {
        val panel = findViewById<View>(R.id.radarPanel)
        if (radarShownId != alert.id) {
            radarShownId = alert.id
            radarStartDistance = maxOf(distance, 200.0)
        }
        val limit = alert.detail?.let { Regex("\\d{2,3}").find(it)?.value?.toIntOrNull() } ?: Nav.speedLimit
        val speed = lastLocation?.takeIf { it.hasSpeed() }?.let { (it.speed * 3.6f).toInt() }
        val over = limit != null && speed != null && speed > limit + 2
        val dangerZone = alert.description?.contains("zone de danger", ignoreCase = true) == true
        val color = Color.parseColor(if (over) "#B91C1C" else "#C2410C")
        panel.background.mutate().setTint(color)
        panel.clipToOutline = true
        findViewById<RadarView>(R.id.radarView).apply {
            this.limit = limit
            redLight = alert.type == AlertType.RADAR_FEU
            danger = dangerZone
            // Vue de l'échangeur déjà affichée : bandeau radar compact pour laisser voir la route
            visibility = if (findViewById<View>(R.id.junctionView).visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.radarDistance).text = Geo.formatDistance(distance.coerceAtLeast(0.0))
        val what = when {
            dangerZone -> "Zone de danger"
            alert.type == AlertType.RADAR_FEU -> "Radar feu rouge"
            else -> "Radar fixe"
        }
        findViewById<TextView>(R.id.radarTitle).text = if (limit != null) "$what · limité à $limit" else what
        findViewById<ShrinkBar>(R.id.radarBar).fraction = (distance / radarStartDistance).toFloat()
        findViewById<TextView>(R.id.radarSpeed).text = if (speed != null) "$speed km/h" else "— km/h"
        val verdict = findViewById<TextView>(R.id.radarVerdict)
        when {
            limit == null || speed == null -> verdict.visibility = View.GONE
            over -> {
                verdict.visibility = View.VISIBLE
                verdict.text = "Ralentis : −${speed - limit}"
                verdict.setTextColor(color)
            }
            else -> {
                verdict.visibility = View.VISIBLE
                verdict.text = "✓ Vitesse OK"
                verdict.setTextColor(Color.parseColor("#1B7A3E"))
            }
        }
        findViewById<View>(R.id.speedFrame).visibility = if (over) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.radarIgnore).apply {
            visibility = if (dangerZone) View.VISIBLE else View.GONE
            setOnClickListener {
                Nav.ignoreAlert(alert.id)
                toast("Cette zone ne sera plus annoncée")
            }
        }
        panel.visibility = View.VISIBLE
    }

    private fun hideRadarPanel() {
        radarShownId = null
        findViewById<View>(R.id.radarPanel).visibility = View.GONE
        findViewById<View>(R.id.speedFrame).visibility = View.GONE
    }

    // ---------- Radars, bornes et signalements ----------

    /** Bases fournies avec l'appli (radars Espagne), importées une seule fois au premier lancement. */
    private fun importBundledRadars() {
        val prefs = getSharedPreferences("bundled", Context.MODE_PRIVATE)
        val files = try { assets.list("radars")?.toList().orEmpty() } catch (e: Exception) { emptyList() }
        var changed = false
        for (f in files) {
            if (prefs.getBoolean(f, false)) continue
            try {
                val items = RadarFile.parse(f, assets.open("radars/$f").use { it.readBytes() })
                if (items.isNotEmpty()) lufopStore.import(LufopStore.datasetName(f), items)
                prefs.edit().putBoolean(f, true).apply()
                changed = true
            } catch (e: Exception) {
                // on réessaiera au prochain lancement
            }
        }
        if (changed) main.post {
            Nav.alertsChanged()
        }
    }

    /** Ouvre le sélecteur de fichiers pour importer une base Lufop (CSV, GPX, KML, OV2 ou ZIP). */
    /** Ouvre le sélecteur de fichiers pour importer une base Lufop (CSV, GPX, KML, OV2, JSON ou ZIP). */
    private fun importRadarFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_IMPORT)
        } catch (e: ActivityNotFoundException) {
            toast("Aucun gestionnaire de fichiers disponible")
        }
    }

    /** Télécharge la base complète via l'API Lufop puis montre les nouveautés. */
    private fun updateFromLufopApi(manual: Boolean) {
        val key = settings.lufopKey
        if (key.isBlank()) return
        if (manual) toast("Téléchargement de la base Lufop…")
        io.execute {
            try {
                val items = LufopApi.download(key)
                val result = lufopStore.import("api-lufop", items)
                settings.lufopLastUpdate = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                main.post {
                    Nav.alertsChanged()
                    if (manual) showImportResult("API Lufop", items, result)
                    else if (result.added.isNotEmpty()) toast("Lufop : ${result.added.size} nouveautés radar (menu ☰)")
                }
            } catch (e: Exception) {
                main.post {
                    if (manual) Sheet(this).title("Lufop indisponible").text(e.message ?: e.toString()).secondary("Fermer") {}.show()
                }
            }
        }
    }

    private fun showImportResult(source: String, items: List<Alert>, result: ImportResult) {
        val byType = items.groupingBy { it.type }.eachCount().entries
            .joinToString("\n") { "${alertIcon(it.key)}  ${it.key.label} : ${it.value}" }
        val sheet = Sheet(this).title("📥  Import réussi", source).text(byType)
        if (!result.firstImport) sheet.text(
            if (result.added.isEmpty()) "Aucune nouveauté par rapport au précédent import."
            else "${result.added.size} nouveautés depuis le précédent import."
        )
        if (result.added.isNotEmpty()) sheet.primary("Voir les nouveautés") { showLufopNews() }
        sheet.secondary("Fermer") {}.show()
    }

    @Deprecated("API Activity simple, suffisante ici")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val fileName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "fichier"
        toast("Import de $fileName…")
        io.execute {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("Fichier illisible")
                val items = RadarFile.parse(fileName, bytes)
                if (items.isEmpty()) throw IllegalStateException("Aucun point reconnu dans « $fileName ». Formats acceptés : CSV, GPX, KML, OV2 ou ZIP.")
                val result = lufopStore.import(LufopStore.datasetName(fileName), items)
                main.post {
                    Nav.alertsChanged()
                    showImportResult(fileName, items, result)
                }
            } catch (e: Exception) {
                main.post {
                    Sheet(this).title("Import impossible").text(e.message ?: e.toString()).secondary("Fermer") {}.show()
                }
            }
        }
    }

    private fun showReportDialog() {
        val loc = lastLocation ?: run { toast("Position GPS pas encore disponible"); return }
        showReportDialogAt(loc.latitude, loc.longitude)
    }

    private fun showReportDialogAt(lat: Double, lon: Double) {
        val sheet = Sheet(this).title("Que voulez-vous signaler ?")
        AlertType.reportable.forEach { type ->
            sheet.item(alertIcon(type), type.label, color = pale(type.color)) {
                val alert = alertStore.add(type, lat, lon)
                Nav.markSpoken(alert.id) // pas d'annonce pour sa propre alerte
                Nav.alertsChanged()
                toast("${alert.type.label} signalé")
            }
        }
        sheet.show()
    }

    // ---------- POIs ----------

    private fun showLongPressMenu(lat: Double, lon: Double) {
        val sheet = Sheet(this).title("Point sélectionné", listOfNotNull(
            String.format(Locale.FRANCE, "%.5f, %.5f", lat, lon), distanceLabel(lat, lon)
        ).joinToString(" · "))
        sheet.primary("▲  Y aller") {
            setDestination(Place("Point sur la carte", String.format(Locale.FRANCE, "%.5f, %.5f", lat, lon), lat, lon))
        }
        sheet.item("⭐", "Ajouter un POI ici", color = "#FEF7E0") { showAddPoiDialog(lat, lon) }
        sheet.item("⚠️", "Signaler ici", color = "#FCE8E6") { showReportDialogAt(lat, lon) }
        sheet.item("🧭", "Ouvrir dans Waze", color = "#E8F0FE") { openInWaze(lat, lon) }
        sheet.show()
    }

    private fun showAddPoiDialog(lat: Double, lon: Double, suggestedName: String? = null) {
        val sheet = Sheet(this).title("⭐  Enregistrer ce lieu", distanceLabel(lat, lon))
        val name = sheet.input("Nom du lieu", suggestedName.orEmpty())
        // Nom proposé d'après l'adresse, modifiable
        if (suggestedName.isNullOrBlank()) io.execute {
            val suggestion = try { Net.reverse(lat, lon) } catch (e: Exception) { null }
            if (suggestion != null) main.post { if (name.text.isEmpty()) name.setText(suggestion) }
        }
        sheet.sectionTitle("Catégorie (touchez pour enregistrer)")
        PoiCategory.entries.forEach { cat ->
            sheet.item(poiIcon(cat), cat.label, color = pale(cat.color)) {
                val label = name.text.toString().trim().ifBlank { cat.label }
                poiStore.add(label, cat, lat, lon)
                refreshPoiLayer()
                toast("POI « $label » ajouté")
            }
        }
        sheet.show()
    }

    private fun poiIcon(c: PoiCategory) = when (c) {
        PoiCategory.FAVORI -> "⭐"
        PoiCategory.MAISON -> "🏠"
        PoiCategory.TRAVAIL -> "💼"
        PoiCategory.STATION -> "⛽"
        PoiCategory.RECHARGE -> "🔌"
        PoiCategory.RESTAURANT -> "🍽️"
        PoiCategory.PARKING -> "🅿️"
        PoiCategory.AUTRE -> "📍"
    }

    private fun showPoiList() {
        val loc = lastLocation
        val pois = poiStore.all().sortedBy { p -> loc?.let { Geo.distance(it.latitude, it.longitude, p.lat, p.lon) } ?: 0.0 }
        val sheet = Sheet(this).title("Mes POIs", if (pois.isEmpty()) null else "${pois.size} lieux enregistrés")
        if (pois.isEmpty()) {
            sheet.text("Aucun POI pour l'instant. Faites un appui long sur la carte, ou ajoutez votre position actuelle.")
            sheet.primary("Ajouter ma position") { loc?.let { showAddPoiDialog(it.latitude, it.longitude) } }
        }
        pois.forEach { p ->
            sheet.item(poiIcon(p.category), p.name, listOfNotNull(p.category.label, distanceLabel(p.lat, p.lon)).joinToString(" · "), pale(p.category.color)) {
                showPoiActions(p)
            }
        }
        sheet.show()
    }

    private fun showPoiActions(p: Poi) {
        val sheet = Sheet(this).title("${poiIcon(p.category)}  ${p.name}", listOfNotNull(p.category.label, distanceLabel(p.lat, p.lon)).joinToString(" · "))
        if (navigating) {
            sheet.primary("Faire un arrêt ici") { addStop(p.toPlace()) }
        } else {
            sheet.primary("▲  Y aller") { setDestination(p.toPlace()) }
        }
        sheet.secondary("Voir sur la carte") { showOnMap(p.lat, p.lon) }
        sheet.secondary("Supprimer") {
            poiStore.remove(p.id)
            refreshPoiLayer()
            toast("POI supprimé")
        }
        sheet.show()
    }

    private fun showOnMap(lat: Double, lon: Double) {
        if (navigating && locationReady) map?.locationComponent?.cameraMode = CameraMode.NONE
        lastGestureAt = System.currentTimeMillis() + 12_000 // on laisse le temps de regarder
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 16.0))
    }

    // ---------- Agenda ----------

    private fun refreshAgenda() {
        if (!hasCalendarPermission()) return
        io.execute {
            val events = try { Agenda.upcoming(this) } catch (e: Exception) { emptyList() }
            main.post {
                agendaEvents = events
                updateAgendaChip()
            }
        }
    }

    /** Raccourci vers le prochain rendez-vous avec adresse (dans les 12 heures). */
    private fun updateAgendaChip() {
        val next = agendaEvents.firstOrNull {
            !it.allDay && it.begin > System.currentTimeMillis() - 30 * 60_000 &&
                it.begin < System.currentTimeMillis() + 12 * 60 * 60_000
        }
        if (next == null || navigating || route != null) {
            agendaChip.visibility = View.GONE
            return
        }
        agendaChip.text = "📅 ${next.whenLabel()} · ${next.title}  ›  Y aller"
        agendaChip.setOnClickListener { goToAgendaEvent(next) }
        agendaChip.visibility = View.VISIBLE
    }

    private fun showAgenda() {
        if (!hasCalendarPermission()) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQ_CALENDAR)
            return
        }
        val events = try { Agenda.upcoming(this) } catch (e: Exception) { emptyList() }
        agendaEvents = events
        val sheet = Sheet(this).title("📅  Mon agenda", "Rendez-vous avec un lieu, 7 prochains jours")
        if (events.isEmpty()) {
            sheet.text("Aucun rendez-vous avec une adresse.\n\nAstuce : remplissez le champ « Lieu » de vos rendez-vous dans Google Agenda.")
        }
        events.forEach { e ->
            sheet.item("📅", "${e.whenLabel()} · ${e.title}", e.location, "#E8F0FE") { goToAgendaEvent(e) }
        }
        sheet.show()
    }

    private fun goToAgendaEvent(e: AgendaEvent) {
        val loc = lastLocation
        toast("Recherche de « ${e.location} »…")
        io.execute {
            val place = try { Net.search(e.location, loc?.latitude, loc?.longitude).firstOrNull() } catch (ex: Exception) { null }
            main.post {
                if (place == null) {
                    toast("Adresse introuvable : ${e.location}")
                    searchInput.setText(e.location)
                } else {
                    agendaChip.visibility = View.GONE
                    setDestination(Place(e.title, "${e.whenLabel()} · ${e.location}", place.lat, place.lon))
                }
            }
        }
    }

    // ---------- McDonald's, Burger King, restaurants, stations ----------

    private fun maybeLoadBrandsAround(loc: Location) {
        if (!settings.showBrands) return
        val center = brandsCenter
        if (center != null && center.distanceTo(loc) < 15_000) return
        brandsCenter = loc
        val d = 0.2
        io.execute {
            try {
                val found = Nearby.brands(loc.latitude - d, loc.longitude - d * 1.5, loc.latitude + d, loc.longitude + d * 1.5)
                main.post { mergeBrands(found) }
            } catch (e: Exception) {
                main.post { brandsCenter = null }
            }
        }
    }

    /** Appelé hors du fil principal : toutes les enseignes dans le rectangle du trajet. */
    private fun loadBrandsForRoute(r: Route) {
        if (!settings.showBrands || r.points.isEmpty()) return
        try {
            val found = Nearby.brands(
                r.points.minOf { it[0] } - 0.03, r.points.minOf { it[1] } - 0.045,
                r.points.maxOf { it[0] } + 0.03, r.points.maxOf { it[1] } + 0.045,
            )
            // On ne garde que celles proches du trajet (moins de 3 km)
            val near = found.filter { r.projectNear(it.lat, it.lon, 3000.0) != null }
            main.post { mergeBrands(near) }
        } catch (e: Exception) {
            // pas bloquant
        }
    }

    private fun mergeBrands(found: List<NearbyPlace>) {
        brandPlaces = brandPlaces + found.associateBy { it.id }
        refreshBrandLayer()
    }

    private fun showFoodMenu() {
        Sheet(this).title("Manger")
            .item("🍟", "McDonald's et Burger King", if (navigating) "Sur mon trajet" else "Les plus proches", "#FFF3C4") { showBrands() }
            .item("🍽️", "Tous les restaurants", if (navigating) "Sur mon trajet" else "Autour de moi", "#FDE7DD") { showRestaurants() }
            .show()
    }

    private fun searchPoints(aheadKm: List<Int>): List<DoubleArray> {
        val loc = lastLocation ?: return emptyList()
        val r = route
        if (!navigating || r == null) return listOf(doubleArrayOf(loc.latitude, loc.longitude))
        val along = r.project(loc.latitude, loc.longitude, segmentHint).along
        return aheadKm.mapNotNull { km ->
            val target = along + km * 1000
            if (target > r.totalDistance) return@mapNotNull null
            val i = r.cumulative.indexOfFirst { it >= target }.takeIf { it >= 0 } ?: return@mapNotNull null
            r.points[i]
        }.ifEmpty { listOf(doubleArrayOf(loc.latitude, loc.longitude)) }
    }

    private fun showBrands() {
        val loc = lastLocation ?: run { toast("Position GPS pas encore disponible"); return }
        val known = brandPlaces.values.toList()
        if (known.isNotEmpty()) {
            showNearbyList("McDonald's et Burger King", known)
            return
        }
        toast("Recherche des McDo et Burger King…")
        io.execute {
            try {
                val d = 0.2
                val found = Nearby.brands(loc.latitude - d, loc.longitude - d * 1.5, loc.latitude + d, loc.longitude + d * 1.5)
                main.post {
                    mergeBrands(found)
                    showNearbyList("McDonald's et Burger King", found)
                }
            } catch (e: Exception) {
                main.post { toast("Recherche impossible : ${e.message}") }
            }
        }
    }

    private fun showRestaurants() {
        val points = searchPoints(listOf(2, 15, 30))
        if (points.isEmpty()) { toast("Position GPS pas encore disponible"); return }
        toast("Recherche des restaurants…")
        io.execute {
            try {
                val found = Nearby.restaurants(points, if (navigating) 2500 else 3000)
                main.post { showNearbyList("Restaurants", found) }
            } catch (e: Exception) {
                main.post { toast("Recherche impossible : ${e.message}") }
            }
        }
    }

    private fun showStations() {
        val points = searchPoints(listOf(3, 25, 50))
        if (points.isEmpty()) { toast("Position GPS pas encore disponible"); return }
        toast("Recherche des stations…")
        val fuel = settings.fuel
        io.execute {
            val found = ArrayList<NearbyPlace>()
            var failed = false
            for (p in points) {
                try {
                    found += Nearby.stations(p[0], p[1], 10, fuel)
                } catch (e: Exception) {
                    failed = true
                }
            }
            if (found.isEmpty() && failed) {
                try {
                    points.forEach { found += Nearby.stationsOsm(it[0], it[1], 8000) }
                } catch (e: Exception) {
                    // rien
                }
            }
            main.post { showNearbyList("Stations-service", found.distinctBy { it.id }, sortableByPrice = true) }
        }
    }

    /**
     * Liste triée : pendant le guidage, les lieux devant soi à moins de 1,5 km du trajet
     * (dans l'ordre de passage) ; sinon les plus proches.
     */
    /**
     * Liste triée : pendant le guidage, les lieux devant soi à moins de 1,5 km du trajet
     * (dans l'ordre de passage) ; sinon les plus proches.
     */
    private fun showNearbyList(title: String, items: List<NearbyPlace>, sortableByPrice: Boolean = false, byPrice: Boolean = false) {
        val loc = lastLocation ?: return
        val r = route
        val entries: List<Pair<NearbyPlace, String>>
        if (navigating && r != null) {
            val along = r.project(loc.latitude, loc.longitude, segmentHint).along
            val onRoute = items.mapNotNull { n -> r.projectNear(n.lat, n.lon, 1500.0)?.let { n to it.along - along } }
                .filter { it.second > -200 }
            val sorted = if (byPrice) onRoute.sortedBy { it.first.price ?: Double.MAX_VALUE } else onRoute.sortedBy { it.second }
            entries = sorted.map { (n, d) -> n to "sur le trajet, dans ${Geo.formatDistance(d.coerceAtLeast(0.0))}" }
        } else {
            val withDist = items.map { it to Geo.distance(loc.latitude, loc.longitude, it.lat, it.lon) }
            val sorted = if (byPrice) withDist.sortedBy { it.first.price ?: Double.MAX_VALUE } else withDist.sortedBy { it.second }
            entries = sorted.map { (n, d) -> n to "à ${Geo.formatDistance(d)}" }
        }
        val shown = entries.take(40)
        nearbyShown = shown.map { it.first }
        refreshNearbyLayer()
        if (shown.isEmpty()) {
            toast(if (navigating) "Aucun résultat le long du trajet" else "Aucun résultat à proximité")
            return
        }
        val fuelLabel = Nearby.fuels.firstOrNull { it.first == settings.fuel }?.second ?: ""
        val sheet = Sheet(this).title(title, if (sortableByPrice) "Prix du $fuelLabel · ${if (byPrice) "moins chères d'abord" else "plus proches d'abord"}" else null)
        if (sortableByPrice) {
            sheet.secondary(if (byPrice) "Trier par distance" else "€  Moins chères d'abord") {
                showNearbyList(title, items, sortableByPrice = true, byPrice = !byPrice)
            }
        }
        shown.forEach { (n, where) ->
            val (icon, color) = when {
                n.brand == "mcdo" -> "🍟" to "#FFF3C4"
                n.brand == "bk" -> "👑" to "#FDE7DD"
                n.kind == NearbyKind.STATION -> "⛽" to "#FCE8E6"
                else -> "🍽️" to "#FDE7DD"
            }
            val title2 = if (n.kind == NearbyKind.STATION) {
                (n.price?.let { "${Nearby.fmtPrice(it)}  ·  " } ?: "") + (n.city ?: n.name)
            } else n.name
            val subtitle = if (n.kind == NearbyKind.STATION) listOfNotNull(where, n.address).joinToString(" · ")
            else listOfNotNull(where, n.detail).joinToString(" · ")
            sheet.item(icon, title2, subtitle, color) {
                if (n.kind == NearbyKind.STATION) showStationCard(n) else showPlaceCard(n)
            }
        }
        sheet.show()
    }

    /** Fiche d'un lieu : nom, infos, distance et gros bouton pour s'y rendre. */
    /** Fiche d'un lieu : nom, infos détaillées (téléphone, horaires, note), gros bouton pour s'y rendre. */
    private fun showPlaceCard(n: NearbyPlace) {
        if (n.kind == NearbyKind.STATION) { showStationCard(n); return }
        val icon = when {
            n.brand == "mcdo" -> "🍟"
            n.brand == "bk" -> "👑"
            n.category == null || n.category in setOf("restaurant", "fast_food", "cafe", "bar", "pub", "food_court", "ice_cream") -> "🍽️"
            n.category in setOf("aerodrome", "aeroway", "airport") -> "✈️"
            n.category in setOf("shop", "grocery", "clothing_store", "supermarket") -> "🛍️"
            n.category in setOf("lodging", "hotel") -> "🏨"
            else -> "📍"
        }
        val sheet = Sheet(this).title("$icon  ${n.name}", listOfNotNull(n.detail, distanceLabel(n.lat, n.lon)).joinToString(" · "))
        val infoBox = sheet.slot()
        val loading = TextView(this).apply {
            text = "Chargement des informations…"
            setTextColor(Color.parseColor("#80868B"))
            setPadding(0, dp(4), 0, dp(8))
        }
        infoBox.addView(loading)
        placeActions(sheet, n)
        sheet.show()
        // Google et OpenStreetMap interrogés en parallèle : on affiche dès qu'une réponse arrive.
        val googleKey = settings.googleKey
        var osm: PlaceInfo? = null
        var google: GoogleInfo? = null
        var googleError: String? = null
        var osmDone = false
        var googleDone = googleKey.isBlank()
        var timedOut = false
        fun render() {
            val final = (osmDone && googleDone) || timedOut
            if (!final && osm == null && google == null) return
            infoBox.removeAllViews()
            fillPlaceInfo(sheet, infoBox, n, osm, google, final)
            if (!final) infoBox.addView(TextView(this).apply {
                text = "Recherche d'autres informations…"
                setTextColor(Color.parseColor("#80868B"))
                setPadding(0, dp(4), 0, dp(4))
            })
            googleError?.let { infoBox.addView(sheet.infoRow("⚠️", "Google : $it") { showKeySettings() }) }
            if (googleKey.isBlank()) infoBox.addView(sheet.infoRow("🔑", "Ajoutez votre clé Google pour la note et les avis") { showKeySettings() })
        }
        if (googleKey.isNotBlank()) Thread {
            var result: GoogleInfo? = null
            var error: String? = null
            try {
                result = Nearby.google(googleKey, n.name, n.lat, n.lon)
                if (result == null) error = "lieu introuvable"
            } catch (e: Exception) {
                error = googleErrorLabel(e)
            }
            main.post { google = result; googleError = error; googleDone = true; render() }
        }.start()
        Thread {
            val result = try { Nearby.osmDetails(n.name, n.lat, n.lon) } catch (e: Exception) { null }
            main.post { osm = result; osmDone = true; render() }
        }.start()
        main.postDelayed({ if (!(osmDone && googleDone)) { timedOut = true; render() } }, 12_000)
    }

    /** Erreur Google Places traduite en conseil concret. */
    private fun googleErrorLabel(e: Exception): String {
        val m = e.message ?: return "erreur inconnue"
        return when {
            "API key not valid" in m || "API_KEY_INVALID" in m -> "clé refusée (vérifiez qu'elle est bien copiée)"
            "BILLING" in m.uppercase() || "billing" in m -> "facturation non activée sur le projet Google Cloud (obligatoire, même pour l'offre gratuite)"
            "has not been used" in m || "SERVICE_DISABLED" in m || "is disabled" in m -> "« Places API (New) » n'est pas activée pour ce projet Google Cloud"
            "API_KEY_SERVICE_BLOCKED" in m || "are blocked" in m -> "la clé est limitée à d'autres API : autorisez « Places API (New) »"
            "API_KEY_ANDROID_APP_BLOCKED" in m || "referer" in m.lowercase() -> "la clé est limitée à une autre appli ou un site : retirez cette restriction"
            "HTTP 429" in m -> "quota dépassé"
            else -> m.take(140)
        }
    }

    private fun fillPlaceInfo(sheet: Sheet, box: LinearLayout, n: NearbyPlace, osm: PlaceInfo?, google: GoogleInfo?, final: Boolean = true) {
        // Note et avis
        if (google?.rating != null) {
            val stars = "★".repeat(Math.round(google.rating).toInt().coerceIn(0, 5)) + "☆".repeat(5 - Math.round(google.rating).toInt().coerceIn(0, 5))
            box.addView(TextView(this).apply {
                text = String.format(Locale.FRANCE, "%.1f  %s", google.rating, stars) +
                    "  (${google.ratingCount} avis)" + (google.priceLevel?.let { " · $it" } ?: "")
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#E37400"))
                setPadding(0, dp(2), 0, dp(6))
            })
        }
        google?.openNow?.let { open ->
            box.addView(sheet.infoRow(if (open) "🟢" else "🔴", if (open) "Ouvert actuellement" else "Fermé actuellement"))
        }
        val address = google?.address ?: osm?.address
        address?.let { box.addView(sheet.infoRow("📍", it)) }
        val phone = google?.phone ?: osm?.phone
        phone?.let { tel ->
            box.addView(sheet.infoRow("📞", tel) {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + tel.filter { it.isDigit() || it == '+' })))
            })
        }
        val website = google?.website ?: osm?.website
        website?.let { url ->
            box.addView(sheet.infoRow("🌐", url.removePrefix("https://").removePrefix("http://").removeSuffix("/")) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (url.startsWith("http")) url else "https://$url"))) } catch (e: Exception) { }
            })
        }
        val hours = google?.weekHours?.takeIf { it.isNotEmpty() }?.joinToString("\n") ?: osm?.hours?.let { formatOsmHours(it) }
        hours?.let { box.addView(sheet.infoRow("🕘", it)) }
        osm?.cuisine?.let { box.addView(sheet.infoRow("🍴", "Cuisine : $it")) }
        val extras = listOfNotNull(if (osm?.takeaway == true) "à emporter" else null, if (osm?.delivery == true) "livraison" else null)
        if (extras.isNotEmpty()) box.addView(sheet.infoRow("🛍️", extras.joinToString(", ").replaceFirstChar { it.uppercase() }))
        if (final && phone == null && website == null && hours == null && google?.rating == null) {
            box.addView(sheet.infoRow("ℹ️", "Pas d'informations détaillées pour ce lieu."))
        }
        // Note et avis complets sur Google Maps (gratuit, sans clé)
        box.addView(sheet.infoRow("⭐", if (google?.rating != null) "Lire les avis sur Google Maps" else "Voir la note et les avis sur Google Maps") {
            val uri = google?.mapsUri?.let { Uri.parse(it) } ?: Uri.parse("geo:${n.lat},${n.lon}?q=" + Uri.encode(n.name))
            try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) { toast("Google Maps n'est pas installé") }
        })
    }

    /** Horaires OSM rendus un peu plus lisibles (Mo-Fr 09:00-18:00 → lun-ven 09h00-18h00). */
    private fun formatOsmHours(h: String): String {
        if (h == "24/7") return "Ouvert 24h/24, 7j/7"
        val days = mapOf("Mo" to "lun", "Tu" to "mar", "We" to "mer", "Th" to "jeu", "Fr" to "ven", "Sa" to "sam", "Su" to "dim", "PH" to "fériés", "off" to "fermé")
        var out = h
        days.forEach { (k, v) -> out = out.replace(Regex("\\b$k\\b"), v) }
        return out.replace(Regex("(\\d{2}):(\\d{2})"), "$1h$2").split(";").joinToString("\n") { it.trim() }
    }

    private fun showStationCard(n: NearbyPlace) {
        if (n.prices.isEmpty() && n.id.startsWith("base-")) {
            // Station touchée sur le fond de carte : on cherche ses prix officiels
            val fuel = settings.fuel
            io.execute {
                val match = try {
                    Nearby.stations(n.lat, n.lon, 1, fuel)
                        .map { it to Geo.distance(n.lat, n.lon, it.lat, it.lon) }
                        .filter { it.second < 250 }
                        .minByOrNull { it.second }?.first
                } catch (e: Exception) { null }
                val merged = match?.copy(id = "fuel-" + n.id, name = n.name, lat = n.lat, lon = n.lon) ?: n.copy(id = "noprice-${n.lat}")
                main.post { showStationCard(merged) }
            }
            return
        }
        val sheet = Sheet(this).title("⛽  ${n.name}", listOfNotNull(n.address, distanceLabel(n.lat, n.lon)).joinToString(" · "))
        if (n.prices.isEmpty()) {
            sheet.text("Prix non communiqués pour cette station.", "#5F6368")
        } else {
            sheet.view(priceGrid(n.prices))
            n.updated?.let { sheet.text("Prix mis à jour ${relativeDate(it)}", "#5F6368", 13f) }
        }
        placeActions(sheet, n)
        sheet.show()
    }

    private fun placeActions(sheet: Sheet, n: NearbyPlace) {
        if (navigating) {
            sheet.primary("Faire un arrêt ici") { addStop(n.toPlace()) }
            sheet.secondary("Remplacer la destination") {
                stopNavigation()
                setDestination(n.toPlace())
            }
        } else {
            sheet.primary("▲  Y aller") { setDestination(n.toPlace()) }
            sheet.secondary("Ouvrir dans Waze") { openInWaze(n.lat, n.lon) }
            sheet.secondary("Enregistrer en POI") {
                val category = if (n.kind == NearbyKind.STATION) PoiCategory.STATION else PoiCategory.RESTAURANT
                poiStore.add(n.name, category, n.lat, n.lon)
                refreshPoiLayer()
                toast("POI « ${n.name} » ajouté")
            }
        }
    }

    /** Grille de prix : 3 cases par ligne, le carburant préféré en bleu. */
    private fun priceGrid(prices: Map<String, Double>): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ordered = Nearby.fuels.filter { prices.containsKey(it.first) }
            .sortedBy { if (it.first == settings.fuel) 0 else 1 }
        ordered.chunked(3).forEach { rowFuels ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowFuels.forEachIndexed { i, (key, label) ->
                val preferred = key == settings.fuel
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor(if (preferred) "#1A73E8" else "#F1F3F4"))
                        cornerRadius = dp(14).toFloat()
                    }
                }
                cell.addView(TextView(this).apply {
                    text = label
                    textSize = 13f
                    setTextColor(Color.parseColor(if (preferred) "#D2E3FC" else "#5F6368"))
                })
                cell.addView(TextView(this).apply {
                    text = String.format(Locale.FRANCE, "%.3f", prices[key])
                    textSize = 22f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(if (preferred) Color.WHITE else Color.parseColor("#202124"))
                })
                cell.addView(TextView(this).apply {
                    text = "€ / L"
                    textSize = 11f
                    setTextColor(Color.parseColor(if (preferred) "#D2E3FC" else "#80868B"))
                })
                row.addView(cell, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                })
            }
            // cases vides pour garder l'alignement
            repeat(3 - rowFuels.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) }) }
            grid.addView(row, LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
        }
        return grid
    }

    /** « il y a 3 h », « hier », « le 21/10 ». */
    private fun relativeDate(iso: String): String {
        val date = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss").firstNotNullOfOrNull { f ->
            try { SimpleDateFormat(f, Locale.US).parse(if (f.endsWith("XXX")) iso else iso.take(19)) } catch (e: Exception) { null }
        } ?: return iso
        val minutes = (System.currentTimeMillis() - date.time) / 60_000
        return when {
            minutes < 60 -> "il y a ${minutes.coerceAtLeast(1)} min"
            minutes < 24 * 60 -> "il y a ${minutes / 60} h"
            minutes < 48 * 60 -> "hier"
            else -> "le " + SimpleDateFormat("dd/MM", Locale.FRANCE).format(date)
        }
    }

    private fun addStop(place: Place) = Nav.addStop(place)

    // ---------- Menu et réglages ----------

    private fun showMainMenu() {
        val next = agendaEvents.firstOrNull()
        val trafficState = when {
            !settings.hasTomTom && !settings.hasHere -> "Clé TomTom ou HERE à ajouter"
            settings.trafficOn -> "Affiché · toucher pour masquer"
            else -> "Masqué · toucher pour afficher"
        }
        Sheet(this)
            .title("GPS Perso")
            .sectionTitle("Aller quelque part")
            .item("📅", "Mon agenda", next?.let { "${it.whenLabel()} · ${it.title}" } ?: "Rendez-vous avec une adresse", "#E8F0FE") { showAgenda() }
            .item("🕘", "Adresses récentes", null, "#E8F0FE") { showRecents() }
            .item("🛣️", "Options d'itinéraire", routeOptionsSummary(), "#E8F0FE") { showRouteOptions() }
            .item("⭐", "Mes POIs", "${poiStore.all().size} lieux enregistrés", "#FEF7E0") { showPoiList() }
            .sectionTitle("Autour de moi / sur le trajet")
            .item("⛽", "Stations-service", "Prix du carburant", "#FCE8E6") { showStations() }
            .item("🍟", "McDonald's et Burger King", null, "#FFF3C4") { showBrands() }
            .item("🍽️", "Restaurants", null, "#FDE7DD") { showRestaurants() }
            .sectionTitle("Carte")
            .item("🅿️", "Ma voiture", parkingSummary(), "#E8F0FE") { showParking() }
            .item("🚦", "Trafic en temps réel", trafficState, "#E6F4EA") { toggleTraffic() }
            .item("📥", "Cartes hors ligne", "Pour rouler sans réseau", "#E6F4EA") { showOfflineMaps() }
            .item("🆕", "Nouveautés radars", lufopStore.lastImport?.let { "Base du $it" } ?: "Aucune base importée", "#F3E8FD") { showLufopNews() }
            .item("⚙️", "Réglages", null, "#F1F3F4") { showSettings() }
            .item("❓", "Aide et nouveautés", "Version ${BuildConfig.VERSION_NAME}", "#E8F0FE") { showHelp() }
            .show()
    }

    // ---------- Maison et adresses récentes ----------

    private fun goHome() {
        if (!settings.hasHome) {
            showHomeSettings()
            return
        }
        if (navigating) stopNavigation()
        setDestination(Place("Maison", settings.homeName, settings.homeLat, settings.homeLon))
    }

    private fun showHomeSettings() {
        val sheet = Sheet(this).title("🏠  Ma maison", if (settings.hasHome) settings.homeName else "Pas encore définie")
        val address = sheet.input("Adresse de la maison", "")
        sheet.primary("Enregistrer cette adresse") {
            val q = address.text.toString().trim()
            if (q.length < 3) return@primary
            val loc = lastLocation
            io.execute {
                val place = try { Net.search(q, loc?.latitude, loc?.longitude).firstOrNull() } catch (e: Exception) { null }
                main.post {
                    if (place == null) toast("Adresse introuvable") else saveHome(listOf(place.name, place.address).filter { it.isNotBlank() }.joinToString(", "), place.lat, place.lon)
                }
            }
        }
        sheet.secondary("📍  Utiliser ma position actuelle") {
            val loc = lastLocation ?: run { toast("Position GPS pas encore disponible"); return@secondary }
            io.execute {
                val name = try { Net.reverse(loc.latitude, loc.longitude) } catch (e: Exception) { null } ?: "Ma position"
                main.post { saveHome(name, loc.latitude, loc.longitude) }
            }
        }
        sheet.show()
    }

    private fun saveHome(name: String, lat: Double, lon: Double) {
        settings.homeName = name
        settings.homeLat = lat
        settings.homeLon = lon
        toast("Maison enregistrée : $name")
    }

    private fun showRecents() {
        val recents = recentStore.all()
        val sheet = Sheet(this).title("🕘  Adresses récentes")
        if (recents.isEmpty()) sheet.text("Les adresses que vous recherchez apparaîtront ici.")
        recents.forEach { p ->
            sheet.item(if (p.name == "Maison") "🏠" else "🕘", p.name, listOfNotNull(p.address.ifBlank { null }, distanceLabel(p.lat, p.lon)).joinToString(" · "), "#F1F3F4") {
                if (navigating) stopNavigation()
                setDestination(p)
            }
        }
        if (recents.isNotEmpty()) sheet.secondary("Effacer l'historique") { recentStore.clear() }
        sheet.show()
    }

    private fun showLufopNews() {
        val recent = lufopStore.recent()
        val sheet = Sheet(this).title("🆕  Nouveautés radars", lufopStore.lastImport?.let { "Import du $it" } ?: "Aucun import pour l'instant")
        if (recent.isEmpty()) {
            sheet.text("Aucune nouveauté lors du dernier import.\n\nTéléchargez le fichier à jour sur lufop.net puis importez-le : l'appli vous montrera ce qui a changé.")
            sheet.primary("Importer un fichier") { importRadarFile() }
        }
        recent.take(100).forEach { a ->
            sheet.item(alertIcon(a.type), a.title, listOfNotNull(a.description, distanceLabel(a.lat, a.lon)).joinToString(" · "), pale(a.type.color)) {
                showOnMap(a.lat, a.lon)
            }
        }
        sheet.show()
    }

    /** Réglages : quelques rubriques, chacune ouvre son propre panneau. */
    // ---------- Sourdine et voix ----------

    private fun updateMuteButton() {
        findViewById<TextView>(R.id.btnMute).text = when (settings.voiceMode) {
            "mute" -> "🔇"
            "alerts", "guidance" -> "🔉"
            else -> "🔊"
        }
    }

    private fun showMuteMenu() {
        val sheet = Sheet(this).title("🔊  Annonces vocales", Voice.modes.first { it.first == settings.voiceMode }.second)
        Voice.modes.forEach { (mode, label) ->
            val icon = when (mode) { "all" -> "🔊"; "alerts" -> "📷"; "guidance" -> "🧭"; else -> "🔇" }
            sheet.item(icon, label, if (mode == settings.voiceMode) "✓ Choix actuel" else null, if (mode == settings.voiceMode) "#D2E3FC" else "#F1F3F4") {
                settings.voiceMode = mode
                if (mode == "mute") Voice.stop()
                updateMuteButton()
                toast(label)
            }
        }
        sheet.show()
    }

    private fun voiceSummary(): String {
        val source = when {
            settings.voiceSource == "eleven" && settings.hasEleven && settings.elevenVoice.isNotBlank() -> "ElevenLabs · " + settings.elevenVoiceName
            settings.voiceSource == "google" && settings.hasGoogle -> "Google · " + settings.googleVoice.removePrefix("fr-FR-")
            else -> "Voix du téléphone"
        }
        return "$source · " + (Voice.modes.firstOrNull { it.first == settings.voiceMode }?.second ?: "")
    }

    private fun showVoiceSettings() {
        val sheet = Sheet(this).title("🗣️  Voix et annonces", voiceSummary())
        sheet.item("🔊", "Annonces", Voice.modes.first { it.first == settings.voiceMode }.second, "#E8F0FE") { showMuteMenu() }
        sheet.sectionTitle("Quelle voix ?")
        val eleven = settings.voiceSource == "eleven" && settings.hasEleven && settings.elevenVoice.isNotBlank()
        val google = !eleven && settings.voiceSource == "google" && settings.hasGoogle
        val phone = !eleven && !google
        sheet.item("📱", "Voix du téléphone", (if (phone) "✓ " else "") + "Gratuite, sans Internet · " + (settings.ttsVoice.ifBlank { "voix par défaut" }), if (phone) "#D2E3FC" else "#F1F3F4") {
            settings.voiceSource = "device"
            showDeviceVoices()
        }
        sheet.item("🎙️", "Voix ElevenLabs", (if (eleven) "✓ " else "") + when {
            !settings.hasEleven -> "Clé ElevenLabs nécessaire (offre gratuite possible)"
            settings.elevenVoice.isBlank() -> "Choisir une voix"
            else -> settings.elevenVoiceName
        }, if (eleven) "#D2E3FC" else "#F1F3F4") {
            if (!settings.hasEleven) showKeySettings() else showElevenVoices()
        }
        sheet.item("✨", "Voix naturelles Google", (if (google) "✓ " else "") + if (settings.hasGoogle) settings.googleVoice.removePrefix("fr-FR-") else "Clé Google nécessaire", if (google) "#D2E3FC" else "#F1F3F4") {
            if (!settings.hasGoogle) showKeySettings() else showGoogleVoices()
        }
        if (eleven || google) sheet.item("🩺", "Tester la voix ${Voice.cloudName()}", "Vérifie chaque étape et dit où ça bloque", "#F1F3F4") {
            toast("Test en cours…")
            Voice.diagnose { report ->
                Sheet(this).title("🩺  Test de la voix ${Voice.cloudName()}").text(report).secondary("Fermer") {}.show()
            }
        }
        Voice.lastCloudError?.let {
            if (eleven) sheet.text("Dernière erreur ElevenLabs : $it", "#D93025", 13f)
            else if (google) sheet.text("Dernière erreur Google : ${googleErrorLabel(Exception(it))}", "#D93025", 13f)
        }
        sheet.sectionTitle("Réglage")
        val rates = listOf(0.85f to "Lente", 1f to "Normale", 1.15f to "Rapide", 1.3f to "Très rapide")
        sheet.item("⏩", "Vitesse", rates.minByOrNull { Math.abs(it.first - settings.ttsRate) }?.second, "#F1F3F4") {
            val s2 = Sheet(this).title("Vitesse de la voix")
            rates.forEach { (r, label) -> s2.item("⏩", label, null, "#F1F3F4") { settings.ttsRate = r; Voice.applyDeviceVoice(); Voice.preview(); showVoiceSettings() } }
            s2.show()
        }
        val pitches = listOf(0.8f to "Grave", 1f to "Normale", 1.2f to "Aiguë")
        sheet.item("🎚️", "Hauteur", pitches.minByOrNull { Math.abs(it.first - settings.ttsPitch) }?.second, "#F1F3F4") {
            val s2 = Sheet(this).title("Hauteur de la voix")
            pitches.forEach { (r, label) -> s2.item("🎚️", label, null, "#F1F3F4") { settings.ttsPitch = r; Voice.applyDeviceVoice(); Voice.preview(); showVoiceSettings() } }
            s2.show()
        }
        sheet.primary("▶  Écouter un essai") { Voice.preview(); showVoiceSettings() }
        sheet.show()
    }

    private fun showDeviceVoices() {
        val sheet = Sheet(this).title("📱  Voix du téléphone", "Toucher une voix pour l'écouter et la choisir")
        val engines = Voice.engines()
        if (engines.size > 1) {
            sheet.sectionTitle("Moteur de voix")
            val current = settings.ttsEngine.ifBlank { Voice.currentEngine() }
            engines.forEach { e ->
                sheet.item("⚙️", e.label, if (e.name == current) "✓ Utilisé" else null, if (e.name == current) "#D2E3FC" else "#F1F3F4") {
                    settings.ttsEngine = e.name
                    settings.ttsVoice = ""
                    Voice.reloadEngine()
                    toast("Moteur ${e.label} chargé")
                    main.postDelayed({ showDeviceVoices() }, 1200)
                }
            }
        }
        val voices = Voice.deviceVoices()
        sheet.sectionTitle("Voix françaises (${voices.size})")
        if (voices.isEmpty()) sheet.text("Aucune voix française trouvée pour ce moteur.", "#5F6368")
        sheet.item("🔁", "Voix par défaut", if (settings.ttsVoice.isBlank()) "✓ Choix actuel" else null, "#F1F3F4") {
            settings.ttsVoice = ""
            Voice.reloadEngine()
            main.postDelayed({ Voice.preview() }, 800)
        }
        voices.forEach { v ->
            val selected = v.name == settings.ttsVoice
            val details = listOfNotNull(
                if (selected) "✓ Choix actuel" else null,
                if (v.isNetworkConnectionRequired) "en ligne" else "hors ligne",
                if (v.quality >= android.speech.tts.Voice.QUALITY_HIGH) "haute qualité" else null,
            ).joinToString(" · ")
            sheet.item("🗣️", v.name.removePrefix("fr-fr-x-").removePrefix("fr-FR-"), details, if (selected) "#D2E3FC" else "#F1F3F4") {
                settings.ttsVoice = v.name
                settings.voiceSource = "device"
                Voice.applyDeviceVoice()
                Voice.preview()
                showDeviceVoices()
            }
        }
        sheet.secondary("Installer d'autres voix") {
            try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) } catch (e: Exception) {
                try { startActivity(Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (e2: Exception) { toast("Réglage introuvable sur ce téléphone") }
            }
        }
        sheet.show()
    }

    private fun showElevenVoices() {
        toast("Chargement des voix ElevenLabs…")
        val key = settings.elevenKey
        io.execute {
            val mine = try { Result.success(Voice.elevenAccountVoices(key)) } catch (e: Exception) { Result.failure(e) }
            val library = if (mine.isSuccess) try { Voice.elevenFrenchVoices(key) } catch (e: Exception) { emptyList() } else emptyList()
            main.post {
                val voices = mine.getOrNull()
                if (voices == null) {
                    val err = mine.exceptionOrNull() as? Exception ?: Exception("erreur")
                    Sheet(this).title("Voix ElevenLabs indisponibles", Voice.elevenErrorLabel(err))
                        .text("Créez une clé sur elevenlabs.io › Developers › API Keys (droit « Text to Speech » et « Voices » en lecture), puis collez-la dans ☰ › Réglages › Services connectés. L'offre gratuite donne 10 000 caractères par mois, et l'appli garde les annonces en cache.")
                        .secondary("Fermer") {}
                        .show()
                    return@post
                }
                val sheet = Sheet(this).title("🎙️  Voix ElevenLabs", "Toucher une voix pour l'écouter et la choisir")
                fun pick(v: Voice.ElevenVoice, id: String) {
                    settings.elevenVoice = id
                    settings.elevenVoiceName = v.name
                    settings.voiceSource = "eleven"
                    Voice.preview()
                    showElevenVoices()
                }
                fun row(v: Voice.ElevenVoice) {
                    val selected = settings.voiceSource == "eleven" && v.id == settings.elevenVoice
                    val icon = when (v.gender) { "male" -> "👨"; "female" -> "👩"; else -> "🗣️" }
                    sheet.item(icon, v.name, listOfNotNull(if (selected) "✓ Choix actuel" else null, v.details.ifBlank { null }).joinToString(" · ").ifBlank { null },
                        if (selected) "#D2E3FC" else "#F1F3F4") {
                        if (!v.shared) { pick(v, v.id); return@item }
                        toast("Ajout de ${v.name} à votre compte…")
                        io.execute {
                            val added = try { Result.success(Voice.elevenAddShared(key, v)) } catch (e: Exception) { Result.failure(e) }
                            main.post {
                                val id = added.getOrNull()
                                if (id != null) pick(v, id)
                                else Sheet(this).title("Voix non ajoutée", Voice.elevenErrorLabel(added.exceptionOrNull() as? Exception ?: Exception("erreur")))
                                    .secondary("Fermer") {}.show()
                            }
                        }
                    }
                }
                sheet.sectionTitle("Vos voix (${voices.size})")
                if (voices.isEmpty()) sheet.text("Aucune voix sur ce compte.", "#5F6368")
                voices.forEach { row(it) }
                val extra = library.filter { l -> voices.none { it.id == l.id } }
                if (extra.isNotEmpty()) {
                    sheet.sectionTitle("Voix françaises natives (bibliothèque)")
                    sheet.text("Ajoutées à votre compte au premier choix. Sur l'offre gratuite, ElevenLabs peut demander un abonnement pour certaines.", "#5F6368", 13f)
                    extra.forEach { row(it) }
                }
                sheet.show()
            }
        }
    }

    private fun showGoogleVoices() {
        toast("Chargement des voix Google…")
        val key = settings.googleKey
        io.execute {
            val result = try { Result.success(Voice.cloudVoices(key)) } catch (e: Exception) { Result.failure(e) }
            main.post {
                val voices = result.getOrNull()
                if (voices == null) {
                    val err = result.exceptionOrNull() as? Exception ?: Exception("erreur")
                    val msg = googleErrorLabel(err).replace("Places API (New)", "Cloud Text-to-Speech API")
                    Sheet(this).title("Voix Google indisponibles", msg)
                        .text("Dans Google Cloud Console, activez « Cloud Text-to-Speech API » sur le même projet que votre clé. C'est gratuit jusqu'à 1 million de caractères par mois, et l'appli garde les annonces en cache.")
                        .secondary("Fermer") {}
                        .show()
                    return@post
                }
                val sheet = Sheet(this).title("✨  Voix Google", "Toucher une voix pour l'écouter et la choisir")
                voices.forEach { v ->
                    val selected = settings.voiceSource == "google" && v.name == settings.googleVoice
                    sheet.item(if (v.gender == "MALE") "👨" else "👩", v.label, if (selected) "✓ Choix actuel" else null, if (selected) "#D2E3FC" else "#F1F3F4") {
                        settings.googleVoice = v.name
                        settings.voiceSource = "google"
                        Voice.preview()
                        showGoogleVoices()
                    }
                }
                sheet.show()
            }
        }
    }

    // ---------- Aide ----------

    private fun showHelp() {
        settings.seenVersion = BuildConfig.VERSION_CODE
        Sheet(this).title("❓  Aide et nouveautés", "GPS Perso · version ${BuildConfig.VERSION_NAME}")
            .sectionTitle("Nouveautés")
            .text(
                "🎙️ Voix ElevenLabs pour le guidage : collez votre clé dans Réglages › Services connectés, puis choisissez une voix française dans Voix et annonces. Si ElevenLabs ne répond pas, la voix du téléphone prend le relais.\n" +
                    "🔍 Carte plus rapprochée pendant le guidage, zoom sur la voiture à l'approche des intersections, et la route reste visible sous les bandeaux de sortie, de voies et de radar. Réglable dans Affichage de la carte › Zoom pendant le guidage.\n" +
                    "⚠️ Zones de danger : grand bandeau avec le bouton « Leurre ? Ne plus annoncer » (il n'apparaissait jamais).\n" +
                    "🔄 Rond-points : le schéma suit le vrai angle de la sortie à prendre (tout droit, à gauche, demi-tour…).\n" +
                    "🔆 L'écran reste allumé tant que l'appli est ouverte.\n" +
                    "⚠️ Zones de danger : annoncées seulement si tu dépasses la limite (réglable dans Radars et alertes), une seule fois tous les 3 km, et bouton « Leurre ? Ne plus annoncer ».\n" +
                    "📷 Radar fixe en approche : grand bandeau avec la distance, la limitation et ta vitesse ; tout passe au rouge avec un cadre autour de l'écran si tu vas trop vite.\n" +
                    "🛣️ Sorties et bifurcations façon TomTom : vue de l'échangeur, panneaux aux couleurs françaises, numéro de sortie, voies dessinées, barre d'approche, « Puis… » et annonces à 2 km, 1 km et 400 m.\n" +
                    "🎯 La carte revient seule sur la voiture 7 s après l'avoir déplacée au doigt, pendant le guidage comme en navigation libre (voiture au centre).\n" +
                    "🔁 Recalcul plus fiable quand on quitte le trajet (ne reste plus bloqué), et l'appli ne se ferme plus sur une erreur : elle affiche un rapport à copier.\n" +
                    "🔀 Choix entre plusieurs trajets, avec durée, péages et bouchons pour chacun.\n" +
                    "🛣️ Options d'itinéraire (☰) : éviter les péages ou les autoroutes. Péages indiqués sous le trajet, avec le prix si clé HERE.\n" +
                    "🚗 Nouvelle voiture blanche, et icônes de véhicule encore plus grandes.\n" +
                    "🚦 Avec une clé HERE (gratuite), les limitations de vitesse du guidage viennent de HERE, bien plus fiables.\n" +
                    "📍 Position plus stable : même source que Google Maps (GPS + capteurs), carte qui ne fige plus quand les alertes se mettent à jour.\n" +
                    "🚦 Limitation de vitesse plus fiable : la route est reconnue en 1 à 2 secondes.\n" +
                    "🔍 Pendant le guidage, le véhicule est en bas de l'écran et la carte zoome toute seule selon la vitesse et à l'approche des sorties.\n" +
                    "☆ Bouton « Enregistrer » sur l'itinéraire, à la place de Waze (Waze reste dans l'appui long sur la carte).\n" +
                    "➕ Choisir une autre destination pendant un trajet propose d'en faire une étape.\n" +
                    "🚧 Bison Futé : accidents, travaux et routes fermées sur autoroutes et nationales, sans clé.\n" +
                    "🚦 Trafic HERE en deuxième source (clé gratuite dans ☰ › Réglages › Services connectés).\n" +
                    "🌦️ Météo sur le trajet : neige, verglas, orages, brouillard à l'heure où vous passerez.\n" +
                    "🛣️ Voies à prendre et panneaux de sortie dans le bandeau de guidage.\n" +
                    "🌙 Mode nuit automatique au coucher du soleil (réglable dans Affichage de la carte).\n" +
                    "📥 Cartes hors ligne : autour de moi, de la maison ou le long du trajet.\n" +
                    "🅿️ Ma voiture : la place est retenue à l'arrivée, pour la retrouver à pied."
            )
            .sectionTitle("Version 10")
            .text(
                "⛰ Altitude du lieu où vous êtes, à côté du compteur.\n" +
                    "🚦 Limitation de vitesse de la route au-dessus du compteur (rouge si vous dépassez), aussi dans Android Auto.\n" +
                    "🗣️ Choix de la voix : voix du téléphone ou voix naturelles Google (☰ › Réglages › Voix et annonces).\n" +
                    "🔊 Bouton sourdine sur la carte : tout, alertes seulement, trajet seulement ou muet.\n" +
                    "ℹ️ Fiches des lieux plus rapides, avec la raison exacte si Google ne répond pas.\n" +
                    "🇫🇷 Radars France intégrés (20 592 points), en plus de l'Espagne.\n" +
                    "🚗 Android Auto : carte, guidage, destinations, stations et signalements sur l'écran de la voiture.\n" +
                    "🔒 Le guidage continue écran verrouillé, avec une notification."
            )
            .sectionTitle("Versions précédentes")
            .text(
                "v6 · Radars Espagne, pictogrammes de radars regroupés, maison plus discrète.\n" +
                    "v5 · Boutons Maison et Récents, fiches restaurant, mise à jour Lufop par API.\n" +
                    "v4 · Nouveaux menus, fiche station avec les prix, import de fichiers radars.\n" +
                    "v1 à v3 · Guidage vocal, trafic, POIs, agenda, stations, McDo et Burger King.",
                "#5F6368", 14f
            )
            .sectionTitle("Astuces")
            .text(
                "• Appui long sur la carte : y aller, ajouter un POI ou signaler.\n" +
                    "• Appui long sur 🏠 : changer l'adresse de la maison.\n" +
                    "• Pendant le guidage, une station ou un resto peut devenir une étape.\n" +
                    "• Android Auto : ouvrez Android Auto › touchez 10 fois « Version » › ⋮ › Paramètres pour les développeurs › Sources inconnues."
            )
            .secondary("Fermer") {}
            .show()
    }

    private fun showSettings() {
        val iconLabels = mapOf(
            "fleche" to "Flèche bleue", "voiture_rouge" to "Voiture rouge", "voiture_noire" to "Voiture noire",
            "voiture_bleue" to "Voiture bleue", "voiture_blanche" to "Voiture blanche", "moto" to "Moto", "camion" to "Camion",
        )
        val fuelLabel = Nearby.fuels.firstOrNull { it.first == settings.fuel }?.second ?: settings.fuel
        val count = lufopStore.all().size
        Sheet(this).title("⚙️  Réglages")
            .item("🏠", "Ma maison", if (settings.hasHome) settings.homeName else "Non définie", "#E8F0FE") { showHomeSettings() }
            .item("🚗", "Mon véhicule", "${iconLabels[settings.positionIcon]} · $fuelLabel", "#E8F0FE") { showVehicleSettings() }
            .item("📷", "Radars et alertes", if (count > 0) "$count points Lufop · annonces ${if (settings.announceRadars) "activées" else "coupées"}" else "Lufop à configurer", "#F3E8FD") { showRadarSettings() }
            .item("🗺️", "Affichage de la carte", "Zoom du guidage, mode nuit, Bison Futé, météo, bornes", "#E6F4EA") { showDisplaySettings() }
            .item("🗣️", "Voix et annonces", voiceSummary(), "#FEF7E0") { showVoiceSettings() }
            .item("🔑", "Services connectés", listOfNotNull(
                if (settings.hasTomTom) "Trafic TomTom ✓" else "Trafic TomTom ✗",
                if (settings.hasHere) "HERE ✓" else "HERE ✗",
                if (settings.hasGoogle) "Avis Google ✓" else "Avis Google ✗",
                if (settings.hasEleven) "ElevenLabs ✓" else "ElevenLabs ✗",
            ).joinToString(" · "), "#F1F3F4") { showKeySettings() }
            .show()
    }

    private fun showVehicleSettings() {
        val iconLabels = listOf(
            "fleche" to "Flèche bleue", "voiture_rouge" to "Voiture rouge", "voiture_noire" to "Voiture noire",
            "voiture_bleue" to "Voiture bleue", "voiture_blanche" to "Voiture blanche", "moto" to "Moto", "camion" to "Camion",
        )
        val sheet = Sheet(this).title("🚗  Mon véhicule").sectionTitle("Icône de ma position")
        iconLabels.forEach { (key, label) ->
            sheet.item(if (key == settings.positionIcon) "✓" else "", label, null, "#E8F0FE") {
                settings.positionIcon = key
                applyPositionIcon()
                showVehicleSettings()
            }
        }
        sheet.sectionTitle("Mon carburant (prix des stations)")
        Nearby.fuels.forEach { (key, label) ->
            sheet.item(if (key == settings.fuel) "✓" else "", label, null, "#FCE8E6") {
                settings.fuel = key
                showVehicleSettings()
            }
        }
        sheet.show()
    }

    private fun showRadarSettings() {
        val count = lufopStore.all().size
        val sheet = Sheet(this).title("📷  Radars et alertes", if (count > 0) "$count points Lufop, mis à jour le ${lufopStore.lastImport}" else "Aucune base Lufop pour l'instant")
        val announce = switchRow("Annonces vocales des radars", settings.announceRadars)
        sheet.view(announce)
        sheet.sectionTitle("Zones de danger")
        listOf(
            "exces" to "Seulement si je dépasse la limite (conseillé)",
            "toutes" to "Toutes les annoncer",
            "jamais" to "Ne jamais les annoncer",
        ).forEach { (key, label) ->
            sheet.item(if (key == settings.dangerMode) "✓" else "⚠️", label, null, "#FFF3E0") {
                settings.dangerMode = key
                Nav.alertsChanged()
                sheet.dismiss()
                showRadarSettings()
            }
        }
        val ignored = settings.ignoredAlerts.size
        if (ignored > 0) {
            sheet.item("🚫", "$ignored zones marquées comme leurres", "Toucher pour les annoncer à nouveau", "#F1F3F4") {
                Nav.clearIgnoredAlerts()
                toast("Leurres oubliés")
                sheet.dismiss()
            }
        }
        sheet.sectionTitle("Clé API Lufop")
        val key = sheet.input("Clé API Lufop", settings.lufopKey)
        fun save() {
            settings.announceRadars = announce.isChecked
            settings.lufopKey = key.text.toString()
            Nav.alertsChanged()
        }
        sheet.primary("⬇️  Mettre à jour depuis Lufop") {
            save()
            if (settings.hasLufop) updateFromLufopApi(manual = true) else toast("Saisissez d'abord votre clé Lufop")
        }
        sheet.secondary("📥  Importer un fichier") {
            save()
            importRadarFile()
        }
        val sets = lufopStore.datasetSizes()
        if (sets.isNotEmpty()) sheet.sectionTitle("Bases importées (toucher pour supprimer)")
        sets.forEach { (name, size) ->
            val label = when (name) {
                "api-lufop" -> "Lufop (API)"
                "ESRADARES" -> "Radars Espagne"
                "FRradarFR" -> "Radars France"
                else -> name
            }
            sheet.item("🗂️", label, "$size points", "#F3E8FD") {
                save()
                Sheet(this).title("Supprimer « $label » ?", "$size points")
                    .primary("Supprimer", "#D93025") {
                        lufopStore.remove(name)
                        Nav.alertsChanged()
                        toast("« $label » supprimée")
                    }
                    .secondary("Annuler") {}
                    .show()
            }
        }
        sheet.onDismiss { save() }
        sheet.show()
    }

    private fun showDisplaySettings() {
        val sheet = Sheet(this).title("🗺️  Affichage de la carte")
        val chargers = switchRow("Bornes de recharge", settings.showChargers)
        val brands = switchRow("McDonald's et Burger King en évidence", settings.showBrands)
        val traffic = switchRow("Trafic en temps réel", settings.trafficOn)
        val bison = switchRow("Bison Futé (accidents, travaux, fermetures)", settings.showBisonFute)
        val weather = switchRow("Météo sur le trajet", settings.showWeather)
        sheet.view(traffic).view(bison).view(weather).view(chargers).view(brands)
        sheet.sectionTitle("Zoom pendant le guidage")
        listOf(
            "tres_proche" to "Très proche",
            "proche" to "Proche (conseillé)",
            "eloigne" to "Éloigné (vue large)",
        ).forEach { (key, label) ->
            sheet.item(if (key == settings.guidanceZoom) "✓" else "🔍", label, null, "#E8F0FE") {
                settings.guidanceZoom = key
                if (navigating) applyAutoZoom(force = true)
                sheet.dismiss()
            }
        }
        sheet.sectionTitle("Mode nuit")
        listOf("auto" to "Automatique (coucher du soleil)", "on" to "Toujours sombre", "off" to "Toujours clair").forEach { (key, label) ->
            sheet.item(if (key == settings.nightMode) "✓" else "🌙", label, null, "#F1F3F4") {
                settings.nightMode = key
                loadStyle(wantDark())
                sheet.dismiss()
            }
        }
        sheet.onDismiss {
            settings.showChargers = chargers.isChecked
            settings.showBrands = brands.isChecked
            if (bison.isChecked != settings.showBisonFute) {
                settings.showBisonFute = bison.isChecked
                if (bison.isChecked) Nav.maybeLoadBisonFute(force = true)
            }
            settings.showWeather = weather.isChecked
            if (traffic.isChecked != settings.trafficOn) {
                settings.trafficOn = traffic.isChecked
                updateTrafficLayer()
                if (traffic.isChecked) Nav.refreshTrafficNow() else Nav.clearIncidents()
            }
            refreshBrandLayer()
            brandsCenter = null
            Nav.reloadAround()
            lastLocation?.let { maybeLoadBrandsAround(it) }
            Nav.alertsChanged()
        }
        sheet.primary("Terminé") {}
        sheet.show()
    }

    private fun showKeySettings() {
        val sheet = Sheet(this).title("🔑  Services connectés", "Clés gratuites, gardées sur ce téléphone")
        sheet.sectionTitle("Trafic TomTom (developer.tomtom.com)")
        val tomtom = sheet.input("Clé API TomTom", settings.tomtomKey)
        sheet.sectionTitle("HERE : limitations de vitesse et trafic (platform.here.com)")
        sheet.text("Facultatif mais conseillé : limitations de vitesse plus fiables pendant le guidage, et incidents en plus de TomTom. Formule « Limited », gratuite et sans carte bancaire.", "#5F6368", 13f)
        val here = sheet.input("Clé API HERE", settings.hereKey)
        sheet.sectionTitle("Notes et avis Google (facultatif)")
        sheet.text("Sans clé, le bouton « Voir les avis sur Google Maps » reste disponible sur chaque restaurant.", "#5F6368", 13f)
        val google = sheet.input("Clé API Google Places", settings.googleKey)
        sheet.sectionTitle("Voix ElevenLabs (elevenlabs.io)")
        sheet.text("Voix très naturelles pour le guidage. Offre gratuite : 10 000 caractères par mois, les annonces déjà dites sont gardées en cache.", "#5F6368", 13f)
        val eleven = sheet.input("Clé API ElevenLabs", settings.elevenKey)
        sheet.primary("Enregistrer") {
            settings.tomtomKey = tomtom.text.toString()
            settings.hereKey = here.text.toString()
            settings.googleKey = google.text.toString()
            settings.elevenKey = eleven.text.toString()
            updateTrafficLayer()
            Nav.refreshTrafficNow()
            toast("Clés enregistrées")
        }
        sheet.show()
    }

    // ---------- Options d'itinéraire ----------

    private fun routeOptionsSummary(): String =
        listOfNotNull(if (settings.avoidTolls) "Sans péage" else null, if (settings.avoidHighways) "Sans autoroute" else null)
            .joinToString(" · ").ifEmpty { "Péages et autoroutes autorisés" }

    private fun showRouteOptions() {
        val sheet = Sheet(this).title("🛣️  Options d'itinéraire", "Le trajet en cours est recalculé")
        val tolls = switchRow("Éviter les péages", settings.avoidTolls)
        val highways = switchRow("Éviter les autoroutes", settings.avoidHighways)
        sheet.view(tolls).view(highways)
        sheet.text("Le prix des péages s'affiche sous le trajet si une clé HERE est saisie (☰ › Réglages › Services connectés).", "#5F6368", 13f)
        sheet.onDismiss {
            if (tolls.isChecked != settings.avoidTolls || highways.isChecked != settings.avoidHighways) {
                settings.avoidTolls = tolls.isChecked
                settings.avoidHighways = highways.isChecked
                if (destination != null) {
                    routeSummary.text = "Calcul de l'itinéraire…"
                    Nav.recomputeRoute()
                }
                toast(routeOptionsSummary())
            }
        }
        sheet.primary("Terminé") {}
        sheet.show()
    }

    // ---------- Ma voiture ----------

    private fun refreshParkingLayer() {
        val src = style?.getSourceAs<GeoJsonSource>("park-src") ?: return
        if (!settings.hasParking) src.setGeoJson(FeatureCollection.fromFeatures(emptyList<Feature>()))
        else src.setGeoJson(Feature.fromGeometry(Point.fromLngLat(settings.parkLon, settings.parkLat)))
    }

    private fun parkingSummary(): String {
        if (!settings.hasParking) return "Aucune place retenue"
        val ago = (System.currentTimeMillis() - settings.parkTime) / 60_000
        val time = when {
            ago < 1 -> "à l'instant"
            ago < 60 -> "il y a $ago min"
            ago < 24 * 60 -> "il y a ${ago / 60} h ${ago % 60} min"
            else -> "le " + SimpleDateFormat("d MMM à HH:mm", Locale.FRANCE).format(Date(settings.parkTime))
        }
        return listOfNotNull("Garée $time", distanceLabel(settings.parkLat, settings.parkLon)).joinToString(" · ")
    }

    private fun showParking() {
        val sheet = Sheet(this).title("🅿️  Ma voiture", parkingSummary())
        if (settings.hasParking) {
            val lat = settings.parkLat
            val lon = settings.parkLon
            sheet.primary("🚶  Y aller à pied") { walkTo(lat, lon) }
            sheet.item("🗺️", "Voir sur la carte", null, "#E8F0FE") { showOnMap(lat, lon) }
        } else {
            sheet.text("La place est retenue toute seule à l'arrivée d'un guidage, ou quand Android Auto se déconnecte.", "#5F6368", 14f)
        }
        sheet.item("📍", "Je suis garé ici", "Retenir ma position actuelle", "#E6F4EA") {
            val loc = lastLocation
            if (loc == null) toast("Position GPS pas encore disponible") else {
                Nav.saveParking(loc.latitude, loc.longitude)
                toast("Place retenue")
            }
        }
        if (settings.hasParking) sheet.secondary("Oublier cette place") { Nav.clearParking() }
        sheet.show()
    }

    private fun walkTo(lat: Double, lon: Double) {
        val google = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon&mode=w"))
        try {
            startActivity(google)
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon(Ma voiture)")))
            } catch (e2: ActivityNotFoundException) {
                showOnMap(lat, lon)
            }
        }
    }

    // ---------- Cartes hors ligne ----------

    private fun showOfflineMaps() {
        val sheet = Sheet(this).title("📥  Cartes hors ligne", "Rues et noms de lieux gardés sur le téléphone")
        sheet.text("Le calcul d'itinéraire, le trafic et la recherche demandent toujours du réseau ; la carte, elle, s'affiche sans.", "#5F6368", 13f)
        sheet.sectionTitle("Télécharger")
        lastLocation?.let { loc ->
            sheet.item("📍", "Autour de moi", "Rayon de 30 km · environ 30 à 80 Mo", "#E6F4EA") {
                downloadOffline("Autour de ma position", listOf(Offline.boundsAround(loc.latitude, loc.longitude, 30.0)))
            }
        }
        if (settings.hasHome) sheet.item("🏠", "Autour de la maison", "${settings.homeName} · rayon de 30 km", "#E8F0FE") {
            downloadOffline("Maison", listOf(Offline.boundsAround(settings.homeLat, settings.homeLon, 30.0)))
        }
        route?.let { r ->
            sheet.item("🛣️", "Le long du trajet", "${destination?.name ?: "Destination"} · ${Geo.formatDistance(r.totalDistance)}", "#FEF7E0") {
                downloadOffline("Trajet vers ${destination?.name ?: "destination"}", Offline.routeBounds(r))
            }
        }
        val slot = sheet.slot()
        slot.addView(TextView(this).apply { text = "Recherche des cartes enregistrées…"; setTextColor(Color.parseColor("#5F6368")) })
        sheet.show()
        Offline.list(this) { zones ->
            slot.removeAllViews()
            if (zones.isEmpty()) {
                slot.addView(TextView(this).apply { text = "Aucune carte enregistrée pour l'instant."; setTextColor(Color.parseColor("#5F6368")) })
                return@list
            }
            zones.forEach { z ->
                val size = String.format(Locale.FRANCE, "%.0f Mo", z.bytes / 1_048_576.0)
                slot.addView(sheet.infoRow("🗂️", "${z.name}\n$size${if (z.complete) "" else " · incomplète"} · toucher pour supprimer") {
                    Sheet(this).title("Supprimer « ${z.name} » ?", size)
                        .primary("Supprimer", "#D93025") { Offline.delete(z) { toast("Carte supprimée") } }
                        .secondary("Annuler") {}
                        .show()
                })
            }
        }
    }

    private fun downloadOffline(name: String, boxes: List<LatLngBounds>) {
        toast("Téléchargement de « $name »…")
        Offline.download(this, name, boxes, progress = { done, total ->
            if (total > 1 && done > 0 && done < total) toast("« $name » : $done / $total")
        }) { error ->
            toast(if (error == null) "« $name » disponible hors ligne" else "« $name » : téléchargement incomplet")
        }
    }

    private fun switchRow(label: String, checked: Boolean) = Switch(this).apply {
        text = label
        textSize = 16f
        isChecked = checked
        setTextColor(Color.parseColor("#202124"))
        setPadding(dp(4), dp(12), dp(4), dp(12))
    }

    // ---------- Voix ----------

    private fun say(text: String) = Nav.say(text)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---------- Cycle de vie de la carte ----------

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        refreshAgenda()
        updateMuteButton()
        main.removeCallbacks(nightCheck)
        main.post(nightCheck)
    }
    override fun onPause() { super.onPause(); mapView.onPause(); main.removeCallbacks(nightCheck) }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    @Deprecated("Gestion du bouton retour")
    override fun onBackPressed() {
        when {
            searchResults.visibility == View.VISIBLE -> hideSearchResults()
            navigating -> Sheet(this)
                .title("Arrêter le guidage ?")
                .primary("Arrêter", "#D93025") { stopNavigation() }
                .secondary("Continuer") {}
                .show()
            route != null -> clearRoute()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onDestroy() {
        Nav.removeListener(this)
        Voice.onCloudError = null
        Nav.detach(this)
        io.shutdownNow()
        layerIo.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }
}
