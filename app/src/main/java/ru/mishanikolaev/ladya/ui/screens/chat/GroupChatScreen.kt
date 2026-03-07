package ru.mishanikolaev.ladya.ui.screens.chat

import androidx.compose.runtime.Composable
import ru.mishanikolaev.ladya.ui.models.ChatAction
import ru.mishanikolaev.ladya.ui.models.ChatUiState

@Composable
fun GroupChatScreen(
    state: ChatUiState,
    onAction: (ChatAction) -> Unit,
    onNavigateBack: () -> Unit,
    onOpenProfile: () -> Unit
) {
    ChatScreen(
        state = state,
        onAction = onAction,
        onNavigateBack = onNavigateBack,
        onOpenProfile = onOpenProfile,
        showCallActions = false,
        groupWindowMode = true
    )
}
