package ru.mishanikolaev.ladya.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.mishanikolaev.ladya.ui.models.LogType

@Composable
fun LogFilterChips(
    selected: LogType,
    onSelected: (LogType) -> Unit,
    modifier: Modifier = Modifier
) {
    val items = listOf(
        LogType.All,
        LogType.System,
        LogType.Network,
        LogType.Chat,
        LogType.File,
        LogType.Error
    )

    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items) { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelected(type) },
                label = {
                    Text(
                        text = when (type) {
                            LogType.All -> "Все"
                            LogType.System -> "System"
                            LogType.Network -> "Network"
                            LogType.Chat -> "Chat"
                            LogType.File -> "File"
                            LogType.Error -> "Error"
                        }
                    )
                }
            )
        }
    }
}
