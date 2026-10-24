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
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
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

class MainActivity : Activity(), LocationListener, TextToSpeech.OnInitListener {

    companion object {
        private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
        private const val REQ_LOCATION = 1
        private const val REQ_CALENDAR = 2
        private const val REQ_IMPORT = 3
        private const val OFF_ROUTE_METERS = 50.0
        private const val ALERT_AHEAD_METERS = 800.0
        private const val FREE_DRIVE_ALERT_METERS = 500.0
        private const val INCIDENTS_REFRESH_MS = 15 * 60_000L
        private const val ETA_REFRESH_MS = 10 * 60_000L
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

    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: Settings
    private lateinit var alertStore: AlertStore
    private lateinit var poiStore: PoiStore
    private lateinit var recentStore: RecentStore
    private lateinit var lufopStore: LufopStore
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var insetTop = 0
    private var insetBottom = 0

    private var lastLocation: Location? = null
    private var searchPlaces: List<Place> = emptyList()
    private var destination: Place? = null
    private var route: Route? = null
    private var navigating = false
    private var segmentHint = 0
    private var offRouteCount = 0
    private var rerouting = false
    private var lastRerouteAt = 0L
    private val spoken = HashSet<String>()

    /** Durée de référence (trafic compris si dispo) pour une distance restante de référence. */
    private var etaSeconds = 0.0
    private var etaDistance = 0.0
    private var trafficDelay = 0.0
    private var lastEtaAt = 0L

    /** Radars et bornes OSM autour de la zone utile. */
    private var osmAlerts: List<Alert> = emptyList()
    private var osmCenter: Location? = null
    private var osmLoading = false
    /** Incidents TomTom (accidents, bouchons, travaux...). */
    private var incidents: Map<String, Alert> = emptyMap()
    private var lastIncidentsAt = 0L
    private var incidentsCenter: Location? = null
    /** McDonald's et Burger King connus (autour de soi et le long du trajet). */
    private var brandPlaces: Map<String, NearbyPlace> = emptyMap()
    private var brandsCenter: Location? = null
    /** Derniers résultats « à proximité » affichés sur la carte. */
    private var nearbyShown: List<NearbyPlace> = emptyList()
    /** Étape facultative (station, resto) avant la destination. */
    private var stop: Place? = null
    private var agendaEvents: List<AgendaEvent> = emptyList()

    /** Alertes situées sur l'itinéraire, avec leur position (m) depuis le départ. */
    private var routeAlerts: List<Pair<Alert, Double>> = emptyList()

    private val ticker = object : Runnable {
        override fun run() {
            periodicRefresh()
            main.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        alertStore = AlertStore(this)
        poiStore = PoiStore(this)
        recentStore = RecentStore(this)
        lufopStore = LufopStore(this)
        bindViews()
        setupEdgeToEdge()
        updateTrafficButton()

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isLogoEnabled = false
            applyMapMargins()
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                style = s
                setupLayers(s)
                updateTrafficLayer()
                enableLocation()
                refreshAlertLayer()
                refreshPoiLayer()
                refreshBrandLayer()
            }
            m.addOnMapLongClickListener { latLng ->
                if (!navigating) showLongPressMenu(latLng.latitude, latLng.longitude)
                true
            }
            m.addOnMapClickListener { latLng ->
                hideSearchResults()
                handleMapTap(latLng)
            }
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(46.6, 2.4), 5.0))
        }

        tts = TextToSpeech(this, this)
        requestLocationPermission()
        io.execute {
            importBundledRadars()
            lufopStore.all() // préchargement du cache
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (settings.hasLufop && settings.lufopLastUpdate != today) updateFromLufopApi(manual = false)
        main.postDelayed(ticker, 60_000)
    }

    private fun bindViews() {
        mapView = findViewById(R.id.mapView)
        topContainer = findViewById(R.id.topContainer)
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
        findViewById<View>(R.id.btnStart).setOnClickListener { startNavigation() }
        findViewById<View>(R.id.btnCancel).setOnClickListener { clearRoute() }
        findViewById<View>(R.id.btnStop).setOnClickListener { stopNavigation() }
        findViewById<View>(R.id.btnWaze).setOnClickListener { openInWaze() }
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

    private fun setupLayers(s: Style) {
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

    /** Toutes les alertes connues : radars (Lufop sinon OSM), bornes, incidents TomTom, signalements perso. */
    private fun allAlerts(): List<Alert> {
        val lufop = lufopStore.all()
        val hasImportedRadars = lufop.any { it.type != AlertType.BORNE }
        val osm = osmAlerts.filter { it.type == AlertType.BORNE || !hasImportedRadars }
        return (lufop + osm + incidents.values + alertStore.all())
            .filter { it.type != AlertType.BORNE || settings.showChargers }
    }

    private fun isAnnounced(a: Alert): Boolean {
        if (!a.type.announce) return false
        val radar = a.type == AlertType.RADAR_FIXE || a.type == AlertType.RADAR_FEU || a.type == AlertType.RADAR_MOBILE
        return !radar || settings.announceRadars
    }

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

    private fun refreshAlertLayer() {
        val src = style?.getSourceAs<GeoJsonSource>("alerts-src") ?: return
        val features = allAlerts().map { a ->
            Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat)).also {
                it.addStringProperty("icon", "alert-${a.type.name}")
                // Libellé court : la vitesse pour un radar, sinon rien (le pictogramme suffit)
                it.addStringProperty("label", a.detail ?: "")
                it.addStringProperty("id", a.id)
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
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
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.latitude(), p.longitude()), m.cameraPosition.zoom + 2))
                return true
            }
        }
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
            else -> "Mon signalement"
        }
        val sheet = Sheet(this).title("${alertIcon(a.type)}  ${a.title}", listOfNotNull(source, distanceLabel(a.lat, a.lon)).joinToString(" · "))
        a.description?.let { sheet.text(it) }
        if (!navigating) sheet.primary("▲  Y aller") { setDestination(Place(a.title, a.description ?: "", a.lat, a.lon)) }
        if (a.source == "moi") sheet.secondary("Supprimer ce signalement") {
            alertStore.remove(a.id)
            refreshAlertLayer()
            computeRouteAlerts()
        }
        sheet.show()
    }

    // ---------- Trafic TomTom ----------

    private fun updateTrafficButton() {
        // Le trafic se règle désormais depuis le menu ☰
    }

    private fun toggleTraffic() {
        if (!settings.hasTomTom) {
            Sheet(this)
                .title("🚦  Trafic en temps réel")
                .text("Ajoutez votre clé TomTom gratuite dans les Réglages pour afficher le trafic, les bouchons et les incidents.")
                .primary("Ouvrir les Réglages") { showSettings() }
                .show()
            return
        }
        settings.trafficOn = !settings.trafficOn
        updateTrafficButton()
        updateTrafficLayer()
        if (settings.trafficOn) {
            lastIncidentsAt = 0
            periodicRefresh()
        } else {
            incidents = emptyMap()
            refreshAlertLayer()
            computeRouteAlerts()
        }
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

    private fun trafficActive() = settings.trafficOn && settings.hasTomTom

    private fun loadIncidents(boxes: List<DoubleArray>) {
        if (!trafficActive() || boxes.isEmpty()) return
        lastIncidentsAt = System.currentTimeMillis()
        val key = settings.tomtomKey
        io.execute {
            val found = ArrayList<Alert>()
            var error: String? = null
            for (b in boxes) {
                try {
                    found += TomTom.incidents(key, b[0], b[1], b[2], b[3])
                } catch (e: Exception) {
                    error = e.message
                }
            }
            main.post {
                incidents = incidents + found.associateBy { it.id }
                refreshAlertLayer()
                computeRouteAlerts()
                if (error != null && found.isEmpty()) toast("Incidents TomTom indisponibles : $error")
            }
        }
    }

    private fun refreshEta(from: Location) {
        val dest = destination ?: return
        val r = route ?: return
        if (!trafficActive()) return
        lastEtaAt = System.currentTimeMillis()
        val key = settings.tomtomKey
        val viaStop = stop
        val remaining = if (navigating) (r.totalDistance - r.project(from.latitude, from.longitude, segmentHint).along) else r.totalDistance
        io.execute {
            try {
                val eta = TomTom.eta(key, from.latitude, from.longitude, dest.lat, dest.lon, listOfNotNull(viaStop))
                main.post {
                    if (route !== r) return@post
                    etaSeconds = eta.travelTime
                    etaDistance = remaining.coerceAtLeast(1.0)
                    trafficDelay = eta.delay
                    if (!navigating) showRouteSummary(r)
                }
            } catch (e: Exception) {
                // on garde la durée Valhalla
            }
        }
    }

    /** Toutes les minutes : incidents et heure d'arrivée rafraîchis selon les besoins. */
    private fun periodicRefresh() {
        val loc = lastLocation ?: return
        if (!trafficActive()) return
        val now = System.currentTimeMillis()
        val r = route
        if (navigating && r != null) {
            if (now - lastIncidentsAt > INCIDENTS_REFRESH_MS) {
                val along = r.project(loc.latitude, loc.longitude, segmentHint).along
                loadIncidents(TomTom.boxesAlong(r, along, along + 90_000, maxBoxes = 2))
            }
            if (now - lastEtaAt > ETA_REFRESH_MS) refreshEta(loc)
        } else if (!navigating) {
            val center = incidentsCenter
            val moved = center == null || center.distanceTo(loc) > 15_000
            if (moved || now - lastIncidentsAt > INCIDENTS_REFRESH_MS) {
                incidentsCenter = loc
                loadIncidents(listOf(doubleArrayOf(loc.latitude - 0.3, loc.longitude - 0.45, loc.latitude + 0.3, loc.longitude + 0.45)))
            }
        }
    }

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
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_LOCATION)
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

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        if (LocationManager.GPS_PROVIDER in providers) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        }
        if (LocationManager.NETWORK_PROVIDER in providers) {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, this, Looper.getMainLooper())
        }
        val last = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        if (last != null) onLocationChanged(last)
    }

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
        "moto" -> R.drawable.pos_moto
        "camion" -> R.drawable.pos_truck
        else -> null
    }

    /** Icône de position : flèche bleue d'origine, ou véhicule qui tourne avec le cap. */
    private fun positionOptions(): LocationComponentOptions {
        val icon = positionIconRes() ?: return LocationComponentOptions.builder(this).build()
        return LocationComponentOptions.builder(this)
            .gpsDrawable(icon)
            .foregroundDrawable(icon)
            .foregroundDrawableStale(icon)
            .backgroundDrawable(R.drawable.pos_none)
            .backgroundDrawableStale(R.drawable.pos_none)
            .bearingDrawable(R.drawable.pos_none)
            .accuracyAlpha(0.1f)
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

    override fun onLocationChanged(location: Location) {
        val previous = lastLocation
        // On ignore la position réseau (imprécise) si le GPS a répondu récemment.
        if (location.provider == LocationManager.NETWORK_PROVIDER && previous != null &&
            previous.provider == LocationManager.GPS_PROVIDER && location.time - previous.time < 10_000
        ) return
        val first = previous == null
        lastLocation = location
        if (locationReady) map?.locationComponent?.forceLocationUpdate(location)
        if (first && locationReady && !navigating) {
            map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 14.0))
        }
        speedText.text = if (location.hasSpeed()) (location.speed * 3.6f).toInt().toString() else "0"

        if (navigating) updateNavigation(location) else checkFreeDriveAlerts(location)
        maybeLoadOsmAround(location)
        maybeLoadBrandsAround(location)
        if (first) periodicRefresh()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Requis sur les anciennes versions d'Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    private fun recenter() {
        val m = map ?: return
        val loc = lastLocation ?: run { toast("Position GPS pas encore disponible"); return }
        if (navigating && locationReady) {
            m.locationComponent.cameraMode = CameraMode.TRACKING_GPS
            m.locationComponent.zoomWhileTracking(17.0)
            m.locationComponent.tiltWhileTracking(50.0)
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

    private fun setDestination(place: Place) {
        val loc = lastLocation ?: run { toast("Position GPS pas encore disponible"); return }
        destination = place
        if (place.name != "Point sur la carte") recentStore.add(place)
        stop = null
        agendaChip.visibility = View.GONE
        drawDestination(place)
        routeTitle.text = place.name
        routeSummary.text = "Calcul de l'itinéraire…"
        routeDetails.text = place.address
        routePanel.visibility = View.VISIBLE
        updateBottomSpacer()
        computeRoute(loc, place, isReroute = false)
    }

    private fun computeRoute(from: Location, to: Place, isReroute: Boolean) {
        rerouting = true
        val via = listOfNotNull(stop)
        val heading = if (from.hasBearing() && from.hasSpeed() && from.speed > 3) from.bearing else null
        io.execute {
            try {
                val r = Net.route(from.latitude, from.longitude, to.lat, to.lon, heading, via)
                main.post { onRouteReady(r, isReroute) }
                loadOsmForRoute(r)
                loadBrandsForRoute(r)
            } catch (e: Exception) {
                main.post {
                    rerouting = false
                    if (isReroute) {
                        toast("Recalcul impossible, nouvel essai bientôt")
                    } else {
                        routeSummary.text = "Itinéraire introuvable"
                        routeDetails.text = e.message ?: ""
                    }
                }
            }
        }
    }

    private fun onRouteReady(r: Route, isReroute: Boolean) {
        rerouting = false
        lastRerouteAt = System.currentTimeMillis()
        route = r
        segmentHint = 0
        offRouteCount = 0
        etaSeconds = r.totalTime
        etaDistance = r.totalDistance.coerceAtLeast(1.0)
        trafficDelay = 0.0
        spoken.removeAll { it.startsWith("m-") }
        drawRoute(r)
        computeRouteAlerts()
        lastLocation?.let { refreshEta(it) }
        if (isReroute) {
            say("Nouvel itinéraire")
            if (trafficActive()) loadIncidents(TomTom.boxesAlong(r, 0.0, 90_000.0, maxBoxes = 2))
            return
        }
        if (trafficActive()) loadIncidents(TomTom.boxesAlong(r))
        showRouteSummary(r)
        routePanel.post { fitRoute(r) }
    }

    private fun showRouteSummary(r: Route) {
        val arrival = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(System.currentTimeMillis() + (etaSeconds * 1000).toLong()))
        routeSummary.text = "${Geo.formatDuration(etaSeconds)} (${Geo.formatDistance(r.totalDistance)})"
        val traffic = when {
            !trafficActive() -> ""
            trafficDelay >= 60 -> " · dont ${Geo.formatDuration(trafficDelay)} de bouchons"
            else -> " · trafic fluide"
        }
        routeDetails.text = "Arrivée vers $arrival$traffic"
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

    private fun clearRoute() {
        stopNavigation()
        route = null
        destination = null
        stop = null
        routeAlerts = emptyList()
        drawRoute(null)
        drawDestination(null)
        routePanel.visibility = View.GONE
        updateBottomSpacer()
        updateAgendaChip()
    }

    private fun openInWaze() {
        val d = destination ?: return
        openInWaze(d.lat, d.lon)
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

    private fun startNavigation() {
        val r = route ?: return
        val m = map ?: return
        navigating = true
        spoken.clear()
        routePanel.visibility = View.GONE
        navPanel.visibility = View.VISIBLE
        maneuverBanner.visibility = View.VISIBLE
        findViewById<View>(R.id.searchBar).visibility = View.GONE
        hideSearchResults()
        updateBottomSpacer()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (locationReady) {
            m.locationComponent.renderMode = RenderMode.GPS
            m.locationComponent.cameraMode = CameraMode.TRACKING_GPS
            m.locationComponent.zoomWhileTracking(17.0)
            m.locationComponent.tiltWhileTracking(50.0)
        }
        r.maneuvers.firstOrNull()?.let { say(it.verbalPre ?: it.instruction) }
        lastLocation?.let { updateNavigation(it) }
    }

    private fun stopNavigation() {
        if (!navigating) return
        navigating = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        maneuverBanner.visibility = View.GONE
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
                m.locationComponent.cameraMode = CameraMode.NONE
                m.locationComponent.renderMode = idleRenderMode()
            }
            m.animateCamera(CameraUpdateFactory.tiltTo(0.0))
        }
        tts?.stop()
    }

    private fun updateNavigation(loc: Location) {
        val r = route ?: return
        val dest = destination ?: return
        val p = r.project(loc.latitude, loc.longitude, segmentHint)
        segmentHint = p.segment

        // Hors itinéraire : on recalcule après 3 positions fiables consécutives.
        if (p.offRoute > OFF_ROUTE_METERS && loc.accuracy < 40) offRouteCount++ else offRouteCount = 0
        if (offRouteCount >= 3 && !rerouting && System.currentTimeMillis() - lastRerouteAt > 8000) {
            offRouteCount = 0
            computeRoute(loc, dest, isReroute = true)
        }

        stop?.let { st ->
            if (Geo.distance(loc.latitude, loc.longitude, st.lat, st.lon) < 50) {
                stop = null
                say("Vous êtes arrivé à votre étape")
            }
        }

        val remaining = (r.totalDistance - p.along).coerceAtLeast(0.0)
        if (remaining < 30 || Geo.distance(loc.latitude, loc.longitude, dest.lat, dest.lon) < 25) {
            say("Vous êtes arrivé à destination")
            stopNavigation()
            clearRoute()
            return
        }

        // Prochaine manœuvre
        val index = r.maneuvers.indexOfFirst { r.cumulative[it.beginIndex] > p.along + 3 }
        if (index >= 0) {
            val man = r.maneuvers[index]
            val dist = r.cumulative[man.beginIndex] - p.along
            maneuverArrow.text = man.arrow
            maneuverDistance.text = Geo.formatDistance(dist)
            maneuverText.text = man.instruction
            val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 50.0
            val farThreshold = if (speedKmh > 90) 1500.0 else 500.0
            val nearThreshold = (speedKmh / 3.6 * 7).coerceIn(40.0, 250.0)
            if (dist <= farThreshold && dist > farThreshold * 0.5 && dist > nearThreshold * 2 && spoken.add("m-$index-far")) {
                say("Dans ${Geo.spokenDistance(dist)}, ${man.verbalAlert ?: man.instruction}")
            }
            if (dist <= nearThreshold && spoken.add("m-$index-near")) {
                say(man.verbalPre ?: man.instruction)
            }
        }

        // Durée restante : référence (trafic compris si dispo) au prorata de la distance
        val remainingTime = etaSeconds * remaining / etaDistance
        val arrival = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(System.currentTimeMillis() + (remainingTime * 1000).toLong()))
        navEta.text = "$arrival · ${Geo.formatDuration(remainingTime)}"
        val traffic = if (trafficActive() && trafficDelay >= 60) " · bouchons +${Geo.formatDuration(trafficDelay)}" else ""
        navRemaining.text = "${Geo.formatDistance(remaining)} restants$traffic"

        // Alertes devant soi sur l'itinéraire
        val ahead = routeAlerts
            .map { (a, along) -> a to (along - p.along) }
            .filter { it.second in -10.0..ALERT_AHEAD_METERS }
            .minByOrNull { it.second }
        showAlert(ahead?.first, ahead?.second)
    }

    /** Place les alertes sur l'itinéraire (en arrière-plan : il peut y en avoir des milliers). */
    private fun computeRouteAlerts() {
        val r = route ?: run { routeAlerts = emptyList(); return }
        val alerts = allAlerts().filter { isAnnounced(it) }
        io.execute {
            val placed = alerts.mapNotNull { a -> r.projectNear(a.lat, a.lon, 40.0)?.let { a to it.along } }
            main.post { if (route === r) routeAlerts = placed }
        }
    }

    /** Hors guidage : alerte si un danger est proche et devant (selon le cap). */
    private fun checkFreeDriveAlerts(loc: Location) {
        val moving = loc.hasSpeed() && loc.speed > 4
        if (!moving) {
            showAlert(null, null)
            return
        }
        val dLat = 0.006
        val candidate = allAlerts()
            .filter { Math.abs(it.lat - loc.latitude) < dLat && Math.abs(it.lon - loc.longitude) < dLat * 1.6 && isAnnounced(it) }
            .map { it to Geo.distance(loc.latitude, loc.longitude, it.lat, it.lon) }
            .filter { (a, d) ->
                d < FREE_DRIVE_ALERT_METERS && (!loc.hasBearing() ||
                    Geo.angleDiff(loc.bearing.toDouble(), Geo.bearing(loc.latitude, loc.longitude, a.lat, a.lon)) < 50)
            }
            .minByOrNull { it.second }
        showAlert(candidate?.first, candidate?.second)
    }

    private fun showAlert(alert: Alert?, distance: Double?) {
        if (alert == null || distance == null) {
            alertBanner.visibility = View.GONE
            return
        }
        alertBanner.text = "⚠  ${alert.title} dans ${Geo.formatDistance(distance.coerceAtLeast(0.0))}"
        alertBanner.setBackgroundResource(R.drawable.bg_alert)
        alertBanner.background.mutate().setTint(Color.parseColor(alert.type.color))
        alertBanner.visibility = View.VISIBLE
        if (spoken.add("a-${alert.id}")) {
            val limit = if (alert.detail != null && alert.type != AlertType.BOUCHON) ", limité à ${alert.detail}" else ""
            val length = if (alert.type == AlertType.BOUCHON && alert.detail != null) " sur ${alert.detail}" else ""
            say("${alert.type.spoken}$length dans ${Geo.spokenDistance(distance.coerceAtLeast(50.0))}$limit")
        }
    }

    // ---------- Radars, bornes et signalements ----------

    private fun loadOsmForRoute(r: Route) {
        if (r.points.isEmpty()) return
        val pad = 0.02
        val south = r.points.minOf { it[0] } - pad
        val north = r.points.maxOf { it[0] } + pad
        val west = r.points.minOf { it[1] } - pad
        val east = r.points.maxOf { it[1] } + pad
        // Sans Lufop, on prend les radars OSM sur tout l'itinéraire ; les bornes seulement autour de l'arrivée.
        try {
            val found = ArrayList<Alert>()
            if (lufopStore.all().none { it.type != AlertType.BORNE }) found += Net.osmAlerts(south, west, north, east, chargers = false)
            if (settings.showChargers) {
                val end = r.points.last()
                found += Net.osmAlerts(end[0] - 0.05, end[1] - 0.075, end[0] + 0.05, end[1] + 0.075, chargers = true)
                    .filter { it.type == AlertType.BORNE }
            }
            main.post { mergeOsm(found) }
        } catch (e: Exception) {
            main.post { toast("Radars OpenStreetMap indisponibles pour cet itinéraire") }
        }
    }

    private fun maybeLoadOsmAround(loc: Location) {
        val center = osmCenter
        if (osmLoading) return
        if (center != null && center.distanceTo(loc) < 5000) return
        osmLoading = true
        osmCenter = loc
        val d = 0.1 // environ 10 km autour
        val chargers = settings.showChargers
        io.execute {
            try {
                val found = Net.osmAlerts(loc.latitude - d, loc.longitude - d * 1.5, loc.latitude + d, loc.longitude + d * 1.5, chargers)
                main.post { mergeOsm(found) }
            } catch (e: Exception) {
                main.post { osmCenter = null }
            } finally {
                main.post { osmLoading = false }
            }
        }
    }

    private fun mergeOsm(found: List<Alert>) {
        osmAlerts = (osmAlerts + found).distinctBy { it.id }
        refreshAlertLayer()
        computeRouteAlerts()
    }

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
            refreshAlertLayer()
            computeRouteAlerts()
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
                    refreshAlertLayer()
                    computeRouteAlerts()
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
                    refreshAlertLayer()
                    computeRouteAlerts()
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
                spoken.add("a-${alert.id}") // pas d'annonce pour sa propre alerte
                refreshAlertLayer()
                computeRouteAlerts()
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

    private fun showAddPoiDialog(lat: Double, lon: Double) {
        val sheet = Sheet(this).title("⭐  Nouveau POI", distanceLabel(lat, lon))
        val name = sheet.input("Nom du POI")
        // Nom proposé d'après l'adresse, modifiable
        io.execute {
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
        val icon = when (n.brand) { "mcdo" -> "🍟"; "bk" -> "👑"; else -> "🍽️" }
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
        val googleKey = settings.googleKey
        io.execute {
            val osm = try { Nearby.osmDetails(n.name, n.lat, n.lon) } catch (e: Exception) { null }
            val google = if (googleKey.isBlank()) null else try { Nearby.google(googleKey, n.name, n.lat, n.lon) } catch (e: Exception) { null }
            main.post {
                infoBox.removeAllViews()
                fillPlaceInfo(sheet, infoBox, n, osm, google)
            }
        }
    }

    private fun fillPlaceInfo(sheet: Sheet, box: LinearLayout, n: NearbyPlace, osm: PlaceInfo?, google: GoogleInfo?) {
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
        if (phone == null && website == null && hours == null && google?.rating == null) {
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

    private fun addStop(place: Place) {
        val loc = lastLocation ?: return
        val dest = destination ?: return
        stop = place
        say("Étape ajoutée")
        computeRoute(loc, dest, isReroute = true)
    }

    // ---------- Menu et réglages ----------

    private fun showMainMenu() {
        val next = agendaEvents.firstOrNull()
        val trafficState = when {
            !settings.hasTomTom -> "Clé TomTom à ajouter"
            settings.trafficOn -> "Affiché · toucher pour masquer"
            else -> "Masqué · toucher pour afficher"
        }
        Sheet(this)
            .title("GPS Perso")
            .sectionTitle("Aller quelque part")
            .item("📅", "Mon agenda", next?.let { "${it.whenLabel()} · ${it.title}" } ?: "Rendez-vous avec une adresse", "#E8F0FE") { showAgenda() }
            .item("🕘", "Adresses récentes", null, "#E8F0FE") { showRecents() }
            .item("⭐", "Mes POIs", "${poiStore.all().size} lieux enregistrés", "#FEF7E0") { showPoiList() }
            .sectionTitle("Autour de moi / sur le trajet")
            .item("⛽", "Stations-service", "Prix du carburant", "#FCE8E6") { showStations() }
            .item("🍟", "McDonald's et Burger King", null, "#FFF3C4") { showBrands() }
            .item("🍽️", "Restaurants", null, "#FDE7DD") { showRestaurants() }
            .sectionTitle("Carte")
            .item("🚦", "Trafic en temps réel", trafficState, "#E6F4EA") { toggleTraffic() }
            .item("🆕", "Nouveautés radars", lufopStore.lastImport?.let { "Base du $it" } ?: "Aucune base importée", "#F3E8FD") { showLufopNews() }
            .item("⚙️", "Réglages", null, "#F1F3F4") { showSettings() }
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
    private fun showSettings() {
        val iconLabels = mapOf(
            "fleche" to "Flèche bleue", "voiture_rouge" to "Voiture rouge", "voiture_noire" to "Voiture noire",
            "voiture_bleue" to "Voiture bleue", "moto" to "Moto", "camion" to "Camion",
        )
        val fuelLabel = Nearby.fuels.firstOrNull { it.first == settings.fuel }?.second ?: settings.fuel
        val count = lufopStore.all().size
        Sheet(this).title("⚙️  Réglages")
            .item("🏠", "Ma maison", if (settings.hasHome) settings.homeName else "Non définie", "#E8F0FE") { showHomeSettings() }
            .item("🚗", "Mon véhicule", "${iconLabels[settings.positionIcon]} · $fuelLabel", "#E8F0FE") { showVehicleSettings() }
            .item("📷", "Radars et alertes", if (count > 0) "$count points Lufop · annonces ${if (settings.announceRadars) "activées" else "coupées"}" else "Lufop à configurer", "#F3E8FD") { showRadarSettings() }
            .item("🗺️", "Affichage de la carte", "Bornes, McDo / Burger King", "#E6F4EA") { showDisplaySettings() }
            .item("🔑", "Services connectés", listOfNotNull(
                if (settings.hasTomTom) "Trafic TomTom ✓" else "Trafic TomTom ✗",
                if (settings.hasGoogle) "Avis Google ✓" else "Avis Google ✗",
            ).joinToString(" · "), "#F1F3F4") { showKeySettings() }
            .show()
    }

    private fun showVehicleSettings() {
        val iconLabels = listOf(
            "fleche" to "Flèche bleue", "voiture_rouge" to "Voiture rouge", "voiture_noire" to "Voiture noire",
            "voiture_bleue" to "Voiture bleue", "moto" to "Moto", "camion" to "Camion",
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
        sheet.sectionTitle("Clé API Lufop")
        val key = sheet.input("Clé API Lufop", settings.lufopKey)
        fun save() {
            settings.announceRadars = announce.isChecked
            settings.lufopKey = key.text.toString()
            computeRouteAlerts()
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
                else -> name
            }
            sheet.item("🗂️", label, "$size points", "#F3E8FD") {
                save()
                Sheet(this).title("Supprimer « $label » ?", "$size points")
                    .primary("Supprimer", "#D93025") {
                        lufopStore.remove(name)
                        refreshAlertLayer()
                        computeRouteAlerts()
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
        sheet.view(traffic).view(chargers).view(brands)
        sheet.onDismiss {
            settings.showChargers = chargers.isChecked
            settings.showBrands = brands.isChecked
            if (traffic.isChecked != settings.trafficOn) {
                settings.trafficOn = traffic.isChecked
                updateTrafficLayer()
                if (!traffic.isChecked) incidents = emptyMap()
                lastIncidentsAt = 0
                periodicRefresh()
            }
            refreshBrandLayer()
            osmCenter = null
            brandsCenter = null
            lastLocation?.let {
                maybeLoadOsmAround(it)
                maybeLoadBrandsAround(it)
            }
            refreshAlertLayer()
            computeRouteAlerts()
        }
        sheet.primary("Terminé") {}
        sheet.show()
    }

    private fun showKeySettings() {
        val sheet = Sheet(this).title("🔑  Services connectés", "Clés gratuites, gardées sur ce téléphone")
        sheet.sectionTitle("Trafic TomTom (developer.tomtom.com)")
        val tomtom = sheet.input("Clé API TomTom", settings.tomtomKey)
        sheet.sectionTitle("Notes et avis Google (facultatif)")
        sheet.text("Sans clé, le bouton « Voir les avis sur Google Maps » reste disponible sur chaque restaurant.", "#5F6368", 13f)
        val google = sheet.input("Clé API Google Places", settings.googleKey)
        sheet.primary("Enregistrer") {
            settings.tomtomKey = tomtom.text.toString()
            settings.googleKey = google.text.toString()
            updateTrafficLayer()
            lastIncidentsAt = 0
            periodicRefresh()
            toast("Clés enregistrées")
        }
        sheet.show()
    }

    private fun switchRow(label: String, checked: Boolean) = Switch(this).apply {
        text = label
        textSize = 16f
        isChecked = checked
        setTextColor(Color.parseColor("#202124"))
        setPadding(dp(4), dp(12), dp(4), dp(12))
    }

    // ---------- Voix ----------

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.FRANCE
            ttsReady = true
        }
    }

    private fun say(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ---------- Cycle de vie de la carte ----------

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume()
        mapView.onResume()
        refreshAgenda()
    }
    override fun onPause() { super.onPause(); mapView.onPause() }
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
        main.removeCallbacks(ticker)
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        tts?.shutdown()
        io.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }
}
