package com.budu.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.budu.app.data.Settings

@Composable
fun ParentScreen(
    settings: Settings,
    usedMinutesToday: Long,
    onSave: (Settings) -> Unit,
    onResetToday: () -> Unit,
    onExit: () -> Unit,
) {
    var childName by remember(settings) { mutableStateOf(settings.childName) }
    var language by remember(settings) { mutableStateOf(settings.languageTag) }
    var limit by remember(settings) { mutableStateOf(settings.dailyLimitMinutes.toString()) }
    var images by remember(settings) { mutableStateOf(settings.imagesEnabled) }
    var naturalVoice by remember(settings) { mutableStateOf(settings.naturalVoice) }
    var serverUrl by remember(settings) { mutableStateOf(settings.serverUrl) }
    var token by remember(settings) { mutableStateOf(settings.appToken) }
    var newPin by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Parent settings", style = MaterialTheme.typography.headlineMedium)
            Text("Played today: $usedMinutesToday min", style = MaterialTheme.typography.bodyLarge)


            OutlinedTextField(
                value = childName, onValueChange = { childName = it.take(40) },
                label = { Text("Child's first name (optional)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = language, onValueChange = { language = it.trim() },
                label = { Text("Language (en-US, et-EE, ru-RU…)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = limit, onValueChange = { limit = it.filter(Char::isDigit).take(3) },
                label = { Text("Daily play limit in minutes (0 = no limit)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Allow Budu to draw pictures", Modifier.weight(1f))
                Switch(checked = images, onCheckedChange = { images = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Natural Google voice (off = tablet voice)", Modifier.weight(1f))
                Switch(checked = naturalVoice, onCheckedChange = { naturalVoice = it })
            }
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it.trim() },
                label = { Text("Server URL") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token, onValueChange = { token = it.trim() },
                label = { Text("App token (same as APP_TOKEN on server)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = newPin, onValueChange = { newPin = it.filter(Char::isDigit).take(8) },
                label = { Text("New PIN (leave empty to keep current)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onSave(
                        settings.copy(
                            childName = childName.trim(),
                            languageTag = language.ifBlank { "en-US" },
                            dailyLimitMinutes = limit.toIntOrNull() ?: settings.dailyLimitMinutes,
                            imagesEnabled = images,
                            naturalVoice = naturalVoice,
                            serverUrl = serverUrl.ifBlank { settings.serverUrl },
                            appToken = token,
                            pin = if (newPin.length >= 4) newPin else settings.pin,
                        ),
                    )
                    newPin = ""
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saved) "Saved ✓" else "Save") }

            OutlinedButton(onClick = onResetToday, modifier = Modifier.fillMaxWidth()) {
                Text("Reset today's play time")
            }
            OutlinedButton(onClick = onExit, modifier = Modifier.fillMaxWidth()) {
                Text("Back to Budu")
            }

            Text(
                "Conversations are not saved on this device. They're only kept in memory while the app is open, " +
                    "and sent to your Budu server to get replies. New PIN must be at least 4 digits.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

