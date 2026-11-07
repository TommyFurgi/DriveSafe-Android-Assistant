package com.example.drivesafeassistant.services

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.drivesafeassistant.utils.Speech

/**
 * Only listens for notifications. Speaking/FGS lives in [DriveModeSpeechService]
 * so stopping Drive Mode never tears down the system notification listener binding.
 */
class NotificationReaderService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
        if (Speech.isDriveModeOn(this)) {
            DriveModeSpeechService.start(this)
        }
    }

    override fun onListenerDisconnected() {
        Log.w(TAG, "Notification listener disconnected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, NotificationReaderService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!Speech.isDriveModeOn(this)) return
        if (sbn.packageName == packageName) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val since = Speech.driveModeSince(this)
        if (since > 0L && sbn.postTime < since) {
            Log.d(TAG, "Skip old notification ${sbn.packageName} postTime=${sbn.postTime} since=$since")
            return
        }

        val text = spokenText(sbn) ?: return
        Log.i(TAG, "Read notification: $text")
        DriveModeSpeechService.speak(this, text)
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

    companion object {
        private const val TAG = "NotificationReader"
    }
}
