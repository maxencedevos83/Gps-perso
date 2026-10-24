package fr.maxence.gpsperso

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Service de premier plan pendant le guidage : Android le laisse tourner écran verrouillé
 * ou appli en arrière-plan (téléphone en poche, Android Auto). La notification montre la prochaine manœuvre.
 */
class NavService : Service(), Nav.Listener {

    companion object {
        private const val CHANNEL = "guidage"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "fr.maxence.gpsperso.STOP"

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, NavService::class.java))
            } catch (e: Exception) {
                // Démarrage refusé (appli en arrière-plan) : le guidage continue tant que l'écran reste allumé.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavService::class.java))
        }
    }

    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Guidage", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Nav.stopNavigation()
            return START_NOT_STICKY
        }
        val n = build("Guidage en cours", Nav.destination?.name ?: "")
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        Nav.addListener(this)
        Nav.lastProgress?.let { onProgress(it) }
        return START_NOT_STICKY
    }

    override fun onProgress(p: Nav.Progress) {
        val man = p.maneuver ?: return
        val title = "${man.arrow}  ${Geo.formatDistance(p.maneuverDistance)} · ${man.instruction}"
        val text = "${Geo.formatDistance(p.remaining)} restants · ${Geo.formatDuration(p.remainingTime)}"
        if (title == lastText) return
        lastText = title
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(title, text))
    }

    private fun build(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Nav.launchIntent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, NavService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Arrêter", stop).build())
            .build()
    }

    override fun onDestroy() {
        Nav.removeListener(this)
        super.onDestroy()
    }
}
