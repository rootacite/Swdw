package com.acite.swdw.model

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    OFFLINE,
    ERROR
}

data class OsInfo(
    val osName: String = "Linux",
    val kernel: String = "-",
    val hostname: String = "localhost",
    val cpuModel: String = "CPU",
    val cpuCores: Int = 1,
    val memTotalMb: Long = 0,
    val memUsedMb: Long = 0,
    val uptimeSecs: Long = 0
)

data class AudioDeviceInfo(
    val name: String,
    val isDefault: Boolean,
    val sampleRates: List<Int> = emptyList(),
    val maxChannels: Int = 2
)

data class VirtualMicInfo(
    val name: String = "SWD-Virtual-Mic",
    val description: String = "SWD 移动端麦克风",
    val isStreaming: Boolean = false,
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val peak: Float = 0f,
    val totalReceivedFrames: Long = 0
)

data class AudioStackInfo(
    val pipewireVersion: String = "1.0",
    val cpalHost: String = "PipeWire",
    val defaultOutputDevice: String = "Default Sink",
    val defaultInputDevice: String = "Default Source",
    val captureRate: Int = 96000,
    val captureChannels: Int = 2,
    val captureFormat: String = "16-bit PCM",
    val totalCapturedFrames: Long = 0,
    val peakLeft: Float = 0f,
    val peakRight: Float = 0f,
    val devices: List<AudioDeviceInfo> = emptyList(),
    val virtualMic: VirtualMicInfo = VirtualMicInfo()
)

data class SystemStatus(
    val os: OsInfo = OsInfo(),
    val audio: AudioStackInfo = AudioStackInfo(),
    val activeListeners: Int = 0,
    val serverTime: Long = 0
)

data class AudioChunk(
    val seq: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val pcmBytes: ByteArray
)
