package com.example.drivesafeassistant.services

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.example.drivesafeassistant.utils.DriveSafeLog
import com.example.drivesafeassistant.utils.Speech

class NotificationReaderService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        DriveSafeLog.call("NotificationReader.onListenerConnected", "driveMode=${Speech.isDriveModeOn(this)}")
        if (Speech.isDriveModeOn(this)) DriveModeSpeechService.start(this)
    }

    override fun onListenerDisconnected() {
        isConnected = false
        DriveSafeLog.call("NotificationReader.onListenerDisconnected")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, NotificationReaderService::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        DriveSafeLog.call(
            "NotificationReader.onNotificationPosted",
            "pkg=${sbn.packageName} id=${sbn.id}"
        )

        if (!Speech.isDriveModeOn(this)) return
        if (sbn.packageName == packageName) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val since = Speech.driveModeSince(this)
        if (since > 0L && sbn.postTime < since) return

        val text = spokenText(sbn) ?: return
        DriveSafeLog.call("NotificationReader.forward", text)
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
        private const val REBIND_RETRY_MS = 1500L

        @Volatile
        var isConnected: Boolean = false
            private set

        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /**
         * Access can stay ON in Settings while the system binding is dead
         * (common after the app sits unused for hours). requestRebind first;
         * if that fails, soft-toggle the component to force a new bind.
         */
        fun ensureBound(context: Context) {
            val app = context.applicationContext
            val component = ComponentName(app, NotificationReaderService::class.java)
            val access = hasAccess(app)
            DriveSafeLog.call("NotificationReader.ensureBound", "access=$access connected=$isConnected")

            if (!access || isConnected) return

            DriveSafeLog.call("NotificationReader.requestRebind")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestRebind(component)
            }

            Handler(Looper.getMainLooper()).postDelayed({
                if (isConnected) {
                    DriveSafeLog.call("NotificationReader.ensureBound", "rebind succeeded")
                    return@postDelayed
                }
                DriveSafeLog.call("NotificationReader.softToggleComponent", "rebind failed, forcing reset")
                softToggleComponent(app, component)
            }, REBIND_RETRY_MS)
        }

        private fun softToggleComponent(context: Context, component: ComponentName) {
            try {
                val pm = context.packageManager
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    requestRebind(component)
                }
                DriveSafeLog.call("NotificationReader.softToggleComponent", "done")
            } catch (error: Exception) {
                DriveSafeLog.call("NotificationReader.softToggleComponent", "failed: ${error.message}")
            }
        }
    }
}
