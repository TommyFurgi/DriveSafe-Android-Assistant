package com.example.drivesafeassistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.drivesafeassistant.MainActivity
import com.example.drivesafeassistant.R
import com.example.drivesafeassistant.utils.DriveModeManager
import com.example.drivesafeassistant.utils.SpeechManager

/**
 * Foreground service that keeps TTS alive while Drive Mode is on.
 * Speech uses media playback so Android allows it after the app screen is closed.
 */
class DriveModeSpeechService : Service() {

    private var mediaSession: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ensureMediaSession()
        SpeechManager.ensureInitialized(this)
        SpeechManager.setSpeakingListener { speaking ->
            updatePlaybackState(speaking)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopDriveMode()
                return START_NOT_STICKY
            }
            ACTION_SPEAK -> {
                startAsForeground()
                SpeechManager.ensureInitialized(this)
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                if (text.isNotEmpty() && DriveModeManager.isActive(this)) {
                    SpeechManager.speak(text, queue = true)
                }
            }
            else -> {
                startAsForeground()
                DriveModeManager.setActive(this, true)
                SpeechManager.ensureInitialized(this)
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (DriveModeManager.isActive(this)) {
            startAsForeground()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        SpeechManager.setSpeakingListener(null)
        mediaSession?.run {
            isActive = false
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildOngoingNotification()
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

    private fun stopDriveMode() {
        DriveModeManager.setActive(this, false)
        updatePlaybackState(playing = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureMediaSession() {
        if (mediaSession != null) return
        mediaSession = MediaSession(this, "DriveModeSpeech").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onStop() {
                    stopDriveMode()
                }
            })
            setPlaybackToLocal(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, getString(R.string.drive_mode_notification_title))
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, getString(R.string.app_name))
                    .build()
            )
            isActive = true
        }
        updatePlaybackState(playing = false)
    }

    private fun updatePlaybackState(playing: Boolean) {
        val state = if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_STOP)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) 1f else 0f)
                .build()
        )
    }

    private fun buildOngoingNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
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
            .addAction(0, getString(R.string.drive_mode_stop), stopIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.drive_mode_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.drive_mode_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "DriveModeSpeech"
        private const val CHANNEL_ID = "drive_mode_speech"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_TEXT = "extra_text"

        const val ACTION_STOP = "com.example.drivesafeassistant.action.STOP_DRIVE_MODE"
        const val ACTION_SPEAK = "com.example.drivesafeassistant.action.SPEAK"

        fun start(context: Context) {
            val intent = Intent(context, DriveModeSpeechService::class.java)
            Log.d(TAG, "Starting DriveModeSpeechService")
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: Exception) {
                Log.e(TAG, "Could not start DriveModeSpeechService", error)
            }
        }

        fun speak(context: Context, text: String) {
            if (text.isBlank()) return
            val intent = Intent(context, DriveModeSpeechService::class.java).apply {
                action = ACTION_SPEAK
                putExtra(EXTRA_TEXT, text)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (error: Exception) {
                Log.e(TAG, "Could not speak in background, falling back", error)
                SpeechManager.ensureInitialized(context)
                SpeechManager.speak(text, queue = true)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DriveModeSpeechService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(intent)
            } catch (error: Exception) {
                Log.e(TAG, "Could not stop DriveModeSpeechService", error)
            }
        }
    }
}
