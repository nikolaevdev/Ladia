package ru.mishanikolaev.ladya.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.mishanikolaev.ladya.ui.models.FileTransferStatus
import ru.mishanikolaev.ladya.ui.models.FileTransferUi

@Composable
fun FileTransferPanel(
    state: FileTransferUi,
    onPickFilesClick: () -> Unit,
    onSendFilesClick: () -> Unit,
    onHideClick: () -> Unit,
    onClearClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Вложения", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onClearClick != null && state.selectedFiles.isNotEmpty() && state.status !in listOf(
                            FileTransferStatus.Sending,
                            FileTransferStatus.Receiving,
                            FileTransferStatus.WaitingForReceiver
                        )
                    ) {
                        TextButton(onClick = onClearClick) { Text("Очистить") }
                    }
                    TextButton(onClick = onHideClick) { Text("Скрыть") }
                }
            }

            if (state.selectedFiles.isEmpty()) {
                Text(
                    text = "Выберите один или несколько файлов",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.selectedFiles.take(3).forEach { file ->
                    Text("• ${file.fileName} • ${file.fileSizeLabel}", style = MaterialTheme.typography.bodyMedium)
                    file.compressedLabel?.let {
                        Text("  $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (state.selectedFiles.size > 3) {
                    Text(
                        text = "+ ещё ${state.selectedFiles.size - 3}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val statusText = when (state.status) {
                FileTransferStatus.Idle -> "Готово к выбору"
                FileTransferStatus.ReadyToSend -> "Можно отправлять"
                FileTransferStatus.WaitingForReceiver -> "Ожидаем подтверждение получателя"
                FileTransferStatus.Sending -> "Файл отправляется"
                FileTransferStatus.Receiving -> "Файл загружается"
                FileTransferStatus.Sent -> "Передача завершена"
                FileTransferStatus.Received -> "Файл сохранён"
                FileTransferStatus.Error -> "Ошибка передачи"
            }
            Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            state.detailText?.takeIf { it.isNotBlank() }?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (state.totalBytes > 0L) {
                val processed = formatTransferSize(state.bytesProcessed)
                val total = formatTransferSize(state.totalBytes)
                Text(
                    text = "$processed из $total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.remainingLabel?.takeIf { it.isNotBlank() }?.let {
                Text(text = "Осталось: $it", style = MaterialTheme.typography.bodySmall)
            }

            if (state.totalFiles > 0) {
                Text(
                    text = "Файлы: ${state.completedFiles} / ${state.totalFiles}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.status == FileTransferStatus.Sending || state.status == FileTransferStatus.Receiving || state.progress > 0f) {
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            state.errorMessage?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPickFilesClick) { Text("📎 Выбрать") }
                Button(
                    onClick = onSendFilesClick,
                    enabled = state.selectedFiles.isNotEmpty() && state.status !in listOf(
                        FileTransferStatus.Sending,
                        FileTransferStatus.Receiving,
                        FileTransferStatus.WaitingForReceiver
                    )
                ) {
                    Text(if (state.selectedFiles.size > 1) "Отправить ${state.selectedFiles.size}" else "Отправить")
                }
            }
        }
    }
}

private fun formatTransferSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val kb = 1024.0
    val mb = kb * 1024
    return when {
        bytes >= mb -> String.format(java.util.Locale.US, "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(java.util.Locale.US, "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}
