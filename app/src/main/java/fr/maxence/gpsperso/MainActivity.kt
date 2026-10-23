package fr.maxence.gpsperso

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
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
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
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
        private const val OFF_ROUTE_METERS = 50.0
        private const val ALERT_AHEAD_METERS = 800.0
        private const val FREE_DRIVE_ALERT_METERS = 500.0
    }

    private lateinit var mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var locationReady = false

    private lateinit var searchInput: EditText
    private lateinit var searchResults: ListView
    private lateinit var maneuverBanner: View
    private lateinit var maneuverArrow: TextView
    private lateinit var maneuverDistance: TextView
    private lateinit var maneuverText: TextView
    private lateinit var alertBanner: TextView
    private lateinit var speedText: TextView
    private lateinit var routePanel: View
    private lateinit var routeTitle: TextView
    private lateinit var routeSummary: TextView
    private lateinit var routeDetails: TextView
    private lateinit var navPanel: View
    private lateinit var navEta: TextView
    private lateinit var navRemaining: TextView

    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var alertStore: AlertStore
    private var tts: TextToSpeech? = null
    private var ttsReady = false

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

    /** Radars OSM autour de la zone utile (itinéraire ou position). */
    private var cameras: List<Alert> = emptyList()
    private var camerasCenter: Location? = null
    private var camerasLoading = false
    /** Alertes situées sur l'itinéraire, avec leur position (m) depuis le départ. */
    private var routeAlerts: List<Pair<Alert, Double>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContentView(R.layout.activity_main)
        alertStore = AlertStore(this)
        bindViews()

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { s ->
                style = s
                setupLayers(s)
                enableLocation()
                refreshAlertLayer()
            }
            m.addOnMapLongClickListener { latLng ->
                if (!navigating) {
                    setDestination(Place("Point sur la carte", String.format(Locale.FRANCE, "%.5f, %.5f", latLng.latitude, latLng.longitude), latLng.latitude, latLng.longitude))
                }
                true
            }
            m.addOnMapClickListener {
                hideSearchResults()
                false
            }
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(46.6, 2.4), 5.0))
        }

        tts = TextToSpeech(this, this)
        requestLocationPermission()
    }

    private fun bindViews() {
        mapView = findViewById(R.id.mapView)
        searchInput = findViewById(R.id.searchInput)
        searchResults = findViewById(R.id.searchResults)
        maneuverBanner = findViewById(R.id.maneuverBanner)
        maneuverArrow = findViewById(R.id.maneuverArrow)
        maneuverDistance = findViewById(R.id.maneuverDistance)
        maneuverText = findViewById(R.id.maneuverText)
        alertBanner = findViewById(R.id.alertBanner)
        speedText = findViewById(R.id.speedText)
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
        searchResults.setOnItemClickListener { _, _, position, _ ->
            searchPlaces.getOrNull(position)?.let {
                hideSearchResults()
                setDestination(it)
            }
        }
        findViewById<View>(R.id.btnRecenter).setOnClickListener { recenter() }
        findViewById<View>(R.id.btnReport).setOnClickListener { showReportDialog() }
        findViewById<View>(R.id.btnStart).setOnClickListener { startNavigation() }
        findViewById<View>(R.id.btnCancel).setOnClickListener { clearRoute() }
        findViewById<View>(R.id.btnStop).setOnClickListener { stopNavigation() }
        findViewById<View>(R.id.btnWaze).setOnClickListener { openInWaze() }
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
        s.addSource(GeoJsonSource("alerts-src"))
        s.addLayer(
            CircleLayer("alerts-layer", "alerts-src").withProperties(
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            )
        )
        s.addLayer(
            SymbolLayer("alerts-labels", "alerts-src").withProperties(
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

    private fun allAlerts(): List<Alert> = cameras + alertStore.all()

    private fun refreshAlertLayer() {
        val src = style?.getSourceAs<GeoJsonSource>("alerts-src") ?: return
        val features = allAlerts().map { a ->
            Feature.fromGeometry(Point.fromLngLat(a.lon, a.lat)).also {
                it.addStringProperty("color", a.type.color)
                it.addStringProperty("label", a.title)
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    // ---------- Localisation ----------

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        if (hasLocationPermission()) startLocationUpdates()
        else requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (hasLocationPermission()) {
                startLocationUpdates()
                enableLocation()
            } else {
                toast("Sans la localisation, le GPS ne peut pas vous guider.")
            }
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
            LocationComponentActivationOptions.Builder(this, s).useDefaultLocationEngine(false).build()
        )
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.COMPASS
        locationReady = true
        lastLocation?.let {
            lc.forceLocationUpdate(it)
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 14.0))
        }
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
        maybeLoadCamerasAround(location)
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
        io.execute {
            try {
                val places = Net.search(q, loc?.latitude, loc?.longitude)
                main.post {
                    searchPlaces = places
                    if (places.isEmpty()) {
                        toast("Aucun résultat")
                        hideSearchResults()
                    } else {
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
                }
            } catch (e: Exception) {
                main.post { toast("Recherche impossible : ${e.message}") }
            }
        }
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
        drawDestination(place)
        routeTitle.text = place.name
        routeSummary.text = "Calcul de l'itinéraire…"
        routeDetails.text = place.address
        routePanel.visibility = View.VISIBLE
        computeRoute(loc, place, isReroute = false)
    }

    private fun computeRoute(from: Location, to: Place, isReroute: Boolean) {
        rerouting = true
        val heading = if (from.hasBearing() && from.hasSpeed() && from.speed > 3) from.bearing else null
        io.execute {
            try {
                val r = Net.route(from.latitude, from.longitude, to.lat, to.lon, heading)
                main.post { onRouteReady(r, isReroute) }
                loadCamerasForRoute(r)
            } catch (e: Exception) {
                main.post {
                    rerouting = false
                    if (isReroute) toast("Recalcul impossible, nouvel essai bientôt")
                    else routeSummary.text = "Itinéraire introuvable"
                    if (!isReroute) routeDetails.text = e.message ?: ""
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
        spoken.removeAll { it.startsWith("m-") }
        drawRoute(r)
        computeRouteAlerts()
        if (isReroute) {
            say("Nouvel itinéraire")
            return
        }
        val arrival = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(System.currentTimeMillis() + (r.totalTime * 1000).toLong()))
        routeSummary.text = "${Geo.formatDuration(r.totalTime)} (${Geo.formatDistance(r.totalDistance)})"
        routeDetails.text = "Arrivée vers $arrival"
        val bounds = LatLngBounds.Builder()
        val step = (r.points.size / 200).coerceAtLeast(1)
        r.points.filterIndexed { i, _ -> i % step == 0 || i == r.points.size - 1 }
            .forEach { bounds.include(LatLng(it[0], it[1])) }
        lastLocation?.let { bounds.include(LatLng(it.latitude, it.longitude)) }
        try {
            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 120))
        } catch (e: Exception) {
            // itinéraire trop court pour former un rectangle
        }
    }

    private fun clearRoute() {
        stopNavigation()
        route = null
        destination = null
        routeAlerts = emptyList()
        drawRoute(null)
        drawDestination(null)
        routePanel.visibility = View.GONE
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
        if (route != null) routePanel.visibility = View.VISIBLE
        map?.let { m ->
            if (locationReady) {
                m.locationComponent.cameraMode = CameraMode.NONE
                m.locationComponent.renderMode = RenderMode.COMPASS
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

        // Durée restante estimée au prorata de l'itinéraire
        val remainingTime = if (r.totalDistance > 0) r.totalTime * remaining / r.totalDistance else 0.0
        val arrival = SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(System.currentTimeMillis() + (remainingTime * 1000).toLong()))
        navEta.text = "$arrival · ${Geo.formatDuration(remainingTime)}"
        navRemaining.text = "${Geo.formatDistance(remaining)} restants"

        // Alertes devant soi sur l'itinéraire
        val ahead = routeAlerts
            .map { (a, along) -> a to (along - p.along) }
            .filter { it.second in -10.0..ALERT_AHEAD_METERS }
            .minByOrNull { it.second }
        showAlert(ahead?.first, ahead?.second)
    }

    private fun computeRouteAlerts() {
        val r = route ?: run { routeAlerts = emptyList(); return }
        val alerts = allAlerts()
        routeAlerts = alerts.mapNotNull { a ->
            val p = r.project(a.lat, a.lon, 0)
            if (p.offRoute < 40) a to p.along else null
        }
    }

    /** Hors guidage : alerte si un danger est proche et devant (selon le cap). */
    private fun checkFreeDriveAlerts(loc: Location) {
        val moving = loc.hasSpeed() && loc.speed > 4
        if (!moving) {
            showAlert(null, null)
            return
        }
        val candidate = allAlerts()
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
        alertBanner.visibility = View.VISIBLE
        if (spoken.add("a-${alert.id}")) {
            val limit = if (alert.detail != null) ", limité à ${alert.detail}" else ""
            say("${alert.type.spoken} dans ${Geo.spokenDistance(distance.coerceAtLeast(50.0))}$limit")
        }
    }

    // ---------- Radars et signalements ----------

    private fun loadCamerasForRoute(r: Route) {
        if (r.points.isEmpty()) return
        val pad = 0.02
        val south = r.points.minOf { it[0] } - pad
        val north = r.points.maxOf { it[0] } + pad
        val west = r.points.minOf { it[1] } - pad
        val east = r.points.maxOf { it[1] } + pad
        try {
            val found = Net.speedCameras(south, west, north, east)
            main.post {
                cameras = (cameras + found).distinctBy { it.id }
                refreshAlertLayer()
                computeRouteAlerts()
            }
        } catch (e: Exception) {
            main.post { toast("Radars indisponibles pour cet itinéraire") }
        }
    }

    private fun maybeLoadCamerasAround(loc: Location) {
        val center = camerasCenter
        if (camerasLoading) return
        if (center != null && center.distanceTo(loc) < 5000) return
        camerasLoading = true
        camerasCenter = loc
        val d = 0.1 // environ 10 km autour
        io.execute {
            try {
                val found = Net.speedCameras(loc.latitude - d, loc.longitude - d * 1.5, loc.latitude + d, loc.longitude + d * 1.5)
                main.post {
                    cameras = (cameras + found).distinctBy { it.id }
                    refreshAlertLayer()
                    computeRouteAlerts()
                }
            } catch (e: Exception) {
                camerasCenter = null
            } finally {
                main.post { camerasLoading = false }
            }
        }
    }

    private fun showReportDialog() {
        val loc = lastLocation ?: run { toast("Position GPS pas encore disponible"); return }
        val types = AlertType.reportable
        AlertDialog.Builder(this)
            .setTitle("Que voulez-vous signaler ?")
            .setItems(types.map { it.label }.toTypedArray()) { _, which ->
                val alert = alertStore.add(types[which], loc.latitude, loc.longitude)
                spoken.add("a-${alert.id}") // pas d'annonce pour sa propre alerte
                refreshAlertLayer()
                computeRouteAlerts()
                toast("${alert.type.label} signalé")
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
    override fun onResume() { super.onResume(); mapView.onResume() }
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
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        tts?.shutdown()
        io.shutdownNow()
        mapView.onDestroy()
        super.onDestroy()
    }
}
