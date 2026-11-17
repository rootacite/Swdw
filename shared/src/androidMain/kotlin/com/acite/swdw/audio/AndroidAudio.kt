package com.acite.swdw.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlin.concurrent.thread

class AndroidAudioPlayer : AudioPlayer {
    private var track: AudioTrack? = null
    private var currentSampleRate: Int = 96000
    private var currentChannels: Int = 2
    private var currentVolume: Float = 0.85f
    private var playing = false

    override fun init(sampleRate: Int, channels: Int) {
        if (track != null && currentSampleRate == sampleRate && currentChannels == channels) {
            return
        }
        release()
        currentSampleRate = sampleRate
        currentChannels = channels

        try {
            val channelMask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBufSize = AudioTrack.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufSize, sampleRate * channels * 2 / 10) // ~100ms buffer

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelMask)
                .build()

            val t = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            t.setVolume(currentVolume)
            track = t
        } catch (e: Exception) {
            println("[AndroidAudio] Failed to init AudioTrack: ${e.message}")
        }
    }

    override fun write(data: ByteArray, offset: Int, size: Int) {
        val t = track ?: return
        if (playing && t.state == AudioTrack.STATE_INITIALIZED) {
            t.write(data, offset, size, AudioTrack.WRITE_BLOCKING)
        }
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        track?.setVolume(currentVolume)
    }

    override fun start() {
        try {
            track?.play()
            playing = true
        } catch (e: Exception) {
            println("[AndroidAudio] play error: ${e.message}")
        }
    }

    override fun pause() {
        try {
            track?.pause()
            playing = false
        } catch (_: Exception) {}
    }

    override fun stop() {
        try {
            track?.stop()
            track?.flush()
            playing = false
        } catch (_: Exception) {}
    }

    override fun release() {
        stop()
        try {
            track?.release()
        } catch (_: Exception) {}
        track = null
    }

    override val isPlaying: Boolean
        get() = playing
}

class AndroidAudioRecorder : AudioRecorder {
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var recording = false
    private var currentGain = 1.0f
    private var isAecEnabled = true
    private var isNsEnabled = true
    private var workerThread: Thread? = null

    override fun startRecording(sampleRate: Int, onAudioData: (ByteArray, Int) -> Unit) {
        stopRecording()

        try {
            val channelMask = AudioFormat.CHANNEL_IN_MONO
            val minBufSize = AudioRecord.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufSize, 2048)

            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (r.state != AudioRecord.STATE_INITIALIZED) {
                println("[AndroidMic] AudioRecord not initialized")
                return
            }

            val audioSessionId = r.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = isAecEnabled
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = isNsEnabled
                }
            }

            r.startRecording()
            record = r
            recording = true

            workerThread = thread(isDaemon = true) {
                val buf = ByteArray(1024)
                while (recording && r.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = r.read(buf, 0, buf.size)
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
            }
        } catch (e: Exception) {
            println("[AndroidMic] Failed to start AudioRecord: ${e.message}")
        }
    }

    override fun stopRecording() {
        recording = false
        workerThread?.interrupt()
        workerThread = null

        try {
            aec?.release()
            aec = null
            ns?.release()
            ns = null
            record?.stop()
            record?.release()
            record = null
        } catch (_: Exception) {}
    }

    override fun setGain(gain: Float) {
        currentGain = gain.coerceIn(0.1f, 3.0f)
    }

    override fun setEchoCancellation(enabled: Boolean) {
        isAecEnabled = enabled
        aec?.enabled = enabled
    }

    override fun setNoiseSuppression(enabled: Boolean) {
        isNsEnabled = enabled
        ns?.enabled = enabled
    }

    override val isRecording: Boolean
        get() = recording
}

actual fun createAudioPlayer(): AudioPlayer = AndroidAudioPlayer()
actual fun createAudioRecorder(): AudioRecorder = AndroidAudioRecorder()
