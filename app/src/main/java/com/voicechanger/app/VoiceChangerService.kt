package com.voicechanger.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class VoiceChangerService : Service() {

    private val binder = LocalBinder()
    val audioProcessor by lazy { AudioProcessor(this) }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceChangerService = this@VoiceChangerService
    }

    companion object {
        const val ACTION_START = "com.voicechanger.app.START"
        const val ACTION_STOP  = "com.voicechanger.app.STOP"
        const val CHANNEL_ID   = "VoiceChangerChannel"
        const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                audioProcessor.start()
            }
            ACTION_STOP -> {
                audioProcessor.stop()
                // stopForeground(STOP_FOREGROUND_REMOVE) requires API 33+
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        audioProcessor.stop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Voice Changer", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // FLAG_IMMUTABLE requires API 23+
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else
            PendingIntent.FLAG_UPDATE_CURRENT

        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, VoiceChangerService::class.java).apply { action = ACTION_STOP },
            pendingFlags
        )
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            pendingFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Changer פועל")
            .setContentText("הקול שלך משתנה בזמן אמת")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "עצור", stopPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
