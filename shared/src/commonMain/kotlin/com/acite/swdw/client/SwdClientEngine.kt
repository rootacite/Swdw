package com.acite.swdw.client

import androidx.compose.runtime.*
import com.acite.swdw.audio.createAudioPlayer
import com.acite.swdw.audio.createAudioRecorder
import com.acite.swdw.currentTimeMillis
import com.acite.swdw.getPlatform
import com.acite.swdw.model.ConnectionState
import com.acite.swdw.model.StatusJsonParser
import com.acite.swdw.model.SystemStatus
import com.acite.swdw.net.createHttpFetcher
import com.acite.swdw.net.createPlatformUdpSocket
import com.acite.swdw.protocol.SwdProtocol
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

class SwdClientEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    var serverHost by mutableStateOf(
        if (getPlatform().name.startsWith("Android")) "192.168.50.1" else "127.0.0.1"
    )
    var serverPort by mutableStateOf(9890)

    var connectionState by mutableStateOf(ConnectionState.DISCONNECTED)
    var latencyMs by mutableStateOf(18)
    var isPlaying by mutableStateOf(false)
    var volume by mutableStateOf(0.85f)
    var isMuted by mutableStateOf(false)

    var isMicStreaming by mutableStateOf(false)
    var micGain by mutableStateOf(1.0f)
    var micPeak by mutableStateOf(0f)
    var echoCancellation by mutableStateOf(true)
    var noiseSuppression by mutableStateOf(true)

    var peakLeft by mutableStateOf(0f)
    var peakRight by mutableStateOf(0f)
    var visualizerBars by mutableStateOf(FloatArray(32) { 0f })

    var systemStatus by mutableStateOf<SystemStatus?>(null)
    var totalPacketsReceived by mutableStateOf(0L)
    var packetsPerSecond by mutableStateOf(0)
    var sampleRate by mutableStateOf(96000)

    var lastHeartbeatTime by mutableStateOf<Long?>(null)
    var lastHeartbeatElapsedSecs by mutableStateOf(-1L)

    val heartbeatElapsedText: String
        get() {
            val elapsed = lastHeartbeatElapsedSecs
            return when {
                elapsed < 0L -> "无心跳"
                elapsed == 0L -> "刚刚"
                elapsed < 60L -> "${elapsed}秒前"
                else -> "${elapsed / 60}分${elapsed % 60}秒前"
            }
        }

    private val udpSocket = createPlatformUdpSocket()
    private val audioPlayer = createAudioPlayer()
    private val audioRecorder = createAudioRecorder()
    private val httpFetcher = createHttpFetcher()

    private val clientId = Random.nextInt(1, 0x7FFFFFFF)
    private var lastPingSentTime = 0L
    private var micSeq = 0L

    @Volatile
    private var isWaitingForEcho = false

    private var receiveJob: Job? = null
    private var heartbeatJob: Job? = null
    private var statusJob: Job? = null
    private var decayJob: Job? = null
    private var packetRateJob: Job? = null

    init {
        startDecayLoop()
        startPacketRateLoop()
    }

    fun connect(host: String = serverHost, port: Int = serverPort) {
        serverHost = host.trim()
        serverPort = port

        disconnect()
        connectionState = ConnectionState.CONNECTING
        lastHeartbeatTime = null
        lastHeartbeatElapsedSecs = -1L
        isWaitingForEcho = false

        try {
            udpSocket.open(0)
        } catch (e: Exception) {
            println("[Client] UDP open failed: ${e.message}")
            connectionState = ConnectionState.ERROR
            return
        }

        startReceiveLoop()
        startHeartbeatLoop()
        startStatusLoop()
    }

    fun disconnect() {
        stopPlayback()
        stopMic()

        heartbeatJob?.cancel()
        heartbeatJob = null

        statusJob?.cancel()
        statusJob = null

        // Send unsubscribe packet before closing
        try {
            val unsub = SwdProtocol.createUnsubscribePacket()
            udpSocket.send(unsub, 0, unsub.size, serverHost, serverPort)
        } catch (_: Exception) {}

        receiveJob?.cancel()
        receiveJob = null

        udpSocket.close()
        connectionState = ConnectionState.DISCONNECTED
        lastHeartbeatTime = null
        lastHeartbeatElapsedSecs = -1L
        isWaitingForEcho = false
        packetsPerSecond = 0
    }

    fun startPlayback() {
        if (connectionState != ConnectionState.CONNECTED && connectionState != ConnectionState.CONNECTING) {
            connect()
        }
        audioPlayer.init(sampleRate, 2)
        audioPlayer.setVolume(if (isMuted) 0f else volume)
        audioPlayer.start()
        isPlaying = true

        // Subscribe to audio downlink stream from server
        try {
            val subPkt = SwdProtocol.createHeartbeatPacket(clientId)
            udpSocket.send(subPkt, 0, subPkt.size, serverHost, serverPort)
        } catch (e: Exception) {
            println("[Client] Failed to send audio subscribe packet: ${e.message}")
        }
    }

    fun stopPlayback() {
        audioPlayer.stop()
        isPlaying = false
        peakLeft = 0f
        peakRight = 0f
        visualizerBars = FloatArray(32) { 0f }
        packetsPerSecond = 0

        // Unsubscribe from audio downlink stream so server stops streaming
        try {
            val unsubPkt = SwdProtocol.createUnsubscribePacket()
            udpSocket.send(unsubPkt, 0, unsubPkt.size, serverHost, serverPort)
        } catch (e: Exception) {
            println("[Client] Failed to send audio unsubscribe packet: ${e.message}")
        }
    }

    fun togglePlayback() {
        if (isPlaying) stopPlayback() else startPlayback()
    }

    fun updateVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        isMuted = false
        audioPlayer.setVolume(volume)
    }

    fun toggleMute() {
        isMuted = !isMuted
        audioPlayer.setVolume(if (isMuted) 0f else volume)
    }

    fun startMic() {
        if (connectionState != ConnectionState.CONNECTED && connectionState != ConnectionState.CONNECTING) {
            connect()
        }
        audioRecorder.setGain(micGain)
        audioRecorder.setEchoCancellation(echoCancellation)
        audioRecorder.setNoiseSuppression(noiseSuppression)

        val micRate = 48000
        audioRecorder.startRecording(micRate) { data, length ->
            if (isMicStreaming && length > 0) {
                // Calculate mic peak
                var maxSample = 0
                var i = 0
                while (i < length - 1) {
                    val s = (data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)
                    val sSigned = if (s > 32767) s - 65536 else s
                    val a = abs(sSigned)
                    if (a > maxSample) maxSample = a
                    i += 2
                }
                micPeak = (maxSample / 32768f).coerceIn(0f, 1f)

                // Send UDP Mic packet
                val pkt = SwdProtocol.createMicPacket(
                    seq = micSeq++,
                    sampleRate = micRate,
                    channels = 1,
                    pcmData = data,
                    offset = 0,
                    length = length
                )
                try {
                    udpSocket.send(pkt, 0, pkt.size, serverHost, serverPort)
                } catch (_: Exception) {}
            }
        }
        isMicStreaming = true
    }

    fun stopMic() {
        audioRecorder.stopRecording()
        isMicStreaming = false
        micPeak = 0f
    }

    fun toggleMic() {
        if (isMicStreaming) stopMic() else startMic()
    }

    fun updateMicGain(g: Float) {
        micGain = g.coerceIn(0.1f, 3.0f)
        audioRecorder.setGain(micGain)
    }

    fun updateEchoCancellation(enabled: Boolean) {
        echoCancellation = enabled
        audioRecorder.setEchoCancellation(enabled)
    }

    fun updateNoiseSuppression(enabled: Boolean) {
        noiseSuppression = enabled
        audioRecorder.setNoiseSuppression(enabled)
    }

    private fun startReceiveLoop() {
        receiveJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(65535)
            val currentBars = FloatArray(32) { 0f }

            while (isActive) {
                val packet = udpSocket.receive(buffer) ?: continue
                val len = packet.length
                if (len < 4) continue

                if (SwdProtocol.isEchoPacket(packet.data, len)) {
                    val now = currentTimeMillis()
                    lastHeartbeatTime = now
                    lastHeartbeatElapsedSecs = 0L
                    isWaitingForEcho = false
                    connectionState = ConnectionState.CONNECTED
                    if (lastPingSentTime > 0) {
                        val rtt = (now - lastPingSentTime).toInt().coerceIn(1, 9999)
                        latencyMs = rtt
                    }
                    val echoInfo = SwdProtocol.parseEcho(packet.data, len)
                    if (echoInfo?.sampleRate != null && echoInfo.sampleRate > 0) {
                        sampleRate = echoInfo.sampleRate
                    }
                } else if (len >= 5 && packet.data[4] == SwdProtocol.PKT_HEARTBEAT_ACK) {
                    val ack = SwdProtocol.parseHeartbeatAck(packet.data, len)
                    if (ack != null) {
                        val now = currentTimeMillis()
                        lastHeartbeatTime = now
                        lastHeartbeatElapsedSecs = 0L
                        isWaitingForEcho = false
                        connectionState = ConnectionState.CONNECTED
                        sampleRate = ack.sampleRate
                        if (lastPingSentTime > 0) {
                            val rtt = (now - lastPingSentTime).toInt().coerceIn(1, 9999)
                            latencyMs = rtt
                        }
                    }
                } else if (len >= 17 && packet.data[4] == SwdProtocol.PKT_AUDIO_DOWNLINK) {
                    val chunk = SwdProtocol.parseAudioPacket(packet.data, len)
                    if (chunk != null) {
                        totalPacketsReceived++
                        if (connectionState != ConnectionState.CONNECTED && connectionState != ConnectionState.OFFLINE) {
                            connectionState = ConnectionState.CONNECTED
                        }

                        if (isPlaying) {
                            audioPlayer.write(chunk.pcmBytes, 0, chunk.pcmBytes.size)

                            // Process Left & Right Channel VU Meter and Visualizer
                            var pL = 0f
                            var pR = 0f
                            val pcm = chunk.pcmBytes
                            val nSamples = pcm.size / 4 // 16-bit stereo = 4 bytes per frame

                            // Simple FFT/Band approximation for 32 spectrum bars
                            val step = max(1, nSamples / 32)
                            var barIdx = 0
                            var frameIdx = 0

                            while (frameIdx < nSamples && barIdx < 32) {
                                val offset = frameIdx * 4
                                val sL = (pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8)
                                val sR = (pcm[offset + 2].toInt() and 0xFF) or (pcm[offset + 3].toInt() shl 8)

                                val sLSigned = if (sL > 32767) sL - 65536 else sL
                                val sRSigned = if (sR > 32767) sR - 65536 else sR

                                val absL = abs(sLSigned) / 32768f
                                val absR = abs(sRSigned) / 32768f

                                if (absL > pL) pL = absL
                                if (absR > pR) pR = absR

                                val barMag = (absL + absR) * 0.5f
                                currentBars[barIdx] = max(currentBars[barIdx] * 0.8f, barMag)

                                barIdx++
                                frameIdx += step
                            }

                            peakLeft = max(peakLeft * 0.85f, pL)
                            peakRight = max(peakRight * 0.85f, pR)
                            visualizerBars = currentBars.copyOf()
                        }
                    }
                }
            }
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val pingStart = currentTimeMillis()
                lastPingSentTime = pingStart
                isWaitingForEcho = true

                // Send 8-second periodic PING packet
                val pingPacket = SwdProtocol.createPingPacket(clientId)
                try {
                    udpSocket.send(pingPacket, 0, pingPacket.size, serverHost, serverPort)
                    if (isPlaying) {
                        val subPkt = SwdProtocol.createHeartbeatPacket(clientId)
                        udpSocket.send(subPkt, 0, subPkt.size, serverHost, serverPort)
                    }
                } catch (e: Exception) {
                    println("[Client] Failed to send PING: ${e.message}")
                }

                // Wait up to 4000ms for ECHO reply
                val timeoutMs = 4000L
                val intervalMs = 8000L
                val checkStepMs = 100L
                var waitedMs = 0L
                var echoReceived = false

                while (waitedMs < timeoutMs && isActive) {
                    delay(checkStepMs)
                    waitedMs += checkStepMs
                    if (!isWaitingForEcho) {
                        echoReceived = true
                        break
                    }
                }

                // If 4 seconds have passed without ECHO reply, consider server offline
                if (!echoReceived && isWaitingForEcho && isActive) {
                    if (connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.CONNECTING) {
                        println("[Client] Heartbeat timeout (>4s) without ECHO! Server offline.")
                        connectionState = ConnectionState.OFFLINE
                    }
                }

                // Wait out the remainder of the 8-second interval from pingStart
                val totalElapsed = currentTimeMillis() - pingStart
                val remainingDelay = (intervalMs - totalElapsed).coerceAtLeast(0L)
                if (remainingDelay > 0 && isActive) {
                    delay(remainingDelay)
                }
            }
        }
    }

    private fun startStatusLoop() {
        statusJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val url = "http://$serverHost:$serverPort/api/status"
                    val json = httpFetcher.get(url)
                    if (json != null) {
                        systemStatus = StatusJsonParser.parse(json)
                    }
                } catch (_: Exception) {}
                delay(3000)
            }
        }
    }

    private fun startDecayLoop() {
        decayJob = scope.launch {
            var lastElapsedUpdate = 0L
            while (isActive) {
                delay(50)
                if (!isPlaying) {
                    if (peakLeft > 0.01f) peakLeft *= 0.8f else peakLeft = 0f
                    if (peakRight > 0.01f) peakRight *= 0.8f else peakRight = 0f
                    for (i in visualizerBars.indices) {
                        visualizerBars[i] *= 0.85f
                    }
                }
                if (!isMicStreaming) {
                    if (micPeak > 0.01f) micPeak *= 0.8f else micPeak = 0f
                }

                val now = currentTimeMillis()
                if (now - lastElapsedUpdate >= 500) {
                    lastElapsedUpdate = now
                    val hb = lastHeartbeatTime
                    if (hb != null) {
                        val sec = (now - hb) / 1000L
                        lastHeartbeatElapsedSecs = max(0L, sec)
                    } else {
                        lastHeartbeatElapsedSecs = -1L
                    }
                }
            }
        }
    }

    private fun startPacketRateLoop() {
        packetRateJob = scope.launch(Dispatchers.Default) {
            var lastCount = 0L
            while (isActive) {
                delay(1000L)
                val current = totalPacketsReceived
                val diff = (current - lastCount).coerceAtLeast(0L).toInt()
                lastCount = current
                packetsPerSecond = diff
            }
        }
    }
}
