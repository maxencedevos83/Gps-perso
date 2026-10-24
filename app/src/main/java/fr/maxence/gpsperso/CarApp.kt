package fr.maxence.gpsperso

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.AppManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver as CarManeuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.TimeZone

/** Point d'entrée Android Auto. */
class GpsCarAppService : CarAppService() {
    // Appli personnelle installée hors Play Store : on accepte tous les hôtes (Android Auto en mode développeur).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCreateSession(): Session = GpsCarSession()
}

class GpsCarSession : Session() {
    private var carMap: CarMap? = null

    override fun onCreateScreen(intent: Intent): Screen {
        Nav.init(carContext)
        if (!Nav.hasLocationPermission()) return PermissionScreen(carContext)
        Nav.attach(this)
        val m = CarMap(carContext)
        carMap = m
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(m)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                m.release()
                Nav.detach(this@GpsCarSession)
            }
        })
        return NavScreen(carContext, m)
    }
}

/** Localisation pas encore autorisée : il faut ouvrir l'appli une fois sur le téléphone. */
class PermissionScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder("Ouvrez GPS Perso sur le téléphone et autorisez la localisation, puis relancez l'appli ici.")
            .setTitle("GPS Perso")
            .setHeaderAction(Action.APP_ICON)
            .build()
}

/** Petits outils communs aux écrans voiture. */
object CarUi {
    private val main = Handler(Looper.getMainLooper())

    fun post(action: () -> Unit) = main.post(action)

    /** Icône dessinée à partir d'un caractère (flèche de manœuvre, +, −...). */
    fun textIcon(text: String, tint: Boolean = false): CarIcon {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.8f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        Canvas(bmp).drawText(text, size / 2f, y, paint)
        val b = CarIcon.Builder(IconCompat.createWithBitmap(bmp))
        if (tint) b.setTint(CarColor.DEFAULT)
        return b.build()
    }

    fun distance(m: Double): Distance = when {
        m >= 10_000 -> Distance.create(Math.round(m / 1000.0).toDouble(), Distance.UNIT_KILOMETERS)
        m >= 1000 -> Distance.create(Math.round(m / 100.0) / 10.0, Distance.UNIT_KILOMETERS_P1)
        m >= 100 -> Distance.create(Math.round(m / 10.0) * 10.0, Distance.UNIT_METERS)
        else -> Distance.create(Math.round(m / 5.0) * 5.0, Distance.UNIT_METERS)
    }

    fun action(title: String, onClick: () -> Unit): Action =
        Action.Builder().setTitle(title).setOnClickListener { onClick() }.build()

    fun iconAction(icon: String, onClick: () -> Unit): Action =
        Action.Builder().setIcon(textIcon(icon, tint = true)).setOnClickListener { onClick() }.build()

    /** Types de manœuvre Valhalla vers ceux d'Android Auto (conduite à droite). */
    fun maneuverType(valhalla: Int): Int = when (valhalla) {
        1, 2, 3 -> CarManeuver.TYPE_DEPART
        4 -> CarManeuver.TYPE_DESTINATION
        5 -> CarManeuver.TYPE_DESTINATION_RIGHT
        6 -> CarManeuver.TYPE_DESTINATION_LEFT
        7, 8, 17, 22, 29 -> CarManeuver.TYPE_STRAIGHT
        9 -> CarManeuver.TYPE_TURN_SLIGHT_RIGHT
        10 -> CarManeuver.TYPE_TURN_NORMAL_RIGHT
        11 -> CarManeuver.TYPE_TURN_SHARP_RIGHT
        12 -> CarManeuver.TYPE_U_TURN_RIGHT
        13 -> CarManeuver.TYPE_U_TURN_LEFT
        14 -> CarManeuver.TYPE_TURN_SHARP_LEFT
        15 -> CarManeuver.TYPE_TURN_NORMAL_LEFT
        16 -> CarManeuver.TYPE_TURN_SLIGHT_LEFT
        18 -> CarManeuver.TYPE_ON_RAMP_NORMAL_RIGHT
        19 -> CarManeuver.TYPE_ON_RAMP_NORMAL_LEFT
        20 -> CarManeuver.TYPE_OFF_RAMP_NORMAL_RIGHT
        21 -> CarManeuver.TYPE_OFF_RAMP_NORMAL_LEFT
        23 -> CarManeuver.TYPE_KEEP_RIGHT
        24 -> CarManeuver.TYPE_KEEP_LEFT
        25 -> CarManeuver.TYPE_MERGE_SIDE_UNSPECIFIED
        26 -> CarManeuver.TYPE_ROUNDABOUT_ENTER_CCW
        27 -> CarManeuver.TYPE_ROUNDABOUT_EXIT_CCW
        28 -> CarManeuver.TYPE_FERRY_BOAT
        37 -> CarManeuver.TYPE_MERGE_RIGHT
        38 -> CarManeuver.TYPE_MERGE_LEFT
        else -> CarManeuver.TYPE_UNKNOWN
    }

    fun step(m: Maneuver): Step = Step.Builder(m.instruction)
        .setManeuver(CarManeuver.Builder(maneuverType(m.type)).setIcon(textIcon(m.arrow)).build())
        .build()

    /** Lance un trajet depuis la voiture : le guidage démarre dès que l'itinéraire est calculé. */
    fun go(screen: Screen, place: Place) {
        if (Nav.setDestination(place, autoStart = true)) {
            CarToast.makeText(screen.carContext, "Calcul de l'itinéraire vers ${place.name}…", CarToast.LENGTH_SHORT).show()
            screen.screenManager.popToRoot()
        } else {
            CarToast.makeText(screen.carContext, "Position GPS pas encore disponible", CarToast.LENGTH_LONG).show()
        }
    }

    fun distanceLabel(lat: Double, lon: Double): String? =
        Nav.lastLocation?.let { "à " + Geo.formatDistance(Geo.distance(it.latitude, it.longitude, lat, lon)) }
}

/** Écran principal : la carte, la prochaine manœuvre, l'heure d'arrivée. */
class NavScreen(carContext: CarContext, private val carMap: CarMap) : Screen(carContext), Nav.Listener {

    private val navManager = carContext.getCarService(NavigationManager::class.java)
    private var computing = false
    private var lastInvalidate = 0L

    init {
        navManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = Nav.stopNavigation()
        })
        carMap.onFollowingChanged = { invalidate() }
        Nav.addListener(this)
        if (Nav.navigating) safe { navManager.navigationStarted() }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                Nav.removeListener(this@NavScreen)
                if (Nav.navigating) safe { navManager.navigationEnded() }
            }
        })
    }

    private fun safe(block: () -> Unit) = try { block() } catch (e: Exception) { }

    override fun onRouteReady(r: Route, isReroute: Boolean) { computing = false; invalidate() }
    override fun onRouteError(message: String, isReroute: Boolean) {
        computing = false
        if (!isReroute) CarToast.makeText(carContext, "Itinéraire introuvable", CarToast.LENGTH_LONG).show()
        invalidate()
    }
    override fun onRouteCleared() { computing = false; invalidate() }
    override fun onNavigationStarted() { safe { navManager.navigationStarted() }; invalidate() }
    override fun onNavigationStopped() { safe { navManager.navigationEnded() }; invalidate() }
    override fun onEtaChanged() = invalidate()
    override fun onMessage(text: String) = CarToast.makeText(carContext, text, CarToast.LENGTH_SHORT).show()

    override fun onProgress(p: Nav.Progress) {
        // Rafraîchi au plus une fois par seconde (la voiture limite les mises à jour).
        val now = System.currentTimeMillis()
        if (now - lastInvalidate < 1000) return
        lastInvalidate = now
        invalidate()
    }

    override fun onGetTemplate(): Template {
        val b = NavigationTemplate.Builder()
        val strip = ActionStrip.Builder()
        val route = Nav.route
        val dest = Nav.destination
        if (Nav.navigating && route != null) {
            strip.addAction(CarUi.action("Arrêter") { Nav.stopNavigation() })
            strip.addAction(CarUi.action("Signaler") { screenManager.push(ReportScreen(carContext)) })
            strip.addAction(CarUi.action("Étape") { screenManager.push(StationsScreen(carContext)) })
            val p = Nav.lastProgress
            val man = p?.maneuver
            if (p == null || man == null) {
                b.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
            } else {
                val info = RoutingInfo.Builder().setCurrentStep(CarUi.step(man), CarUi.distance(p.maneuverDistance))
                p.nextManeuver?.let { info.setNextStep(CarUi.step(it)) }
                b.setNavigationInfo(info.build())
                val color = when {
                    p.trafficDelay >= 600 -> CarColor.RED
                    p.trafficDelay >= 180 -> CarColor.YELLOW
                    else -> CarColor.GREEN
                }
                b.setDestinationTravelEstimate(
                    TravelEstimate.Builder(CarUi.distance(p.remaining), DateTimeWithZone.create(p.arrivalMillis, TimeZone.getDefault()))
                        .setRemainingTimeSeconds(p.remainingTime.toLong().coerceAtLeast(0))
                        .setRemainingTimeColor(color)
                        .build()
                )
            }
        } else {
            strip.addAction(CarUi.action("Aller à") { screenManager.push(DestinationsScreen(carContext)) })
            if (route != null && dest != null) {
                strip.addAction(CarUi.action("Démarrer") { Nav.startNavigation() })
                strip.addAction(CarUi.action("Annuler") { Nav.clearRoute() })
                val traffic = if (Nav.trafficActive() && Nav.trafficDelay >= 60) " · bouchons +${Geo.formatDuration(Nav.trafficDelay)}" else ""
                b.setNavigationInfo(
                    MessageInfo.Builder(dest.name)
                        .setText("${Geo.formatDuration(Nav.etaSeconds)} · ${Geo.formatDistance(route.totalDistance)}$traffic")
                        .build()
                )
            } else if (dest != null) {
                b.setNavigationInfo(MessageInfo.Builder("Calcul de l'itinéraire…").setText(dest.name).build())
            } else {
                strip.addAction(CarUi.action("Signaler") { screenManager.push(ReportScreen(carContext)) })
            }
        }
        b.setActionStrip(strip.build())
        val mapStrip = ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(CarUi.iconAction("+") { carMap.zoom(1.0) })
            .addAction(CarUi.iconAction("−") { carMap.zoom(-1.0) })
        if (!carMap.following) mapStrip.addAction(CarUi.iconAction("◎") { carMap.recenter() })
        b.setMapActionStrip(mapStrip.build())
        return b.build()
    }
}

/** « Aller à » : recherche, maison, récents, POIs, agenda. */
class DestinationsScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val s = Nav.settings
        val list = ItemList.Builder()
        list.addItem(row("🔍  Rechercher une adresse") { screenManager.push(SearchScreen(carContext)) })
        if (s.hasHome) list.addItem(row("🏠  Maison", s.homeName) { CarUi.go(this, Place("Maison", s.homeName, s.homeLat, s.homeLon)) })
        list.addItem(row("🕘  Adresses récentes") {
            screenManager.push(PlacesScreen(carContext, "Adresses récentes", Nav.recentStore.all()))
        })
        list.addItem(row("⭐  Mes POIs") {
            val pois = Nav.poiStore.all().map { it.toPlace() }.sortedBy { p ->
                Nav.lastLocation?.let { Geo.distance(it.latitude, it.longitude, p.lat, p.lon) } ?: 0.0
            }
            screenManager.push(PlacesScreen(carContext, "Mes POIs", pois))
        })
        list.addItem(row("📅  Mon agenda") { screenManager.push(AgendaScreen(carContext)) })
        list.addItem(row("⛽  Stations-service") { screenManager.push(StationsScreen(carContext)) })
        @Suppress("DEPRECATION")
        return ListTemplate.Builder()
            .setTitle("Aller à")
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun row(title: String, text: String? = null, onClick: () -> Unit): Row {
        val r = Row.Builder().setTitle(title).setBrowsable(text == null).setOnClickListener { onClick() }
        if (!text.isNullOrBlank()) r.addText(text)
        return r.build()
    }
}

/** Liste de lieux : toucher un lieu lance le guidage. */
class PlacesScreen(carContext: CarContext, private val title: String, private val places: List<Place>) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder().setNoItemsMessage("Aucun lieu")
        places.take(20).forEach { p ->
            val r = Row.Builder().setTitle(p.name).setOnClickListener { CarUi.go(this, p) }
            val sub = listOfNotNull(p.address.ifBlank { null }, CarUi.distanceLabel(p.lat, p.lon)).joinToString(" · ")
            if (sub.isNotBlank()) r.addText(sub)
            list.addItem(r.build())
        }
        @Suppress("DEPRECATION")
        return ListTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

/** Recherche d'adresse (clavier disponible à l'arrêt, selon la voiture). */
class SearchScreen(carContext: CarContext) : Screen(carContext) {
    private var results: List<Place> = emptyList()
    private var loading = false
    private var searched = false

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {}
            override fun onSearchSubmitted(searchText: String) = search(searchText)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Adresse, lieu, ville…")
            .setShowKeyboardByDefault(results.isEmpty())
        if (loading) {
            b.setLoading(true)
        } else {
            val list = ItemList.Builder()
            if (searched) list.setNoItemsMessage("Aucun résultat")
            results.forEach { p ->
                val r = Row.Builder().setTitle(p.name).setOnClickListener { CarUi.go(this, p) }
                val sub = listOfNotNull(p.address.ifBlank { null }, CarUi.distanceLabel(p.lat, p.lon)).joinToString(" · ")
                if (sub.isNotBlank()) r.addText(sub)
                list.addItem(r.build())
            }
            b.setItemList(list.build())
        }
        return b.build()
    }

    private fun search(q: String) {
        if (q.isBlank()) return
        loading = true
        invalidate()
        val loc = Nav.lastLocation
        Nav.io.execute {
            val found = try { Net.search(q, loc?.latitude, loc?.longitude) } catch (e: Exception) { emptyList() }
            CarUi.post {
                results = found
                loading = false
                searched = true
                invalidate()
            }
        }
    }
}

/** Rendez-vous avec une adresse dans l'agenda. */
class AgendaScreen(carContext: CarContext) : Screen(carContext) {
    private var events: List<AgendaEvent>? = null

    init {
        Nav.io.execute {
            val found = try { Agenda.upcoming(carContext) } catch (e: Exception) { emptyList() }
            CarUi.post { events = found; invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        @Suppress("DEPRECATION")
        val b = ListTemplate.Builder().setTitle("Mon agenda").setHeaderAction(Action.BACK)
        val evts = events ?: return b.setLoading(true).build()
        val list = ItemList.Builder().setNoItemsMessage("Aucun rendez-vous avec une adresse")
        evts.take(20).forEach { e ->
            list.addItem(
                Row.Builder().setTitle("${e.whenLabel()} · ${e.title}").addText(e.location)
                    .setOnClickListener { open(e) }.build()
            )
        }
        return b.setSingleList(list.build()).build()
    }

    private fun open(e: AgendaEvent) {
        val loc = Nav.lastLocation
        CarToast.makeText(carContext, "Recherche de l'adresse…", CarToast.LENGTH_SHORT).show()
        Nav.io.execute {
            val place = try { Net.search(e.location, loc?.latitude, loc?.longitude).firstOrNull() } catch (ex: Exception) { null }
            CarUi.post {
                if (place == null) CarToast.makeText(carContext, "Adresse introuvable", CarToast.LENGTH_LONG).show()
                else CarUi.go(this, Place(e.title, e.location, place.lat, place.lon))
            }
        }
    }
}

/** Stations-service proches, les moins chères d'abord ; en guidage, la station devient une étape. */
class StationsScreen(carContext: CarContext) : Screen(carContext) {
    private var stations: List<NearbyPlace>? = null
    private var error = false

    init {
        val loc = Nav.lastLocation
        if (loc == null) stations = emptyList()
        else Nav.io.execute {
            val found = try { Nearby.stations(loc.latitude, loc.longitude, 10, Nav.settings.fuel) } catch (e: Exception) { error = true; emptyList() }
            val sorted = found.sortedWith(compareBy<NearbyPlace> { it.price == null }.thenBy { it.price ?: 0.0 })
            CarUi.post { stations = sorted; invalidate() }
        }
    }

    override fun onGetTemplate(): Template {
        val title = if (Nav.navigating) "Faire le plein (étape)" else "Stations-service"
        @Suppress("DEPRECATION")
        val b = ListTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK)
        val list = stations ?: return b.setLoading(true).build()
        val items = ItemList.Builder().setNoItemsMessage(if (error) "Prix indisponibles pour le moment" else "Aucune station à proximité")
        list.take(20).forEach { n ->
            val price = n.price?.let { "${Nearby.fmtPrice(it)} ${Nav.settings.fuel}" }
            val r = Row.Builder().setTitle(n.name).setOnClickListener {
                if (Nav.navigating) {
                    Nav.addStop(n.toPlace())
                    screenManager.popToRoot()
                } else CarUi.go(this, n.toPlace())
            }
            val sub = listOfNotNull(price, CarUi.distanceLabel(n.lat, n.lon)).joinToString(" · ")
            if (sub.isNotBlank()) r.addText(sub)
            items.addItem(r.build())
        }
        return b.setSingleList(items.build()).build()
    }
}

/** Signalement rapide depuis la voiture. */
class ReportScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        AlertType.reportable.forEach { type ->
            list.addItem(Row.Builder().setTitle(type.label).setOnClickListener {
                val loc = Nav.lastLocation
                if (loc != null) {
                    val alert = Nav.alertStore.add(type, loc.latitude, loc.longitude)
                    Nav.markSpoken(alert.id)
                    Nav.alertsChanged()
                    CarToast.makeText(carContext, "${type.label} signalé", CarToast.LENGTH_SHORT).show()
                }
                screenManager.pop()
            }.build())
        }
        @Suppress("DEPRECATION")
        return ListTemplate.Builder().setTitle("Signaler").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}
