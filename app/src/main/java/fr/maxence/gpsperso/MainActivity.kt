package fr.maxence.gpsperso

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
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
    private lateinit var btnTraffic: TextView
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
        io.execute { lufopStore.all() } // préchargement du cache
        if (settings.hasLufop && lufopStore.needsSync()) syncLufop(manual = false)
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
        btnTraffic = findViewById(R.id.btnTraffic)
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
        btnTraffic.setOnClickListener { toggleTraffic() }
        findViewById<View>(R.id.btnFood).setOnClickListener { showFoodMenu() }
        findViewById<View>(R.id.btnFuel).setOnClickListener { showStations() }
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
        addPointLayers(s, "alerts", 7f)
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
        val osm = osmAlerts.filter { it.type == AlertType.BORNE || lufop.isEmpty() }
        return (lufop + osm + incidents.values + alertStore.all())
            .filter { it.type != AlertType.BORNE || settings.showChargers }
    }

    private fun isAnnounced(a: Alert): Boolean {
        if (!a.type.announce) return false
        val radar = a.type == AlertType.RADAR_FIXE || a.type == AlertType.RADAR_FEU || a.type == AlertType.RADAR_MOBILE
        return !radar || settings.announceRadars
    }

    private fun refreshAlertLayer() {
        val src = style?.getSourceAs<GeoJsonSource>("alerts-src") ?: return
        val features = allAlerts().map { a ->
            Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat)).also {
                it.addStringProperty("color", a.type.color)
                it.addStringProperty("label", a.title)
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
    private fun handleMapTap(latLng: LatLng): Boolean {
        val m = map ?: return false
        val point = m.projection.toScreenLocation(latLng)
        val hit = m.queryRenderedFeatures(point, "pois-layer", "brands-layer", "nearby-layer", "alerts-layer").firstOrNull() ?: return false
        val id = if (hit.hasProperty("id")) hit.getStringProperty("id") else return false
        (brandPlaces[id] ?: nearbyShown.firstOrNull { it.id == id })?.let { showNearbyActions(it); return true }
        poiStore.all().firstOrNull { it.id == id }?.let { showPoiActions(it); return true }
        allAlerts().firstOrNull { it.id == id }?.let { showAlertDetails(it); return true }
        return false
    }

    private fun showAlertDetails(a: Alert) {
        val source = when (a.source) {
            "lufop" -> "Lufop"
            "osm" -> "OpenStreetMap"
            "tomtom" -> "Trafic TomTom"
            else -> "Mon signalement"
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(a.title)
            .setMessage(listOfNotNull(a.description, "Source : $source").joinToString("\n\n"))
            .setPositiveButton("Fermer", null)
        if (a.source == "moi") {
            builder.setNegativeButton("Supprimer") { _, _ ->
                alertStore.remove(a.id)
                refreshAlertLayer()
                computeRouteAlerts()
            }
        }
        if (!navigating) {
            builder.setNeutralButton("Y aller") { _, _ -> setDestination(Place(a.title, a.description ?: "", a.lat, a.lon)) }
        }
        builder.show()
    }

    // ---------- Trafic TomTom ----------

    private fun updateTrafficButton() {
        btnTraffic.alpha = if (settings.trafficOn && settings.hasTomTom) 1f else 0.45f
    }

    private fun toggleTraffic() {
        if (!settings.hasTomTom) {
            AlertDialog.Builder(this)
                .setTitle("Trafic en temps réel")
                .setMessage("Ajoutez votre clé TomTom gratuite dans les Réglages pour afficher le trafic et les incidents.")
                .setPositiveButton("Réglages") { _, _ -> showSettings() }
                .setNegativeButton("Plus tard", null)
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
        val uri = Uri.parse("https://waze.com/ul?ll=${d.lat},${d.lon}&navigate=yes")
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
            if (lufopStore.all().isEmpty()) found += Net.osmAlerts(south, west, north, east, chargers = false)
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

    private fun syncLufop(manual: Boolean, forceFull: Boolean = false) {
        val key = settings.lufopKey
        if (key.isBlank()) return
        if (manual) toast("Mise à jour des radars Lufop…")
        io.execute {
            try {
                val result = lufopStore.sync(key, forceFull)
                main.post {
                    refreshAlertLayer()
                    computeRouteAlerts()
                    when {
                        result.full -> toast("Lufop : ${result.total} radars chargés")
                        result.added.isNotEmpty() -> toast("Lufop : ${result.added.size} nouveautés radar (menu ☰)")
                        manual -> toast("Lufop : aucune nouveauté")
                    }
                }
            } catch (e: Exception) {
                main.post {
                    if (manual) {
                        AlertDialog.Builder(this)
                            .setTitle("Erreur Lufop")
                            .setMessage(e.message ?: e.toString())
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        toast("Lufop indisponible : ${e.message?.take(80)}")
                    }
                }
            }
        }
    }

    private fun showReportDialog() {
        val loc = lastLocation ?: run { toast("Position GPS pas encore disponible"); return }
        showReportDialogAt(loc.latitude, loc.longitude)
    }

    private fun showReportDialogAt(lat: Double, lon: Double) {
        val types = AlertType.reportable
        AlertDialog.Builder(this)
            .setTitle("Que voulez-vous signaler ?")
            .setItems(types.map { it.label }.toTypedArray()) { _, which ->
                val alert = alertStore.add(types[which], lat, lon)
                spoken.add("a-${alert.id}") // pas d'annonce pour sa propre alerte
                refreshAlertLayer()
                computeRouteAlerts()
                toast("${alert.type.label} signalé")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ---------- POIs ----------

    private fun showLongPressMenu(lat: Double, lon: Double) {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Itinéraire vers ce point", "Ajouter un POI ici", "Signaler ici")) { _, which ->
                when (which) {
                    0 -> setDestination(Place("Point sur la carte", String.format(Locale.FRANCE, "%.5f, %.5f", lat, lon), lat, lon))
                    1 -> showAddPoiDialog(lat, lon)
                    2 -> showReportDialogAt(lat, lon)
                }
            }
            .show()
    }

    private fun showAddPoiDialog(lat: Double, lon: Double) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val name = EditText(this).apply {
            hint = "Nom du POI"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val categories = PoiCategory.entries
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, categories.map { it.label })
        }
        layout.addView(name)
        layout.addView(spinner)
        // Nom proposé d'après l'adresse, modifiable
        io.execute {
            val suggestion = try { Net.reverse(lat, lon) } catch (e: Exception) { null }
            if (suggestion != null) main.post { if (name.text.isEmpty()) name.setText(suggestion) }
        }
        AlertDialog.Builder(this)
            .setTitle("Nouveau POI")
            .setView(layout)
            .setPositiveButton("Enregistrer") { _, _ ->
                val label = name.text.toString().trim().ifBlank { categories[spinner.selectedItemPosition].label }
                poiStore.add(label, categories[spinner.selectedItemPosition], lat, lon)
                refreshPoiLayer()
                toast("POI « $label » ajouté")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showPoiList() {
        val loc = lastLocation
        val pois = poiStore.all().sortedBy { p -> loc?.let { Geo.distance(it.latitude, it.longitude, p.lat, p.lon) } ?: 0.0 }
        if (pois.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Mes POIs")
                .setMessage("Aucun POI pour l'instant. Faites un appui long sur la carte, ou utilisez « Ajouter ma position en POI ».")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = pois.map { p ->
            val dist = loc?.let { " · " + Geo.formatDistance(Geo.distance(it.latitude, it.longitude, p.lat, p.lon)) } ?: ""
            "${p.name}\n${p.category.label}$dist"
        }
        AlertDialog.Builder(this)
            .setTitle("Mes POIs")
            .setItems(labels.toTypedArray()) { _, which -> showPoiActions(pois[which]) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun showPoiActions(p: Poi) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("Y aller", "Voir sur la carte", "Supprimer")) { _, which ->
                when (which) {
                    0 -> if (!navigating) setDestination(p.toPlace()) else toast("Arrêtez d'abord le guidage")
                    1 -> map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 16.0))
                    2 -> {
                        poiStore.remove(p.id)
                        refreshPoiLayer()
                        toast("POI supprimé")
                    }
                }
            }
            .show()
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
        if (events.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Mon agenda")
                .setMessage("Aucun rendez-vous avec une adresse dans les 7 prochains jours.\n\nAstuce : renseignez le champ « Lieu » de vos rendez-vous dans Google Agenda.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Mon agenda (7 jours)")
            .setItems(events.map { "${it.whenLabel()} · ${it.title}\n${it.location}" }.toTypedArray()) { _, which ->
                goToAgendaEvent(events[which])
            }
            .setNegativeButton("Fermer", null)
            .show()
    }

    /** Cherche l'adresse du rendez-vous puis calcule l'itinéraire. */
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
        val d = 0.3
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
        AlertDialog.Builder(this)
            .setItems(arrayOf("McDonald's et Burger King", "Tous les restaurants")) { _, which ->
                if (which == 0) showBrands() else showRestaurants()
            }
            .show()
    }

    /** Points où chercher : devant soi sur le trajet pendant le guidage, sinon autour de soi. */
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
                val d = 0.3
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
        val labels = shown.map { (n, where) ->
            val star = when (n.brand) { "mcdo" -> "🍟 "; "bk" -> "👑 "; else -> "" }
            "$star${n.name} · $where" + (n.detail?.let { "\n$it" } ?: "")
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> showNearbyActions(shown[which].first) }
            .setNegativeButton("Fermer", null)
        if (sortableByPrice) {
            builder.setNeutralButton(if (byPrice) "Trier par distance" else "Moins chères d'abord") { _, _ ->
                showNearbyList(title, items, sortableByPrice = true, byPrice = !byPrice)
            }
        }
        builder.show()
    }

    private fun showNearbyActions(n: NearbyPlace) {
        val options = if (navigating) arrayOf("Faire un arrêt ici", "Remplacer la destination", "Voir sur la carte")
        else arrayOf("Y aller", "Enregistrer en POI", "Voir sur la carte")
        AlertDialog.Builder(this)
            .setTitle(n.name)
            .setMessage(n.detail)
            .setItems(options) { _, which ->
                when {
                    which == 2 -> {
                        if (navigating && locationReady) map?.locationComponent?.cameraMode = CameraMode.NONE
                        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(n.lat, n.lon), 16.0))
                    }
                    navigating && which == 0 -> addStop(n.toPlace())
                    navigating && which == 1 -> {
                        stopNavigation()
                        setDestination(n.toPlace())
                    }
                    which == 0 -> setDestination(n.toPlace())
                    else -> {
                        val category = if (n.kind == NearbyKind.STATION) PoiCategory.STATION else PoiCategory.RESTAURANT
                        poiStore.add(n.name, category, n.lat, n.lon)
                        refreshPoiLayer()
                        toast("POI « ${n.name} » ajouté")
                    }
                }
            }
            .show()
    }

    /** Ajoute une étape avant la destination et recalcule le trajet. */
    private fun addStop(place: Place) {
        val loc = lastLocation ?: return
        val dest = destination ?: return
        stop = place
        say("Étape ajoutée")
        computeRoute(loc, dest, isReroute = true)
    }

    // ---------- Menu et réglages ----------

    private fun showMainMenu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Mon agenda", "Mes POIs", "Ajouter ma position en POI", "Stations-service", "Restaurants", "McDonald's et Burger King", "Nouveautés radars Lufop", "Réglages")) { _, which ->
                when (which) {
                    0 -> showAgenda()
                    1 -> showPoiList()
                    2 -> lastLocation?.let { showAddPoiDialog(it.latitude, it.longitude) } ?: toast("Position GPS pas encore disponible")
                    3 -> showStations()
                    4 -> showRestaurants()
                    5 -> showBrands()
                    6 -> showLufopNews()
                    7 -> showSettings()
                }
            }
            .show()
    }

    private fun showLufopNews() {
        if (!settings.hasLufop) {
            toast("Ajoutez d'abord votre clé Lufop dans les Réglages")
            return
        }
        val recent = lufopStore.recent()
        val sync = lufopStore.lastSync?.let { "Dernière mise à jour : $it" } ?: "Jamais mis à jour"
        if (recent.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Nouveautés radars")
                .setMessage("Aucune nouveauté lors de la dernière mise à jour.\n\n$sync\n${lufopStore.all().size} radars en mémoire.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Mettre à jour") { _, _ -> syncLufop(manual = true) }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Nouveautés radars (${recent.size})")
            .setItems(recent.map { "${it.title}\n${it.description ?: ""}" }.toTypedArray()) { _, which ->
                val a = recent[which]
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(a.lat, a.lon), 16.0))
            }
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun showSettings() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setPadding(0, dp(12), 0, 0)
            setTextColor(Color.parseColor("#5F6368"))
        }
        val tomtom = EditText(this).apply {
            setText(settings.tomtomKey)
            hint = "Clé API TomTom"
            isSingleLine = true
        }
        val lufop = EditText(this).apply {
            setText(settings.lufopKey)
            hint = "Clé API Lufop"
            isSingleLine = true
        }
        val chargers = CheckBox(this).apply {
            text = "Afficher les bornes de recharge"
            isChecked = settings.showChargers
        }
        val radars = CheckBox(this).apply {
            text = "Annoncer les radars"
            isChecked = settings.announceRadars
        }
        val lufopInfo = lufopStore.lastSync?.let { "Lufop : ${lufopStore.all().size} radars, mis à jour le $it" }
            ?: "Lufop : pas encore téléchargé"
        layout.addView(label("Trafic en temps réel (developer.tomtom.com)"))
        layout.addView(tomtom)
        layout.addView(label("Radars Lufop (api.lufop.net)"))
        layout.addView(lufop)
        layout.addView(label(lufopInfo))
        layout.addView(chargers)
        layout.addView(radars)
        val brands = CheckBox(this).apply {
            text = "Afficher McDonald's et Burger King"
            isChecked = settings.showBrands
        }
        layout.addView(brands)
        val iconKeys = listOf("fleche", "voiture_rouge", "voiture_noire", "voiture_bleue", "moto", "camion")
        val iconLabels = listOf("Flèche bleue", "Voiture rouge", "Voiture noire", "Voiture bleue", "Moto", "Camion")
        val iconSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, iconLabels)
            setSelection(iconKeys.indexOf(settings.positionIcon).coerceAtLeast(0))
        }
        layout.addView(label("Icône de ma position"))
        layout.addView(iconSpinner)
        val fuelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, Nearby.fuels.map { it.second })
            setSelection(Nearby.fuels.indexOfFirst { it.first == settings.fuel }.coerceAtLeast(0))
        }
        layout.addView(label("Mon carburant"))
        layout.addView(fuelSpinner)

        fun save(): Boolean {
            val lufopChanged = lufop.text.toString().trim() != settings.lufopKey
            settings.tomtomKey = tomtom.text.toString()
            settings.lufopKey = lufop.text.toString()
            settings.showChargers = chargers.isChecked
            settings.announceRadars = radars.isChecked
            settings.showBrands = brands.isChecked
            settings.positionIcon = iconKeys[iconSpinner.selectedItemPosition.coerceAtLeast(0)]
            settings.fuel = Nearby.fuels[fuelSpinner.selectedItemPosition.coerceAtLeast(0)].first
            applyPositionIcon()
            refreshBrandLayer()
            updateTrafficButton()
            updateTrafficLayer()
            osmCenter = null
            lastLocation?.let { maybeLoadOsmAround(it) }
            lastIncidentsAt = 0
            periodicRefresh()
            refreshAlertLayer()
            computeRouteAlerts()
            return lufopChanged
        }

        AlertDialog.Builder(this)
            .setTitle("Réglages")
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton("Enregistrer") { _, _ ->
                if (save() && settings.hasLufop) syncLufop(manual = true, forceFull = true)
            }
            .setNeutralButton("Mettre à jour Lufop") { _, _ ->
                save()
                if (settings.hasLufop) syncLufop(manual = true) else toast("Clé Lufop manquante")
            }
            .setNegativeButton("Annuler", null)
            .show()
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
            navigating -> AlertDialog.Builder(this)
                .setMessage("Arrêter le guidage ?")
                .setPositiveButton("Arrêter") { _, _ -> stopNavigation() }
                .setNegativeButton("Continuer", null)
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
