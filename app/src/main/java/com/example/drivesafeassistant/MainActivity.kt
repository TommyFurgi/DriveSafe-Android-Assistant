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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.drivesafeassistant.screens.ui.MainScreen
import com.example.drivesafeassistant.services.DriveModeSpeechService
import com.example.drivesafeassistant.services.NotificationReaderService
import com.example.drivesafeassistant.ui.theme.DriveSafeAssistantTheme
import com.example.drivesafeassistant.utils.DriveModeManager
import com.example.drivesafeassistant.utils.SpeechManager

class MainActivity : ComponentActivity() {

    private var isTtsReady by mutableStateOf(false)
    private var isDrivingModeActive by mutableStateOf(false)
    private var hasNotificationAccess by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission is required for background features.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        isDrivingModeActive = DriveModeManager.isActive(this)
        hasNotificationAccess = isNotificationListenerEnabled()

        SpeechManager.ensureInitialized(this) {
            isTtsReady = true
            if (!SpeechManager.isEnglishAvailable) {
                Toast.makeText(
                    this,
                    "Install an English voice in system Text-to-speech settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        isTtsReady = SpeechManager.isReady

        checkNotificationPermission()

        // If Drive Mode was left on, ensure the foreground speech service is running
        if (isDrivingModeActive) {
            DriveModeSpeechService.start(this)
        }

        setContent {
            DriveSafeAssistantTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        isTtsReady = isTtsReady,
                        isDrivingModeActive = isDrivingModeActive,
                        hasNotificationAccess = hasNotificationAccess,
                        onTestSpeechClick = {
                            SpeechManager.ensureInitialized(this)
                            SpeechManager.speak("Drive Safe Assistant is active and ready.")
                        },
                        onToggleDriveMode = { active ->
                            if (active && !isNotificationListenerEnabled()) {
                                Toast.makeText(
                                    this,
                                    "Grant notification access so Drive Mode can read alerts.",
                                    Toast.LENGTH_LONG
                                ).show()
                                openNotificationListenerSettings()
                            } else {
                                isDrivingModeActive = active
                                DriveModeManager.setActive(this, active)
                                if (active) {
                                    DriveModeSpeechService.start(this)
                                } else {
                                    DriveModeSpeechService.stop(this)
                                }
                                val statusText = if (active) "Drive mode enabled." else "Drive mode disabled."
                                SpeechManager.ensureInitialized(this)
                                SpeechManager.speak(statusText)
                                Toast.makeText(this, statusText, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRequestNotificationAccess = {
                            openNotificationListenerSettings()
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hasNotificationAccess = isNotificationListenerEnabled()
        isDrivingModeActive = DriveModeManager.isActive(this)
        isTtsReady = SpeechManager.isReady
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledPackages.contains(packageName)
    }

    private fun openNotificationListenerSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this@MainActivity, NotificationReaderService::class.java).flattenToString()
                )
            }
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        startActivity(intent)
    }
}
