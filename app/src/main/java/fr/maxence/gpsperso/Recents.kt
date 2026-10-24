package fr.maxence.gpsperso

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Dernières destinations (15 au plus), la plus récente en premier. */
class RecentStore(context: Context) {
    private val prefs = context.getSharedPreferences("recents", Context.MODE_PRIVATE)

    fun all(): List<Place> {
        val array = try { JSONArray(prefs.getString("items", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Place(o.optString("name"), o.optString("address"), o.getDouble("lat"), o.getDouble("lon"))
        }
    }

    fun add(place: Place) {
        val list = listOf(place) + all().filter { Geo.distance(it.lat, it.lon, place.lat, place.lon) > 30 }
        save(list.take(15))
    }

    fun remove(place: Place) = save(all().filter { it.lat != place.lat || it.lon != place.lon })

    fun clear() = save(emptyList())

    private fun save(list: List<Place>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("name", it.name).put("address", it.address).put("lat", it.lat).put("lon", it.lon))
        }
        prefs.edit().putString("items", array.toString()).apply()
    }
}
