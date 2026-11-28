package fr.maxence.gpsperso

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var tomtomKey: String
        get() = prefs.getString("tomtom_key", "") ?: ""
        set(v) = prefs.edit().putString("tomtom_key", v.trim()).apply()

    var trafficOn: Boolean
        get() = prefs.getBoolean("traffic_on", true)
        set(v) = prefs.edit().putBoolean("traffic_on", v).apply()

    var showChargers: Boolean
        get() = prefs.getBoolean("show_chargers", true)
        set(v) = prefs.edit().putBoolean("show_chargers", v).apply()

    /** Zoom de la carte pendant le guidage : "tres_proche", "proche" (par défaut) ou "eloigne". */
    var guidanceZoom: String
        get() = prefs.getString("guidance_zoom", "proche") ?: "proche"
        set(v) = prefs.edit().putString("guidance_zoom", v).apply()

    val guidanceZoomBias: Double
        get() = when (guidanceZoom) {
            "tres_proche" -> 0.7
            "eloigne" -> -0.9
            else -> 0.0
        }

    /**
     * Taille des bandeaux du haut pendant le guidage : "grand" (schéma d'échangeur et grand bandeau radar),
     * "compact" (par défaut : une seule bande, radar en pastille) ou "mini" (une ligne).
     */
    var bannerSize: String
        get() = prefs.getString("banner_size", "compact") ?: "compact"
        set(v) = prefs.edit().putString("banner_size", v).apply()

    /** Zones de danger : "toutes", "exces" (seulement si je dépasse la limite) ou "jamais". */
    var dangerMode: String
        get() = prefs.getString("danger_mode", "exces") ?: "exces"
        set(v) = prefs.edit().putString("danger_mode", v).apply()

    /** Alertes marquées comme leurres : plus jamais annoncées. */
    var ignoredAlerts: Set<String>
        get() = prefs.getStringSet("ignored_alerts", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("ignored_alerts", HashSet(v)).apply()

    var announceRadars: Boolean
        get() = prefs.getBoolean("announce_radars", true)
        set(v) = prefs.edit().putBoolean("announce_radars", v).apply()

    /** Icône de position : "fleche", "voiture_rouge", "voiture_noire", "voiture_bleue", "moto", "camion". */
    var positionIcon: String
        get() = prefs.getString("position_icon", "fleche") ?: "fleche"
        set(v) = prefs.edit().putString("position_icon", v).apply()

    /** Carburant préféré pour les prix des stations. */
    var fuel: String
        get() = prefs.getString("fuel", "gazole") ?: "gazole"
        set(v) = prefs.edit().putString("fuel", v).apply()

    var showBrands: Boolean
        get() = prefs.getBoolean("show_brands", true)
        set(v) = prefs.edit().putBoolean("show_brands", v).apply()

    /** Clé Lufop : celle saisie dans Réglages, sinon celle fournie à la compilation (secret GitHub LUFOP_KEY). */
    var lufopKey: String
        get() = prefs.getString("lufop_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.LUFOP_KEY
        set(v) = prefs.edit().putString("lufop_key", v.trim()).apply()

    var lufopLastUpdate: String
        get() = prefs.getString("lufop_last_update", "") ?: ""
        set(v) = prefs.edit().putString("lufop_last_update", v).apply()

    /** Clé Google Places (facultative) : celle saisie dans Réglages, sinon le secret GitHub GOOGLE_KEY. */
    var googleKey: String
        get() = prefs.getString("google_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.GOOGLE_KEY
        set(v) = prefs.edit().putString("google_key", v.trim()).apply()

    /** Adresse de la maison (bouton 🏠). */
    var homeName: String
        get() = prefs.getString("home_name", "") ?: ""
        set(v) = prefs.edit().putString("home_name", v).apply()

    var homeLat: Double
        get() = prefs.getString("home_lat", "")?.toDoubleOrNull() ?: Double.NaN
        set(v) = prefs.edit().putString("home_lat", v.toString()).apply()

    var homeLon: Double
        get() = prefs.getString("home_lon", "")?.toDoubleOrNull() ?: Double.NaN
        set(v) = prefs.edit().putString("home_lon", v.toString()).apply()

    /** Sourdine : "all", "alerts" (radars et dangers), "guidance" (consignes de route), "mute". */
    var voiceMode: String
        get() = prefs.getString("voice_mode", "all") ?: "all"
        set(v) = prefs.edit().putString("voice_mode", v).apply()

    /** "device" : voix du téléphone ; "google" : voix Google Cloud (clé Google) ; "eleven" : voix ElevenLabs (clé ElevenLabs). */
    var voiceSource: String
        get() = prefs.getString("voice_source", "device") ?: "device"
        set(v) = prefs.edit().putString("voice_source", v).apply()

    var ttsEngine: String
        get() = prefs.getString("tts_engine", "") ?: ""
        set(v) = prefs.edit().putString("tts_engine", v).apply()

    var ttsVoice: String
        get() = prefs.getString("tts_voice", "") ?: ""
        set(v) = prefs.edit().putString("tts_voice", v).apply()

    var googleVoice: String
        get() = prefs.getString("google_voice", "fr-FR-Neural2-A") ?: "fr-FR-Neural2-A"
        set(v) = prefs.edit().putString("google_voice", v).apply()

    /** Clé ElevenLabs : celle saisie dans Réglages, sinon le secret GitHub ELEVENLABS_KEY. */
    var elevenKey: String
        get() = prefs.getString("eleven_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.ELEVENLABS_KEY
        set(v) = prefs.edit().putString("eleven_key", v.trim()).apply()

    /** Voix ElevenLabs choisie (identifiant + nom affiché). Par défaut « Alice », voix française claire. */
    var elevenVoice: String
        get() = prefs.getString("eleven_voice", "ZtJvTSnGgAdhWKQBViA1") ?: "ZtJvTSnGgAdhWKQBViA1"
        set(v) = prefs.edit().putString("eleven_voice", v).apply()

    var elevenVoiceName: String
        get() = prefs.getString("eleven_voice_name", "Alice") ?: "Alice"
        set(v) = prefs.edit().putString("eleven_voice_name", v).apply()

    var ttsRate: Float
        get() = prefs.getFloat("tts_rate", 1f)
        set(v) = prefs.edit().putFloat("tts_rate", v).apply()

    var ttsPitch: Float
        get() = prefs.getFloat("tts_pitch", 1f)
        set(v) = prefs.edit().putFloat("tts_pitch", v).apply()

    /** Options d'itinéraire. */
    var avoidTolls: Boolean
        get() = prefs.getBoolean("avoid_tolls", false)
        set(v) = prefs.edit().putBoolean("avoid_tolls", v).apply()

    var avoidHighways: Boolean
        get() = prefs.getBoolean("avoid_highways", false)
        set(v) = prefs.edit().putBoolean("avoid_highways", v).apply()

    /** Dernière version dont on a montré les nouveautés. */
    var seenVersion: Int
        get() = prefs.getInt("seen_version", 0)
        set(v) = prefs.edit().putInt("seen_version", v).apply()

    /** Clé HERE (facultative) : deuxième source d'incidents de trafic. */
    var hereKey: String
        get() = prefs.getString("here_key", "") ?: ""
        set(v) = prefs.edit().putString("here_key", v.trim()).apply()

    var showBisonFute: Boolean
        get() = prefs.getBoolean("show_bison_fute", true)
        set(v) = prefs.edit().putBoolean("show_bison_fute", v).apply()

    var showWeather: Boolean
        get() = prefs.getBoolean("show_weather", true)
        set(v) = prefs.edit().putBoolean("show_weather", v).apply()

    /** Mode nuit : "auto" (selon le soleil), "on", "off". */
    var nightMode: String
        get() = prefs.getString("night_mode", "auto") ?: "auto"
        set(v) = prefs.edit().putString("night_mode", v).apply()

    var parkLat: Double
        get() = prefs.getString("park_lat", "")?.toDoubleOrNull() ?: Double.NaN
        set(v) = prefs.edit().putString("park_lat", v.toString()).apply()

    var parkLon: Double
        get() = prefs.getString("park_lon", "")?.toDoubleOrNull() ?: Double.NaN
        set(v) = prefs.edit().putString("park_lon", v.toString()).apply()

    var parkTime: Long
        get() = prefs.getLong("park_time", 0)
        set(v) = prefs.edit().putLong("park_time", v).apply()

    val hasParking: Boolean get() = !parkLat.isNaN() && !parkLon.isNaN()
    val hasHere: Boolean get() = hereKey.isNotBlank()

    val hasHome: Boolean get() = !homeLat.isNaN() && !homeLon.isNaN()
    val hasLufop: Boolean get() = lufopKey.isNotBlank()
    val hasGoogle: Boolean get() = googleKey.isNotBlank()
    val hasEleven: Boolean get() = elevenKey.isNotBlank()

    val hasTomTom: Boolean get() = tomtomKey.isNotBlank()
}
