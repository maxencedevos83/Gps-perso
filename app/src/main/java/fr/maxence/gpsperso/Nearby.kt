package fr.maxence.gpsperso

import org.json.JSONObject
import java.util.Locale

enum class NearbyKind { STATION, RESTAURANT, FAST_FOOD_BRAND }

/** Lieu trouvé autour de soi ou le long du trajet. */
data class NearbyPlace(
    val id: String,
    val kind: NearbyKind,
    val name: String,
    val lat: Double,
    val lon: Double,
    val detail: String? = null,
    /** "mcdo" ou "bk" pour les enseignes mises en évidence. */
    val brand: String? = null,
    /** Prix du carburant préféré (station), pour trier. */
    val price: Double? = null,
    /** Tous les prix connus (clé de carburant → €/L). */
    val prices: Map<String, Double> = emptyMap(),
    val address: String? = null,
    /** Date de mise à jour des prix (ISO 8601). */
    val updated: String? = null,
    /** Ville (stations) pour le titre. */
    val city: String? = null,
) {
    fun toPlace() = Place(name, detail ?: "", lat, lon)
}

object Nearby {
    private const val FUEL_API =
        "https://data.economie.gouv.fr/api/explore/v2.1/catalog/datasets/prix-des-carburants-en-france-flux-instantane-v2/records"

    val fuels = listOf("gazole" to "Gazole", "sp95" to "SP95", "e10" to "SP95-E10", "sp98" to "SP98", "e85" to "E85", "gplc" to "GPLc")

    private fun overpass(query: String, quick: Boolean = false): List<JSONObject> {
        val json = if (quick) Net.overpassQuick(query) else Net.overpass(query)
        val elements = json.getJSONArray("elements")
        return (0 until elements.length()).map { elements.getJSONObject(it) }
    }

    private fun coords(e: JSONObject): Pair<Double, Double>? {
        if (e.has("lat")) return e.getDouble("lat") to e.getDouble("lon")
        val c = e.optJSONObject("center") ?: return null
        return c.getDouble("lat") to c.getDouble("lon")
    }

    private fun brandOf(tags: JSONObject?): String? {
        if (tags == null) return null
        val wikidata = tags.optString("brand:wikidata")
        val text = (tags.optString("brand") + " " + tags.optString("name")).lowercase(Locale.ROOT)
        return when {
            wikidata == "Q38076" || "mcdonald" in text -> "mcdo"
            wikidata == "Q177054" || "burger king" in text -> "bk"
            else -> null
        }
    }

    /** McDonald's et Burger King dans un rectangle (léger, même sur un long trajet). */
    fun brands(south: Double, west: Double, north: Double, east: Double): List<NearbyPlace> {
        val box = "($south,$west,$north,$east)"
        val query = "[out:json][timeout:30];(" +
            "nwr[\"amenity\"=\"fast_food\"][\"brand:wikidata\"~\"^(Q38076|Q177054)$\"]$box;" +
            "nwr[\"amenity\"=\"fast_food\"][\"brand\"~\"^(McDonald's|Burger King)$\"]$box;" +
            ");out center;"
        return overpass(query).mapNotNull { e ->
            val (lat, lon) = coords(e) ?: return@mapNotNull null
            val tags = e.optJSONObject("tags")
            val brand = brandOf(tags) ?: return@mapNotNull null
            val city = tags?.optString("addr:city")?.takeIf { it.isNotBlank() }
            NearbyPlace(
                id = "osm-${e.optString("type")}-${e.getLong("id")}",
                kind = NearbyKind.FAST_FOOD_BRAND,
                name = if (brand == "mcdo") "McDonald's" else "Burger King",
                lat = lat, lon = lon,
                detail = listOfNotNull(city, openingInfo(tags)).joinToString(" · ").ifBlank { null },
                brand = brand,
            )
        }.distinctBy { it.id }
    }

    /** Restaurants et fast-foods dans un rayon (mètres). */
    fun restaurants(points: List<DoubleArray>, radius: Int): List<NearbyPlace> {
        val parts = points.joinToString("") { "nwr[\"amenity\"~\"^(restaurant|fast_food)$\"][\"name\"](around:$radius,${it[0]},${it[1]});" }
        val query = "[out:json][timeout:25];($parts);out center 200;"
        return overpass(query).mapNotNull { e ->
            val (la, lo) = coords(e) ?: return@mapNotNull null
            val tags = e.optJSONObject("tags") ?: return@mapNotNull null
            val cuisine = tags.optString("cuisine").split(';').firstOrNull()?.replace('_', ' ')?.takeIf { it.isNotBlank() }
            val type = if (tags.optString("amenity") == "fast_food") "Fast-food" else "Restaurant"
            NearbyPlace(
                id = "osm-${e.optString("type")}-${e.getLong("id")}",
                kind = NearbyKind.RESTAURANT,
                name = tags.optString("name"),
                lat = la, lon = lo,
                detail = listOfNotNull(type, cuisine, openingInfo(tags)).joinToString(" · "),
                brand = brandOf(tags),
            )
        }.distinctBy { it.id }
    }

    private fun openingInfo(tags: JSONObject?): String? =
        tags?.optString("opening_hours")?.takeIf { it == "24/7" }?.let { "ouvert 24h/24" }

    /**
     * Stations-service avec prix officiels (data.economie.gouv.fr, gratuit, sans clé).
     * fuel : clé du carburant préféré (gazole, sp95, e10...).
     */
    fun stations(lat: Double, lon: Double, radiusKm: Int, fuel: String): List<NearbyPlace> {
        val where = "within_distance(geom, geom'POINT($lon $lat)', ${radiusKm}km)"
        val url = "$FUEL_API?where=${Net.enc(where)}&limit=100"
        val results = JSONObject(Net.request(url)).optJSONArray("results") ?: return emptyList()
        val fuelLabel = fuels.firstOrNull { it.first == fuel }?.second ?: fuel
        val list = ArrayList<NearbyPlace>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val geom = r.optJSONObject("geom")
            val la = geom?.optDouble("lat")?.takeIf { !it.isNaN() } ?: r.optString("latitude").toDoubleOrNull()?.div(100_000) ?: continue
            val lo = geom?.optDouble("lon")?.takeIf { !it.isNaN() } ?: r.optString("longitude").toDoubleOrNull()?.div(100_000) ?: continue
            if (la.isNaN() || lo.isNaN()) continue
            val prices = LinkedHashMap<String, Double>()
            for ((k, _) in fuels) r.optDouble("${k}_prix").takeIf { !it.isNaN() && it > 0 }?.let { prices[k] = it }
            val updated = fuels.mapNotNull { (k, _) -> r.optString("${k}_maj").takeIf { it.isNotBlank() && it != "null" } }.maxOrNull()
            val price = prices[fuel]
            val others = fuels.filter { it.first != fuel }
                .mapNotNull { (k, label) -> r.optDouble("${k}_prix").takeIf { !it.isNaN() && it > 0 }?.let { "$label ${fmtPrice(it)}" } }
            val address = listOf(r.optString("adresse"), r.optString("ville")).filter { it.isNotBlank() }.joinToString(", ")
            val main = if (price != null) "$fuelLabel ${fmtPrice(price)}" else "$fuelLabel indisponible"
            list.add(
                NearbyPlace(
                    id = "fuel-${r.optString("id", "$i")}",
                    kind = NearbyKind.STATION,
                    name = "Station · ${capitalize(r.optString("ville"))}".trimEnd(' ', '·'),
                    lat = la, lon = lo,
                    detail = listOf(main, address, others.joinToString(", ")).filter { it.isNotBlank() }.joinToString("\n"),
                    price = price,
                    prices = prices,
                    address = address.ifBlank { null },
                    updated = updated,
                    city = capitalize(r.optString("ville")).ifBlank { null },
                )
            )
        }
        return list
    }

    /** Repli si l'API des prix ne répond pas : stations OpenStreetMap sans prix. */
    fun stationsOsm(lat: Double, lon: Double, radius: Int): List<NearbyPlace> {
        val query = "[out:json][timeout:25];nwr[\"amenity\"=\"fuel\"](around:$radius,$lat,$lon);out center 100;"
        return overpass(query).mapNotNull { e ->
            val (la, lo) = coords(e) ?: return@mapNotNull null
            val tags = e.optJSONObject("tags")
            val name = listOfNotNull(tags?.optString("brand"), tags?.optString("name"))
                .firstOrNull { it.isNotBlank() } ?: "Station-service"
            NearbyPlace("osm-${e.optString("type")}-${e.getLong("id")}", NearbyKind.STATION, name, la, lo, "Prix non disponible")
        }
    }

    fun fmtPrice(p: Double) = String.format(Locale.FRANCE, "%.3f €", p)

    private fun capitalize(s: String) = s.lowercase(Locale.FRANCE).split(' ', '-').joinToString(" ") {
        it.replaceFirstChar { c -> c.titlecase(Locale.FRANCE) }
    }

    /** Infos OpenStreetMap d'un lieu : téléphone, site, horaires, cuisine, adresse. */
    fun osmDetails(name: String, lat: Double, lon: Double): PlaceInfo? {
        val query = "[out:json][timeout:10];nwr(around:80,$lat,$lon)[\"name\"];out tags center 30;"
        val candidates = overpass(query, quick = true).mapNotNull { e ->
            val tags = e.optJSONObject("tags") ?: return@mapNotNull null
            val (la, lo) = coords(e) ?: return@mapNotNull null
            Triple(tags, Geo.distance(lat, lon, la, lo), tags.optString("name"))
        }
        val best = candidates.filter { it.third.equals(name, ignoreCase = true) }.minByOrNull { it.second }
            ?: candidates.filter { it.first.has("amenity") }.minByOrNull { it.second }
            ?: return null
        val t = best.first
        fun tag(vararg keys: String) = keys.map { t.optString(it) }.firstOrNull { it.isNotBlank() }
        val street = listOfNotNull(tag("addr:housenumber"), tag("addr:street")).joinToString(" ")
        val address = listOf(street, listOfNotNull(tag("addr:postcode"), tag("addr:city")).joinToString(" "))
            .filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
        return PlaceInfo(
            phone = tag("phone", "contact:phone"),
            website = tag("website", "contact:website"),
            hours = tag("opening_hours"),
            cuisine = tag("cuisine")?.split(';')?.joinToString(", ") { cuisineLabel(it.trim()) },
            address = address,
            takeaway = tag("takeaway") == "yes",
            delivery = tag("delivery") == "yes",
        )
    }

    private fun cuisineLabel(c: String) = when (c) {
        "burger" -> "burgers"
        "pizza" -> "pizzas"
        "french" -> "française"
        "italian" -> "italienne"
        "japanese" -> "japonaise"
        "sushi" -> "sushis"
        "chinese" -> "chinoise"
        "asian" -> "asiatique"
        "indian" -> "indienne"
        "kebab" -> "kebab"
        "chicken" -> "poulet"
        "sandwich" -> "sandwichs"
        "regional" -> "régionale"
        "crepe" -> "crêpes"
        "seafood" -> "fruits de mer"
        "steak_house" -> "grill"
        "coffee_shop" -> "café"
        else -> c.replace('_', ' ')
    }

    /**
     * Note, avis et coordonnées via Google Places (clé facultative ; la formule gratuite
     * mensuelle de Google suffit pour un usage personnel).
     */
    fun google(key: String, name: String, lat: Double, lon: Double): GoogleInfo? {
        val body = JSONObject()
            .put("textQuery", name)
            .put("languageCode", "fr")
            .put("maxResultCount", 1)
            .put("locationBias", JSONObject().put("circle", JSONObject()
                .put("center", JSONObject().put("latitude", lat).put("longitude", lon))
                .put("radius", 300.0)))
        val fields = "places.displayName,places.rating,places.userRatingCount,places.nationalPhoneNumber," +
            "places.websiteUri,places.currentOpeningHours,places.priceLevel,places.googleMapsUri,places.formattedAddress,places.location"
        val json = JSONObject(Net.request(
            "https://places.googleapis.com/v1/places:searchText", body.toString(),
            headers = mapOf("X-Goog-Api-Key" to key, "X-Goog-FieldMask" to fields),
        ))
        val p = json.optJSONArray("places")?.optJSONObject(0) ?: return null
        val loc = p.optJSONObject("location")
        if (loc != null && Geo.distance(lat, lon, loc.optDouble("latitude"), loc.optDouble("longitude")) > 500) return null
        val hours = p.optJSONObject("currentOpeningHours")
        val days = hours?.optJSONArray("weekdayDescriptions")
        return GoogleInfo(
            rating = p.optDouble("rating").takeIf { !it.isNaN() },
            ratingCount = p.optInt("userRatingCount", 0),
            phone = p.optString("nationalPhoneNumber").ifBlank { null },
            website = p.optString("websiteUri").ifBlank { null },
            openNow = if (hours?.has("openNow") == true) hours.optBoolean("openNow") else null,
            weekHours = (0 until (days?.length() ?: 0)).map { days!!.optString(it) },
            priceLevel = when (p.optString("priceLevel")) {
                "PRICE_LEVEL_INEXPENSIVE" -> "€"
                "PRICE_LEVEL_MODERATE" -> "€€"
                "PRICE_LEVEL_EXPENSIVE" -> "€€€"
                "PRICE_LEVEL_VERY_EXPENSIVE" -> "€€€€"
                else -> null
            },
            mapsUri = p.optString("googleMapsUri").ifBlank { null },
            address = p.optString("formattedAddress").ifBlank { null },
        )
    }
}

class PlaceInfo(
    val phone: String?,
    val website: String?,
    val hours: String?,
    val cuisine: String?,
    val address: String?,
    val takeaway: Boolean,
    val delivery: Boolean,
)

class GoogleInfo(
    val rating: Double?,
    val ratingCount: Int,
    val phone: String?,
    val website: String?,
    val openNow: Boolean?,
    val weekHours: List<String>,
    val priceLevel: String?,
    val mapsUri: String?,
    val address: String?,
)
