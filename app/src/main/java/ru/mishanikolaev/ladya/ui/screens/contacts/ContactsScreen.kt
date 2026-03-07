package ru.mishanikolaev.ladya.ui.screens.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.mishanikolaev.ladya.ui.components.AvatarCircle
import ru.mishanikolaev.ladya.ui.components.ChatThreadRow
import ru.mishanikolaev.ladya.ui.components.ContactCard
import ru.mishanikolaev.ladya.ui.components.DiscoveredPeerCard
import ru.mishanikolaev.ladya.ui.components.EmptyState
import ru.mishanikolaev.ladya.ui.models.ContactsAction
import ru.mishanikolaev.ladya.ui.models.ContactsUiState

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class
)
@Composable
fun ContactsScreen(
    state: ContactsUiState,
    onAction: (ContactsAction) -> Unit,
    onNavigateBack: (() -> Unit)?
) {
    var searchQuery by remember { mutableStateOf("") }
    var showMore by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Чаты", "Контакты", "Устройства рядом")

    val normalizedQuery = searchQuery.trim().lowercase()
    val threads = if (normalizedQuery.isBlank()) state.threads else state.threads.filter {
        it.title.lowercase().contains(normalizedQuery) || it.preview.lowercase().contains(normalizedQuery)
    }
    val contacts = if (normalizedQuery.isBlank()) state.contacts else state.contacts.filter {
        it.displayName.lowercase().contains(normalizedQuery) ||
            it.peerId.lowercase().contains(normalizedQuery) ||
            it.host.lowercase().contains(normalizedQuery)
    }
    val peers = if (normalizedQuery.isBlank()) state.discoveredPeers else state.discoveredPeers.filter {
        it.title.lowercase().contains(normalizedQuery) ||
            it.host.lowercase().contains(normalizedQuery) ||
            it.peerId.lowercase().contains(normalizedQuery)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Ладья", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        onNavigateBack?.let { back ->
                            TextButton(onClick = back) { Text("Назад") }
                        }
                    },
                    actions = {
                        IconButton(onClick = { onAction(ContactsAction.RefreshRequested) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                        }
                        IconButton(onClick = { onAction(ContactsAction.OpenConnectionClicked) }) {
                            Icon(Icons.Default.Hub, contentDescription = "Сеть")
                        }
                        IconButton(onClick = { onAction(ContactsAction.OpenLocalProfileClicked) }) {
                            Icon(Icons.Default.Person, contentDescription = "Профиль")
                        }
                        IconButton(onClick = { showMore = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Ещё")
                        }
                        DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            DropdownMenuItem(
                                text = { Text("Настройки") },
                                onClick = {
                                    showMore = false
                                    onAction(ContactsAction.OpenSettingsClicked)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Добавить контакт") },
                                onClick = {
                                    showMore = false
                                    onAction(ContactsAction.AddContactClicked)
                                }
                            )
                        }
                    }
                )

                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    when (selectedTab) {
                        2 -> onAction(ContactsAction.RefreshRequested)
                        else -> onAction(ContactsAction.AddContactClicked)
                    }
                }
            ) {
                Icon(
                    imageVector = if (selectedTab == 2) Icons.Default.Refresh else Icons.Default.Add,
                    contentDescription = if (selectedTab == 2) "Обновить" else "Добавить"
                )
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = {
                        Text(
                            when (selectedTab) {
                                0 -> "Поиск по чатам"
                                1 -> "Поиск по контактам"
                                else -> "Поиск по устройствам рядом"
                            }
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                )
            }

            item {
                Surface(
                    tonalElevation = 0.dp,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "В сети: ${state.onlineCount}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (state.lastRefreshLabel.isNotBlank()) state.lastRefreshLabel else "Список обновляется автоматически",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = { onAction(ContactsAction.RefreshRequested) }) {
                            Text(if (state.isRefreshing) "Идёт поиск" else "Обновить")
                        }
                    }
                }
            }

            item {
                Surface(
                    tonalElevation = 1.dp,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AvatarCircle(title = "Я", isOnline = true, avatarEmoji = state.localAvatarEmoji)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.activeChatTitle ?: "Ваше устройство",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = state.activeChatPreview ?: "Профиль устройства, публичный ник и ваши активные чаты",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onAction(ContactsAction.OpenLocalProfileClicked) }) {
                            Text("Открыть")
                        }
                    }
                }
            }

            when (selectedTab) {
                0 -> {
                    if (threads.isEmpty()) {
                        item {
                            EmptyState(
                                title = "Чаты пока пусты",
                                subtitle = "Подключитесь к устройству или выберите собеседника"
                            )
                        }
                    } else {
                        itemsIndexed(threads, key = { index, item -> "thread-${item.key}-$index" }) { _, thread ->
                            ChatThreadRow(
                                thread = thread,
                                onOpenClick = { onAction(ContactsAction.OpenThreadClicked(it)) },
                                onOpenProfileClick = {
                                    val peer = state.discoveredPeers.firstOrNull { peer ->
                                        peer.peerId == it.peerId || peer.host == it.host
                                    }
                                    val contact = state.contacts.firstOrNull { contact ->
                                        contact.peerId == it.peerId || contact.host == it.host
                                    }
                                    when {
                                        contact != null -> onAction(ContactsAction.OpenContactProfileClicked(contact))
                                        peer != null -> onAction(ContactsAction.OpenPeerProfileClicked(peer))
                                    }
                                },
                                onAddToContactsClick = { threadItem ->
                                    state.discoveredPeers.firstOrNull {
                                        it.peerId == threadItem.peerId || it.host == threadItem.host
                                    }?.let {
                                        onAction(ContactsAction.SavePeerAsContactClicked(it))
                                    }
                                },
                                onDeleteForMeClick = { onAction(ContactsAction.DeleteThreadForMeClicked(it)) },
                                onDeleteForEveryoneClick = { onAction(ContactsAction.DeleteThreadForEveryoneClicked(it)) }
                            )
                        }
                    }
                }

                1 -> {
                    if (contacts.isEmpty()) {
                        item {
                            EmptyState(
                                title = "Контактов пока нет",
                                subtitle = "Сохраните устройство из сети или добавьте контакт вручную"
                            )
                        }
                    } else {
                        itemsIndexed(contacts, key = { index, item -> "contact-${item.id}-$index" }) { _, contact ->
                            ContactCard(
                                contact = contact,
                                onConnectClick = { onAction(ContactsAction.ConnectToContactClicked(it)) },
                                onOpenProfileClick = { onAction(ContactsAction.OpenContactProfileClicked(it)) },
                                onDeleteClick = { onAction(ContactsAction.DeleteContactClicked(it.id)) }
                            )
                        }
                    }
                }

                2 -> {
                    if (peers.isEmpty()) {
                        item {
                            EmptyState(
                                title = "Устройства не найдены",
                                subtitle = "Проверьте, что второе устройство находится в той же сети"
                            )
                        }
                    } else {
                        itemsIndexed(peers, key = { index, item -> "peer-${item.peerId}-$index" }) { _, peer ->
                            DiscoveredPeerCard(
                                peer = peer,
                                onConnectClick = { onAction(ContactsAction.ConnectToPeerClicked(it)) },
                                onOpenProfileClick = { onAction(ContactsAction.OpenPeerProfileClicked(it)) },
                                onSaveContactClick = { onAction(ContactsAction.SavePeerAsContactClicked(it)) }
                            )
                        }
                    }
                }
            }
        }
    }
}
