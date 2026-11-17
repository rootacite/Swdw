package com.acite.swdw.audio

import javax.sound.sampled.*

class JvmAudioPlayer : AudioPlayer {
    private var line: SourceDataLine? = null
    private var currentSampleRate: Int = 96000
    private var currentChannels: Int = 2
    private var currentVolume: Float = 0.85f
    private var playing = false

    override fun init(sampleRate: Int, channels: Int) {
        if (line != null && currentSampleRate == sampleRate && currentChannels == channels) {
            return
        }
        release()
        currentSampleRate = sampleRate
        currentChannels = channels
        try {
            val format = AudioFormat(sampleRate.toFloat(), 16, channels, true, false)
            val info = DataLine.Info(SourceDataLine::class.java, format)
            val l = AudioSystem.getLine(info) as SourceDataLine
            val bufferSize = sampleRate * channels * 2 / 10
            l.open(format, bufferSize)
            line = l
            setVolume(currentVolume)
        } catch (e: Exception) {
            println("[JvmAudio] Failed to open audio line: ${e.message}")
        }
    }

    override fun write(data: ByteArray, offset: Int, size: Int) {
        line?.let { l ->
            if (playing && l.isOpen) {
                l.write(data, offset, size)
            }
        }
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        line?.let { l ->
            try {
                if (l.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    val ctrl = l.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
                    val min = ctrl.minimum
                    val max = ctrl.maximum
                    if (currentVolume <= 0.001f) {
                        ctrl.value = min
                    } else {
                        val gain = (20.0 * kotlin.math.log10(currentVolume.toDouble())).toFloat()
                        ctrl.value = gain.coerceIn(min, max)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    override fun start() {
        line?.start()
        playing = true
    }

    override fun pause() {
        line?.stop()
        playing = false
    }

    override fun stop() {
        line?.stop()
        line?.flush()
        playing = false
    }

    override fun release() {
        stop()
        line?.close()
        line = null
    }

    override val isPlaying: Boolean
        get() = playing
}

class JvmAudioRecorder : AudioRecorder {
    private var line: TargetDataLine? = null
    private var recording = false
    private var currentGain = 1.0f
    private var thread: Thread? = null

    override fun startRecording(sampleRate: Int, onAudioData: (ByteArray, Int) -> Unit) {
        stopRecording()
        try {
            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val l = AudioSystem.getLine(info) as TargetDataLine
            l.open(format)
            l.start()
            line = l
            recording = true

            thread = Thread {
                val buf = ByteArray(1024)
                while (recording && l.isOpen) {
                    val read = l.read(buf, 0, buf.size)
                    if (read > 0) {
                        if (currentGain != 1.0f) {
                            var i = 0
                            while (i < read - 1) {
                                val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
                                val sSigned = if (s > 32767) s - 65536 else s
                                val amplified = (sSigned * currentGain).toInt().coerceIn(-32768, 32767)
                                buf[i] = amplified.toByte()
                                buf[i + 1] = (amplified ushr 8).toByte()
                                i += 2
                            }
                        }
                        onAudioData(buf, read)
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            println("[JvmMic] Failed to open mic: ${e.message}")
        }
    }

    override fun stopRecording() {
        recording = false
        thread?.interrupt()
        thread = null
        line?.stop()
        line?.close()
        line = null
    }

    override fun setGain(gain: Float) {
        currentGain = gain.coerceIn(0.1f, 3.0f)
    }

    override fun setEchoCancellation(enabled: Boolean) {}
    override fun setNoiseSuppression(enabled: Boolean) {}

    override val isRecording: Boolean
        get() = recording
}

actual fun createAudioPlayer(): AudioPlayer = JvmAudioPlayer()
actual fun createAudioRecorder(): AudioRecorder = JvmAudioRecorder()
