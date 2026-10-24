package fr.maxence.gpsperso

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

class ImportResult(val imported: Int, val added: List<Alert>, val total: Int, val firstImport: Boolean)

/**
 * Bases radars / bornes importées (API Lufop, fichiers lufop.net ou tout fichier de POI CSV, GPX,
 * KML, OV2, JSON, éventuellement dans un ZIP). Chaque source est un « jeu » séparé : réimporter
 * un fichier du même nom le remplace, sans toucher aux autres (France, Espagne...).
 */
class LufopStore(context: Context) {
    private val file = File(context.filesDir, "lufop.json")
    private val prefs = context.getSharedPreferences("lufop", Context.MODE_PRIVATE)
    @Volatile private var datasets: Map<String, List<Alert>>? = null
    @Volatile private var cache: List<Alert>? = null

    val lastImport: String? get() = prefs.getString("last_import", null)

    @Synchronized
    private fun load(): Map<String, List<Alert>> {
        datasets?.let { return it }
        val map = LinkedHashMap<String, List<Alert>>()
        if (file.exists()) {
            val text = try { file.readText() } catch (e: Exception) { "" }
            fun parseArray(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> Alert.fromJson(o) } }
            try {
                if (text.trimStart().startsWith("[")) {
                    map["ancien"] = parseArray(JSONArray(text)) // format des versions précédentes
                } else {
                    val o = JSONObject(text)
                    o.keys().forEach { k -> map[k] = parseArray(o.getJSONArray(k)) }
                }
            } catch (e: Exception) {
                // fichier abîmé : on repart de zéro
            }
        }
        datasets = map
        return map
    }

    fun all(): List<Alert> {
        cache?.let { return it }
        val list = load().values.flatten().distinctBy { it.id }
        cache = list
        return list
    }

    /** Jeux importés avec leur nombre de points. */
    fun datasetSizes(): Map<String, Int> = load().mapValues { it.value.size }

    fun has(dataset: String) = load().containsKey(dataset)

    /** Éléments apparus lors du dernier import. */
    fun recent(): List<Alert> {
        val ids = prefs.getStringSet("recent_ids", emptySet()) ?: emptySet()
        return all().filter { it.id in ids }
    }

    /** Remplace le jeu « dataset » et repère les nouveautés par rapport à sa version précédente. */
    @Synchronized
    fun import(dataset: String, items: List<Alert>): ImportResult {
        val map = LinkedHashMap(load())
        val previous = map[dataset].orEmpty()
        val previousIds = previous.map { it.id }.toSet()
        val added = if (previous.isEmpty()) emptyList() else items.filter { it.id !in previousIds }
        map[dataset] = items
        save(map)
        prefs.edit()
            .putString("last_import", SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date()))
            .putStringSet("recent_ids", added.map { it.id }.toSet())
            .apply()
        return ImportResult(items.size, added, all().size, previous.isEmpty())
    }

    @Synchronized
    fun remove(dataset: String) {
        val map = LinkedHashMap(load())
        map.remove(dataset)
        save(map)
    }

    @Synchronized
    fun clear() {
        file.delete()
        datasets = emptyMap()
        cache = emptyList()
        prefs.edit().clear().apply()
    }

    private fun save(map: Map<String, List<Alert>>) {
        val o = JSONObject()
        map.forEach { (k, list) -> o.put(k, JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }) }
        file.writeText(o.toString())
        datasets = map
        cache = null
    }

    companion object {
        /** Nom de jeu tiré du nom de fichier : « ESRADARES.csv » → « ESRADARES ». */
        fun datasetName(fileName: String) = fileName.substringAfterLast('/').substringBeforeLast('.').ifBlank { "fichier" }
    }
}

/** Téléchargement direct de la base via l'API Lufop (clé personnelle, 200 requêtes par jour). */
object LufopApi {
    fun download(key: String): List<Alert> {
        val raw = Net.request("https://api.lufop.net/api?key=${Net.enc(key)}&format=json&pays=fr&nbr=10000")
        val items = RadarFile.parseJson("lufop-api", raw)
        if (items.isEmpty()) throw IllegalStateException("Réponse Lufop non reconnue : ${raw.take(300)}")
        return items
    }
}

object RadarFile {
    /** Lit un fichier (ou une archive ZIP) et renvoie les points trouvés. */
    fun parse(fileName: String, bytes: ByteArray): List<Alert> {
        val name = fileName.lowercase(Locale.ROOT)
        if (name.endsWith(".zip") || (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())) {
            val all = ArrayList<Alert>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) all += parse(entry.name, zip.readBytes())
                }
            }
            return all.distinctBy { it.id }
        }
        return when {
            name.endsWith(".json") -> parseJson(fileName, decode(bytes))
            name.endsWith(".ov2") -> parseOv2(fileName, bytes)
            name.endsWith(".gpx") -> parseGpx(fileName, decode(bytes))
            name.endsWith(".kml") -> parseKml(fileName, decode(bytes))
            name.endsWith(".csv") || name.endsWith(".txt") || name.endsWith(".asc") -> parseCsv(fileName, decode(bytes))
            else -> {
                val text = decode(bytes)
                when {
                    text.trimStart().startsWith("{") || text.trimStart().startsWith("[") -> parseJson(fileName, text)
                    "<gpx" in text -> parseGpx(fileName, text)
                    "<kml" in text -> parseKml(fileName, text)
                    else -> parseCsv(fileName, text)
                }
            }
        }
    }

    /**
     * JSON (API Lufop ou export) : on cherche le premier tableau d'objets avec des coordonnées,
     * quel que soit le nom exact des champs.
     */
    fun parseJson(fileName: String, raw: String): List<Alert> {
        val root: Any = try {
            if (raw.trimStart().startsWith("[")) org.json.JSONArray(raw) else org.json.JSONObject(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        val array = findArray(root) ?: return emptyList()
        val out = ArrayList<Alert>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val lat = number(o, "lat", "latitude") ?: continue
            val lon = number(o, "lng", "lon", "long", "longitude") ?: continue
            val typeText = listOfNotNull(text(o, "type", "categorie", "category", "nom", "name"), text(o, "flash")).joinToString(" ")
            val place = listOfNotNull(text(o, "voie", "street", "rue"), text(o, "commune", "city", "ville")).joinToString(", ")
            val speed = text(o, "vitesse", "speed", "vit", "vma")?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
            make(fileName, lat, lon, listOf(typeText, place).filter { it.isNotBlank() }.joinToString(" · "), speed)?.let(out::add)
        }
        return out
    }

    private fun findArray(node: Any?, depth: Int = 0): org.json.JSONArray? {
        if (depth > 4) return null
        when (node) {
            is org.json.JSONArray -> {
                if (node.length() > 0 && node.optJSONObject(0)?.let { number(it, "lat", "latitude") != null } == true) return node
                for (i in 0 until node.length()) findArray(node.opt(i), depth + 1)?.let { return it }
            }
            is org.json.JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) findArray(node.opt(keys.next()), depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun findKey(o: org.json.JSONObject, names: Array<out String>): String? {
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (names.any { it.equals(k, ignoreCase = true) }) return k
        }
        return null
    }

    private fun number(o: org.json.JSONObject, vararg names: String): Double? {
        val k = findKey(o, names) ?: return null
        return o.opt(k)?.toString()?.replace(',', '.')?.toDoubleOrNull()
    }

    private fun text(o: org.json.JSONObject, vararg names: String): String? {
        val k = findKey(o, names) ?: return null
        return o.opt(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        return if ('�' in utf8) String(bytes, Charsets.ISO_8859_1) else utf8
    }

    /** Type d'alerte deviné d'après le nom du fichier et du point. */
    private fun typeFor(fileName: String, label: String): AlertType {
        val t = (fileName + " " + label).lowercase(Locale.FRANCE)
        return when {
            "borne" in t || "recharge" in t || "irve" in t || "electri" in t || "électri" in t -> AlertType.BORNE
            "feu" in t || "red" in t -> AlertType.RADAR_FEU
            "mobile" in t || "embarqu" in t -> AlertType.RADAR_MOBILE
            "danger" in t || "accident" in t -> AlertType.DANGER
            else -> AlertType.RADAR_FIXE
        }
    }

    private val speedRegex = Regex("(?<!\\d)(20|25|30|40|45|50|60|70|80|90|100|110|120|130)(?!\\d)")

    private fun make(fileName: String, lat: Double, lon: Double, label: String, speed: String? = null): Alert? {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return null
        val type = typeFor(fileName.substringAfterLast('/'), label)
        val detectedSpeed = speed ?: if (type == AlertType.BORNE) null else
            speedRegex.find(label)?.value ?: speedRegex.find(fileName.substringAfterLast('/'))?.value
        val id = String.format(Locale.US, "lufop-%.5f_%.5f", lat, lon)
        return Alert(
            id = id,
            type = type,
            lat = lat,
            lon = lon,
            createdAt = 0,
            detail = detectedSpeed,
            source = "lufop",
            description = label.trim().ifBlank { null },
        )
    }

    private fun parseGpx(fileName: String, text: String): List<Alert> {
        val wpt = Regex("<wpt[^>]*?lat=\"([-0-9.]+)\"[^>]*?lon=\"([-0-9.]+)\"[^>]*>(.*?)</wpt>", RegexOption.DOT_MATCHES_ALL)
        val wptLonFirst = Regex("<wpt[^>]*?lon=\"([-0-9.]+)\"[^>]*?lat=\"([-0-9.]+)\"[^>]*>(.*?)</wpt>", RegexOption.DOT_MATCHES_ALL)
        val nameRe = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
        val out = ArrayList<Alert>()
        wpt.findAll(text).forEach { m ->
            val label = nameRe.find(m.groupValues[3])?.groupValues?.get(1)?.let(::unescape) ?: ""
            make(fileName, m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), label)?.let(out::add)
        }
        if (out.isEmpty()) wptLonFirst.findAll(text).forEach { m ->
            val label = nameRe.find(m.groupValues[3])?.groupValues?.get(1)?.let(::unescape) ?: ""
            make(fileName, m.groupValues[2].toDouble(), m.groupValues[1].toDouble(), label)?.let(out::add)
        }
        return out
    }

    private fun parseKml(fileName: String, text: String): List<Alert> {
        val placemark = Regex("<Placemark.*?</Placemark>", RegexOption.DOT_MATCHES_ALL)
        val nameRe = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL)
        val coordRe = Regex("<coordinates>\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)")
        return placemark.findAll(text).mapNotNull { m ->
            val c = coordRe.find(m.value) ?: return@mapNotNull null
            val label = nameRe.find(m.value)?.groupValues?.get(1)?.let(::unescape) ?: ""
            make(fileName, c.groupValues[2].toDouble(), c.groupValues[1].toDouble(), label)
        }.toList()
    }

    private fun unescape(s: String) = s.replace("<![CDATA[", "").replace("]]>", "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").trim()

    /**
     * CSV / TXT / ASC : on repère les deux premiers nombres qui ressemblent à des coordonnées,
     * dans l'ordre lon,lat (Garmin, iGO) ou lat,lon. Le reste sert de libellé.
     */
    private fun parseCsv(fileName: String, text: String): List<Alert> {
        val out = ArrayList<Alert>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            // Ligne entière entre guillemets avec guillemets doublés à l'intérieur (exports Lufop)
            val unwrapped = if (line.length > 2 && line.startsWith("\"") && line.endsWith("\"") && "\"\"" in line) {
                line.substring(1, line.length - 1).replace("\"\"", "\"")
            } else line
            val fields = splitCsv(unwrapped)
            val numbers = fields.mapIndexedNotNull { i, f -> f.replace(',', '.').toDoubleOrNull()?.let { i to it } }
            if (numbers.size < 2) continue
            val (i1, a) = numbers[0]
            val (i2, b) = numbers[1]
            val (lat, lon) = when {
                a in 41.0..52.0 && b in -6.0..10.0 -> a to b
                b in 41.0..52.0 && a in -6.0..10.0 -> b to a
                Math.abs(a) <= 180 && Math.abs(b) <= 90 -> b to a // format lon,lat par défaut
                else -> continue
            }
            val label = fields.filterIndexed { i, f -> i != i1 && i != i2 && f.replace(',', '.').toDoubleOrNull() == null }
                .joinToString(" ").trim()
            // iGO : X,Y,TYPE,SPEED,... → la vitesse est un nombre plausible après les coordonnées
            val speed = numbers.drop(2).map { it.second }.firstOrNull { it in 20.0..130.0 && it % 10 == 0.0 }?.toInt()?.toString()
            make(fileName, lat, lon, label, speed)?.let(out::add)
        }
        return out
    }

    private fun splitCsv(line: String): List<String> {
        val sep = when {
            '\t' in line -> '\t'
            line.count { it == ';' } > line.count { it == ',' } / 2 && ';' in line -> ';'
            else -> ','
        }
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var quoted = false
        for (c in line) {
            when {
                c == '"' -> quoted = !quoted
                c == sep && !quoted -> { fields.add(current.toString().trim()); current.clear() }
                else -> current.append(c)
            }
        }
        fields.add(current.toString().trim())
        return fields
    }

    /** Format binaire TomTom OV2 : enregistrements de type 2 (lon, lat ×100 000, nom). */
    private fun parseOv2(fileName: String, bytes: ByteArray): List<Alert> {
        val out = ArrayList<Alert>()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 5) {
            val start = buf.position()
            val type = buf.get().toInt() and 0xff
            if (type == 1) {
                // Enregistrement « skipper » de 21 octets : sa longueur couvre tout le bloc, on l'enjambe seul.
                if (start + 21 > bytes.size) break
                buf.position(start + 21)
                continue
            }
            val length = buf.int
            if (length < 5 || start + length > bytes.size) break
            when (type) {
                2, 3 -> if (length >= 13) {
                    val lon = buf.int / 100_000.0
                    val lat = buf.int / 100_000.0
                    val nameBytes = bytes.copyOfRange(start + 13, start + length).takeWhile { it != 0.toByte() }.toByteArray()
                    make(fileName, lat, lon, decode(nameBytes))?.let(out::add)
                }
            }
            buf.position(start + length)
        }
        return out
    }
}
