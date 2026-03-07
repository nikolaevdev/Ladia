package ru.mishanikolaev.ladya.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.mishanikolaev.ladya.ui.models.DiscoveredPeerUi

@Composable
fun DiscoveredPeerCard(
    peer: DiscoveredPeerUi,
    onConnectClick: (DiscoveredPeerUi) -> Unit,
    onOpenProfileClick: (DiscoveredPeerUi) -> Unit,
    onSaveContactClick: ((DiscoveredPeerUi) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarCircle(
                title = peer.title,
                isOnline = true,
                avatarEmoji = peer.avatarEmoji,
                modifier = Modifier.clickable { onOpenProfileClick(peer) }
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = peer.title, style = MaterialTheme.typography.titleSmall)
                Text(text = "${peer.host}:${peer.port}", style = MaterialTheme.typography.bodyMedium)
                if (peer.subtitle.isNotBlank()) {
                    Text(text = peer.subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = when (peer.trustStatus) {
                        ru.mishanikolaev.ladya.ui.models.TrustStatus.Verified -> "Безопасность: подтверждено"
                        ru.mishanikolaev.ladya.ui.models.TrustStatus.Unverified -> "Безопасность: не подтверждено"
                        ru.mishanikolaev.ladya.ui.models.TrustStatus.Suspicious -> "Безопасность: ключ изменился"
                        ru.mishanikolaev.ladya.ui.models.TrustStatus.Blocked -> "Безопасность: заблокировано"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onConnectClick(peer) }) { Text("Подключиться") }
                    OutlinedButton(onClick = { onOpenProfileClick(peer) }) { Text("Профиль") }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Меню устройства")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Открыть профиль") },
                            onClick = {
                                showMenu = false
                                onOpenProfileClick(peer)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Подключиться") },
                            onClick = {
                                showMenu = false
                                onConnectClick(peer)
                            }
                        )
                        if (onSaveContactClick != null) {
                            DropdownMenuItem(
                                text = { Text(if (peer.isSavedContact) "Изменить контакт" else "Сохранить контакт") },
                                onClick = {
                                    showMenu = false
                                    onSaveContactClick(peer)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
