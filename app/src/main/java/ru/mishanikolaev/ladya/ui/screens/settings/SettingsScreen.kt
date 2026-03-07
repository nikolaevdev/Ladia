package ru.mishanikolaev.ladya.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.mishanikolaev.ladya.sound.SoundEffect
import ru.mishanikolaev.ladya.ui.components.AvatarCircle
import ru.mishanikolaev.ladya.ui.models.SettingsAction
import ru.mishanikolaev.ladya.ui.models.SettingsUiState

private val avatarEmojis = listOf("🛶", "😀", "😎", "🤖", "🧭", "🔥", "🐺", "🦊", "🐻", "👤")

@Composable
private fun SoundRow(
    title: String,
    checked: Boolean,
    effect: SoundEffect,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
    onPreviewClick: (SoundEffect) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, modifier = Modifier.weight(1f))
        IconButton(onClick = { onPreviewClick(effect) }, enabled = enabled && checked) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Прослушать")
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onNavigateBack: () -> Unit
) {
    val soundSettings = state.soundSettings
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AvatarCircle(title = state.localDeviceName.ifBlank { "Я" }, avatarEmoji = state.avatarEmoji)
            OutlinedTextField(
                value = state.publicNick,
                onValueChange = { onAction(SettingsAction.PublicNickChanged(it)) },
                label = { Text("Публичный ник") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.localDeviceName,
                onValueChange = { onAction(SettingsAction.LocalDeviceNameChanged(it)) },
                label = { Text("Название устройства") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text("Emoji-аватар")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                avatarEmojis.forEach { emoji ->
                    Surface(onClick = { onAction(SettingsAction.AvatarEmojiChanged(emoji)) }, tonalElevation = if (state.avatarEmoji == emoji) 3.dp else 0.dp) {
                        Text(emoji, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
            Text(if (state.backgroundServiceActive) "Фоновый сервис активен" else "Фоновый сервис не активен")

            Text("Звуковые эффекты", style = MaterialTheme.typography.titleLarge)
            Text("Можно отдельно включать события и сразу их прослушивать.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Включить все звуки", modifier = Modifier.weight(1f))
                Switch(
                    checked = soundSettings.masterEnabled,
                    onCheckedChange = { onAction(SettingsAction.MasterSoundsChanged(it)) }
                )
            }

            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Звонки", style = MaterialTheme.typography.titleMedium)
                    SoundRow("Входящий звонок", soundSettings.incomingCallEnabled, SoundEffect.IncomingCall, soundSettings.masterEnabled, { onAction(SettingsAction.IncomingCallSoundChanged(it)) }, { onAction(SettingsAction.PreviewSoundClicked(it)) })
                    SoundRow("Исходящий звонок", soundSettings.outgoingCallEnabled, SoundEffect.OutgoingCall, soundSettings.masterEnabled, { onAction(SettingsAction.OutgoingCallSoundChanged(it)) }, { onAction(SettingsAction.PreviewSoundClicked(it)) })
                    SoundRow("Соединение установлено", soundSettings.callConnectedEnabled, SoundEffect.CallConnected, soundSettings.masterEnabled, { onAction(SettingsAction.CallConnectedSoundChanged(it)) }, { onAction(SettingsAction.PreviewSoundClicked(it)) })
                    SoundRow("Завершение звонка", soundSettings.callEndedEnabled, SoundEffect.CallEnded, soundSettings.masterEnabled, { onAction(SettingsAction.CallEndedSoundChanged(it)) }, { onAction(SettingsAction.PreviewSoundClicked(it)) })
                }
            }
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Сообщения и файлы", style = MaterialTheme.typography.titleMedium)
                    SoundRow("Входящее сообщение", soundSettings.messageIncomingEnabled, SoundEffect.MessageIncoming, soundSettings.masterEnabled, { onAction(SettingsAction.MessageIncomingSoundChanged(it)) }, { onAction(SettingsAction.PreviewSoundClicked(it)) })
                    SoundRow("Исходящее сообщение", soundSettings.messageOutgoingEnabled, SoundEffect.MessageOutgoing, soundSettings.masterEnabled, { onAction(SettingsAction.MessageOutgoingSoundChanged(it)) }, { onAction(SettingsAction.PreviewSoundClicked(it)) })
                    SoundRow("Ошибка сообщения", soundSettings.messageErrorEnabled, SoundEffect.MessageError, soundSettings.masterEnabled, { onAction(SettingsAction.MessageErrorSoundChanged(it)) }, { onAction(SettingsAction.PreviewSoundClicked(it)) })
                    SoundRow("Файл получен", soundSettings.fileReceivedEnabled, SoundEffect.FileReceived, soundSettings.masterEnabled, { onAction(SettingsAction.FileReceivedSoundChanged(it)) }, { onAction(SettingsAction.PreviewSoundClicked(it)) })
                    SoundRow("Файл отправлен", soundSettings.fileSentEnabled, SoundEffect.FileSent, soundSettings.masterEnabled, { onAction(SettingsAction.FileSentSoundChanged(it)) }, { onAction(SettingsAction.PreviewSoundClicked(it)) })
                }
            }
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Безопасность и система", style = MaterialTheme.typography.titleMedium)
                    SoundRow("Устройство найдено", soundSettings.deviceFoundEnabled, SoundEffect.DeviceFound, soundSettings.masterEnabled, { onAction(SettingsAction.DeviceFoundSoundChanged(it)) }, { onAction(SettingsAction.PreviewSoundClicked(it)) })
                    SoundRow("Устройство подтверждено", soundSettings.deviceVerifiedEnabled, SoundEffect.DeviceVerified, soundSettings.masterEnabled, { onAction(SettingsAction.DeviceVerifiedSoundChanged(it)) }, { onAction(SettingsAction.PreviewSoundClicked(it)) })
                    SoundRow("Предупреждение безопасности", soundSettings.deviceWarningEnabled, SoundEffect.DeviceWarning, soundSettings.masterEnabled, { onAction(SettingsAction.DeviceWarningSoundChanged(it)) }, { onAction(SettingsAction.PreviewSoundClicked(it)) })
                    SoundRow("Мягкое системное уведомление", soundSettings.notificationSoftEnabled, SoundEffect.NotificationSoft, soundSettings.masterEnabled, { onAction(SettingsAction.NotificationSoftSoundChanged(it)) }, { onAction(SettingsAction.PreviewSoundClicked(it)) })
                }
            }
            Button(onClick = { onAction(SettingsAction.SaveClicked) }, modifier = Modifier.fillMaxWidth()) {
                Text("Сохранить")
            }
        }
    }
}
