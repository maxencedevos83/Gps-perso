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
        get() = prefs.getString("lufop_key", null) ?: BuildConfig.LUFOP_KEY
        set(v) = prefs.edit().putString("lufop_key", v.trim()).apply()

    var lufopLastUpdate: String
        get() = prefs.getString("lufop_last_update", "") ?: ""
        set(v) = prefs.edit().putString("lufop_last_update", v).apply()

    /** Clé Google Places (facultative) pour les notes et avis des restaurants. */
    var googleKey: String
        get() = prefs.getString("google_key", "") ?: ""
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

    val hasHome: Boolean get() = !homeLat.isNaN() && !homeLon.isNaN()
    val hasLufop: Boolean get() = lufopKey.isNotBlank()
    val hasGoogle: Boolean get() = googleKey.isNotBlank()

    val hasTomTom: Boolean get() = tomtomKey.isNotBlank()
}
