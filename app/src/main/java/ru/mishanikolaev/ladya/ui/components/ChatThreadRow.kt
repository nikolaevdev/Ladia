package ru.mishanikolaev.ladya.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.mishanikolaev.ladya.ui.models.ChatThreadUi

@Composable
fun ChatThreadRow(
    thread: ChatThreadUi,
    onOpenClick: (ChatThreadUi) -> Unit,
    onOpenProfileClick: (ChatThreadUi) -> Unit,
    onAddToContactsClick: ((ChatThreadUi) -> Unit)? = null,
    onDeleteForMeClick: ((ChatThreadUi) -> Unit)? = null,
    onDeleteForEveryoneClick: ((ChatThreadUi) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpenClick(thread) },
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarCircle(title = thread.title, isOnline = thread.isOnline, avatarEmoji = thread.avatarEmoji)
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = thread.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = thread.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (thread.isTyping) {
                        StatusChip(text = "печатает…", highlighted = true)
                    } else if (thread.isOnline) {
                        StatusChip(text = "онлайн", highlighted = false)
                    }
                    Text(
                        text = thread.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "⋮",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable { expanded = true }
                )
                if (thread.unreadCount > 0) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Text(
                            text = thread.unreadCount.toString(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("Открыть") }, onClick = { expanded = false; onOpenClick(thread) })
                DropdownMenuItem(text = { Text("Профиль") }, onClick = { expanded = false; onOpenProfileClick(thread) })
                if (onAddToContactsClick != null && !thread.isSavedContact) {
                    DropdownMenuItem(text = { Text("Добавить в контакты") }, onClick = { expanded = false; onAddToContactsClick(thread) })
                }
                if (onDeleteForMeClick != null) {
                    DropdownMenuItem(text = { Text("Удалить у меня") }, onClick = { expanded = false; onDeleteForMeClick(thread) })
                }
                if (onDeleteForEveryoneClick != null) {
                    DropdownMenuItem(text = { Text("Удалить у обоих") }, onClick = { expanded = false; onDeleteForEveryoneClick(thread) })
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, highlighted: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
