package com.acite.swdw.audio

interface AudioPlayer {
    fun init(sampleRate: Int, channels: Int)
    fun write(data: ByteArray, offset: Int, size: Int)
    fun setVolume(volume: Float)
    fun start()
    fun pause()
    fun stop()
    fun release()
    val isPlaying: Boolean
}

interface AudioRecorder {
    fun startRecording(sampleRate: Int, onAudioData: (ByteArray, Int) -> Unit)
    fun stopRecording()
    fun setGain(gain: Float)
    fun setEchoCancellation(enabled: Boolean)
    fun setNoiseSuppression(enabled: Boolean)
    val isRecording: Boolean
}

expect fun createAudioPlayer(): AudioPlayer
expect fun createAudioRecorder(): AudioRecorder
