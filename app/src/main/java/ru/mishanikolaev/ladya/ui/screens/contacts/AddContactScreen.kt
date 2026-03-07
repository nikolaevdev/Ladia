package ru.mishanikolaev.ladya.ui.screens.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.mishanikolaev.ladya.ui.models.AddContactAction
import ru.mishanikolaev.ladya.ui.models.AddContactUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactScreen(
    state: AddContactUiState,
    onAction: (AddContactAction) -> Unit,
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Добавить контакт") },
                navigationIcon = {
                    TextButton(onClick = onNavigateBack) { Text("Назад") }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.displayName,
                onValueChange = { onAction(AddContactAction.DisplayNameChanged(it)) },
                label = { Text("Имя контакта") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.peerId,
                onValueChange = { onAction(AddContactAction.PeerIdChanged(it)) },
                label = { Text("Peer ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.host,
                onValueChange = { onAction(AddContactAction.HostChanged(it)) },
                label = { Text("IP-адрес") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = state.port,
                onValueChange = { onAction(AddContactAction.PortChanged(it)) },
                label = { Text("Порт") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )

            state.errorMessage?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Button(
                onClick = { onAction(AddContactAction.SaveClicked) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Сохранить контакт")
            }
        }
    }
}
