package ru.mishanikolaev.ladya.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.mishanikolaev.ladya.ui.models.ConnectionStatus

@Composable
fun StatusCard(
    status: ConnectionStatus,
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Статус подключения",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = when (status) {
                    ConnectionStatus.Idle -> "Ожидание"
                    ConnectionStatus.Listening -> "Ожидание входящего подключения"
                    ConnectionStatus.Connecting -> "Подключение к удалённому узлу"
                    ConnectionStatus.Connected -> "Подключено"
                    ConnectionStatus.Error -> "Ошибка подключения"
                },
                style = MaterialTheme.typography.bodyLarge
            )
            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
