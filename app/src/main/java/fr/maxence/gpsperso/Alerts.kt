package fr.maxence.gpsperso

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Types d'alertes façon Waze. ttlMinutes = 0 : pas d'expiration pour un signalement.
 * announce = false : affiché sur la carte sans annonce vocale.
 */
enum class AlertType(
    val label: String,
    val spoken: String,
    val color: String,
    val ttlMinutes: Long,
    val announce: Boolean = true,
) {
    RADAR_FIXE("Radar", "Radar", "#D93025", 0),
    RADAR_FEU("Radar feu rouge", "Radar feu rouge", "#A50E0E", 0),
    RADAR_MOBILE("Radar mobile", "Zone de radar mobile", "#F29900", 240),
    POLICE("Police", "Police signalée", "#1A73E8", 120),
    ACCIDENT("Accident", "Accident signalé", "#B31412", 120),
    DANGER("Danger", "Danger signalé", "#F9AB00", 720),
    TRAVAUX("Travaux", "Travaux", "#E8710A", 1440),
    ROUTE_FERMEE("Route fermée", "Route fermée", "#5F6368", 0),
    BOUCHON("Bouchon", "Bouchon", "#9334E6", 60),
    BORNE("Borne", "", "#0F9D58", 0, announce = false);

    companion object {
        /** Types que l'utilisateur peut signaler lui-même. */
        val reportable = listOf(POLICE, RADAR_MOBILE, ACCIDENT, DANGER, TRAVAUX, BOUCHON)
    }
}

/** source : "moi" (signalement perso), "osm", "lufop" ou "tomtom". */
data class Alert(
    val id: String,
    val type: AlertType,
    val lat: Double,
    val lon: Double,
    val createdAt: Long,
    val detail: String? = null,
    val source: String = "moi",
    val description: String? = null,
) {
    val title: String get() = if (detail != null) "${type.label} $detail" else type.label

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.name).put("lat", lat).put("lon", lon).put("ts", createdAt)
        .put("detail", detail ?: "").put("source", source).put("description", description ?: "")

    companion object {
        fun fromJson(o: JSONObject): Alert? {
            val type = try { AlertType.valueOf(o.getString("type")) } catch (e: Exception) { return null }
            return Alert(
                id = o.getString("id"),
                type = type,
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                createdAt = o.optLong("ts"),
                detail = o.optString("detail").ifBlank { null },
                source = o.optString("source", "moi"),
                description = o.optString("description").ifBlank { null },
            )
        }
    }
}

/** Signalements personnels, gardés sur le téléphone jusqu'à leur expiration. */
class AlertStore(context: Context) {
    private val prefs = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    fun all(): List<Alert> {
        val now = System.currentTimeMillis()
        val raw = prefs.getString("items", "[]") ?: "[]"
        val array = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        val list = ArrayList<Alert>()
        for (i in 0 until array.length()) {
            val alert = array.optJSONObject(i)?.let { Alert.fromJson(it) } ?: continue
            val ttl = alert.type.ttlMinutes
            if (ttl == 0L || now - alert.createdAt < ttl * 60_000) list.add(alert)
        }
        if (list.size != array.length()) save(list)
        return list
    }

    fun add(type: AlertType, lat: Double, lon: Double): Alert {
        val alert = Alert(UUID.randomUUID().toString(), type, lat, lon, System.currentTimeMillis())
        save(all() + alert)
        return alert
    }

    fun remove(id: String) = save(all().filter { it.id != id })

    private fun save(list: List<Alert>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
