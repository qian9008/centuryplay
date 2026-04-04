package com.airplay.streamer.raop

import android.util.Log
import com.airplay.streamer.util.LogServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.RSAPublicKeySpec
import java.math.BigInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * RAOP (Remote Audio Output Protocol) client for AirPlay 1 speakers
 * Implements the RTSP-based protocol for audio streaming
 */
class RaopClient(
    private val host: String,
    private val port: Int,
    private val codecCapabilities: String? = null,
    private val encryptionCapabilities: String? = null,
    private val forceAlacEncoding: Boolean? = null,
    private val forceEncryption: Boolean? = null,
    private val rsaPaddingMode: String? = null,
    private val modeLabel: String? = null,
    private val rtspPassword: String? = null,
    private val streamLatencyMsOverride: Long? = null,
    private val transportMode: String? = null  // "auto", "tcp", or "udp"
) {
    companion object {
        private const val TAG = "RaopClient"
        private const val USER_AGENT = "iTunes/4.6 (Macintosh; U; PPC Mac OS X 10.3)"
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
        private const val BITS_PER_SAMPLE = 16
        private const val FRAMES_PER_PACKET = 352
        private const val DEFAULT_STREAM_LATENCY_MS = 1100L
        private const val RSA_PADDING_OAEP = "OAEP"
        private const val RSA_PADDING_PKCS1 = "PKCS1"
        private const val RETRANSMIT_CACHE_SIZE = 512
    }
    
    // Client identifiers (as per AirPlay spec)
    private val clientInstance = generateHexId(8)
    private val dacpId = clientInstance
    private val activeRemote = Random.nextLong(100000000, 4294967295).toString()
    private val localMacAddress = generateHexId(6).uppercase()

    private var rtspSocket: Socket? = null
    private var rtspReader: BufferedReader? = null
    private var rtspWriter: PrintWriter? = null
    private var audioTcpSocket: Socket? = null
    private var audioTcpOutput: BufferedOutputStream? = null
    private val rtspRequestLock = Any()
    private var audioSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null

    // Helper to run the timing thread
    private var isTimingRunning = AtomicBoolean(false)
    // Helper to run the sync packet thread
    private var isSyncRunning = AtomicBoolean(false)
    private var isControlReceiverRunning = AtomicBoolean(false)
    // Helper to run connection health monitor
    private var isHealthMonitorRunning = AtomicBoolean(false)
    // Helper to run RTSP keepalive
    private var isKeepAliveRunning = AtomicBoolean(false)
    private var syncSequence = 0
    
    private val HEALTH_CHECK_INTERVAL_MS = 3000L  // Check every 3 seconds

    private val cSeq = AtomicInteger(0)
    private var sessionId: String? = null
    private var serverSessionId: String? = null
    private val localSessionId: String = Random.nextLong(0, Long.MAX_VALUE).toString()
    private var serverPort: Int = 0
    private var serverControlPort: Int = 0  // Server's control port for sync packets
    private var serverTimingPort: Int = 0   // Server's timing port
    private var audioTransport: String = "udp"
    private var tcpAudioPacketCount: Int = 0
    private var controlPort: Int = 0
    private var timingPort: Int = 0

    private val isConnected = AtomicBoolean(false)
    private val isStreaming = AtomicBoolean(false)

    private var rtpSequence: Int = Random.nextInt(0xFFFF)
    private var rtpTimestamp: Long = Random.nextLong(0xFFFFFFFFL)
    private val ssrc: Int = Random.nextInt()

    // Audio buffer for PCM data
    private val alacEncoder = AlacEncoder()

    // Apple's RSA Public Key for AirPlay (2048-bit) - from shairport-sync's super_secret_key
    // This is the correct key that shairport-sync and other receivers use
    private val RSA_MODULUS = "59dE8qLieItsH1WgjrcFRKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC" +
            "5vOYvfDmFI6oSFXi5ELabWJmT2dKHzBJKa3k9ok+8t9ucRqMd6DZHJ2YCCLlDR" +
            "KSKv6kDqnw4UwPdpOMXziC/AMj3Z/lUVX1G7WSHCAWKf1zNS1eLvqr+boEjXuB" +
            "OitnZ/bDzPHrTOZz0Dew0uowxf/+sG+NCK3eQJVxqcaJ/vEHKIVd2M+5qL71yJ" +
            "Q+87X6oV3eaYvt3zWZYD6z5vYTcrtij2VZ9Zmni/UAaHqn9JdsBWLUEpVviYnh" +
            "imNVvYFZeCXg/IdTQ+x4IRdiXNv5hEew=="
    private val RSA_EXPONENT = "AQAB"

    private var aesKey: ByteArray? = null
    private var aesIv: ByteArray? = null

    // Configuration flags
    private var useEncryption = true
    private var useAlacEncoding = false
    private var currentRsaPaddingMode = RSA_PADDING_OAEP
    private val streamLatencyMs: Long = (streamLatencyMsOverride ?: DEFAULT_STREAM_LATENCY_MS).coerceIn(250L, 5000L)
    private val streamLatencySamples: Int = ((streamLatencyMs * SAMPLE_RATE) / 1000L).toInt()

    private data class DigestChallenge(
        val scheme: String,
        val realm: String?,
        val nonce: String?,
        val algorithm: String?,
        val qop: String?,
        val opaque: String?
    )
    private var digestChallenge: DigestChallenge? = null
    private var cachedAuthorizationHeader: String? = null
    private var digestNonceCount: Int = 0

    private val retransmitCache = object : LinkedHashMap<Int, ByteArray>(RETRANSMIT_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean {
            return size > RETRANSMIT_CACHE_SIZE
        }
    }

    interface StreamingCallback {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
    }

    var callback: StreamingCallback? = null
    /**
     * Connect to the AirPlay speaker
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            logD("Connecting to $host:$port mode=${modeLabel ?: "<default>"}")
            configureCompatibility()
            
            // Create RTSP socket with short timeout for diagnostics
            rtspSocket = Socket(host, port).apply {
                soTimeout = 3000  // 3 second timeout for faster feedback
            }
            logD("RTSP socket connected")
            
            rtspReader = BufferedReader(InputStreamReader(rtspSocket!!.getInputStream()))
            rtspWriter = PrintWriter(OutputStreamWriter(rtspSocket!!.getOutputStream()), true)

            // Create UDP sockets for audio/control/timing
            audioSocket = DatagramSocket()
            controlSocket = DatagramSocket()
            timingSocket = DatagramSocket()
            logD("UDP sockets: audio=${audioSocket?.localPort}, ctrl=${controlSocket?.localPort}, time=${timingSocket?.localPort}")

            // Diagnostic: Try OPTIONS first to see if server responds at all
            logD("Testing OPTIONS...")
            val optionsResult = testOptions()
            logD("OPTIONS result: $optionsResult")
            
            // Now try ANNOUNCE with longer timeout
            rtspSocket?.soTimeout = 10000
            logD("Starting ANNOUNCE...")
            if (!announce()) {
                logE("ANNOUNCE failed")
                disconnect()
                return@withContext false
            }
            logD("ANNOUNCE succeeded")

            logD("Starting SETUP...")
            if (!setup()) {
                logE("SETUP failed")
                disconnect()
                return@withContext false
            }
            logD("SETUP succeeded - serverPort=$serverPort")

            logD("Starting RECORD...")
            if (!record()) {
                logE("RECORD failed")
                disconnect()
                return@withContext false
            }
            logD("RECORD succeeded - streaming ready!")

            isConnected.set(true)
            callback?.onConnected()
            true
        } catch (e: Exception) {
            logE("Connection failed: ${e.message}")
            callback?.onError("Connection failed: ${e.message}")
            disconnect()
            false
        }
    }

    /**
     * OPTIONS - Query server capabilities (required before ANNOUNCE)
     */
    private fun options(): Boolean {
        val sb = StringBuilder()
        sb.append("OPTIONS * RTSP/1.0\r\n")
        sb.append("CSeq: ${cSeq.incrementAndGet()}\r\n")
        sb.append("User-Agent: $USER_AGENT\r\n")
        sb.append("Client-Instance: $clientInstance\r\n")
        sb.append("DACP-ID: $dacpId\r\n")
        sb.append("Active-Remote: $activeRemote\r\n")
        sb.append("\r\n")
        
        val request = sb.toString()
        Log.d(TAG, "OPTIONS request:\n$request")

        val response = sendRtspRequest(request)
        Log.d(TAG, "OPTIONS response: code=${response?.first}, headers=${response?.second}")
        // Some servers return 200, some return 501 (not implemented) but still work
        return response != null && (response.first == 200 || response.first == 501)
    }

    private fun generateAppleChallenge(): String {
        val bytes = ByteArray(16)
        Random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    /**
     * ANNOUNCE - Describe the audio format to the server
     */
    private fun announce(): Boolean {
        val rsaAesKey: String?
        val aesIvBase64: String?
        if (useEncryption) {
            generateKeys()
            rsaAesKey = encryptRsaAesKey()
            if (rsaAesKey == null) {
                logE("Failed to encrypt AES key")
                return false
            }
            aesIvBase64 = Base64.getEncoder().encodeToString(aesIv!!)
        } else {
            rsaAesKey = null
            aesIvBase64 = null
        }

        val localIp = rtspSocket?.localAddress?.hostAddress ?: "0.0.0.0"
        val sdp = buildSdp(localIp, rsaAesKey, aesIvBase64)

        logD("SDP content:\n$sdp")

        // Generate Apple-Challenge for authentication
        val challenge = generateAppleChallenge()
        
        val request = buildRtspRequest(
            "ANNOUNCE",
            mapOf(
                "Content-Type" to "application/sdp",
                "Content-Length" to sdp.toByteArray().size.toString(),
                "Apple-Challenge" to challenge,
                "X-Apple-Session-ID" to (serverSessionId ?: generateSessionId())
            ),
            sdp
        )
        logD("ANNOUNCE request [${modeLabel ?: "<default>"}]:\n$request")

        val response = sendRtspRequest(request)
        logD("ANNOUNCE response [${modeLabel ?: "<default>"}]: code=${response?.first}, headers=${response?.second}")
        return response?.first == 200
    }


    /**
     * Diagnostic: Test OPTIONS request
     */
    private fun testOptions(): Boolean {
        try {
            val challenge = generateAppleChallenge()
            
            val sb = StringBuilder()
            sb.append("OPTIONS * RTSP/1.0\r\n")
            sb.append("CSeq: ${cSeq.incrementAndGet()}\r\n")
            sb.append("User-Agent: iTunes/10.6 (Windows; N)\r\n")
            sb.append("Client-Instance: $clientInstance\r\n")
            sb.append("DACP-ID: $dacpId\r\n")
            sb.append("Active-Remote: $activeRemote\r\n")
            sb.append("Apple-Challenge: $challenge\r\n")
            sb.append("\r\n")
            
            val request = sb.toString()
            logD("Diagnostic OPTIONS request:\n$request")


            val response = sendRtspRequest(request)
            logD("Diagnostic OPTIONS response: code=${response?.first}")
            response?.second?.forEach { (key, value) ->
                logD("Header: $key = $value")
            }
            return response != null
        } catch (e: Exception) {
            logE("Diagnostic OPTIONS failed: ${e.message}")
            return false
        }
    }

    /**
     * Generate random AES key and IV, and initialize the cipher
     */
    private fun generateKeys() {
        aesKey = ByteArray(16)
        aesIv = ByteArray(16)
        Random.nextBytes(aesKey!!)
        Random.nextBytes(aesIv!!)
        logD("Generated AES session key and IV")
    }

    /**
     * Encrypt the AES key with Apple's standard AirPlay RSA Public Key
     * shairport-sync uses RSA-OAEP with SHA-1 for decryption
     */
    private fun encryptRsaAesKey(): String? {
        try {
            // Decode the modulus and exponent from base64
            val modulusBytes = Base64.getDecoder().decode(RSA_MODULUS)
            val exponentBytes = Base64.getDecoder().decode(RSA_EXPONENT)
            
            val modulus = BigInteger(1, modulusBytes)
            val exponent = BigInteger(1, exponentBytes)
            
            val spec = RSAPublicKeySpec(modulus, exponent)
            val factory = KeyFactory.getInstance("RSA")
            val publicKey = factory.generatePublic(spec)
            
            // Most RAOP receivers, including shairport-sync, expect OAEP with SHA-1/MGF1.
            val cipher = Cipher.getInstance(
                when (currentRsaPaddingMode) {
                    RSA_PADDING_PKCS1 -> "RSA/ECB/PKCS1Padding"
                    else -> "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"
                }
            )
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            
            val encryptedKey = cipher.doFinal(aesKey!!)
            return Base64.getEncoder().encodeToString(encryptedKey)
        } catch (e: Exception) {
            logE("RSA Encryption failed: ${e.message}")
            return null
        }
    }

    /**
     * SETUP - Configure the streaming ports
     */

    private fun setup(): Boolean {
        val localControlPort = controlSocket?.localPort ?: return false
        val localTimingPort = timingSocket?.localPort ?: return false
        controlPort = localControlPort
        timingPort = localTimingPort
        
        Log.d(TAG, "SETUP with control_port=$localControlPort, timing_port=$localTimingPort")

        val variants = listOf(
            SetupTransportVariant(
                label = "udp ports",
                transport = "RTP/AVP/UDP;unicast;mode=record;control_port=$localControlPort;timing_port=$localTimingPort"
            ),
            SetupTransportVariant(
                label = "udp bare",
                transport = "RTP/AVP/UDP;unicast;mode=record"
            ),
            SetupTransportVariant(
                label = "avp ports",
                transport = "RTP/AVP;unicast;mode=record;control_port=$localControlPort;timing_port=$localTimingPort"
            ),
            SetupTransportVariant(
                label = "avp bare",
                transport = "RTP/AVP;unicast;mode=record"
            )
        )

        for (variant in variants) {
            val request = buildRtspRequest(
                "SETUP",
                mapOf("Transport" to variant.transport)
            )
            logD("SETUP request [${modeLabel ?: "<default>"} / ${variant.label}]:\n$request")

            val response = sendRtspRequest(request)
            logD("SETUP response [${modeLabel ?: "<default>"} / ${variant.label}]: code=${response?.first}, headers=${response?.second}")

            if (response != null && response.first == 200) {
                val transportHeader = response.second["Transport"] ?: return false
                parseTransportHeader(transportHeader)
                if (serverControlPort == 0) {
                    serverControlPort = localControlPort
                    logD("SETUP fallback: using local control_port as server target: $serverControlPort")
                }
                if (serverTimingPort == 0) {
                    serverTimingPort = localTimingPort
                    logD("SETUP fallback: using local timing_port as server target: $serverTimingPort")
                }
            logD("SETUP succeeded via ${variant.label} - serverPort=$serverPort")

                if (audioTransport == "udp") {
                    startTimingResponder()
                } else {
                    openAudioTcpSocket()
                }

                val sessionVal = response.second["Session"]
                if (sessionVal != null) {
                    serverSessionId = sessionVal.split(";")[0].trim()
                    logD("Captured server session ID: $serverSessionId")
                }
                return true
            }
        }

        return false
    }

    private fun startTimingResponder() {
        val socket = timingSocket ?: return
        logD("startTimingResponder called. Socket port: ${socket.localPort}")
        isTimingRunning.set(true)
        Thread {
            logD("Timing thread started execution")
            val buffer = ByteArray(128)
            val packet = DatagramPacket(buffer, buffer.size)
            var timingPacketCount = 0
            try {
                while (isTimingRunning.get() && !socket.isClosed) {
                    socket.receive(packet)
                    timingPacketCount++
                    
                    // Log packet details for debugging
                    if (timingPacketCount <= 5 || timingPacketCount % 100 == 0) {
                        val req = packet.data
                        val type = req[1].toInt() and 0xFF
                        logD("Timing Packet #$timingPacketCount: type=0x${type.toString(16)}, len=${packet.length}, from=${packet.address}:${packet.port}")
                    }
                    
                    if (packet.length >= 32) { 
                        val req = packet.data
                        val response = ByteArray(packet.length)
                        
                        // Copy header (first 8 bytes)
                        System.arraycopy(req, 0, response, 0, 8)
                        
                        // Set Type to Reply (0x53 | 0x80 = 0xD3)
                        response[1] = (0x53 or 0x80).toByte()
                        
                        // NTP Logic: Copy T_xmit (24-31) to T_orig (8-15)
                        System.arraycopy(req, 24, response, 8, 8)
                        
                        // Current time
                        val now = System.currentTimeMillis()
                        val ntpSec = (now / 1000) + 2208988800L
                        val ntpFrac = ((now % 1000) * 4294967296.0 / 1000.0).toLong()
                        
                        // Write T_recv (16-23) and T_xmit (24-31)
                        writeNtpTimestamp(response, 16, ntpSec, ntpFrac)
                        writeNtpTimestamp(response, 24, ntpSec, ntpFrac)

                        val reply = DatagramPacket(response, packet.length, packet.address, packet.port)
                        socket.send(reply)
                        
                        if (timingPacketCount <= 5) {
                            logD("Timing Response #$timingPacketCount sent: sec=$ntpSec, frac=$ntpFrac")
                        }
                    }
                }
            } catch (e: Exception) {
                if (isTimingRunning.get()) {
                    logE("Timing thread error: ${e.message}")
                    // If timing fails and we're still supposed to be connected, trigger disconnect
                    if (isConnected.get()) {
                        logE("Timing socket closed unexpectedly - server may have disconnected")
                        handleServerDisconnect()
                    }
                }
            }
            logD("Timing thread finished. Total packets handled: $timingPacketCount")
        }.start()
    }
    
    private fun writeNtpTimestamp(buffer: ByteArray, offset: Int, seconds: Long, fraction: Long) {
        // Seconds (32-bit big endian)
        buffer[offset] = (seconds shr 24).toByte()
        buffer[offset+1] = (seconds shr 16).toByte()
        buffer[offset+2] = (seconds shr 8).toByte()
        buffer[offset+3] = (seconds).toByte()
        
        // Fraction (32-bit big endian)
        buffer[offset+4] = (fraction shr 24).toByte()
        buffer[offset+5] = (fraction shr 16).toByte()
        buffer[offset+6] = (fraction shr 8).toByte()
        buffer[offset+7] = (fraction).toByte()
    }

    /**
     * Start sync packet sender - sends RTP timestamp to NTP time mapping to the receiver
     * This is essential for the receiver to know when to play audio frames
     */
    private fun startSyncSender() {
        val socket = controlSocket ?: return
        if (serverControlPort == 0) {
            logE("Cannot start sync sender - serverControlPort is 0")
            return
        }
        
        logD("Starting sync sender to $host:$serverControlPort using local control port $controlPort")
        isSyncRunning.set(true)
        syncSequence = 0
        logD("Sync timing established: rtpTimestamp=$rtpTimestamp, latency=${streamLatencyMs}ms")
        
        Thread {
            try {
                val address = InetAddress.getByName(host)
                var lastSyncTime = 0L
                
                while (isSyncRunning.get() && !socket.isClosed) {
                    val now = System.currentTimeMillis()
                    
                    // Send sync packet every 300ms
                    if (now - lastSyncTime >= 300) {
                        val currentRtp = rtpTimestamp
                        val playTimeMs = now + streamLatencyMs
                        val syncPacket = buildSyncPacket(currentRtp, playTimeMs)
                        val packet = DatagramPacket(syncPacket, syncPacket.size, address, serverControlPort)
                        socket.send(packet)
                        
                        syncSequence++
                        if (syncSequence <= 5 || syncSequence % 20 == 0) {
                            logD("Sync #$syncSequence: currentRtp=$currentRtp playAt=${currentRtp + streamLatencySamples} playTime=$playTimeMs")
                        }
                        lastSyncTime = now
                    }
                    
                    Thread.sleep(50) // Check every 50ms
                }
            } catch (e: Exception) {
                if (isSyncRunning.get()) {
                    logE("Sync sender error: ${e.message}")
                }
            }
            logD("Sync sender finished")
        }.start()
    }
    
    /**
     * Build an AirPlay sync/control packet
     * Format (20 bytes):
     * - byte 0: 0x80 (RTP version 2) or 0x90 (with extension, for first sync)
     * - byte 1: 0xd4 (payload type 84 = sync)
     * - bytes 2-3: sequence number (big-endian)
     * - bytes 4-7: RTP timestamp that should play at the given NTP time (big-endian)
     * - bytes 8-15: NTP timestamp when the RTP audio should play (64-bit big-endian)
     * - bytes 16-19: RTP timestamp + latency for next sync point (big-endian)
     */
    private fun buildSyncPacket(currentRtp: Long, playTimeMs: Long): ByteArray {
        val packet = ByteArray(20)
        
        // Header: version 2, with extension bit for first packet
        packet[0] = if (syncSequence == 0) 0x90.toByte() else 0x80.toByte()
        packet[1] = 0xd4.toByte()  // Payload type 84 (sync)
        
        // Sequence number (big-endian)
        packet[2] = (syncSequence shr 8).toByte()
        packet[3] = syncSequence.toByte()
        
        // Current RTP position in the sender timeline.
        packet[4] = (currentRtp shr 24).toByte()
        packet[5] = (currentRtp shr 16).toByte()
        packet[6] = (currentRtp shr 8).toByte()
        packet[7] = currentRtp.toByte()
        
        // NTP timestamp when the receiver should render the future play point.
        val ntpSec = (playTimeMs / 1000) + 2208988800L
        val ntpFrac = ((playTimeMs % 1000) * 4294967296.0 / 1000.0).toLong()
        writeNtpTimestamp(packet, 8, ntpSec, ntpFrac)
        
        // Future RTP play point with the standard AirPlay buffer latency applied.
        val nextRtp = currentRtp + streamLatencySamples
        packet[16] = (nextRtp shr 24).toByte()
        packet[17] = (nextRtp shr 16).toByte()
        packet[18] = (nextRtp shr 8).toByte()
        packet[19] = nextRtp.toByte()
        
        return packet
    }
    
    private fun stopSyncSender() {
        isSyncRunning.set(false)
    }

    private fun startControlReceiver() {
        val socket = controlSocket ?: return
        if (serverControlPort == 0) return
        if (isControlReceiverRunning.get()) return

        isControlReceiverRunning.set(true)
        Thread {
            val buffer = ByteArray(1500)
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                while (isControlReceiverRunning.get() && !socket.isClosed) {
                    socket.receive(packet)
                    if (packet.length < 8) continue
                    val data = packet.data
                    val payloadType = data[1].toInt() and 0x7F
                    // Accept common resend-request payload types from different receivers.
                    if (payloadType == 0x55 || payloadType == 0x56) {
                        val (startSeq, count) = parseRetransmitRange(data, packet.length)
                        if (count > 0) {
                            resendRtpPackets(startSeq, count)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isControlReceiverRunning.get()) {
                    logE("Control receiver error: ${e.message}")
                }
            }
            logD("Control receiver finished")
        }.start()
    }

    private fun stopControlReceiver() {
        isControlReceiverRunning.set(false)
    }

    private fun parseRetransmitRange(data: ByteArray, length: Int): Pair<Int, Int> {
        if (length >= 8) {
            val firstA = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            val countA = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
            if (countA in 1..128) return firstA to countA
            val firstB = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            val countB = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
            if (countB in 1..128) return firstB to countB
        }
        return 0 to 0
    }

    private fun resendRtpPackets(startSequence: Int, count: Int) {
        val socket = controlSocket ?: return
        val address = InetAddress.getByName(host)
        var resent = 0
        for (i in 0 until count) {
            val seq = (startSequence + i) and 0xFFFF
            val pkt = lookupRtpPacket(seq) ?: continue
            val resendPacket = DatagramPacket(pkt, pkt.size, address, serverControlPort)
            socket.send(resendPacket)
            resent++
        }
        if (resent > 0) {
            logD("Retransmit served: start=$startSequence count=$count sent=$resent")
        }
    }

    private fun startKeepAlive() {
        if (isKeepAliveRunning.get()) return
        isKeepAliveRunning.set(true)
        Thread {
            try {
                while (isKeepAliveRunning.get() && isConnected.get()) {
                    Thread.sleep(2000)
                    if (!isKeepAliveRunning.get() || !isConnected.get()) break
                    val request = buildRtspRequest("OPTIONS")
                    val response = sendRtspRequest(request)
                    logD("Keepalive OPTIONS response: code=${response?.first}, headers=${response?.second}")
                    if (response == null || (response.first != 200 && response.first != 501)) {
                        logE("Keepalive failed")
                        handleServerDisconnect()
                        break
                    }
                }
            } catch (e: Exception) {
                if (isKeepAliveRunning.get()) {
                    logE("Keepalive error: ${e.message}")
                }
            }
            logD("Keepalive thread finished")
        }.start()
    }

    private fun stopKeepAlive() {
        isKeepAliveRunning.set(false)
    }
    
    /**
     * Start connection health monitor
     * Monitors the TCP RTSP socket to detect if the server has disconnected
     */
    private fun startHealthMonitor() {
        logD("startHealthMonitor() called")
        
        // Ensure any previous health monitor is stopped first
        if (isHealthMonitorRunning.get()) {
            logD("Stopping previous health monitor first")
            stopHealthMonitor()
        }
        
        logD("Starting connection health monitor thread")
        isHealthMonitorRunning.set(true)
        
        Thread {
            logD("Health monitor thread started")
            try {
                while (isHealthMonitorRunning.get() && isConnected.get()) {
                    Thread.sleep(HEALTH_CHECK_INTERVAL_MS)
                    
                    if (!isHealthMonitorRunning.get() || !isConnected.get()) break
                    
                    // Check if TCP socket is still valid
                    val currentSocket = rtspSocket
                    if (currentSocket == null || currentSocket.isClosed || !currentSocket.isConnected) {
                        logE("Health check: RTSP socket is closed/disconnected")
                        handleServerDisconnect()
                        break
                    }
                    
                    logD("Health check: socket alive")
                    
                    try {
                        currentSocket.sendUrgentData(0xFF)
                        if (currentSocket.isInputShutdown || currentSocket.isOutputShutdown) {
                            logE("Health check: RTSP socket input/output is shutdown")
                            handleServerDisconnect()
                            break
                        }
                        logD("Health check: socket OK")
                    } catch (e: java.io.IOException) {
                        logE("Health check: RTSP socket probe failed - ${e.message}")
                        handleServerDisconnect()
                        break
                    }
                }
            } catch (e: Exception) {
                if (isHealthMonitorRunning.get()) {
                    logE("Health monitor error: ${e.message}")
                }
            }
            logD("Health monitor finished")
        }.start()
    }
    
    private fun stopHealthMonitor() {
        isHealthMonitorRunning.set(false)
    }
    
    /**
     * Handle unexpected server disconnect
     * Called when we detect the server has stopped responding
     */
    private fun handleServerDisconnect() {
        if (!isConnected.get()) return  // Already disconnected
        
        logE("Server disconnect detected - cleaning up")
        
        // Set flags immediately
        isStreaming.set(false)
        isConnected.set(false)
        
        // Stop all background threads
        stopHealthMonitor()
        stopSyncSender()
        stopControlReceiver()
        stopKeepAlive()
        stopTimingResponder()
        
        // Close sockets (don't wait for TEARDOWN since server is gone)
        try { audioSocket?.close() } catch (_: Exception) {}
        audioSocket = null
        try { controlSocket?.close() } catch (_: Exception) {}
        controlSocket = null
        try { timingSocket?.close() } catch (_: Exception) {}
        timingSocket = null
        try { rtspWriter?.close() } catch (_: Exception) {}
        rtspWriter = null
        try { rtspReader?.close() } catch (_: Exception) {}
        rtspReader = null
        try { rtspSocket?.close() } catch (_: Exception) {}
        rtspSocket = null
        
        // Reset state
        serverPort = 0
        serverControlPort = 0
        serverTimingPort = 0
        serverSessionId = null
        
        // Clear audio buffer
        synchronized(audioBuffer) {
            audioBuffer.reset()
        }
        
        synchronized(retransmitCache) {
            retransmitCache.clear()
        }

        digestChallenge = null
        cachedAuthorizationHeader = null
        digestNonceCount = 0
        logD("Server disconnect cleanup complete")
        callback?.onError("Server disconnected")
        callback?.onDisconnected()
    }
    
    // ...

    /**
     * RECORD - Start the streaming session
     */
    private fun record(): Boolean {
        // Ensure Content-Length is STRICTLY omitted from header map
        val headerMap = mapOf(
            "Range" to "npt=0-",
            "RTP-Info" to "seq=$rtpSequence;rtptime=$rtpTimestamp"
        )
        
        val request = buildRtspRequest(
            "RECORD",
            headerMap,
            body = "",
            sessionId = serverSessionId 
        )
        logD("RECORD request (Check for Content-Length):\n$request")

        val response = sendRtspRequest(request)
        logD("RECORD response: code=${response?.first}")
        
        if (response?.first == 200) {
            isStreaming.set(true)
            // Start sending sync packets to tell receiver when to play audio
            startSyncSender()
            // Listen for retransmission requests on control channel.
            startControlReceiver()
            // Keep the RTSP control session active on receivers that close it aggressively.
            startKeepAlive()
            // Start connection health monitor
            startHealthMonitor()
            return true
        }
        return false
    }

    // Audio buffer for PCM data
    private val audioBuffer = java.io.ByteArrayOutputStream()
    private val PACKET_SIZE = 1408 // 352 frames * 4 bytes
    private var debugPacketCount = 0

    /**
     * Stream PCM audio data
     * @param pcmData PCM audio samples (16-bit stereo, 44100Hz, Little Endian)
     */
    suspend fun streamAudio(pcmData: ByteArray) = withContext(Dispatchers.IO) {
        if (!isStreaming.get()) return@withContext

        try {
            // Append new data to buffer
            synchronized(audioBuffer) {
                audioBuffer.write(pcmData)
            }

            // Process full packets
            val bufferBytes = synchronized(audioBuffer) { audioBuffer.toByteArray() }
            if (bufferBytes.size >= PACKET_SIZE) {
                var offset = 0
                while (offset + PACKET_SIZE <= bufferBytes.size) {
                    val chunk = bufferBytes.copyOfRange(offset, offset + PACKET_SIZE)
                    
                    // Debug Logging: Calculate RMS periodically
                    debugPacketCount++
                    if (debugPacketCount % 50 == 0 || debugPacketCount <= 5) {
                        var sum = 0.0
                        val samples = chunk.size / 2 // 16-bit samples
                        for (i in 0 until chunk.size step 2) {
                             val sample = ((chunk[i+1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort()
                             sum += sample.toDouble() * sample.toDouble()
                        }
                        val rms = Math.sqrt(sum / samples).toInt()
                        val db = if (rms > 0) Math.round(20 * Math.log10(rms.toDouble())) else Long.MIN_VALUE
                        val mode = if (useAlacEncoding) "ALAC" else "L16"
                        val enc = if (useEncryption) "ENC" else "PLAIN"
                        LogServer.log("SND: Pkt $rtpSequence, RMS=$rms (Max 32767), Vol=${if (db == Long.MIN_VALUE) "-inf" else "${db}dB"}, Mode=$mode, Enc=$enc")
                    }

                    // Encode audio data
                    val encodedData = if (useAlacEncoding) {
                        LogServer.log("ENCODING: Using ALAC encoder")
                        alacEncoder.encode(chunk)
                    } else {
                        LogServer.log("ENCODING: Using L16 (big-endian PCM)")
                        // Fallback: Convert PCM from little-endian to big-endian (network byte order)
                        // L16 format (RFC 3551) requires big-endian (network byte order)
                        swapEndianness(chunk)
                    }
                    
                    // Encrypt audio data with AES-128-CBC (only if encryption is enabled)
                    val payloadData = if (useEncryption) {
                        encryptAudio(encodedData)
                    } else {
                        encodedData  // Send unencrypted for testing
                    }
                    
                    // Build RTP packet with payload
                    val rtpPacket = buildRtpPacket(payloadData)
                    rememberRtpPacket(rtpSequence, rtpPacket)

                    // Send to server
                    if (audioTransport == "tcp") {
                        logTcpAudioPacket(rtpPacket)
                        audioTcpOutput?.write(rtpPacket)
                        audioTcpOutput?.flush()
                    } else {
                        val address = InetAddress.getByName(host)
                        val packet = DatagramPacket(rtpPacket, rtpPacket.size, address, serverPort)
                        audioSocket?.send(packet)
                    }

                    // Update sequence and timestamp
                    rtpSequence = (rtpSequence + 1) and 0xFFFF
                    rtpTimestamp += FRAMES_PER_PACKET
                    
                    offset += PACKET_SIZE
                }
                
                // Keep remaining bytes
                synchronized(audioBuffer) {
                    audioBuffer.reset()
                    if (offset < bufferBytes.size) {
                        val remaining = bufferBytes.copyOfRange(offset, bufferBytes.size)
                        audioBuffer.write(remaining)
                    }
                }
            }
        } catch (e: Exception) {
            callback?.onError("Streaming error: ${e.message}")
        }
    }

    /**
     * Set volume (0.0 to 1.0)
     */
    suspend fun setVolume(volume: Float): Boolean = withContext(Dispatchers.IO) {
        if (!isConnected.get()) return@withContext false

        // AirPlay volume is in dB, -144 (mute) to 0 (max)
        val dbVolume = if (volume <= 0f) -144f else (volume * 30f - 30f)
        val volumeStr = "volume: $dbVolume\r\n"

        val request = buildRtspRequest(
            "SET_PARAMETER",
            mapOf(
                "Content-Type" to "text/parameters",
                "Content-Length" to volumeStr.length.toString()
            ),
            volumeStr,
            sessionId = serverSessionId
        )

        sendRtspRequest(request)?.first == 200
    }

    /**
     * Disconnect from the speaker - robust teardown with proper cleanup
     * This ensures all threads are stopped, all sockets are closed, and TEARDOWN is sent
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        logD("disconnect() called - starting robust teardown")
        
        // First, stop all flags to signal threads to exit
        val wasStreaming = isStreaming.getAndSet(false)
        val wasConnected = isConnected.getAndSet(false)
        
        // Stop background threads immediately
        logD("Stopping health monitor...")
        stopHealthMonitor()
        
        logD("Stopping sync sender...")
        stopSyncSender()
        stopControlReceiver()

        logD("Stopping keepalive...")
        stopKeepAlive()
        
        logD("Stopping timing responder...")
        stopTimingResponder()
        
        // Give threads a moment to notice the flags
        try { Thread.sleep(100) } catch (_: Exception) {}
        
        // Send TEARDOWN if we were connected (with timeout)
        if (wasConnected) {
            try {
                logD("Sending TEARDOWN request...")
                rtspSocket?.soTimeout = 2000  // 2 second timeout for teardown
                val request = buildRtspRequest("TEARDOWN", sessionId = serverSessionId)
                val response = sendRtspRequest(request)
                logD("TEARDOWN response: ${response?.first}")
            } catch (e: Exception) {
                logD("TEARDOWN failed (expected if connection lost): ${e.message}")
            }
        }
        
        // Close RTSP connection
        logD("Closing RTSP socket...")
        try {
            rtspWriter?.close()
        } catch (_: Exception) {}
        rtspWriter = null
        
        try {
            rtspReader?.close()
        } catch (_: Exception) {}
        rtspReader = null
        
        try {
            rtspSocket?.close()
        } catch (_: Exception) {}
        rtspSocket = null
        
        // Close UDP sockets (this will unblock any blocking receive() calls)
        logD("Closing UDP sockets...")
        try {
            audioSocket?.close()
        } catch (_: Exception) {}
        audioSocket = null
        try {
            audioTcpOutput?.close()
        } catch (_: Exception) {}
        audioTcpOutput = null
        try {
            audioTcpSocket?.close()
        } catch (_: Exception) {}
        audioTcpSocket = null
        
        try {
            controlSocket?.close()
        } catch (_: Exception) {}
        controlSocket = null
        
        try {
            timingSocket?.close()
        } catch (_: Exception) {}
        timingSocket = null
        
        // Reset state
        serverPort = 0
        serverControlPort = 0
        serverTimingPort = 0
        serverSessionId = null
        syncSequence = 0
        audioTransport = "udp"
        tcpAudioPacketCount = 0
        
        // Clear audio buffer
        synchronized(audioBuffer) {
            audioBuffer.reset()
        }
        
        synchronized(retransmitCache) {
            retransmitCache.clear()
        }

        digestChallenge = null
        cachedAuthorizationHeader = null
        digestNonceCount = 0
        logD("Teardown complete")
        callback?.onDisconnected()
    }
    
    /**
     * Stop the timing responder thread safely
     */
    private fun stopTimingResponder() {
        isTimingRunning.set(false)
        // Socket close will interrupt the blocking receive()
    }

    private fun buildRtspRequest(
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: String = "",
        sessionId: String? = null
    ): String {
        val sb = StringBuilder()
        // URL format per AirPlay spec: rtsp://host/sessionId (no port in URL)
        sb.append("$method rtsp://$host/$localSessionId RTSP/1.0\r\n")
        sb.append("CSeq: ${cSeq.incrementAndGet()}\r\n")
        sb.append("User-Agent: $USER_AGENT\r\n")
        sb.append("Client-Instance: $clientInstance\r\n")
        sb.append("DACP-ID: $dacpId\r\n")
        sb.append("Active-Remote: $activeRemote\r\n")
        sb.append("X-Apple-Device-ID: $localMacAddress\r\n")
        cachedAuthorizationHeader?.takeIf { it.isNotBlank() }?.let {
            sb.append("Authorization: $it\r\n")
        }

        if (sessionId != null) {
            sb.append("Session: $sessionId\r\n")
        }

        headers.forEach { (key, value) ->
            sb.append("$key: $value\r\n")
        }

        sb.append("\r\n")
        sb.append(body)

        return sb.toString()
    }

    /**
     * Send empty metadata to the speaker - some receivers need this to start playback
     */
    private fun sendMetadata(): Boolean {
        val metadata = "metadata: \r\n"
        val request = buildRtspRequest(
            "SET_PARAMETER",
            mapOf(
                "Content-Type" to "text/parameters",
                "Content-Length" to metadata.length.toString()
            ),
            metadata,
            sessionId = serverSessionId
        )
        return sendRtspRequest(request)?.first == 200
    }

    private fun parseRtspResponse(): Pair<Int, Map<String, String>>? {
        val headers = mutableMapOf<String, String>()

        val statusLine = rtspReader?.readLine() ?: return null
        val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: return null

        while (true) {
            val line = rtspReader?.readLine() ?: break
            if (line.isEmpty()) break

            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
        }

        return statusCode to headers
    }

    private fun rememberRtpPacket(sequence: Int, packet: ByteArray) {
        synchronized(retransmitCache) {
            retransmitCache[sequence and 0xFFFF] = packet
        }
    }

    private fun lookupRtpPacket(sequence: Int): ByteArray? {
        synchronized(retransmitCache) {
            return retransmitCache[sequence and 0xFFFF]
        }
    }

    private fun sendRtspRequest(request: String): Pair<Int, Map<String, String>>? {
        synchronized(rtspRequestLock) {
            rtspWriter?.print(request)
            rtspWriter?.flush()
            val response = parseRtspResponse() ?: return null
            if (response.first != 401) return response

            val challengeHeader = response.second.entries.firstOrNull {
                it.key.equals("WWW-Authenticate", ignoreCase = true)
            }?.value
            val challenge = parseWwwAuthenticate(challengeHeader)
            if (challenge == null) {
                logE("401 received without supported WWW-Authenticate header")
                return response
            }
            val authorization = buildAuthorizationHeader(challenge, request)
            if (authorization.isNullOrBlank()) {
                logE("401 auth challenge present, but no credentials available for ${challenge.scheme}")
                return response
            }

            digestChallenge = challenge
            cachedAuthorizationHeader = authorization
            val retryRequest = updateRequestForRetryWithAuthorization(request, authorization)
            rtspWriter?.print(retryRequest)
            rtspWriter?.flush()
            val retryResponse = parseRtspResponse()
            if (retryResponse?.first == 401) {
                cachedAuthorizationHeader = null
            }
            return retryResponse
        }
    }

    private fun parseWwwAuthenticate(header: String?): DigestChallenge? {
        if (header.isNullOrBlank()) return null
        val trimmed = header.trim()
        return when {
            trimmed.startsWith("Digest", ignoreCase = true) -> {
                val params = parseAuthParams(trimmed.substringAfter(" ", ""))
                DigestChallenge(
                    scheme = "Digest",
                    realm = params["realm"],
                    nonce = params["nonce"],
                    algorithm = params["algorithm"],
                    qop = params["qop"],
                    opaque = params["opaque"]
                )
            }
            trimmed.startsWith("Basic", ignoreCase = true) -> DigestChallenge(
                scheme = "Basic",
                realm = null,
                nonce = null,
                algorithm = null,
                qop = null,
                opaque = null
            )
            else -> null
        }
    }

    private fun parseAuthParams(params: String): Map<String, String> {
        val regex = Regex("""(\w+)=("([^"]*)"|[^,]+)""")
        val result = mutableMapOf<String, String>()
        regex.findAll(params).forEach { match ->
            val key = match.groupValues[1].trim().lowercase()
            val value = match.groupValues[3].ifEmpty { match.groupValues[2] }.trim().trim('"')
            result[key] = value
        }
        return result
    }

    private fun buildAuthorizationHeader(challenge: DigestChallenge, request: String): String? {
        val password = rtspPassword ?: return null
        val firstLine = request.lineSequence().firstOrNull()?.trim() ?: return null
        val parts = firstLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        val uri = parts[1]
        val userName = "AirPlay"

        if (challenge.scheme.equals("Basic", ignoreCase = true)) {
            val token = Base64.getEncoder().encodeToString("$userName:$password".toByteArray())
            return "Basic $token"
        }

        val realm = challenge.realm ?: return null
        val nonce = challenge.nonce ?: return null
        val algorithm = (challenge.algorithm ?: "MD5").uppercase()
        if (algorithm != "MD5") {
            logE("Unsupported Digest algorithm: $algorithm")
            return null
        }

        val ha1 = md5Hex("$userName:$realm:$password")
        val ha2 = md5Hex("$method:$uri")
        val qopToken = challenge.qop?.split(",")?.map { it.trim() }?.firstOrNull { it.equals("auth", ignoreCase = true) }
        return if (qopToken != null) {
            digestNonceCount += 1
            val nc = "%08x".format(digestNonceCount)
            val cnonce = generateHexId(8).lowercase()
            val response = md5Hex("$ha1:$nonce:$nc:$cnonce:$qopToken:$ha2")
            buildString {
                append("Digest username=\"$userName\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\", qop=$qopToken, nc=$nc, cnonce=\"$cnonce\"")
                challenge.opaque?.takeIf { it.isNotBlank() }?.let { append(", opaque=\"$it\"") }
                append(", algorithm=MD5")
            }
        } else {
            val response = md5Hex("$ha1:$nonce:$ha2")
            buildString {
                append("Digest username=\"$userName\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\"")
                challenge.opaque?.takeIf { it.isNotBlank() }?.let { append(", opaque=\"$it\"") }
                append(", algorithm=MD5")
            }
        }
    }

    private fun updateRequestForRetryWithAuthorization(request: String, authorization: String): String {
        val lines = request.split("\r\n").toMutableList()
        var cseqLineIndex = -1
        var authLineIndex = -1
        var blankLineIndex = -1
        lines.forEachIndexed { index, line ->
            if (line.startsWith("CSeq:", ignoreCase = true)) cseqLineIndex = index
            if (line.startsWith("Authorization:", ignoreCase = true)) authLineIndex = index
            if (line.isEmpty() && blankLineIndex == -1) blankLineIndex = index
        }
        if (cseqLineIndex >= 0) {
            lines[cseqLineIndex] = "CSeq: ${cSeq.incrementAndGet()}"
        }
        val newAuth = "Authorization: $authorization"
        if (authLineIndex >= 0) {
            lines[authLineIndex] = newAuth
        } else if (blankLineIndex >= 0) {
            lines.add(blankLineIndex, newAuth)
        } else {
            lines.add(newAuth)
            lines.add("")
        }
        return lines.joinToString("\r\n")
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun parseTransportHeader(transport: String) {
        // Determine transport based on user preference or server response
        val serverSupportsTcp = transport.contains("RTP/AVP/TCP", ignoreCase = true)
        audioTransport = when (transportMode) {
            "tcp" -> {
                logD("Using TCP transport (forced by user preference)")
                "tcp"
            }
            "udp" -> {
                logD("Using UDP transport (forced by user preference)")
                "udp"
            }
            else -> { // "auto" or null
                if (serverSupportsTcp) {
                    logD("Server supports TCP, using TCP transport")
                    "tcp"
                } else {
                    logD("Server supports UDP, using UDP transport")
                    "udp"
                }
            }
        }
        transport.split(";").forEach { part ->
            val trimmedPart = part.trim()
            when {
                trimmedPart.startsWith("server_port=") -> {
                    serverPort = trimmedPart.substringAfter("=").toIntOrNull() ?: 0
                    logD("Parsed server_port (audio): $serverPort")
                }
                trimmedPart.startsWith("control_port=") -> {
                    serverControlPort = trimmedPart.substringAfter("=").toIntOrNull() ?: 0
                    logD("Parsed control_port (server): $serverControlPort")
                }
                trimmedPart.startsWith("timing_port=") -> {
                    serverTimingPort = trimmedPart.substringAfter("=").toIntOrNull() ?: 0
                    logD("Parsed timing_port (server): $serverTimingPort")
                }
            }
        }
    }

    private fun openAudioTcpSocket() {
        if (serverPort == 0) {
            logE("Cannot open audio TCP socket - serverPort is 0")
            return
        }
        try {
            audioTcpSocket?.close()
        } catch (_: Exception) {}
        tcpAudioPacketCount = 0
        audioTcpSocket = Socket(host, serverPort).apply {
            tcpNoDelay = true
        }
        audioTcpOutput = BufferedOutputStream(audioTcpSocket!!.getOutputStream())
        logD("Opened audio TCP socket to $host:$serverPort")
    }

    private fun logTcpAudioPacket(rtpPacket: ByteArray) {
        tcpAudioPacketCount++
        if (tcpAudioPacketCount <= 3) {
            val preview = rtpPacket.take(12).joinToString(" ") { "%02X".format(it) }
            logD("TCP audio packet #$tcpAudioPacketCount raw bytes: $preview")
        }
    }

    // SDP payload is chosen from the receiver's advertised RAOP capabilities when available.
    
    private fun buildSdp(localIp: String, rsaAesKey: String?, aesIv: String?): String {
        val sdpLines = mutableListOf(
            "v=0",
            "o=iTunes $localSessionId 0 IN IP4 $localIp",
            "s=iTunes",
            "c=IN IP4 $host",
            "t=0 0",
            "m=audio 0 RTP/AVP 96"
        )

        if (useAlacEncoding) {
            sdpLines.add("a=rtpmap:96 AppleLossless")
            // fmtp 字段用空格分隔（RFC 4566），与 shairport-sync 一致
            // frameLength compatVer bitDepth riceMult riceInit riceLimit numCh maxRun maxFrameBytes(0=VBR) avgBR sampleRate
            sdpLines.add("a=fmtp:96 352 0 16 40 10 14 2 255 0 0 44100")
        } else {
            sdpLines.add("a=rtpmap:96 L16/44100/2")
        }

        sdpLines.add("a=latency:$streamLatencySamples")

        if (useEncryption && rsaAesKey != null && aesIv != null) {
            sdpLines.add("a=rsaaeskey:$rsaAesKey")
            sdpLines.add("a=aesiv:$aesIv")
        } else {
            logD("ENCRYPTION DISABLED - streaming unencrypted ${if (useAlacEncoding) "ALAC" else "L16"} audio")
        }

        return sdpLines.joinToString("\r\n") + "\r\n"
    }

    private fun buildRtpPacket(data: ByteArray): ByteArray {
        val header = ByteArray(12)

        // RTP header
        header[0] = 0x80.toByte() // Version 2
        header[1] = 0x60.toByte() // Payload type 96

        // Sequence number (big endian)
        header[2] = (rtpSequence shr 8).toByte()
        header[3] = rtpSequence.toByte()

        // Timestamp (big endian)
        header[4] = (rtpTimestamp shr 24).toByte()
        header[5] = (rtpTimestamp shr 16).toByte()
        header[6] = (rtpTimestamp shr 8).toByte()
        header[7] = rtpTimestamp.toByte()

        // SSRC (big endian)
        header[8] = (ssrc shr 24).toByte()
        header[9] = (ssrc shr 16).toByte()
        header[10] = (ssrc shr 8).toByte()
        header[11] = ssrc.toByte()

        return header + data
    }

    private fun generateSessionId(): String {
        return Random.nextLong(0, Long.MAX_VALUE).toString()
    }

    private fun generateClientId(): String {
        val bytes = ByteArray(8)
        Random.nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }
    
    private fun generateHexId(bytes: Int): String {
        val data = ByteArray(bytes)
        Random.nextBytes(data)
        return data.joinToString("") { "%02X".format(it) }
    }

    /**
     * Swap byte order for 16-bit PCM samples (little-endian to big-endian)
     * L16 format (RFC 3551) requires network byte order (big-endian)
     */
    private fun swapEndianness(data: ByteArray): ByteArray {
        val result = ByteArray(data.size)
        for (i in 0 until data.size step 2) {
            if (i + 1 < data.size) {
                result[i] = data[i + 1]
                result[i + 1] = data[i]
            }
        }
        return result
    }

    /**
     * Encrypt audio data using AES-128-CBC
     * AirPlay uses a modified CBC mode where only complete 16-byte blocks are encrypted,
     * and the remaining bytes (< 16) are left unencrypted at the end.
     * Each packet uses a fresh cipher starting from the original IV.
     */
    private fun encryptAudio(data: ByteArray): ByteArray {
        val key = aesKey ?: return data
        val iv = aesIv ?: return data
        
        try {
            // Create a fresh cipher for each packet with the original IV
            // AirPlay resets the IV for each packet (unlike standard CBC chaining)
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            
            // Only encrypt complete 16-byte blocks
            val blockSize = 16
            val numCompleteBlocks = data.size / blockSize
            val encryptedSize = numCompleteBlocks * blockSize
            val remainingBytes = data.size - encryptedSize
            
            if (encryptedSize == 0) {
                // No complete blocks to encrypt, return data as-is
                return data
            }
            
            // Encrypt the complete blocks
            val toEncrypt = data.copyOfRange(0, encryptedSize)
            val encrypted = cipher.doFinal(toEncrypt)
            
            // Combine encrypted blocks with unencrypted remainder
            return if (remainingBytes > 0) {
                val remainder = data.copyOfRange(encryptedSize, data.size)
                encrypted + remainder
            } else {
                encrypted
            }
        } catch (e: Exception) {
            logE("Audio encryption failed: ${e.message}")
            return data // Fall back to unencrypted on error
        }
    }

    fun isConnected(): Boolean = isConnected.get()
    fun isStreaming(): Boolean = isStreaming.get()

    private fun configureCompatibility() {
        val codecSet = parseCapabilityList(codecCapabilities)
        val encryptionSet = parseCapabilityList(encryptionCapabilities)

        val detectedAlacEncoding = when {
            codecSet.contains("0") -> false
            codecSet.contains("1") -> true
            else -> false
        }

        val detectedEncryption = when {
            encryptionSet.contains("1") -> true
            encryptionSet.contains("0") -> false
            else -> true
        }

        useAlacEncoding = forceAlacEncoding ?: detectedAlacEncoding
        useEncryption = forceEncryption ?: detectedEncryption
        currentRsaPaddingMode = when (rsaPaddingMode?.uppercase()) {
            RSA_PADDING_PKCS1 -> RSA_PADDING_PKCS1
            else -> RSA_PADDING_OAEP
        }

        logD(
            "Compatibility: cn=${codecCapabilities ?: "<unknown>"} et=${encryptionCapabilities ?: "<unknown>"} " +
                "=> codec=${if (useAlacEncoding) "ALAC" else "L16"} encryption=${if (useEncryption) "RSA/AES" else "none"} rsa=$currentRsaPaddingMode"
        )
    }

    private fun parseCapabilityList(value: String?): Set<String> {
        if (value.isNullOrBlank()) return emptySet()
        return value.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }
    
    // Logging helpers - log to both Android logcat and web LogServer
    private fun logD(msg: String) {
        Log.d(TAG, msg)
        LogServer.d(TAG, msg)
    }
    
    private fun logE(msg: String) {
        Log.e(TAG, msg)
        LogServer.e(TAG, msg)
    }
}
