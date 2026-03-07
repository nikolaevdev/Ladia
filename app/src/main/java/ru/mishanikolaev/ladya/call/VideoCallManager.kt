package ru.mishanikolaev.ladya.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

class VideoCallManager(
    private val context: Context,
    private val onError: (String) -> Unit,
    private val onRecommendedProfileChanged: ((String) -> Unit)? = null
) {
    companion object {
        const val VIDEO_PORT = 1906
        private const val MAX_CHUNK_SIZE = 1200
        private const val HEADER_SIZE = 9
        private const val FRAME_ASSEMBLY_TTL_MS = 3000L
        private const val PROFILE_REEVAL_INTERVAL_MS = 2400L
        private const val PROFILE_HYSTERESIS_MS = 4200L
        private const val MAX_ASSEMBLIES = 18
        private val ANALYSIS_TARGET_SIZE = Size(640, 480)
    }

    data class Stats(
        val isRunning: Boolean = false,
        val framesSent: Long = 0L,
        val framesReceived: Long = 0L,
        val bytesSent: Long = 0L,
        val bytesReceived: Long = 0L,
        val listeningPort: Int = VIDEO_PORT,
        val remoteFrame: Bitmap? = null,
        val isVideoMuted: Boolean = false,
        val adaptiveProfile: String = "HIGH",
        val targetFps: Int = 15,
        val jpegQuality: Int = 78,
        val isFrontCamera: Boolean = true,
        val effectiveFps: Int = 15,
        val receiveFps: Int = 0,
        val averageEncodeMs: Long = 0L,
        val averageFrameBytes: Long = 0L,
        val networkQualityLabel: String = "Нормально"
    )

    private data class FrameAssembly(
        val totalChunks: Int,
        val chunks: Array<ByteArray?>,
        var bytes: Int = 0,
        var lastUpdatedAt: Long = System.currentTimeMillis()
    )

    private enum class AdaptiveProfile(
        val wireName: String,
        val maxDimension: Int,
        val jpegQuality: Int,
        val minFrameIntervalMs: Long,
        val minKbps: Float,
        val minFps: Float,
        val qualityLabel: String
    ) {
        LOW("LOW", 320, 60, 125L, 110f, 5.5f, "Слабая сеть"),
        MEDIUM("MEDIUM", 480, 72, 92L, 220f, 8.5f, "Нестабильная сеть"),
        HIGH("HIGH", 640, 82, 66L, 360f, 11.0f, "Хорошая сеть");

        companion object {
            fun fromWireName(value: String?): AdaptiveProfile = entries.firstOrNull { it.wireName == value } ?: HIGH
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    private var socket: DatagramSocket? = null
    private var receiverJob: Job? = null
    private var senderJob: Job? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var boundPreviewView: PreviewView? = null
    private var boundLifecycleOwner: LifecycleOwner? = null
    @Volatile private var remoteAddress: InetAddress? = null
    @Volatile private var isVideoMuted: Boolean = false
    @Volatile private var useFrontCamera: Boolean = true
    @Volatile private var adaptiveProfile: AdaptiveProfile = AdaptiveProfile.HIGH
    @Volatile private var lastRecommendedProfileSent: String = AdaptiveProfile.HIGH.wireName
    @Volatile private var networkMonitorStartedAtMs: Long = 0L
    @Volatile private var lastProfileDecisionAtMs: Long = 0L
    @Volatile private var encodingBusy: Boolean = false
    @Volatile private var lastRenderedFrameId: Int = 0
    private val frameIdCounter = AtomicInteger(1)
    private val framesSent = AtomicLong(0L)
    private val framesReceived = AtomicLong(0L)
    private val bytesSent = AtomicLong(0L)
    private val bytesReceived = AtomicLong(0L)
    private val assemblies = ConcurrentHashMap<Int, FrameAssembly>()
    private val latestEncodedFrame = AtomicReference<ByteArray?>(null)
    private val encodedFrameSizeBytes = AtomicLong(0L)
    private val encodeTimeAvgMs = AtomicLong(0L)
    private val receiveFps = AtomicLong(0L)
    private val localFps = AtomicLong(0L)

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    fun bindPreview(previewView: PreviewView, lifecycleOwner: LifecycleOwner) {
        boundPreviewView = previewView
        boundLifecycleOwner = lifecycleOwner
        if (!hasCameraPermission()) return
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = runCatching { providerFuture.get() }.getOrNull() ?: return@addListener
            cameraProvider = provider
            val localPreview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val localAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(ANALYSIS_TARGET_SIZE)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            localAnalysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    if (isVideoMuted || encodingBusy) return@setAnalyzer
                    val profile = adaptiveProfile
                    val localTargetInterval = effectiveMinFrameIntervalMs(profile)
                    val lastLocalFps = localFps.get()
                    val localJpeg = imageProxyToJpeg(
                        image = image,
                        mirrorHorizontally = useFrontCamera,
                        maxDimension = profile.maxDimension,
                        jpegQuality = profile.jpegQuality,
                        localTargetInterval = localTargetInterval,
                        currentLocalFps = lastLocalFps.toInt()
                    ) ?: return@setAnalyzer
                    latestEncodedFrame.set(localJpeg)
                    encodedFrameSizeBytes.set(localJpeg.size.toLong())
                } catch (_: Exception) {
                } finally {
                    image.close()
                }
            }
            try {
                provider.unbindAll()
                val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.bindToLifecycle(lifecycleOwner, selector, localPreview, localAnalysis)
                preview = localPreview
                analysis = localAnalysis
                updateStats(socket != null)
            } catch (e: Exception) {
                onError("Не удалось запустить видеокамеру: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun switchCameraFacing() {
        useFrontCamera = !useFrontCamera
        val previewView = boundPreviewView
        val lifecycleOwner = boundLifecycleOwner
        if (previewView != null && lifecycleOwner != null && hasCameraPermission()) {
            bindPreview(previewView, lifecycleOwner)
        } else {
            updateStats(socket != null)
        }
    }

    fun isUsingFrontCamera(): Boolean = useFrontCamera

    fun start(remoteHost: String, remotePort: Int = VIDEO_PORT) {
        stopNetworkOnly()
        remoteAddress = runCatching { InetAddress.getByName(remoteHost) }.getOrElse {
            onError("Не удалось определить адрес видеоканала")
            null
        }
        if (remoteAddress == null) return
        try {
            socket = DatagramSocket(VIDEO_PORT).apply {
                reuseAddress = true
                soTimeout = 1200
            }
        } catch (e: SocketException) {
            onError("Не удалось открыть UDP-видеопорт $VIDEO_PORT: ${e.message}")
            return
        }
        framesSent.set(0)
        framesReceived.set(0)
        bytesSent.set(0)
        bytesReceived.set(0)
        encodeTimeAvgMs.set(0)
        localFps.set(0)
        receiveFps.set(0)
        latestEncodedFrame.set(null)
        encodedFrameSizeBytes.set(0)
        lastRenderedFrameId = 0
        networkMonitorStartedAtMs = System.currentTimeMillis()
        lastRecommendedProfileSent = adaptiveProfile.wireName
        lastProfileDecisionAtMs = 0L
        updateStats(true)
        startSenderLoop(remotePort)
        receiverJob = scope.launch {
            val localSocket = socket ?: return@launch
            val buffer = ByteArray(MAX_CHUNK_SIZE + HEADER_SIZE)
            var prevFrames = 0L
            var prevBytes = 0L
            var lastProbeAt = System.currentTimeMillis()
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    localSocket.receive(packet)
                    processIncomingPacket(packet.data, packet.length)
                    cleanupExpiredAssemblies()
                } catch (_: Exception) {
                    cleanupExpiredAssemblies()
                }
                val now = System.currentTimeMillis()
                if (now - lastProbeAt >= PROFILE_REEVAL_INTERVAL_MS) {
                    val framesNow = framesReceived.get()
                    val bytesNow = bytesReceived.get()
                    val deltaFrames = framesNow - prevFrames
                    val deltaBytes = bytesNow - prevBytes
                    prevFrames = framesNow
                    prevBytes = bytesNow
                    lastProbeAt = now
                    maybeRecommendProfile(deltaFrames, deltaBytes, now - lastProbeAt + PROFILE_REEVAL_INTERVAL_MS)
                }
            }
        }
    }

    private fun startSenderLoop(remotePort: Int) {
        senderJob?.cancel()
        senderJob = scope.launch {
            var loopStartedAt = SystemClock.elapsedRealtime()
            var prevSentFrames = 0L
            while (isActive) {
                val profile = adaptiveProfile
                val interval = effectiveMinFrameIntervalMs(profile)
                val startTick = SystemClock.elapsedRealtime()
                val frame = latestEncodedFrame.getAndSet(null)
                if (!isVideoMuted && frame != null) {
                    sendFrame(frame, remotePort)
                }
                val now = SystemClock.elapsedRealtime()
                if (now - loopStartedAt >= PROFILE_REEVAL_INTERVAL_MS) {
                    val sentNow = framesSent.get()
                    val delta = sentNow - prevSentFrames
                    prevSentFrames = sentNow
                    localFps.set((delta * 1000f / (now - loopStartedAt).coerceAtLeast(1)).roundToInt().toLong())
                    loopStartedAt = now
                    updateStats(true)
                }
                val spent = SystemClock.elapsedRealtime() - startTick
                delay((interval - spent).coerceAtLeast(8L))
            }
        }
    }

    fun stop() {
        stopNetworkOnly()
        runCatching { cameraProvider?.unbindAll() }
        boundPreviewView = null
        boundLifecycleOwner = null
        cameraProvider = null
        analysis = null
        preview = null
        _stats.value = Stats(isVideoMuted = isVideoMuted, remoteFrame = null, isFrontCamera = useFrontCamera)
    }

    private fun stopNetworkOnly() {
        receiverJob?.cancel()
        receiverJob = null
        senderJob?.cancel()
        senderJob = null
        runCatching { socket?.close() }
        socket = null
        assemblies.clear()
        latestEncodedFrame.set(null)
        updateStats(false)
    }

    fun setVideoMuted(enabled: Boolean) {
        isVideoMuted = enabled
        if (enabled) latestEncodedFrame.set(null)
        updateStats(socket != null)
    }

    fun applyNetworkProfile(profileName: String?) {
        val profile = AdaptiveProfile.fromWireName(profileName)
        adaptiveProfile = profile
        updateStats(socket != null)
    }

    fun currentAdaptiveProfile(): String = adaptiveProfile.wireName

    fun isVideoMuted(): Boolean = isVideoMuted

    private fun sendFrame(jpegBytes: ByteArray, remotePort: Int) {
        val localSocket = socket ?: return
        val target = remoteAddress ?: return
        val frameId = frameIdCounter.getAndIncrement()
        val totalChunks = ((jpegBytes.size + MAX_CHUNK_SIZE - 1) / MAX_CHUNK_SIZE).coerceAtLeast(1)
        var offset = 0
        for (chunkIndex in 0 until totalChunks) {
            val length = minOf(MAX_CHUNK_SIZE, jpegBytes.size - offset)
            val packetBytes = ByteBuffer.allocate(HEADER_SIZE + length)
                .put('V'.code.toByte())
                .putInt(frameId)
                .putShort(chunkIndex.toShort())
                .putShort(totalChunks.toShort())
                .put(jpegBytes, offset, length)
                .array()
            runCatching {
                localSocket.send(DatagramPacket(packetBytes, packetBytes.size, target, remotePort))
            }
            offset += length
        }
        framesSent.incrementAndGet()
        bytesSent.addAndGet(jpegBytes.size.toLong())
        updateStats(true)
    }

    private fun processIncomingPacket(data: ByteArray, length: Int) {
        if (length <= HEADER_SIZE || data[0] != 'V'.code.toByte()) return
        val buffer = ByteBuffer.wrap(data, 0, length)
        buffer.get()
        val frameId = buffer.int
        if (frameId < lastRenderedFrameId - 1) return
        val chunkIndex = buffer.short.toInt() and 0xffff
        val totalChunks = buffer.short.toInt() and 0xffff
        if (chunkIndex >= totalChunks || totalChunks <= 0) return
        val payload = ByteArray(length - HEADER_SIZE)
        buffer.get(payload)
        val assembly = assemblies.getOrPut(frameId) {
            if (assemblies.size >= MAX_ASSEMBLIES) {
                val oldestKey = assemblies.entries.minByOrNull { it.value.lastUpdatedAt }?.key
                if (oldestKey != null) assemblies.remove(oldestKey)
            }
            FrameAssembly(totalChunks, arrayOfNulls(totalChunks))
        }
        assembly.lastUpdatedAt = System.currentTimeMillis()
        if (assembly.chunks[chunkIndex] == null) {
            assembly.chunks[chunkIndex] = payload
            assembly.bytes += payload.size
        }
        if (assembly.chunks.all { it != null }) {
            val joined = ByteArray(assembly.bytes)
            var offset = 0
            assembly.chunks.forEach { chunk ->
                val bytes = chunk ?: return@forEach
                bytes.copyInto(joined, offset)
                offset += bytes.size
            }
            assemblies.remove(frameId)
            if (frameId < lastRenderedFrameId) return
            val bitmap = BitmapFactory.decodeByteArray(joined, 0, joined.size)
            if (bitmap != null) {
                lastRenderedFrameId = frameId
                framesReceived.incrementAndGet()
                bytesReceived.addAndGet(joined.size.toLong())
                updateStats(true, bitmap)
            }
        }
    }

    private fun updateStats(isRunning: Boolean, remoteFrame: Bitmap? = null) {
        val profile = adaptiveProfile
        val receiveFpsNow = receiveFps.get().toInt().coerceAtLeast(0)
        val localFpsNow = localFps.get().toInt().coerceAtLeast(0)
        val effectiveFps = (1000L / effectiveMinFrameIntervalMs(profile)).toInt().coerceAtLeast(1)
        _stats.value = _stats.value.copy(
            isRunning = isRunning,
            framesSent = framesSent.get(),
            framesReceived = framesReceived.get(),
            bytesSent = bytesSent.get(),
            bytesReceived = bytesReceived.get(),
            listeningPort = VIDEO_PORT,
            remoteFrame = remoteFrame ?: _stats.value.remoteFrame,
            isVideoMuted = isVideoMuted,
            adaptiveProfile = profile.wireName,
            targetFps = (1000L / profile.minFrameIntervalMs).toInt().coerceAtLeast(1),
            jpegQuality = profile.jpegQuality,
            isFrontCamera = useFrontCamera,
            effectiveFps = effectiveFps,
            receiveFps = receiveFpsNow,
            averageEncodeMs = encodeTimeAvgMs.get(),
            averageFrameBytes = encodedFrameSizeBytes.get(),
            networkQualityLabel = when {
                receiveFpsNow <= 0 && framesReceived.get() == 0L -> "Ожидание видеопотока"
                profile == AdaptiveProfile.HIGH && localFpsNow >= 10 && receiveFpsNow >= 10 -> "Хорошая сеть"
                profile == AdaptiveProfile.MEDIUM || receiveFpsNow in 6..9 -> "Нестабильная сеть"
                else -> "Слабая сеть"
            }
        )
    }

    private fun effectiveMinFrameIntervalMs(profile: AdaptiveProfile): Long {
        val encodeAvg = encodeTimeAvgMs.get().coerceAtLeast(0L)
        val loadPenalty = when {
            encodeAvg >= 95L -> 1.75f
            encodeAvg >= 70L -> 1.45f
            encodeAvg >= 45L -> 1.2f
            else -> 1.0f
        }
        return max(profile.minFrameIntervalMs, (profile.minFrameIntervalMs * loadPenalty).roundToInt().toLong())
    }

    private fun imageProxyToJpeg(
        image: androidx.camera.core.ImageProxy,
        mirrorHorizontally: Boolean,
        maxDimension: Int,
        jpegQuality: Int,
        localTargetInterval: Long,
        currentLocalFps: Int
    ): ByteArray? {
        val encodeStartedAt = SystemClock.elapsedRealtime()
        encodingBusy = true
        try {
            val nv21 = yuv420888ToNv21(image) ?: return null
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), jpegQuality, out)
            val rawBytes = out.toByteArray()
            val rawBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
            val rotation = image.imageInfo.rotationDegrees.toFloat()
            val needsTransform = rotation != 0f || mirrorHorizontally
            val transformed = if (needsTransform) {
                val matrix = Matrix().apply {
                    postRotate(rotation)
                    if (mirrorHorizontally) postScale(-1f, 1f, rawBitmap.width / 2f, rawBitmap.height / 2f)
                }
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            } else rawBitmap
            val maxDimAdjusted = when {
                currentLocalFps <= 4 -> (maxDimension * 0.8f).roundToInt()
                localTargetInterval >= 120L -> (maxDimension * 0.9f).roundToInt()
                else -> maxDimension
            }.coerceAtLeast(240)
            val scaled = downscaleBitmap(transformed, maxDimAdjusted)
            val jpegOut = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpegOut)
            if (scaled !== transformed) transformed.recycle()
            if (transformed !== rawBitmap) rawBitmap.recycle()
            return jpegOut.toByteArray()
        } finally {
            val elapsed = (SystemClock.elapsedRealtime() - encodeStartedAt).coerceAtLeast(1L)
            val prev = encodeTimeAvgMs.get()
            val smoothed = if (prev == 0L) elapsed else ((prev * 0.78f) + (elapsed * 0.22f)).roundToInt().toLong()
            encodeTimeAvgMs.set(smoothed)
            encodingBusy = false
        }
    }

    private fun yuv420888ToNv21(image: androidx.camera.core.ImageProxy): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        val out = ByteArray(ySize + uvSize * 2)
        image.planes[0].copyPlaneTo(out, 0, width, height)
        val vPlane = image.planes[2]
        val uPlane = image.planes[1]
        val vBuffer = vPlane.buffer
        val uBuffer = uPlane.buffer
        vBuffer.rewind()
        uBuffer.rewind()
        var outputOffset = ySize
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                out[outputOffset++] = vBuffer.get(vIndex)
                out[outputOffset++] = uBuffer.get(uIndex)
            }
        }
        return out
    }

    private fun androidx.camera.core.ImageProxy.PlaneProxy.copyPlaneTo(
        out: ByteArray,
        offset: Int,
        width: Int,
        height: Int
    ) {
        val source = buffer
        source.rewind()
        var outputOffset = offset
        for (row in 0 until height) {
            val rowStart = row * rowStride
            for (col in 0 until width) {
                out[outputOffset++] = source.get(rowStart + col * pixelStride)
            }
        }
    }

    private fun maybeRecommendProfile(deltaFrames: Long, deltaBytes: Long, windowMs: Long) {
        val uptime = System.currentTimeMillis() - networkMonitorStartedAtMs
        if (uptime < 3500L) return
        val safeWindowMs = windowMs.coerceAtLeast(1000L)
        val fps = deltaFrames * 1000f / safeWindowMs.toFloat()
        val kbps = (deltaBytes * 8f) / safeWindowMs.toFloat()
        receiveFps.set(fps.roundToInt().toLong())

        val current = adaptiveProfile
        val recommended = when {
            fps < AdaptiveProfile.LOW.minFps || kbps < AdaptiveProfile.LOW.minKbps -> AdaptiveProfile.LOW
            fps < AdaptiveProfile.MEDIUM.minFps || kbps < AdaptiveProfile.MEDIUM.minKbps -> AdaptiveProfile.MEDIUM
            else -> AdaptiveProfile.HIGH
        }
        val now = System.currentTimeMillis()
        val shouldSwitch = when {
            recommended == current -> false
            recommended.ordinal < current.ordinal -> true
            else -> now - lastProfileDecisionAtMs >= PROFILE_HYSTERESIS_MS
        }
        if (shouldSwitch && recommended.wireName != lastRecommendedProfileSent) {
            lastRecommendedProfileSent = recommended.wireName
            lastProfileDecisionAtMs = now
            onRecommendedProfileChanged?.invoke(recommended.wireName)
        }
        updateStats(true)
    }

    private fun downscaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= maxDimension) return bitmap
        val ratio = maxDimension.toFloat() / maxSide.toFloat()
        val targetWidth = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun cleanupExpiredAssemblies() {
        val now = System.currentTimeMillis()
        val iterator = assemblies.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.lastUpdatedAt > FRAME_ASSEMBLY_TTL_MS || entry.key < lastRenderedFrameId - 1) {
                iterator.remove()
            }
        }
    }
}
