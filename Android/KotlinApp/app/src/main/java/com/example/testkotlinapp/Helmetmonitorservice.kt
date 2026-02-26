package com.example.testkotlinapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps BLE monitoring alive when the app moves to background.
 *
 * Actions handled:
 *  - ACTION_START             → shows a persistent "Helmet connected" notification
 *  - ACTION_ALERT_DISCONNECT  → replaces it with a loud "Helmet disconnected!" alert
 *  - ACTION_STOP              → removes the notification and stops itself
 */
class HelmetMonitorService : Service() {

    companion object {
        const val ACTION_START             = "com.example.testkotlinapp.ACTION_START"
        const val ACTION_ALERT_DISCONNECT  = "com.example.testkotlinapp.ACTION_ALERT_DISCONNECT"
        const val ACTION_STOP              = "com.example.testkotlinapp.ACTION_STOP"

        private const val CHANNEL_CONNECTED    = "helmet_connected"
        private const val CHANNEL_ALERT        = "helmet_alert"
        private const val NOTIF_ID_FOREGROUND  = 1001
        private const val NOTIF_ID_DISCONNECT  = 1002
    }

    private lateinit var notificationManager: NotificationManager

    // ════════════════════════════════════════════════════
    //  Service lifecycle
    // ════════════════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START            -> startForegroundConnected()
            ACTION_ALERT_DISCONNECT -> fireDisconnectAlert()
            ACTION_STOP             -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                notificationManager.cancel(NOTIF_ID_DISCONNECT)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ════════════════════════════════════════════════════
    //  Notification channels
    // ════════════════════════════════════════════════════

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            // Silent persistent channel (foreground connected status)
            val connectedChannel = NotificationChannel(
                CHANNEL_CONNECTED,
                "Helmet Connected",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while helmet is connected"
                setSound(null, null)
                enableVibration(false)
            }

            // High-importance channel with default alarm sound for disconnect alert
            val alertSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val alertAudioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val alertChannel = NotificationChannel(
                CHANNEL_ALERT,
                "Helmet Alert",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when helmet connection is lost"
                setSound(alertSoundUri, alertAudioAttr)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            notificationManager.createNotificationChannel(connectedChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    // ════════════════════════════════════════════════════
    //  Notifications
    // ════════════════════════════════════════════════════

    /** Persistent silent foreground notification shown while helmet is connected. */
    private fun startForegroundConnected() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_CONNECTED)
            .setSmallIcon(R.drawable.helemt)          // use your helmet icon
            .setContentTitle("Smart Helmet Connected")
            .setContentText("Monitoring helmet sensor…")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIF_ID_FOREGROUND, notification)
    }

    /**
     * Fires a loud, heads-up disconnect alert notification.
     * Works even when the app is fully in the background.
     */
    private fun fireDisconnectAlert() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val alertSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.helemt)
            .setContentTitle("⚠️ Helmet Disconnected!")
            .setContentText("You may have left your helmet behind. Tap to open the app.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Your Smart Helmet has disconnected. Make sure you haven't left it behind!")
            )
            .setSound(alertSoundUri)
            .setVibrate(longArrayOf(0, 500, 200, 500, 200, 500))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        // Update foreground notification text, then also post a separate heads-up
        notificationManager.notify(NOTIF_ID_DISCONNECT, notification)

        // Stop the foreground notification since we're now disconnected
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}