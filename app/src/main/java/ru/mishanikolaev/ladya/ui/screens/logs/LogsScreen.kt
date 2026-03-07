package ru.mishanikolaev.ladya.ui.screens.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ru.mishanikolaev.ladya.ui.components.EmptyState
import ru.mishanikolaev.ladya.ui.components.LogFilterChips
import ru.mishanikolaev.ladya.ui.components.LogRow
import ru.mishanikolaev.ladya.ui.models.LogType
import ru.mishanikolaev.ladya.ui.models.LogUi
import ru.mishanikolaev.ladya.ui.models.LogsAction
import ru.mishanikolaev.ladya.ui.models.LogsUiState
import ru.mishanikolaev.ladya.ui.theme.LadyaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    state: LogsUiState,
    onAction: (LogsAction) -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Логи") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) {
                        Text("Назад")
                    }
                },
                actions = {
                    TextButton(onClick = { onAction(LogsAction.ClearLogsClicked) }) {
                        Text("Очистить")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LogFilterChips(
                selected = state.selectedFilter,
                onSelected = { onAction(LogsAction.FilterSelected(it)) }
            )

            if (state.visibleLogs.isEmpty()) {
                EmptyState(
                    title = "Логи отсутствуют",
                    subtitle = "Здесь будут события сети, чата и передачи файлов"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(state.visibleLogs, key = { index, item -> "log-${item.id}-$index" }) { _, log ->
                        LogRow(item = log)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LogsScreenPreview() {
    LadyaTheme {
        LogsScreen(
            state = LogsUiState(
                logs = listOf(
                    LogUi("1", "12:00:00", LogType.Network, "Listener started on 0.0.0.0:1903"),
                    LogUi("2", "12:00:05", LogType.Network, "HELLO received"),
                    LogUi("3", "12:00:05", LogType.Network, "HELLO_ACK sent")
                )
            ),
            onAction = {},
            onNavigateBack = {}
        )
    }
}
