package ru.mishanikolaev.ladya.navigation

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.view.PreviewView
import ru.mishanikolaev.ladya.network.LadyaNodeRepository
import ru.mishanikolaev.ladya.ui.models.*
import ru.mishanikolaev.ladya.ui.screens.chat.ChatScreen
import ru.mishanikolaev.ladya.ui.screens.chat.GroupChatScreen
import ru.mishanikolaev.ladya.ui.screens.call.CallScreen
import ru.mishanikolaev.ladya.ui.screens.connection.ConnectionScreen
import ru.mishanikolaev.ladya.ui.screens.contacts.AddContactScreen
import ru.mishanikolaev.ladya.ui.screens.contacts.ContactsScreen
import ru.mishanikolaev.ladya.ui.screens.logs.LogsScreen
import ru.mishanikolaev.ladya.ui.screens.profile.ProfileScreen
import ru.mishanikolaev.ladya.ui.screens.settings.SettingsScreen

@Composable
fun AppNavHost() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        LadyaNodeRepository.ensureInitialized(context)
    }

    val connectionState by LadyaNodeRepository.connectionState.collectAsState()
    val chatState by LadyaNodeRepository.chatState.collectAsState()
    val logsState by LadyaNodeRepository.logsState.collectAsState()
    val contactsState by LadyaNodeRepository.contactsState.collectAsState()
    val addContactState by LadyaNodeRepository.addContactState.collectAsState()
    val settingsState by LadyaNodeRepository.settingsState.collectAsState()
    val profileState by LadyaNodeRepository.profileState.collectAsState()
    val callState by LadyaNodeRepository.callState.collectAsState()
    val pendingRoute by LadyaNodeRepository.pendingRoute.collectAsState()

    var currentRoute by rememberSaveable { mutableStateOf(AppRoute.Chats) }

    val filesPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
        onResult = { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                uris.forEach { selected ->
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            selected,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
                LadyaNodeRepository.setPendingFiles(uris)
            }
        }
    )


    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            LadyaNodeRepository.onMicrophonePermissionResult(granted)
            if (granted) {
                LadyaNodeRepository.resumePendingAudioCallAfterPermission()
            }
        }
    )

    val videoPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { result ->
            val micGranted = result[Manifest.permission.RECORD_AUDIO] == true
            val cameraGranted = result[Manifest.permission.CAMERA] == true
            LadyaNodeRepository.onVideoPermissionsResult(micGranted = micGranted, cameraGranted = cameraGranted)
            if (micGranted && cameraGranted) {
                LadyaNodeRepository.resumePendingVideoCallAfterPermission()
            }
        }
    )

    LaunchedEffect(connectionState.status, connectionState.canOpenChat, connectionState.shouldAutoOpenChat, currentRoute) {
        if ((currentRoute == AppRoute.Connection || currentRoute == AppRoute.Chats) &&
            connectionState.status == ConnectionStatus.Connected &&
            connectionState.canOpenChat &&
            connectionState.shouldAutoOpenChat
        ) {
            LadyaNodeRepository.consumeAutoOpenChat()
            currentRoute = AppRoute.Chat
        }
    }

    LaunchedEffect(callState.status) {
        when (callState.status) {
            CallStatus.IncomingRinging,
            CallStatus.OutgoingRinging,
            CallStatus.Connecting,
            CallStatus.Active -> {
                if (currentRoute != AppRoute.Call) currentRoute = AppRoute.Call
            }
            CallStatus.Idle -> {
                if (currentRoute == AppRoute.Call) {
                    currentRoute = if (chatState.isConnected) { if (chatState.isGroupChat || (chatState.activeThreadKey?.startsWith("group:") == true)) AppRoute.GroupChat else AppRoute.Chat } else AppRoute.Chats
                }
            }
            else -> Unit
        }
    }


    LaunchedEffect(pendingRoute) {
        pendingRoute?.let { route ->
            currentRoute = route
            LadyaNodeRepository.consumePendingRoute()
        }
    }

    LaunchedEffect(chatState.isGroupChat, chatState.activeThreadKey, currentRoute) {
        val isGroupThread = chatState.isGroupChat || (chatState.activeThreadKey?.startsWith("group:") == true)
        if (currentRoute == AppRoute.Chat && isGroupThread) {
            currentRoute = AppRoute.GroupChat
        } else if (currentRoute == AppRoute.GroupChat && !isGroupThread) {
            currentRoute = AppRoute.Chat
        }
    }

    when (currentRoute) {
        AppRoute.Chats -> ContactsScreen(
            state = contactsState,
            onAction = { action ->
                when (action) {
                    is ContactsAction.ConnectToPeerClicked -> LadyaNodeRepository.connectToPeer(action.peer)
                    is ContactsAction.ConnectToContactClicked -> LadyaNodeRepository.connectToContact(action.contact)
                    is ContactsAction.OpenThreadClicked -> {
                        LadyaNodeRepository.openThread(action.thread)
                        currentRoute = if (action.thread.key.startsWith("group:")) AppRoute.GroupChat else AppRoute.Chat
                    }
                    is ContactsAction.SavePeerAsContactClicked -> {
                        LadyaNodeRepository.prepareAddContactFromPeer(action.peer)
                        currentRoute = AppRoute.AddContact
                    }
                    is ContactsAction.OpenPeerProfileClicked -> {
                        LadyaNodeRepository.selectPeerProfile(action.peer)
                        currentRoute = AppRoute.Profile
                    }
                    is ContactsAction.OpenContactProfileClicked -> {
                        LadyaNodeRepository.selectContactProfile(action.contact)
                        currentRoute = AppRoute.Profile
                    }
                    ContactsAction.OpenLocalProfileClicked -> {
                        LadyaNodeRepository.selectLocalProfile()
                        currentRoute = AppRoute.Profile
                    }
                    ContactsAction.OpenActiveChatClicked -> currentRoute = if (chatState.isGroupChat || (chatState.activeThreadKey?.startsWith("group:") == true)) AppRoute.GroupChat else AppRoute.Chat
                    ContactsAction.AddContactClicked -> {
                        LadyaNodeRepository.prepareEmptyAddContact()
                        currentRoute = AppRoute.AddContact
                    }
                    ContactsAction.OpenConnectionClicked -> currentRoute = AppRoute.Connection
                    ContactsAction.OpenSettingsClicked -> currentRoute = AppRoute.Settings
                    ContactsAction.RefreshRequested -> LadyaNodeRepository.refreshPeers(manual = true)
                    is ContactsAction.DeleteContactClicked -> LadyaNodeRepository.deleteContact(action.contactId)
                    is ContactsAction.DeleteThreadForMeClicked -> LadyaNodeRepository.deleteThreadForMe(action.thread)
                    is ContactsAction.DeleteThreadForEveryoneClicked -> LadyaNodeRepository.deleteThreadForEveryone(action.thread)
                }
            },
            onNavigateBack = null
        )

        AppRoute.Connection -> ConnectionScreen(
            state = connectionState,
            onAction = { action ->
                when (action) {
                    is ConnectionAction.RemoteIpChanged -> LadyaNodeRepository.updateRemoteIp(action.value)
                    is ConnectionAction.RemotePortChanged -> LadyaNodeRepository.updateRemotePort(action.value)
                    ConnectionAction.ConnectClicked -> LadyaNodeRepository.connectToManualEndpoint()
                    ConnectionAction.OpenChatClicked -> currentRoute = AppRoute.Chat
                    ConnectionAction.OpenChatsClicked -> currentRoute = AppRoute.Chats
                    ConnectionAction.OpenSettingsClicked -> currentRoute = AppRoute.Settings
                    ConnectionAction.DismissError -> Unit
                    is ConnectionAction.ConnectToPeerClicked -> LadyaNodeRepository.connectToPeer(action.peer)
                }
            },
            onOpenChat = { currentRoute = AppRoute.Chat }
        )

        AppRoute.Chat -> ChatScreen(
            state = chatState,
            onAction = { action ->
                when (action) {
                    is ChatAction.MessageInputChanged -> LadyaNodeRepository.updateMessageInput(action.value)
                    ChatAction.OpenCreateGroupClicked -> LadyaNodeRepository.openCreateGroupDialog()
                    ChatAction.DismissCreateGroupClicked -> LadyaNodeRepository.dismissCreateGroupDialog()
                    is ChatAction.ToggleGroupMemberClicked -> LadyaNodeRepository.toggleGroupMemberSelection(action.peerId)
                    ChatAction.SelectAllOnlineGroupMembersClicked -> LadyaNodeRepository.selectAllOnlineGroupMembers()
                    ChatAction.ClearGroupSelectionClicked -> LadyaNodeRepository.clearGroupMemberSelection()
                    is ChatAction.GroupTitleChanged -> LadyaNodeRepository.updateGroupDraftTitle(action.value)
                    ChatAction.ConfirmCreateGroupClicked -> {
                        LadyaNodeRepository.confirmCreateGroup()
                        currentRoute = AppRoute.GroupChat
                    }
                    ChatAction.LeaveGroupClicked -> LadyaNodeRepository.leaveCurrentGroup()
                    ChatAction.OpenGroupSettingsClicked -> LadyaNodeRepository.openGroupSettingsDialog()
                    ChatAction.DismissGroupSettingsClicked -> LadyaNodeRepository.dismissGroupSettingsDialog()
                    is ChatAction.GroupSettingsTitleChanged -> LadyaNodeRepository.updateGroupSettingsDraftTitle(action.value)
                    is ChatAction.ToggleGroupAdminClicked -> LadyaNodeRepository.toggleGroupAdmin(action.peerId)
                    is ChatAction.RemoveGroupMemberClicked -> LadyaNodeRepository.removeGroupMemberFromDraft(action.peerId)
                    ChatAction.SaveGroupSettingsClicked -> LadyaNodeRepository.saveGroupSettings()
                    ChatAction.SendMessageClicked -> LadyaNodeRepository.sendMessage()
                    is ChatAction.AppendEmojiClicked -> LadyaNodeRepository.appendEmojiToMessage(action.emoji)
                    is ChatAction.VoiceNoteRecorded -> {
                        LadyaNodeRepository.sendVoiceNoteRecorded(android.net.Uri.parse(action.uriString), action.durationLabel)
                    }
                    ChatAction.PickFilesClicked -> filesPickerLauncher.launch(arrayOf("*/*"))
                    ChatAction.SendFilesClicked -> LadyaNodeRepository.sendSelectedFiles()
                    ChatAction.AcceptIncomingFileClicked -> LadyaNodeRepository.acceptIncomingFile()
                    ChatAction.DeclineIncomingFileClicked -> LadyaNodeRepository.declineIncomingFile()
                    ChatAction.ToggleAttachmentsClicked -> LadyaNodeRepository.toggleAttachmentsPanel()
                    ChatAction.HideAttachmentsClicked -> LadyaNodeRepository.hideAttachmentsPanel()
                    ChatAction.ClearPendingFilesClicked -> LadyaNodeRepository.clearPendingFiles()
                    is ChatAction.StartEditMessageClicked -> LadyaNodeRepository.startEditingMessage(action.messageId)
                    is ChatAction.EditMessageInputChanged -> LadyaNodeRepository.updateEditingMessageInput(action.value)
                    ChatAction.ConfirmEditMessageClicked -> LadyaNodeRepository.confirmEditingMessage()
                    ChatAction.CancelEditMessageClicked -> LadyaNodeRepository.cancelEditingMessage()
                    is ChatAction.DeleteMessageForMeClicked -> LadyaNodeRepository.deleteMessageForMe(action.messageId)
                    is ChatAction.DeleteMessageForEveryoneClicked -> LadyaNodeRepository.deleteMessageForEveryone(action.messageId)
                    is ChatAction.SaveAttachmentCopyClicked -> LadyaNodeRepository.saveAttachmentCopy(action.messageId)
                    ChatAction.LeaveRoomClicked -> {
                        LadyaNodeRepository.disconnectFromPeer()
                        currentRoute = AppRoute.Chats
                    }
                    ChatAction.ClearHistoryForMeClicked -> LadyaNodeRepository.clearHistoryForMe()
                    ChatAction.ClearHistoryForEveryoneClicked -> LadyaNodeRepository.clearHistoryForEveryone()
                    ChatAction.OpenLogsClicked -> currentRoute = AppRoute.Logs
                    ChatAction.OpenChatsClicked -> currentRoute = AppRoute.Chats
                    ChatAction.OpenSettingsClicked -> currentRoute = AppRoute.Settings
                    ChatAction.OpenProfileClicked -> {
                        LadyaNodeRepository.selectCurrentChatProfile()
                        currentRoute = AppRoute.Profile
                    }
                    ChatAction.StartAudioCallClicked -> {
                        if (callState.isMicPermissionGranted) {
                            LadyaNodeRepository.startAudioCall()
                            currentRoute = AppRoute.Call
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    ChatAction.StartVideoCallClicked -> {
                        if (callState.isMicPermissionGranted && callState.isCameraPermissionGranted) {
                            LadyaNodeRepository.startVideoCall()
                            currentRoute = AppRoute.Call
                        } else {
                            videoPermissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                        }
                    }
                }
            },
            onNavigateBack = { currentRoute = AppRoute.Chats },
            onOpenProfile = {
                LadyaNodeRepository.selectCurrentChatProfile()
                currentRoute = AppRoute.Profile
            }
        )


        AppRoute.GroupChat -> GroupChatScreen(
            state = chatState,
            onAction = { action ->
                when (action) {
                    is ChatAction.MessageInputChanged -> LadyaNodeRepository.updateMessageInput(action.value)
                    ChatAction.OpenCreateGroupClicked -> LadyaNodeRepository.openCreateGroupDialog()
                    ChatAction.DismissCreateGroupClicked -> LadyaNodeRepository.dismissCreateGroupDialog()
                    is ChatAction.ToggleGroupMemberClicked -> LadyaNodeRepository.toggleGroupMemberSelection(action.peerId)
                    ChatAction.SelectAllOnlineGroupMembersClicked -> LadyaNodeRepository.selectAllOnlineGroupMembers()
                    ChatAction.ClearGroupSelectionClicked -> LadyaNodeRepository.clearGroupMemberSelection()
                    is ChatAction.GroupTitleChanged -> LadyaNodeRepository.updateGroupDraftTitle(action.value)
                    ChatAction.ConfirmCreateGroupClicked -> LadyaNodeRepository.confirmCreateGroup()
                    ChatAction.LeaveGroupClicked -> {
                        LadyaNodeRepository.leaveCurrentGroup()
                        currentRoute = AppRoute.Chats
                    }
                    ChatAction.OpenGroupSettingsClicked -> LadyaNodeRepository.openGroupSettingsDialog()
                    ChatAction.DismissGroupSettingsClicked -> LadyaNodeRepository.dismissGroupSettingsDialog()
                    is ChatAction.GroupSettingsTitleChanged -> LadyaNodeRepository.updateGroupSettingsDraftTitle(action.value)
                    is ChatAction.ToggleGroupAdminClicked -> LadyaNodeRepository.toggleGroupAdmin(action.peerId)
                    is ChatAction.RemoveGroupMemberClicked -> LadyaNodeRepository.removeGroupMemberFromDraft(action.peerId)
                    ChatAction.SaveGroupSettingsClicked -> LadyaNodeRepository.saveGroupSettings()
                    ChatAction.SendMessageClicked -> LadyaNodeRepository.sendMessage()
                    is ChatAction.AppendEmojiClicked -> LadyaNodeRepository.appendEmojiToMessage(action.emoji)
                    is ChatAction.VoiceNoteRecorded -> LadyaNodeRepository.sendVoiceNoteRecorded(android.net.Uri.parse(action.uriString), action.durationLabel)
                    ChatAction.PickFilesClicked -> filesPickerLauncher.launch(arrayOf("*/*"))
                    ChatAction.SendFilesClicked -> LadyaNodeRepository.sendSelectedFiles()
                    ChatAction.AcceptIncomingFileClicked -> LadyaNodeRepository.acceptIncomingFile()
                    ChatAction.DeclineIncomingFileClicked -> LadyaNodeRepository.declineIncomingFile()
                    ChatAction.ToggleAttachmentsClicked -> LadyaNodeRepository.toggleAttachmentsPanel()
                    ChatAction.HideAttachmentsClicked -> LadyaNodeRepository.hideAttachmentsPanel()
                    ChatAction.ClearPendingFilesClicked -> LadyaNodeRepository.clearPendingFiles()
                    is ChatAction.StartEditMessageClicked -> LadyaNodeRepository.startEditingMessage(action.messageId)
                    is ChatAction.EditMessageInputChanged -> LadyaNodeRepository.updateEditingMessageInput(action.value)
                    ChatAction.ConfirmEditMessageClicked -> LadyaNodeRepository.confirmEditingMessage()
                    ChatAction.CancelEditMessageClicked -> LadyaNodeRepository.cancelEditingMessage()
                    is ChatAction.DeleteMessageForMeClicked -> LadyaNodeRepository.deleteMessageForMe(action.messageId)
                    is ChatAction.DeleteMessageForEveryoneClicked -> LadyaNodeRepository.deleteMessageForEveryone(action.messageId)
                    is ChatAction.SaveAttachmentCopyClicked -> LadyaNodeRepository.saveAttachmentCopy(action.messageId)
                    ChatAction.LeaveRoomClicked -> currentRoute = AppRoute.Chats
                    ChatAction.ClearHistoryForMeClicked -> LadyaNodeRepository.clearHistoryForMe()
                    ChatAction.ClearHistoryForEveryoneClicked -> LadyaNodeRepository.clearHistoryForEveryone()
                    ChatAction.OpenLogsClicked -> currentRoute = AppRoute.Logs
                    ChatAction.OpenChatsClicked -> currentRoute = AppRoute.Chats
                    ChatAction.OpenSettingsClicked -> currentRoute = AppRoute.Settings
                    ChatAction.OpenProfileClicked -> {
                        LadyaNodeRepository.selectCurrentChatProfile()
                        currentRoute = AppRoute.Profile
                    }
                    ChatAction.StartAudioCallClicked, ChatAction.StartVideoCallClicked -> Unit
                }
            },
            onNavigateBack = { currentRoute = AppRoute.Chats },
            onOpenProfile = {
                LadyaNodeRepository.selectCurrentChatProfile()
                currentRoute = AppRoute.Profile
            }
        )

        AppRoute.Call -> {
            val lifecycleOwner = LocalLifecycleOwner.current
            CallScreen(
                state = callState,
                onAction = { action ->
                    when (action) {
                        CallAction.AcceptIncomingCallClicked -> {
                            if (callState.isVideoCall) {
                                if (callState.isMicPermissionGranted && callState.isCameraPermissionGranted) {
                                    LadyaNodeRepository.acceptIncomingVideoCall()
                                } else {
                                    videoPermissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                                }
                            } else {
                                if (callState.isMicPermissionGranted) {
                                    LadyaNodeRepository.acceptIncomingAudioCall()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                        CallAction.DeclineIncomingCallClicked -> LadyaNodeRepository.declineIncomingAudioCall()
                        CallAction.EndCallClicked -> LadyaNodeRepository.endAudioCall()
                        CallAction.RetryCallClicked -> {
                            if (callState.isVideoCall) {
                                if (callState.isMicPermissionGranted && callState.isCameraPermissionGranted) {
                                    LadyaNodeRepository.startVideoCall()
                                } else {
                                    videoPermissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA))
                                }
                            } else {
                                if (callState.isMicPermissionGranted) {
                                    LadyaNodeRepository.startAudioCall()
                                } else {
                                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        }
                        CallAction.ToggleSpeakerClicked -> LadyaNodeRepository.toggleAudioSpeaker()
                        CallAction.ToggleMicrophoneClicked -> LadyaNodeRepository.toggleAudioMicrophone()
                        CallAction.ToggleVideoClicked -> LadyaNodeRepository.toggleVideoEnabled()
                        CallAction.SwitchCameraClicked -> LadyaNodeRepository.switchVideoCameraFacing()
                        CallAction.BackClicked -> Unit
                    }
                },
                onNavigateBack = { currentRoute = AppRoute.Chat },
                localPreviewContent = {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> PreviewView(ctx).also { LadyaNodeRepository.bindLocalVideoPreview(it, lifecycleOwner) } })
                }
            )
        }

        AppRoute.Logs -> LogsScreen(
            state = logsState,
            onAction = { action ->
                when (action) {
                    is LogsAction.FilterSelected -> LadyaNodeRepository.selectLogFilter(action.type)
                    LogsAction.ClearLogsClicked -> LadyaNodeRepository.clearLogs()
                }
            },
            onNavigateBack = { currentRoute = if (chatState.isConnected) AppRoute.Chat else AppRoute.Chats }
        )

        AppRoute.AddContact -> AddContactScreen(
            state = addContactState,
            onAction = { action ->
                when (action) {
                    is AddContactAction.DisplayNameChanged -> LadyaNodeRepository.updateAddContactDisplayName(action.value)
                    is AddContactAction.PeerIdChanged -> LadyaNodeRepository.updateAddContactPeerId(action.value)
                    is AddContactAction.HostChanged -> LadyaNodeRepository.updateAddContactHost(action.value)
                    is AddContactAction.PortChanged -> LadyaNodeRepository.updateAddContactPort(action.value)
                    AddContactAction.SaveClicked -> {
                        if (LadyaNodeRepository.saveAddContact()) currentRoute = AppRoute.Chats
                    }
                }
            },
            onNavigateBack = { currentRoute = AppRoute.Chats }
        )

        AppRoute.Settings -> SettingsScreen(
            state = settingsState,
            onAction = { action ->
                when (action) {
                    is SettingsAction.PublicNickChanged -> LadyaNodeRepository.updatePublicNick(action.value)
                    is SettingsAction.LocalDeviceNameChanged -> LadyaNodeRepository.updateLocalDeviceName(action.value)
                    is SettingsAction.AvatarEmojiChanged -> LadyaNodeRepository.updateAvatarEmoji(action.value)
                    is SettingsAction.MasterSoundsChanged -> LadyaNodeRepository.updateMasterSoundsEnabled(action.enabled)
                    is SettingsAction.IncomingCallSoundChanged -> LadyaNodeRepository.updateIncomingCallSoundEnabled(action.enabled)
                    is SettingsAction.OutgoingCallSoundChanged -> LadyaNodeRepository.updateOutgoingCallSoundEnabled(action.enabled)
                    is SettingsAction.CallConnectedSoundChanged -> LadyaNodeRepository.updateCallConnectedSoundEnabled(action.enabled)
                    is SettingsAction.CallEndedSoundChanged -> LadyaNodeRepository.updateCallEndedSoundEnabled(action.enabled)
                    is SettingsAction.MessageIncomingSoundChanged -> LadyaNodeRepository.updateMessageIncomingSoundEnabled(action.enabled)
                    is SettingsAction.MessageOutgoingSoundChanged -> LadyaNodeRepository.updateMessageOutgoingSoundEnabled(action.enabled)
                    is SettingsAction.MessageErrorSoundChanged -> LadyaNodeRepository.updateMessageErrorSoundEnabled(action.enabled)
                    is SettingsAction.FileReceivedSoundChanged -> LadyaNodeRepository.updateFileReceivedSoundEnabled(action.enabled)
                    is SettingsAction.FileSentSoundChanged -> LadyaNodeRepository.updateFileSentSoundEnabled(action.enabled)
                    is SettingsAction.DeviceFoundSoundChanged -> LadyaNodeRepository.updateDeviceFoundSoundEnabled(action.enabled)
                    is SettingsAction.DeviceVerifiedSoundChanged -> LadyaNodeRepository.updateDeviceVerifiedSoundEnabled(action.enabled)
                    is SettingsAction.DeviceWarningSoundChanged -> LadyaNodeRepository.updateDeviceWarningSoundEnabled(action.enabled)
                    is SettingsAction.NotificationSoftSoundChanged -> LadyaNodeRepository.updateNotificationSoftSoundEnabled(action.enabled)
                    is SettingsAction.PreviewSoundClicked -> LadyaNodeRepository.playPreviewSound(action.effect)
                    SettingsAction.SaveClicked -> {
                        LadyaNodeRepository.saveSettings()
                        currentRoute = AppRoute.Chats
                    }
                }
            },
            onNavigateBack = { currentRoute = AppRoute.Chats }
        )

        AppRoute.Profile -> ProfileScreen(
            state = profileState,
            onAction = { action ->
                when (action) {
                    ProfileAction.WriteClicked -> {
                        LadyaNodeRepository.openProfileWrite()
                        currentRoute = AppRoute.Chat
                    }
                    ProfileAction.AddToContactsClicked -> {
                        LadyaNodeRepository.openProfileAddToContacts()
                        currentRoute = AppRoute.AddContact
                    }
                    ProfileAction.VerifyClicked -> LadyaNodeRepository.verifyProfilePeer()
                    ProfileAction.BlockClicked -> LadyaNodeRepository.blockProfilePeer()
                    ProfileAction.UnblockClicked -> LadyaNodeRepository.unblockProfilePeer()
                    ProfileAction.BackClicked -> currentRoute = AppRoute.Chats
                }
            },
            onNavigateBack = { currentRoute = AppRoute.Chats }
        )
    }
}
