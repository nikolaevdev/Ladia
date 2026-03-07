package ru.mishanikolaev.ladya.ui.screens.call

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import ru.mishanikolaev.ladya.ui.components.AvatarCircle
import ru.mishanikolaev.ladya.ui.models.CallAction
import ru.mishanikolaev.ladya.ui.models.CallStatus
import ru.mishanikolaev.ladya.ui.models.CallUiState

@Composable
private fun CallRoundButton(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(60.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            )
        ) {
            icon()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun CallHeader(state: CallUiState, onNavigateBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = CircleShape, tonalElevation = 2.dp) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = state.peerTitle.ifBlank { "Собеседник" },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when (state.status) {
                    CallStatus.IncomingRinging -> if (state.isVideoCall) "Входящий видеовызов" else "Входящий вызов"
                    CallStatus.OutgoingRinging -> if (state.isVideoCall) "Вызываем…" else "Вызываем…"
                    CallStatus.Connecting -> "Подключение…"
                    CallStatus.Active -> state.durationLabel
                    CallStatus.Ended -> "Звонок завершён"
                    CallStatus.Error -> "Ошибка звонка"
                    else -> state.qualityLabel
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun StatsCard(
    state: CallUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null)
                    Text(state.qualityLabel, style = MaterialTheme.typography.bodyMedium)
                }
                IconButton(onClick = onToggle) {
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (expanded) "Свернуть статистику" else "Раскрыть статистику")
                }
            }
            Text("Микрофон: ${if (state.isMicMuted) "выключен" else "включён"} · собеседник: ${if (state.isRemoteMicMuted) "выключен" else "включён"}")
            Text("Звук: ${if (state.isSpeakerEnabled) "динамик" else "разговорный"}")
            if (state.isVideoCall) {
                Text("Камера: ${if (state.isVideoMuted) "выключена" else "включена"} · ${if (state.isFrontCamera) "фронтальная" else "тыльная"}")
                Text("Собеседник: камера ${if (state.isRemoteVideoMuted) "выключена" else "включена"}")
                Text("Профиль: ${state.adaptiveVideoProfile} · до ${state.targetVideoFps} fps · Q${state.videoJpegQuality}")
            }
            if (expanded) {
                Text("Аудио: ↑ ${state.packetsSent} / ↓ ${state.packetsReceived} · ${formatBytes(state.bytesSent + state.bytesReceived)}")
                if (state.isVideoCall) {
                    Text("Видео: ↑ ${state.videoFramesSent} / ↓ ${state.videoFramesReceived} · ${formatBytes(state.videoBytesSent + state.videoBytesReceived)}")
                    Text("Порты: аудио ${state.localPort}, видео ${state.videoLocalPort}")
                }
                state.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun CallScreen(
    state: CallUiState,
    onAction: (CallAction) -> Unit,
    onNavigateBack: () -> Unit,
    localPreviewContent: @Composable (() -> Unit)? = null
) {
    var statsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.status) {
        if (state.status == CallStatus.Idle) onNavigateBack()
    }

    BackHandler(onBack = {
        onAction(CallAction.BackClicked)
        onNavigateBack()
    })

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.surfaceContainer
                    )
                )
            )
    ) {
        if (state.isVideoCall) {
            Box(modifier = Modifier.fillMaxSize()) {
                state.remoteVideoFrame?.let { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = "Видео собеседника",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } ?: Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AvatarCircle(
                            title = state.peerTitle.ifBlank { "Собеседник" },
                            avatarEmoji = state.peerAvatarEmoji,
                            isOnline = state.status == CallStatus.Active,
                            modifier = Modifier.size(104.dp)
                        )
                        Text(
                            if (state.isRemoteVideoMuted) "Собеседник отключил камеру" else "Ожидание видеопотока",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 92.dp, end = 16.dp)
                        .size(width = 118.dp, height = 168.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (!state.isVideoMuted) {
                        localPreviewContent?.invoke() ?: Text("Камера")
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.VideocamOff, contentDescription = null)
                            Text("Видео выкл.")
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            CallHeader(state = state, onNavigateBack = onNavigateBack)

            if (!state.isVideoCall) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    AvatarCircle(
                        title = state.peerTitle.ifBlank { "Собеседник" },
                        avatarEmoji = state.peerAvatarEmoji,
                        isOnline = state.status == CallStatus.Active,
                        modifier = Modifier.size(112.dp)
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = state.peerTitle.ifBlank { "Собеседник" },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.qualityLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            StatsCard(
                state = state,
                expanded = statsExpanded,
                onToggle = { statsExpanded = !statsExpanded },
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(28.dp),
                tonalElevation = 4.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
            ) {
                when (state.status) {
                    CallStatus.IncomingRinging -> {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (state.isVideoCall) "Принять видеовызов?" else "Принять вызов?",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { onAction(CallAction.AcceptIncomingCallClicked) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(22.dp),
                                    contentPadding = PaddingValues(vertical = 16.dp)
                                ) {
                                    Icon(Icons.Default.Call, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Принять")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onAction(CallAction.DeclineIncomingCallClicked)
                                        onNavigateBack()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(22.dp),
                                    contentPadding = PaddingValues(vertical = 16.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Отклонить")
                                }
                            }
                        }
                    }

                    CallStatus.OutgoingRinging, CallStatus.Connecting, CallStatus.Active -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CallRoundButton(
                                    icon = { Icon(Icons.Default.VolumeUp, contentDescription = null) },
                                    label = if (state.isSpeakerEnabled) "Обычный" else "Динамик",
                                    onClick = { onAction(CallAction.ToggleSpeakerClicked) },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                                CallRoundButton(
                                    icon = { Icon(if (state.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic, contentDescription = null) },
                                    label = if (state.isMicMuted) "Вкл. микр." else "Mute",
                                    onClick = { onAction(CallAction.ToggleMicrophoneClicked) },
                                    containerColor = if (state.isMicMuted) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer
                                )
                                CallRoundButton(
                                    icon = { Icon(Icons.Default.CallEnd, contentDescription = null) },
                                    label = "Завершить",
                                    onClick = {
                                        onAction(CallAction.EndCallClicked)
                                        onNavigateBack()
                                    },
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                )
                            }
                            if (state.isVideoCall) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CallRoundButton(
                                        icon = { Icon(if (state.isVideoMuted) Icons.Default.VideocamOff else Icons.Default.Videocam, contentDescription = null) },
                                        label = if (state.isVideoMuted) "Камера off" else "Камера on",
                                        onClick = { onAction(CallAction.ToggleVideoClicked) },
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    CallRoundButton(
                                        icon = { Icon(Icons.Default.Cameraswitch, contentDescription = null) },
                                        label = if (state.isFrontCamera) "Тыльная" else "Фронт.",
                                        onClick = { onAction(CallAction.SwitchCameraClicked) },
                                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                    )
                                }
                            }
                        }
                    }

                    CallStatus.Ended, CallStatus.Error -> {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = if (state.status == CallStatus.Error) "Не удалось установить соединение" else "Звонок завершён",
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                            Button(onClick = { onAction(CallAction.RetryCallClicked) }, shape = RoundedCornerShape(20.dp)) {
                                Icon(Icons.Default.Call, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Повторить")
                            }
                        }
                    }

                    else -> Spacer(modifier = Modifier.height(1.dp))
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format("%.1f МБ", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format("%.1f КБ", bytes / 1024f)
        else -> "$bytes Б"
    }
}
