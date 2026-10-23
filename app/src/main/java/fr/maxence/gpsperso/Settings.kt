package fr.maxence.gpsperso

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var tomtomKey: String
        get() = prefs.getString("tomtom_key", "") ?: ""
        set(v) = prefs.edit().putString("tomtom_key", v.trim()).apply()

    var lufopKey: String
        get() = prefs.getString("lufop_key", "") ?: ""
        set(v) = prefs.edit().putString("lufop_key", v.trim()).apply()

    var trafficOn: Boolean
        get() = prefs.getBoolean("traffic_on", true)
        set(v) = prefs.edit().putBoolean("traffic_on", v).apply()

    var showChargers: Boolean
        get() = prefs.getBoolean("show_chargers", true)
        set(v) = prefs.edit().putBoolean("show_chargers", v).apply()

    var announceRadars: Boolean
        get() = prefs.getBoolean("announce_radars", true)
        set(v) = prefs.edit().putBoolean("announce_radars", v).apply()

    val hasTomTom: Boolean get() = tomtomKey.isNotBlank()
    val hasLufop: Boolean get() = lufopKey.isNotBlank()
}
