package ru.mishanikolaev.ladya.ui.models

import android.graphics.Bitmap

enum class ConnectionStatus {
    Idle,
    Listening,
    Connecting,
    Connected,
    Error
}

enum class MessageDeliveryStatus {
    Sending,
    Delivered,
    Failed
}

enum class TrustStatus {
    Unverified,
    Verified,
    Suspicious,
    Blocked
}

data class DiscoveredPeerUi(
    val id: String,
    val peerId: String,
    val title: String,
    val host: String,
    val port: Int,
    val subtitle: String = "",
    val isSavedContact: Boolean = false,
    val avatarEmoji: String? = null,
    val trustStatus: TrustStatus = TrustStatus.Unverified,
    val fingerprint: String? = null,
    val trustWarning: String? = null,
    val shortAuthString: String? = null,
    val publicKeyBase64: String? = null,
    val keyId: String? = null
)

data class MeshRouteUi(
    val peerId: String,
    val title: String,
    val host: String,
    val viaTitle: String? = null,
    val hops: Int = 1,
    val isOnline: Boolean = true,
    val trustStatus: TrustStatus = TrustStatus.Unverified,
    val securityNote: String? = null,
    val isPreferred: Boolean = false
)




data class ChatGroupDefinition(
    val id: String,
    var title: String,
    val members: MutableSet<String>,
    val ownerPeerId: String,
    val adminPeerIds: MutableSet<String> = mutableSetOf(ownerPeerId)
)

data class GroupMemberUi(
    val peerId: String,
    val title: String,
    val isOnline: Boolean = false,
    val trustStatus: TrustStatus = TrustStatus.Unverified,
    val isSelected: Boolean = false,
    val isAdmin: Boolean = false
)

data class ConnectionUiState(
    val localPeerId: String = "",
    val localDeviceName: String = "",
    val publicNick: String = "",
    val avatarEmoji: String = "🛶",
    val localFingerprint: String = "",
    val localIp: String = "—",
    val localPort: Int = 1903,
    val remoteIpInput: String = "",
    val remotePortInput: String = "1903",
    val status: ConnectionStatus = ConnectionStatus.Idle,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val canOpenChat: Boolean = false,
    val backgroundServiceActive: Boolean = false,
    val discoveredPeers: List<DiscoveredPeerUi> = emptyList(),
    val connectedPeerTitle: String? = null,
    val connectionLabel: String = "Ожидание подключения",
    val shouldAutoOpenChat: Boolean = false,
    val remoteTrustStatus: TrustStatus = TrustStatus.Unverified,
    val remoteFingerprint: String? = null,
    val securityWarning: String? = null,
    val shortAuthString: String? = null,
    val relayPendingPackets: Int = 0,
    val relayKnownRoutes: Int = 0,
    val relayForwardedPackets: Long = 0L,
    val relayDroppedPackets: Long = 0L,
    val relayTrustedRoutes: Int = 0,
    val relayUntrustedRoutes: Int = 0,
    val relayBlockedRoutes: Int = 0,
    val relayReroutedPackets: Long = 0L,
    val relayRecoveredRoutes: Long = 0L,
    val relaySecurityNote: String? = null,
    val relayTopology: List<MeshRouteUi> = emptyList(),
    val groupPacketsSent: Long = 0L,
    val groupPacketsReceived: Long = 0L,
    val groupPacketsDropped: Long = 0L
)

enum class MessageType {
    Incoming,
    Outgoing,
    System
}

data class MessageUi(
    val id: String,
    val text: String,
    val timestamp: String,
    val type: MessageType,
    val senderLabel: String? = null,
    val sentAtMillis: Long = System.currentTimeMillis(),
    val messageId: String = id,
    val deliveryStatus: MessageDeliveryStatus? = null,
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false,
    val attachmentName: String? = null,
    val attachmentMimeType: String? = null,
    val attachmentUri: String? = null,
    val attachmentSizeBytes: Long? = null,
    val isImageAttachment: Boolean = false,
    val isVoiceNote: Boolean = false,
    val voiceNoteDurationLabel: String? = null,
    val routeLabel: String? = null,
    val hopCount: Int = 0,
    val targetPeerId: String? = null
)

enum class FileTransferStatus {
    Idle,
    ReadyToSend,
    WaitingForReceiver,
    Sending,
    Receiving,
    Sent,
    Received,
    Error
}

data class SelectedFileUi(
    val id: String,
    val fileName: String,
    val fileSizeLabel: String,
    val mimeType: String = "*/*",
    val isImage: Boolean = false,
    val compressedLabel: String? = null
)

data class FileTransferUi(
    val selectedFiles: List<SelectedFileUi> = emptyList(),
    val fileName: String? = null,
    val fileSizeLabel: String? = null,
    val status: FileTransferStatus = FileTransferStatus.Idle,
    val progress: Float = 0f,
    val errorMessage: String? = null,
    val detailText: String? = null,
    val bytesProcessed: Long = 0L,
    val totalBytes: Long = 0L,
    val remainingLabel: String? = null,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0
)

data class IncomingFileOfferUi(
    val transferId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val fileSizeLabel: String,
    val mimeType: String = "*/*",
    val fromPeerLabel: String? = null,
    val totalChunks: Int = 0,
    val checksum: String? = null
)

data class ChatUiState(
    val roomTitle: String = "Комната",
    val roomSubtitle: String? = null,
    val roomAvatarEmoji: String? = null,
    val messages: List<MessageUi> = emptyList(),
    val messageInput: String = "",
    val isConnected: Boolean = false,
    val fileTransfer: FileTransferUi = FileTransferUi(),
    val autoScrollToBottom: Boolean = true,
    val incomingFileOffer: IncomingFileOfferUi? = null,
    val isPeerOnline: Boolean = false,
    val typingLabel: String? = null,
    val connectionLabel: String = "Не подключено",
    val activeThreadKey: String? = null,
    val attachmentsExpanded: Boolean = false,
    val editingMessageId: String? = null,
    val editingMessageText: String = "",
    val isGroupChat: Boolean = false,
    val groupId: String? = null,
    val groupOwnerPeerId: String? = null,
    val groupMembers: List<String> = emptyList(),
    val showCreateGroupDialog: Boolean = false,
    val availableGroupMembers: List<GroupMemberUi> = emptyList(),
    val selectedGroupMemberIds: Set<String> = emptySet(),
    val groupDraftTitle: String = "",
    val showGroupSettingsDialog: Boolean = false,
    val groupSettingsDraftTitle: String = "",
    val groupSettingsMembers: List<GroupMemberUi> = emptyList(),
    val groupAdminPeerIds: Set<String> = emptySet()
)

enum class LogType {
    All,
    System,
    Network,
    Chat,
    File,
    Error
}

data class LogUi(
    val id: String,
    val time: String,
    val type: LogType,
    val message: String
)


enum class CallStatus {
    Idle,
    OutgoingRinging,
    IncomingRinging,
    Connecting,
    Active,
    Ended,
    Error
}

data class CallUiState(
    val status: CallStatus = CallStatus.Idle,
    val peerTitle: String = "",
    val peerAvatarEmoji: String? = null,
    val isMicPermissionGranted: Boolean = false,
    val isCameraPermissionGranted: Boolean = false,
    val errorMessage: String? = null,
    val incomingInviteFrom: String? = null,
    val qualityLabel: String = "Ожидание звонка",
    val durationLabel: String = "00:00",
    val packetsSent: Long = 0L,
    val packetsReceived: Long = 0L,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val localPort: Int = 1905,
    val isSpeakerEnabled: Boolean = false,
    val isMicMuted: Boolean = false,
    val micStatusLabel: String = "Микрофон включён",
    val isVideoCall: Boolean = false,
    val isVideoMuted: Boolean = false,
    val videoStatusLabel: String = "Видео выключено",
    val videoFramesSent: Long = 0L,
    val videoFramesReceived: Long = 0L,
    val videoBytesSent: Long = 0L,
    val videoBytesReceived: Long = 0L,
    val videoLocalPort: Int = 1906,
    val remoteVideoFrame: Bitmap? = null,
    val isRemoteMicMuted: Boolean = false,
    val isRemoteVideoMuted: Boolean = false,
    val adaptiveVideoProfile: String = "HIGH",
    val targetVideoFps: Int = 15,
    val videoJpegQuality: Int = 78,
    val isFrontCamera: Boolean = true
)


data class LogsUiState(
    val logs: List<LogUi> = emptyList(),
    val selectedFilter: LogType = LogType.All
) {
    val visibleLogs: List<LogUi>
        get() = if (selectedFilter == LogType.All) logs else logs.filter { it.type == selectedFilter }
}

data class ContactUi(
    val id: String,
    val peerId: String,
    val displayName: String,
    val host: String = "",
    val port: Int = 1903,
    val isOnline: Boolean = false,
    val publicNick: String? = null,
    val avatarEmoji: String? = null,
    val trustStatus: TrustStatus = TrustStatus.Unverified,
    val fingerprint: String? = null,
    val trustWarning: String? = null,
    val publicKeyBase64: String? = null,
    val keyId: String? = null
)

data class ChatThreadUi(
    val key: String,
    val title: String,
    val preview: String,
    val timestamp: String,
    val lastMessageEpoch: Long = 0L,
    val unreadCount: Int = 0,
    val isOnline: Boolean = false,
    val isTyping: Boolean = false,
    val isSavedContact: Boolean = false,
    val peerId: String? = null,
    val host: String? = null,
    val port: Int = 1903,
    val avatarEmoji: String? = null
)

data class ContactsUiState(
    val contacts: List<ContactUi> = emptyList(),
    val discoveredPeers: List<DiscoveredPeerUi> = emptyList(),
    val localAvatarEmoji: String = "🛶",
    val activeChatTitle: String? = null,
    val activeChatPreview: String? = null,
    val threads: List<ChatThreadUi> = emptyList(),
    val isRefreshing: Boolean = false,
    val onlineCount: Int = 0,
    val lastRefreshLabel: String = ""
)

data class AddContactUiState(
    val displayName: String = "",
    val peerId: String = "",
    val host: String = "",
    val port: String = "1903",
    val errorMessage: String? = null
)

data class SoundSettingsUi(
    val masterEnabled: Boolean = true,
    val incomingCallEnabled: Boolean = true,
    val outgoingCallEnabled: Boolean = true,
    val callConnectedEnabled: Boolean = true,
    val callEndedEnabled: Boolean = true,
    val messageIncomingEnabled: Boolean = true,
    val messageOutgoingEnabled: Boolean = true,
    val messageErrorEnabled: Boolean = true,
    val fileReceivedEnabled: Boolean = true,
    val fileSentEnabled: Boolean = true,
    val deviceFoundEnabled: Boolean = true,
    val deviceVerifiedEnabled: Boolean = true,
    val deviceWarningEnabled: Boolean = true,
    val notificationSoftEnabled: Boolean = true
)

data class SettingsUiState(
    val publicNick: String = "",
    val localDeviceName: String = "",
    val backgroundServiceActive: Boolean = false,
    val avatarEmoji: String = "🛶",
    val localFingerprint: String = "",
    val soundSettings: SoundSettingsUi = SoundSettingsUi()
)

data class ProfileUiState(
    val isLocalProfile: Boolean = false,
    val title: String = "",
    val publicNick: String = "",
    val peerId: String = "",
    val host: String = "",
    val port: Int = 1903,
    val isSavedContact: Boolean = false,
    val canWrite: Boolean = false,
    val canAddToContacts: Boolean = false,
    val sourcePeer: DiscoveredPeerUi? = null,
    val sourceContact: ContactUi? = null,
    val avatarEmoji: String? = null,
    val trustStatus: TrustStatus = TrustStatus.Unverified,
    val fingerprint: String? = null,
    val securityWarning: String? = null,
    val shortAuthString: String? = null,
    val publicKeyBase64: String? = null,
    val keyId: String? = null,
    val canVerifyPeer: Boolean = false,
    val canBlockPeer: Boolean = false,
    val canUnblockPeer: Boolean = false
)

sealed interface ConnectionAction {
    data class RemoteIpChanged(val value: String) : ConnectionAction
    data class RemotePortChanged(val value: String) : ConnectionAction
    data object ConnectClicked : ConnectionAction
    data object OpenChatClicked : ConnectionAction
    data object OpenChatsClicked : ConnectionAction
    data object OpenSettingsClicked : ConnectionAction
    data object DismissError : ConnectionAction
    data class ConnectToPeerClicked(val peer: DiscoveredPeerUi) : ConnectionAction
}

sealed interface ChatAction {
    data class MessageInputChanged(val value: String) : ChatAction
    data class AppendEmojiClicked(val emoji: String) : ChatAction
    data class VoiceNoteRecorded(val uriString: String, val durationLabel: String) : ChatAction
    data object OpenCreateGroupClicked : ChatAction
    data object DismissCreateGroupClicked : ChatAction
    data class ToggleGroupMemberClicked(val peerId: String) : ChatAction
    data object SelectAllOnlineGroupMembersClicked : ChatAction
    data object ClearGroupSelectionClicked : ChatAction
    data class GroupTitleChanged(val value: String) : ChatAction
    data object ConfirmCreateGroupClicked : ChatAction
    data object LeaveGroupClicked : ChatAction
    data object OpenGroupSettingsClicked : ChatAction
    data object DismissGroupSettingsClicked : ChatAction
    data class GroupSettingsTitleChanged(val value: String) : ChatAction
    data class ToggleGroupAdminClicked(val peerId: String) : ChatAction
    data class RemoveGroupMemberClicked(val peerId: String) : ChatAction
    data object SaveGroupSettingsClicked : ChatAction
    data object SendMessageClicked : ChatAction
    data object PickFilesClicked : ChatAction
    data object SendFilesClicked : ChatAction
    data object AcceptIncomingFileClicked : ChatAction
    data object DeclineIncomingFileClicked : ChatAction
    data object ToggleAttachmentsClicked : ChatAction
    data object HideAttachmentsClicked : ChatAction
    data object ClearPendingFilesClicked : ChatAction
    data class StartEditMessageClicked(val messageId: String) : ChatAction
    data class EditMessageInputChanged(val value: String) : ChatAction
    data object ConfirmEditMessageClicked : ChatAction
    data object CancelEditMessageClicked : ChatAction
    data class DeleteMessageForMeClicked(val messageId: String) : ChatAction
    data class DeleteMessageForEveryoneClicked(val messageId: String) : ChatAction
    data class SaveAttachmentCopyClicked(val messageId: String) : ChatAction
    data object LeaveRoomClicked : ChatAction
    data object ClearHistoryForMeClicked : ChatAction
    data object ClearHistoryForEveryoneClicked : ChatAction
    data object OpenLogsClicked : ChatAction
    data object OpenChatsClicked : ChatAction
    data object OpenSettingsClicked : ChatAction
    data object OpenProfileClicked : ChatAction
    data object StartAudioCallClicked : ChatAction
    data object StartVideoCallClicked : ChatAction
}

sealed interface CallAction {
    data object AcceptIncomingCallClicked : CallAction
    data object DeclineIncomingCallClicked : CallAction
    data object EndCallClicked : CallAction
    data object RetryCallClicked : CallAction
    data object ToggleSpeakerClicked : CallAction
    data object ToggleMicrophoneClicked : CallAction
    data object ToggleVideoClicked : CallAction
    data object SwitchCameraClicked : CallAction
    data object BackClicked : CallAction
}

sealed interface LogsAction {
    data class FilterSelected(val type: LogType) : LogsAction
    data object ClearLogsClicked : LogsAction
}

sealed interface ContactsAction {
    data class ConnectToPeerClicked(val peer: DiscoveredPeerUi) : ContactsAction
    data class ConnectToContactClicked(val contact: ContactUi) : ContactsAction
    data class OpenThreadClicked(val thread: ChatThreadUi) : ContactsAction
    data class SavePeerAsContactClicked(val peer: DiscoveredPeerUi) : ContactsAction
    data class OpenPeerProfileClicked(val peer: DiscoveredPeerUi) : ContactsAction
    data class OpenContactProfileClicked(val contact: ContactUi) : ContactsAction
    data object OpenLocalProfileClicked : ContactsAction
    data object OpenActiveChatClicked : ContactsAction
    data object AddContactClicked : ContactsAction
    data object OpenConnectionClicked : ContactsAction
    data object OpenSettingsClicked : ContactsAction
    data object RefreshRequested : ContactsAction
    data class DeleteContactClicked(val contactId: String) : ContactsAction
    data class DeleteThreadForMeClicked(val thread: ChatThreadUi) : ContactsAction
    data class DeleteThreadForEveryoneClicked(val thread: ChatThreadUi) : ContactsAction
}

sealed interface AddContactAction {
    data class DisplayNameChanged(val value: String) : AddContactAction
    data class PeerIdChanged(val value: String) : AddContactAction
    data class HostChanged(val value: String) : AddContactAction
    data class PortChanged(val value: String) : AddContactAction
    data object SaveClicked : AddContactAction
}

sealed interface SettingsAction {
    data class PreviewSoundClicked(val effect: ru.mishanikolaev.ladya.sound.SoundEffect) : SettingsAction
    data class PublicNickChanged(val value: String) : SettingsAction
    data class LocalDeviceNameChanged(val value: String) : SettingsAction
    data class AvatarEmojiChanged(val value: String) : SettingsAction
    data class MasterSoundsChanged(val enabled: Boolean) : SettingsAction
    data class IncomingCallSoundChanged(val enabled: Boolean) : SettingsAction
    data class OutgoingCallSoundChanged(val enabled: Boolean) : SettingsAction
    data class CallConnectedSoundChanged(val enabled: Boolean) : SettingsAction
    data class CallEndedSoundChanged(val enabled: Boolean) : SettingsAction
    data class MessageIncomingSoundChanged(val enabled: Boolean) : SettingsAction
    data class MessageOutgoingSoundChanged(val enabled: Boolean) : SettingsAction
    data class MessageErrorSoundChanged(val enabled: Boolean) : SettingsAction
    data class FileReceivedSoundChanged(val enabled: Boolean) : SettingsAction
    data class FileSentSoundChanged(val enabled: Boolean) : SettingsAction
    data class DeviceFoundSoundChanged(val enabled: Boolean) : SettingsAction
    data class DeviceVerifiedSoundChanged(val enabled: Boolean) : SettingsAction
    data class DeviceWarningSoundChanged(val enabled: Boolean) : SettingsAction
    data class NotificationSoftSoundChanged(val enabled: Boolean) : SettingsAction
    data object SaveClicked : SettingsAction
}

sealed interface ProfileAction {
    data object WriteClicked : ProfileAction
    data object AddToContactsClicked : ProfileAction
    data object VerifyClicked : ProfileAction
    data object BlockClicked : ProfileAction
    data object UnblockClicked : ProfileAction
    data object BackClicked : ProfileAction
}
