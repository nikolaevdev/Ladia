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
import ru.mishanikolaev.ladya.ui.models.ContactUi

@Composable
fun ContactCard(
    contact: ContactUi,
    onConnectClick: (ContactUi) -> Unit,
    onOpenProfileClick: (ContactUi) -> Unit,
    onDeleteClick: ((ContactUi) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarCircle(
                title = contact.displayName,
                isOnline = contact.isOnline,
                avatarEmoji = contact.avatarEmoji,
                modifier = Modifier.clickable { onOpenProfileClick(contact) }
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(contact.displayName, style = MaterialTheme.typography.titleSmall)
                val info = buildString {
                    if (contact.publicNick?.isNotBlank() == true) append("@${contact.publicNick}")
                    if (contact.host.isNotBlank()) {
                        if (isNotEmpty()) append(" • ")
                        append("${contact.host}:${contact.port}")
                    }
                    if (contact.peerId.isNotBlank()) {
                        if (isNotEmpty()) append(" • ")
                        append(contact.peerId)
                    }
                }
                if (info.isNotBlank()) {
                    Text(text = info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = when (contact.trustStatus) {
                        ru.mishanikolaev.ladya.ui.models.TrustStatus.Verified -> "Безопасность: подтверждено"
                        ru.mishanikolaev.ladya.ui.models.TrustStatus.Unverified -> "Безопасность: не подтверждено"
                        ru.mishanikolaev.ladya.ui.models.TrustStatus.Suspicious -> "Безопасность: ключ изменился"
                        ru.mishanikolaev.ladya.ui.models.TrustStatus.Blocked -> "Безопасность: заблокировано"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onConnectClick(contact) }, enabled = contact.host.isNotBlank()) {
                        Text(if (contact.isOnline) "Подключиться" else "Подкл. по IP")
                    }
                    OutlinedButton(onClick = { onOpenProfileClick(contact) }) { Text("Профиль") }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Меню контакта")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Открыть профиль") },
                            onClick = {
                                showMenu = false
                                onOpenProfileClick(contact)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Подключиться") },
                            onClick = {
                                showMenu = false
                                onConnectClick(contact)
                            }
                        )
                        if (onDeleteClick != null) {
                            DropdownMenuItem(
                                text = { Text("Удалить") },
                                onClick = {
                                    showMenu = false
                                    onDeleteClick(contact)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
