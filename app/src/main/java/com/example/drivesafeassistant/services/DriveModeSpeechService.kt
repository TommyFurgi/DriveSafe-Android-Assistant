package com.example.drivesafeassistant.services

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.drivesafeassistant.MainActivity
import com.example.drivesafeassistant.R
import com.example.drivesafeassistant.utils.Speech

/**
 * Keeps a media-playback foreground service alive while Drive Mode is on,
 * so TTS can speak after the app UI is closed or the screen is locked.
 */
class DriveModeSpeechService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        Speech.ensure(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Speech.setDriveMode(this, false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SPEAK -> {
                promoteToForeground()
                Speech.ensure(this)
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotEmpty() && Speech.isDriveModeOn(this)) {
                    Speech.speak(text, queue = true)
                }
            }
            else -> {
                promoteToForeground()
                if (!Speech.isDriveModeOn(this)) Speech.setDriveMode(this, true)
                Speech.ensure(this)
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Speech.isDriveModeOn(this)) promoteToForeground()
        super.onTaskRemoved(rootIntent)
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, DriveModeSpeechService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.drive_mode_notification_title))
            .setContentText(getString(R.string.drive_mode_notification_text))
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, getString(R.string.drive_mode_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.drive_mode_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.drive_mode_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "DriveModeSpeech"
        private const val CHANNEL_ID = "drive_mode_speech"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TEXT = "extra_text"
        const val ACTION_STOP = "com.example.drivesafeassistant.action.STOP_DRIVE_MODE"
        const val ACTION_SPEAK = "com.example.drivesafeassistant.action.SPEAK"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DriveModeSpeechService::class.java)
                )
            } catch (error: Exception) {
                Log.e(TAG, "Could not start DriveModeSpeechService", error)
            }
        }

        fun speak(context: Context, text: String) {
            if (text.isBlank()) return
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DriveModeSpeechService::class.java)
                        .setAction(ACTION_SPEAK)
                        .putExtra(EXTRA_TEXT, text)
                )
            } catch (error: Exception) {
                Log.e(TAG, "Could not speak via DriveModeSpeechService", error)
                Speech.ensure(context)
                Speech.speak(text, queue = true)
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, DriveModeSpeechService::class.java).setAction(ACTION_STOP)
                )
            } catch (error: Exception) {
                Log.e(TAG, "Could not stop DriveModeSpeechService", error)
            }
        }
    }
}
