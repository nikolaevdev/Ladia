package ru.mishanikolaev.ladya.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.mishanikolaev.ladya.ui.models.IncomingFileOfferUi

@Composable
fun IncomingFileOfferCard(
    offer: IncomingFileOfferUi,
    onAcceptClick: () -> Unit,
    onDeclineClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Входящий файл",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "${offer.fileName} • ${offer.fileSizeLabel}",
                style = MaterialTheme.typography.bodyMedium
            )
            offer.fromPeerLabel?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = "От: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Выберите, куда сохранить файл, перед началом загрузки",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAcceptClick) {
                    Text("Сохранить как")
                }
                OutlinedButton(onClick = onDeclineClick) {
                    Text("Отклонить")
                }
            }
        }
    }
}
