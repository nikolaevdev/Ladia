package ru.mishanikolaev.ladya.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong

class AudioCallManager(
    private val context: Context,
    private val onError: (String) -> Unit
) {
    companion object {
        const val AUDIO_PORT = 1905
        private const val SAMPLE_RATE = 16_000
        private const val PACKET_BYTES = 640
    }

    data class Stats(
        val isRunning: Boolean = false,
        val packetsSent: Long = 0L,
        val packetsReceived: Long = 0L,
        val bytesSent: Long = 0L,
        val bytesReceived: Long = 0L,
        val listeningPort: Int = AUDIO_PORT
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var senderJob: Job? = null
    private var receiverJob: Job? = null
    private var socket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneState: Boolean = false
    private val packetsSent = AtomicLong(0)
    private val packetsReceived = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val bytesReceived = AtomicLong(0)
    @Volatile
    private var isMicMuted = false
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun start(remoteHost: String, remotePort: Int = AUDIO_PORT) {
        stop()
        configureAudioForCall()
        if (!hasMicrophonePermission()) {
            onError("Нет доступа к микрофону")
            return
        }
        val targetAddress = runCatching { InetAddress.getByName(remoteHost) }.getOrElse {
            onError("Не удалось определить адрес собеседника")
            return
        }
        try {
            socket = DatagramSocket(AUDIO_PORT).apply {
                soTimeout = 1500
                reuseAddress = true
            }
        } catch (e: SocketException) {
            onError("Не удалось открыть UDP-порт $AUDIO_PORT: ${e.message}")
            return
        }

        val minRecord = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(PACKET_BYTES * 4)
        val minTrack = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(PACKET_BYTES * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minRecord
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError("AudioRecord не инициализирован")
            stop()
            return
        }
        audioRecord = record

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minTrack)
            .build().apply {
                setVolume(AudioTrack.getMaxVolume())
            }
        audioTrack = track

        packetsSent.set(0)
        packetsReceived.set(0)
        bytesSent.set(0)
        bytesReceived.set(0)
        updateStats(true)

        receiverJob = scope.launch {
            val localSocket = socket ?: return@launch
            val localTrack = audioTrack ?: return@launch
            val buffer = ByteArray(PACKET_BYTES)
            runCatching { localTrack.play() }
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    localSocket.receive(packet)
                    localTrack.write(packet.data, 0, packet.length)
                    packetsReceived.incrementAndGet()
                    bytesReceived.addAndGet(packet.length.toLong())
                    updateStats(true)
                } catch (_: Exception) {
                }
            }
        }

        senderJob = scope.launch {
            val localSocket = socket ?: return@launch
            val localRecord = audioRecord ?: return@launch
            val payload = ByteArray(PACKET_BYTES)
            runCatching {
                localRecord.startRecording()
                track.play()
            }
            while (isActive) {
                val read = runCatching { localRecord.read(payload, 0, payload.size) }.getOrDefault(0)
                if (read > 0 && !isMicMuted) {
                    val packet = DatagramPacket(payload, read, targetAddress, remotePort)
                    runCatching { localSocket.send(packet) }
                    packetsSent.incrementAndGet()
                    bytesSent.addAndGet(read.toLong())
                    updateStats(true)
                }
            }
        }
    }

    fun stop() {
        senderJob?.cancel()
        receiverJob?.cancel()
        senderJob = null
        receiverJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.release() }
        runCatching { socket?.close() }
        isMicMuted = false
        restoreAudioAfterCall()
        audioRecord = null
        audioTrack = null
        socket = null
        updateStats(false)
    }


    fun setSpeakerEnabled(enabled: Boolean) {
        runCatching {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = enabled
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val targetType = if (enabled) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val target = audioManager.availableCommunicationDevices.firstOrNull { it.type == targetType }
                if (target != null) {
                    audioManager.setCommunicationDevice(target)
                } else if (!enabled) {
                    audioManager.clearCommunicationDevice()
                }
            }
        }
    }

    fun isSpeakerEnabled(): Boolean = audioManager.isSpeakerphoneOn

    fun setMicrophoneMuted(enabled: Boolean) {
        isMicMuted = enabled
    }

    fun isMicrophoneMuted(): Boolean = isMicMuted

    private fun configureAudioForCall() {
        previousMode = audioManager.mode
        previousSpeakerphoneState = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        setSpeakerEnabled(false)
    }

    private fun restoreAudioAfterCall() {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.isSpeakerphoneOn = previousSpeakerphoneState
            audioManager.mode = previousMode
        }
    }

    private fun updateStats(isRunning: Boolean) {
        _stats.value = Stats(
            isRunning = isRunning,
            packetsSent = packetsSent.get(),
            packetsReceived = packetsReceived.get(),
            bytesSent = bytesSent.get(),
            bytesReceived = bytesReceived.get(),
            listeningPort = AUDIO_PORT
        )
    }
}
