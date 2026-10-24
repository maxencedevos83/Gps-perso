package fr.maxence.gpsperso

import org.json.JSONObject
import java.util.Locale

/** HERE Traffic (offre gratuite, clé sur platform.here.com) : deuxième source d'incidents. */
object Here {
    fun incidents(key: String, south: Double, west: Double, north: Double, east: Double): List<Alert> {
        val bbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", west, south, east, north)
        val url = "https://data.traffic.hereapi.com/v7/incidents?in=bbox:$bbox&locationReferencing=shape&lang=fr-FR&apiKey=${Net.enc(key)}"
        val results = JSONObject(Net.request(url)).optJSONArray("results") ?: return emptyList()
        val list = ArrayList<Alert>()
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val d = r.optJSONObject("incidentDetails") ?: continue
            val closed = d.optBoolean("roadClosed")
            val (type, label) = when (d.optString("type")) {
                "accident" -> AlertType.ACCIDENT to "Accident"
                "construction" -> AlertType.TRAVAUX to "Travaux"
                "congestion" -> AlertType.BOUCHON to "Bouchon"
                "roadClosure" -> AlertType.ROUTE_FERMEE to "Route fermée"
                "disabledVehicle" -> AlertType.DANGER to "Véhicule arrêté"
                "roadHazard" -> AlertType.DANGER to "Danger sur la route"
                "weather" -> AlertType.DANGER to "Conditions météo"
                "laneRestriction" -> AlertType.TRAVAUX to "Voie fermée"
                "massEvent", "plannedEvent" -> AlertType.BOUCHON to "Événement"
                else -> AlertType.DANGER to "Incident"
            }.let { if (closed) AlertType.ROUTE_FERMEE to "Route fermée" else it }
            val link = r.optJSONObject("location")?.optJSONObject("shape")?.optJSONArray("links")?.optJSONObject(0)
            val p = link?.optJSONArray("points")?.optJSONObject(0) ?: continue
            val description = d.optJSONObject("description")?.optString("value")?.ifBlank { null }
                ?: d.optJSONObject("summary")?.optString("value")?.ifBlank { null }
            list += Alert(
                id = "here-" + d.optString("id", "${p.optDouble("lat")}_${p.optDouble("lng")}"),
                type = type,
                lat = p.optDouble("lat"),
                lon = p.optDouble("lng"),
                createdAt = System.currentTimeMillis(),
                detail = null,
                source = "here",
                description = listOfNotNull(label, description).joinToString("\n"),
            )
        }
        return list
    }
}
