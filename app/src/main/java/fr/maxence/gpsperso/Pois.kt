package fr.maxence.gpsperso

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class PoiCategory(val label: String, val color: String) {
    FAVORI("Favori", "#F4B400"),
    MAISON("Maison", "#0F9D58"),
    TRAVAIL("Travail", "#795548"),
    STATION("Station-service", "#DB4437"),
    RECHARGE("Recharge", "#00897B"),
    RESTAURANT("Restaurant", "#FF7043"),
    PARKING("Parking", "#4285F4"),
    AUTRE("Autre", "#9C27B0"),
}

data class Poi(val id: String, val name: String, val category: PoiCategory, val lat: Double, val lon: Double) {
    fun toPlace() = Place(name, category.label, lat, lon)
}

/** Points d'intérêt personnels, enregistrés sur le téléphone. */
class PoiStore(context: Context) {
    private val prefs = context.getSharedPreferences("pois", Context.MODE_PRIVATE)

    fun all(): List<Poi> {
        val array = try { JSONArray(prefs.getString("items", "[]")) } catch (e: Exception) { JSONArray() }
        val list = ArrayList<Poi>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val category = try { PoiCategory.valueOf(o.optString("category")) } catch (e: Exception) { PoiCategory.AUTRE }
            list.add(Poi(o.getString("id"), o.getString("name"), category, o.getDouble("lat"), o.getDouble("lon")))
        }
        return list
    }

    fun add(name: String, category: PoiCategory, lat: Double, lon: Double): Poi {
        val poi = Poi(UUID.randomUUID().toString(), name, category, lat, lon)
        save(all() + poi)
        return poi
    }

    fun remove(id: String) = save(all().filter { it.id != id })

    fun search(query: String): List<Poi> {
        val q = query.trim().lowercase()
        return all().filter { it.name.lowercase().contains(q) || it.category.label.lowercase().contains(q) }
    }

    private fun save(list: List<Poi>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("category", it.category.name)
                .put("lat", it.lat).put("lon", it.lon))
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
