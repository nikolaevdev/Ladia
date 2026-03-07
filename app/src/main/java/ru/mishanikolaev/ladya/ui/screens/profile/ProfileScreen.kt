package ru.mishanikolaev.ladya.ui.screens.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.mishanikolaev.ladya.ui.components.AvatarCircle
import ru.mishanikolaev.ladya.ui.models.ProfileAction
import ru.mishanikolaev.ladya.ui.models.ProfileUiState
import ru.mishanikolaev.ladya.ui.models.TrustStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    state: ProfileUiState,
    onAction: (ProfileAction) -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Профиль") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AvatarCircle(title = state.title, avatarEmoji = state.avatarEmoji)
                    Text(state.title.ifBlank { "Без имени" }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (state.trustStatus) {
                            TrustStatus.Verified -> "Подтверждённое устройство"
                            TrustStatus.Unverified -> "Устройство не подтверждено"
                            TrustStatus.Suspicious -> "Обнаружено изменение ключа"
                            TrustStatus.Blocked -> "Устройство заблокировано"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            InfoCard(
                title = if (state.isLocalProfile) "Моё устройство" else "Основные данные",
                lines = listOf(
                    "Публичный ник: ${state.publicNick.ifBlank { "не задан" }}",
                    "Peer ID: ${state.peerId.ifBlank { "не задан" }}",
                    "Адрес: ${state.host.ifBlank { "не указан" }}:${state.port}"
                )
            )

            InfoCard(
                title = "Безопасность",
                lines = buildList {
                    add("Отпечаток: ${state.fingerprint ?: "не получен"}")
                    if (!state.keyId.isNullOrBlank()) add("ID ключа: ${state.keyId}")
                    if (!state.shortAuthString.isNullOrBlank()) add("Код сверки: ${state.shortAuthString}")
                    if (!state.publicKeyBase64.isNullOrBlank()) add("Публичный ключ: ${state.publicKeyBase64!!.take(64)}…")
                    if (!state.securityWarning.isNullOrBlank()) add("Предупреждение: ${state.securityWarning}")
                }
            )

            if (!state.isLocalProfile) {
                if (state.canWrite) {
                    Button(onClick = { onAction(ProfileAction.WriteClicked) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Открыть чат")
                    }
                }
                if (state.canAddToContacts) {
                    OutlinedButton(onClick = { onAction(ProfileAction.AddToContactsClicked) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Добавить в контакты")
                    }
                }
                if (state.canVerifyPeer) {
                    Button(onClick = { onAction(ProfileAction.VerifyClicked) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Подтвердить устройство")
                    }
                }
                if (state.canBlockPeer) {
                    OutlinedButton(onClick = { onAction(ProfileAction.BlockClicked) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Заблокировать")
                    }
                }
                if (state.canUnblockPeer) {
                    OutlinedButton(onClick = { onAction(ProfileAction.UnblockClicked) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Снять блокировку")
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, lines: List<String>) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
