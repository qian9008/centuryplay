package com.airplay.streamer.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.airplay.streamer.MainActivity
import com.airplay.streamer.R
import com.airplay.streamer.raop.RaopClient
import com.airplay.streamer.util.LogServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that captures system audio using MediaProjection/AudioPlaybackCapture
 * and streams it to an AirPlay speaker via RAOP
 */
class AudioCaptureService : Service() {
    private data class RaopCompatibilityMode(
        val label: String,
        val useAlac: Boolean,
        val useEncryption: Boolean,
        val rsaPadding: String
    )

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "airplay_streaming"
        private const val EARLY_DISCONNECT_WINDOW_MS = 8000L

        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAMES_PER_PACKET = 352
        private const val BYTES_PER_FRAME = 4 // 16-bit stereo = 4 bytes
        private const val BUFFER_SIZE = FRAMES_PER_PACKET * BYTES_PER_FRAME

        const val ACTION_START = "com.airplay.streamer.START"
        const val ACTION_STOP = "com.airplay.streamer.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_CODEC_CAPABILITIES = "codec_capabilities"
        const val EXTRA_ENCRYPTION_CAPABILITIES = "encryption_capabilities"

        // Singleton for accessing streaming state
        var instance: AudioCaptureService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var raopClient: RaopClient? = null
    private var captureJob: Job? = null
    private var connectionJob: Job? = null

    private var isCapturing = false
    private var deviceName: String = "AirPlay Speaker"
    private var currentModeIndex = 0
    private var activeMode: RaopCompatibilityMode? = null
    private var connectedAtMs = 0L
    private var isSwitchingModes = false
    private var shouldStayStopped = false
    private var codecCapabilities: String? = null
    private var encryptionCapabilities: String? = null
    private var targetHost: String? = null
    private var targetPort: Int = 0

    // Callback for UI updates
    var onStateChanged: ((Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onDestroy() {
        stopCapture(true) // Full stop on destroy
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "AirPlay Speaker"
                codecCapabilities = intent.getStringExtra(EXTRA_CODEC_CAPABILITIES)
                encryptionCapabilities = intent.getStringExtra(EXTRA_ENCRYPTION_CAPABILITIES)
                targetHost = host
                targetPort = port
                shouldStayStopped = false
                isSwitchingModes = false
                currentModeIndex = 0
                activeMode = null
                connectedAtMs = 0L

                if (resultData != null) {
                    startCapture(
                        resultCode = resultCode,
                        resultData = resultData,
                        host = host,
                        port = port,
                        codecCapabilities = codecCapabilities,
                        encryptionCapabilities = encryptionCapabilities
                    )
                }
            }
            ACTION_STOP -> {
                shouldStayStopped = true
                stopCapture(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent,
        host: String,
        port: Int,
        codecCapabilities: String?,
        encryptionCapabilities: String?
    ) {
        // If we are already capturing, stop the protocol part but keep MediaProjection alive if possible
        if (isCapturing || connectionJob?.isActive == true) {
            LogServer.log("Switching target - performing soft reset")
            stopCapture(false) // Soft stop: don't release MediaProjection
        }

        // Robust FGS start for Android 14 constraints
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            LogServer.log("Failed explicit FGS type start: ${e.message}")
            try {
                startForeground(NOTIFICATION_ID, createNotification())
            } catch (e2: Exception) {
                LogServer.log("Failed basic FGS start: ${e2.message}")
            }
        }

        // Android 14+: Must have MediaProjection token AFTER starting FGS of type mediaProjection (or vice-versa depending on OEM bugs)
        if (mediaProjection == null) {
            try {
                LogServer.log("Requesting new MediaProjection")
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
            } catch (e: Exception) {
                LogServer.log("CRITICAL: Failed to get MediaProjection - ${e.message}")
                stopCapture(true)
                stopSelf()
                return
            }
        } else {
            LogServer.log("Reusing existing MediaProjection")
        }
        
        connectionJob?.cancel()

        connectionJob = serviceScope.launch {
            try {
                if (mediaProjection == null || !serviceScope.isActive) {
                    LogServer.log("MediaProjection is null or job cancelled")
                    stopSelf()
                    return@launch
                }

                LogServer.log("Protocol force: AirPlay 1 (RAOP)")
                val connected = connectWithFallback(
                    host = host,
                    port = port,
                    codecCapabilities = codecCapabilities,
                    encryptionCapabilities = encryptionCapabilities
                )
                if (!connected || !serviceScope.isActive) return@launch

                // Try AudioPlaybackCapture
                val captureStarted = tryAudioPlaybackCapture()

                if (captureStarted) {
                    isCapturing = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onStateChanged?.invoke(true)
                    }
                    LogServer.log("Audio capture started, beginning stream loop")
                    startAudioStreamLoop()
                } else {
                    LogServer.log("Audio capture failed (possibly DRM blocked)")
                    stopCapture(true)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                LogServer.log("Streaming/Pairing Error: ${e.message}")
                stopCapture(true)
            }
        }
    }

    private suspend fun connectWithFallback(
        host: String,
        port: Int,
        codecCapabilities: String?,
        encryptionCapabilities: String?
    ): Boolean {
        val modes = buildCompatibilityModes(codecCapabilities, encryptionCapabilities)

        while (serviceScope.isActive && !shouldStayStopped && currentModeIndex < modes.size) {
            val mode = modes[currentModeIndex]
            activeMode = mode
            connectedAtMs = 0L
            LogServer.log("Trying RAOP mode ${currentModeIndex + 1}/${modes.size}: ${mode.label}")

            kotlinx.coroutines.yield()

            raopClient?.callback = null
            try {
                raopClient?.disconnect()
            } catch (_: Exception) {}
            
            raopClient = RaopClient(
                host = host,
                port = port,
                codecCapabilities = codecCapabilities,
                encryptionCapabilities = encryptionCapabilities,
                forceAlacEncoding = mode.useAlac,
                forceEncryption = mode.useEncryption,
                rsaPaddingMode = mode.rsaPadding,
                modeLabel = mode.label
            )

            raopClient?.callback = object : RaopClient.StreamingCallback {
                override fun onConnected() {
                    connectedAtMs = System.currentTimeMillis()
                    LogServer.log("RAOP callback: Connected with ${mode.label}")
                }

                override fun onDisconnected() {
                    if (connectedAtMs == 0L) {
                        LogServer.log("RAOP callback: Disconnected before stream start on ${mode.label}")
                        return
                    }
                    LogServer.log("RAOP callback: Server disconnected on ${mode.label}")
                    handleRaopDisconnect()
                }

                override fun onError(error: String) {
                    LogServer.log("RAOP callback: Error on ${mode.label} - $error")
                }
            }

            val connected = raopClient?.connect() ?: false
            if (connected) {
                LogServer.log("RAOP connection established")
                return true
            }

            currentModeIndex++
        }

        LogServer.log("Failed to connect to RAOP server with all compatibility modes")
        stopCapture(true)
        return false
    }

    private fun handleRaopDisconnect() {
        if (shouldStayStopped) return

        val uptime = if (connectedAtMs == 0L) Long.MAX_VALUE else System.currentTimeMillis() - connectedAtMs
        val shouldRetry = uptime in 1 until EARLY_DISCONNECT_WINDOW_MS

        if (shouldRetry) {
            LogServer.log("Early disconnect after ${uptime}ms on ${activeMode?.label}; switching compatibility mode")
            switchToNextCompatibilityMode()
            return
        }

        LogServer.log("RAOP callback: Server disconnected - stopping service")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            stopCapture(true)
            stopSelf()
        }
    }

    private fun switchToNextCompatibilityMode() {
        if (isSwitchingModes || shouldStayStopped) return
        isSwitchingModes = true
        currentModeIndex++

        serviceScope.launch(Dispatchers.IO) {
            try {
                val jobToCancel = captureJob
                captureJob = null
                isCapturing = false
                jobToCancel?.cancel()

                val recordToRelease = audioRecord
                audioRecord = null

                try {
                    recordToRelease?.stop()
                    jobToCancel?.join()
                    recordToRelease?.release()
                } catch (_: Exception) {}

                raopClient?.callback = null
                try {
                    raopClient?.disconnect()
                } catch (_: Exception) {}
                raopClient = null

                delay(300)

                if (!serviceScope.isActive || shouldStayStopped) return@launch
                
                val host = targetHost ?: return@launch
                val port = targetPort
                val connected = connectWithFallback(host, port, codecCapabilities, encryptionCapabilities)
                if (!connected || shouldStayStopped) return@launch

                val captureStarted = tryAudioPlaybackCapture()
                if (captureStarted) {
                    isCapturing = true
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onStateChanged?.invoke(true)
                    }
                    LogServer.log("Audio capture restarted after compatibility fallback")
                    startAudioStreamLoop()
                } else {
                    LogServer.log("Audio capture restart failed after compatibility fallback")
                    stopCapture(true)
                }
            } finally {
                isSwitchingModes = false
            }
        }
    }

    private fun buildCompatibilityModes(
        codecCapabilities: String?,
        encryptionCapabilities: String?
    ): List<RaopCompatibilityMode> {
        val codecSet = codecCapabilities?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
        val encryptionSet = encryptionCapabilities?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

        // cn=0 → L16/PCM, cn=1 → ALAC. 若 codecSet 为空则两者均视为支持。
        val supportsL16   = codecSet.isEmpty() || codecSet.contains("0")
        val supportsAlac  = codecSet.isEmpty() || codecSet.contains("1")

        // et=0 → plain, et=1 → RSA/AES (classic RAOP)
        val supportsClassicRaopEncryption = encryptionSet.isEmpty() || encryptionSet.contains("1")
        val supportsPlain = encryptionSet.isEmpty() || encryptionSet.contains("0")

        val candidates = buildList<RaopCompatibilityMode> {
            // ── ALAC 优先（Apple 设备原生格式）──────────────────────────────
            if (supportsAlac) {
                if (supportsPlain) {
                    add(RaopCompatibilityMode("ALAC + plain", useAlac = true, useEncryption = false, rsaPadding = "OAEP"))
                }
                if (supportsClassicRaopEncryption) {
                    add(RaopCompatibilityMode("ALAC + PKCS1 + AES", useAlac = true, useEncryption = true, rsaPadding = "PKCS1"))
                    add(RaopCompatibilityMode("ALAC + OAEP + AES",  useAlac = true, useEncryption = true, rsaPadding = "OAEP"))
                }
            }
            // ── L16 兜底 ───────────────────────────────────────────────────
            if (supportsL16) {
                if (supportsPlain) {
                    add(RaopCompatibilityMode("L16 + plain", useAlac = false, useEncryption = false, rsaPadding = "OAEP"))
                }
                if (supportsClassicRaopEncryption) {
                    add(RaopCompatibilityMode("L16 + PKCS1 + AES", useAlac = false, useEncryption = true, rsaPadding = "PKCS1"))
                    add(RaopCompatibilityMode("L16 + OAEP + AES",  useAlac = false, useEncryption = true, rsaPadding = "OAEP"))
                }
            }
        }

        // 若设备上报了具体能力集但全部被过滤（理论上不应发生），用 ALAC+plain 兜底
        return candidates.ifEmpty {
            LogServer.log("buildCompatibilityModes: no matching modes, falling back to ALAC+plain")
            listOf(RaopCompatibilityMode("ALAC + plain", useAlac = true, useEncryption = false, rsaPadding = "OAEP"))
        }
    }

    private fun tryAudioPlaybackCapture(): Boolean {
        return try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                LogServer.log("RECORD_AUDIO permission not granted")
                return false
            }

            val currentProjection = mediaProjection ?: return false
            val config = AudioPlaybackCaptureConfiguration.Builder(currentProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AUDIO_FORMAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
                BUFFER_SIZE * 4
            )

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                audioRecord?.startRecording()
                LogServer.log("AudioRecord started recording (Project reused: ${mediaProjection != null})")
                true
            } else {
                LogServer.log("AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                false
            }
        } catch (e: Exception) {
            LogServer.log("AudioRecord setup error: ${e.message}")
            false
        }
    }

    private fun startAudioStreamLoop() {
        captureJob = serviceScope.launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            var packetCount = 0
            
            try {
                while (isActive && isCapturing) {
                    val bytesRead = audioRecord?.read(buffer, 0, BUFFER_SIZE) ?: -1
                    if (bytesRead > 0) {
                        packetCount++
                        raopClient?.streamAudio(buffer.copyOf(bytesRead))
                    } else if (bytesRead < 0) {
                        break
                    } else {
                        Thread.sleep(10)
                    }
                }
            } catch (e: Exception) {
                LogServer.log("Audio stream loop error: ${e.message}")
            }
        }
    }

    private fun stopCapture(stopProjection: Boolean) {
        isCapturing = false
        
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            onStateChanged?.invoke(false)
        }
        
        LogServer.log("stopCapture(stopProjection=$stopProjection) called")
        
        pauseMediaPlayback()
        connectionJob?.cancel()
        connectionJob = null
        
        val jobToJoin = captureJob
        captureJob?.cancel()
        captureJob = null

        val recordToRelease = audioRecord
        audioRecord = null

        raopClient?.callback = null
        val clientToDisconnect = raopClient
        raopClient = null

        serviceScope.launch(Dispatchers.IO) {
            try {
                // VERY IMPORTANT to prevent native crash: wait for thread to unblock before calling release
                recordToRelease?.stop()
                jobToJoin?.join()
                recordToRelease?.release()
                LogServer.log("AudioRecord released gracefully")
            } catch (e: Exception) {
                LogServer.log("Error tearing down AudioRecord: ${e.message}")
            }
            
            try { clientToDisconnect?.disconnect() } catch (_: Exception) {}
        }

        if (stopProjection) {
            try {
                mediaProjection?.stop()
                LogServer.log("MediaProjection FULL stop")
            } catch (_: Exception) {}
            mediaProjection = null
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "AirPlay Streaming", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val stopPendingIntent = PendingIntent.getService(this, 1, Intent(this, AudioCaptureService::class.java).apply { action = ACTION_STOP }, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.streaming_notification_title, deviceName))
            .setContentText(getString(R.string.streaming_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.stop_streaming), stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    fun isCurrentlyStreaming(): Boolean = isCapturing

    fun setVolume(volume: Float) {
        serviceScope.launch { try { raopClient?.setVolume(volume) } catch (_: Exception) {} }
    }

    private fun pauseMediaPlayback() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.isMusicActive) {
                val eventTime = android.os.SystemClock.uptimeMillis()
                audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(eventTime, eventTime, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
                audioManager.dispatchMediaKeyEvent(android.view.KeyEvent(eventTime, eventTime, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE, 0))
            }
        } catch (_: Exception) {}
    }
}
