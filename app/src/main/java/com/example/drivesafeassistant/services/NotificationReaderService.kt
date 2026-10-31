package com.example.drivesafeassistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.drivesafeassistant.MainActivity
import com.example.drivesafeassistant.R
import com.example.drivesafeassistant.utils.Speech

/**
 * Reads notifications while Drive Mode is on, including when another app is open
 * or the screen is locked. The foreground service keeps TTS allowed to play.
 */
class NotificationReaderService : NotificationListenerService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        Speech.ensure(this)
        Speech.onSpeaking = { speaking ->
            session?.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_STOP)
                    .setState(
                        if (speaking) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                        if (speaking) 1f else 0f
                    )
                    .build()
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Speech.setDriveMode(this, false)
                session?.isActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SPEAK -> {
                showOngoing()
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotEmpty() && Speech.isDriveModeOn(this)) {
                    Speech.ensure(this)
                    Speech.speak(text, queue = true)
                }
            }
            else -> {
                showOngoing()
                Speech.setDriveMode(this, true)
                Speech.ensure(this)
            }
        }
        return START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        if (Speech.isDriveModeOn(this)) start(this)
    }

    override fun onListenerDisconnected() {
        requestRebind(ComponentName(this, NotificationReaderService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !Speech.isDriveModeOn(this) || sbn.packageName == packageName) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val text = spokenText(sbn) ?: return
        Log.d(TAG, "Speaking notification: $text")
        speak(this, text)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Speech.isDriveModeOn(this)) showOngoing()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Speech.onSpeaking = null
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun spokenText(sbn: StatusBarNotification): String? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
            .ifEmpty { extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty() }
        if (title.isEmpty() && body.isEmpty()) return null
        val app = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        return buildString {
            append("Notification from ")
            append(app)
            if (title.isNotEmpty()) append(". ").append(title)
            if (body.isNotEmpty() && body != title) append(". ").append(body)
        }
    }

    private fun showOngoing() {
        if (session == null) {
            session = MediaSession(this, "DriveMode").apply {
                setPlaybackToLocal(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                isActive = true
            }
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NotificationReaderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "NotificationReader"
        private const val CHANNEL_ID = "drive_mode_speech"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TEXT = "extra_text"
        private const val ACTION_STOP = "com.example.drivesafeassistant.action.STOP_DRIVE_MODE"
        private const val ACTION_SPEAK = "com.example.drivesafeassistant.action.SPEAK"

        fun start(context: Context) = launch(context, Intent(context, NotificationReaderService::class.java))

        fun speak(context: Context, text: String) = launch(
            context,
            Intent(context, NotificationReaderService::class.java)
                .setAction(ACTION_SPEAK)
                .putExtra(EXTRA_TEXT, text)
        )

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, NotificationReaderService::class.java).setAction(ACTION_STOP)
                )
            } catch (error: Exception) {
                Log.e(TAG, "Could not stop notification reader", error)
            }
        }

        private fun launch(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: Exception) {
                Log.e(TAG, "Could not start notification reader", error)
            }
        }
    }
}
