package com.example.drivesafeassistant.screens.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen(
    isTtsReady: Boolean,
    isDrivingModeActive: Boolean,
    hasNotificationAccess: Boolean,
    onTestSpeechClick: () -> Unit,
    onToggleDriveMode: (Boolean) -> Unit,
    onRequestNotificationAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isTtsReady) "TTS Engine: Ready" else "TTS Engine: Loading...",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = if (hasNotificationAccess) {
                "Notification Access: Granted"
            } else {
                "Notification Access: Required"
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        if (!hasNotificationAccess) {
            OutlinedButton(
                onClick = onRequestNotificationAccess,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Text(text = "Grant Notification Access")
            }
        }

        Button(
            onClick = onTestSpeechClick,
            enabled = isTtsReady,
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
        ) {
            Text(text = "Test Speech Output")
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            Text(text = "Drive Mode Active", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = isDrivingModeActive,
                onCheckedChange = onToggleDriveMode
            )
        }

        if (isDrivingModeActive) {
            Text(
                text = "Notifications are read aloud in English, even when this screen is closed. Leave the Drive Mode notification visible.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
