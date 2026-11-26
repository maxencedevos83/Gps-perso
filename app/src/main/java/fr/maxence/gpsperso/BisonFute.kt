package fr.maxence.gpsperso

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Bison Futé (données ouvertes de l'État, format DATEX II) : accidents, travaux, fermetures,
 * véhicules en panne, obstacles sur les autoroutes et routes nationales. Gratuit, sans clé.
 */
object BisonFute {
    private const val URL_RRN = "https://tipi.bison-fute.gouv.fr/bison-fute-ouvert/publicationsDIR/Evenementiel-DIR/grt/RRN/content.xml"

    private class Rec(
        var type: String = "",
        var subType: String = "",
        var lat: Double = Double.NaN,
        var lon: Double = Double.NaN,
        /** Extrémités amont (from) et aval (to) d'un tronçon TPEG : donnent le sens touché. */
        var fromLat: Double = Double.NaN,
        var fromLon: Double = Double.NaN,
        var toLat: Double = Double.NaN,
        var toLon: Double = Double.NaN,
        var direction: String? = null,
        var description: String? = null,
        var road: String? = null,
        var town: String? = null,
        var start: Long = 0,
        var end: Long = 0,
    )

    fun download(): List<Alert> {
        val conn = URL(URL_RRN).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "GPSPerso/0.1 (application personnelle Android)")
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("Bison Futé : HTTP ${conn.responseCode}")
            return conn.inputStream.use { parse(it) }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTime(s: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).parse(s)?.time ?: 0
    } catch (e: Exception) {
        try { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(s)?.time ?: 0 } catch (e2: Exception) { 0 }
    }

    private fun parse(input: java.io.InputStream): List<Alert> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, "UTF-8")
        val result = LinkedHashMap<String, Alert>()
        var situationId = ""
        var rec: Rec? = null
        var commentType = ""
        var lastValue: String? = null
        var lastDescriptor: String? = null
        val path = ArrayList<String>()
        val now = System.currentTimeMillis()
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfter(':')
                    path += name
                    when (name) {
                        "situation" -> situationId = parser.getAttributeValue(null, "id") ?: ""
                        "situationRecord" -> rec = Rec(type = (parser.getAttributeValue(null, "xsi:type") ?: "").substringAfter(':'))
                    }
                }
                XmlPullParser.TEXT -> {
                    val r = rec ?: continue
                    val text = parser.text?.trim().orEmpty()
                    if (text.isEmpty() || path.isEmpty()) continue
                    when (path.last()) {
                        "latitude" -> {
                            val v = text.toDoubleOrNull() ?: Double.NaN
                            if (r.lat.isNaN()) r.lat = v
                            if ("from" in path && r.fromLat.isNaN()) r.fromLat = v
                            if ("to" in path && r.toLat.isNaN()) r.toLat = v
                        }
                        "longitude" -> {
                            val v = text.toDoubleOrNull() ?: Double.NaN
                            if (r.lon.isNaN()) r.lon = v
                            if ("from" in path && r.fromLon.isNaN()) r.fromLon = v
                            if ("to" in path && r.toLon.isNaN()) r.toLon = v
                        }
                        "tpegDirection" -> if (r.direction == null) r.direction = text
                        "value" -> lastValue = text
                        "commentType" -> commentType = text
                        "tpegOtherPointDescriptorType" -> lastDescriptor = text
                        "overallStartTime" -> r.start = parseTime(text)
                        "overallEndTime" -> r.end = parseTime(text)
                        "accidentType", "roadMaintenanceType", "roadOrCarriagewayOrLaneManagementType", "vehicleObstructionType",
                        "obstructionType", "environmentalObstructionType", "abnormalTrafficType", "animalPresenceType",
                        "weatherRelatedRoadConditionType", "infrastructureDamageType" -> if (r.subType.isEmpty()) r.subType = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfter(':')
                    val r = rec
                    if (r != null) when (name) {
                        "generalPublicComment" -> {
                            if (commentType == "description" && r.description == null) r.description = lastValue
                            commentType = ""
                        }
                        "name" -> {
                            if (lastDescriptor == "linkName" && r.road == null) r.road = lastValue
                            if (lastDescriptor == "townName" && r.town == null) r.town = lastValue
                            lastDescriptor = null
                        }
                        "situationRecord" -> {
                            toAlert(situationId, r, now)?.let { a ->
                                // Une alerte par situation : on garde la plus importante
                                val prev = result[situationId]
                                if (prev == null || rank(a.type) < rank(prev.type)) result[situationId] = a
                            }
                            rec = null
                        }
                    }
                    if (path.isNotEmpty()) path.removeAt(path.size - 1)
                }
            }
        }
        return result.values.toList()
    }

    private fun rank(t: AlertType) = when (t) {
        AlertType.ROUTE_FERMEE -> 0
        AlertType.ACCIDENT -> 1
        AlertType.DANGER -> 2
        AlertType.BOUCHON -> 3
        else -> 4
    }

    private fun toAlert(situationId: String, r: Rec, now: Long): Alert? {
        if (r.lat.isNaN() || r.lon.isNaN()) return null
        // Événements à venir ou terminés : ignorés
        if (r.start > now + 30 * 60_000 || (r.end in 1 until now)) return null
        val (type, label) = when (r.type) {
            "Accident" -> AlertType.ACCIDENT to "Accident"
            "MaintenanceWorks", "ConstructionWorks" -> AlertType.TRAVAUX to "Travaux"
            "RoadOrCarriagewayOrLaneManagement" -> when (r.subType) {
                "roadClosed", "closedPermanentlyForTheWinter", "carriagewayClosures" -> AlertType.ROUTE_FERMEE to "Route fermée"
                "laneClosures" -> AlertType.TRAVAUX to "Voie fermée"
                "singleAlternateLineTraffic" -> AlertType.TRAVAUX to "Circulation alternée"
                "contraflow" -> AlertType.TRAVAUX to "Basculement de circulation"
                "narrowLanes" -> AlertType.TRAVAUX to "Voies rétrécies"
                else -> return null // restrictions poids lourds, etc.
            }
            "VehicleObstruction" -> AlertType.DANGER to "Véhicule arrêté"
            "GeneralObstruction" -> AlertType.DANGER to "Obstacle sur la route"
            "EnvironmentalObstruction" -> AlertType.DANGER to when (r.subType) {
                "rockfalls" -> "Chute de pierres"
                "fallenTrees" -> "Arbre sur la route"
                "flooding" -> "Inondation"
                "subsidence" -> "Affaissement"
                else -> "Obstacle naturel"
            }
            "AnimalPresenceObstruction" -> AlertType.DANGER to "Animaux sur la route"
            "InfrastructureDamageObstruction" -> AlertType.DANGER to "Chaussée endommagée"
            "WeatherRelatedRoadConditions" -> AlertType.DANGER to when (r.subType) {
                "snowOnTheRoad" -> "Neige sur la route"
                "ice", "blackIce" -> "Verglas"
                else -> "Route glissante"
            }
            "AbnormalTraffic" -> AlertType.BOUCHON to "Ralentissement"
            else -> return null
        }
        val where = listOfNotNull(r.road, r.town).joinToString(" · ")
        val desc = listOfNotNull(label + if (where.isNotEmpty()) " · $where" else "", r.description).joinToString("\n")
        return Alert(
            id = "bf-$situationId",
            type = type,
            lat = r.lat,
            lon = r.lon,
            createdAt = now,
            detail = null,
            source = "bisonfute",
            description = desc,
            heading = heading(r),
        )
    }

    /** Sens de circulation touché : du point amont vers le point aval, sinon direction TPEG. */
    private fun heading(r: Rec): Double? {
        if (r.direction in setOf("bothWays", "allDirections")) return null
        if (!r.fromLat.isNaN() && !r.fromLon.isNaN() && !r.toLat.isNaN() && !r.toLon.isNaN() &&
            Geo.distance(r.fromLat, r.fromLon, r.toLat, r.toLon) >= 50) {
            return Geo.bearing(r.fromLat, r.fromLon, r.toLat, r.toLon)
        }
        return when (r.direction) {
            "northBound" -> 0.0
            "northEastBound" -> 45.0
            "eastBound" -> 90.0
            "southEastBound" -> 135.0
            "southBound" -> 180.0
            "southWestBound" -> 225.0
            "westBound" -> 270.0
            "northWestBound" -> 315.0
            else -> null
        }
    }
}
