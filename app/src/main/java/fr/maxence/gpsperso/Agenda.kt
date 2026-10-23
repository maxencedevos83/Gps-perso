package fr.maxence.gpsperso

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AgendaEvent(val title: String, val location: String, val begin: Long, val allDay: Boolean) {
    /** « Aujourd'hui 14:30 », « Demain 09:00 », « ven. 25 oct. 18:00 ». */
    fun whenLabel(): String {
        val today = Calendar.getInstance()
        val day = Calendar.getInstance().apply { timeInMillis = begin }
        val dayDiff = (day.get(Calendar.YEAR) - today.get(Calendar.YEAR)) * 366 +
            day.get(Calendar.DAY_OF_YEAR) - today.get(Calendar.DAY_OF_YEAR)
        val dayLabel = when (dayDiff) {
            0 -> "Aujourd'hui"
            1 -> "Demain"
            else -> SimpleDateFormat("EEE d MMM", Locale.FRANCE).format(Date(begin))
        }
        return if (allDay) dayLabel else "$dayLabel ${SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(begin))}"
    }
}

/**
 * Lit l'agenda du téléphone (Google Agenda, Samsung, Outlook... tout ce qui est synchronisé
 * sur Android) et garde les rendez-vous qui ont une adresse.
 */
object Agenda {
    fun upcoming(context: Context, days: Int = 7): List<AgendaEvent> {
        val now = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now - 60 * 60_000L) // inclut le rendez-vous en cours
        ContentUris.appendId(builder, now + days * 24 * 60 * 60_000L)
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
        )
        val events = ArrayList<AgendaEvent>()
        context.contentResolver.query(builder.build(), projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val location = c.getString(1)?.trim().orEmpty()
                if (location.length < 3) continue
                events.add(AgendaEvent(c.getString(0)?.ifBlank { null } ?: "Rendez-vous", location, c.getLong(2), c.getInt(3) == 1))
            }
        }
        return events
    }
}
