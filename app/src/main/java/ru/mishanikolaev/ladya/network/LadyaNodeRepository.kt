package ru.mishanikolaev.ladya.network

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import ru.mishanikolaev.ladya.call.AudioCallManager
import ru.mishanikolaev.ladya.call.VideoCallManager
import ru.mishanikolaev.ladya.network.protocol.PacketFactory
import ru.mishanikolaev.ladya.network.protocol.PacketEnvelope
import ru.mishanikolaev.ladya.network.protocol.PacketParser
import ru.mishanikolaev.ladya.network.protocol.TransferManifest
import ru.mishanikolaev.ladya.network.protocol.ProtocolConstants
import ru.mishanikolaev.ladya.network.transport.DeliveryPolicy
import ru.mishanikolaev.ladya.security.DeviceIdentityManager
import ru.mishanikolaev.ladya.sound.AppSoundManager
import ru.mishanikolaev.ladya.sound.SoundEffect
import ru.mishanikolaev.ladya.ui.models.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.min
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import ru.mishanikolaev.ladya.navigation.AppRoute
import ru.mishanikolaev.ladya.notify.AppNotificationManager
import java.io.FileOutputStream

object LadyaNodeRepository {

    const val DEFAULT_PORT = 1903
    private const val FILE_TRANSFER_PORT = 1904
    private const val AUDIO_CALL_PORT = 1905
    private const val VIDEO_CALL_PORT = 1906
    private const val RELAY_PORT = 1907
    private const val PREFS_NAME = "ladya_prefs"
    private const val PREF_PEER_ID = "peer_id"
    private const val PREF_PUBLIC_NICK = "public_nick"
    private const val PREF_DEVICE_NAME = "device_name"
    private const val PREF_AVATAR_EMOJI = "avatar_emoji"
    private const val PREF_CONTACTS_JSON = "contacts_json"
    private const val PREF_CHAT_HISTORY_JSON = "chat_history_json" // legacy
    private const val PREF_CHAT_HISTORIES_JSON = "chat_histories_json"
    private const val PREF_CHAT_ROOM_TITLE = "chat_room_title"
    private const val PREF_CHAT_THREADS_JSON = "chat_threads_json"
    private const val SERVICE_TYPE = "_ladya._tcp."
    private const val FILE_MAGIC = "LADYA_FILE_V1"
    private const val SOCKET_TIMEOUT_MS = 5000
    private const val FILE_BUFFER_SIZE = 64 * 1024
    private const val DISCOVERY_REFRESH_INTERVAL_MS = 15_000L
    private const val PEER_STALE_TIMEOUT_MS = 45_000L
    private const val AUTO_RECONNECT_COOLDOWN_MS = 12_000L
    private const val RECOVERY_RECONNECT_BASE_DELAY_MS = 2_500L
    private const val RECOVERY_RECONNECT_MAX_DELAY_MS = 15_000L
    private const val RECOVERY_RECONNECT_MAX_ATTEMPTS = 8
    private const val PEER_HEALTH_CHECK_INTERVAL_MS = 5_000L
    private const val PREF_LAST_CONNECTED_PEER_ID = "last_connected_peer_id"
    private const val PREF_LAST_CONNECTED_HOST = "last_connected_host"
    private const val PREF_LAST_CONNECTED_PORT = "last_connected_port"
    private const val DEFAULT_AVATAR_EMOJI = "🛶"
    private const val TAG = "LadyaNode"
    private const val RELAY_FILE_CHUNK_BASE64_THRESHOLD = 16 * 1024

    private data class UriMeta(
        val name: String,
        val size: Long,
        val mimeType: String,
        val sourceUri: Uri? = null,
        val compressedFromSize: Long? = null,
        val tempBytes: ByteArray? = null
    )

    private data class OutgoingFileTransfer(
        val transferId: String,
        val uri: Uri,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val chunkSize: Int,
        val totalChunks: Int,
        val checksum: String,
        val tempBytes: ByteArray? = null,
        val previewUriString: String? = null
    )

    private data class IncomingFileTransfer(
        val transferId: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val fromPeerLabel: String?,
        val chunkSize: Int,
        val totalChunks: Int,
        val checksum: String,
        var destinationUri: Uri? = null,
        var expectedChunkIndex: Int = 0,
        var receivedBytes: Long = 0L,
        var tempRelayFilePath: String? = null,
        var routeLabel: String? = null,
        var hopCount: Int = 0
    )

    private enum class VideoPermissionAction { StartOutgoing, AcceptIncoming }

    private data class PendingMessageAck(
        val packetProvider: () -> String,
        var attempts: Int = 1,
        var job: Job? = null
    )

    private data class QueuedPacket(
        val packet: String,
        val disconnectOnFailure: Boolean,
        val result: CompletableDeferred<Boolean>
    )

    private data class RecoveryCandidate(
        val host: String,
        val port: Int,
        val peerId: String? = null,
        val title: String = host,
        val reason: String
    )

    private data class RelayResolution(
        val targetPeerId: String,
        val targetTitle: String,
        val routeLabel: String?,
        val isRelayed: Boolean,
        val relayTrustStatus: TrustStatus = TrustStatus.Unverified,
        val securityWarning: String? = null,
        val relayPeerId: String? = null,
        val relayHost: String? = null,
        val recoveryCandidateUsed: Boolean = false
    )

    private enum class SessionState {
        Idle,
        Listening,
        Connecting,
        Handshaking,
        Active,
        Closing,
        Error
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var appContext: Context
    private lateinit var databaseHelper: LadyaDatabaseHelper
    private var initialized = false
    private var chatHistoryPersistenceStarted = false

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private var serverSocket: ServerSocket? = null
    private var fileServerSocket: ServerSocket? = null
    private var relayServerSocket: ServerSocket? = null
    private var listenerJob: Job? = null
    private var fileListenerJob: Job? = null
    private var relayListenerJob: Job? = null
    private var readerJob: Job? = null
    private var pingJob: Job? = null
    private var senderJob: Job? = null
    private var fileSenderJob: Job? = null
    private var discoveryRefreshJob: Job? = null
    private var recoveryReconnectJob: Job? = null
    private var peerHealthMonitorJob: Job? = null

    private var currentSocket: Socket? = null
    private var currentWriter: BufferedWriter? = null
    private val sendLock = Any()
    private val connectionLifecycleLock = Any()
    @Volatile
    private var connectionClosing = false
    @Volatile
    private var activeSocketSessionId: String? = null
    private val discoveredPeers = ConcurrentHashMap<String, DiscoveredPeerUi>()
    private val peerLastSeenAt = ConcurrentHashMap<String, Long>()
    private val relayFailureCounts = ConcurrentHashMap<String, Int>()
    private val relayLastFailureAt = ConcurrentHashMap<String, Long>()
    private val relayLastSuccessAt = ConcurrentHashMap<String, Long>()
    @Volatile private var relayReroutedPacketsCounter: Long = 0L
    @Volatile private var relayRecoveredRoutesCounter: Long = 0L
    private val outgoingTransfers = ConcurrentHashMap<String, OutgoingFileTransfer>()
    private val incomingTransfers = ConcurrentHashMap<String, IncomingFileTransfer>()
    private val pendingFileUris = mutableListOf<Uri>()
    private val outgoingPacketQueue = Channel<QueuedPacket>(Channel.UNLIMITED)
    private val outgoingFileQueue = Channel<String>(Channel.UNLIMITED)
    private val pendingMessageAcks = ConcurrentHashMap<String, PendingMessageAck>()
    private val outgoingFileRetryCounts = ConcurrentHashMap<String, Int>()
    private val deliveredIncomingMessageIds = LinkedHashSet<String>()
    private val deliveredRelayPacketIds = LinkedHashSet<String>()
    private val pendingRelayPackets = ConcurrentHashMap<String, String>()
    @Volatile private var relayForwardedPacketsCounter: Long = 0L
    @Volatile private var groupPacketsSentCounter: Long = 0L
    @Volatile private var groupPacketsReceivedCounter: Long = 0L
    @Volatile private var groupPacketsDroppedCounter: Long = 0L
    @Volatile private var relayDroppedPacketsCounter: Long = 0L
    private val threadSnapshots = ConcurrentHashMap<String, ChatThreadUi>()
    private val threadHistories = ConcurrentHashMap<String, MutableList<MessageUi>>()
    private val groupDefinitions = ConcurrentHashMap<String, ChatGroupDefinition>()
    private lateinit var audioCallManager: AudioCallManager
    private lateinit var videoCallManager: VideoCallManager
    private var audioCallStatsJob: Job? = null
    private var videoCallStatsJob: Job? = null
    private var callDurationJob: Job? = null
    private var pendingCallRemoteHost: String? = null
    private var pendingVideoPermissionAction: VideoPermissionAction? = null
    private var callStartedAtMillis: Long? = null

    private var remotePeerId: String? = null
    private var currentThreadKey: String? = null
    private var typingHeartbeatJob: Job? = null
    private var remoteTypingResetJob: Job? = null
    private var localTypingActive = false
    private var remotePublicNick: String? = null
    private var remoteDeviceName: String? = null
    private var remotePublicKey: String? = null
    private var remoteFingerprint: String? = null
    private var remoteTrustStatus: TrustStatus = TrustStatus.Unverified
    private var remoteSecurityWarning: String? = null
    private var remoteShortAuthString: String? = null
    @Volatile
    private var sessionState: SessionState = SessionState.Idle
    @Volatile
    private var lastAutoReconnectAttemptAt: Long = 0L

    private val _connectionState = MutableStateFlow(ConnectionUiState())
    val connectionState: StateFlow<ConnectionUiState> = _connectionState.asStateFlow()

    private val _chatState = MutableStateFlow(
        ChatUiState(
            roomTitle = "Комната",
            messages = listOf(
                MessageUi(
                    id = UUID.randomUUID().toString(),
                    text = "Сетевой узел запускается",
                    timestamp = timeNow(),
                    type = MessageType.System
                )
            )
        )
    )
    val chatState: StateFlow<ChatUiState> = _chatState.asStateFlow()

    private val _logsState = MutableStateFlow(LogsUiState())
    val logsState: StateFlow<LogsUiState> = _logsState.asStateFlow()

    private val _contactsState = MutableStateFlow(ContactsUiState())
    val contactsState: StateFlow<ContactsUiState> = _contactsState.asStateFlow()

    private val _addContactState = MutableStateFlow(AddContactUiState())
    val addContactState: StateFlow<AddContactUiState> = _addContactState.asStateFlow()

    private val _settingsState = MutableStateFlow(SettingsUiState())
    val settingsState: StateFlow<SettingsUiState> = _settingsState.asStateFlow()

    private val _profileState = MutableStateFlow(ProfileUiState())
    val profileState: StateFlow<ProfileUiState> = _profileState.asStateFlow()

    private val _callState = MutableStateFlow(CallUiState())
    val callState: StateFlow<CallUiState> = _callState.asStateFlow()

    private val _pendingRoute = MutableStateFlow<AppRoute?>(null)
    val pendingRoute: StateFlow<AppRoute?> = _pendingRoute.asStateFlow()
    @Volatile
    private var isAppForeground: Boolean = true

    fun setAppForeground(foreground: Boolean) {
        isAppForeground = foreground
        if (foreground) {
            AppNotificationManager.cancelIncomingCallNotification(appContext)
            AppNotificationManager.cancelMessageNotification(appContext)
        }
    }

    fun requestOpenRoute(route: AppRoute) {
        _pendingRoute.value = route
    }

    fun consumePendingRoute() {
        _pendingRoute.value = null
    }

    fun ensureInitialized(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        databaseHelper = LadyaDatabaseHelper(appContext)
        audioCallManager = AudioCallManager(appContext) { error -> handleAudioCallError(error) }
        videoCallManager = VideoCallManager(
            appContext,
            onError = { error -> handleAudioCallError(error) },
            onRecommendedProfileChanged = { profile -> sendVideoProfileRecommendation(profile) }
        )
        initialized = true

        val peerId = getOrCreatePeerId(appContext)
        val deviceName = getSavedDeviceName(appContext, peerId)
        val publicNick = getSavedPublicNick(appContext)
        val avatarEmoji = getSavedAvatarEmoji(appContext)
        migrateLegacyJsonStorageIfNeeded()
        val contacts = loadContacts(appContext)
        val localIdentity = DeviceIdentityManager.getOrCreateIdentity(appContext)
        val persistedRoomTitle = loadPersistedRoomTitle(appContext)
        loadPersistedThreadHistories(appContext).forEach { (key, messages) ->
            threadHistories[key] = messages.toMutableList()
        }
        val persistedHistory = threadHistories[persistedRoomTitle]?.toList() ?: emptyList()
        loadThreadSnapshots(appContext).forEach { threadSnapshots[it.key] = it }

        _callState.value = _callState.value.copy(
            isMicPermissionGranted = audioCallManager.hasMicrophonePermission(),
            isCameraPermissionGranted = videoCallManager.hasCameraPermission(),
            localPort = AUDIO_CALL_PORT,
            videoLocalPort = VIDEO_CALL_PORT,
            isSpeakerEnabled = audioCallManager.isSpeakerEnabled(),
            isMicMuted = audioCallManager.isMicrophoneMuted(),
            micStatusLabel = if (audioCallManager.isMicrophoneMuted()) "Микрофон выключен" else "Микрофон включён",
            isVideoMuted = videoCallManager.isVideoMuted(),
            videoStatusLabel = if (videoCallManager.isVideoMuted()) "Камера выключена" else "Камера включена",
            adaptiveVideoProfile = videoCallManager.currentAdaptiveProfile(),
            targetVideoFps = 15,
            videoJpegQuality = 78,
            isFrontCamera = videoCallManager.isUsingFrontCamera()
        )
        startAudioStatsCollector()
        startVideoStatsCollector()
        startRelayServer()

        _settingsState.value = SettingsUiState(
            publicNick = publicNick,
            localDeviceName = deviceName,
            backgroundServiceActive = false,
            avatarEmoji = avatarEmoji,
            localFingerprint = localIdentity.fingerprint,
            soundSettings = loadSoundSettings(appContext)
        )
        _contactsState.value = ContactsUiState(
            contacts = contacts,
            discoveredPeers = emptyList(),
            localAvatarEmoji = avatarEmoji,
            activeChatTitle = persistedRoomTitle.takeIf { persistedHistory.isNotEmpty() },
            activeChatPreview = persistedHistory.lastOrNull()?.text,
            threads = threadSnapshots.values.sortedByDescending { it.lastMessageEpoch },
            isRefreshing = false,
            onlineCount = 0,
            lastRefreshLabel = ""
        )
        _connectionState.value = _connectionState.value.copy(
            localPeerId = peerId,
            localDeviceName = deviceName,
            publicNick = publicNick,
            avatarEmoji = avatarEmoji,
            localFingerprint = localIdentity.fingerprint,
            localPort = DEFAULT_PORT,
            remotePortInput = DEFAULT_PORT.toString(),
            backgroundServiceActive = false,
            localIp = findLocalIpAddress() ?: "—"
        )
        if (persistedHistory.isNotEmpty()) {
            currentThreadKey = persistedRoomTitle
            threadSnapshots[persistedRoomTitle] = ChatThreadUi(
                key = persistedRoomTitle,
                title = persistedRoomTitle,
                preview = persistedHistory.lastOrNull()?.text.orEmpty(),
                timestamp = persistedHistory.lastOrNull()?.timestamp.orEmpty(),
                lastMessageEpoch = persistedHistory.lastOrNull()?.sentAtMillis ?: System.currentTimeMillis()
            )
            _chatState.value = _chatState.value.copy(
                roomTitle = persistedRoomTitle,
                roomSubtitle = null,
                activeThreadKey = persistedRoomTitle,
                messages = persistedHistory
            )
        }
        if (!chatHistoryPersistenceStarted) {
            chatHistoryPersistenceStarted = true
            scope.launch {
                _chatState.collectLatest { state ->
                    persistChatHistory(state)
                }
            }
        }
        startSenderLoop()
        startFileTransferLoop()
        publishPeers()
        log(LogType.System, "Узел инициализирован: $peerId")
    }

    private fun transitionSession(newState: SessionState, reason: String? = null) {
        if (sessionState == newState) return
        sessionState = newState
        reason?.let { log(LogType.Network, "Состояние соединения: ${newState.name} • $it") }
    }

    private fun isSessionActive(): Boolean = sessionState == SessionState.Active && isSocketConnected()

    private fun canUseControlChannel(): Boolean =
        (sessionState == SessionState.Handshaking || sessionState == SessionState.Active) && isSocketConnected()

    private fun resolveThreadKey(peerId: String?, host: String?, fallbackTitle: String? = null): String {
        return peerId?.takeIf { it.isNotBlank() }
            ?: host?.takeIf { it.isNotBlank() }
            ?: fallbackTitle?.takeIf { it.isNotBlank() }
            ?: "Комната"
    }

    private fun groupThreadKey(groupId: String): String = "group:$groupId"

    private fun threadGroupId(threadKey: String?): String? = threadKey?.takeIf { it.startsWith("group:") }?.removePrefix("group:")

    private fun currentGroupDefinition(): ChatGroupDefinition? {
        val stateGroupId = _chatState.value.groupId?.takeIf { it.isNotBlank() }
        val threadGroupId = threadGroupId(currentThreadKey)
        return listOfNotNull(stateGroupId, threadGroupId).firstNotNullOfOrNull { groupDefinitions[it] }
    }

    private fun canManageGroup(group: ChatGroupDefinition): Boolean {
        val localPeerId = _connectionState.value.localPeerId
        return localPeerId == group.ownerPeerId || localPeerId in group.adminPeerIds
    }

    private fun resolveGroupMemberLabels(group: ChatGroupDefinition): List<String> = group.members.map { memberId ->
        val base = when {
            memberId == _connectionState.value.localPeerId -> "Вы"
            discoveredPeers[memberId] != null -> discoveredPeers[memberId]!!.title
            else -> loadContacts(appContext).firstOrNull { it.peerId == memberId }?.displayName ?: memberId.take(8)
        }
        if (memberId == group.ownerPeerId) "$base • владелец" else if (memberId in group.adminPeerIds) "$base • админ" else base
    }

    private fun buildGroupMemberUi(group: ChatGroupDefinition): List<GroupMemberUi> {
        val contacts = loadContacts(appContext)
        return group.members.map { memberId ->
            val peer = discoveredPeers[memberId]
            val title = when {
                memberId == _connectionState.value.localPeerId -> "Вы"
                peer != null -> peer.title
                else -> contacts.firstOrNull { it.peerId == memberId }?.displayName ?: memberId.take(8)
            }
            GroupMemberUi(
                peerId = memberId,
                title = title,
                isOnline = peer != null,
                trustStatus = peer?.trustStatus ?: contacts.firstOrNull { it.peerId == memberId }?.trustStatus ?: TrustStatus.Unverified,
                isAdmin = memberId == group.ownerPeerId || memberId in group.adminPeerIds
            )
        }.sortedWith(compareByDescending<GroupMemberUi> { it.peerId == group.ownerPeerId }.thenBy { it.title.lowercase() })
    }

    private fun currentAvailableGroupMembers(selected: Set<String> = _chatState.value.selectedGroupMemberIds): List<GroupMemberUi> {
        val localPeerId = _connectionState.value.localPeerId
        val contacts = loadContacts(appContext)
        val result = linkedMapOf<String, GroupMemberUi>()
        discoveredPeers.values.filter { it.peerId != localPeerId && it.trustStatus != TrustStatus.Blocked }.forEach { peer ->
            result[peer.peerId] = GroupMemberUi(peerId = peer.peerId, title = peer.title, isOnline = true, trustStatus = peer.trustStatus, isSelected = peer.peerId in selected)
        }
        contacts.filter { it.peerId.isNotBlank() && it.peerId != localPeerId }.forEach { contact ->
            if (contact.peerId !in result) {
                result[contact.peerId] = GroupMemberUi(peerId = contact.peerId, title = contact.displayName, isOnline = discoveredPeers[contact.peerId] != null, trustStatus = contact.trustStatus, isSelected = contact.peerId in selected)
            }
        }
        return result.values.toList()
    }

    private fun buildSmartGroupTitle(selectedPeerIds: Set<String>): String {
        val labels = currentAvailableGroupMembers(selectedPeerIds)
            .filter { it.peerId in selectedPeerIds }
            .map { it.title.trim() }
            .filter { it.isNotBlank() }
        return when {
            labels.isEmpty() -> "Новая группа"
            labels.size == 1 -> labels.first()
            labels.size == 2 -> labels.joinToString(", ")
            else -> labels.take(2).joinToString(", ") + " +${labels.size - 2}"
        }
    }

    private fun switchToThread(
        threadKey: String,
        title: String,
        online: Boolean = false,
        typingLabel: String? = null,
        connectionLabel: String = _chatState.value.connectionLabel
    ) {
        saveCurrentThreadState()
        currentThreadKey = threadKey
        val history = threadHistories[threadKey]?.toList() ?: emptyList()
        val threadAvatar = threadSnapshots[threadKey]?.avatarEmoji
            ?: discoveredPeers[threadKey]?.avatarEmoji
            ?: loadContacts(appContext).firstOrNull { it.peerId == threadKey || it.host == threadKey }?.avatarEmoji
        val group = threadGroupId(threadKey)?.let { groupDefinitions[it] }
        _chatState.update { state ->
            state.copy(
                roomTitle = title,
                roomSubtitle = if (group != null) "${group.members.size} участников" else if (online) "Онлайн" else typingLabel ?: connectionLabel,
                activeThreadKey = threadKey,
                roomAvatarEmoji = if (group != null) "👥" else threadAvatar,
                messages = history,
                isPeerOnline = online,
                typingLabel = typingLabel,
                connectionLabel = if (group != null) "Mesh-группа" else connectionLabel,
                isGroupChat = group != null,
                groupId = group?.id,
                groupOwnerPeerId = group?.ownerPeerId,
                groupMembers = group?.let(::resolveGroupMemberLabels) ?: emptyList(),
                groupAdminPeerIds = group?.adminPeerIds?.toSet() ?: emptySet()
            )
        }
        _connectionState.update { it.copy(connectedPeerTitle = title) }
        _contactsState.update {
            it.copy(
                activeChatTitle = title.takeIf { name -> history.isNotEmpty() && name.isNotBlank() },
                activeChatPreview = history.lastOrNull()?.text
            )
        }
    }

    private fun saveCurrentThreadState() {
        val key = currentThreadKey ?: return
        val state = _chatState.value
        threadHistories[key] = state.messages.takeLast(300).toMutableList()
        databaseHelper.replaceThreadMessages(key, state.messages.takeLast(300))
        persistThreadHistories()
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_CHAT_ROOM_TITLE, state.roomTitle)
            .apply()
    }

    fun onServiceStarted(context: Context) {
        ensureInitialized(context)
        transitionSession(SessionState.Listening, "Фоновый сервис запущен")
        _connectionState.update {
            it.copy(
                backgroundServiceActive = true,
                localIp = findLocalIpAddress() ?: it.localIp,
                localDeviceName = _settingsState.value.localDeviceName,
                publicNick = _settingsState.value.publicNick
            )
        }
        _settingsState.update { it.copy(backgroundServiceActive = true) }
        acquireMulticastLock()
        startServer()
        startFileServer()
        startDiscovery()
        startAutoRefreshLoop()
        startPeerHealthMonitor()
        refreshPeers(manual = false)
    }

    fun onServiceStopped() {
        transitionSession(SessionState.Idle, "Фоновый сервис остановлен")
        stopAutoRefreshLoop()
        stopPeerHealthMonitor()
        recoveryReconnectJob?.cancel()
        recoveryReconnectJob = null
        _connectionState.update { it.copy(backgroundServiceActive = false) }
        _settingsState.update { it.copy(backgroundServiceActive = false) }
        _contactsState.update { it.copy(isRefreshing = false) }
        releaseMulticastLock()
    }

    fun refreshPeers(manual: Boolean = true) {
        if (!initialized) return
        scope.launch {
            if (manual) {
                _contactsState.update { it.copy(isRefreshing = true) }
                log(LogType.Network, "Запущено ручное обновление списка устройств")
            }
            _connectionState.update { state -> state.copy(localIp = findLocalIpAddress() ?: state.localIp) }
            pruneStalePeers(force = true)
            restartNsdRegistration()
            restartDiscoveryCycle()
            delay(if (manual) 1200 else 600)
            pruneStalePeers(force = false)
            publishPeers()
            _contactsState.update {
                it.copy(
                    isRefreshing = false,
                    lastRefreshLabel = "Обновлено ${timeNow()}"
                )
            }
        }
    }

    private fun startAutoRefreshLoop() {
        if (discoveryRefreshJob?.isActive == true) return
        discoveryRefreshJob = scope.launch {
            while (isActive) {
                delay(DISCOVERY_REFRESH_INTERVAL_MS)
                refreshPeers(manual = false)
            }
        }
    }

    private fun stopAutoRefreshLoop() {
        discoveryRefreshJob?.cancel()
        discoveryRefreshJob = null
    }

    private fun startPeerHealthMonitor() {
        if (peerHealthMonitorJob?.isActive == true) return
        peerHealthMonitorJob = scope.launch {
            while (isActive) {
                delay(PEER_HEALTH_CHECK_INTERVAL_MS)
                pruneStalePeers(force = false)
                if (!isSessionActive() && !connectionClosing && _settingsState.value.backgroundServiceActive) {
                    if (recoveryReconnectJob?.isActive != true) {
                        scheduleRecoveryReconnect("Монитор сети: сессия неактивна")
                    }
                }
            }
        }
    }

    private fun stopPeerHealthMonitor() {
        peerHealthMonitorJob?.cancel()
        peerHealthMonitorJob = null
    }

    private fun restartDiscoveryCycle() {
        val listener = discoveryListener
        if (listener != null) {
            safeStopDiscovery(listener)
            discoveryListener = null
        }
        startDiscovery()
    }

    private fun pruneStalePeers(force: Boolean = false) {
        val now = System.currentTimeMillis()
        var changed = false
        val iterator = peerLastSeenAt.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (force || now - entry.value > PEER_STALE_TIMEOUT_MS) {
                iterator.remove()
                if (discoveredPeers.remove(entry.key) != null) {
                    changed = true
                }
            }
        }
        if (changed) publishPeers()
    }

    fun updateRemoteIp(value: String) {
        _connectionState.update { it.copy(remoteIpInput = value) }
    }

    fun updateRemotePort(value: String) {
        _connectionState.update { it.copy(remotePortInput = value.filter(Char::isDigit)) }
    }

    fun connectToManualEndpoint() {
        val state = _connectionState.value
        val ip = state.remoteIpInput.trim()
        val port = state.remotePortInput.toIntOrNull() ?: DEFAULT_PORT
        if (ip.isBlank()) {
            setError("Введите IP-адрес удалённого устройства")
            return
        }
        rememberReconnectTarget(null, ip, port)
        _connectionState.update { it.copy(shouldAutoOpenChat = true) }
        connectTo(ip, port, ip)
    }

    fun connectToPeer(peer: DiscoveredPeerUi) {
        _connectionState.update {
            it.copy(
                remoteIpInput = peer.host,
                remotePortInput = peer.port.toString(),
                errorMessage = null
            )
        }
        remotePeerId = peer.peerId
        rememberReconnectTarget(peer.peerId, peer.host, peer.port)
        _connectionState.update { it.copy(shouldAutoOpenChat = true) }
        connectTo(peer.host, peer.port, peer.title)
    }

    fun connectToContact(contact: ContactUi) {
        if (contact.host.isBlank()) {
            setError("У контакта не указан IP-адрес")
            return
        }
        remotePeerId = contact.peerId.ifBlank { null }
        rememberReconnectTarget(remotePeerId, contact.host, contact.port)
        _connectionState.update {
            it.copy(
                remoteIpInput = contact.host,
                remotePortInput = contact.port.toString(),
                errorMessage = null,
                shouldAutoOpenChat = true
            )
        }
        connectTo(contact.host, contact.port, contact.displayName)
    }

    fun disconnectFromPeer() {
        endAudioCall(notifyRemote = true, systemMessage = false)
        if (canUseControlChannel()) {
            sendPacket(buildPacket(type = "DISCONNECT"))
        }
        onConnectionLost("Вы вышли из комнаты", initiatedLocally = true)
    }

    fun onMicrophonePermissionResult(granted: Boolean) {
        _callState.update { it.copy(isMicPermissionGranted = granted) }
        if (!granted) {
            setError("Для звонка нужен доступ к микрофону")
            setChatSystemMessage("Аудиозвонок недоступен без разрешения на микрофон")
        }
    }

    fun onVideoPermissionsResult(micGranted: Boolean, cameraGranted: Boolean) {
        _callState.update { it.copy(isMicPermissionGranted = micGranted, isCameraPermissionGranted = cameraGranted) }
        if (!micGranted || !cameraGranted) {
            setError("Для видеозвонка нужны разрешения на микрофон и камеру")
            setChatSystemMessage("Видеозвонок недоступен без разрешений на микрофон и камеру")
        }
    }

    fun resumePendingVideoCallAfterPermission() {
        _callState.update { it.copy(isMicPermissionGranted = audioCallManager.hasMicrophonePermission(), isCameraPermissionGranted = videoCallManager.hasCameraPermission()) }
        when (pendingVideoPermissionAction) {
            VideoPermissionAction.AcceptIncoming -> acceptIncomingVideoCall()
            else -> startVideoCall()
        }
        pendingVideoPermissionAction = null
    }

    fun bindLocalVideoPreview(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        if (_callState.value.isVideoCall && _callState.value.status != CallStatus.Idle) {
            videoCallManager.bindPreview(previewView, lifecycleOwner)
        }
    }

    fun resumePendingAudioCallAfterPermission() {
        _callState.update { it.copy(isMicPermissionGranted = audioCallManager.hasMicrophonePermission()) }
        when (_callState.value.status) {
            CallStatus.IncomingRinging -> acceptIncomingAudioCall()
            else -> startAudioCall()
        }
    }

    fun startVideoCall() {
        if (!isSessionActive()) {
            setError("Сначала подключитесь к собеседнику")
            return
        }
        if (!audioCallManager.hasMicrophonePermission() || !videoCallManager.hasCameraPermission()) {
            pendingVideoPermissionAction = VideoPermissionAction.StartOutgoing
            onVideoPermissionsResult(audioCallManager.hasMicrophonePermission(), videoCallManager.hasCameraPermission())
            return
        }
        val remoteHost = currentSocket?.inetAddress?.hostAddress ?: _connectionState.value.remoteIpInput
        if (remoteHost.isBlank()) {
            setError("Не удалось определить адрес собеседника для видеозвонка")
            return
        }
        pendingCallRemoteHost = remoteHost
        _callState.update {
            it.copy(
                status = CallStatus.OutgoingRinging,
                peerTitle = resolveConnectedPeerTitle(remoteHost).orEmpty().ifBlank { _chatState.value.roomTitle },
                peerAvatarEmoji = _chatState.value.roomAvatarEmoji,
                errorMessage = null,
                incomingInviteFrom = null,
                qualityLabel = "Вызываем собеседника по видео…",
                durationLabel = "00:00",
                isVideoCall = true,
                remoteVideoFrame = null
            )
        }
        setChatSystemMessage("Исходящий видеовызов…")
        startLoopSound(SoundEffect.OutgoingCall)
        sendPacket(buildPacket(type = "CALL_INVITE", payload = mapOf("mode" to "video", "audioPort" to AUDIO_CALL_PORT, "videoPort" to VIDEO_CALL_PORT)), disconnectOnFailure = false)
    }

    fun acceptIncomingVideoCall() {
        AppNotificationManager.cancelIncomingCallNotification(appContext)
        val remoteHost = pendingCallRemoteHost ?: currentSocket?.inetAddress?.hostAddress
        if (remoteHost.isNullOrBlank()) {
            handleAudioCallError("Не удалось определить адрес входящего видеовызова")
            return
        }
        if (!audioCallManager.hasMicrophonePermission() || !videoCallManager.hasCameraPermission()) {
            pendingVideoPermissionAction = VideoPermissionAction.AcceptIncoming
            onVideoPermissionsResult(audioCallManager.hasMicrophonePermission(), videoCallManager.hasCameraPermission())
            return
        }
        _callState.update {
            it.copy(
                status = CallStatus.Connecting,
                errorMessage = null,
                qualityLabel = "Подключаем аудио и видео…",
                peerTitle = it.peerTitle.ifBlank { resolveConnectedPeerTitle(remoteHost).orEmpty().ifBlank { _chatState.value.roomTitle } },
                isVideoCall = true
            )
        }
        stopLoopSound()
        sendPacket(buildPacket(type = "CALL_ACCEPT", payload = mapOf("mode" to "video", "audioPort" to AUDIO_CALL_PORT, "videoPort" to VIDEO_CALL_PORT)), disconnectOnFailure = false)
        startAudioStreaming(remoteHost)
    }

    fun toggleVideoEnabled() {
        val newValue = !_callState.value.isVideoMuted
        videoCallManager.setVideoMuted(newValue)
        _callState.update {
            it.copy(isVideoMuted = newValue, videoStatusLabel = if (newValue) "Камера выключена" else "Камера включена")
        }
        sendCurrentMediaState()
        if (newValue) {
            _callState.update { it.copy(remoteVideoFrame = it.remoteVideoFrame) }
        }
    }

    fun switchVideoCameraFacing() {
        videoCallManager.switchCameraFacing()
        _callState.update {
            it.copy(
                isFrontCamera = videoCallManager.isUsingFrontCamera(),
                videoStatusLabel = if (it.isVideoMuted) {
                    "Камера выключена"
                } else {
                    "${if (videoCallManager.isUsingFrontCamera()) "Фронтальная" else "Тыльная"} камера • ${it.adaptiveVideoProfile}"
                }
            )
        }
        log(LogType.Network, "Переключена ${if (videoCallManager.isUsingFrontCamera()) "фронтальная" else "тыльная"} камера")
    }

    private fun sendCurrentMediaState() {
        if (_callState.value.status == CallStatus.Idle) return
        sendPacket(
            buildPacket(
                type = "CALL_MEDIA_STATE",
                payload = mapOf(
                    "micMuted" to _callState.value.isMicMuted,
                    "videoMuted" to _callState.value.isVideoMuted,
                    "isVideoCall" to _callState.value.isVideoCall
                )
            ),
            disconnectOnFailure = false
        )
    }

    private fun sendVideoProfileRecommendation(profile: String) {
        if (_callState.value.status == CallStatus.Idle || !_callState.value.isVideoCall) return
        sendPacket(
            buildPacket(type = "CALL_VIDEO_PROFILE", payload = mapOf("profile" to profile)),
            disconnectOnFailure = false
        )
        log(LogType.Network, "Видео-профиль сети обновлён: $profile")
    }

    fun startAudioCall() {
        if (!isSessionActive()) {
            setError("Сначала подключитесь к собеседнику")
            return
        }
        if (!audioCallManager.hasMicrophonePermission()) {
            onMicrophonePermissionResult(false)
            return
        }
        val remoteHost = currentSocket?.inetAddress?.hostAddress ?: _connectionState.value.remoteIpInput
        if (remoteHost.isBlank()) {
            setError("Не удалось определить адрес собеседника для звонка")
            return
        }
        pendingCallRemoteHost = remoteHost
        _callState.update {
            it.copy(
                status = CallStatus.OutgoingRinging,
                peerTitle = resolveConnectedPeerTitle(remoteHost).orEmpty().ifBlank { _chatState.value.roomTitle },
                peerAvatarEmoji = _chatState.value.roomAvatarEmoji,
                errorMessage = null,
                incomingInviteFrom = null,
                qualityLabel = "Вызываем собеседника…",
                durationLabel = "00:00",
                isVideoCall = false,
                remoteVideoFrame = null
            )
        }
        setChatSystemMessage("Исходящий аудиовызов…")
        startLoopSound(SoundEffect.OutgoingCall)
        sendPacket(buildPacket(type = "CALL_INVITE", payload = mapOf("mode" to "audio", "audioPort" to AUDIO_CALL_PORT)), disconnectOnFailure = false)
    }

    fun acceptIncomingAudioCall() {
        AppNotificationManager.cancelIncomingCallNotification(appContext)
        val remoteHost = pendingCallRemoteHost ?: currentSocket?.inetAddress?.hostAddress
        if (remoteHost.isNullOrBlank()) {
            handleAudioCallError("Не удалось определить адрес входящего вызова")
            return
        }
        if (!audioCallManager.hasMicrophonePermission()) {
            onMicrophonePermissionResult(false)
            return
        }
        _callState.update {
            it.copy(
                status = CallStatus.Connecting,
                errorMessage = null,
                qualityLabel = "Подключаем аудиоканал…",
                peerTitle = it.peerTitle.ifBlank { resolveConnectedPeerTitle(remoteHost).orEmpty().ifBlank { _chatState.value.roomTitle } },
                isVideoCall = false,
                remoteVideoFrame = null
            )
        }
        stopLoopSound()
        sendPacket(buildPacket(type = "CALL_ACCEPT", payload = mapOf("mode" to "audio", "audioPort" to AUDIO_CALL_PORT)), disconnectOnFailure = false)
        startAudioStreaming(remoteHost)
    }

    fun declineIncomingAudioCall() {
        AppNotificationManager.cancelIncomingCallNotification(appContext)
        sendPacket(buildPacket(type = "CALL_DECLINE", payload = mapOf("reason" to "declined")), disconnectOnFailure = false)
        playSound(SoundEffect.CallEnded)
        stopAudioCallLocally("Входящий вызов отклонён", CallStatus.Ended)
    }

    fun toggleAudioSpeaker() {
        val newValue = !_callState.value.isSpeakerEnabled
        audioCallManager.setSpeakerEnabled(newValue)
        _callState.update { it.copy(isSpeakerEnabled = audioCallManager.isSpeakerEnabled()) }
    }

    fun toggleAudioMicrophone() {
        val newValue = !_callState.value.isMicMuted
        audioCallManager.setMicrophoneMuted(newValue)
        _callState.update {
            it.copy(
                isMicMuted = newValue,
                micStatusLabel = if (newValue) "Микрофон выключен" else "Микрофон включён"
            )
        }
        sendCurrentMediaState()
    }

    fun endAudioCall(notifyRemote: Boolean = true, systemMessage: Boolean = true) {
        AppNotificationManager.cancelIncomingCallNotification(appContext)
        if (notifyRemote) {
            sendPacket(buildPacket(type = "CALL_HANGUP", payload = mapOf("reason" to "local_end")), disconnectOnFailure = false)
        }
        playSound(SoundEffect.CallEnded)
        stopAudioCallLocally(if (systemMessage) "Звонок завершён" else null, CallStatus.Ended)
    }

    private fun shouldShowSystemNotifications(): Boolean = !isAppForeground

    private fun startAudioStreaming(remoteHost: String) {
        pendingCallRemoteHost = remoteHost
        val videoEnabled = _callState.value.isVideoCall
        _callState.update {
            it.copy(
                status = CallStatus.Connecting,
                qualityLabel = if (videoEnabled) "Запускаем UDP-аудио и видео…" else "Запускаем UDP-аудиоканал…",
                errorMessage = null,
                isSpeakerEnabled = audioCallManager.isSpeakerEnabled(),
                isMicMuted = audioCallManager.isMicrophoneMuted(),
                micStatusLabel = if (audioCallManager.isMicrophoneMuted()) "Микрофон выключен" else "Микрофон включён",
                isVideoMuted = videoCallManager.isVideoMuted(),
                videoStatusLabel = if (videoCallManager.isVideoMuted()) "Камера выключена" else "Камера включена"
            )
        }
        audioCallManager.start(remoteHost, AUDIO_CALL_PORT)
        if (videoEnabled) videoCallManager.start(remoteHost, VIDEO_CALL_PORT) else videoCallManager.stop()
        callStartedAtMillis = System.currentTimeMillis()
        startCallDurationTicker()
        stopLoopSound()
        playSound(SoundEffect.CallConnected)
        _callState.update {
            it.copy(
                status = CallStatus.Active,
                qualityLabel = if (videoEnabled) "Видео 1:1 • UDP JPEG + голос" else "Голос 1:1 • UDP 16 kHz",
                isSpeakerEnabled = audioCallManager.isSpeakerEnabled(),
                isMicMuted = audioCallManager.isMicrophoneMuted(),
                micStatusLabel = if (audioCallManager.isMicrophoneMuted()) "Микрофон выключен" else "Микрофон включён",
                isVideoMuted = videoCallManager.isVideoMuted(),
                videoStatusLabel = if (videoCallManager.isVideoMuted()) "Камера выключена" else "Камера включена"
            )
        }
        setChatSystemMessage(if (videoEnabled) "Видеозвонок активен" else "Аудиозвонок активен")
        log(LogType.Network, if (videoEnabled) "Видеозвонок активен с $remoteHost:$VIDEO_CALL_PORT" else "Аудиозвонок активен с $remoteHost:$AUDIO_CALL_PORT")
        sendCurrentMediaState()
    }

    private fun stopAudioCallLocally(systemMessage: String?, endStatus: CallStatus) {
        AppNotificationManager.cancelIncomingCallNotification(appContext)
        stopLoopSound()
        audioCallManager.stop()
        videoCallManager.stop()
        callStartedAtMillis = null
        callDurationJob?.cancel()
        callDurationJob = null
        pendingCallRemoteHost = null
        _callState.update {
            it.copy(
                status = endStatus,
                qualityLabel = when (endStatus) {
                    CallStatus.Error -> "Ошибка аудиоканала"
                    CallStatus.Ended -> "Звонок завершён"
                    else -> it.qualityLabel
                },
                incomingInviteFrom = null,
                remoteVideoFrame = null,
                isRemoteMicMuted = false,
                isRemoteVideoMuted = false
            )
        }
        systemMessage?.let { setChatSystemMessage(it) }
    }

    private fun handleAudioCallError(error: String) {
        log(LogType.Error, error)
        _callState.update {
            it.copy(
                status = CallStatus.Error,
                errorMessage = error,
                qualityLabel = "Ошибка аудиоканала",
                isMicMuted = audioCallManager.isMicrophoneMuted(),
                micStatusLabel = if (audioCallManager.isMicrophoneMuted()) "Микрофон выключен" else "Микрофон включён"
            )
        }
        stopAudioCallLocally(null, CallStatus.Error)
    }

    private fun startAudioStatsCollector() {
        audioCallStatsJob?.cancel()
        audioCallStatsJob = scope.launch {
            audioCallManager.stats.collect { stats ->
                _callState.update {
                    it.copy(
                        packetsSent = stats.packetsSent,
                        packetsReceived = stats.packetsReceived,
                        bytesSent = stats.bytesSent,
                        bytesReceived = stats.bytesReceived,
                        localPort = stats.listeningPort,
                        isMicPermissionGranted = audioCallManager.hasMicrophonePermission(),
                        qualityLabel = when {
                            it.status == CallStatus.Active && it.isVideoCall && stats.packetsReceived > 0L -> "Видео 1:1 • звук активен"
                            it.status == CallStatus.Active && stats.packetsReceived > 0L -> "Голос 1:1 • канал активен"
                            it.status == CallStatus.Active && it.isVideoCall -> "Видео 1:1 • ожидаем звук"
                            it.status == CallStatus.Active -> "Голос 1:1 • ожидаем голос собеседника"
                            else -> it.qualityLabel
                        }
                    )
                }
            }
        }
    }

    private fun startVideoStatsCollector() {
        videoCallStatsJob?.cancel()
        videoCallStatsJob = scope.launch {
            videoCallManager.stats.collect { stats ->
                _callState.update {
                    it.copy(
                        isCameraPermissionGranted = videoCallManager.hasCameraPermission(),
                        videoFramesSent = stats.framesSent,
                        videoFramesReceived = stats.framesReceived,
                        videoBytesSent = stats.bytesSent,
                        videoBytesReceived = stats.bytesReceived,
                        videoLocalPort = stats.listeningPort,
                        remoteVideoFrame = if (it.isRemoteVideoMuted) null else (stats.remoteFrame ?: it.remoteVideoFrame),
                        isVideoMuted = stats.isVideoMuted,
                        adaptiveVideoProfile = stats.adaptiveProfile,
                        targetVideoFps = stats.targetFps,
                        videoJpegQuality = stats.jpegQuality,
                        isFrontCamera = stats.isFrontCamera,
                        videoStatusLabel = when {
                            !it.isVideoCall -> "Видео выключено"
                            stats.isVideoMuted -> "Камера выключена"
                            it.isRemoteVideoMuted -> "Собеседник отключил камеру"
                            stats.framesReceived > 0L -> "Видео активно · ${stats.adaptiveProfile} · ${stats.effectiveFps} fps · Q${stats.jpegQuality}"
                            else -> "Ожидаем видеопоток · ${stats.adaptiveProfile}"
                        },
                        qualityLabel = when {
                            it.status == CallStatus.Active && it.isVideoCall && stats.framesReceived > 0L -> "Видео 1:1 • ${stats.networkQualityLabel} • ${stats.receiveFps} fps"
                            it.status == CallStatus.Active && it.isVideoCall -> "Видео 1:1 • адаптация ${stats.adaptiveProfile}"
                            else -> it.qualityLabel
                        }
                    )
                }
            }
        }
    }

    private fun startCallDurationTicker() {
        callDurationJob?.cancel()
        callDurationJob = scope.launch {
            while (isActive) {
                val started = callStartedAtMillis ?: break
                val elapsed = ((System.currentTimeMillis() - started) / 1000L).coerceAtLeast(0L)
                _callState.update { it.copy(durationLabel = String.format(Locale.getDefault(), "%02d:%02d", elapsed / 60, elapsed % 60)) }
                delay(1000)
            }
        }
    }

    fun clearHistoryForMe() {
        val key = currentThreadKey ?: resolveThreadKey(remotePeerId, currentSocket?.inetAddress?.hostAddress, _chatState.value.roomTitle)
        threadHistories.remove(key)
        _chatState.update {
            it.copy(
                messages = emptyList(),
                incomingFileOffer = null,
                fileTransfer = FileTransferUi(),
                attachmentsExpanded = false,
                editingMessageId = null,
                editingMessageText = ""
            )
        }
        threadSnapshots.remove(key)
        databaseHelper.deleteThread(key)
        persistThreadSnapshots()
        publishPeers()
        log(LogType.Chat, "История очищена только на этом устройстве")
    }

    fun clearHistoryForEveryone() {
        val shouldNotifyRemote = isSessionActive()
        if (shouldNotifyRemote) {
            sendPacket(buildPacket(type = "CLEAR_HISTORY"))
        }
        clearHistoryForMe()
        if (!shouldNotifyRemote) {
            setError("Нет активного соединения. История очищена только на этом устройстве")
        } else {
            log(LogType.Chat, "Запрошена очистка истории у обоих собеседников")
        }
    }

    fun openCreateGroupDialog() {
        _chatState.update {
            it.copy(
                showCreateGroupDialog = true,
                availableGroupMembers = currentAvailableGroupMembers(),
                selectedGroupMemberIds = emptySet(),
                groupDraftTitle = it.groupDraftTitle
            )
        }
    }

    fun dismissCreateGroupDialog() {
        _chatState.update { it.copy(showCreateGroupDialog = false, availableGroupMembers = emptyList(), selectedGroupMemberIds = emptySet(), groupDraftTitle = "") }
    }

    fun toggleGroupMemberSelection(peerId: String) {
        _chatState.update { state ->
            val next = state.selectedGroupMemberIds.toMutableSet().apply { if (!add(peerId)) remove(peerId) }
            state.copy(selectedGroupMemberIds = next, availableGroupMembers = currentAvailableGroupMembers(next))
        }
    }

    fun selectAllOnlineGroupMembers() {
        val selected = currentAvailableGroupMembers().mapTo(linkedSetOf()) { it.peerId }
        _chatState.update { it.copy(selectedGroupMemberIds = selected, availableGroupMembers = currentAvailableGroupMembers(selected)) }
    }

    fun clearGroupMemberSelection() {
        _chatState.update { it.copy(selectedGroupMemberIds = emptySet(), availableGroupMembers = currentAvailableGroupMembers(emptySet())) }
    }

    fun updateGroupDraftTitle(value: String) {
        _chatState.update { it.copy(groupDraftTitle = value) }
    }

    fun confirmCreateGroup() {
        val state = _chatState.value
        val localPeerId = _connectionState.value.localPeerId.takeIf { it.isNotBlank() } ?: return
        val selected = state.selectedGroupMemberIds.toMutableSet()
        if (selected.isEmpty()) {
            setError("Выберите участников группы")
            return
        }
        selected += localPeerId
        val title = state.groupDraftTitle.trim().ifBlank { buildSmartGroupTitle(state.selectedGroupMemberIds) }
        val groupId = UUID.randomUUID().toString()
        val group = ChatGroupDefinition(groupId, title, selected, localPeerId, mutableSetOf(localPeerId))
        groupDefinitions[groupId] = group
        val threadKey = groupThreadKey(groupId)
        val system = MessageUi(id = UUID.randomUUID().toString(), text = "Группа создана", timestamp = timeNow(), type = MessageType.System)
        threadHistories[threadKey] = mutableListOf(system)
        threadSnapshots[threadKey] = ChatThreadUi(key = threadKey, title = title, preview = system.text, timestamp = system.timestamp, lastMessageEpoch = System.currentTimeMillis(), avatarEmoji = "👥")
        switchToThread(threadKey, title, online = true, connectionLabel = "Mesh-группа")
        _chatState.update { it.copy(showCreateGroupDialog = false, availableGroupMembers = emptyList(), selectedGroupMemberIds = emptySet(), groupDraftTitle = "") }
        rememberThreadPreview(system.text, system.timestamp, explicitKey = threadKey, explicitTitle = title)
        publishPeers()
        selected.filter { it != localPeerId }.forEach { targetPeerId ->
            sendGroupPacketToPeer(targetPeerId, "GROUP_INVITE", "RELAY_GROUP_INVITE", mapOf("groupId" to groupId, "groupTitle" to title, "ownerPeerId" to localPeerId, "members" to selected.toList(), "admins" to listOf(localPeerId), "inviterLabel" to localDisplayName()))
        }
    }

    fun openGroupSettingsDialog() {
        val group = currentGroupDefinition()
        if (group == null) {
            setError("Группа не найдена")
            return
        }
        if (!canManageGroup(group)) {
            setError("Управлять группой может только владелец или администратор")
            return
        }
        _chatState.update {
            it.copy(
                showGroupSettingsDialog = true,
                groupSettingsDraftTitle = group.title,
                groupSettingsMembers = buildGroupMemberUi(group),
                groupAdminPeerIds = group.adminPeerIds.toSet()
            )
        }
    }

    fun dismissGroupSettingsDialog() {
        _chatState.update { it.copy(showGroupSettingsDialog = false, groupSettingsDraftTitle = "", groupSettingsMembers = emptyList(), groupAdminPeerIds = emptySet()) }
    }

    fun updateGroupSettingsDraftTitle(value: String) {
        _chatState.update { it.copy(groupSettingsDraftTitle = value) }
    }

    fun toggleGroupAdmin(peerId: String) {
        val group = currentGroupDefinition() ?: return
        if (peerId == group.ownerPeerId) return
        _chatState.update { state ->
            val admins = state.groupAdminPeerIds.toMutableSet().apply { if (!add(peerId)) remove(peerId) }
            state.copy(
                groupAdminPeerIds = admins,
                groupSettingsMembers = state.groupSettingsMembers.map { member ->
                    if (member.peerId == peerId) member.copy(isAdmin = peerId in admins) else member
                }
            )
        }
    }

    fun removeGroupMemberFromDraft(peerId: String) {
        val group = currentGroupDefinition() ?: return
        if (peerId == group.ownerPeerId) return
        _chatState.update { state ->
            state.copy(
                groupSettingsMembers = state.groupSettingsMembers.filterNot { it.peerId == peerId },
                groupAdminPeerIds = state.groupAdminPeerIds - peerId
            )
        }
    }

    fun saveGroupSettings() {
        val group = currentGroupDefinition() ?: return
        if (!canManageGroup(group)) {
            setError("Недостаточно прав для изменения группы")
            return
        }
        val state = _chatState.value
        val newTitle = state.groupSettingsDraftTitle.trim().ifBlank { group.title }
        val oldMembers = group.members.toSet()
        val newMembers = state.groupSettingsMembers.mapTo(mutableSetOf()) { it.peerId }.apply { add(_connectionState.value.localPeerId) }
        val newAdmins = state.groupAdminPeerIds.toMutableSet().apply { add(group.ownerPeerId); retainAll(newMembers) }
        group.title = newTitle
        group.members.clear(); group.members.addAll(newMembers)
        group.adminPeerIds.clear(); group.adminPeerIds.addAll(newAdmins)
        val threadKey = groupThreadKey(group.id)
        threadSnapshots[threadKey] = (threadSnapshots[threadKey] ?: ChatThreadUi(key = threadKey, title = newTitle, preview = "Настройки группы обновлены", timestamp = timeNow(), lastMessageEpoch = System.currentTimeMillis(), avatarEmoji = "👥")).copy(title = newTitle)
        val system = MessageUi(id = UUID.randomUUID().toString(), text = "Настройки группы обновлены", timestamp = timeNow(), type = MessageType.System)
        val history = threadHistories.getOrPut(threadKey) { mutableListOf() }
        history += system
        _chatState.update {
            it.copy(
                showGroupSettingsDialog = false,
                groupSettingsDraftTitle = "",
                groupSettingsMembers = emptyList(),
                roomTitle = newTitle,
                roomSubtitle = "${group.members.size} участников",
                groupMembers = resolveGroupMemberLabels(group),
                groupAdminPeerIds = group.adminPeerIds.toSet(),
                messages = if (it.activeThreadKey == threadKey) history.toList() else it.messages
            )
        }
        rememberThreadPreview(system.text, system.timestamp, explicitKey = threadKey, explicitTitle = newTitle)
        val payload = mapOf("groupId" to group.id, "groupTitle" to newTitle, "ownerPeerId" to group.ownerPeerId, "members" to group.members.toList(), "admins" to group.adminPeerIds.toList(), "actorLabel" to localDisplayName())
        (oldMembers + newMembers).filter { it != _connectionState.value.localPeerId }.forEach { targetPeerId ->
            sendGroupPacketToPeer(targetPeerId, "GROUP_UPDATE_SETTINGS", "RELAY_GROUP_UPDATE_SETTINGS", payload)
        }
        publishPeers()
    }

    fun leaveCurrentGroup() {
        val group = currentGroupDefinition() ?: return
        val localPeerId = _connectionState.value.localPeerId
        val remainingMembers = group.members.filter { it != localPeerId }.toMutableSet()
        val nextOwner = when {
            group.ownerPeerId != localPeerId -> group.ownerPeerId
            group.adminPeerIds.any { it != localPeerId && it in remainingMembers } -> group.adminPeerIds.first { it != localPeerId && it in remainingMembers }
            remainingMembers.isNotEmpty() -> remainingMembers.first()
            else -> ""
        }
        val nextAdmins = group.adminPeerIds.filter { it != localPeerId && it in remainingMembers }.toMutableSet().apply {
            if (nextOwner.isNotBlank()) add(nextOwner)
        }
        remainingMembers.forEach { targetPeerId ->
            sendGroupPacketToPeer(
                targetPeerId,
                "GROUP_LEAVE",
                "RELAY_GROUP_LEAVE",
                mapOf(
                    "groupId" to group.id,
                    "groupTitle" to group.title,
                    "ownerPeerId" to nextOwner,
                    "members" to remainingMembers.toList(),
                    "admins" to nextAdmins.toList(),
                    "peerId" to localPeerId,
                    "senderLabel" to localDisplayName()
                )
            )
        }
        val threadKey = groupThreadKey(group.id)
        val localSystem = MessageUi(id = UUID.randomUUID().toString(), text = "Вы покинули группу ${group.title}", timestamp = timeNow(), type = MessageType.System)
        threadHistories[threadKey] = mutableListOf(localSystem)
        threadSnapshots.remove(threadKey)
        groupDefinitions.remove(group.id)
        if (currentThreadKey == threadKey) currentThreadKey = null
        _chatState.update {
            it.copy(
                isGroupChat = false,
                groupId = null,
                groupOwnerPeerId = null,
                groupMembers = emptyList(),
                groupAdminPeerIds = emptySet(),
                showGroupSettingsDialog = false,
                groupSettingsDraftTitle = "",
                groupSettingsMembers = emptyList(),
                roomTitle = "Чаты",
                roomSubtitle = null,
                roomAvatarEmoji = null,
                messages = emptyList(),
                activeThreadKey = null
            )
        }
        publishPeers()
    }

    private fun sendGroupPacketToPeer(targetPeerId: String, directType: String, relayType: String, payload: Map<String, Any?>): Boolean {
        if (targetPeerId.isBlank()) return false
        if (isSessionActive() && remotePeerId == targetPeerId) {
            return sendPacket(buildPacket(directType, payload), disconnectOnFailure = false)
        }
        val packet = buildRelayPacket(relayType, targetPeerId, payload, ttl = 6)
        val directHost = discoveredPeers[targetPeerId]?.host
        val relayHost = when {
            !directHost.isNullOrBlank() -> directHost
            currentSocket?.inetAddress?.hostAddress?.isNotBlank() == true -> currentSocket?.inetAddress?.hostAddress
            else -> selectBestRelayPeer(targetPeerId)?.host
        } ?: return false
        return sendRelayPacketDirect(relayHost, packet)
    }

    private fun sendGroupMessage() {
        val state = _chatState.value
        val group = currentGroupDefinition() ?: return
        val text = state.messageInput.trim()
        if (text.isBlank()) return
        val messageId = UUID.randomUUID().toString()
        val outgoing = MessageUi(id = messageId, messageId = messageId, text = text, timestamp = timeNow(), type = MessageType.Outgoing, senderLabel = localDisplayName(), deliveryStatus = MessageDeliveryStatus.Delivered, routeLabel = "mesh")
        _chatState.update { it.copy(messageInput = "", messages = it.messages + outgoing) }
        syncCurrentThreadMessages()
        rememberThreadPreview(text, outgoing.timestamp, explicitKey = groupThreadKey(group.id), explicitTitle = group.title)
        playSound(SoundEffect.MessageOutgoing)
        val localPeerId = _connectionState.value.localPeerId
        group.members.filter { it != localPeerId }.forEach { targetPeerId ->
            if (sendGroupPacketToPeer(targetPeerId, "GROUP_MESSAGE", "RELAY_GROUP_MESSAGE", mapOf("groupId" to group.id, "groupTitle" to group.title, "ownerPeerId" to group.ownerPeerId, "members" to group.members.toList(), "messageId" to messageId, "text" to text, "senderPeerId" to localPeerId, "senderLabel" to localDisplayName()))) {
                groupPacketsSentCounter += 1
            } else {
                groupPacketsDroppedCounter += 1
            }
        }
        updateRelayDiagnostics()
    }

    private fun sendGroupAttachment(uri: Uri, durationLabel: String? = null) {
        val group = currentGroupDefinition() ?: return
        val meta = readUriMeta(uri)
        val source = meta.sourceUri ?: uri
        val bytes = meta.tempBytes ?: appContext.contentResolver.openInputStream(source)?.use { it.readBytes() } ?: run {
            setError("Не удалось прочитать вложение для группы")
            return
        }
        if (bytes.size > 350_000) {
            setError("Для group MVP пока поддерживаются вложения до 350 КБ")
            return
        }
        val messageId = UUID.randomUUID().toString()
        val mimeType = meta.mimeType.ifBlank { "application/octet-stream" }
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val isVoice = mimeType.startsWith("audio/")
        val label = if (isVoice) "Голосовое сообщение" else meta.name
        val outgoing = MessageUi(
            id = messageId,
            messageId = messageId,
            text = label,
            timestamp = timeNow(),
            type = MessageType.Outgoing,
            senderLabel = localDisplayName(),
            deliveryStatus = MessageDeliveryStatus.Delivered,
            attachmentName = meta.name,
            attachmentMimeType = mimeType,
            attachmentUri = uri.toString(),
            attachmentSizeBytes = meta.size,
            isImageAttachment = mimeType.startsWith("image/"),
            isVoiceNote = isVoice,
            voiceNoteDurationLabel = durationLabel,
            routeLabel = "mesh"
        )
        _chatState.update { it.copy(messages = it.messages + outgoing, attachmentsExpanded = false, fileTransfer = FileTransferUi()) }
        syncCurrentThreadMessages()
        rememberThreadPreview(label, outgoing.timestamp, explicitKey = groupThreadKey(group.id), explicitTitle = group.title)
        val localPeerId = _connectionState.value.localPeerId
        group.members.filter { it != localPeerId }.forEach { targetPeerId ->
            val sent = sendGroupPacketToPeer(targetPeerId, "GROUP_FILE", "RELAY_GROUP_FILE", mapOf(
                "groupId" to group.id,
                "groupTitle" to group.title,
                "ownerPeerId" to group.ownerPeerId,
                "members" to group.members.toList(),
                "messageId" to messageId,
                "fileName" to meta.name,
                "mimeType" to mimeType,
                "fileSize" to meta.size,
                "attachmentData" to base64,
                "senderPeerId" to localPeerId,
                "senderLabel" to localDisplayName(),
                "durationLabel" to durationLabel,
                "isVoice" to isVoice
            ))
            if (sent) groupPacketsSentCounter += 1 else groupPacketsDroppedCounter += 1
        }
        playSound(if (isVoice) SoundEffect.MessageOutgoing else SoundEffect.FileSent)
        updateRelayDiagnostics()
    }

    fun sendMessage() {
        if (_chatState.value.isGroupChat && _chatState.value.groupId != null) {
            sendGroupMessage()
            return
        }
        val text = _chatState.value.messageInput.trim()
        if (text.isBlank()) return
        if (!isSessionActive()) {
            setChatSystemMessage("Нет активного соединения")
            setError("Сначала подключитесь к другому устройству")
            return
        }

        val resolution = resolveActiveChatTarget() ?: run {
            setError("Не удалось определить получателя")
            return
        }
        resolution.securityWarning?.let { warning ->
            setChatSystemMessage("Relay-маршрут: $warning")
        }
        if (resolution.recoveryCandidateUsed) {
            relayRecoveredRoutesCounter += 1
            log(LogType.Network, "Mesh: выбран альтернативный relay-маршрут для ${resolution.targetTitle}")
            updateRelayDiagnostics()
        }

        val messageId = UUID.randomUUID().toString()
        val outgoing = MessageUi(
            id = messageId,
            messageId = messageId,
            text = text,
            timestamp = timeNow(),
            type = MessageType.Outgoing,
            senderLabel = localDisplayName(),
            deliveryStatus = MessageDeliveryStatus.Sending,
            routeLabel = resolution.routeLabel,
            targetPeerId = resolution.targetPeerId
        )

        _chatState.update {
            it.copy(
                messageInput = "",
                messages = it.messages + outgoing
            )
        }
        rememberThreadPreview(outgoing.text, outgoing.timestamp)
        playSound(SoundEffect.MessageOutgoing)
        pushTypingState(false)

        val packetProvider = {
            if (resolution.isRelayed) {
                buildRelayPacket(
                    type = "RELAY_CHAT",
                    targetPeerId = resolution.targetPeerId,
                    payload = mapOf(
                        "messageId" to messageId,
                        "text" to text,
                        "senderLabel" to localDisplayName(),
                        "originalSenderPeerId" to _connectionState.value.localPeerId,
                        "originalSenderHost" to _connectionState.value.localIp,
                        "finalTargetPeerId" to resolution.targetPeerId,
                        "finalTargetTitle" to resolution.targetTitle,
                        "isEdited" to false,
                        "isDeleted" to false,
                        "hopCount" to 1,
                        "routeLabel" to resolution.routeLabel
                    )
                )
            } else {
                buildPacket(
                    type = "CHAT",
                    payload = mapOf(
                        "messageId" to messageId,
                        "text" to text,
                        "senderLabel" to localDisplayName(),
                        "isEdited" to false,
                        "isDeleted" to false
                    )
                )
            }
        }

        scope.launch {
            val sent = sendPacket(packetProvider())
            if (sent) {
                scheduleMessageAckRetry(messageId, packetProvider)
                log(LogType.Chat, if (resolution.isRelayed) "Отправлено relay-сообщение $messageId для ${resolution.targetTitle}" else "Отправлено сообщение $messageId")
            } else {
                markMessageDelivery(messageId, MessageDeliveryStatus.Failed)
                playSound(SoundEffect.MessageError)
                setError("Сообщение не отправлено. Проверьте соединение")
            }
        }
    }

    fun updateMessageInput(value: String) {
        _chatState.update { it.copy(messageInput = value) }
        if (isSessionActive()) {
            pushTypingState(value.isNotBlank())
        }
    }

    fun appendEmojiToMessage(emoji: String) {
        if (emoji.isBlank()) return
        updateMessageInput(_chatState.value.messageInput + emoji)
    }

    fun setPendingFiles(uris: List<Uri>) {
        pendingFileUris.clear()
        pendingFileUris.addAll(uris.distinct())
        val selected = pendingFileUris.map { uri ->
            val meta = readUriMeta(uri)
            SelectedFileUi(
                id = uri.toString(),
                fileName = meta.name,
                fileSizeLabel = formatSize(meta.size),
                mimeType = meta.mimeType,
                isImage = meta.mimeType.startsWith("image/"),
                compressedLabel = if (meta.compressedFromSize != null) "сжато из ${formatSize(meta.compressedFromSize)}" else null
            )
        }
        val totalBytes = pendingFileUris.sumOf { readUriMeta(it).size.coerceAtLeast(0L) }

        _chatState.update {
            it.copy(
                attachmentsExpanded = selected.isNotEmpty(),
                fileTransfer = it.fileTransfer.copy(
                    selectedFiles = selected,
                    fileName = selected.firstOrNull()?.fileName,
                    fileSizeLabel = if (selected.size == 1) selected.firstOrNull()?.fileSizeLabel else formatSize(totalBytes),
                    status = if (selected.isEmpty()) FileTransferStatus.Idle else FileTransferStatus.ReadyToSend,
                    progress = 0f,
                    errorMessage = null,
                    bytesProcessed = 0L,
                    totalBytes = totalBytes,
                    remainingLabel = if (totalBytes > 0L) formatSize(totalBytes) else null,
                    completedFiles = 0,
                    totalFiles = selected.size,
                    detailText = when {
                        selected.isEmpty() -> null
                        selected.size == 1 -> if (selected.firstOrNull()?.compressedLabel != null) "Фото будет отправлено со сжатием" else "Файл готов к отправке"
                        else -> "Выбрано ${selected.size} файлов"
                    }
                )
            )
        }
    }

    fun clearPendingFiles() {
        pendingFileUris.clear()
        _chatState.update {
            it.copy(fileTransfer = FileTransferUi(), attachmentsExpanded = false)
        }
    }

    fun toggleAttachmentsPanel() {
        _chatState.update { it.copy(attachmentsExpanded = !it.attachmentsExpanded) }
    }

    fun hideAttachmentsPanel() {
        val status = _chatState.value.fileTransfer.status
        val transferInProgress = status == FileTransferStatus.Sending ||
            status == FileTransferStatus.Receiving ||
            status == FileTransferStatus.WaitingForReceiver
        if (transferInProgress) {
            _chatState.update { it.copy(attachmentsExpanded = false) }
        } else {
            clearPendingFiles()
        }
    }

    fun startEditingMessage(messageId: String) {
        val message = _chatState.value.messages.firstOrNull { it.messageId == messageId && it.type == MessageType.Outgoing && !it.isDeleted } ?: return
        if (message.attachmentUri != null || message.isVoiceNote || message.isImageAttachment) {
            setError("Редактирование доступно только для текстовых сообщений")
            return
        }
        _chatState.update {
            it.copy(
                editingMessageId = message.messageId,
                editingMessageText = message.text,
                attachmentsExpanded = false
            )
        }
    }

    fun updateEditingMessageInput(value: String) {
        _chatState.update { it.copy(editingMessageText = value) }
    }

    fun cancelEditingMessage() {
        _chatState.update { it.copy(editingMessageId = null, editingMessageText = "") }
    }

    fun confirmEditingMessage() {
        val messageId = _chatState.value.editingMessageId ?: return
        val newText = _chatState.value.editingMessageText.trim()
        if (newText.isBlank()) return
        val target = _chatState.value.messages.firstOrNull { it.messageId == messageId && it.type == MessageType.Outgoing } ?: return
        if (target.isDeleted || target.attachmentUri != null || target.isVoiceNote || target.isImageAttachment) {
            cancelEditingMessage()
            return
        }
        val updated = target.copy(text = newText, isEdited = true)
        replaceMessage(updated)
        rememberThreadPreview(newText, updated.timestamp)
        cancelEditingMessage()
        if (isSessionActive()) {
            scope.launch {
                sendPacket(
                    buildPacket(
                        type = "EDIT_MESSAGE",
                        payload = mapOf(
                            "messageId" to messageId,
                            "newText" to newText
                        )
                    )
                )
            }
        }
        log(LogType.Chat, "Сообщение $messageId изменено")
    }

    fun deleteMessageForMe(messageId: String) {
        removeMessageLocally(messageId)
        if (_chatState.value.editingMessageId == messageId) {
            cancelEditingMessage()
        }
        log(LogType.Chat, "Сообщение $messageId удалено локально")
    }

    fun deleteMessageForEveryone(messageId: String) {
        val target = _chatState.value.messages.firstOrNull { it.messageId == messageId && it.type == MessageType.Outgoing } ?: return
        if (!isSessionActive()) {
            setError("Нет активного соединения. Сообщение можно удалить только у себя")
            return
        }
        val deleted = target.copy(
            text = "Сообщение удалено",
            isDeleted = true,
            isEdited = false,
            deliveryStatus = null
        )
        replaceMessage(deleted)
        if (_chatState.value.editingMessageId == messageId) {
            cancelEditingMessage()
        }
        scope.launch {
            sendPacket(buildPacket(type = "DELETE_MESSAGE", payload = mapOf("messageId" to messageId)))
        }
        log(LogType.Chat, "Сообщение $messageId удалено у всех")
    }


    fun saveAttachmentCopy(messageId: String) {
        val message = _chatState.value.messages.firstOrNull { it.messageId == messageId || it.id == messageId }
        val sourceUri = message?.attachmentUri?.takeIf { it.isNotBlank() } ?: run {
            setError("Вложение не найдено")
            return
        }
        runCatching {
            val input = appContext.contentResolver.openInputStream(Uri.parse(sourceUri))
                ?: File(Uri.parse(sourceUri).path ?: throw IllegalArgumentException("Пустой путь")).inputStream()
            val fileName = (message.attachmentName ?: message.text.ifBlank { "attachment_${System.currentTimeMillis()}" }).replace(Regex("[^A-Za-zА-Яа-я0-9._-]"), "_")
            val targetDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir
            if (!targetDir.exists()) targetDir.mkdirs()
            val target = File(targetDir, "${System.currentTimeMillis()}_$fileName")
            input.use { inp -> target.outputStream().use { out -> inp.copyTo(out) } }
            val system = MessageUi(id = UUID.randomUUID().toString(), text = "Копия сохранена: ${target.name}", timestamp = timeNow(), type = MessageType.System)
            _chatState.update { it.copy(messages = it.messages + system) }
            syncCurrentThreadMessages()
            rememberThreadPreview(system.text, system.timestamp)
        }.onFailure {
            setError("Не удалось сохранить вложение")
            log(LogType.Error, "Сохранение вложения не удалось: ${it.message}")
        }
    }

    fun sendVoiceNoteRecorded(uri: Uri, durationLabel: String) {
        if (_chatState.value.isGroupChat && _chatState.value.groupId != null) {
            sendGroupAttachment(uri, durationLabel)
        } else {
            setPendingFiles(listOf(uri))
            sendSelectedFiles()
        }
    }

    fun sendSelectedFiles() {
        if (_chatState.value.isGroupChat && _chatState.value.groupId != null) {
            val uris = pendingFileUris.toList()
            if (uris.isEmpty()) {
                _chatState.update { it.copy(fileTransfer = it.fileTransfer.copy(status = FileTransferStatus.Error, errorMessage = "Сначала выберите файл")) }
                return
            }
            uris.forEach { sendGroupAttachment(it) }
            clearPendingFiles()
            return
        }
        if (pendingFileUris.isEmpty()) {
            _chatState.update {
                it.copy(
                    fileTransfer = it.fileTransfer.copy(
                        status = FileTransferStatus.Error,
                        errorMessage = "Сначала выберите файл"
                    )
                )
            }
            return
        }
        if (!isSessionActive()) {
            setError("Сначала подключитесь к другому устройству")
            _chatState.update {
                it.copy(
                    fileTransfer = it.fileTransfer.copy(
                        status = FileTransferStatus.Error,
                        errorMessage = "Нет соединения с устройством"
                    )
                )
            }
            return
        }

        val urisToSend = pendingFileUris.toList()

        scope.launch {
            var offeredCount = 0
            var totalBytes = 0L
            urisToSend.forEach { uri ->
                val meta = readUriMeta(uri)
                if (meta.size <= 0L) return@forEach
                val transferId = UUID.randomUUID().toString()
                val chunkSize = FILE_BUFFER_SIZE
                val totalChunks = ((meta.size + chunkSize - 1L) / chunkSize).toInt().coerceAtLeast(1)
                val checksum = calculateFileChecksum(meta)
                outgoingTransfers[transferId] = OutgoingFileTransfer(
                    transferId = transferId,
                    uri = meta.sourceUri ?: uri,
                    fileName = meta.name,
                    fileSize = meta.size,
                    mimeType = meta.mimeType,
                    chunkSize = chunkSize,
                    totalChunks = totalChunks,
                    checksum = checksum,
                    tempBytes = meta.tempBytes,
                    previewUriString = uri.toString()
                )
                val manifest = TransferManifest(
                    transferId = transferId,
                    fileName = meta.name,
                    fileSize = meta.size,
                    mimeType = meta.mimeType,
                    chunkSize = chunkSize,
                    totalChunks = totalChunks,
                    checksum = checksum,
                    senderLabel = localDisplayName(),
                    previewUriString = uri.toString()
                )
                val relayResolution = resolveActiveChatTarget()
                val sent = if (relayResolution?.isRelayed == true) {
                    sendRelayedFileControl(
                        type = "RELAY_FILE_MANIFEST",
                        transferId = transferId,
                        payload = manifest.toPayload() + mapOf(
                            "finalTargetPeerId" to relayResolution.targetPeerId,
                            "routeLabel" to relayResolution.routeLabel,
                            "hopCount" to 1
                        )
                    )
                } else {
                    sendPacket(buildPacket(type = "FILE_MANIFEST", payload = manifest.toPayload()))
                }
                if (sent) {
                    offeredCount += 1
                    totalBytes += meta.size
                    setChatSystemMessage("Подготовлена передача файла ${meta.name}")
                    log(LogType.File, "Отправлен FILE_MANIFEST: ${meta.name}")
                } else {
                    outgoingTransfers.remove(transferId)
                }
            }

            if (offeredCount > 0) {
                _chatState.update {
                    it.copy(
                        attachmentsExpanded = false,
                        fileTransfer = it.fileTransfer.copy(
                            status = FileTransferStatus.WaitingForReceiver,
                            progress = 0f,
                            errorMessage = null,
                            totalBytes = totalBytes,
                            remainingLabel = formatSize(totalBytes),
                            detailText = if (offeredCount == 1) {
                                "Ожидаем подтверждение и место сохранения"
                            } else {
                                "Ожидаем подтверждение по $offeredCount файлам"
                            }
                        )
                    )
                }
            } else {
                _chatState.update {
                    it.copy(
                        fileTransfer = it.fileTransfer.copy(
                            status = FileTransferStatus.Error,
                            errorMessage = "Не удалось отправить запрос на передачу файлов"
                        )
                    )
                }
            }
        }
    }

    fun acceptIncomingFile(destinationUri: Uri? = null) {
        val offer = _chatState.value.incomingFileOffer ?: return
        val pending = incomingTransfers[offer.transferId] ?: return
        val resolvedDestination = destinationUri ?: createDownloadDestinationUri(offer.fileName, offer.mimeType)
        if (resolvedDestination == null) {
            _chatState.update {
                it.copy(fileTransfer = it.fileTransfer.copy(status = FileTransferStatus.Error, errorMessage = "Не удалось подготовить загрузку в папку Загрузки"))
            }
            return
        }
        pending.destinationUri = resolvedDestination
        _chatState.update {
            it.copy(
                incomingFileOffer = null,
                fileTransfer = it.fileTransfer.copy(
                    fileName = offer.fileName,
                    fileSizeLabel = offer.fileSizeLabel,
                    status = FileTransferStatus.Receiving,
                    progress = 0f,
                    errorMessage = null,
                    bytesProcessed = 0L,
                    totalBytes = offer.fileSizeBytes,
                    remainingLabel = formatSize(offer.fileSizeBytes),
                    detailText = "Сохраняем в Загрузки/Ladya. Ожидаем отправителя"
                )
            )
        }
        scope.launch {
            val transfer = incomingTransfers[offer.transferId]
            val sent = if (transfer?.routeLabel != null) {
                sendRelayedFileControl("RELAY_FILE_ACCEPT", offer.transferId)
            } else sendPacket(
                buildPacket(
                    type = "FILE_ACCEPT",
                    payload = mapOf("transferId" to offer.transferId)
                )
            )
            if (!sent) {
                incomingTransfers.remove(offer.transferId)
                _chatState.update {
                    it.copy(
                        fileTransfer = it.fileTransfer.copy(
                            status = FileTransferStatus.Error,
                            errorMessage = "Не удалось подтвердить приём файла"
                        )
                    )
                }
            }
        }
    }

    fun declineIncomingFile() {
        val offer = _chatState.value.incomingFileOffer ?: return
        incomingTransfers.remove(offer.transferId)
        _chatState.update {
            it.copy(
                incomingFileOffer = null,
                fileTransfer = it.fileTransfer.copy(
                    detailText = "Входящий файл отклонён",
                    errorMessage = null,
                    status = if (it.fileTransfer.fileName != null) it.fileTransfer.status else FileTransferStatus.Idle
                )
            )
        }
        scope.launch {
            val transfer = incomingTransfers[offer.transferId]
            if (transfer?.routeLabel != null) sendRelayedFileControl("RELAY_FILE_DECLINE", offer.transferId)
            else sendPacket(buildPacket(type = "FILE_DECLINE", payload = mapOf("transferId" to offer.transferId)))
        }
        setChatSystemMessage("Приём файла ${offer.fileName} отклонён")
    }

    fun selectLogFilter(type: LogType) {
        _logsState.update { it.copy(selectedFilter = type) }
    }

    fun clearLogs() {
        _logsState.update { it.copy(logs = emptyList()) }
        log(LogType.System, "Логи очищены")
    }

    fun prepareEmptyAddContact() {
        _addContactState.value = AddContactUiState(port = DEFAULT_PORT.toString())
    }

    fun prepareAddContactFromPeer(peer: DiscoveredPeerUi) {
        _addContactState.value = AddContactUiState(
            displayName = peer.title,
            peerId = peer.peerId,
            host = peer.host,
            port = peer.port.toString()
        )
    }

    fun updateAddContactDisplayName(value: String) {
        _addContactState.update { it.copy(displayName = value, errorMessage = null) }
    }

    fun updateAddContactPeerId(value: String) {
        _addContactState.update { it.copy(peerId = value, errorMessage = null) }
    }

    fun updateAddContactHost(value: String) {
        _addContactState.update { it.copy(host = value, errorMessage = null) }
    }

    fun updateAddContactPort(value: String) {
        _addContactState.update { it.copy(port = value.filter(Char::isDigit), errorMessage = null) }
    }

    fun saveAddContact(): Boolean {
        val state = _addContactState.value
        val displayName = state.displayName.trim()
        val peerId = state.peerId.trim()
        val host = state.host.trim()
        val port = state.port.toIntOrNull() ?: DEFAULT_PORT
        if (displayName.isBlank()) {
            _addContactState.update { it.copy(errorMessage = "Введите имя контакта") }
            return false
        }
        if (peerId.isBlank() && host.isBlank()) {
            _addContactState.update { it.copy(errorMessage = "Укажите peer ID или IP-адрес") }
            return false
        }
        val current = loadContacts(appContext).toMutableList()
        val existingIndex = current.indexOfFirst { contact ->
            contact.peerId.isNotBlank() && contact.peerId == peerId || (host.isNotBlank() && contact.host == host)
        }
        val sourcePeer = discoveredPeers.values.firstOrNull { it.peerId == peerId || (host.isNotBlank() && it.host == host) }
        val existing = current.getOrNull(existingIndex)
        val newContact = ContactUi(
            id = existing?.id ?: UUID.randomUUID().toString(),
            peerId = peerId,
            displayName = displayName,
            host = host,
            port = port,
            isOnline = discoveredPeers.values.any { peer -> peer.peerId == peerId || peer.host == host },
            publicNick = existing?.publicNick ?: sourcePeer?.subtitle?.substringAfter("@", "")?.substringBefore(" •")?.takeIf { it.isNotBlank() },
            avatarEmoji = existing?.avatarEmoji ?: sourcePeer?.avatarEmoji,
            trustStatus = existing?.trustStatus ?: sourcePeer?.trustStatus ?: TrustStatus.Unverified,
            fingerprint = existing?.fingerprint ?: sourcePeer?.fingerprint,
            trustWarning = existing?.trustWarning ?: sourcePeer?.trustWarning
        )
        if (existingIndex >= 0) current[existingIndex] = newContact else current.add(newContact)
        saveContacts(current)
        _addContactState.value = AddContactUiState(port = DEFAULT_PORT.toString())
        log(LogType.System, "Контакт сохранён: $displayName")
        return true
    }

    fun deleteContact(contactId: String) {
        val updated = loadContacts(appContext).filterNot { it.id == contactId }
        saveContacts(updated)
        log(LogType.System, "Контакт удалён")
    }

    fun updatePublicNick(value: String) {
        _settingsState.update { it.copy(publicNick = value) }
    }

    fun updateLocalDeviceName(value: String) {
        _settingsState.update { it.copy(localDeviceName = value) }
    }

    fun updateAvatarEmoji(value: String) {
        _settingsState.update { it.copy(avatarEmoji = value.ifBlank { DEFAULT_AVATAR_EMOJI }) }
    }

    fun updateMasterSoundsEnabled(enabled: Boolean) {
        _settingsState.update { it.copy(soundSettings = it.soundSettings.copy(masterEnabled = enabled)) }
    }

    fun updateIncomingCallSoundEnabled(enabled: Boolean) {
        updateSoundToggle { copy(incomingCallEnabled = enabled) }
    }

    fun updateOutgoingCallSoundEnabled(enabled: Boolean) {
        updateSoundToggle { copy(outgoingCallEnabled = enabled) }
    }

    fun updateCallConnectedSoundEnabled(enabled: Boolean) {
        updateSoundToggle { copy(callConnectedEnabled = enabled) }
    }

    fun updateCallEndedSoundEnabled(enabled: Boolean) {
        updateSoundToggle { copy(callEndedEnabled = enabled) }
    }

    fun updateMessageIncomingSoundEnabled(enabled: Boolean) {
        updateSoundToggle { copy(messageIncomingEnabled = enabled) }
    }

    fun updateMessageOutgoingSoundEnabled(enabled: Boolean) {
        updateSoundToggle { copy(messageOutgoingEnabled = enabled) }
    }

    fun updateMessageErrorSoundEnabled(enabled: Boolean) {
        updateSoundToggle { copy(messageErrorEnabled = enabled) }
    }

    fun updateFileReceivedSoundEnabled(enabled: Boolean) {
        updateSoundToggle { copy(fileReceivedEnabled = enabled) }
    }

    fun updateFileSentSoundEnabled(enabled: Boolean) {
        updateSoundToggle { copy(fileSentEnabled = enabled) }
    }

    fun updateDeviceFoundSoundEnabled(enabled: Boolean) {
        updateSoundToggle { copy(deviceFoundEnabled = enabled) }
    }

    fun updateDeviceVerifiedSoundEnabled(enabled: Boolean) {
        updateSoundToggle { copy(deviceVerifiedEnabled = enabled) }
    }

    fun updateDeviceWarningSoundEnabled(enabled: Boolean) {
        updateSoundToggle { copy(deviceWarningEnabled = enabled) }
    }

    fun updateNotificationSoftSoundEnabled(enabled: Boolean) {
        updateSoundToggle { copy(notificationSoftEnabled = enabled) }
    }

    private fun updateSoundToggle(transform: SoundSettingsUi.() -> SoundSettingsUi) {
        _settingsState.update { current ->
            current.copy(soundSettings = current.soundSettings.transform())
        }
    }

    fun playPreviewSound(effect: SoundEffect) {
        AppSoundManager.play(appContext, effect)
    }

    fun saveSettings() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val publicNick = _settingsState.value.publicNick.trim()
        val deviceName = _settingsState.value.localDeviceName.trim().ifBlank {
            defaultDeviceName(_connectionState.value.localPeerId)
        }
        val avatarEmoji = _settingsState.value.avatarEmoji.ifBlank { DEFAULT_AVATAR_EMOJI }
        prefs.edit()
            .putString(PREF_PUBLIC_NICK, publicNick)
            .putString(PREF_DEVICE_NAME, deviceName)
            .putString(PREF_AVATAR_EMOJI, avatarEmoji)
            .apply()
        persistSoundSettings(_settingsState.value.soundSettings)

        _settingsState.update { it.copy(publicNick = publicNick, localDeviceName = deviceName, avatarEmoji = avatarEmoji, localFingerprint = DeviceIdentityManager.getOrCreateIdentity(appContext).fingerprint) }
        _connectionState.update { it.copy(publicNick = publicNick, localDeviceName = deviceName, avatarEmoji = avatarEmoji, localFingerprint = DeviceIdentityManager.getOrCreateIdentity(appContext).fingerprint) }
        if (_profileState.value.isLocalProfile) selectLocalProfile()
        restartNsdRegistration()
        sendProfileUpdateIfConnected()
        log(LogType.System, "Настройки сохранены")
    }

    fun selectCurrentChatProfile() {
        val key = currentThreadKey
        val thread = key?.let { threadSnapshots[it] }
        if (thread != null) {
            val contact = loadContacts(appContext).firstOrNull { it.keyId == thread.key || it.peerId == thread.peerId || (!thread.host.isNullOrBlank() && it.host == thread.host) }
            if (contact != null) {
                selectContactProfile(contact)
                return
            }
            val peer = discoveredPeers[thread.peerId] ?: discoveredPeers[thread.host]
            if (peer != null) {
                selectPeerProfile(peer)
                return
            }
            _profileState.value = ProfileUiState(
                isLocalProfile = false,
                title = thread.title,
                peerId = thread.peerId.orEmpty(),
                host = thread.host.orEmpty(),
                port = thread.port,
                canWrite = true,
                canAddToContacts = thread.peerId != null,
                trustStatus = remoteTrustStatus,
                fingerprint = remoteFingerprint,
                shortAuthString = remoteShortAuthString,
                publicKeyBase64 = remotePublicKey,
                keyId = remotePublicKey?.let(DeviceIdentityManager::fullKeyIdForPublicKey),
                avatarEmoji = thread.avatarEmoji,
                canVerifyPeer = remoteTrustStatus != TrustStatus.Verified && remoteTrustStatus != TrustStatus.Blocked,
                canBlockPeer = remoteTrustStatus != TrustStatus.Blocked,
                canUnblockPeer = remoteTrustStatus == TrustStatus.Blocked
            )
            return
        }
        selectLocalProfile()
    }

    fun deleteThreadForMe(thread: ChatThreadUi) {
        threadHistories.remove(thread.key)
        threadSnapshots.remove(thread.key)
        databaseHelper.deleteThread(thread.key)
        if (currentThreadKey == thread.key) {
            _chatState.update { it.copy(messages = emptyList(), incomingFileOffer = null, fileTransfer = FileTransferUi(), editingMessageId = null, editingMessageText = "") }
        }
        persistThreadSnapshots()
        publishPeers()
        log(LogType.Chat, "Чат ${thread.title} удалён только на этом устройстве")
    }

    fun deleteThreadForEveryone(thread: ChatThreadUi) {
        val canNotifyRemote = isSessionActive() && (thread.key == currentThreadKey || (!thread.peerId.isNullOrBlank() && thread.peerId == remotePeerId))
        if (canNotifyRemote) {
            sendPacket(buildPacket(type = "CLEAR_HISTORY"))
        }
        deleteThreadForMe(thread)
        if (!canNotifyRemote) {
            setError("Нет активного соединения с этим чатом. Переписка удалена только у Вас")
        }
    }

    fun selectLocalProfile() {
        _profileState.value = ProfileUiState(
            isLocalProfile = true,
            title = _settingsState.value.localDeviceName,
            publicNick = _settingsState.value.publicNick,
            peerId = _connectionState.value.localPeerId,
            host = _connectionState.value.localIp,
            port = _connectionState.value.localPort,
            isSavedContact = false,
            canWrite = false,
            canAddToContacts = false,
            avatarEmoji = _settingsState.value.avatarEmoji,
            trustStatus = TrustStatus.Verified,
            fingerprint = _settingsState.value.localFingerprint,
            publicKeyBase64 = DeviceIdentityManager.getOrCreateIdentity(appContext).publicKeyBase64,
            keyId = DeviceIdentityManager.getOrCreateIdentity(appContext).fullKeyId,
            canVerifyPeer = false,
            canBlockPeer = false,
            canUnblockPeer = false
        )
    }

    fun selectPeerProfile(peer: DiscoveredPeerUi) {
        _profileState.value = ProfileUiState(
            isLocalProfile = false,
            title = peer.title,
            publicNick = peer.subtitle.substringAfter("@", "").substringBefore(" •").takeIf { peer.subtitle.startsWith("@") } ?: "",
            peerId = peer.peerId,
            host = peer.host,
            port = peer.port,
            isSavedContact = peer.isSavedContact,
            canWrite = true,
            canAddToContacts = !peer.isSavedContact,
            sourcePeer = peer,
            avatarEmoji = peer.avatarEmoji,
            trustStatus = peer.trustStatus,
            fingerprint = peer.fingerprint,
            securityWarning = peer.trustWarning,
            shortAuthString = peer.shortAuthString,
            publicKeyBase64 = peer.publicKeyBase64,
            keyId = peer.keyId,
            canVerifyPeer = peer.trustStatus != TrustStatus.Verified && peer.trustStatus != TrustStatus.Blocked,
            canBlockPeer = peer.trustStatus != TrustStatus.Blocked,
            canUnblockPeer = peer.trustStatus == TrustStatus.Blocked
        )
    }

    fun selectContactProfile(contact: ContactUi) {
        _profileState.value = ProfileUiState(
            isLocalProfile = false,
            title = contact.displayName,
            publicNick = contact.publicNick.orEmpty(),
            peerId = contact.peerId,
            host = contact.host,
            port = contact.port,
            isSavedContact = true,
            canWrite = contact.host.isNotBlank(),
            canAddToContacts = false,
            sourceContact = contact,
            avatarEmoji = contact.avatarEmoji,
            trustStatus = contact.trustStatus,
            fingerprint = contact.fingerprint,
            securityWarning = contact.trustWarning,
            shortAuthString = DeviceIdentityManager.buildShortAuthString(
                DeviceIdentityManager.getOrCreateIdentity(appContext).publicKeyBase64,
                contact.publicKeyBase64 ?: remotePublicKey
            ),
            publicKeyBase64 = contact.publicKeyBase64,
            keyId = contact.keyId,
            canVerifyPeer = contact.trustStatus != TrustStatus.Verified && contact.trustStatus != TrustStatus.Blocked,
            canBlockPeer = contact.trustStatus != TrustStatus.Blocked,
            canUnblockPeer = contact.trustStatus == TrustStatus.Blocked
        )
    }

    fun openProfileWrite() {
        val profile = _profileState.value
        when {
            profile.sourceContact != null -> connectToContact(profile.sourceContact)
            profile.sourcePeer != null -> connectToPeer(profile.sourcePeer)
        }
    }

    fun verifyProfilePeer() {
        val profile = _profileState.value
        if (profile.isLocalProfile || profile.peerId.isBlank()) return
        val contacts = loadContacts(appContext).toMutableList()
        val index = contacts.indexOfFirst {
            (!profile.keyId.isNullOrBlank() && it.keyId == profile.keyId) ||
            it.peerId == profile.peerId ||
            (profile.host.isNotBlank() && it.host == profile.host)
        }
        val existing = contacts.getOrNull(index)
        val contact = ContactUi(
            id = existing?.id ?: UUID.randomUUID().toString(),
            peerId = profile.peerId,
            displayName = profile.title.ifBlank { existing?.displayName ?: profile.peerId },
            host = profile.host,
            port = profile.port,
            isOnline = existing?.isOnline ?: false,
            publicNick = profile.publicNick.takeIf { it.isNotBlank() } ?: existing?.publicNick,
            avatarEmoji = profile.avatarEmoji ?: existing?.avatarEmoji,
            trustStatus = TrustStatus.Verified,
            fingerprint = profile.fingerprint ?: existing?.fingerprint,
            trustWarning = null,
            publicKeyBase64 = profile.publicKeyBase64 ?: existing?.publicKeyBase64,
            keyId = profile.keyId ?: existing?.keyId
        )
        if (index >= 0) contacts[index] = contact else contacts.add(contact)
        saveContacts(contacts)
        remoteTrustStatus = TrustStatus.Verified
        remoteSecurityWarning = null
        remoteFingerprint = profile.fingerprint ?: remoteFingerprint
        remoteShortAuthString = DeviceIdentityManager.buildShortAuthString(
            DeviceIdentityManager.getOrCreateIdentity(appContext).publicKeyBase64,
            remotePublicKey
        )
        refreshCurrentProfileSecurity()
        updateConnectionSecurityState()
        log(LogType.System, "Узел подтверждён: ${profile.title}")
    }

    fun blockProfilePeer() {
        val profile = _profileState.value
        if (profile.isLocalProfile || profile.peerId.isBlank()) return
        upsertProfileContactTrust(TrustStatus.Blocked, profile.securityWarning ?: "Узел заблокирован пользователем")
        remoteTrustStatus = TrustStatus.Blocked
        remoteSecurityWarning = profile.securityWarning ?: "Узел заблокирован пользователем"
        refreshCurrentProfileSecurity()
        updateConnectionSecurityState()
        log(LogType.System, "Узел заблокирован: ${profile.title}")
    }

    fun unblockProfilePeer() {
        val profile = _profileState.value
        if (profile.isLocalProfile || profile.peerId.isBlank()) return
        upsertProfileContactTrust(TrustStatus.Unverified, null)
        remoteTrustStatus = TrustStatus.Unverified
        remoteSecurityWarning = null
        refreshCurrentProfileSecurity()
        updateConnectionSecurityState()
        log(LogType.System, "Блокировка снята: ${profile.title}")
    }

    private fun upsertProfileContactTrust(trustStatus: TrustStatus, warning: String?) {
        val profile = _profileState.value
        val contacts = loadContacts(appContext).toMutableList()
        val index = contacts.indexOfFirst {
            (!profile.keyId.isNullOrBlank() && it.keyId == profile.keyId) ||
            it.peerId == profile.peerId ||
            (profile.host.isNotBlank() && it.host == profile.host)
        }
        val existing = contacts.getOrNull(index)
        val updated = ContactUi(
            id = existing?.id ?: UUID.randomUUID().toString(),
            peerId = profile.peerId,
            displayName = profile.title.ifBlank { existing?.displayName ?: profile.peerId },
            host = profile.host,
            port = profile.port,
            isOnline = existing?.isOnline ?: false,
            publicNick = profile.publicNick.takeIf { it.isNotBlank() } ?: existing?.publicNick,
            avatarEmoji = profile.avatarEmoji ?: existing?.avatarEmoji,
            trustStatus = trustStatus,
            fingerprint = profile.fingerprint ?: existing?.fingerprint,
            trustWarning = warning,
            publicKeyBase64 = profile.publicKeyBase64 ?: existing?.publicKeyBase64,
            keyId = profile.keyId ?: existing?.keyId
        )
        if (index >= 0) contacts[index] = updated else contacts.add(updated)
        saveContacts(contacts)
    }

    private fun refreshCurrentProfileSecurity() {
        val profile = _profileState.value
        if (profile.isLocalProfile) {
            selectLocalProfile()
            return
        }
        profile.sourceContact?.let {
            selectContactProfile(loadContacts(appContext).firstOrNull { contact -> contact.id == it.id } ?: it)
            return
        }
        profile.sourcePeer?.let {
            selectPeerProfile(discoveredPeers[it.peerId] ?: it)
            return
        }
        _profileState.update {
            it.copy(
                trustStatus = remoteTrustStatus,
                fingerprint = remoteFingerprint,
                securityWarning = remoteSecurityWarning,
                shortAuthString = remoteShortAuthString,
                publicKeyBase64 = remotePublicKey,
                keyId = remotePublicKey?.let(DeviceIdentityManager::fullKeyIdForPublicKey),
                canVerifyPeer = remoteTrustStatus != TrustStatus.Verified && remoteTrustStatus != TrustStatus.Blocked,
                canBlockPeer = remoteTrustStatus != TrustStatus.Blocked,
                canUnblockPeer = remoteTrustStatus == TrustStatus.Blocked
            )
        }
    }

    fun openProfileAddToContacts() {
        val peer = _profileState.value.sourcePeer ?: return
        prepareAddContactFromPeer(peer)
    }

    private fun sendProfileUpdateIfConnected() {
        if (!isSessionActive()) return
        scope.launch {
            sendPacket(
                buildPacket(
                    type = "PROFILE_UPDATE",
                    payload = signedIdentityPayload("PROFILE_UPDATE")
                ),
                disconnectOnFailure = false
            )
        }
    }

    fun openThread(thread: ChatThreadUi) {
        if (!thread.key.startsWith("group:")) {
            remotePeerId = thread.peerId ?: remotePeerId
        }
        switchToThread(
            threadKey = thread.key,
            title = thread.title,
            online = thread.isOnline,
            typingLabel = if (thread.isTyping) "печатает…" else null,
            connectionLabel = if (thread.isOnline) "Онлайн" else if (isSessionActive()) _chatState.value.connectionLabel else "Оффлайн"
        )
        rememberThreadPreview(
            preview = thread.preview.ifBlank { _chatState.value.messages.lastOrNull()?.text ?: "Пустой чат" },
            timestamp = thread.timestamp,
            explicitKey = thread.key,
            explicitTitle = thread.title,
            explicitEpoch = thread.lastMessageEpoch
        )
    }

    private fun connectTo(host: String, port: Int, source: String) {
        if (isSessionActive() && currentSocket?.inetAddress?.hostAddress == host) {
            _connectionState.update {
                it.copy(
                    status = ConnectionStatus.Connected,
                    isLoading = false,
                    errorMessage = null,
                    canOpenChat = false,
                    connectedPeerTitle = resolveConnectedPeerTitle(host),
                    connectionLabel = "Подключено"
                )
            }
            return
        }
        scope.launch {
            transitionSession(SessionState.Connecting, "Исходящее подключение к $host:$port")
            _connectionState.update {
                it.copy(
                    status = ConnectionStatus.Connecting,
                    isLoading = true,
                    errorMessage = null,
                    remoteIpInput = host,
                    remotePortInput = port.toString()
                )
            }
            var pendingSocket: Socket? = null
            runCatching {
                pendingSocket = Socket().apply {
                    keepAlive = true
                    tcpNoDelay = true
                    soTimeout = 0
                }
                pendingSocket?.connect(InetSocketAddress(host, port), SOCKET_TIMEOUT_MS)
                attachSocket(pendingSocket ?: error("Не удалось создать сокет"), initiatedByUs = true)
                pendingSocket = null
                log(LogType.Network, "Подключение выполнено к $host:$port ($source)")
            }.onFailure { throwable ->
                runCatching { pendingSocket?.close() }
                setError(throwable.message?.let { "Не удалось подключиться: $it" }
                    ?: "Не удалось подключиться к удалённому устройству")
                transitionSession(SessionState.Error, "Ошибка подключения")
                log(LogType.Error, throwable.message ?: "Ошибка TCP-подключения")
            }
        }
    }

    private fun startServer() {
        if (listenerJob?.isActive == true) return
        listenerJob = scope.launch {
            runCatching {
                serverSocket?.close()
                serverSocket = ServerSocket(DEFAULT_PORT)
                transitionSession(if (isSessionActive()) SessionState.Active else SessionState.Listening, "Listener готов")
                _connectionState.update {
                    it.copy(
                        localPort = DEFAULT_PORT,
                        localIp = findLocalIpAddress() ?: it.localIp,
                        status = if (isSessionActive()) ConnectionStatus.Connected else ConnectionStatus.Listening,
                        isLoading = false,
                        connectionLabel = if (isSessionActive()) "Онлайн" else "Ожидаем входящее подключение"
                    )
                }
                log(LogType.Network, "Listener запущен на порту $DEFAULT_PORT")
                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    socket.keepAlive = true
                    socket.tcpNoDelay = true
                    socket.soTimeout = 0
                    if (canUseControlChannel()) {
                        log(LogType.Network, "Отклонено входящее соединение: уже есть активный чат")
                        runCatching { socket.close() }
                        continue
                    }
                    log(LogType.Network, "Входящее TCP-подключение от ${socket.inetAddress.hostAddress}")
                    attachSocket(socket, initiatedByUs = false)
                }
            }.onFailure { throwable ->
                setError(throwable.message ?: "Не удалось запустить listener")
                log(LogType.Error, throwable.message ?: "Ошибка listener")
            }
        }
    }

    private fun startRelayServer() {
        if (relayListenerJob?.isActive == true) return
        relayListenerJob = scope.launch {
            runCatching {
                relayServerSocket?.close()
                relayServerSocket = ServerSocket(RELAY_PORT)
                log(LogType.Network, "Relay listener запущен на порту $RELAY_PORT")
                while (isActive) {
                    val socket = relayServerSocket?.accept() ?: break
                    launch { receiveRelayPacket(socket) }
                }
            }.onFailure { throwable ->
                log(LogType.Error, throwable.message ?: "Не удалось запустить relay listener")
            }
        }
    }

    private fun startFileServer() {
        if (fileListenerJob?.isActive == true) return
        fileListenerJob = scope.launch {
            runCatching {
                fileServerSocket?.close()
                fileServerSocket = ServerSocket(FILE_TRANSFER_PORT)
                log(LogType.File, "Файловый listener запущен на порту $FILE_TRANSFER_PORT")
                while (isActive) {
                    val socket = fileServerSocket?.accept() ?: break
                    launch { receiveIncomingFile(socket) }
                }
            }.onFailure { throwable ->
                log(LogType.Error, throwable.message ?: "Не удалось запустить файловый listener")
            }
        }
    }

    private fun startDiscovery() {
        if (nsdManager == null) {
            nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        }
        if (discoveryListener != null) return
        registerOwnService()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                log(LogType.Network, "Поиск устройств запущен")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                resolveService(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val lostPeerId = readPeerId(serviceInfo)
                if (lostPeerId != null) {
                    peerLastSeenAt.remove(lostPeerId)
                    discoveredPeers.remove(lostPeerId)
                }
                publishPeers()
                log(LogType.Network, "Устройство недоступно: ${serviceInfo.serviceName}")
                if (!lostPeerId.isNullOrBlank() && lostPeerId == remotePeerId) {
                    scheduleRecoveryReconnect("Активный узел исчез из discovery")
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                log(LogType.Network, "Поиск устройств остановлен")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                log(LogType.Error, "Не удалось запустить поиск устройств: $errorCode")
                safeStopDiscovery(this)
                discoveryListener = null
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                log(LogType.Error, "Ошибка остановки поиска устройств: $errorCode")
                safeStopDiscovery(this)
                discoveryListener = null
            }
        }

        runCatching {
            nsdManager?.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            log(LogType.Error, it.message ?: "Не удалось начать discovery")
            discoveryListener = null
        }
    }

    private fun registerOwnService() {
        if (registrationListener != null) return
        val peerId = _connectionState.value.localPeerId

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = createServiceName(_settingsState.value.localDeviceName, peerId)
            serviceType = SERVICE_TYPE
            port = DEFAULT_PORT

            setAttribute("pid", peerId)
            setAttribute("dev", _settingsState.value.localDeviceName)
            setAttribute("nik", _settingsState.value.publicNick)
            setAttribute("emo", _settingsState.value.avatarEmoji)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                log(LogType.Error, "Ошибка публикации сервиса: $errorCode")
                registrationListener = null
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                log(LogType.Error, "Ошибка снятия публикации сервиса: $errorCode")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                log(LogType.Network, "Сервис опубликован: ${serviceInfo.serviceName}")
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                log(LogType.Network, "Сервис снят с публикации")
            }
        }

        runCatching {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        }.onFailure {
            log(LogType.Error, it.message ?: "Не удалось зарегистрировать NSD-сервис")
            registrationListener = null
        }
    }

    private fun restartNsdRegistration() {
        if (nsdManager == null) return
        val listener = registrationListener
        if (listener != null) {
            runCatching { nsdManager?.unregisterService(listener) }
            registrationListener = null
        }
        registerOwnService()
    }

    @Suppress("DEPRECATION")
    private fun resolveService(serviceInfo: NsdServiceInfo) {
        runCatching {
            nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    log(LogType.Error, "Resolve failed: $errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    val host = serviceInfo.host?.hostAddress ?: return
                    if (host == _connectionState.value.localIp && serviceInfo.port == DEFAULT_PORT) return

                    val peerId = readPeerId(serviceInfo) ?: parsePeerIdFromServiceName(serviceInfo.serviceName)
                    if (peerId == _connectionState.value.localPeerId) return

                    val current = discoveredPeers[peerId]
                    val deviceName = readAttribute(serviceInfo, "dev").ifBlank { readAttribute(serviceInfo, "deviceName") }.ifBlank {
                        parseDeviceNameFromServiceName(serviceInfo.serviceName)
                    }
                    val publicNick = readAttribute(serviceInfo, "nik").ifBlank { readAttribute(serviceInfo, "publicNick") }
                    val avatarEmoji = readAttribute(serviceInfo, "emo").ifBlank { current?.avatarEmoji ?: "" }
                    val title = resolvePeerTitle(peerId = peerId, deviceName = deviceName, publicNick = publicNick)

                    val knownContact = findContactByPeerId(peerId) ?: loadContacts(appContext).firstOrNull { it.host == host }
                    val peer = DiscoveredPeerUi(
                        id = peerId,
                        peerId = peerId,
                        title = title,
                        host = host,
                        port = serviceInfo.port,
                        subtitle = buildPeerSubtitle(publicNick, deviceName, host),
                        isSavedContact = knownContact != null,
                        avatarEmoji = avatarEmoji.ifBlank { null },
                        trustStatus = knownContact?.trustStatus ?: current?.trustStatus ?: TrustStatus.Unverified,
                        fingerprint = knownContact?.fingerprint ?: current?.fingerprint,
                        trustWarning = knownContact?.trustWarning ?: current?.trustWarning,
                        shortAuthString = current?.shortAuthString ?: DeviceIdentityManager.buildShortAuthString(
                            DeviceIdentityManager.getOrCreateIdentity(appContext).publicKeyBase64,
                            knownContact?.publicKeyBase64 ?: current?.publicKeyBase64
                        ),
                        publicKeyBase64 = knownContact?.publicKeyBase64 ?: current?.publicKeyBase64,
                        keyId = knownContact?.keyId ?: current?.keyId
                    )
                    val hadPeer = discoveredPeers.containsKey(peer.peerId)
                    discoveredPeers[peer.peerId] = peer
                    if (!hadPeer) playSound(SoundEffect.DeviceFound)
                    peerLastSeenAt[peer.peerId] = System.currentTimeMillis()
                    publishPeers()
                    maybeAutoReconnect(peer)
                    log(LogType.Network, "Найден узел ${peer.title} ($host:${peer.port})")
                }
            })
        }.onFailure {
            log(LogType.Error, it.message ?: "Не удалось разрешить адрес узла")
        }
    }

    private fun attachSocket(socket: Socket, initiatedByUs: Boolean) {
        if (currentSocket === socket) return
        val previousSocket: Socket?
        val previousWriter: BufferedWriter?
        synchronized(connectionLifecycleLock) {
            previousSocket = currentSocket
            previousWriter = currentWriter
            runCatching { readerJob?.cancel() }
            runCatching { pingJob?.cancel() }
            readerJob = null
            pingJob = null
            clearOutgoingPacketQueue()
            connectionClosing = false
            activeSocketSessionId = UUID.randomUUID().toString()
            socket.keepAlive = true
            socket.tcpNoDelay = true
            socket.soTimeout = 0
            currentSocket = socket
            currentWriter = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        }
        recoveryReconnectJob?.cancel()
        recoveryReconnectJob = null
        runCatching { previousWriter?.close() }
        runCatching { previousSocket?.close() }
        val resolvedTitle = resolveConnectedPeerTitle(socket.inetAddress.hostAddress) ?: "Комната"
        val threadKey = resolveThreadKey(remotePeerId, socket.inetAddress.hostAddress, resolvedTitle)
        transitionSession(SessionState.Handshaking, "Открыт сокет, ожидаем HELLO/HELLO_ACK")
        switchToThread(
            threadKey = threadKey,
            title = resolvedTitle,
            online = false,
            typingLabel = null,
            connectionLabel = "Рукопожатие"
        )
        _connectionState.update {
            it.copy(
                status = ConnectionStatus.Connected,
                isLoading = false,
                errorMessage = null,
                canOpenChat = false,
                remoteIpInput = socket.inetAddress.hostAddress ?: it.remoteIpInput,
                remotePortInput = DEFAULT_PORT.toString(),
                localIp = findLocalIpAddress() ?: it.localIp,
                connectedPeerTitle = resolvedTitle,
                connectionLabel = "Рукопожатие"
            )
        }
        _chatState.update {
            it.copy(
                isConnected = false,
                isPeerOnline = false,
                connectionLabel = "Рукопожатие",
                typingLabel = null,
                roomTitle = resolvedTitle,
                messages = it.messages + MessageUi(
                    id = UUID.randomUUID().toString(),
                    text = "Соединение установлено с ${socket.inetAddress.hostAddress}:${socket.port}",
                    timestamp = timeNow(),
                    type = MessageType.System
                )
            )
        }
        rememberThreadPreview("Соединение установлено", timeNow())

        if (initiatedByUs) {
            sendPacket(buildPacket(type = "HELLO", payload = signedIdentityPayload("HELLO")))
        }

        val sessionId = activeSocketSessionId
        readerJob = scope.launch {
            runCatching {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                while (isActive) {
                    val raw = reader.readLine() ?: break
                    handleIncomingPacket(raw)
                }
            }.onFailure { throwable ->
                val details = formatThrowable(throwable)
                if (connectionClosing || activeSocketSessionId != sessionId) {
                    log(LogType.Network, "Чтение сокета завершено: $details")
                } else {
                    log(LogType.Error, "Ошибка чтения из сокета: $details")
                }
            }
            if (currentSocket == socket && !connectionClosing && activeSocketSessionId == sessionId) {
                onConnectionLost("Соединение закрыто")
            }
        }

        pingJob = scope.launch {
            delay(5000)
            while (isActive && currentSocket == socket && activeSocketSessionId == sessionId && !connectionClosing) {
                if (sessionState != SessionState.Active) {
                    delay(1000)
                    continue
                }
                val sent = sendPacket(buildPacket(type = "PING"), disconnectOnFailure = false)
                if (!sent && isSocketConnected()) {
                    log(LogType.Network, "PING не отправлен, ждём следующую попытку")
                }
                delay(10_000)
            }
        }
    }


    private data class IncomingIdentityAssessment(
        val trustStatus: TrustStatus,
        val fingerprint: String?,
        val warning: String?,
        val shortAuthString: String?,
        val publicKeyBase64: String? = null,
        val keyId: String? = null
    )

    private fun signedIdentityPayload(packetType: String, echoHelloNonce: String? = null): Map<String, Any?> {
        val identity = DeviceIdentityManager.getOrCreateIdentity(appContext)
        return when (packetType) {
            "PROFILE_UPDATE" -> {
                val issuedAt = System.currentTimeMillis()
                val payloadToSign = DeviceIdentityManager.buildProfileSignaturePayload(
                    type = packetType,
                    peerId = _connectionState.value.localPeerId,
                    publicNick = _settingsState.value.publicNick,
                    avatarEmoji = _settingsState.value.avatarEmoji,
                    publicKeyBase64 = identity.publicKeyBase64,
                    issuedAt = issuedAt
                )
                val signature = DeviceIdentityManager.signPayload(identity.privateKeyBase64, payloadToSign)
                mapOf(
                    "publicNick" to _settingsState.value.publicNick,
                    "avatarEmoji" to _settingsState.value.avatarEmoji,
                    "publicKey" to identity.publicKeyBase64,
                    "issuedAt" to issuedAt,
                    "identitySignature" to signature
                )
            }
            "HELLO" -> {
                val helloNonce = UUID.randomUUID().toString()
                val payloadToSign = DeviceIdentityManager.buildHelloPayloadToSign(
                    protocolVersion = ProtocolConstants.PROTOCOL_VERSION,
                    senderPeerId = _connectionState.value.localPeerId,
                    publicKeyBase64 = identity.publicKeyBase64,
                    helloNonce = helloNonce
                )
                val signature = DeviceIdentityManager.signPayload(identity.privateKeyBase64, payloadToSign)
                mapOf(
                    "publicKey" to identity.publicKeyBase64,
                    "helloNonce" to helloNonce,
                    "identitySignature" to signature
                )
            }
            "HELLO_ACK" -> {
                val ackNonce = UUID.randomUUID().toString()
                val payloadToSign = DeviceIdentityManager.buildHelloAckPayloadToSign(
                    protocolVersion = ProtocolConstants.PROTOCOL_VERSION,
                    senderPeerId = _connectionState.value.localPeerId,
                    publicKeyBase64 = identity.publicKeyBase64,
                    ackNonce = ackNonce,
                    echoHelloNonce = echoHelloNonce.orEmpty()
                )
                val signature = DeviceIdentityManager.signPayload(identity.privateKeyBase64, payloadToSign)
                buildMap<String, Any?> {
                    put("publicKey", identity.publicKeyBase64)
                    put("ackNonce", ackNonce)
                    put("identitySignature", signature)
                    echoHelloNonce?.let { put("echoHelloNonce", it) }
                }
            }
            "HELLO_CONFIRM" -> {
                val payloadToSign = DeviceIdentityManager.buildHelloConfirmPayloadToSign(
                    protocolVersion = ProtocolConstants.PROTOCOL_VERSION,
                    senderPeerId = _connectionState.value.localPeerId,
                    publicKeyBase64 = identity.publicKeyBase64,
                    echoAckNonce = echoHelloNonce.orEmpty()
                )
                val signature = DeviceIdentityManager.signPayload(identity.privateKeyBase64, payloadToSign)
                buildMap<String, Any?> {
                    put("publicKey", identity.publicKeyBase64)
                    put("identitySignature", signature)
                    echoHelloNonce?.let { put("echoAckNonce", it) }
                }
            }
            else -> emptyMap()
        }
    }

    private fun assessIncomingIdentity(envelope: PacketEnvelope): IncomingIdentityAssessment {
        val payload = envelope.payload
        val peerId = envelope.senderPeerId.ifBlank { remotePeerId }.orEmpty()
        val identitySignature = payload.optString("identitySignature").ifBlank { null }
        val publicKey = payload.optString("publicKey").ifBlank { null }
        val localIdentity = DeviceIdentityManager.getOrCreateIdentity(appContext)
        val computedFingerprint = publicKey?.let(DeviceIdentityManager::fingerprintForPublicKey)
        val keyId = publicKey?.let(DeviceIdentityManager::fullKeyIdForPublicKey)
        val shortAuthString = DeviceIdentityManager.buildShortAuthString(localIdentity.publicKeyBase64, publicKey)
        val contact = findContactByKeyId(keyId) ?: findContactByPeerId(peerId)

        if (contact?.trustStatus == TrustStatus.Blocked) {
            return IncomingIdentityAssessment(
                trustStatus = TrustStatus.Blocked,
                fingerprint = contact.fingerprint ?: computedFingerprint,
                warning = contact.trustWarning ?: "Узел заблокирован пользователем",
                shortAuthString = shortAuthString,
                publicKeyBase64 = publicKey,
                keyId = keyId
            )
        }

        if (publicKey.isNullOrBlank() || identitySignature.isNullOrBlank()) {
            return IncomingIdentityAssessment(
                trustStatus = TrustStatus.Unverified,
                fingerprint = computedFingerprint,
                warning = "Идентичность не подтверждена: отсутствует публичный ключ или подпись.",
                shortAuthString = shortAuthString,
                publicKeyBase64 = publicKey,
                keyId = keyId
            )
        }

        val signatureValid = when (envelope.type) {
            "PROFILE_UPDATE" -> {
                val issuedAt = payload.optLong("issuedAt", 0L)
                if (issuedAt <= 0L) {
                    false
                } else {
                    val signedPayload = DeviceIdentityManager.buildProfileSignaturePayload(
                        type = envelope.type,
                        peerId = peerId,
                        publicNick = payload.optString("publicNick"),
                        avatarEmoji = payload.optString("avatarEmoji"),
                        publicKeyBase64 = publicKey,
                        issuedAt = issuedAt
                    )
                    DeviceIdentityManager.verifySignature(publicKey, signedPayload, identitySignature)
                }
            }
            "HELLO" -> {
                val helloNonce = payload.optString("helloNonce").ifBlank { null }
                !helloNonce.isNullOrBlank() && DeviceIdentityManager.verifySignature(
                    publicKey,
                    DeviceIdentityManager.buildHelloPayloadToSign(
                        protocolVersion = payload.optInt("protocolVersion", ProtocolConstants.PROTOCOL_VERSION),
                        senderPeerId = peerId,
                        publicKeyBase64 = publicKey,
                        helloNonce = helloNonce
                    ),
                    identitySignature
                )
            }
            "HELLO_ACK" -> {
                val ackNonce = payload.optString("ackNonce").ifBlank { null }
                val echoHelloNonce = payload.optString("echoHelloNonce").ifBlank { null }
                !ackNonce.isNullOrBlank() && !echoHelloNonce.isNullOrBlank() && DeviceIdentityManager.verifySignature(
                    publicKey,
                    DeviceIdentityManager.buildHelloAckPayloadToSign(
                        protocolVersion = payload.optInt("protocolVersion", ProtocolConstants.PROTOCOL_VERSION),
                        senderPeerId = peerId,
                        publicKeyBase64 = publicKey,
                        ackNonce = ackNonce,
                        echoHelloNonce = echoHelloNonce
                    ),
                    identitySignature
                )
            }
            "HELLO_CONFIRM" -> {
                val echoAckNonce = payload.optString("echoAckNonce").ifBlank { null }
                !echoAckNonce.isNullOrBlank() && DeviceIdentityManager.verifySignature(
                    publicKey,
                    DeviceIdentityManager.buildHelloConfirmPayloadToSign(
                        protocolVersion = payload.optInt("protocolVersion", ProtocolConstants.PROTOCOL_VERSION),
                        senderPeerId = peerId,
                        publicKeyBase64 = publicKey,
                        echoAckNonce = echoAckNonce
                    ),
                    identitySignature
                )
            }
            else -> false
        }

        if (!signatureValid) {
            return IncomingIdentityAssessment(
                trustStatus = TrustStatus.Unverified,
                fingerprint = computedFingerprint,
                warning = "Ключ получен, но подпись не подтверждена. Устройство не считается доверенным.",
                shortAuthString = shortAuthString,
                publicKeyBase64 = publicKey,
                keyId = keyId
            )
        }

        val knownPublicKey = contact?.publicKeyBase64
        val knownKeyId = contact?.keyId
        val keyChanged = when {
            !knownPublicKey.isNullOrBlank() -> knownPublicKey != publicKey
            !knownKeyId.isNullOrBlank() -> knownKeyId != keyId
            !remotePublicKey.isNullOrBlank() -> remotePublicKey != publicKey
            else -> false
        }

        if (keyChanged) {
            return IncomingIdentityAssessment(
                trustStatus = TrustStatus.Suspicious,
                fingerprint = computedFingerprint,
                warning = if (contact?.trustStatus == TrustStatus.Verified) {
                    "Ключ подтверждённого устройства изменился. Требуется повторная проверка."
                } else {
                    "Ключ устройства изменился относительно ранее сохранённого."
                },
                shortAuthString = shortAuthString,
                publicKeyBase64 = publicKey,
                keyId = keyId
            )
        }

        return IncomingIdentityAssessment(
            trustStatus = if (contact?.trustStatus == TrustStatus.Verified) TrustStatus.Verified else TrustStatus.Unverified,
            fingerprint = computedFingerprint,
            warning = if (contact?.trustStatus == TrustStatus.Verified) null else "Устройство подтверждено по ключу, но ещё не отмечено как доверенное вручную.",
            shortAuthString = shortAuthString,
            publicKeyBase64 = publicKey,
            keyId = keyId
        )
    }

    private fun updateConnectionSecurityState() {
        val trustLabel = when (remoteTrustStatus) {
            TrustStatus.Verified -> "Доверено"
            TrustStatus.Unverified -> "Проверить ключ"
            TrustStatus.Suspicious -> "Риск подмены"
            TrustStatus.Blocked -> "Заблокировано"
        }
        _connectionState.update {
            it.copy(
                remoteTrustStatus = remoteTrustStatus,
                remoteFingerprint = remoteFingerprint,
                securityWarning = remoteSecurityWarning,
                shortAuthString = remoteShortAuthString,
                connectionLabel = if (sessionState == SessionState.Active) "Онлайн • $trustLabel" else it.connectionLabel
            )
        }
        if (sessionState == SessionState.Active) {
            _chatState.update { it.copy(connectionLabel = "Онлайн • $trustLabel") }
        }
    }

    private fun receiveRelayPacket(socket: Socket) {
        socket.use { relaySocket ->
            runCatching {
                val raw = BufferedReader(InputStreamReader(relaySocket.getInputStream(), StandardCharsets.UTF_8)).readLine()
                if (!raw.isNullOrBlank()) {
                    handleIncomingRelayPacket(raw, relaySocket.inetAddress?.hostAddress)
                }
            }.onFailure { throwable ->
                log(LogType.Error, throwable.message ?: "Ошибка получения relay-пакета")
            }
        }
    }

    private fun handleIncomingRelayPacket(raw: String, relayHost: String?) {
        val envelope = PacketParser.parse(raw)
        val payload = envelope.payload
        when (envelope.type) {
            "RELAY_CHAT", "RELAY_CHAT_FORWARD" -> handleRelayChatPacket(envelope, payload, relayHost)
            "RELAY_CHAT_ACK" -> {
                val messageId = payload.optString("messageId")
                if (messageId.isNotBlank()) {
                    pendingMessageAcks.remove(messageId)?.job?.cancel()
                    markMessageDelivery(messageId, MessageDeliveryStatus.Delivered)
                    log(LogType.Chat, "Получен RELAY_CHAT_ACK для $messageId")
                }
            }
            "RELAY_FILE_MANIFEST", "RELAY_FILE_MANIFEST_FORWARD" -> handleRelayFileManifestPacket(envelope, payload, relayHost)
            "RELAY_FILE_ACCEPT", "RELAY_FILE_ACCEPT_FORWARD" -> handleRelayFileAcceptPacket(envelope, payload, relayHost)
            "RELAY_FILE_DECLINE", "RELAY_FILE_DECLINE_FORWARD" -> handleRelayFileDeclinePacket(envelope, payload, relayHost)
            "RELAY_FILE_CHUNK", "RELAY_FILE_CHUNK_FORWARD" -> handleRelayFileChunkPacket(envelope, payload, relayHost)
            "RELAY_FILE_COMPLETE", "RELAY_FILE_COMPLETE_FORWARD" -> handleRelayFileCompletePacket(envelope, payload, relayHost)
            "RELAY_FILE_FAILED", "RELAY_FILE_FAILED_FORWARD" -> handleRelayFileFailedPacket(envelope, payload, relayHost)
            "RELAY_GROUP_INVITE", "RELAY_GROUP_INVITE_FORWARD" -> handleRelayGroupInvitePacket(envelope, payload, relayHost)
            "RELAY_GROUP_MESSAGE", "RELAY_GROUP_MESSAGE_FORWARD" -> handleRelayGroupMessagePacket(envelope, payload, relayHost)
            "RELAY_GROUP_LEAVE", "RELAY_GROUP_LEAVE_FORWARD" -> handleRelayGroupLeavePacket(envelope, payload, relayHost)
            "RELAY_GROUP_FILE", "RELAY_GROUP_FILE_FORWARD" -> handleRelayGroupFilePacket(envelope, payload, relayHost)
            "RELAY_GROUP_UPDATE_SETTINGS", "RELAY_GROUP_UPDATE_SETTINGS_FORWARD" -> handleRelayGroupUpdateSettingsPacket(envelope, payload, relayHost)
        }
    }

    private fun handleRelayChatPacket(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?) {
        val targetPeerId = envelope.targetPeerId ?: payload.optString("finalTargetPeerId").ifBlank { null }
        val messageId = payload.optString("messageId").ifBlank { envelope.packetId }
        val originalSenderPeerId = payload.optString("originalSenderPeerId").ifBlank { envelope.senderPeerId }
        val originalSenderHost = payload.optString("originalSenderHost").ifBlank { null }
        val hopCount = payload.optInt("hopCount", 1)
        val routeLabel = payload.optString("routeLabel").ifBlank { null }

        if (!rememberRelayPacketId(envelope.packetId)) return

        if (!targetPeerId.isNullOrBlank() && targetPeerId == _connectionState.value.localPeerId) {
            if (rememberIncomingMessageId(messageId)) {
                val senderLabel = payload.optString("senderLabel").ifBlank { resolvePeerTitle(originalSenderPeerId, null, null) }
                val incoming = MessageUi(
                    id = messageId,
                    messageId = messageId,
                    text = payload.optString("text"),
                    timestamp = timeNow(),
                    type = MessageType.Incoming,
                    senderLabel = senderLabel,
                    isEdited = payload.optBoolean("isEdited", false),
                    isDeleted = payload.optBoolean("isDeleted", false),
                    routeLabel = routeLabel ?: relayHost?.let { "через $it" },
                    hopCount = hopCount,
                    targetPeerId = targetPeerId
                )
                _chatState.update { it.copy(messages = it.messages + incoming) }
                playSound(SoundEffect.MessageIncoming)
                if (shouldShowSystemNotifications()) {
                    AppNotificationManager.showMessageNotification(appContext, senderLabel.ifBlank { "Новое сообщение" }, incoming.text)
                }
                rememberThreadPreview(incoming.text, incoming.timestamp)
            }
            if (!originalSenderHost.isNullOrBlank()) {
                sendRelayPacketDirect(
                    host = originalSenderHost,
                    packet = buildRelayPacket(
                        type = "RELAY_CHAT_ACK",
                        targetPeerId = originalSenderPeerId,
                        payload = mapOf("messageId" to messageId)
                    )
                )
            }
            return
        }

        val forwarded = relayForwardPacket(envelope, payload, relayHost)
        if (!forwarded) {
            pendingRelayPackets[envelope.packetId] = rawPacketWithPayload(envelope, payload, maxOf(0, envelope.ttl - 1))
            log(LogType.Network, "Relay-пакет $messageId поставлен в очередь ожидания маршрута")
        }
    }

    private fun rawPacketWithPayload(envelope: PacketEnvelope, payload: JSONObject, ttl: Int = envelope.ttl): String {
        val map = mutableMapOf<String, Any?>()
        val keys = payload.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = payload.opt(key)
        }
        return PacketFactory.create(
            type = envelope.type,
            senderPeerId = envelope.senderPeerId,
            targetPeerId = envelope.targetPeerId,
            ackId = envelope.ackId,
            ttl = ttl,
            payload = map
        )
    }

    private fun relayForwardPacket(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?): Boolean {
        if (envelope.ttl <= 0) return false
        val targetPeerId = envelope.targetPeerId ?: payload.optString("finalTargetPeerId").ifBlank { null } ?: return false
        val targetPeer = discoveredPeers[targetPeerId] ?: loadContacts(appContext).firstOrNull { it.peerId == targetPeerId }?.let {
            DiscoveredPeerUi(it.id, it.peerId, it.displayName, it.host, it.port, avatarEmoji = it.avatarEmoji, trustStatus = it.trustStatus)
        }
        val targetHost = targetPeer?.host?.takeIf { it.isNotBlank() }
        if (relayHost != null && relayHost == targetHost) return false
        val map = mutableMapOf<String, Any?>()
        val keys = payload.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = payload.opt(key)
        }
        val currentHop = payload.optInt("hopCount", 1)
        map["hopCount"] = currentHop + 1
        map["routeLabel"] = ((payload.optString("routeLabel").ifBlank { null } ?: relayHost?.let { "через $it" } ?: "")
            .let { existing ->
                val hopTitle = _settingsState.value.publicNick.ifBlank { _settingsState.value.localDeviceName }
                if (existing.isBlank()) "через $hopTitle" else "$existing → $hopTitle"
            })
        val packet = PacketFactory.create(
            type = if (envelope.type.endsWith("_FORWARD")) envelope.type else envelope.type + "_FORWARD",
            senderPeerId = envelope.senderPeerId,
            targetPeerId = targetPeerId,
            ackId = envelope.ackId,
            ttl = envelope.ttl - 1,
            payload = map
        )
        var sent = false
        if (!targetHost.isNullOrBlank()) {
            sent = sendRelayPacketDirect(targetHost, packet)
        }
        if (!sent) {
            val fallbackRelay = selectBestRelayPeer(targetPeerId, excludePeerIds = setOfNotNull(targetPeerId, remotePeerId))
            if (fallbackRelay != null && fallbackRelay.host != relayHost) {
                relayReroutedPacketsCounter += 1
                sent = sendRelayPacketDirect(fallbackRelay.host, packet)
                rememberRelayResult(fallbackRelay.peerId, sent)
                if (sent) {
                    relayRecoveredRoutesCounter += 1
                    log(LogType.Network, "Relay: пакет reroute через ${fallbackRelay.title}")
                }
            }
        }
        if (sent) {
            relayForwardedPacketsCounter += 1
            updateRelayDiagnostics()
            log(LogType.Network, "Relay: пакет ${payload.optString("messageId").ifBlank { payload.optString("transferId") }} перенаправлен")
        } else {
            relayDroppedPacketsCounter += 1
            updateRelayDiagnostics()
        }
        return sent
    }

    private fun sendRelayPacketDirect(host: String, packet: String): Boolean {
        return runCatching {
            Socket().use { relaySocket ->
                relaySocket.connect(InetSocketAddress(host, RELAY_PORT), SOCKET_TIMEOUT_MS)
                relaySocket.tcpNoDelay = true
                BufferedWriter(OutputStreamWriter(relaySocket.getOutputStream(), StandardCharsets.UTF_8)).use { writer ->
                    writer.write(packet)
                    writer.newLine()
                    writer.flush()
                }
            }
            true
        }.getOrElse { false }
    }

    private fun rememberRelayPacketId(packetId: String): Boolean {
        synchronized(deliveredRelayPacketIds) {
            if (deliveredRelayPacketIds.contains(packetId)) return false
            deliveredRelayPacketIds.add(packetId)
            while (deliveredRelayPacketIds.size > 512) {
                val first = deliveredRelayPacketIds.firstOrNull() ?: break
                deliveredRelayPacketIds.remove(first)
            }
            return true
        }
    }

    private fun flushPendingRelayPackets() {
        if (pendingRelayPackets.isEmpty()) {
            updateRelayDiagnostics()
            return
        }
        val iterator = pendingRelayPackets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val envelope = runCatching { PacketParser.parse(entry.value) }.getOrNull() ?: continue
            if (relayForwardPacket(envelope, envelope.payload, null)) {
                iterator.remove()
            }
        }
        updateRelayDiagnostics()
    }

    private fun updateRelayDiagnostics() {
        val routes = discoveredPeers.values
            .sortedWith(
                compareByDescending<DiscoveredPeerUi> { routePreferred(it.trustStatus) }
                    .thenByDescending { trustScore(it.trustStatus) }
                    .thenBy { it.title }
            )
            .map { peer ->
                val viaTitle = if (remotePeerId != null && remotePeerId != peer.peerId) {
                    discoveredPeers[remotePeerId]?.title ?: remotePeerId
                } else null
                MeshRouteUi(
                    peerId = peer.peerId,
                    title = peer.title,
                    host = peer.host,
                    viaTitle = viaTitle,
                    hops = if (viaTitle == null) 1 else 2,
                    isOnline = true,
                    trustStatus = peer.trustStatus,
                    securityNote = relaySecurityNote(peer.trustStatus),
                    isPreferred = routePreferred(peer.trustStatus)
                )
            }
        val trustedRoutes = routes.count { it.trustStatus == TrustStatus.Verified }
        val blockedRoutes = routes.count { it.trustStatus == TrustStatus.Blocked }
        val untrustedRoutes = routes.count { it.trustStatus == TrustStatus.Unverified || it.trustStatus == TrustStatus.Suspicious }
        val securityNote = when {
            blockedRoutes > 0 -> "Заблокированные узлы исключаются из relay-маршрутов"
            trustedRoutes > 0 -> "Проверенные узлы используются как приоритетные relay-маршруты"
            untrustedRoutes > 0 -> "Доступны только неподтверждённые relay-маршруты"
            else -> null
        }
        _connectionState.update {
            it.copy(
                relayPendingPackets = pendingRelayPackets.size,
                relayKnownRoutes = routes.size,
                relayForwardedPackets = relayForwardedPacketsCounter,
                relayDroppedPackets = relayDroppedPacketsCounter,
                relayTrustedRoutes = trustedRoutes,
                relayUntrustedRoutes = untrustedRoutes,
                relayBlockedRoutes = blockedRoutes,
                relayReroutedPackets = relayReroutedPacketsCounter,
                relayRecoveredRoutes = relayRecoveredRoutesCounter,
                relaySecurityNote = securityNote,
                relayTopology = routes
            )
        }
    }

    private fun buildRelayFilePacket(
        type: String,
        targetPeerId: String,
        transferId: String,
        payload: Map<String, Any?>
    ): String {
        return buildRelayPacket(
            type = type,
            targetPeerId = targetPeerId,
            payload = payload + mapOf("transferId" to transferId)
        )
    }

    private suspend fun sendRelayedFileControl(type: String, transferId: String, payload: Map<String, Any?> = emptyMap()): Boolean {
        val resolution = resolveActiveChatTarget() ?: return false
        val packet = buildRelayFilePacket(type, resolution.targetPeerId, transferId, payload + mapOf(
            "finalTargetPeerId" to resolution.targetPeerId,
            "routeLabel" to resolution.routeLabel,
            "senderLabel" to localDisplayName(),
            "originalSenderPeerId" to _connectionState.value.localPeerId,
            "originalSenderHost" to _connectionState.value.localIp
        ))
        val relayHost = currentSocket?.inetAddress?.hostAddress ?: return false
        val sent = sendRelayPacketDirect(relayHost, packet)
        if (!sent) pendingRelayPackets[PacketParser.parse(packet).packetId] = packet
        updateRelayDiagnostics()
        return sent
    }

    private fun relayFileForwardOrQueue(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?): Boolean {
        val forwarded = relayForwardPacket(envelope, payload, relayHost)
        if (!forwarded) {
            pendingRelayPackets[envelope.packetId] = rawPacketWithPayload(envelope, payload, maxOf(0, envelope.ttl - 1))
            updateRelayDiagnostics()
        }
        return forwarded
    }

    private fun handleRelayFileManifestPacket(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?) {
        val targetPeerId = envelope.targetPeerId ?: payload.optString("finalTargetPeerId").ifBlank { null }
        if (!rememberRelayPacketId(envelope.packetId)) return
        if (!targetPeerId.isNullOrBlank() && targetPeerId == _connectionState.value.localPeerId) {
            val manifest = TransferManifest.fromPayload(payload)
            val fromPeer = payload.optString("senderLabel").ifBlank { manifest.senderLabel ?: envelope.senderPeerId }
            incomingTransfers[manifest.transferId] = IncomingFileTransfer(
                transferId = manifest.transferId,
                fileName = manifest.fileName,
                fileSize = manifest.fileSize,
                mimeType = manifest.mimeType,
                fromPeerLabel = fromPeer,
                chunkSize = manifest.chunkSize,
                totalChunks = manifest.totalChunks,
                checksum = manifest.checksum,
                routeLabel = payload.optString("routeLabel").ifBlank { relayHost?.let { "через $it" } },
                hopCount = payload.optInt("hopCount", 1)
            )
            _chatState.update {
                it.copy(
                    incomingFileOffer = IncomingFileOfferUi(
                        transferId = manifest.transferId,
                        fileName = manifest.fileName,
                        fileSizeBytes = manifest.fileSize,
                        fileSizeLabel = formatSize(manifest.fileSize),
                        mimeType = manifest.mimeType,
                        fromPeerLabel = listOfNotNull(fromPeer, payload.optString("routeLabel").ifBlank { null }).joinToString(" • "),
                        totalChunks = manifest.totalChunks,
                        checksum = manifest.checksum
                    ),
                    messages = it.messages + MessageUi(
                        id = UUID.randomUUID().toString(),
                        text = "Получен relay-файл ${manifest.fileName} (${formatSize(manifest.fileSize)})",
                        timestamp = timeNow(),
                        type = MessageType.System,
                        routeLabel = payload.optString("routeLabel").ifBlank { null },
                        hopCount = payload.optInt("hopCount", 1)
                    )
                )
            }
            playSound(SoundEffect.NotificationSoft)
            return
        }
        relayFileForwardOrQueue(envelope, payload, relayHost)
    }

    private fun handleRelayFileAcceptPacket(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?) {
        val targetPeerId = envelope.targetPeerId ?: payload.optString("finalTargetPeerId").ifBlank { null }
        if (!rememberRelayPacketId(envelope.packetId)) return
        if (!targetPeerId.isNullOrBlank() && targetPeerId == _connectionState.value.localPeerId) {
            enqueueOutgoingFileTransfer(payload.optString("transferId"))
            return
        }
        relayFileForwardOrQueue(envelope, payload, relayHost)
    }

    private fun handleRelayFileDeclinePacket(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?) {
        val targetPeerId = envelope.targetPeerId ?: payload.optString("finalTargetPeerId").ifBlank { null }
        if (!rememberRelayPacketId(envelope.packetId)) return
        if (!targetPeerId.isNullOrBlank() && targetPeerId == _connectionState.value.localPeerId) {
            val transferId = payload.optString("transferId")
            val declined = outgoingTransfers.remove(transferId)
            _chatState.update { it.copy(fileTransfer = it.fileTransfer.copy(status = FileTransferStatus.Error, errorMessage = "Получатель отклонил relay-файл", detailText = declined?.fileName)) }
            return
        }
        relayFileForwardOrQueue(envelope, payload, relayHost)
    }

    private fun handleRelayFileChunkPacket(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?) {
        val targetPeerId = envelope.targetPeerId ?: payload.optString("finalTargetPeerId").ifBlank { null }
        if (!rememberRelayPacketId(envelope.packetId)) return
        if (!targetPeerId.isNullOrBlank() && targetPeerId == _connectionState.value.localPeerId) {
            val transferId = payload.optString("transferId")
            val transfer = incomingTransfers[transferId] ?: return
            val chunkIndex = payload.optInt("chunkIndex", -1)
            if (chunkIndex < 0) return
            val encoded = payload.optString("chunkData")
            if (encoded.isBlank()) return
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            if (transfer.tempRelayFilePath.isNullOrBlank()) {
                val tempFile = File(appContext.cacheDir, "relay_${transferId}.part")
                if (tempFile.exists()) tempFile.delete()
                transfer.tempRelayFilePath = tempFile.absolutePath
            }
            if (chunkIndex != transfer.expectedChunkIndex) {
                log(LogType.Error, "Relay chunk out of order for $transferId: $chunkIndex != ${transfer.expectedChunkIndex}")
                return
            }
            FileOutputStream(File(transfer.tempRelayFilePath!!), true).use { it.write(bytes) }
            transfer.expectedChunkIndex += 1
            transfer.receivedBytes += bytes.size
            val progress = if (transfer.fileSize > 0L) (transfer.receivedBytes.toFloat() / transfer.fileSize.toFloat()).coerceIn(0f, 1f) else 0f
            _chatState.update {
                it.copy(fileTransfer = it.fileTransfer.copy(
                    status = FileTransferStatus.Receiving,
                    progress = progress,
                    bytesProcessed = transfer.receivedBytes,
                    totalBytes = transfer.fileSize,
                    remainingLabel = formatSize((transfer.fileSize - transfer.receivedBytes).coerceAtLeast(0L)),
                    detailText = "Relay-чанк ${transfer.expectedChunkIndex} из ${transfer.totalChunks}"
                ))
            }
            return
        }
        relayFileForwardOrQueue(envelope, payload, relayHost)
    }

    private fun handleRelayFileCompletePacket(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?) {
        val targetPeerId = envelope.targetPeerId ?: payload.optString("finalTargetPeerId").ifBlank { null }
        if (!rememberRelayPacketId(envelope.packetId)) return
        if (!targetPeerId.isNullOrBlank() && targetPeerId == _connectionState.value.localPeerId) {
            val transferId = payload.optString("transferId")
            val transfer = incomingTransfers.remove(transferId) ?: return
            val tempPath = transfer.tempRelayFilePath ?: return
            val tempFile = File(tempPath)
            val actualChecksum = calculateChecksumForFile(tempFile)
            if (transfer.checksum.isNotBlank() && !actualChecksum.equals(transfer.checksum, ignoreCase = true)) {
                scope.launch { sendRelayedFileControl("RELAY_FILE_FAILED", transferId, mapOf("reason" to "Контрольная сумма relay-файла не совпала")) }
                tempFile.delete()
                return
            }
            val destinationUri = transfer.destinationUri ?: return
            appContext.contentResolver.openOutputStream(destinationUri, "w")?.use { out -> tempFile.inputStream().use { it.copyTo(out) } }
            tempFile.delete()
            val savedAt = timeNow()
            _chatState.update {
                it.copy(
                    fileTransfer = it.fileTransfer.copy(status = FileTransferStatus.Received, progress = 1f, bytesProcessed = transfer.fileSize, totalBytes = transfer.fileSize, remainingLabel = "0 B", detailText = "Relay-файл сохранён"),
                    messages = it.messages + MessageUi(id = UUID.randomUUID().toString(), text = "Relay-файл ${transfer.fileName} сохранён", timestamp = savedAt, type = MessageType.System, routeLabel = transfer.routeLabel, hopCount = transfer.hopCount)
                )
            }
            playSound(SoundEffect.FileReceived)
            scope.launch { sendRelayedFileControl("RELAY_FILE_COMPLETE", transferId, mapOf("checksum" to actualChecksum)) }
            return
        }
        relayFileForwardOrQueue(envelope, payload, relayHost)
    }

    private fun handleRelayFileFailedPacket(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?) {
        val targetPeerId = envelope.targetPeerId ?: payload.optString("finalTargetPeerId").ifBlank { null }
        if (!rememberRelayPacketId(envelope.packetId)) return
        if (!targetPeerId.isNullOrBlank() && targetPeerId == _connectionState.value.localPeerId) {
            val transferId = payload.optString("transferId")
            val reason = payload.optString("reason").ifBlank { "Relay file failed" }
            outgoingTransfers.remove(transferId)
            _chatState.update { it.copy(fileTransfer = it.fileTransfer.copy(status = FileTransferStatus.Error, errorMessage = reason)) }
            return
        }
        relayFileForwardOrQueue(envelope, payload, relayHost)
    }

    private fun calculateChecksumForFile(file: File): String {
        val crc = CRC32()
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value.toString(16)
    }

    private fun handleIncomingPacket(raw: String) {
        runCatching {
            val envelope = PacketParser.parse(raw)
            val payload = envelope.payload
            when (envelope.type) {
                "RELAY_CHAT", "RELAY_CHAT_FORWARD", "RELAY_CHAT_ACK",
                "RELAY_GROUP_INVITE", "RELAY_GROUP_INVITE_FORWARD",
                "RELAY_GROUP_MESSAGE", "RELAY_GROUP_MESSAGE_FORWARD",
                "RELAY_GROUP_LEAVE", "RELAY_GROUP_LEAVE_FORWARD",
                "RELAY_GROUP_FILE", "RELAY_GROUP_FILE_FORWARD",
                "RELAY_GROUP_UPDATE_SETTINGS", "RELAY_GROUP_UPDATE_SETTINGS_FORWARD" -> {
                    handleIncomingRelayPacket(raw, currentSocket?.inetAddress?.hostAddress)
                }
                "HELLO" -> {
                    remotePeerId = envelope.senderPeerId.ifBlank { remotePeerId }
                    remotePublicNick = payload.optString("publicNick").ifBlank { null }
                    remoteDeviceName = payload.optString("deviceName").ifBlank { null }
                    val securityAssessment = assessIncomingIdentity(envelope)
                    remotePublicKey = payload.optString("publicKey").ifBlank { null }
                    remoteFingerprint = securityAssessment.fingerprint
                    remoteTrustStatus = securityAssessment.trustStatus
                    remoteSecurityWarning = securityAssessment.warning
                    remoteShortAuthString = securityAssessment.shortAuthString
                    updateRemoteIdentity(
                        peerId = remotePeerId,
                        publicNick = remotePublicNick,
                        deviceName = remoteDeviceName,
                        host = currentSocket?.inetAddress?.hostAddress,
                        avatarEmoji = payload.optString("avatarEmoji").ifBlank { null },
                        trustStatus = remoteTrustStatus,
                        fingerprint = remoteFingerprint,
                        trustWarning = remoteSecurityWarning,
                        shortAuthString = remoteShortAuthString,
                        publicKeyBase64 = securityAssessment.publicKeyBase64,
                        keyId = securityAssessment.keyId
                    )
                    transitionSession(SessionState.Active, "HELLO получен")
                    updateConnectionLabels("Онлайн")
                    updateConnectionSecurityState()
                    _connectionState.update { it.copy(canOpenChat = true, status = ConnectionStatus.Connected) }
                    _chatState.update { it.copy(isConnected = true) }
                    log(LogType.Network, "Получен HELLO")
                    flushPendingRelayPackets()
                    if (!remoteSecurityWarning.isNullOrBlank()) {
                        log(LogType.Error, remoteSecurityWarning!!)
                        playSound(SoundEffect.DeviceWarning)
                        setChatSystemMessage(remoteSecurityWarning!!)
                    }
                    sendPacket(buildPacket(
                        type = "HELLO_ACK",
                        payload = signedIdentityPayload("HELLO_ACK", echoHelloNonce = payload.optString("helloNonce").ifBlank { envelope.packetId }) + mapOf(
                            "ackId" to envelope.packetId,
                            "protocolVersion" to ProtocolConstants.PROTOCOL_VERSION
                        )
                    ))
                    flushPendingRelayPackets()
                    if (remoteTrustStatus == TrustStatus.Verified) {
                        playSound(SoundEffect.DeviceVerified)
                        setChatSystemMessage("Подключено доверенное устройство")
                    } else {
                        playSound(SoundEffect.NotificationSoft)
                        setChatSystemMessage("Удалённое устройство подключилось. Стабильный код сверки: ${remoteShortAuthString ?: "—"}")
                    }
                }

                "HELLO_ACK" -> {
                    remotePeerId = envelope.senderPeerId.ifBlank { remotePeerId }
                    remotePublicNick = payload.optString("publicNick").ifBlank { null }
                    remoteDeviceName = payload.optString("deviceName").ifBlank { null }
                    val securityAssessment = assessIncomingIdentity(envelope)
                    remotePublicKey = payload.optString("publicKey").ifBlank { null }
                    remoteFingerprint = securityAssessment.fingerprint
                    remoteTrustStatus = securityAssessment.trustStatus
                    remoteSecurityWarning = securityAssessment.warning
                    remoteShortAuthString = securityAssessment.shortAuthString
                    updateRemoteIdentity(
                        peerId = remotePeerId,
                        publicNick = remotePublicNick,
                        deviceName = remoteDeviceName,
                        host = currentSocket?.inetAddress?.hostAddress,
                        avatarEmoji = payload.optString("avatarEmoji").ifBlank { null },
                        trustStatus = remoteTrustStatus,
                        fingerprint = remoteFingerprint,
                        trustWarning = remoteSecurityWarning,
                        shortAuthString = remoteShortAuthString,
                        publicKeyBase64 = securityAssessment.publicKeyBase64,
                        keyId = securityAssessment.keyId
                    )
                    transitionSession(SessionState.Active, "HELLO_ACK получен")
                    updateConnectionLabels("Онлайн")
                    updateConnectionSecurityState()
                    _connectionState.update { it.copy(canOpenChat = true, status = ConnectionStatus.Connected) }
                    _chatState.update { it.copy(isConnected = true) }
                    log(LogType.Network, "Получен HELLO_ACK")
                    if (!remoteSecurityWarning.isNullOrBlank()) {
                        log(LogType.Error, remoteSecurityWarning!!)
                        playSound(SoundEffect.DeviceWarning)
                        setChatSystemMessage(remoteSecurityWarning!!)
                    } else {
                        setChatSystemMessage("Рукопожатие выполнено. Стабильный код сверки: ${remoteShortAuthString ?: "—"}")
                    }
                    sendPacket(buildPacket(
                        type = "HELLO_CONFIRM",
                        payload = signedIdentityPayload("HELLO_CONFIRM", echoHelloNonce = payload.optString("ackNonce").ifBlank { envelope.packetId }) + mapOf(
                            "ackId" to envelope.packetId,
                            "protocolVersion" to ProtocolConstants.PROTOCOL_VERSION
                        )
                    ))
                }

                "HELLO_CONFIRM" -> {
                    val securityAssessment = assessIncomingIdentity(envelope)
                    remotePublicKey = payload.optString("publicKey").ifBlank { null }
                    remoteFingerprint = securityAssessment.fingerprint
                    remoteTrustStatus = securityAssessment.trustStatus
                    remoteSecurityWarning = securityAssessment.warning
                    remoteShortAuthString = securityAssessment.shortAuthString
                    updateConnectionSecurityState()
                    if (!remoteSecurityWarning.isNullOrBlank()) {
                        log(LogType.Error, remoteSecurityWarning!!)
                    } else {
                        log(LogType.Network, "Получен HELLO_CONFIRM")
                    }
                }

                "PROFILE_UPDATE" -> {
                    val securityAssessment = assessIncomingIdentity(envelope)
                    remotePublicKey = payload.optString("publicKey").ifBlank { null }
                    remoteFingerprint = securityAssessment.fingerprint
                    remoteTrustStatus = securityAssessment.trustStatus
                    remoteSecurityWarning = securityAssessment.warning
                    remoteShortAuthString = securityAssessment.shortAuthString
                    updateRemoteIdentity(
                        peerId = envelope.senderPeerId.ifBlank { remotePeerId },
                        publicNick = payload.optString("publicNick").ifBlank { remotePublicNick },
                        deviceName = payload.optString("deviceName").ifBlank { remoteDeviceName },
                        host = currentSocket?.inetAddress?.hostAddress,
                        avatarEmoji = payload.optString("avatarEmoji").ifBlank { null },
                        trustStatus = remoteTrustStatus,
                        fingerprint = remoteFingerprint,
                        trustWarning = remoteSecurityWarning,
                        shortAuthString = remoteShortAuthString,
                        publicKeyBase64 = securityAssessment.publicKeyBase64,
                        keyId = securityAssessment.keyId
                    )
                    updateConnectionSecurityState()
                    setChatSystemMessage(
                        remoteSecurityWarning ?: "Профиль собеседника обновлён. Стабильный код сверки: ${remoteShortAuthString ?: "—"}"
                    )
                }

                "CALL_INVITE" -> {
                    val remoteHost = currentSocket?.inetAddress?.hostAddress
                    pendingCallRemoteHost = remoteHost
                    val callerTitle = resolveConnectedPeerTitle(remoteHost).orEmpty().ifBlank { _chatState.value.roomTitle }
                    val mode = payload.optString("mode").ifBlank { "audio" }
                    val isVideo = mode == "video"
                    _callState.update {
                        it.copy(
                            status = CallStatus.IncomingRinging,
                            peerTitle = callerTitle,
                            peerAvatarEmoji = _chatState.value.roomAvatarEmoji,
                            incomingInviteFrom = callerTitle,
                            errorMessage = null,
                            qualityLabel = if (isVideo) "Входящий видеовызов" else "Входящий аудиовызов",
                            durationLabel = "00:00",
                            isVideoCall = isVideo,
                            remoteVideoFrame = null
                        )
                    }
                    setChatSystemMessage(if (isVideo) "Входящий видеовызов от $callerTitle" else "Входящий аудиовызов от $callerTitle")
                    if (shouldShowSystemNotifications()) {
                        AppNotificationManager.showIncomingCallNotification(appContext, callerTitle)
                        requestOpenRoute(AppRoute.Call)
                    }
                    startLoopSound(SoundEffect.IncomingCall)
                    log(LogType.Network, "Получен CALL_INVITE")
                }

                "CALL_ACCEPT" -> {
                    val remoteHost = currentSocket?.inetAddress?.hostAddress ?: pendingCallRemoteHost
                    if (!remoteHost.isNullOrBlank()) {
                        val isVideo = payload.optString("mode") == "video" || _callState.value.isVideoCall
                        _callState.update { it.copy(status = CallStatus.Connecting, qualityLabel = if (isVideo) "Собеседник принял видеовызов" else "Собеседник принял вызов", isVideoCall = isVideo) }
                        startAudioStreaming(remoteHost)
                    } else {
                        handleAudioCallError("Собеседник принял вызов, но адрес аудиоканала не определён")
                    }
                }

                "CALL_DECLINE" -> {
                    playSound(SoundEffect.CallEnded)
                    stopAudioCallLocally("Собеседник отклонил аудиовызов", CallStatus.Ended)
                }

                "CALL_HANGUP" -> {
                    playSound(SoundEffect.CallEnded)
                    stopAudioCallLocally("Собеседник завершил аудиовызов", CallStatus.Ended)
                }

                "CALL_MEDIA_STATE" -> {
                    val remoteMicMuted = payload.optBoolean("micMuted", false)
                    val remoteVideoMuted = payload.optBoolean("videoMuted", false)
                    _callState.update {
                        it.copy(
                            isRemoteMicMuted = remoteMicMuted,
                            isRemoteVideoMuted = remoteVideoMuted,
                            remoteVideoFrame = if (remoteVideoMuted) null else it.remoteVideoFrame,
                            qualityLabel = when {
                                it.status == CallStatus.Active && remoteMicMuted && remoteVideoMuted -> "Собеседник отключил микрофон и камеру"
                                it.status == CallStatus.Active && remoteMicMuted -> "Собеседник отключил микрофон"
                                it.status == CallStatus.Active && remoteVideoMuted -> "Собеседник отключил камеру"
                                else -> it.qualityLabel
                            }
                        )
                    }
                    if (remoteMicMuted || remoteVideoMuted) {
                        log(LogType.Network, "Обновлено состояние медиа собеседника: micMuted=$remoteMicMuted, videoMuted=$remoteVideoMuted")
                    }
                }

                "CALL_VIDEO_PROFILE" -> {
                    val profile = payload.optString("profile").ifBlank { "MEDIUM" }
                    videoCallManager.applyNetworkProfile(profile)
                    _callState.update {
                        it.copy(
                            videoStatusLabel = when {
                                it.isRemoteVideoMuted -> "Собеседник отключил камеру"
                                else -> "Видео активно · профиль $profile"
                            },
                            qualityLabel = if (it.status == CallStatus.Active && it.isVideoCall) "Видео 1:1 • профиль сети $profile" else it.qualityLabel
                        )
                    }
                    log(LogType.Network, "Собеседник рекомендовал видео-профиль: $profile")
                }

                "PING" -> {
                    sendPacket(buildPacket(type = "PONG", payload = mapOf("ackId" to envelope.packetId)), disconnectOnFailure = false)
                    updateConnectionLabels("Онлайн")
                    log(LogType.Network, "Получен PING, отправлен PONG")
                }

                "PONG" -> {
                    updateConnectionLabels("Онлайн")
                    log(LogType.Network, "Получен PONG")
                }

                "TYPING" -> {
                    handleRemoteTyping(payload.optBoolean("active", false))
                }

                "GROUP_INVITE" -> {
                    handleGroupInvitePayload(payload, null)
                }

                "GROUP_MESSAGE" -> {
                    handleGroupMessagePayload(payload, "group").also { groupPacketsReceivedCounter += 1; updateRelayDiagnostics() }
                }

                "GROUP_LEAVE" -> {
                    handleGroupLeavePayload(payload)
                }

                "GROUP_FILE" -> {
                    handleGroupFilePayload(payload, "group")
                }

                "GROUP_UPDATE_SETTINGS" -> {
                    handleGroupUpdateSettingsPayload(payload, "group")
                }

                "CHAT" -> {
                    val messageId = payload.optString("messageId").ifBlank { UUID.randomUUID().toString() }
                    val text = payload.optString("text")
                    val senderLabel = payload.optString("senderLabel").ifBlank { resolveConnectedPeerTitle(currentSocket?.inetAddress?.hostAddress) }
                    if (rememberIncomingMessageId(messageId)) {
                        val incoming = MessageUi(
                            id = messageId,
                            messageId = messageId,
                            text = text,
                            timestamp = timeNow(),
                            type = MessageType.Incoming,
                            senderLabel = senderLabel,
                            isEdited = payload.optBoolean("isEdited", false),
                            isDeleted = payload.optBoolean("isDeleted", false)
                        )
                        _chatState.update { it.copy(messages = it.messages + incoming) }
                        playSound(SoundEffect.MessageIncoming)
                        if (shouldShowSystemNotifications()) {
                            AppNotificationManager.showMessageNotification(appContext, senderLabel ?: "Новое сообщение", text)
                        }
                        rememberThreadPreview(text, incoming.timestamp)
                        log(LogType.Chat, "Получено сообщение $messageId")
                    }
                    sendPacket(
                        buildPacket(
                            type = "CHAT_ACK",
                            payload = mapOf("messageId" to messageId, "ackId" to envelope.packetId)
                        ),
                        disconnectOnFailure = false
                    )
                    handleRemoteTyping(false)
                }

                "CHAT_ACK" -> {
                    val messageId = payload.optString("messageId")
                    if (messageId.isNotBlank()) {
                        pendingMessageAcks.remove(messageId)?.job?.cancel()
                        markMessageDelivery(messageId, MessageDeliveryStatus.Delivered)
                        log(LogType.Chat, "Получен CHAT_ACK для $messageId")
                    }
                }

                "EDIT_MESSAGE" -> {
                    val messageId = payload.optString("messageId")
                    val newText = payload.optString("newText")
                    if (messageId.isNotBlank() && newText.isNotBlank()) {
                        updateMessageById(messageId) { old -> old.copy(text = newText, isEdited = true, isDeleted = false) }
                        log(LogType.Chat, "Собеседник изменил сообщение $messageId")
                    }
                }

                "DELETE_MESSAGE" -> {
                    val messageId = payload.optString("messageId")
                    if (messageId.isNotBlank()) {
                        updateMessageById(messageId) { old ->
                            old.copy(text = "Сообщение удалено", isDeleted = true, isEdited = false, deliveryStatus = null)
                        }
                        log(LogType.Chat, "Собеседник удалил сообщение $messageId")
                    }
                }

                "FILE_MANIFEST" -> {
                    val manifest = TransferManifest.fromPayload(payload)
                    val fromPeer = manifest.senderLabel ?: envelope.senderPeerId.ifBlank {
                        resolveConnectedPeerTitle(currentSocket?.inetAddress?.hostAddress).orEmpty()
                    }
                    incomingTransfers[manifest.transferId] = IncomingFileTransfer(
                        transferId = manifest.transferId,
                        fileName = manifest.fileName,
                        fileSize = manifest.fileSize,
                        mimeType = manifest.mimeType,
                        fromPeerLabel = fromPeer,
                        chunkSize = manifest.chunkSize,
                        totalChunks = manifest.totalChunks,
                        checksum = manifest.checksum
                    )
                    _chatState.update {
                        it.copy(
                            incomingFileOffer = IncomingFileOfferUi(
                                transferId = manifest.transferId,
                                fileName = manifest.fileName,
                                fileSizeBytes = manifest.fileSize,
                                fileSizeLabel = formatSize(manifest.fileSize),
                                mimeType = manifest.mimeType,
                                fromPeerLabel = fromPeer,
                                totalChunks = manifest.totalChunks,
                                checksum = manifest.checksum
                            ),
                            messages = it.messages + MessageUi(
                                id = UUID.randomUUID().toString(),
                                text = "Получен файл ${manifest.fileName} (${formatSize(manifest.fileSize)}). Можно скачать в папку Загрузки.",
                                timestamp = timeNow(),
                                type = MessageType.System
                            )
                        )
                    }
                    playSound(SoundEffect.NotificationSoft)
                    rememberThreadPreview("Файл: ${manifest.fileName}", timeNow())
                    log(LogType.File, "Получен FILE_MANIFEST: ${manifest.fileName}")
                }

                "FILE_ACCEPT" -> {
                    val transferId = payload.optString("transferId")
                    log(LogType.File, "Получено подтверждение приёма файла")
                    enqueueOutgoingFileTransfer(transferId)
                }

                "FILE_DECLINE" -> {
                    val transferId = payload.optString("transferId")
                    val declined = outgoingTransfers.remove(transferId)
                    _chatState.update {
                        it.copy(
                            fileTransfer = it.fileTransfer.copy(
                                status = FileTransferStatus.Error,
                                progress = 0f,
                                errorMessage = "Получатель отклонил файл",
                                detailText = declined?.fileName?.let { name -> "$name не был принят" }
                            )
                        )
                    }
                    setChatSystemMessage("Получатель отклонил файл ${declined?.fileName ?: ""}".trim())
                    log(LogType.File, "Получатель отклонил входящий файл")
                }

                "FILE_COMPLETE" -> {
                    val transferId = payload.optString("transferId")
                    val transfer = outgoingTransfers.remove(transferId)
                    if (transfer != null) {
                        outgoingFileRetryCounts.remove(transferId)
                        _chatState.update {
                            it.copy(
                                fileTransfer = it.fileTransfer.copy(
                                    status = FileTransferStatus.Sent,
                                    progress = 1f,
                                    bytesProcessed = transfer.fileSize,
                                    totalBytes = transfer.fileSize,
                                    remainingLabel = "0 B",
                                    detailText = "Получатель подтвердил целостность файла ${transfer.fileName}"
                                )
                            )
                        }
                        playSound(SoundEffect.FileSent)
                        log(LogType.File, "FILE_COMPLETE подтверждён для ${transfer.fileName}")
                    }
                }

                "FILE_FAILED" -> {
                    val transferId = payload.optString("transferId")
                    val reason = payload.optString("reason").ifBlank { "Ошибка целостности" }
                    val transfer = outgoingTransfers[transferId]
                    if (transfer != null) {
                        scheduleOutgoingFileRetry(transferId, reason)
                    } else {
                        _chatState.update {
                            it.copy(fileTransfer = it.fileTransfer.copy(status = FileTransferStatus.Error, errorMessage = reason))
                        }
                    }
                    log(LogType.Error, "Передача файла завершилась ошибкой: $reason")
                }

                "DISCONNECT" -> {
                    onConnectionLost("Собеседник вышел из комнаты")
                }

                "CLEAR_HISTORY" -> {
                    currentThreadKey?.let { key -> threadHistories.remove(key); threadSnapshots.remove(key); databaseHelper.deleteThread(key) }
                    persistThreadHistories()
                    persistThreadSnapshots()
                    _chatState.update {
                        it.copy(
                            messages = emptyList(),
                            incomingFileOffer = null,
                            fileTransfer = FileTransferUi(),
                            attachmentsExpanded = false,
                            editingMessageId = null,
                            editingMessageText = ""
                        )
                    }
                    rememberThreadPreview("История очищена", timeNow())
                    log(LogType.Chat, "Собеседник очистил историю чата")
                }
            }
        }.onFailure {
            log(LogType.Error, it.message ?: "Некорректный пакет")
        }
    }
    private fun enqueueOutgoingFileTransfer(transferId: String) {
        if (transferId.isBlank()) return
        startFileTransferLoop()
        outgoingFileQueue.trySend(transferId)
    }

    private fun startFileTransferLoop() {
        if (fileSenderJob?.isActive == true) return
        fileSenderJob = scope.launch {
            for (transferId in outgoingFileQueue) {
                val success = performOutgoingFileTransfer(transferId)
                if (!success) {
                    val reason = _chatState.value.fileTransfer.errorMessage ?: "Ошибка отправки файла"
                    scheduleOutgoingFileRetry(transferId, reason)
                }
            }
        }
    }

    private fun scheduleOutgoingFileRetry(transferId: String, reason: String) {
        val transfer = outgoingTransfers[transferId] ?: return
        val nextAttempt = (outgoingFileRetryCounts[transferId] ?: 0) + 1
        if (nextAttempt > DeliveryPolicy.FILE_RETRY_LIMIT) {
            outgoingTransfers.remove(transferId)
            outgoingFileRetryCounts.remove(transferId)
            _chatState.update {
                it.copy(
                    fileTransfer = it.fileTransfer.copy(
                        status = FileTransferStatus.Error,
                        errorMessage = reason,
                        detailText = "${transfer.fileName}: превышено число повторов"
                    )
                )
            }
            log(LogType.Error, "Передача файла ${transfer.fileName} завершилась после ${DeliveryPolicy.FILE_RETRY_LIMIT} попыток")
            return
        }

        outgoingFileRetryCounts[transferId] = nextAttempt
        _chatState.update {
            it.copy(
                fileTransfer = it.fileTransfer.copy(
                    status = FileTransferStatus.WaitingForReceiver,
                    errorMessage = null,
                    detailText = "Повторная отправка ${transfer.fileName}, попытка $nextAttempt из ${DeliveryPolicy.FILE_RETRY_LIMIT}"
                )
            )
        }
        log(LogType.File, "Файл ${transfer.fileName}: запланирован повтор $nextAttempt")
        scope.launch {
            delay((2000L * nextAttempt).coerceAtMost(6000L))
            if (outgoingTransfers.containsKey(transferId) && isSessionActive()) {
                enqueueOutgoingFileTransfer(transferId)
            }
        }
    }

    private suspend fun performOutgoingFileTransfer(transferId: String): Boolean {
        val transfer = outgoingTransfers[transferId] ?: return false
        val relayResolution = resolveActiveChatTarget()
        if (relayResolution?.isRelayed == true) {
            return performOutgoingRelayFileTransfer(transfer, relayResolution)
        }
        val host = currentSocket?.inetAddress?.hostAddress
        if (host.isNullOrBlank()) {
            _chatState.update {
                it.copy(
                    fileTransfer = it.fileTransfer.copy(
                        status = FileTransferStatus.Error,
                        errorMessage = "Не удалось определить адрес получателя"
                    )
                )
            }
            return false
        }

        return runCatching {
            _chatState.update {
                it.copy(
                    fileTransfer = it.fileTransfer.copy(
                        fileName = transfer.fileName,
                        fileSizeLabel = formatSize(transfer.fileSize),
                        status = FileTransferStatus.Sending,
                        progress = 0f,
                        errorMessage = null,
                        bytesProcessed = 0L,
                        totalBytes = transfer.fileSize,
                        remainingLabel = formatSize(transfer.fileSize),
                        detailText = "Передаём чанки по локальной сети"
                    )
                )
            }

            Socket().use { socket ->
                socket.keepAlive = true
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, FILE_TRANSFER_PORT), 15_000)
                DataOutputStream(BufferedOutputStream(socket.getOutputStream())).use { output ->
                    output.writeUTF(FILE_MAGIC)
                    output.writeUTF(transfer.transferId)
                    output.writeUTF(transfer.fileName)
                    output.writeLong(transfer.fileSize)
                    output.writeUTF(transfer.mimeType)
                    output.writeInt(transfer.chunkSize)
                    output.writeInt(transfer.totalChunks)
                    output.writeUTF(transfer.checksum)

                    (transfer.tempBytes?.inputStream() ?: appContext.contentResolver.openInputStream(transfer.uri))?.use { input ->
                        val buffer = ByteArray(transfer.chunkSize)
                        var sentBytes = 0L
                        var chunkIndex = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.writeInt(chunkIndex)
                            output.writeInt(read)
                            output.write(buffer, 0, read)
                            sentBytes += read
                            chunkIndex += 1
                            val progress = if (transfer.fileSize > 0L) {
                                (sentBytes.toFloat() / transfer.fileSize.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            _chatState.update {
                                it.copy(
                                    fileTransfer = it.fileTransfer.copy(
                                        status = FileTransferStatus.Sending,
                                        progress = progress,
                                        bytesProcessed = sentBytes,
                                        totalBytes = transfer.fileSize,
                                        remainingLabel = formatSize((transfer.fileSize - sentBytes).coerceAtLeast(0L)),
                                        detailText = "Чанк ${chunkIndex} из ${transfer.totalChunks} • ${formatSize(sentBytes)} из ${formatSize(transfer.fileSize)}"
                                    )
                                )
                            }
                        }
                    } ?: error("Не удалось открыть исходный файл")
                    output.flush()
                }
            }

            outgoingFileRetryCounts.remove(transferId)
            val sentAt = timeNow()
            val isImage = transfer.mimeType.startsWith("image/")
            val isVoice = transfer.mimeType.startsWith("audio/")
            _chatState.update {
                val outgoingMessage = when {
                    isImage -> MessageUi(
                        id = UUID.randomUUID().toString(),
                        text = transfer.fileName,
                        timestamp = sentAt,
                        type = MessageType.Outgoing,
                        senderLabel = localDisplayName(),
                        deliveryStatus = MessageDeliveryStatus.Delivered,
                        attachmentName = transfer.fileName,
                        attachmentMimeType = transfer.mimeType,
                        attachmentUri = transfer.previewUriString ?: transfer.uri.toString(),
                        attachmentSizeBytes = transfer.fileSize,
                        isImageAttachment = true
                    )
                    isVoice -> MessageUi(
                        id = UUID.randomUUID().toString(),
                        text = "Голосовое сообщение",
                        timestamp = sentAt,
                        type = MessageType.Outgoing,
                        senderLabel = localDisplayName(),
                        deliveryStatus = MessageDeliveryStatus.Delivered,
                        attachmentName = transfer.fileName,
                        attachmentMimeType = transfer.mimeType,
                        attachmentUri = transfer.previewUriString ?: transfer.uri.toString(),
                        attachmentSizeBytes = transfer.fileSize,
                        isVoiceNote = true
                    )
                    else -> MessageUi(
                        id = UUID.randomUUID().toString(),
                        text = "Файл ${transfer.fileName} отправлен",
                        timestamp = sentAt,
                        type = MessageType.System
                    )
                }
                it.copy(
                    fileTransfer = it.fileTransfer.copy(
                        status = FileTransferStatus.Sent,
                        progress = 1f,
                        bytesProcessed = transfer.fileSize,
                        totalBytes = transfer.fileSize,
                        remainingLabel = "0 B",
                        errorMessage = null,
                        detailText = if (isImage) "Фото отправлено" else "Файл отправлен. Ожидаем подтверждение целостности"
                    ),
                    messages = it.messages + outgoingMessage
                )
            }
            rememberThreadPreview(if (isImage) "Фото" else "Файл: ${transfer.fileName}", sentAt)
            log(LogType.File, "Файл отправлен чанками: ${transfer.fileName}")
            true
        }.getOrElse { throwable ->
            _chatState.update {
                it.copy(
                    fileTransfer = it.fileTransfer.copy(
                        status = FileTransferStatus.Error,
                        progress = 0f,
                        errorMessage = throwable.message ?: "Ошибка отправки файла"
                    )
                )
            }
            log(LogType.Error, throwable.message ?: "Ошибка отправки файла")
            false
        }
    }

    private suspend fun performOutgoingRelayFileTransfer(transfer: OutgoingFileTransfer, resolution: RelayResolution): Boolean {
        return runCatching {
            resolution.securityWarning?.let { warning ->
                setChatSystemMessage("Relay-файл: $warning")
            }
            _chatState.update {
                it.copy(fileTransfer = it.fileTransfer.copy(status = FileTransferStatus.Sending, progress = 0f, errorMessage = null, detailText = listOfNotNull(
                            "Relay-отправка через ${resolution.routeLabel ?: resolution.targetTitle}",
                            resolution.securityWarning
                        ).joinToString(" • ")))
            }
            val sourceBytes = transfer.tempBytes ?: appContext.contentResolver.openInputStream(transfer.uri)?.use { it.readBytes() } ?: error("Не удалось открыть исходный файл")
            val chunkSize = transfer.chunkSize.coerceAtMost(RELAY_FILE_CHUNK_BASE64_THRESHOLD)
            var sentBytes = 0L
            sourceBytes.asList().chunked(chunkSize).forEachIndexed { index, chunkList ->
                val chunk = chunkList.toByteArray()
                val sent = sendRelayedFileControl(
                    type = "RELAY_FILE_CHUNK",
                    transferId = transfer.transferId,
                    payload = mapOf(
                        "chunkIndex" to index,
                        "totalChunks" to transfer.totalChunks,
                        "chunkData" to Base64.encodeToString(chunk, Base64.NO_WRAP),
                        "fileName" to transfer.fileName,
                        "mimeType" to transfer.mimeType,
                        "fileSize" to transfer.fileSize,
                        "checksum" to transfer.checksum,
                        "senderLabel" to localDisplayName(),
                        "finalTargetPeerId" to resolution.targetPeerId,
                        "routeLabel" to resolution.routeLabel,
                        "hopCount" to 1
                    )
                )
                if (!sent) error("Не удалось отправить relay-чанк $index")
                sentBytes += chunk.size
                val progress = (sentBytes.toFloat() / transfer.fileSize.toFloat()).coerceIn(0f, 1f)
                _chatState.update {
                    it.copy(fileTransfer = it.fileTransfer.copy(status = FileTransferStatus.Sending, progress = progress, bytesProcessed = sentBytes, totalBytes = transfer.fileSize, remainingLabel = formatSize((transfer.fileSize - sentBytes).coerceAtLeast(0L)), detailText = "Relay-чанк ${index + 1} из ${transfer.totalChunks}"))
                }
            }
            sendRelayedFileControl("RELAY_FILE_COMPLETE", transfer.transferId, mapOf("checksum" to transfer.checksum, "finalTargetPeerId" to resolution.targetPeerId, "routeLabel" to resolution.routeLabel, "hopCount" to 1))
            _chatState.update {
                it.copy(fileTransfer = it.fileTransfer.copy(status = FileTransferStatus.Sent, progress = 1f, bytesProcessed = transfer.fileSize, totalBytes = transfer.fileSize, remainingLabel = "0 B", detailText = "Relay-файл отправлен"), messages = it.messages + MessageUi(id = UUID.randomUUID().toString(), text = "Файл ${transfer.fileName} отправлен", timestamp = timeNow(), type = MessageType.System, routeLabel = resolution.routeLabel, hopCount = 2))
            }
            playSound(SoundEffect.FileSent)
            true
        }.getOrElse { throwable ->
            _chatState.update { it.copy(fileTransfer = it.fileTransfer.copy(status = FileTransferStatus.Error, errorMessage = throwable.message ?: "Ошибка relay-отправки файла")) }
            false
        }
    }

    private suspend fun receiveIncomingFile(socket: Socket) {
        runCatching {
            DataInputStream(BufferedInputStream(socket.getInputStream())).use { input ->
                val magic = input.readUTF()
                if (magic != FILE_MAGIC) error("Некорректный файловый пакет")

                val transferId = input.readUTF()
                val fileName = input.readUTF()
                val fileSize = input.readLong()
                val mimeType = input.readUTF()
                val chunkSize = input.readInt()
                val totalChunks = input.readInt()
                val expectedChecksum = input.readUTF()

                val transfer = incomingTransfers[transferId]
                    ?: error("Нет подтверждённой загрузки для файла $fileName")
                val destinationUri = transfer.destinationUri
                    ?: error("Не выбрано место сохранения для файла $fileName")

                val crc32 = CRC32()
                appContext.contentResolver.openOutputStream(destinationUri, "w")?.use { output ->
                    val buffer = ByteArray(chunkSize)
                    var receivedBytes = 0L
                    var chunkIndex = 0
                    while (receivedBytes < fileSize) {
                        val seq = input.readInt()
                        val read = input.readInt()
                        if (seq != chunkIndex) throw IOException("Нарушен порядок чанков: ожидали $chunkIndex, получили $seq")
                        input.readFully(buffer, 0, read)
                        output.write(buffer, 0, read)
                        crc32.update(buffer, 0, read)
                        receivedBytes += read
                        chunkIndex += 1
                        val progress = if (fileSize > 0L) {
                            (receivedBytes.toFloat() / fileSize.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                        _chatState.update {
                            it.copy(
                                fileTransfer = it.fileTransfer.copy(
                                    status = FileTransferStatus.Receiving,
                                    progress = progress,
                                    bytesProcessed = receivedBytes,
                                    totalBytes = fileSize,
                                    remainingLabel = formatSize((fileSize - receivedBytes).coerceAtLeast(0L)),
                                    detailText = "Чанк ${chunkIndex} из ${totalChunks} • ${formatSize(receivedBytes)} из ${formatSize(fileSize)}"
                                )
                            )
                        }
                    }
                    output.flush()
                } ?: error("Не удалось открыть место сохранения")

                val actualChecksum = crc32.value.toString(16)
                if (expectedChecksum.isNotBlank() && !actualChecksum.equals(expectedChecksum, ignoreCase = true)) {
                    sendPacket(buildPacket(type = "FILE_FAILED", payload = mapOf("transferId" to transferId, "reason" to "Контрольная сумма не совпала")), disconnectOnFailure = false)
                    throw IOException("Контрольная сумма не совпала")
                }

                incomingTransfers.remove(transferId)
                val savedAt = timeNow()
                val isImage = mimeType.startsWith("image/")
                val isVoice = mimeType.startsWith("audio/")
                _chatState.update {
                    val incomingMessage = when {
                        isImage -> MessageUi(
                            id = UUID.randomUUID().toString(),
                            text = fileName,
                            timestamp = savedAt,
                            type = MessageType.Incoming,
                            senderLabel = remotePublicNick ?: remoteDeviceName,
                            attachmentName = fileName,
                            attachmentMimeType = mimeType,
                            attachmentUri = destinationUri.toString(),
                            attachmentSizeBytes = fileSize,
                            isImageAttachment = true
                        )
                        isVoice -> MessageUi(
                            id = UUID.randomUUID().toString(),
                            text = "Голосовое сообщение",
                            timestamp = savedAt,
                            type = MessageType.Incoming,
                            senderLabel = remotePublicNick ?: remoteDeviceName,
                            attachmentName = fileName,
                            attachmentMimeType = mimeType,
                            attachmentUri = destinationUri.toString(),
                            attachmentSizeBytes = fileSize,
                            isVoiceNote = true
                        )
                        else -> MessageUi(
                            id = UUID.randomUUID().toString(),
                            text = "Файл $fileName сохранён",
                            timestamp = savedAt,
                            type = MessageType.System
                        )
                    }
                    it.copy(
                        fileTransfer = it.fileTransfer.copy(
                            fileName = fileName,
                            fileSizeLabel = formatSize(fileSize),
                            status = FileTransferStatus.Received,
                            progress = 1f,
                            bytesProcessed = fileSize,
                            totalBytes = fileSize,
                            remainingLabel = "0 B",
                            errorMessage = null,
                            detailText = if (isImage) "Фото сохранено в Загрузки/Ladya" else "Файл сохранён и проверен"
                        ),
                        messages = it.messages + incomingMessage
                    )
                }
                playSound(SoundEffect.FileReceived)
                rememberThreadPreview(if (isImage) "Фото" else "Файл сохранён: $fileName", savedAt)
                sendPacket(buildPacket(type = "FILE_COMPLETE", payload = mapOf("transferId" to transferId, "checksum" to actualChecksum)), disconnectOnFailure = false)
                log(LogType.File, "Файл получен и сохранён: $fileName")
            }
        }.onFailure { throwable ->
            _chatState.update {
                it.copy(
                    fileTransfer = it.fileTransfer.copy(
                        status = FileTransferStatus.Error,
                        progress = 0f,
                        errorMessage = throwable.message ?: "Ошибка приёма файла"
                    )
                )
            }
            log(LogType.Error, throwable.message ?: "Ошибка приёма файла")
        }
    }

    private fun buildPacket(type: String, payload: Map<String, Any?> = emptyMap()): String {
        return PacketFactory.create(
            type = type,
            senderPeerId = _connectionState.value.localPeerId,
            targetPeerId = remotePeerId,
            payload = payload
        )
    }

    private fun buildRelayPacket(type: String, targetPeerId: String, payload: Map<String, Any?> = emptyMap(), ttl: Int = ProtocolConstants.DEFAULT_PACKET_TTL): String {
        return PacketFactory.create(
            type = type,
            senderPeerId = _connectionState.value.localPeerId,
            targetPeerId = targetPeerId,
            ttl = ttl,
            payload = payload
        )
    }

    private fun resolveActiveChatTarget(): RelayResolution? {
        val thread = currentThreadKey?.let { threadSnapshots[it] }
        val activePeerId = remotePeerId
        val targetPeerId = thread?.peerId?.takeIf { it.isNotBlank() } ?: activePeerId ?: return null
        val targetTitle = thread?.title?.takeIf { it.isNotBlank() }
            ?: discoveredPeers[targetPeerId]?.title
            ?: activePeerId?.let { discoveredPeers[it]?.title }
            ?: _chatState.value.roomTitle

        val directPeer = discoveredPeers[targetPeerId]
        val directAvailable = directPeer?.host?.isNotBlank() == true && directPeer.trustStatus != TrustStatus.Blocked
        if (activePeerId == targetPeerId || (activePeerId == null && directAvailable)) {
            return RelayResolution(
                targetPeerId = targetPeerId,
                targetTitle = targetTitle,
                routeLabel = null,
                isRelayed = false,
                relayTrustStatus = directPeer?.trustStatus ?: remoteTrustStatus,
                securityWarning = null,
                relayPeerId = null,
                relayHost = directPeer?.host
            )
        }

        val preferredRelay = selectBestRelayPeer(targetPeerId, preferredPeerId = activePeerId)
        val relayPeerId = preferredRelay?.peerId ?: activePeerId
        val relayPeer = preferredRelay ?: relayPeerId?.let { discoveredPeers[it] }
        val isRelayed = relayPeer != null && relayPeer.peerId != targetPeerId
        val relayStatus = relayPeer?.trustStatus ?: remoteTrustStatus
        if (isRelayed && relayStatus == TrustStatus.Blocked) {
            setError("Маршрут через заблокированный узел запрещён")
            return null
        }
        val relayTitle = relayPeer?.title ?: relayPeerId
        val routeLabel = if (isRelayed && !relayTitle.isNullOrBlank()) {
            val suffix = relayRouteSuffix(relayStatus)
            val base = if (suffix.isNullOrBlank()) "через $relayTitle" else "через $relayTitle • $suffix"
            if (preferredRelay != null && activePeerId != null && preferredRelay.peerId != activePeerId) "$base • reroute" else base
        } else null
        return RelayResolution(
            targetPeerId = targetPeerId,
            targetTitle = targetTitle,
            routeLabel = routeLabel,
            isRelayed = isRelayed,
            relayTrustStatus = relayStatus,
            securityWarning = if (isRelayed) relaySecurityNote(relayStatus) else null,
            relayPeerId = relayPeerId,
            relayHost = relayPeer?.host,
            recoveryCandidateUsed = preferredRelay != null && activePeerId != null && preferredRelay.peerId != activePeerId
        )
    }

    private fun selectBestRelayPeer(targetPeerId: String, preferredPeerId: String? = null, excludePeerIds: Set<String> = emptySet()): DiscoveredPeerUi? {
        val now = System.currentTimeMillis()
        val candidates = discoveredPeers.values
            .asSequence()
            .filter { it.peerId != targetPeerId }
            .filter { it.peerId !in excludePeerIds }
            .filter { it.host.isNotBlank() }
            .filter { it.trustStatus != TrustStatus.Blocked }
            .sortedWith(
                compareByDescending<DiscoveredPeerUi> { if (preferredPeerId != null && it.peerId == preferredPeerId) 1 else 0 }
                    .thenByDescending { trustScore(it.trustStatus) }
                    .thenBy { relayFailurePenalty(it.peerId, now) }
                    .thenByDescending { peerLastSeenAt[it.peerId] ?: 0L }
                    .thenByDescending { relayLastSuccessAt[it.peerId] ?: 0L }
            )
            .toList()
        return candidates.firstOrNull()
    }

    private fun relayFailurePenalty(peerId: String, now: Long): Int {
        val failures = relayFailureCounts[peerId] ?: 0
        val lastFailure = relayLastFailureAt[peerId] ?: 0L
        val cooldownPenalty = if (lastFailure > 0L && now - lastFailure < 15_000L) 100 else 0
        return failures * 10 + cooldownPenalty
    }

    private fun rememberRelayResult(peerId: String?, success: Boolean) {
        peerId ?: return
        if (success) {
            relayLastSuccessAt[peerId] = System.currentTimeMillis()
            relayFailureCounts.remove(peerId)
            relayLastFailureAt.remove(peerId)
        } else {
            relayFailureCounts[peerId] = (relayFailureCounts[peerId] ?: 0) + 1
            relayLastFailureAt[peerId] = System.currentTimeMillis()
        }
    }

    private fun startSenderLoop() {
        if (senderJob?.isActive == true) return
        senderJob = scope.launch {
            for (queued in outgoingPacketQueue) {
                val result = performSendPacket(queued.packet, queued.disconnectOnFailure)
                queued.result.complete(result)
            }
        }
    }

    private fun clearOutgoingPacketQueue() {
        while (true) {
            val queued = outgoingPacketQueue.tryReceive().getOrNull() ?: break
            queued.result.complete(false)
        }
    }

    private fun sendPacket(packet: String, disconnectOnFailure: Boolean = true): Boolean {
        startSenderLoop()
        startFileTransferLoop()
        return runBlocking {
            val result = CompletableDeferred<Boolean>()
            outgoingPacketQueue.send(
                QueuedPacket(
                    packet = packet,
                    disconnectOnFailure = disconnectOnFailure,
                    result = result
                )
            )
            result.await()
        }
    }

    private fun performSendPacket(packet: String, disconnectOnFailure: Boolean): Boolean {
        val socket = currentSocket ?: return false
        if (socket.isClosed || !socket.isConnected || socket.isOutputShutdown) {
            log(
                LogType.Error,
                "Сокет недоступен для отправки: closed=${socket.isClosed}, connected=${socket.isConnected}, outputShutdown=${socket.isOutputShutdown}"
            )
            if (disconnectOnFailure) {
                onConnectionLost("Соединение недоступно для отправки")
            }
            return false
        }

        val payloadType = runCatching { JSONObject(packet).optString("type") }
            .getOrNull()
            .orEmpty()

        return runCatching {
            synchronized(sendLock) {
                if (socket !== currentSocket || connectionClosing) {
                    throw IOException("Активное соединение уже заменено или закрывается")
                }
                val primaryWriter = currentWriter ?: BufferedWriter(
                    OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                ).also { currentWriter = it }
                try {
                    primaryWriter.write(packet)
                    primaryWriter.newLine()
                    primaryWriter.flush()
                } catch (firstError: IOException) {
                    log(LogType.Network, "Повторная попытка отправки пакета $payloadType: ${formatThrowable(firstError)}")
                    val retryWriter = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
                    currentWriter = retryWriter
                    retryWriter.write(packet)
                    retryWriter.newLine()
                    retryWriter.flush()
                }
            }
            true
        }.getOrElse { throwable ->
            val details = formatThrowable(throwable)
            log(LogType.Error, "Не удалось отправить пакет ${payloadType.ifBlank { "UNKNOWN" }}: $details")
            if (disconnectOnFailure && socket === currentSocket && !connectionClosing) {
                onConnectionLost("Соединение потеряно во время отправки")
            }
            false
        }
    }

    private fun isSocketConnected(): Boolean {
        val socket = currentSocket
        return socket != null && socket.isConnected && !socket.isClosed
    }

    private fun onConnectionLost(message: String, initiatedLocally: Boolean = false) {
        stopAudioCallLocally(null, CallStatus.Ended)
        synchronized(connectionLifecycleLock) {
            if (connectionClosing) return
            connectionClosing = true
        }
        transitionSession(SessionState.Closing, message)
        closeCurrentSocket()
        clearOutgoingPacketQueue()
        pendingMessageAcks.forEach { (_, pending) -> pending.job?.cancel() }
        pendingMessageAcks.clear()
        outgoingFileRetryCounts.clear()
        remotePeerId = null
        remotePublicNick = null
        remoteDeviceName = null
        localTypingActive = false
        remoteTypingResetJob?.cancel()
        transitionSession(SessionState.Listening, "Готов к новому подключению")
        _connectionState.update {
            it.copy(
                status = ConnectionStatus.Listening,
                isLoading = false,
                canOpenChat = false,
                connectedPeerTitle = null,
                connectionLabel = "Ожидаем новое подключение",
                shouldAutoOpenChat = false
            )
        }
        _chatState.update {
            it.copy(
                isConnected = false,
                isPeerOnline = false,
                typingLabel = null,
                roomTitle = it.roomTitle,
                connectionLabel = message,
                incomingFileOffer = null,
                attachmentsExpanded = false,
                editingMessageId = null,
                editingMessageText = "",
                messages = it.messages + MessageUi(
                    id = UUID.randomUUID().toString(),
                    text = message,
                    timestamp = timeNow(),
                    type = MessageType.System
                )
            )
        }
        rememberThreadPreview(message, timeNow())
        if (initiatedLocally) {
            clearPendingFiles()
        }
        publishPeers()
        if (!_profileState.value.isLocalProfile) {
            _profileState.value = ProfileUiState()
        }
        log(LogType.Network, message)
        if (!initiatedLocally && _settingsState.value.backgroundServiceActive) {
            scheduleRecoveryReconnect(message)
        }
    }

    private fun closeCurrentSocket() {
        val socketToClose: Socket?
        val writerToClose: BufferedWriter?
        synchronized(connectionLifecycleLock) {
            runCatching { readerJob?.cancel() }
            runCatching { pingJob?.cancel() }
            readerJob = null
            pingJob = null
            socketToClose = currentSocket
            writerToClose = currentWriter
            currentSocket = null
            currentWriter = null
            activeSocketSessionId = null
        }
        runCatching { writerToClose?.close() }
        runCatching { socketToClose?.close() }
    }

    private fun formatThrowable(throwable: Throwable): String {
        val name = throwable::class.java.simpleName.ifBlank { "Throwable" }
        val message = throwable.message?.takeIf { it.isNotBlank() }
        return if (message != null) "$name: $message" else name
    }

    fun consumeAutoOpenChat() {
        _connectionState.update { it.copy(shouldAutoOpenChat = false) }
    }

    private fun setError(message: String) {
        if (!isSessionActive()) transitionSession(SessionState.Error, message)
        _connectionState.update {
            it.copy(
                status = if (isSocketConnected()) ConnectionStatus.Connected else ConnectionStatus.Error,
                isLoading = false,
                errorMessage = message,
                canOpenChat = isSocketConnected(),
                connectionLabel = if (isSocketConnected()) "Подключено" else message
            )
        }
    }

    private fun setChatSystemMessage(message: String) {
        _chatState.update {
            it.copy(
                messages = it.messages + MessageUi(
                    id = UUID.randomUUID().toString(),
                    text = message,
                    timestamp = timeNow(),
                    type = MessageType.System
                )
            )
        }
        rememberThreadPreview(message, timeNow())
    }

    private fun updateRemoteIdentity(
        peerId: String?,
        publicNick: String?,
        deviceName: String?,
        host: String?,
        avatarEmoji: String? = null,
        trustStatus: TrustStatus = remoteTrustStatus,
        fingerprint: String? = remoteFingerprint,
        trustWarning: String? = remoteSecurityWarning,
        shortAuthString: String? = remoteShortAuthString,
        publicKeyBase64: String? = remotePublicKey,
        keyId: String? = publicKeyBase64?.let(DeviceIdentityManager::fullKeyIdForPublicKey)
    ) {
        if (peerId.isNullOrBlank()) return
        val current = discoveredPeers[peerId]
        val title = resolvePeerTitle(peerId, deviceName, publicNick)
        peerLastSeenAt[peerId] = System.currentTimeMillis()
        discoveredPeers[peerId] = DiscoveredPeerUi(
            id = peerId,
            peerId = peerId,
            title = title,
            host = host ?: current?.host.orEmpty(),
            port = current?.port ?: DEFAULT_PORT,
            subtitle = buildPeerSubtitle(publicNick, deviceName, host ?: current?.host.orEmpty()),
            isSavedContact = findContactByPeerId(peerId) != null,
            avatarEmoji = avatarEmoji ?: current?.avatarEmoji,
            trustStatus = trustStatus,
            fingerprint = fingerprint ?: current?.fingerprint,
            trustWarning = trustWarning,
            shortAuthString = shortAuthString,
            publicKeyBase64 = publicKeyBase64 ?: current?.publicKeyBase64,
            keyId = keyId ?: current?.keyId
        )
        val threadKey = resolveThreadKey(peerId, host, title)
        val previousThreadKey = currentThreadKey
        val isNewThread = previousThreadKey != threadKey
        if (isNewThread && previousThreadKey != null && threadHistories[threadKey] == null) {
            threadHistories[threadKey] = (threadHistories.remove(previousThreadKey) ?: _chatState.value.messages.toMutableList())
            threadSnapshots[previousThreadKey]?.let { snapshot ->
                threadSnapshots[threadKey] = snapshot.copy(key = threadKey, title = title, peerId = peerId, host = host ?: snapshot.host, avatarEmoji = avatarEmoji ?: snapshot.avatarEmoji)
                threadSnapshots.remove(previousThreadKey)
            }
        }
        currentThreadKey = threadKey
        _connectionState.update {
            it.copy(
                connectedPeerTitle = title,
                connectionLabel = "Онлайн",
                canOpenChat = true,
                status = ConnectionStatus.Connected,
                remoteTrustStatus = trustStatus,
                remoteFingerprint = fingerprint,
                securityWarning = trustWarning,
                shortAuthString = shortAuthString
            )
        }
        rememberReconnectTarget(peerId, host, currentSocket?.port ?: DEFAULT_PORT)
        if (isNewThread) {
            switchToThread(threadKey = threadKey, title = title, online = true, typingLabel = null, connectionLabel = "Онлайн")
        } else {
            _chatState.update { it.copy(roomTitle = title, roomAvatarEmoji = avatarEmoji ?: it.roomAvatarEmoji, isPeerOnline = true, connectionLabel = "Онлайн") }
        }
        updateContactIdentity(peerId, publicNick, host, avatarEmoji, trustStatus, fingerprint, trustWarning, publicKeyBase64, keyId)
        rememberThreadPreview(
            _chatState.value.messages.lastOrNull()?.text ?: "Готов к диалогу",
            _chatState.value.messages.lastOrNull()?.timestamp ?: timeNow(),
            explicitKey = threadKey,
            explicitTitle = title
        )
        publishPeers()
        flushPendingRelayPackets()
    }

    private fun parseGroupMembers(payload: JSONObject): MutableSet<String> {
        val result = mutableSetOf<String>()
        val members = payload.optJSONArray("members")
        if (members != null) {
            for (index in 0 until members.length()) {
                members.optString(index).takeIf { it.isNotBlank() }?.let(result::add)
            }
        }
        return result
    }

    private fun parseGroupAdmins(payload: JSONObject, ownerPeerId: String): MutableSet<String> {
        val result = mutableSetOf<String>()
        val admins = payload.optJSONArray("admins")
        if (admins != null) {
            for (index in 0 until admins.length()) {
                admins.optString(index).takeIf { it.isNotBlank() }?.let(result::add)
            }
        }
        if (ownerPeerId.isNotBlank()) result.add(ownerPeerId)
        return result
    }

    private fun handleGroupInvitePayload(payload: JSONObject, routeLabel: String?) {
        val groupId = payload.optString("groupId").ifBlank { return }
        val title = payload.optString("groupTitle").ifBlank { "Новая группа" }
        val ownerPeerId = payload.optString("ownerPeerId")
        val members = parseGroupMembers(payload)
        val localPeerId = _connectionState.value.localPeerId
        if (localPeerId.isNotBlank()) members.add(localPeerId)
        val group = ChatGroupDefinition(groupId, title, members, ownerPeerId, parseGroupAdmins(payload, ownerPeerId))
        groupDefinitions[groupId] = group
        val threadKey = groupThreadKey(groupId)
        val inviterLabel = payload.optString("inviterLabel").ifBlank { "Участник" }
        val system = MessageUi(id = UUID.randomUUID().toString(), text = "$inviterLabel добавил Вас в группу", timestamp = timeNow(), type = MessageType.System, routeLabel = routeLabel)
        val history = threadHistories.getOrPut(threadKey) { mutableListOf() }
        if (history.none { it.text == system.text && it.type == MessageType.System }) history += system
        threadSnapshots[threadKey] = (threadSnapshots[threadKey] ?: ChatThreadUi(key = threadKey, title = title, preview = system.text, timestamp = system.timestamp, lastMessageEpoch = System.currentTimeMillis(), avatarEmoji = "👥")).copy(title = title, preview = system.text, timestamp = system.timestamp, lastMessageEpoch = System.currentTimeMillis(), avatarEmoji = "👥")
        if (_chatState.value.activeThreadKey == threadKey || !_chatState.value.isGroupChat) {
            switchToThread(threadKey, title, online = true, connectionLabel = "Mesh-группа")
        } else {
            rememberThreadPreview(system.text, system.timestamp, explicitKey = threadKey, explicitTitle = title)
        }
        publishPeers()
    }

    private fun handleGroupMessagePayload(payload: JSONObject, routeLabel: String?) {
        val groupId = payload.optString("groupId").ifBlank { return }
        val title = payload.optString("groupTitle").ifBlank { "Группа" }
        val ownerPeerId = payload.optString("ownerPeerId")
        val members = parseGroupMembers(payload)
        val group = groupDefinitions[groupId] ?: ChatGroupDefinition(groupId, title, members, ownerPeerId, parseGroupAdmins(payload, ownerPeerId)).also { groupDefinitions[groupId] = it }
        val threadKey = groupThreadKey(groupId)
        val messageId = payload.optString("messageId").ifBlank { UUID.randomUUID().toString() }
        if (!rememberIncomingMessageId(messageId)) return
        val senderLabel = payload.optString("senderLabel").ifBlank { resolvePeerTitle(payload.optString("senderPeerId"), null, null) }
        val incoming = MessageUi(id = messageId, messageId = messageId, text = payload.optString("text"), timestamp = timeNow(), type = MessageType.Incoming, senderLabel = senderLabel, routeLabel = routeLabel)
        val history = threadHistories.getOrPut(threadKey) { mutableListOf() }
        history += incoming
        threadSnapshots[threadKey] = (threadSnapshots[threadKey] ?: ChatThreadUi(key = threadKey, title = title, preview = incoming.text, timestamp = incoming.timestamp, lastMessageEpoch = System.currentTimeMillis(), avatarEmoji = "👥")).copy(title = title, preview = incoming.text, timestamp = incoming.timestamp, lastMessageEpoch = System.currentTimeMillis(), avatarEmoji = "👥")
        if (_chatState.value.activeThreadKey == threadKey) {
            _chatState.update { it.copy(messages = history.toList(), isGroupChat = true, groupId = groupId, groupOwnerPeerId = group.ownerPeerId, groupMembers = resolveGroupMemberLabels(group), groupAdminPeerIds = group.adminPeerIds.toSet(), roomTitle = title, roomSubtitle = "${group.members.size} участников", activeThreadKey = threadKey, roomAvatarEmoji = "👥") }
        }
        rememberThreadPreview(incoming.text, incoming.timestamp, explicitKey = threadKey, explicitTitle = title)
        playSound(SoundEffect.MessageIncoming)
    }

    private fun handleGroupLeavePayload(payload: JSONObject) {
        val groupId = payload.optString("groupId").ifBlank { return }
        val peerId = payload.optString("peerId").ifBlank { return }
        val threadKey = groupThreadKey(groupId)
        val existing = groupDefinitions[groupId]
        val title = payload.optString("groupTitle").ifBlank { existing?.title ?: "Группа" }
        val ownerPeerId = payload.optString("ownerPeerId").ifBlank { existing?.ownerPeerId ?: return }
        val members = parseGroupMembers(payload)
        val admins = parseGroupAdmins(payload, ownerPeerId)
        val group = if (existing == null || existing.ownerPeerId != ownerPeerId) {
            ChatGroupDefinition(groupId, title, members, ownerPeerId, admins).also { groupDefinitions[groupId] = it }
        } else {
            existing.title = title
            existing.members.clear(); existing.members.addAll(members)
            existing.adminPeerIds.clear(); existing.adminPeerIds.addAll(admins)
            existing
        }
        val senderLabel = payload.optString("senderLabel").ifBlank { resolvePeerTitle(peerId, null, null) }
        val system = MessageUi(id = UUID.randomUUID().toString(), text = "$senderLabel покинул группу", timestamp = timeNow(), type = MessageType.System, routeLabel = payload.optString("routeLabel").ifBlank { null })
        val history = threadHistories.getOrPut(threadKey) { mutableListOf() }
        history += system
        if (_connectionState.value.localPeerId == peerId || _connectionState.value.localPeerId !in group.members) {
            groupDefinitions.remove(groupId)
            threadSnapshots.remove(threadKey)
            if (_chatState.value.activeThreadKey == threadKey) {
                _chatState.update { it.copy(isGroupChat = false, groupId = null, groupOwnerPeerId = null, groupMembers = emptyList(), groupAdminPeerIds = emptySet(), roomTitle = "Чаты", roomSubtitle = null, messages = emptyList(), activeThreadKey = null) }
            }
            publishPeers()
            return
        }
        if (_chatState.value.activeThreadKey == threadKey) {
            _chatState.update { it.copy(messages = history.toList(), groupOwnerPeerId = group.ownerPeerId, groupMembers = resolveGroupMemberLabels(group), groupAdminPeerIds = group.adminPeerIds.toSet(), roomSubtitle = "${group.members.size} участников", roomTitle = group.title) }
        }
        rememberThreadPreview(system.text, system.timestamp, explicitKey = threadKey, explicitTitle = group.title)
        publishPeers()
    }

    private fun handleGroupUpdateSettingsPayload(payload: JSONObject, routeLabel: String?) {
        val groupId = payload.optString("groupId").ifBlank { return }
        val title = payload.optString("groupTitle").ifBlank { "Группа" }
        val ownerPeerId = payload.optString("ownerPeerId").ifBlank { return }
        val members = parseGroupMembers(payload)
        val admins = parseGroupAdmins(payload, ownerPeerId)
        val existing = groupDefinitions[groupId]
        val group = if (existing == null || existing.ownerPeerId != ownerPeerId) {
            ChatGroupDefinition(groupId, title, members, ownerPeerId, admins).also { groupDefinitions[groupId] = it }
        } else {
            existing.title = title
            existing.members.clear(); existing.members.addAll(members)
            existing.adminPeerIds.clear(); existing.adminPeerIds.addAll(admins)
            existing
        }
        val threadKey = groupThreadKey(groupId)
        val actor = payload.optString("actorLabel").ifBlank { "Участник" }
        val system = MessageUi(id = UUID.randomUUID().toString(), text = "$actor обновил настройки группы", timestamp = timeNow(), type = MessageType.System, routeLabel = routeLabel)
        val history = threadHistories.getOrPut(threadKey) { mutableListOf() }
        history += system
        threadSnapshots[threadKey] = (threadSnapshots[threadKey] ?: ChatThreadUi(key = threadKey, title = group.title, preview = system.text, timestamp = system.timestamp, lastMessageEpoch = System.currentTimeMillis(), avatarEmoji = "👥")).copy(title = group.title, preview = system.text, timestamp = system.timestamp, lastMessageEpoch = System.currentTimeMillis(), avatarEmoji = "👥")
        val localPeerId = _connectionState.value.localPeerId
        if (localPeerId !in group.members) {
            groupDefinitions.remove(groupId)
            threadSnapshots.remove(threadKey)
            if (_chatState.value.activeThreadKey == threadKey) {
                _chatState.update { it.copy(isGroupChat = false, groupId = null, groupOwnerPeerId = null, groupMembers = emptyList(), groupAdminPeerIds = emptySet(), roomTitle = "Чаты", roomSubtitle = null, messages = emptyList(), activeThreadKey = null) }
            }
            publishPeers()
            return
        }
        if (_chatState.value.activeThreadKey == threadKey) {
            _chatState.update { it.copy(messages = history.toList(), groupOwnerPeerId = group.ownerPeerId, groupMembers = resolveGroupMemberLabels(group), groupAdminPeerIds = group.adminPeerIds.toSet(), roomTitle = group.title, roomSubtitle = "${group.members.size} участников") }
        }
        rememberThreadPreview(system.text, system.timestamp, explicitKey = threadKey, explicitTitle = group.title)
        publishPeers()
    }

    private fun handleRelayGroupUpdateSettingsPacket(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?) {
        val targetPeerId = envelope.targetPeerId ?: payload.optString("finalTargetPeerId").ifBlank { null }
        if (!rememberRelayPacketId(envelope.packetId)) return
        if (!targetPeerId.isNullOrBlank() && targetPeerId == _connectionState.value.localPeerId) {
            handleGroupUpdateSettingsPayload(payload, relayHost?.let { "через $it" })
            groupPacketsReceivedCounter += 1
            updateRelayDiagnostics()
            return
        }
        relayForwardPacket(
            envelope.copy(
                type = "RELAY_GROUP_UPDATE_SETTINGS_FORWARD",
                targetPeerId = targetPeerId ?: envelope.targetPeerId
            ),
            payload.apply {
                if (!targetPeerId.isNullOrBlank()) put("finalTargetPeerId", targetPeerId)
            },
            relayHost
        )
    }

    private fun handleRelayGroupInvitePacket(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?) {
        val targetPeerId = envelope.targetPeerId ?: payload.optString("finalTargetPeerId").ifBlank { null }
        if (!rememberRelayPacketId(envelope.packetId)) return
        if (!targetPeerId.isNullOrBlank() && targetPeerId == _connectionState.value.localPeerId) {
            handleGroupInvitePayload(payload, relayHost?.let { "через $it" })
            groupPacketsReceivedCounter += 1
            updateRelayDiagnostics()
            return
        }
        if (!relayForwardPacket(envelope, payload, relayHost)) {
            groupPacketsDroppedCounter += 1
            updateRelayDiagnostics()
        }
    }

    private fun handleRelayGroupMessagePacket(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?) {
        val targetPeerId = envelope.targetPeerId ?: payload.optString("finalTargetPeerId").ifBlank { null }
        if (!rememberRelayPacketId(envelope.packetId)) return
        if (!targetPeerId.isNullOrBlank() && targetPeerId == _connectionState.value.localPeerId) {
            handleGroupMessagePayload(payload, payload.optString("routeLabel").ifBlank { relayHost?.let { "через $it" } })
            groupPacketsReceivedCounter += 1
            updateRelayDiagnostics()
            return
        }
        if (!relayForwardPacket(envelope, payload, relayHost)) {
            groupPacketsDroppedCounter += 1
            updateRelayDiagnostics()
        }
    }

    private fun handleRelayGroupLeavePacket(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?) {
        val targetPeerId = envelope.targetPeerId ?: payload.optString("finalTargetPeerId").ifBlank { null }
        if (!rememberRelayPacketId(envelope.packetId)) return
        if (!targetPeerId.isNullOrBlank() && targetPeerId == _connectionState.value.localPeerId) {
            handleGroupLeavePayload(payload)
            groupPacketsReceivedCounter += 1
            updateRelayDiagnostics()
            return
        }
        if (!relayForwardPacket(envelope, payload, relayHost)) {
            groupPacketsDroppedCounter += 1
            updateRelayDiagnostics()
        }
    }

    private fun saveIncomingGroupAttachment(fileName: String, mimeType: String, base64Data: String): String? {
        return runCatching {
            val dir = File(appContext.cacheDir, "group_inbox").apply { mkdirs() }
            val safeName = fileName.ifBlank { "attachment_${System.currentTimeMillis()}" }.replace(Regex("[^A-Za-zА-Яа-я0-9._-]"), "_")
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType).orEmpty()
            val target = File(dir, if (safeName.contains('.')) "${System.currentTimeMillis()}_$safeName" else "${System.currentTimeMillis()}_${safeName}.${ext.ifBlank { "bin" }}")
            target.writeBytes(Base64.decode(base64Data, Base64.DEFAULT))
            Uri.fromFile(target).toString()
        }.getOrNull()
    }

    private fun handleGroupFilePayload(payload: JSONObject, routeLabel: String?) {
        val groupId = payload.optString("groupId").ifBlank { return }
        val title = payload.optString("groupTitle").ifBlank { "Группа" }
        val ownerPeerId = payload.optString("ownerPeerId")
        val members = parseGroupMembers(payload)
        val group = groupDefinitions[groupId] ?: ChatGroupDefinition(groupId, title, members, ownerPeerId, parseGroupAdmins(payload, ownerPeerId)).also { groupDefinitions[groupId] = it }
        val threadKey = groupThreadKey(groupId)
        val messageId = payload.optString("messageId").ifBlank { UUID.randomUUID().toString() }
        if (!rememberIncomingMessageId(messageId)) return
        val mimeType = payload.optString("mimeType").ifBlank { "application/octet-stream" }
        val fileName = payload.optString("fileName").ifBlank { if (mimeType.startsWith("audio/")) "voice_note" else "attachment" }
        val attachmentUri = saveIncomingGroupAttachment(fileName, mimeType, payload.optString("attachmentData"))
        val senderLabel = payload.optString("senderLabel").ifBlank { resolvePeerTitle(payload.optString("senderPeerId"), null, null) }
        val incoming = MessageUi(
            id = messageId,
            messageId = messageId,
            text = if (payload.optBoolean("isVoice", false) || mimeType.startsWith("audio/")) "Голосовое сообщение" else fileName,
            timestamp = timeNow(),
            type = MessageType.Incoming,
            senderLabel = senderLabel,
            attachmentName = fileName,
            attachmentMimeType = mimeType,
            attachmentUri = attachmentUri,
            attachmentSizeBytes = payload.optLong("fileSize").takeIf { it > 0L },
            isImageAttachment = mimeType.startsWith("image/"),
            isVoiceNote = payload.optBoolean("isVoice", false) || mimeType.startsWith("audio/"),
            voiceNoteDurationLabel = payload.optString("durationLabel").ifBlank { null },
            routeLabel = routeLabel
        )
        val history = threadHistories.getOrPut(threadKey) { mutableListOf() }
        history += incoming
        threadSnapshots[threadKey] = (threadSnapshots[threadKey] ?: ChatThreadUi(key = threadKey, title = title, preview = incoming.text, timestamp = incoming.timestamp, lastMessageEpoch = System.currentTimeMillis(), avatarEmoji = "👥")).copy(title = title, preview = incoming.text, timestamp = incoming.timestamp, lastMessageEpoch = System.currentTimeMillis(), avatarEmoji = "👥")
        if (_chatState.value.activeThreadKey == threadKey) {
            _chatState.update { it.copy(messages = history.toList(), isGroupChat = true, groupId = groupId, groupOwnerPeerId = group.ownerPeerId, groupMembers = resolveGroupMemberLabels(group), groupAdminPeerIds = group.adminPeerIds.toSet(), roomTitle = title, roomSubtitle = "${group.members.size} участников", activeThreadKey = threadKey, roomAvatarEmoji = "👥") }
        }
        rememberThreadPreview(incoming.text, incoming.timestamp, explicitKey = threadKey, explicitTitle = title)
        playSound(if (incoming.isVoiceNote) SoundEffect.MessageIncoming else SoundEffect.FileReceived)
    }

    private fun handleRelayGroupFilePacket(envelope: PacketEnvelope, payload: JSONObject, relayHost: String?) {
        val targetPeerId = envelope.targetPeerId ?: payload.optString("finalTargetPeerId").ifBlank { null }
        if (!rememberRelayPacketId(envelope.packetId)) return
        if (!targetPeerId.isNullOrBlank() && targetPeerId == _connectionState.value.localPeerId) {
            handleGroupFilePayload(payload, payload.optString("routeLabel").ifBlank { relayHost?.let { "через $it" } })
            groupPacketsReceivedCounter += 1
            updateRelayDiagnostics()
            return
        }
        if (!relayForwardPacket(envelope, payload, relayHost)) {
            groupPacketsDroppedCounter += 1
            updateRelayDiagnostics()
        }
    }

    private fun publishPeers() {
        val contacts = loadContacts(appContext)
        val peers = discoveredPeers.values
            .map { peer ->
                val contact = contacts.firstOrNull { it.peerId.isNotBlank() && it.peerId == peer.peerId }
                    ?: contacts.firstOrNull { it.host.isNotBlank() && it.host == peer.host }
                peer.copy(
                    title = contact?.displayName ?: peer.title,
                    isSavedContact = contact != null,
                    subtitle = if (contact?.publicNick?.isNullOrBlank() == false) {
                        "@${contact.publicNick} • ${peer.host}:${peer.port}"
                    } else {
                        peer.subtitle
                    },
                    avatarEmoji = contact?.avatarEmoji ?: peer.avatarEmoji,
                    trustStatus = contact?.trustStatus ?: peer.trustStatus,
                    fingerprint = contact?.fingerprint ?: peer.fingerprint,
                    trustWarning = contact?.trustWarning ?: peer.trustWarning,
                    shortAuthString = peer.shortAuthString,
                    publicKeyBase64 = contact?.publicKeyBase64 ?: peer.publicKeyBase64,
                    keyId = contact?.keyId ?: peer.keyId
                )
            }
            .sortedBy { it.title.lowercase() }

        val mergedContacts = contacts.map { contact ->
            val onlinePeer = peers.firstOrNull { peer -> peer.peerId == contact.peerId || peer.host == contact.host }
            contact.copy(
                isOnline = onlinePeer != null,
                host = onlinePeer?.host ?: contact.host,
                port = onlinePeer?.port ?: contact.port,
                publicNick = onlinePeer?.subtitle?.substringAfter("@", "")?.substringBefore(" •")?.takeIf { it.isNotBlank() }
                    ?: contact.publicNick,
                avatarEmoji = onlinePeer?.avatarEmoji ?: contact.avatarEmoji,
                trustStatus = if (contact.trustStatus == TrustStatus.Blocked) TrustStatus.Blocked else onlinePeer?.trustStatus ?: contact.trustStatus,
                fingerprint = onlinePeer?.fingerprint ?: contact.fingerprint,
                trustWarning = onlinePeer?.trustWarning ?: contact.trustWarning,
                publicKeyBase64 = onlinePeer?.publicKeyBase64 ?: contact.publicKeyBase64,
                keyId = onlinePeer?.keyId ?: contact.keyId
            )
        }.sortedBy { it.displayName.lowercase() }

        val threadMap = threadSnapshots.toMutableMap()
        mergedContacts.forEach { contact ->
            val key = contact.peerId.ifBlank { contact.host }
            if (key.isNotBlank()) {
                val existing = threadMap[key]
                threadMap[key] = (existing ?: ChatThreadUi(
                    key = key,
                    title = contact.displayName,
                    preview = "Пока нет сообщений",
                    timestamp = "",
                    lastMessageEpoch = 0L,
                    peerId = contact.peerId.takeIf { it.isNotBlank() },
                    host = contact.host.takeIf { it.isNotBlank() },
                    port = contact.port,
                    avatarEmoji = contact.avatarEmoji
                )).copy(
                    title = contact.displayName,
                    isOnline = contact.isOnline,
                    isSavedContact = true,
                    peerId = contact.peerId.takeIf { it.isNotBlank() },
                    host = contact.host.takeIf { it.isNotBlank() },
                    port = contact.port,
                    avatarEmoji = contact.avatarEmoji ?: existing?.avatarEmoji
                )
            }
        }
        peers.forEach { peer ->
            val key = peer.peerId.ifBlank { peer.host }
            if (key.isNotBlank()) {
                val existing = threadMap[key]
                threadMap[key] = (existing ?: ChatThreadUi(
                    key = key,
                    title = peer.title,
                    preview = "Доступен в сети",
                    timestamp = "",
                    lastMessageEpoch = 0L,
                    peerId = peer.peerId,
                    host = peer.host,
                    port = peer.port,
                    avatarEmoji = peer.avatarEmoji
                )).copy(
                    title = existing?.title ?: peer.title,
                    isOnline = true,
                    isSavedContact = peer.isSavedContact,
                    peerId = peer.peerId,
                    host = peer.host,
                    port = peer.port,
                    isTyping = existing?.isTyping == true && key == currentThreadKey && _chatState.value.typingLabel != null,
                    avatarEmoji = peer.avatarEmoji ?: existing?.avatarEmoji
                )
            }
        }

        val threads = threadMap.values
            .filter { it.title.isNotBlank() }
            .sortedByDescending { it.lastMessageEpoch }

        _connectionState.update { it.copy(discoveredPeers = peers) }
        updateRelayDiagnostics()
        val onlineCount = (mergedContacts.count { it.isOnline } + peers.count { !it.isSavedContact }).coerceAtLeast(peers.size)
        _contactsState.update {
            it.copy(
                contacts = mergedContacts,
                discoveredPeers = peers,
                activeChatTitle = _chatState.value.roomTitle.takeIf { title -> _chatState.value.messages.isNotEmpty() && title.isNotBlank() },
                activeChatPreview = _chatState.value.messages.lastOrNull()?.text,
                threads = threads,
                onlineCount = onlineCount,
                localAvatarEmoji = _settingsState.value.avatarEmoji
            )
        }
        persistThreadSnapshots()
    }

    private fun rememberThreadPreview(
        preview: String,
        timestamp: String,
        explicitKey: String? = currentThreadKey,
        explicitTitle: String? = _chatState.value.roomTitle,
        explicitEpoch: Long = System.currentTimeMillis()
    ) {
        val key = explicitKey?.takeIf { it.isNotBlank() } ?: explicitTitle?.takeIf { it.isNotBlank() } ?: return
        val old = threadSnapshots[key]
        threadSnapshots[key] = ChatThreadUi(
            key = key,
            title = explicitTitle?.takeIf { it.isNotBlank() } ?: old?.title ?: key,
            preview = preview,
            timestamp = timestamp,
            lastMessageEpoch = max(explicitEpoch, old?.lastMessageEpoch ?: 0L),
            unreadCount = 0,
            isOnline = old?.isOnline ?: isSessionActive(),
            isTyping = false,
            isSavedContact = old?.isSavedContact ?: findContactByPeerId(remotePeerId) != null,
            peerId = if (key.startsWith("group:")) null else remotePeerId ?: old?.peerId,
            host = if (key.startsWith("group:")) null else currentSocket?.inetAddress?.hostAddress ?: old?.host,
            port = DEFAULT_PORT,
            avatarEmoji = if (key.startsWith("group:")) "👥" else _chatState.value.roomAvatarEmoji ?: old?.avatarEmoji
        )
        publishPeers()
    }

    private fun replaceMessage(updated: MessageUi) {
        _chatState.update { state ->
            state.copy(messages = state.messages.map { if (it.messageId == updated.messageId) updated else it })
        }
        syncCurrentThreadMessages()
        val preview = _chatState.value.messages.lastOrNull()?.let { currentPreviewFor(it) } ?: "Пустой чат"
        rememberThreadPreview(preview, _chatState.value.messages.lastOrNull()?.timestamp ?: timeNow())
    }

    private fun removeMessageLocally(messageId: String) {
        _chatState.update { state ->
            state.copy(messages = state.messages.filterNot { it.messageId == messageId })
        }
        syncCurrentThreadMessages()
        val last = _chatState.value.messages.lastOrNull()
        rememberThreadPreview(last?.let { currentPreviewFor(it) } ?: "Пустой чат", last?.timestamp ?: timeNow())
    }

    private fun updateMessageById(messageId: String, transform: (MessageUi) -> MessageUi) {
        val current = _chatState.value.messages.firstOrNull { it.messageId == messageId } ?: return
        replaceMessage(transform(current))
    }

    private fun syncCurrentThreadMessages() {
        val key = currentThreadKey ?: return
        threadHistories[key] = _chatState.value.messages.takeLast(300).toMutableList()
        persistThreadHistories()
    }

    private fun currentPreviewFor(message: MessageUi): String {
        return when {
            message.isDeleted -> "Сообщение удалено"
            message.text.isBlank() -> "Пустой чат"
            else -> message.text
        }
    }

    private fun updateConnectionLabels(label: String) {
        val trustSuffix = when (remoteTrustStatus) {
            TrustStatus.Verified -> " • Доверено"
            TrustStatus.Unverified -> if (label == "Онлайн") " • Проверить ключ" else ""
            TrustStatus.Suspicious -> " • Ключ изменился"
            TrustStatus.Blocked -> " • Заблокировано"
        }
        val resolvedLabel = label + trustSuffix
        _connectionState.update { it.copy(connectionLabel = resolvedLabel) }
        _chatState.update { it.copy(connectionLabel = resolvedLabel, isPeerOnline = true) }
        publishPeers()
    }

    private fun markMessageDelivery(messageId: String, status: MessageDeliveryStatus) {
        _chatState.update { state ->
            state.copy(
                messages = state.messages.map { message ->
                    if (message.messageId == messageId) message.copy(deliveryStatus = status) else message
                }
            )
        }
        val preview = _chatState.value.messages.lastOrNull()?.let { currentPreviewFor(it) } ?: return
        rememberThreadPreview(preview, _chatState.value.messages.lastOrNull()?.timestamp ?: timeNow())
    }

    private fun scheduleMessageAckRetry(messageId: String, packetProvider: () -> String) {
        pendingMessageAcks[messageId]?.job?.cancel()
        val pending = PendingMessageAck(packetProvider = packetProvider)
        pending.job = scope.launch {
            while (pending.attempts < DeliveryPolicy.MESSAGE_ACK_RETRY_LIMIT) {
                delay(DeliveryPolicy.MESSAGE_ACK_DELAY_MS)
                if (!pendingMessageAcks.containsKey(messageId)) return@launch
                val delivered = _chatState.value.messages.any { it.messageId == messageId && it.deliveryStatus == MessageDeliveryStatus.Delivered }
                if (delivered) {
                    pendingMessageAcks.remove(messageId)
                    return@launch
                }
                pending.attempts += 1
                val resent = sendPacket(packetProvider())
                log(LogType.Chat, "Повторная отправка сообщения $messageId, попытка ${pending.attempts}")
                if (!resent) break
            }
            markMessageDelivery(messageId, MessageDeliveryStatus.Failed)
            pendingMessageAcks.remove(messageId)
        }
        pendingMessageAcks[messageId] = pending
    }

    private fun rememberIncomingMessageId(messageId: String): Boolean {
        synchronized(deliveredIncomingMessageIds) {
            if (deliveredIncomingMessageIds.contains(messageId)) return false
            deliveredIncomingMessageIds.add(messageId)
            while (deliveredIncomingMessageIds.size > 512) {
                val first = deliveredIncomingMessageIds.firstOrNull() ?: break
                deliveredIncomingMessageIds.remove(first)
            }
            return true
        }
    }

    private fun pushTypingState(active: Boolean) {
        if (!isSessionActive()) return
        if (active == localTypingActive && active) {
            typingHeartbeatJob?.cancel()
            typingHeartbeatJob = scope.launch {
                delay(1500)
                localTypingActive = false
                sendPacket(buildPacket(type = "TYPING", payload = mapOf("active" to false)), disconnectOnFailure = false)
            }
            return
        }
        localTypingActive = active
        scope.launch {
            sendPacket(buildPacket(type = "TYPING", payload = mapOf("active" to active)), disconnectOnFailure = false)
        }
        typingHeartbeatJob?.cancel()
        if (active) {
            typingHeartbeatJob = scope.launch {
                delay(1500)
                localTypingActive = false
                sendPacket(buildPacket(type = "TYPING", payload = mapOf("active" to false)), disconnectOnFailure = false)
            }
        }
    }

    private fun handleRemoteTyping(active: Boolean) {
        remoteTypingResetJob?.cancel()
        if (active) {
            _chatState.update { it.copy(typingLabel = "печатает…", isPeerOnline = true) }
            val key = currentThreadKey
            if (key != null) {
                threadSnapshots[key] = (threadSnapshots[key] ?: ChatThreadUi(key, _chatState.value.roomTitle, "", "", 0L)).copy(isTyping = true)
            }
            publishPeers()
            remoteTypingResetJob = scope.launch {
                delay(2000)
                handleRemoteTyping(false)
            }
        } else {
            _chatState.update { it.copy(typingLabel = null) }
            val key = currentThreadKey
            if (key != null && threadSnapshots[key] != null) {
                threadSnapshots[key] = threadSnapshots[key]!!.copy(isTyping = false)
            }
            publishPeers()
        }
    }

    private fun calculateFileChecksum(uri: Uri): String {
        val crc32 = CRC32()
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(FILE_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                crc32.update(buffer, 0, read)
            }
        }
        return crc32.value.toString(16)
    }

    private fun calculateFileChecksum(meta: UriMeta): String {
        meta.tempBytes?.let {
            val crc32 = CRC32()
            crc32.update(it, 0, it.size)
            return crc32.value.toString(16)
        }
        val source = meta.sourceUri ?: return "0"
        return calculateFileChecksum(source)
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifiManager.createMulticastLock("ladya_multicast_lock").apply {
            setReferenceCounted(true)
            acquire()
        }
        log(LogType.Network, "Multicast lock активирован")
    }

    private fun releaseMulticastLock() {
        runCatching {
            multicastLock?.takeIf { it.isHeld }?.release()
        }
        multicastLock = null
    }

    private fun safeStopDiscovery(listener: NsdManager.DiscoveryListener) {
        runCatching {
            nsdManager?.stopServiceDiscovery(listener)
        }
    }

    private fun readUriMeta(uri: Uri): UriMeta {
        var name = "selected_file"
        var size = 0L
        val resolver: ContentResolver = appContext.contentResolver
        resolver.query(uri, null, null, null, null)?.use { cursor: Cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
        if (size <= 0L) {
            size = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length.takeIf { it > 0L } ?: 0L
                } ?: 0L
            }.getOrDefault(size)
        }
        val mimeType = resolver.getType(uri) ?: URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        if (!mimeType.startsWith("image/")) {
            return UriMeta(name = name, size = size, mimeType = mimeType, sourceUri = uri)
        }
        return compressImageIfNeeded(uri, name, size, mimeType)
    }

    private fun compressImageIfNeeded(uri: Uri, fileName: String, originalSize: Long, mimeType: String): UriMeta {
        val originalBitmap = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return UriMeta(name = fileName, size = originalSize, mimeType = mimeType, sourceUri = uri)

        val maxDim = max(originalBitmap.width, originalBitmap.height)
        val scale = if (maxDim > 1600) 1600f / maxDim.toFloat() else 1f
        val targetWidth = (originalBitmap.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (originalBitmap.height * scale).toInt().coerceAtLeast(1)
        val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(originalBitmap, targetWidth, targetHeight, true) else originalBitmap

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, stream)
        val compressedBytes = stream.toByteArray()
        if (bitmap !== originalBitmap) originalBitmap.recycle()
        bitmap.recycle()

        if (compressedBytes.size.toLong() >= originalSize * 0.95 && mimeType == "image/jpeg" && scale >= 1f) {
            return UriMeta(name = fileName, size = originalSize, mimeType = mimeType, sourceUri = uri)
        }
        val normalizedName = fileName.substringBeforeLast('.', fileName) + ".jpg"
        return UriMeta(
            name = normalizedName,
            size = compressedBytes.size.toLong(),
            mimeType = "image/jpeg",
            sourceUri = uri,
            compressedFromSize = originalSize,
            tempBytes = compressedBytes
        )
    }

    private fun createDownloadDestinationUri(fileName: String, mimeType: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Ladya")
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
        }
        return runCatching {
            appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
    }

    private fun readAttribute(serviceInfo: NsdServiceInfo, key: String): String {
        val value = serviceInfo.attributes[key] ?: return ""
        return runCatching { String(value, Charsets.UTF_8) }.getOrDefault("")
    }

    private fun readPeerId(serviceInfo: NsdServiceInfo): String? {
        return readAttribute(serviceInfo, "pid").ifBlank { readAttribute(serviceInfo, "peerId") }.ifBlank { null }
    }

    private fun parsePeerIdFromServiceName(serviceName: String): String {
        return serviceName.substringAfterLast('-').ifBlank { serviceName }
    }

    private fun parseDeviceNameFromServiceName(serviceName: String): String {
        val withoutPrefix = serviceName.removePrefix("${ProtocolConstants.PROTOCOL_NAME}-")
        return withoutPrefix.substringBeforeLast('-').replace('_', ' ').ifBlank { withoutPrefix }
    }

    private fun createServiceName(deviceName: String, peerId: String): String {
        val cleaned = sanitizeForServiceName(deviceName.ifBlank { defaultDeviceName(peerId) })
        return "${ProtocolConstants.PROTOCOL_NAME}-$cleaned-$peerId".take(60)
    }

    private fun sanitizeForServiceName(value: String): String {
        return value.trim().ifBlank { "Ladya" }
            .map { ch -> if (ch.isLetterOrDigit()) ch else '_' }
            .joinToString(separator = "")
            .take(32)
            .ifBlank { "Ladya" }
    }

    private fun buildPeerSubtitle(publicNick: String?, deviceName: String?, host: String): String {
        val nickPart = publicNick?.takeIf { it.isNotBlank() }?.let { "@$it" }
        val devicePart = deviceName?.takeIf { it.isNotBlank() }
        return listOfNotNull(nickPart, devicePart, host.takeIf { it.isNotBlank() })
            .joinToString(" • ")
    }

    private fun resolvePeerTitle(peerId: String, deviceName: String?, publicNick: String?): String {
        val saved = findContactByPeerId(peerId)
        return saved?.displayName
            ?: publicNick?.takeIf { it.isNotBlank() }
            ?: deviceName?.takeIf { it.isNotBlank() }
            ?: peerId
    }

    private fun resolveConnectedPeerTitle(host: String?): String? {
        if (host.isNullOrBlank()) return _connectionState.value.connectedPeerTitle
        val contact = loadContacts(appContext).firstOrNull {
            it.host == host || (it.peerId.isNotBlank() && it.peerId == remotePeerId)
        }
        return contact?.displayName
            ?: remotePublicNick?.takeIf { it.isNotBlank() }
            ?: remoteDeviceName?.takeIf { it.isNotBlank() }
            ?: remotePeerId?.let { discoveredPeers[it]?.title }
            ?: discoveredPeers.values.firstOrNull { it.host == host }?.title
            ?: host
    }

    private fun defaultDeviceName(peerId: String): String = "Узел-$peerId"

    private fun trustScore(status: TrustStatus): Int = when (status) {
        TrustStatus.Verified -> 300
        TrustStatus.Unverified -> 200
        TrustStatus.Suspicious -> 50
        TrustStatus.Blocked -> -1000
    }

    private fun relaySecurityNote(status: TrustStatus): String? = when (status) {
        TrustStatus.Verified -> null
        TrustStatus.Unverified -> "Маршрут идёт через неподтверждённый узел"
        TrustStatus.Suspicious -> "Маршрут идёт через подозрительный узел"
        TrustStatus.Blocked -> "Маршрут через этот узел запрещён"
    }

    private fun relayRouteSuffix(status: TrustStatus): String? = when (status) {
        TrustStatus.Verified -> "проверенный"
        TrustStatus.Unverified -> "непроверенный"
        TrustStatus.Suspicious -> "подозрительный"
        TrustStatus.Blocked -> "заблокирован"
    }

    private fun routePreferred(status: TrustStatus): Boolean = status == TrustStatus.Verified

    private fun resolveRecoveryCandidate(): RecoveryCandidate? {
        val (savedPeerId, savedHost, savedPort) = loadReconnectTarget()
        val candidates = discoveredPeers.values
            .filter { it.host.isNotBlank() && it.trustStatus != TrustStatus.Blocked }
            .sortedWith(
                compareByDescending<DiscoveredPeerUi> { trustScore(it.trustStatus) }
                    .thenByDescending { peerLastSeenAt[it.peerId] ?: 0L }
                    .thenBy { it.title.lowercase(Locale.getDefault()) }
            )

        candidates.firstOrNull { !savedPeerId.isNullOrBlank() && it.peerId == savedPeerId }?.let {
            return RecoveryCandidate(it.host, if (it.port > 0) it.port else savedPort, it.peerId, it.title, "последний узел по peerId")
        }
        candidates.firstOrNull { !savedHost.isNullOrBlank() && it.host == savedHost }?.let {
            return RecoveryCandidate(it.host, if (it.port > 0) it.port else savedPort, it.peerId, it.title, "последний узел по host")
        }
        if (!savedHost.isNullOrBlank()) {
            return RecoveryCandidate(savedHost, savedPort, savedPeerId, savedHost, "последний сохранённый адрес")
        }
        candidates.firstOrNull()?.let {
            return RecoveryCandidate(it.host, if (it.port > 0) it.port else DEFAULT_PORT, it.peerId, it.title, "резервный сосед")
        }
        return null
    }

    private fun scheduleRecoveryReconnect(reason: String) {
        if (!initialized || _settingsState.value.backgroundServiceActive.not()) return
        if (recoveryReconnectJob?.isActive == true || isSessionActive() || _connectionState.value.isLoading) return
        recoveryReconnectJob = scope.launch {
            var attempt = 0
            var delayMs = RECOVERY_RECONNECT_BASE_DELAY_MS
            while (isActive && !isSessionActive() && _settingsState.value.backgroundServiceActive && attempt < RECOVERY_RECONNECT_MAX_ATTEMPTS) {
                val candidate = resolveRecoveryCandidate()
                if (candidate == null) {
                    log(LogType.Network, "Восстановление сети ожидает появления доступного узла")
                    delay(delayMs)
                    delayMs = min(delayMs * 2, RECOVERY_RECONNECT_MAX_DELAY_MS)
                    attempt++
                    continue
                }
                attempt++
                remotePeerId = candidate.peerId
                _chatState.update { state ->
                    state.copy(
                        typingLabel = null,
                        connectionLabel = "Восстановление сети: попытка $attempt/${RECOVERY_RECONNECT_MAX_ATTEMPTS}"
                    )
                }
                log(LogType.Network, "Восстановление сети: попытка $attempt → ${candidate.title} (${candidate.reason}). Причина: $reason")
                connectTo(candidate.host, candidate.port, "автовосстановление сети")
                delay(delayMs)
                delayMs = min(delayMs * 2, RECOVERY_RECONNECT_MAX_DELAY_MS)
            }
            if (!isSessionActive()) {
                log(LogType.Network, "Автовосстановление завершено без активной сессии. Узел остаётся в режиме ожидания.")
            }
        }
    }

    private fun localDisplayName(): String {
        return _settingsState.value.publicNick.trim().ifBlank {
            _settingsState.value.localDeviceName.trim().ifBlank { _connectionState.value.localPeerId }
        }
    }

    private fun rememberReconnectTarget(peerId: String?, host: String?, port: Int) {
        if (!initialized) return
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LAST_CONNECTED_PEER_ID, peerId)
            .putString(PREF_LAST_CONNECTED_HOST, host)
            .putInt(PREF_LAST_CONNECTED_PORT, port)
            .apply()
    }

    private fun loadReconnectTarget(): Triple<String?, String?, Int> {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Triple(
            prefs.getString(PREF_LAST_CONNECTED_PEER_ID, null),
            prefs.getString(PREF_LAST_CONNECTED_HOST, null),
            prefs.getInt(PREF_LAST_CONNECTED_PORT, DEFAULT_PORT)
        )
    }

    private fun maybeAutoReconnect(peer: DiscoveredPeerUi) {
        if (!initialized || canUseControlChannel() || _connectionState.value.isLoading) return
        val (savedPeerId, savedHost, savedPort) = loadReconnectTarget()
        val shouldReconnect =
            (!savedPeerId.isNullOrBlank() && peer.peerId == savedPeerId) ||
                (!savedHost.isNullOrBlank() && peer.host == savedHost)
        if (!shouldReconnect) return
        val now = System.currentTimeMillis()
        if (now - lastAutoReconnectAttemptAt < AUTO_RECONNECT_COOLDOWN_MS) return
        lastAutoReconnectAttemptAt = now
        log(LogType.Network, "Автопереподключение к ${peer.title}")
        remotePeerId = peer.peerId
        _connectionState.update { it.copy(shouldAutoOpenChat = false) }
        connectTo(peer.host, if (peer.port > 0) peer.port else savedPort, peer.title)
    }

    private fun findLocalIpAddress(): String? {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .flatMap { networkInterface -> Collections.list(networkInterface.inetAddresses) }
                .firstOrNull { address -> !address.isLoopbackAddress && address is Inet4Address }
                ?.hostAddress
        }.getOrNull()
    }

    private fun getOrCreatePeerId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_PEER_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val newId = UUID.randomUUID().toString().take(8)
        prefs.edit().putString(PREF_PEER_ID, newId).apply()
        return newId
    }

    private fun getSavedPublicNick(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_PUBLIC_NICK, "")
            .orEmpty()
    }

    private fun getSavedAvatarEmoji(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_AVATAR_EMOJI, DEFAULT_AVATAR_EMOJI)
            .orEmpty()
            .ifBlank { DEFAULT_AVATAR_EMOJI }
    }

    private fun getSavedDeviceName(context: Context, peerId: String): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_DEVICE_NAME, defaultDeviceName(peerId))
            .orEmpty()
            .ifBlank { defaultDeviceName(peerId) }
    }

    private fun loadSoundSettings(context: Context): SoundSettingsUi {
        return SoundSettingsUi(
            masterEnabled = AppSoundManager.isMasterEnabled(context),
            incomingCallEnabled = AppSoundManager.isEnabled(context, SoundEffect.IncomingCall),
            outgoingCallEnabled = AppSoundManager.isEnabled(context, SoundEffect.OutgoingCall),
            callConnectedEnabled = AppSoundManager.isEnabled(context, SoundEffect.CallConnected),
            callEndedEnabled = AppSoundManager.isEnabled(context, SoundEffect.CallEnded),
            messageIncomingEnabled = AppSoundManager.isEnabled(context, SoundEffect.MessageIncoming),
            messageOutgoingEnabled = AppSoundManager.isEnabled(context, SoundEffect.MessageOutgoing),
            messageErrorEnabled = AppSoundManager.isEnabled(context, SoundEffect.MessageError),
            fileReceivedEnabled = AppSoundManager.isEnabled(context, SoundEffect.FileReceived),
            fileSentEnabled = AppSoundManager.isEnabled(context, SoundEffect.FileSent),
            deviceFoundEnabled = AppSoundManager.isEnabled(context, SoundEffect.DeviceFound),
            deviceVerifiedEnabled = AppSoundManager.isEnabled(context, SoundEffect.DeviceVerified),
            deviceWarningEnabled = AppSoundManager.isEnabled(context, SoundEffect.DeviceWarning),
            notificationSoftEnabled = AppSoundManager.isEnabled(context, SoundEffect.NotificationSoft)
        )
    }

    private fun persistSoundSettings(settings: SoundSettingsUi) {
        AppSoundManager.setMasterEnabled(appContext, settings.masterEnabled)
        AppSoundManager.setEnabled(appContext, SoundEffect.IncomingCall, settings.incomingCallEnabled)
        AppSoundManager.setEnabled(appContext, SoundEffect.OutgoingCall, settings.outgoingCallEnabled)
        AppSoundManager.setEnabled(appContext, SoundEffect.CallConnected, settings.callConnectedEnabled)
        AppSoundManager.setEnabled(appContext, SoundEffect.CallEnded, settings.callEndedEnabled)
        AppSoundManager.setEnabled(appContext, SoundEffect.MessageIncoming, settings.messageIncomingEnabled)
        AppSoundManager.setEnabled(appContext, SoundEffect.MessageOutgoing, settings.messageOutgoingEnabled)
        AppSoundManager.setEnabled(appContext, SoundEffect.MessageError, settings.messageErrorEnabled)
        AppSoundManager.setEnabled(appContext, SoundEffect.FileReceived, settings.fileReceivedEnabled)
        AppSoundManager.setEnabled(appContext, SoundEffect.FileSent, settings.fileSentEnabled)
        AppSoundManager.setEnabled(appContext, SoundEffect.DeviceFound, settings.deviceFoundEnabled)
        AppSoundManager.setEnabled(appContext, SoundEffect.DeviceVerified, settings.deviceVerifiedEnabled)
        AppSoundManager.setEnabled(appContext, SoundEffect.DeviceWarning, settings.deviceWarningEnabled)
        AppSoundManager.setEnabled(appContext, SoundEffect.NotificationSoft, settings.notificationSoftEnabled)
    }

    private fun playSound(effect: SoundEffect) {
        AppSoundManager.play(appContext, effect)
    }

    private fun startLoopSound(effect: SoundEffect) {
        AppSoundManager.startLoop(appContext, effect)
    }

    private fun stopLoopSound() {
        AppSoundManager.stopLoop()
    }

    private fun loadPersistedRoomTitle(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_CHAT_ROOM_TITLE, "Комната")
            .orEmpty()
            .ifBlank { "Комната" }
    }

    private fun parseMessagesArray(array: JSONArray): List<MessageUi> {
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    MessageUi(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        messageId = item.optString("messageId").ifBlank { item.optString("id").ifBlank { UUID.randomUUID().toString() } },
                        text = item.optString("text"),
                        timestamp = item.optString("timestamp"),
                        type = runCatching { MessageType.valueOf(item.optString("type")) }.getOrDefault(MessageType.System),
                        senderLabel = item.optString("senderLabel").ifBlank { null },
                        sentAtMillis = item.optLong("sentAtMillis", System.currentTimeMillis()),
                        deliveryStatus = item.optString("deliveryStatus").takeIf { it.isNotBlank() }?.let {
                            runCatching { MessageDeliveryStatus.valueOf(it) }.getOrNull()
                        },
                        isEdited = item.optBoolean("isEdited", false),
                        isDeleted = item.optBoolean("isDeleted", false),
                        attachmentName = item.optString("attachmentName").ifBlank { null },
                        attachmentMimeType = item.optString("attachmentMimeType").ifBlank { null },
                        attachmentUri = item.optString("attachmentUri").ifBlank { null },
                        attachmentSizeBytes = item.optLong("attachmentSizeBytes").takeIf { it > 0L },
                        isImageAttachment = item.optBoolean("isImageAttachment", false)
                    )
                )
            }
        }
    }

    private fun loadPersistedThreadHistories(context: Context): Map<String, List<MessageUi>> {
        return databaseHelper.loadThreadHistories()
    }

    private fun persistThreadHistories() {
        if (!initialized) return
        threadHistories.forEach { (key, messages) ->
            databaseHelper.replaceThreadMessages(key, messages)
        }
        val dbKeys = databaseHelper.loadThreadHistories().keys
        dbKeys.filter { it !in threadHistories.keys }.forEach(databaseHelper::deleteThread)
    }

    private fun persistChatHistory(state: ChatUiState) {
        if (!initialized) return
        val key = currentThreadKey ?: resolveThreadKey(remotePeerId, currentSocket?.inetAddress?.hostAddress, state.roomTitle)
        threadHistories[key] = state.messages.takeLast(300).toMutableList()
        databaseHelper.replaceThreadMessages(key, state.messages.takeLast(300))
        persistThreadHistories()
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_CHAT_ROOM_TITLE, state.roomTitle)
            .apply()
        _contactsState.update {
            it.copy(
                activeChatTitle = state.roomTitle.takeIf { title -> state.messages.isNotEmpty() && title.isNotBlank() },
                activeChatPreview = state.messages.lastOrNull()?.text
            )
        }
    }

    private fun loadThreadSnapshots(context: Context): List<ChatThreadUi> {
        return databaseHelper.loadThreadSnapshots()
    }

    private fun persistThreadSnapshots() {
        if (!initialized) return
        databaseHelper.saveThreadSnapshots(threadSnapshots.values)
    }

    private fun loadContacts(context: Context): List<ContactUi> {
        return databaseHelper.loadContacts()
    }

    private fun saveContacts(contacts: List<ContactUi>) {
        databaseHelper.saveContacts(contacts)
        publishPeers()
    }

    private fun findContactByPeerId(peerId: String?): ContactUi? {
        if (peerId.isNullOrBlank()) return null
        return loadContacts(appContext).firstOrNull { it.peerId == peerId }
    }

    private fun findContactByKeyId(keyId: String?): ContactUi? {
        if (keyId.isNullOrBlank()) return null
        return loadContacts(appContext).firstOrNull { it.keyId == keyId }
    }

    private fun updateContactIdentity(
        peerId: String?,
        publicNick: String?,
        host: String?,
        avatarEmoji: String? = null,
        trustStatus: TrustStatus? = null,
        fingerprint: String? = null,
        trustWarning: String? = null,
        publicKeyBase64: String? = null,
        keyId: String? = publicKeyBase64?.let(DeviceIdentityManager::fullKeyIdForPublicKey)
    ) {
        if (peerId.isNullOrBlank() && host.isNullOrBlank()) return
        val contacts = loadContacts(appContext).toMutableList()
        val index = contacts.indexOfFirst {
            (!keyId.isNullOrBlank() && it.keyId == keyId) ||
            (!peerId.isNullOrBlank() && it.peerId == peerId) ||
            (host != null && it.host == host)
        }
        if (index >= 0) {
            val existing = contacts[index]
            contacts[index] = existing.copy(
                publicNick = publicNick?.takeIf { it.isNotBlank() } ?: existing.publicNick,
                host = host ?: existing.host,
                avatarEmoji = avatarEmoji ?: existing.avatarEmoji,
                trustStatus = trustStatus ?: existing.trustStatus,
                fingerprint = fingerprint ?: existing.fingerprint,
                trustWarning = trustWarning,
                publicKeyBase64 = publicKeyBase64 ?: existing.publicKeyBase64,
                keyId = keyId ?: existing.keyId
            )
            saveContacts(contacts)
        }
    }

    private fun migrateLegacyJsonStorageIfNeeded() {
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (databaseHelper.loadContacts().isEmpty()) {
            val raw = prefs.getString(PREF_CONTACTS_JSON, null).orEmpty()
            if (raw.isNotBlank()) {
                val migrated = runCatching {
                    val array = JSONArray(raw)
                    buildList {
                        for (index in 0 until array.length()) {
                            val item = array.getJSONObject(index)
                            add(
                                ContactUi(
                                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                                    peerId = item.optString("peerId"),
                                    displayName = item.optString("displayName"),
                                    host = item.optString("host"),
                                    port = item.optInt("port", DEFAULT_PORT),
                                    publicNick = item.optString("publicNick").ifBlank { null },
                                    avatarEmoji = item.optString("avatarEmoji").ifBlank { null },
                                    trustStatus = item.optString("trustStatus").takeIf { it.isNotBlank() }?.let { value -> runCatching { TrustStatus.valueOf(value) }.getOrDefault(TrustStatus.Unverified) } ?: TrustStatus.Unverified,
                                    fingerprint = item.optString("fingerprint").ifBlank { null },
                                    trustWarning = item.optString("trustWarning").ifBlank { null },
                                    publicKeyBase64 = item.optString("publicKeyBase64").ifBlank { null },
                                    keyId = item.optString("keyId").ifBlank { null }
                                )
                            )
                        }
                    }
                }.getOrDefault(emptyList())
                if (migrated.isNotEmpty()) databaseHelper.saveContacts(migrated)
            }
        }
        if (databaseHelper.loadThreadSnapshots().isEmpty()) {
            val raw = prefs.getString(PREF_CHAT_THREADS_JSON, null).orEmpty()
            if (raw.isNotBlank()) {
                val migrated = runCatching {
                    val array = JSONArray(raw)
                    buildList {
                        for (index in 0 until array.length()) {
                            val item = array.getJSONObject(index)
                            add(
                                ChatThreadUi(
                                    key = item.optString("key"),
                                    title = item.optString("title"),
                                    preview = item.optString("preview"),
                                    timestamp = item.optString("timestamp"),
                                    lastMessageEpoch = item.optLong("lastMessageEpoch", 0L),
                                    unreadCount = item.optInt("unreadCount", 0),
                                    isSavedContact = item.optBoolean("isSavedContact", false),
                                    peerId = item.optString("peerId").ifBlank { null },
                                    host = item.optString("host").ifBlank { null },
                                    port = item.optInt("port", DEFAULT_PORT),
                                    avatarEmoji = item.optString("avatarEmoji").ifBlank { null }
                                )
                            )
                        }
                    }
                }.getOrDefault(emptyList())
                if (migrated.isNotEmpty()) databaseHelper.saveThreadSnapshots(migrated)
            }
        }
        if (databaseHelper.loadThreadHistories().isEmpty()) {
            val historiesRaw = prefs.getString(PREF_CHAT_HISTORIES_JSON, null).orEmpty()
            if (historiesRaw.isNotBlank()) {
                runCatching {
                    val json = JSONObject(historiesRaw)
                    json.keys().forEach { key ->
                        databaseHelper.replaceThreadMessages(key, parseMessagesArray(json.getJSONArray(key)))
                    }
                }
            }
        }
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "—"
        return when {
            size >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f GB", size / 1024f / 1024f / 1024f)
            size >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", size / 1024f / 1024f)
            size >= 1024 -> String.format(Locale.US, "%.1f KB", size / 1024f)
            else -> "$size B"
        }
    }

    private fun timeNow(): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }

    private fun log(type: LogType, message: String) {
        Log.d(TAG, message)
        _logsState.update {
            it.copy(
                logs = listOf(
                    LogUi(
                        id = UUID.randomUUID().toString(),
                        time = timeNow(),
                        type = type,
                        message = message
                    )
                ) + it.logs.take(499)
            )
        }
    }
}
