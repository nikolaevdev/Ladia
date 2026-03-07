package ru.mishanikolaev.ladya.ui.components

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.mishanikolaev.ladya.ui.models.MessageDeliveryStatus
import ru.mishanikolaev.ladya.ui.models.MessageType
import ru.mishanikolaev.ladya.ui.models.MessageUi

@Composable
fun MessageBubble(
    message: MessageUi,
    modifier: Modifier = Modifier,
    onEditClick: ((MessageUi) -> Unit)? = null,
    onDeleteForMeClick: ((MessageUi) -> Unit)? = null,
    onDeleteForEveryoneClick: ((MessageUi) -> Unit)? = null,
    onSaveAttachmentClick: ((MessageUi) -> Unit)? = null
) {
    val isOutgoing = message.type == MessageType.Outgoing
    var expanded by remember { mutableStateOf(false) }
    var showImageDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var player by remember(message.id) { mutableStateOf<MediaPlayer?>(null) }
    var isPlayingAudio by remember(message.id) { mutableStateOf(false) }
    var audioDurationMs by remember(message.id) { mutableIntStateOf(0) }
    var audioProgressMs by remember(message.id) { mutableIntStateOf(0) }

    if (showImageDialog && !message.attachmentUri.isNullOrBlank()) {
        ZoomableImageDialog(
            imageUri = message.attachmentUri,
            caption = message.attachmentName ?: message.text,
            onDismiss = { showImageDialog = false }
        )
    }

    androidx.compose.runtime.LaunchedEffect(isPlayingAudio, player) {
        while (isPlayingAudio) {
            val current = player?.currentPosition ?: 0
            audioProgressMs = current
            delay(250)
        }
    }

    androidx.compose.runtime.DisposableEffect(message.id) {
        onDispose {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 22.dp,
                topEnd = 22.dp,
                bottomStart = if (isOutgoing) 22.dp else 8.dp,
                bottomEnd = if (isOutgoing) 8.dp else 22.dp
            ),
            tonalElevation = 1.dp,
            color = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (isOutgoing || onDeleteForMeClick != null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            text = "⋮",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.74f)
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { expanded = true }
                                .padding(bottom = 2.dp)
                        )
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            if (isOutgoing && !message.isDeleted && onEditClick != null && !message.isImageAttachment && !message.isVoiceNote && message.attachmentUri == null) {
                                DropdownMenuItem(
                                    text = { Text("Редактировать") },
                                    onClick = {
                                        expanded = false
                                        onEditClick(message)
                                    }
                                )
                            }
                            if (!message.attachmentUri.isNullOrBlank() && onSaveAttachmentClick != null) {
                                DropdownMenuItem(
                                    text = { Text("Сохранить копию") },
                                    onClick = {
                                        expanded = false
                                        onSaveAttachmentClick(message)
                                    }
                                )
                            }
                            if (onDeleteForMeClick != null) {
                                DropdownMenuItem(
                                    text = { Text("Удалить у меня") },
                                    onClick = {
                                        expanded = false
                                        onDeleteForMeClick(message)
                                    }
                                )
                            }
                            if (isOutgoing && onDeleteForEveryoneClick != null) {
                                DropdownMenuItem(
                                    text = { Text("Удалить у всех") },
                                    onClick = {
                                        expanded = false
                                        onDeleteForEveryoneClick(message)
                                    }
                                )
                            }
                        }
                    }
                }
                message.senderLabel?.takeIf { it.isNotBlank() && !isOutgoing }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Start
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (!message.isDeleted && message.isImageAttachment && !message.attachmentUri.isNullOrBlank()) {
                    UriImage(
                        uriString = message.attachmentUri,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.15f)
                            .clickable { showImageDialog = true },
                        contentDescription = message.attachmentName ?: "Фото",
                        fallbackText = "Фото недоступно"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (!message.isDeleted && message.isVoiceNote) {
                    val audioUri = message.attachmentUri
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                IconButton(onClick = {
                                    if (audioUri.isNullOrBlank()) return@IconButton
                                    if (isPlayingAudio) {
                                        runCatching { player?.pause() }
                                        isPlayingAudio = false
                                    } else {
                                        val existing = player
                                        if (existing != null) {
                                            runCatching { existing.start() }
                                            isPlayingAudio = true
                                        } else {
                                            runCatching {
                                                MediaPlayer().apply {
                                                    setDataSource(context, android.net.Uri.parse(audioUri))
                                                    setOnPreparedListener { mp ->
                                                        audioDurationMs = mp.duration.coerceAtLeast(0)
                                                        mp.start()
                                                        isPlayingAudio = true
                                                    }
                                                    setOnCompletionListener { mp ->
                                                        isPlayingAudio = false
                                                        audioProgressMs = 0
                                                        runCatching { mp.seekTo(0) }
                                                    }
                                                    prepareAsync()
                                                }
                                            }.onSuccess { created ->
                                                player = created
                                            }
                                        }
                                    }
                                }) {
                                    Icon(
                                        imageVector = if (isPlayingAudio) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlayingAudio) "Пауза" else "Прослушать",
                                        tint = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.GraphicEq,
                                            contentDescription = null,
                                            tint = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = message.text.ifBlank { "Голосовое сообщение" },
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    val totalLabel = message.voiceNoteDurationLabel ?: formatPlaybackMillis(audioDurationMs)
                                    Text(
                                        text = if (audioProgressMs > 0) "${formatPlaybackMillis(audioProgressMs)} / $totalLabel" else totalLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.74f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = if (audioDurationMs > 0) (audioProgressMs.toFloat() / audioDurationMs.toFloat()).coerceIn(0f, 1f) else 0f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    Text(
                        text = when {
                            message.isDeleted -> "Сообщение удалено"
                            message.isImageAttachment -> message.attachmentName ?: message.text
                            else -> message.text
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = message.timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.74f)
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (message.isEdited && !message.isDeleted) {
                        Text(
                            text = "изменено",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.74f)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    message.routeLabel?.takeIf { it.isNotBlank() && !message.isDeleted }?.let { route ->
                        Text(
                            text = route,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.74f)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    message.deliveryStatus?.takeIf { !message.isDeleted }?.let { status ->
                        Text(
                            text = when (status) {
                                MessageDeliveryStatus.Sending -> "Отправка"
                                MessageDeliveryStatus.Delivered -> "Доставлено"
                                MessageDeliveryStatus.Failed -> "Ошибка"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isOutgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.74f)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}


private fun formatPlaybackMillis(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return "%02d:%02d".format(mins, secs)
}
