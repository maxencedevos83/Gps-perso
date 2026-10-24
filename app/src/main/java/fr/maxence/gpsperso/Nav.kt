package fr.maxence.gpsperso

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Moteur de guidage partagé par le téléphone et Android Auto : position GPS, itinéraire,
 * consignes vocales, radars et trafic. Il vit tant que l'appli tourne, écran verrouillé compris
 * (un service de premier plan le maintient pendant le guidage).
 */
object Nav : LocationListener {

    private const val OFF_ROUTE_METERS = 50.0
    private const val ALERT_AHEAD_METERS = 800.0
    private const val FREE_DRIVE_ALERT_METERS = 500.0
    private const val INCIDENTS_REFRESH_MS = 15 * 60_000L
    private const val ETA_REFRESH_MS = 10 * 60_000L

    /** État du guidage à un instant donné. */
    class Progress(
        val maneuver: Maneuver?,
        val maneuverDistance: Double,
        val nextManeuver: Maneuver?,
        val remaining: Double,
        val remainingTime: Double,
        val trafficDelay: Double,
    ) {
        val arrivalMillis: Long get() = System.currentTimeMillis() + (remainingTime * 1000).toLong()
    }

    /** Tous les rappels arrivent sur le fil principal. */
    interface Listener {
        fun onLocation(loc: Location, first: Boolean) {}
        fun onRouteReady(r: Route, isReroute: Boolean) {}
        fun onRouteError(message: String, isReroute: Boolean) {}
        fun onRouteCleared() {}
        fun onNavigationStarted() {}
        fun onNavigationStopped() {}
        fun onProgress(p: Progress) {}
        fun onAlert(alert: Alert?, distance: Double?) {}
        fun onAlertsChanged() {}
        fun onEtaChanged() {}
        fun onMessage(text: String) {}
        fun onSpeedLimit(limit: Int?) {}
        fun onAltitude(meters: Int?) {}
        fun onWeather(warnings: List<Weather.Warning>) {}
        fun onParkingChanged() {}
    }

    private lateinit var app: Context
    lateinit var settings: Settings; private set
    lateinit var alertStore: AlertStore; private set
    lateinit var lufopStore: LufopStore; private set
    lateinit var recentStore: RecentStore; private set
    lateinit var poiStore: PoiStore; private set

    val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val owners = HashSet<Any>()
    private var locating = false


    var lastLocation: Location? = null; private set
    /** Vitesse maximale de la route actuelle (km/h), si connue. */
    var speedLimit: Int? = null; private set
    /** Altitude du sol (m) sous la position actuelle. */
    var altitude: Int? = null; private set
    private var altitudeAt: Location? = null
    private var altitudeTime = 0L
    private var altitudeLoading = false
    var destination: Place? = null; private set
    /** Étape facultative (station, resto) avant la destination. */
    var stop: Place? = null; private set
    var route: Route? = null; private set
    var navigating = false; private set
    var segmentHint = 0; private set
    var lastProgress: Progress? = null; private set
    var currentAlert: Pair<Alert, Double>? = null; private set
    private var offRouteCount = 0
    private var rerouting = false
    private var lastRerouteAt = 0L
    private var startWhenReady = false
    private val spoken = HashSet<String>()

    /** Durée de référence (trafic compris si dispo) pour une distance restante de référence. */
    var etaSeconds = 0.0; private set
    private var etaDistance = 0.0
    var trafficDelay = 0.0; private set
    private var lastEtaAt = 0L

    /** Radars et bornes OSM autour de la zone utile. */
    private var osmAlerts: List<Alert> = emptyList()
    private var osmCenter: Location? = null
    private var osmLoading = false
    /** Incidents TomTom (accidents, bouchons, travaux...). */
    private var incidents: Map<String, Alert> = emptyMap()
    private var lastIncidentsAt = 0L
    private var incidentsCenter: Location? = null
    /** Alertes situées sur l'itinéraire, avec leur position (m) depuis le départ. */
    private var routeAlerts: List<Pair<Alert, Double>> = emptyList()

    /** Événements Bison Futé (routes nationales et autoroutes). */
    private var bisonAlerts: List<Alert> = emptyList()
    private var bisonAt = 0L
    private var bisonLoading = false
    /** Météo prévue sur le trajet. */
    var weatherWarnings: List<Weather.Warning> = emptyList(); private set

    private val ticker = object : Runnable {
        override fun run() {
            periodicRefresh()
            maybeLoadBisonFute()
            main.postDelayed(this, 60_000)
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        settings = Settings(app)
        alertStore = AlertStore(app)
        lufopStore = LufopStore(app)
        recentStore = RecentStore(app)
        poiStore = PoiStore(app)
        Voice.init(app, settings)
        main.postDelayed(ticker, 60_000)
    }

    fun addListener(l: Listener) { if (l !in listeners) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private fun emit(action: (Listener) -> Unit) = listeners.forEach { action(it) }

    // ---------- Localisation ----------

    fun hasLocationPermission() =
        app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Le téléphone ou la voiture a besoin de la position. */
    fun attach(owner: Any) {
        owners.add(owner)
        startLocation()
        maybeLoadBisonFute()
    }

    /** Plus personne n'affiche la carte : on coupe le GPS, sauf pendant un guidage. */
    fun detach(owner: Any) {
        owners.remove(owner)
        if (owners.isEmpty() && !navigating) stopLocation()
    }

    @SuppressLint("MissingPermission")
    fun startLocation() {
        if (locating || !hasLocationPermission()) return
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = lm.getProviders(true)
        if (LocationManager.GPS_PROVIDER in providers) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
        }
        if (LocationManager.NETWORK_PROVIDER in providers) {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, this, Looper.getMainLooper())
        }
        locating = true
        val last = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        if (last != null && lastLocation == null) onLocationChanged(last)
    }

    private fun stopLocation() {
        if (!locating) return
        (app.getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        locating = false
    }

    override fun onLocationChanged(location: Location) {
        val previous = lastLocation
        // On ignore la position réseau (imprécise) si le GPS a répondu récemment.
        if (location.provider == LocationManager.NETWORK_PROVIDER && previous != null &&
            previous.provider == LocationManager.GPS_PROVIDER && location.time - previous.time < 10_000
        ) return
        val first = previous == null
        lastLocation = location
        val limit = SpeedLimit.update(location)
        if (limit != speedLimit) {
            speedLimit = limit
            emit { it.onSpeedLimit(limit) }
        }
        emit { it.onLocation(location, first) }
        maybeUpdateAltitude(location)
        if (navigating) updateNavigation(location) else checkFreeDriveAlerts(location)
        maybeLoadOsmAround(location)
        if (first) periodicRefresh()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Requis sur les anciennes versions d'Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // ---------- Itinéraire ----------

    /** Nouvelle destination ; [autoStart] lance le guidage dès que l'itinéraire est prêt (voiture). */
    fun setDestination(place: Place, autoStart: Boolean = false): Boolean {
        val loc = lastLocation ?: return false
        if (navigating) stopNavigation()
        destination = place
        if (place.name != "Point sur la carte") recentStore.add(place)
        stop = null
        startWhenReady = autoStart
        computeRoute(loc, place, isReroute = false)
        return true
    }

    fun addStop(place: Place) {
        val loc = lastLocation ?: return
        val dest = destination ?: return
        stop = place
        say("Étape ajoutée")
        computeRoute(loc, dest, isReroute = true)
    }

    private fun computeRoute(from: Location, to: Place, isReroute: Boolean) {
        rerouting = true
        val via = listOfNotNull(stop)
        val heading = if (from.hasBearing() && from.hasSpeed() && from.speed > 3) from.bearing else null
        io.execute {
            try {
                val r = Net.route(from.latitude, from.longitude, to.lat, to.lon, heading, via)
                main.post { if (destination === to) onRouteReady(r, isReroute) }
                try { Net.addLanes(r, from.latitude, from.longitude, to.lat, to.lon, heading, via) } catch (e: Exception) { }
                if (!isReroute) loadWeather(r)
                loadOsmForRoute(r)
            } catch (e: Exception) {
                main.post {
                    rerouting = false
                    if (destination !== to) return@post
                    startWhenReady = false
                    emit { it.onRouteError(e.message ?: "", isReroute) }
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
        computeRouteAlerts()
        lastLocation?.let { refreshEta(it) }
        if (isReroute) say("Nouvel itinéraire")
        if (trafficActive()) {
            loadIncidents(if (isReroute) TomTom.boxesAlong(r, 0.0, 90_000.0, maxBoxes = 2) else TomTom.boxesAlong(r))
        }
        emit { it.onRouteReady(r, isReroute) }
        if (startWhenReady && !isReroute) {
            startWhenReady = false
            startNavigation()
        }
        if (navigating) lastLocation?.let { updateNavigation(it) }
    }

    fun clearRoute() {
        stopNavigation()
        route = null
        destination = null
        stop = null
        startWhenReady = false
        routeAlerts = emptyList()
        lastProgress = null
        weatherWarnings = emptyList()
        emit { it.onRouteCleared() }
    }

    // ---------- Guidage ----------

    fun startNavigation() {
        val r = route ?: return
        if (navigating) return
        navigating = true
        spoken.clear()
        startLocation()
        NavService.start(app)
        emit { it.onNavigationStarted() }
        r.maneuvers.firstOrNull()?.let { say(it.verbalPre ?: it.instruction) }
        weatherWarnings.firstOrNull()?.let { say("Météo : ${it.text} sur le trajet, vers ${it.place}") }
        lastLocation?.let { updateNavigation(it) }
    }

    fun stopNavigation() {
        if (!navigating) return
        navigating = false
        currentAlert = null
        NavService.stop(app)
        Voice.stop()
        emit { it.onAlert(null, null) }
        emit { it.onNavigationStopped() }
        if (owners.isEmpty()) stopLocation()
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
            saveParking(loc.latitude, loc.longitude)
            clearRoute()
            return
        }

        // Prochaine manœuvre
        val index = r.maneuvers.indexOfFirst { r.cumulative[it.beginIndex] > p.along + 3 }
        var man: Maneuver? = null
        var dist = 0.0
        if (index >= 0) {
            man = r.maneuvers[index]
            dist = r.cumulative[man.beginIndex] - p.along
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
        val progress = Progress(man, dist, r.maneuvers.getOrNull(index + 1).takeIf { index >= 0 }, remaining, remainingTime,
            if (trafficActive()) trafficDelay else 0.0)
        lastProgress = progress
        emit { it.onProgress(progress) }

        // Alertes devant soi sur l'itinéraire
        val ahead = routeAlerts
            .map { (a, along) -> a to (along - p.along) }
            .filter { it.second in -10.0..ALERT_AHEAD_METERS }
            .minByOrNull { it.second }
        showAlert(ahead?.first, ahead?.second)
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
        val previous = currentAlert
        currentAlert = if (alert != null && distance != null) alert to distance else null
        if (alert == null || distance == null) {
            if (previous != null) emit { it.onAlert(null, null) }
            return
        }
        emit { it.onAlert(alert, distance) }
        if (spoken.add("a-${alert.id}")) {
            val limit = if (alert.detail != null && alert.type != AlertType.BOUCHON) ", limité à ${alert.detail}" else ""
            val length = if (alert.type == AlertType.BOUCHON && alert.detail != null) " sur ${alert.detail}" else ""
            val what = when {
                alert.description?.contains("zone de danger", ignoreCase = true) == true -> "Zone de danger"
                alert.source == "bisonfute" || alert.source == "here" ->
                    alert.description?.lineSequence()?.firstOrNull()?.substringBefore(" ·")?.ifBlank { null } ?: alert.type.spoken
                else -> alert.type.spoken
            }
            say("$what$length dans ${Geo.spokenDistance(distance.coerceAtLeast(50.0))}$limit", Voice.Kind.ALERT)
        }
    }

    /** Pas d'annonce pour un signalement que l'on vient de faire soi-même. */
    fun markSpoken(alertId: String) { spoken.add("a-$alertId") }

    // ---------- Alertes : radars, bornes, incidents, signalements ----------

    /** Toutes les alertes connues : radars (Lufop sinon OSM), bornes, incidents TomTom, signalements perso. */
    fun allAlerts(): List<Alert> {
        val lufop = lufopStore.all()
        val hasImportedRadars = lufop.any { it.type != AlertType.BORNE }
        val osm = osmAlerts.filter { it.type == AlertType.BORNE || !hasImportedRadars }
        val bison = if (settings.showBisonFute) bisonAlerts else emptyList()
        return (lufop + osm + incidents.values + bison + alertStore.all())
            .filter { it.type != AlertType.BORNE || settings.showChargers }
    }

    fun isAnnounced(a: Alert): Boolean {
        if (!a.type.announce) return false
        val radar = a.type == AlertType.RADAR_FIXE || a.type == AlertType.RADAR_FEU || a.type == AlertType.RADAR_MOBILE
        return !radar || settings.announceRadars
    }

    /** À appeler quand les alertes ont changé (import, signalement, réglage). */
    fun alertsChanged() {
        emit { it.onAlertsChanged() }
        computeRouteAlerts()
    }

    /** Place les alertes sur l'itinéraire (en arrière-plan : il peut y en avoir des milliers). */
    private fun computeRouteAlerts() {
        val r = route ?: run { routeAlerts = emptyList(); return }
        io.execute {
            val alerts = allAlerts().filter { isAnnounced(it) }
            val placed = alerts.mapNotNull { a -> r.projectNear(a.lat, a.lon, 40.0)?.let { a to it.along } }
            main.post { if (route === r) routeAlerts = placed }
        }
    }

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
            main.post { emit { it.onMessage("Radars OpenStreetMap indisponibles pour cet itinéraire") } }
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

    /** Réglages d'affichage changés : on recharge les radars et bornes OSM autour de soi. */
    fun reloadAround() {
        osmCenter = null
        lastLocation?.let { maybeLoadOsmAround(it) }
    }

    private fun mergeOsm(found: List<Alert>) {
        osmAlerts = (osmAlerts + found).distinctBy { it.id }
        alertsChanged()
    }

    // ---------- Trafic TomTom ----------

    fun trafficActive() = settings.trafficOn && (settings.hasTomTom || settings.hasHere)

    /** Trafic coupé : on oublie les incidents. */
    fun clearIncidents() {
        incidents = emptyMap()
        alertsChanged()
    }

    /** Trafic rallumé : on recharge tout de suite. */
    fun refreshTrafficNow() {
        lastIncidentsAt = 0
        incidentsCenter = null
        periodicRefresh()
    }

    private fun loadIncidents(boxes: List<DoubleArray>) {
        if (!trafficActive() || boxes.isEmpty()) return
        lastIncidentsAt = System.currentTimeMillis()
        val key = settings.tomtomKey
        val hereKey = settings.hereKey
        io.execute {
            val found = ArrayList<Alert>()
            var error: String? = null
            for (b in boxes) {
                if (key.isNotBlank()) try {
                    found += TomTom.incidents(key, b[0], b[1], b[2], b[3])
                } catch (e: Exception) {
                    error = e.message
                }
                if (hereKey.isNotBlank()) try {
                    found += Here.incidents(hereKey, b[0], b[1], b[2], b[3])
                } catch (e: Exception) {
                    error = "HERE : " + e.message
                }
            }
            main.post {
                incidents = incidents + found.associateBy { it.id }
                alertsChanged()
                if (error != null && found.isEmpty()) emit { it.onMessage("Incidents TomTom indisponibles : $error") }
            }
        }
    }

    private fun refreshEta(from: Location) {
        val dest = destination ?: return
        val r = route ?: return
        if (!trafficActive() || !settings.hasTomTom) return
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
                    emit { it.onEtaChanged() }
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

    // ---------- Bison Futé, météo, parking ----------

    /** Bison Futé : le fichier national est rechargé toutes les 20 minutes quand l'appli sert. */
    fun maybeLoadBisonFute(force: Boolean = false) {
        if (!settings.showBisonFute || bisonLoading) return
        if (!force && System.currentTimeMillis() - bisonAt < 20 * 60_000) return
        if (!force && owners.isEmpty() && !navigating) return
        bisonLoading = true
        io.execute {
            val found = try { BisonFute.download() } catch (e: Exception) { null }
            main.post {
                bisonLoading = false
                if (found != null) {
                    bisonAt = System.currentTimeMillis()
                    bisonAlerts = found
                    alertsChanged()
                }
            }
        }
    }

    private fun loadWeather(r: Route) {
        if (!settings.showWeather) return
        try {
            val warnings = Weather.alongRoute(r, System.currentTimeMillis()) { lat, lon ->
                try { Net.reverse(lat, lon)?.split(",")?.lastOrNull { it.isNotBlank() }?.trim() } catch (e: Exception) { null } ?: "sur le trajet"
            }
            main.post {
                if (route !== r && route != null) return@post
                weatherWarnings = warnings
                emit { it.onWeather(warnings) }
                if (navigating && warnings.isNotEmpty()) say("Météo : ${warnings.first().text} sur le trajet")
            }
        } catch (e: Exception) {
            // météo facultative
        }
    }

    fun saveParking(lat: Double, lon: Double) {
        settings.parkLat = lat
        settings.parkLon = lon
        settings.parkTime = System.currentTimeMillis()
        emit { it.onParkingChanged() }
    }

    fun clearParking() {
        settings.parkLat = Double.NaN
        settings.parkLon = Double.NaN
        emit { it.onParkingChanged() }
    }

    // ---------- Altitude ----------

    /**
     * Altitude du terrain (modèle numérique Open-Meteo, gratuit, sans clé) : plus juste que
     * l'altitude GPS brute, décalée d'environ 50 m en France. Rafraîchie tous les 150 m.
     */
    private fun maybeUpdateAltitude(loc: Location) {
        val prev = altitudeAt
        if (altitudeLoading) return
        if (prev != null && prev.distanceTo(loc) < 150 && System.currentTimeMillis() - altitudeTime < 10 * 60_000) return
        altitudeLoading = true
        altitudeAt = loc
        altitudeTime = System.currentTimeMillis()
        io.execute {
            val meters = try {
                val url = String.format(java.util.Locale.US, "https://api.open-meteo.com/v1/elevation?latitude=%.5f&longitude=%.5f", loc.latitude, loc.longitude)
                org.json.JSONObject(Net.request(url, timeoutMs = 8000)).getJSONArray("elevation").getDouble(0).let { Math.round(it).toInt() }
            } catch (e: Exception) {
                // Hors ligne : altitude GPS au-dessus du niveau de la mer si le téléphone la fournit
                if (android.os.Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) Math.round(loc.mslAltitudeMeters).toInt() else null
            }
            main.post {
                altitudeLoading = false
                if (meters == null) { altitudeAt = null; return@post }
                if (meters != altitude) {
                    altitude = meters
                    emit { it.onAltitude(meters) }
                }
            }
        }
    }

    // ---------- Voix ----------

    fun say(text: String, kind: Voice.Kind = Voice.Kind.GUIDANCE) = Voice.say(text, kind)

    /** Ouvre l'appli sur le téléphone. */
    fun launchIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
