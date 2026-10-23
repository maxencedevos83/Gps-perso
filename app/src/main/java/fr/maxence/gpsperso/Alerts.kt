package fr.maxence.gpsperso

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Types d'alertes façon Waze. ttlMinutes = 0 : alerte permanente. */
enum class AlertType(val label: String, val spoken: String, val color: String, val ttlMinutes: Long) {
    RADAR_FIXE("Radar fixe", "Radar", "#D93025", 0),
    RADAR_MOBILE("Radar mobile", "Radar mobile signalé", "#F29900", 240),
    POLICE("Police", "Police signalée", "#1A73E8", 120),
    ACCIDENT("Accident", "Accident signalé", "#B31412", 120),
    DANGER("Danger", "Danger signalé", "#F9AB00", 720),
    TRAVAUX("Travaux", "Travaux signalés", "#E8710A", 1440),
    BOUCHON("Bouchon", "Bouchon signalé", "#9334E6", 60);

    companion object {
        /** Types que l'utilisateur peut signaler lui-même. */
        val reportable = listOf(POLICE, RADAR_MOBILE, ACCIDENT, DANGER, TRAVAUX, BOUCHON)
    }
}

data class Alert(
    val id: String,
    val type: AlertType,
    val lat: Double,
    val lon: Double,
    val createdAt: Long,
    val detail: String? = null,
) {
    val title: String get() = if (detail != null) "${type.label} $detail" else type.label
}

/** Signalements personnels, gardés sur le téléphone jusqu'à leur expiration. */
class AlertStore(context: Context) {
    private val prefs = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    fun all(): List<Alert> {
        val now = System.currentTimeMillis()
        val raw = prefs.getString("items", "[]") ?: "[]"
        val list = ArrayList<Alert>()
        val array = try { JSONArray(raw) } catch (e: Exception) { JSONArray() }
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val type = try { AlertType.valueOf(o.getString("type")) } catch (e: Exception) { continue }
            val alert = Alert(o.getString("id"), type, o.getDouble("lat"), o.getDouble("lon"), o.getLong("ts"))
            if (type.ttlMinutes == 0L || now - alert.createdAt < type.ttlMinutes * 60_000) list.add(alert)
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
        list.forEach {
            array.put(JSONObject().put("id", it.id).put("type", it.type.name)
                .put("lat", it.lat).put("lon", it.lon).put("ts", it.createdAt))
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
