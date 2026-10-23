package fr.maxence.gpsperso

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LufopSyncResult(val added: List<Alert>, val total: Int, val full: Boolean)

/**
 * Base radars Lufop (clé API personnelle) : téléchargée en entier une première fois,
 * puis mise à jour chaque jour avec seulement les nouveautés (paramètre since).
 */
class LufopStore(private val context: Context) {
    private val file = File(context.filesDir, "lufop.json")
    private val prefs = context.getSharedPreferences("lufop", Context.MODE_PRIVATE)
    private var cache: List<Alert>? = null

    val lastSync: String? get() = prefs.getString("last_sync", null)

    fun all(): List<Alert> {
        cache?.let { return it }
        val list = ArrayList<Alert>()
        if (file.exists()) {
            val array = try { JSONArray(file.readText()) } catch (e: Exception) { JSONArray() }
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { Alert.fromJson(it) }?.let { list.add(it) }
        }
        cache = list
        return list
    }

    /** Radars ajoutés ou modifiés lors des dernières mises à jour. */
    fun recent(): List<Alert> {
        val ids = prefs.getStringSet("recent_ids", emptySet()) ?: emptySet()
        return all().filter { it.id in ids }
    }

    fun needsSync(): Boolean = lastSync != today()

    fun sync(key: String, forceFull: Boolean = false): LufopSyncResult {
        val existing = if (forceFull) emptyList() else all()
        val full = existing.isEmpty()
        var url = "https://api.lufop.net/api?key=${Net.enc(key)}&format=json&pays=fr&nbr=10000"
        if (!full && lastSync != null) url += "&since=$lastSync"
        val raw = Net.request(url)
        val parsed = parse(raw)
        if (parsed.isEmpty() && full) {
            throw IllegalStateException("Réponse Lufop non reconnue : ${raw.take(300)}")
        }
        val byId = LinkedHashMap<String, Alert>()
        existing.forEach { byId[it.id] = it }
        val added = parsed.filter { byId[it.id] != it }
        parsed.forEach { byId[it.id] = it }
        val merged = byId.values.toList()
        val array = JSONArray()
        merged.forEach { array.put(it.toJson()) }
        file.writeText(array.toString())
        cache = merged
        val editor = prefs.edit().putString("last_sync", today())
        if (!full) editor.putStringSet("recent_ids", added.map { it.id }.toSet())
        editor.apply()
        return LufopSyncResult(if (full) emptyList() else added, merged.size, full)
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        /**
         * Lecture tolérante : on cherche le premier tableau d'objets contenant des coordonnées,
         * quel que soit le nom exact des champs.
         */
        fun parse(raw: String): List<Alert> {
            val root: Any = try {
                if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw)
            } catch (e: Exception) {
                return emptyList()
            }
            val array = findArray(root) ?: return emptyList()
            val list = ArrayList<Alert>()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val lat = number(o, "lat", "latitude") ?: continue
                val lon = number(o, "lng", "lon", "long", "longitude") ?: continue
                val id = text(o, "id", "ID", "idradar") ?: "${lat}_$lon"
                val typeText = (text(o, "type", "categorie", "category", "nom") ?: "").lowercase()
                val type = when {
                    "feu" in typeText || "red" in typeText -> AlertType.RADAR_FEU
                    "mobile" in typeText || "chantier" in typeText -> AlertType.RADAR_MOBILE
                    else -> AlertType.RADAR_FIXE
                }
                val speed = text(o, "vitesse", "speed", "vit", "vma")?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
                val place = listOfNotNull(text(o, "voie", "street", "rue"), text(o, "commune", "city", "ville"))
                    .joinToString(", ").ifBlank { null }
                list.add(
                    Alert(
                        id = "lufop-$id",
                        type = type,
                        lat = lat,
                        lon = lon,
                        createdAt = 0,
                        detail = speed,
                        source = "lufop",
                        description = listOfNotNull(typeText.ifBlank { null }, place).joinToString(" · ").ifBlank { null },
                    )
                )
            }
            return list
        }

        private fun findArray(node: Any?, depth: Int = 0): JSONArray? {
            if (depth > 4) return null
            when (node) {
                is JSONArray -> {
                    if (node.length() > 0 && node.optJSONObject(0)?.let { number(it, "lat", "latitude") != null } == true) return node
                    for (i in 0 until node.length()) findArray(node.opt(i), depth + 1)?.let { return it }
                    if (node.length() == 0) return node
                }
                is JSONObject -> {
                    val keys = node.keys()
                    while (keys.hasNext()) findArray(node.opt(keys.next()), depth + 1)?.let { return it }
                }
            }
            return null
        }

        private fun findKey(o: JSONObject, names: Array<out String>): String? {
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (names.any { it.equals(k, ignoreCase = true) }) return k
            }
            return null
        }

        private fun number(o: JSONObject, vararg names: String): Double? {
            val k = findKey(o, names) ?: return null
            return o.opt(k)?.toString()?.replace(',', '.')?.toDoubleOrNull()
        }

        private fun text(o: JSONObject, vararg names: String): String? {
            val k = findKey(o, names) ?: return null
            return o.opt(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        }
    }
}
