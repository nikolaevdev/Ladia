package ru.mishanikolaev.ladya.ui.screens.chat

import android.Manifest
import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import ru.mishanikolaev.ladya.ui.components.AvatarCircle
import ru.mishanikolaev.ladya.ui.components.EmptyState
import ru.mishanikolaev.ladya.ui.components.FileTransferPanel
import ru.mishanikolaev.ladya.ui.components.IncomingFileOfferCard
import ru.mishanikolaev.ladya.ui.components.MessageBubble
import ru.mishanikolaev.ladya.ui.components.SystemMessageBubble
import ru.mishanikolaev.ladya.ui.models.ChatAction
import ru.mishanikolaev.ladya.ui.models.ChatUiState
import ru.mishanikolaev.ladya.ui.models.FileTransferStatus
import ru.mishanikolaev.ladya.ui.models.GroupMemberUi
import ru.mishanikolaev.ladya.ui.models.MessageType
import java.io.File

private val quickEmojis = listOf("😀", "😂", "👍", "🔥", "❤️", "👏", "🙏", "🤝", "👀", "🎉")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onAction: (ChatAction) -> Unit,
    onNavigateBack: () -> Unit,
    onOpenProfile: () -> Unit,
    showCallActions: Boolean = true,
    groupWindowMode: Boolean = false
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showEmojiRow by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var voiceFile by remember { mutableStateOf<File?>(null) }
    var isRecordingVoice by remember { mutableStateOf(false) }
    var isVoiceLocked by remember { mutableStateOf(false) }
    var recordStartedAt by remember { mutableLongStateOf(0L) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var groupSearchQuery by remember { mutableStateOf("") }

    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val pair = startVoiceRecording(context)
            recorder = pair.first
            voiceFile = pair.second
            if (pair.first != null && pair.second != null) {
                isRecordingVoice = true
                recordStartedAt = SystemClock.elapsedRealtime()
            }
        }
    }

    BackHandler(onBack = onNavigateBack)

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty() && state.autoScrollToBottom) {
            listState.animateScrollToItem(state.messages.lastIndex)
        }
    }

    LaunchedEffect(isRecordingVoice, recordStartedAt) {
        while (isRecordingVoice) {
            elapsedSeconds = ((SystemClock.elapsedRealtime() - recordStartedAt) / 1000L).coerceAtLeast(0L)
            delay(250)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            voiceFile?.takeIf { it.exists() && !isVoiceLocked }?.delete()
        }
    }

    if (state.showCreateGroupDialog) {
        val selectedMembers = state.availableGroupMembers.filter { it.peerId in state.selectedGroupMemberIds }
        val filteredGroupMembers = state.availableGroupMembers
            .filter { member ->
                groupSearchQuery.isBlank() || member.title.contains(groupSearchQuery, ignoreCase = true) || member.peerId.contains(groupSearchQuery, ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<GroupMemberUi> { it.isSelected }
                    .thenByDescending { it.isOnline }
                    .thenBy { it.title.lowercase() }
            )
        val suggestedTitle = when {
            selectedMembers.isEmpty() -> "Новая группа"
            selectedMembers.size == 1 -> selectedMembers.first().title
            selectedMembers.size == 2 -> selectedMembers.joinToString(", ") { it.title }
            else -> selectedMembers.take(2).joinToString(", ") { it.title } + " +${selectedMembers.size - 2}"
        }
        AlertDialog(
            onDismissRequest = { groupSearchQuery = ""; onAction(ChatAction.DismissCreateGroupClicked) },
            title = { Text("Новая mesh-группа") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.groupDraftTitle,
                        onValueChange = { onAction(ChatAction.GroupTitleChanged(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        placeholder = { Text("Название группы (по желанию)") },
                        supportingText = { Text("Если не указывать, будет: $suggestedTitle") },
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Выбрано: ${selectedMembers.size}", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { onAction(ChatAction.SelectAllOnlineGroupMembersClicked) }) { Text("Выбрать доступных") }
                            TextButton(onClick = { onAction(ChatAction.ClearGroupSelectionClicked) }) { Text("Сбросить") }
                        }
                    }
                    if (selectedMembers.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            selectedMembers.forEach { member ->
                                AssistChip(
                                    onClick = { onAction(ChatAction.ToggleGroupMemberClicked(member.peerId)) },
                                    label = { Text(member.title, maxLines = 1) }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = groupSearchQuery,
                        onValueChange = { groupSearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        placeholder = { Text("Поиск по участникам") },
                        singleLine = true
                    )
                    if (state.availableGroupMembers.isEmpty()) {
                        Text("Нет доступных соседей для группового чата", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().height(280.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredGroupMembers, key = { it.peerId }) { member ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clickable { onAction(ChatAction.ToggleGroupMemberClicked(member.peerId)) },
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (member.isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Checkbox(checked = member.isSelected, onCheckedChange = { onAction(ChatAction.ToggleGroupMemberClicked(member.peerId)) })
                                        AvatarCircle(
                                            title = member.title,
                                            isOnline = member.isOnline,
                                            modifier = Modifier.size(34.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(member.title, maxLines = 1)
                                            Text(
                                                text = when (member.trustStatus) {
                                                    ru.mishanikolaev.ladya.ui.models.TrustStatus.Verified -> "Доверен"
                                                    ru.mishanikolaev.ladya.ui.models.TrustStatus.Suspicious -> "Подозрителен"
                                                    ru.mishanikolaev.ladya.ui.models.TrustStatus.Blocked -> "Заблокирован"
                                                    else -> "Не подтверждён"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { groupSearchQuery = ""; onAction(ChatAction.ConfirmCreateGroupClicked) },
                    enabled = state.selectedGroupMemberIds.isNotEmpty(),
                    shape = RoundedCornerShape(18.dp)
                ) { Text("Создать") }
            },
            dismissButton = {
                TextButton(onClick = { groupSearchQuery = ""; onAction(ChatAction.DismissCreateGroupClicked) }) { Text("Отмена") }
            }
        )
    }

    if (state.showGroupSettingsDialog && groupWindowMode) {
        AlertDialog(
            onDismissRequest = { onAction(ChatAction.DismissGroupSettingsClicked) },
            title = { Text("Управление группой") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.groupSettingsDraftTitle,
                        onValueChange = { onAction(ChatAction.GroupSettingsTitleChanged(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Название группы") },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp)
                    )
                    Text("Участники", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.groupSettingsMembers, key = { it.peerId }) { member ->
                            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    AvatarCircle(title = member.title, avatarEmoji = if (member.peerId == state.groupOwnerPeerId) "👑" else null, isOnline = false, modifier = Modifier.size(34.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(member.title, maxLines = 1)
                                        Text(
                                            when {
                                                member.peerId == state.groupOwnerPeerId -> "Владелец"
                                                member.isAdmin -> "Администратор"
                                                else -> "Участник"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (member.peerId != state.groupOwnerPeerId) {
                                        TextButton(onClick = { onAction(ChatAction.ToggleGroupAdminClicked(member.peerId)) }) {
                                            Text(if (member.isAdmin) "Снять админа" else "Сделать админом")
                                        }
                                        IconButton(onClick = { onAction(ChatAction.RemoveGroupMemberClicked(member.peerId)) }) {
                                            Icon(Icons.Default.Close, contentDescription = "Исключить участника")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { onAction(ChatAction.SaveGroupSettingsClicked) }, shape = RoundedCornerShape(18.dp)) {
                    Text("Сохранить")
                }
            },
            dismissButton = { TextButton(onClick = { onAction(ChatAction.DismissGroupSettingsClicked) }) { Text("Отмена") } }
        )
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Удалить переписку") },
            text = {
                Text(
                    if (state.isConnected) {
                        "Можно удалить историю только у Вас или отправить команду удаления собеседнику тоже."
                    } else {
                        "Соединения нет, поэтому сейчас история будет удалена только на этом устройстве."
                    }
                )
            },
            confirmButton = {
                Column {
                    if (state.isConnected) {
                        TextButton(onClick = {
                            showClearHistoryDialog = false
                            onAction(ChatAction.ClearHistoryForEveryoneClicked)
                        }) { Text("Удалить у обоих") }
                    }
                    TextButton(onClick = {
                        showClearHistoryDialog = false
                        onAction(ChatAction.ClearHistoryForMeClicked)
                    }) { Text("Удалить только у меня") }
                }
            },
            dismissButton = { TextButton(onClick = { showClearHistoryDialog = false }) { Text("Отмена") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                title = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .let { if (groupWindowMode) it else it.clickable { onOpenProfile() } }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AvatarCircle(
                                title = state.roomTitle,
                                avatarEmoji = state.roomAvatarEmoji,
                                isOnline = if (groupWindowMode) false else state.isPeerOnline,
                                modifier = Modifier.size(40.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (groupWindowMode && state.isGroupChat) state.roomTitle.ifBlank { "Групповой чат" } else state.roomTitle,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1
                                )
                                Text(
                                    text = when {
                                        groupWindowMode && state.isGroupChat -> (state.roomSubtitle ?: "Групповой чат")
                                        else -> state.typingLabel ?: state.connectionLabel
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (state.typingLabel != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }
                        if ((state.isGroupChat || groupWindowMode) && state.groupMembers.isNotEmpty()) {
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                state.groupMembers.take(4).forEach { member ->
                                    AssistChip(onClick = { }, label = { Text(member, maxLines = 1) })
                                }
                                if (state.groupMembers.size > 4) {
                                    AssistChip(onClick = { }, label = { Text("+${state.groupMembers.size - 4}") })
                                }
                            }
                        }
                    }
                },
                actions = {
                    if (showCallActions) {
                        IconButton(onClick = { onAction(ChatAction.StartAudioCallClicked) }) {
                            Icon(Icons.Default.Call, contentDescription = "Аудиозвонок")
                        }
                        IconButton(onClick = { onAction(ChatAction.StartVideoCallClicked) }) {
                            Icon(Icons.Default.Videocam, contentDescription = "Видеозвонок")
                        }
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Меню")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (!groupWindowMode) {
                            DropdownMenuItem(text = { Text("Профиль") }, onClick = {
                                showMenu = false
                                onAction(ChatAction.OpenProfileClicked)
                            })
                            DropdownMenuItem(text = { Text("Логи") }, onClick = {
                                showMenu = false
                                onAction(ChatAction.OpenLogsClicked)
                            })
                            DropdownMenuItem(text = { Text("Создать группу") }, onClick = {
                                showMenu = false
                                onAction(ChatAction.OpenCreateGroupClicked)
                            })
                            DropdownMenuItem(text = { Text("Очистить историю") }, onClick = {
                                showMenu = false
                                showClearHistoryDialog = true
                            })
                            DropdownMenuItem(text = { Text("Настройки") }, onClick = {
                                showMenu = false
                                onAction(ChatAction.OpenSettingsClicked)
                            })
                        } else {
                            DropdownMenuItem(text = { Text("Управление группой") }, onClick = {
                                showMenu = false
                                onAction(ChatAction.OpenGroupSettingsClicked)
                            })
                            DropdownMenuItem(text = { Text("Очистить историю") }, onClick = {
                                showMenu = false
                                showClearHistoryDialog = true
                            })
                        }
                        if (state.isGroupChat) {
                            DropdownMenuItem(text = { Text("Покинуть группу") }, onClick = {
                                showMenu = false
                                onAction(ChatAction.LeaveGroupClicked)
                            })
                        }
                        DropdownMenuItem(text = { Text(if (groupWindowMode) "Закрыть группу" else "Выйти из чата") }, onClick = {
                            showMenu = false
                            onAction(ChatAction.LeaveRoomClicked)
                        })
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!groupWindowMode) state.incomingFileOffer?.let { offer ->
                    IncomingFileOfferCard(
                        offer = offer,
                        onAcceptClick = { onAction(ChatAction.AcceptIncomingFileClicked) },
                        onDeclineClick = { onAction(ChatAction.DeclineIncomingFileClicked) }
                    )
                }

                if (state.attachmentsExpanded || state.fileTransfer.status in listOf(
                        FileTransferStatus.Sending,
                        FileTransferStatus.Receiving,
                        FileTransferStatus.WaitingForReceiver
                    )
                ) {
                    FileTransferPanel(
                        state = state.fileTransfer,
                        onPickFilesClick = { onAction(ChatAction.PickFilesClicked) },
                        onSendFilesClick = { onAction(ChatAction.SendFilesClicked) },
                        onHideClick = { onAction(ChatAction.HideAttachmentsClicked) },
                        onClearClick = { onAction(ChatAction.ClearPendingFilesClicked) }
                    )
                }

                if (showEmojiRow) {
                    Surface(shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            quickEmojis.forEach { emoji ->
                                TextButton(onClick = { onAction(ChatAction.AppendEmojiClicked(emoji)) }) { Text(emoji) }
                            }
                        }
                    }
                }

                if (isRecordingVoice) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isVoiceLocked) "Голосовое записывается без удержания" else "Запись голосового сообщения",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "${formatDuration(elapsedSeconds)} · AAC/M4A",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledIconButton(
                                onClick = { isVoiceLocked = !isVoiceLocked },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Icon(
                                    if (isVoiceLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                                    contentDescription = "Закрепить запись"
                                )
                            }
                            FilledIconButton(
                                onClick = {
                                    runCatching { recorder?.stop() }
                                    runCatching { recorder?.release() }
                                    recorder = null
                                    voiceFile?.delete()
                                    voiceFile = null
                                    isRecordingVoice = false
                                    isVoiceLocked = false
                                    elapsedSeconds = 0L
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Отменить")
                            }
                            FilledIconButton(
                                onClick = {
                                    val file = voiceFile
                                    runCatching { recorder?.stop() }
                                    runCatching { recorder?.release() }
                                    recorder = null
                                    isRecordingVoice = false
                                    if (file != null && file.exists()) {
                                        val uri = runCatching {
                                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                        }.getOrElse {
                                            Uri.fromFile(file)
                                        }
                                        onAction(ChatAction.VoiceNoteRecorded(uri.toString(), formatDuration(elapsedSeconds)))
                                    }
                                    voiceFile = null
                                    isVoiceLocked = false
                                    elapsedSeconds = 0L
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Отправить голосовое")
                            }
                        }
                    }
                }

                if (state.editingMessageId != null) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        tonalElevation = 1.dp,
                        color = MaterialTheme.colorScheme.surfaceContainer
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Редактирование сообщения", style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                value = state.editingMessageText,
                                onValueChange = { onAction(ChatAction.EditMessageInputChanged(it)) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 4,
                                shape = RoundedCornerShape(18.dp)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onAction(ChatAction.CancelEditMessageClicked) }) { Text("Отмена") }
                                Button(
                                    onClick = { onAction(ChatAction.ConfirmEditMessageClicked) },
                                    enabled = state.editingMessageText.isNotBlank(),
                                    shape = RoundedCornerShape(18.dp)
                                ) { Text("Сохранить") }
                            }
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(28.dp),
                    tonalElevation = 2.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CompactCircleButton(
                                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                                onClick = { onAction(ChatAction.ToggleAttachmentsClicked) },
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                            CompactCircleButton(
                                icon = { Icon(Icons.Default.EmojiEmotions, contentDescription = null) },
                                onClick = { showEmojiRow = !showEmojiRow },
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                            CompactCircleButton(
                                icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                                onClick = {
                                    if (isRecordingVoice) return@CompactCircleButton
                                    recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                },
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                            OutlinedTextField(
                                value = state.messageInput,
                                onValueChange = { onAction(ChatAction.MessageInputChanged(it)) },
                                modifier = Modifier.weight(1f),
                                placeholder = {
                                    Text(
                                        when {
                                            state.editingMessageId != null -> "Сначала завершите редактирование"
                                            state.isGroupChat || groupWindowMode -> "Сообщение группе"
                                            state.isConnected -> "Сообщение"
                                            else -> "Нет соединения"
                                        }
                                    )
                                },
                                singleLine = false,
                                minLines = 1,
                                maxLines = 4,
                                enabled = state.editingMessageId == null,
                                shape = RoundedCornerShape(22.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when {
                                    isRecordingVoice -> "Запись идёт"
                                    state.isGroupChat || groupWindowMode -> "Групповой чат: ${state.groupMembers.size} участников"
                                    else -> "Mesh / relay активен"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { onAction(ChatAction.SendMessageClicked) },
                                enabled = state.editingMessageId == null && state.messageInput.isNotBlank() && state.isConnected,
                                shape = RoundedCornerShape(22.dp),
                                colors = ButtonDefaults.buttonColors()
                            ) {
                                Icon(Icons.Default.Send, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Отправить")
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        if (state.messages.isEmpty()) {
            EmptyState(
                title = "История пока пуста",
                subtitle = if (state.isConnected) "Напишите первое сообщение" else "Подключитесь к устройству, чтобы начать чат",
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(state.messages, key = { index, item -> "msg-${item.id}-$index" }) { _, message ->
                        when (message.type) {
                            MessageType.System -> SystemMessageBubble(text = message.text)
                            MessageType.Incoming, MessageType.Outgoing -> MessageBubble(
                                message = message,
                                onEditClick = { onAction(ChatAction.StartEditMessageClicked(it.messageId)) },
                                onDeleteForMeClick = { onAction(ChatAction.DeleteMessageForMeClicked(it.messageId)) },
                                onDeleteForEveryoneClick = { onAction(ChatAction.DeleteMessageForEveryoneClicked(it.messageId)) },
                                onSaveAttachmentClick = { onAction(ChatAction.SaveAttachmentCopyClicked(it.messageId)) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactCircleButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = containerColor)
    ) { icon() }
}

private fun startVoiceRecording(context: Context): Pair<MediaRecorder?, File?> {
    return runCatching {
        val outFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        @Suppress("DEPRECATION")
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64000)
            setAudioSamplingRate(22050)
            setOutputFile(outFile.absolutePath)
            prepare()
            start()
        }
        recorder to outFile
    }.getOrElse { null to null }
}

private fun formatDuration(totalSeconds: Long): String {
    val mins = totalSeconds / 60L
    val secs = totalSeconds % 60L
    return "%02d:%02d".format(mins, secs)
}
