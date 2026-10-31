package com.example.drivesafeassistant

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.drivesafeassistant.screens.ui.MainScreen
import com.example.drivesafeassistant.services.NotificationReaderService
import com.example.drivesafeassistant.ui.theme.DriveSafeAssistantTheme
import com.example.drivesafeassistant.utils.Speech

class MainActivity : ComponentActivity() {

    private var isTtsReady by mutableStateOf(false)
    private var isDrivingModeActive by mutableStateOf(false)
    private var hasNotificationAccess by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast("Notification permission is required for background features.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        isDrivingModeActive = Speech.isDriveModeOn(this)
        hasNotificationAccess = isNotificationListenerEnabled()
        Speech.ensure(this) {
            isTtsReady = true
            if (!Speech.isEnglishAvailable) {
                toast("Install an English voice in system Text-to-speech settings.")
            }
        }
        isTtsReady = Speech.isReady
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (isDrivingModeActive) NotificationReaderService.start(this)

        setContent {
            DriveSafeAssistantTheme {
                Scaffold(Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        isTtsReady = isTtsReady,
                        isDrivingModeActive = isDrivingModeActive,
                        hasNotificationAccess = hasNotificationAccess,
                        onTestSpeechClick = {
                            Speech.ensure(this)
                            Speech.speak("Drive Safe Assistant is active and ready.")
                        },
                        onToggleDriveMode = { active ->
                            if (active && !isNotificationListenerEnabled()) {
                                toast("Grant notification access so Drive Mode can read alerts.")
                                openNotificationListenerSettings()
                            } else {
                                isDrivingModeActive = active
                                Speech.setDriveMode(this, active)
                                if (active) NotificationReaderService.start(this) else NotificationReaderService.stop(this)
                                val status = if (active) "Drive mode enabled." else "Drive mode disabled."
                                Speech.ensure(this)
                                Speech.speak(status)
                                Toast.makeText(this, status, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRequestNotificationAccess = { openNotificationListenerSettings() },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasNotificationAccess = isNotificationListenerEnabled()
        isDrivingModeActive = Speech.isDriveModeOn(this)
        isTtsReady = Speech.isReady
    }

    private fun isNotificationListenerEnabled() =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun openNotificationListenerSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(this, NotificationReaderService::class.java).flattenToString()
            )
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        startActivity(intent)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
