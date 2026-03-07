package ru.mishanikolaev.ladya.ui.screens.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.mishanikolaev.ladya.ui.components.DiscoveredPeerCard
import ru.mishanikolaev.ladya.ui.components.EmptyState
import ru.mishanikolaev.ladya.ui.components.PrimaryActionButton
import ru.mishanikolaev.ladya.ui.components.SectionCard
import ru.mishanikolaev.ladya.ui.components.StatusCard
import ru.mishanikolaev.ladya.ui.models.ConnectionAction
import ru.mishanikolaev.ladya.ui.models.ConnectionUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    state: ConnectionUiState,
    onAction: (ConnectionAction) -> Unit,
    onOpenChat: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Подключение") },
                navigationIcon = {
                    TextButton(onClick = { onAction(ConnectionAction.OpenChatsClicked) }) {
                        Text("Чаты")
                    }
                },
                actions = {
                    TextButton(onClick = { onAction(ConnectionAction.OpenSettingsClicked) }) {
                        Text("Настр.")
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
            StatusCard(
                status = state.status,
                isLoading = state.isLoading
            )

            SectionCard(title = "Ваш узел") {
                Text("Название устройства: ${state.localDeviceName}")
                Text("Публичный ник: ${state.publicNick.ifBlank { "не задан" }}")
                Text("Peer ID: ${state.localPeerId}")
                Text("IP: ${state.localIp}")
                Text("Порт: ${state.localPort}")
                Text(
                    text = if (state.backgroundServiceActive) {
                        "Фоновый режим активен"
                    } else {
                        "Фоновый режим не активен"
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(title = "Mesh / relay") {
                Text("Известных маршрутов: ${state.relayKnownRoutes}")
                Text("Проверенные маршруты: ${state.relayTrustedRoutes}")
                Text("Неподтверждённые маршруты: ${state.relayUntrustedRoutes}")
                Text("Заблокированные узлы: ${state.relayBlockedRoutes}")
                Text("Reroute-срабатывания: ${state.relayReroutedPackets}")
                Text("Восстановленные маршруты: ${state.relayRecoveredRoutes}")
                Text("В очереди relay: ${state.relayPendingPackets}")
                Text("Переслано пакетов: ${state.relayForwardedPackets}")
                Text("Group отправлено: ${state.groupPacketsSent}")
                Text("Group получено: ${state.groupPacketsReceived}")
                Text("Group сброшено: ${state.groupPacketsDropped}")
                Text("Сброшено пакетов: ${state.relayDroppedPackets}")
                state.relaySecurityNote?.let {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = it,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                if (state.relayTopology.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.relayTopology.take(6).forEach { route ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = buildString {
                                            append(route.title)
                                            if (route.isPreferred) append(" • приоритет")
                                        },
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text("Host: ${route.host}")
                                    Text(
                                        if (route.viaTitle.isNullOrBlank()) "Маршрут: прямой"
                                        else "Маршрут: через ${route.viaTitle} • hop ${route.hops}"
                                    )
                                    Text("Доверие: ${route.trustStatus}")
                                    route.securityNote?.let { note ->
                                        Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Text("Маршруты пока не сформированы", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionCard(title = "Доступные устройства") {
                if (state.discoveredPeers.isEmpty()) {
                    EmptyState(
                        title = "Устройства пока не найдены",
                        subtitle = "Проверьте, что второе устройство в той же Wi‑Fi сети и приложение на нём запущено"
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.discoveredPeers.forEach { peer ->
                            DiscoveredPeerCard(
                                peer = peer,
                                onConnectClick = { onAction(ConnectionAction.ConnectToPeerClicked(it)) },
                                onOpenProfileClick = { onAction(ConnectionAction.ConnectToPeerClicked(it)) }
                            )
                        }
                    }
                }
            }

            SectionCard(title = "Ручное подключение") {
                OutlinedTextField(
                    value = state.remoteIpInput,
                    onValueChange = { onAction(ConnectionAction.RemoteIpChanged(it)) },
                    label = { Text("IP-адрес устройства") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.remotePortInput,
                    onValueChange = { onAction(ConnectionAction.RemotePortChanged(it)) },
                    label = { Text("Порт") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            state.errorMessage?.let {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = it,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            PrimaryActionButton(
                text = "Подключиться",
                onClick = { onAction(ConnectionAction.ConnectClicked) },
                enabled = !state.isLoading
            )

            OutlinedButton(
                onClick = onOpenChat,
                enabled = state.canOpenChat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Открыть чат")
            }
        }
    }
}
