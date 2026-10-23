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

    val hasTomTom: Boolean get() = tomtomKey.isNotBlank()
}
