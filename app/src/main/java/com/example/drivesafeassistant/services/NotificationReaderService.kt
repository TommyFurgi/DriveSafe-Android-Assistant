package com.example.drivesafeassistant.services

import android.app.Notification
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.drivesafeassistant.utils.DriveModeManager

class NotificationReaderService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
        if (DriveModeManager.isActive(this)) {
            DriveModeSpeechService.start(this)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, NotificationReaderService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!DriveModeManager.isActive(this)) return
        if (sbn.packageName == packageName) return
        if (isGroupSummary(sbn)) return

        val spokenText = buildSpokenText(sbn) ?: return
        Log.d(TAG, "Speaking notification: $spokenText")
        DriveModeSpeechService.speak(this, spokenText)
    }

    private fun isGroupSummary(sbn: StatusBarNotification): Boolean {
        return sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
    }

    private fun buildSpokenText(sbn: StatusBarNotification): String? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()

        val body = when {
            bigText.isNotEmpty() -> bigText
            text.isNotEmpty() -> text
            else -> ""
        }

        if (title.isEmpty() && body.isEmpty()) return null

        val appLabel = resolveAppLabel(sbn.packageName)
        return buildString {
            append("Notification from ")
            append(appLabel)
            if (title.isNotEmpty()) {
                append(". ")
                append(title)
            }
            if (body.isNotEmpty() && body != title) {
                append(". ")
                append(body)
            }
        }
    }

    private fun resolveAppLabel(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    companion object {
        private const val TAG = "NotificationReader"
    }
}
