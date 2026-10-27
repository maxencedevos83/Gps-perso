package fr.maxence.gpsperso

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * Fils d'arrière-plan qui ne font jamais planter l'appli : une erreur imprévue dans une tâche
 * est notée (et visible au prochain démarrage) au lieu de fermer l'application.
 */
object Bg {
    private var crashFile: File? = null

    fun init(ctx: Context) {
        if (crashFile != null) return
        crashFile = File(ctx.filesDir, "dernier-plantage.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            record("Plantage (${t.name})", e)
            previous?.uncaughtException(t, e)
        }
    }

    fun pool(name: String, threads: Int = 1): ExecutorService {
        var n = 0
        val factory = ThreadFactory { r ->
            Thread(r, "$name-${n++}").apply {
                isDaemon = true
                setUncaughtExceptionHandler { th, e -> record("Erreur évitée (${th.name})", e) }
            }
        }
        return if (threads == 1) Executors.newSingleThreadExecutor(factory) else Executors.newFixedThreadPool(threads, factory)
    }

    fun record(title: String, e: Throwable) {
        Log.e("GpsPerso", title, e)
        try {
            crashFile?.writeText("$title — ${java.util.Date()}\n${Log.getStackTraceString(e).take(6000)}")
        } catch (x: Exception) { }
    }

    /** Dernière erreur enregistrée (puis oubliée), ou null. */
    fun takeLastCrash(): String? {
        val f = crashFile ?: return null
        if (!f.exists()) return null
        val text = try { f.readText() } catch (e: Exception) { null }
        f.delete()
        return text
    }
}
