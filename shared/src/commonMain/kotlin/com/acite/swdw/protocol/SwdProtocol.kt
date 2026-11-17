package com.acite.swdw.protocol

import com.acite.swdw.model.AudioChunk

object SwdProtocol {
    val MAGIC_BYTES = byteArrayOf('S'.code.toByte(), 'W'.code.toByte(), 'D'.code.toByte(), 1)

    const val PKT_AUDIO_DOWNLINK: Byte = 0x01
    const val PKT_MIC_UPLINK: Byte = 0x02
    const val PKT_HEARTBEAT_SUB: Byte = 0x03
    const val PKT_UNSUB: Byte = 0x04
    const val PKT_DISCOVERY: Byte = 0x05
    const val PKT_PING: Byte = 0x06

    const val PKT_HEARTBEAT_ACK: Byte = 0x83.toByte()
    const val PKT_DISCOVERY_ACK: Byte = 0x85.toByte()
    const val PKT_ECHO: Byte = 0x86.toByte()

    fun createPingPacket(clientId: Int): ByteArray {
        val pingBytes = "PING".encodeToByteArray()
        val buf = ByteArray(4 + 1 + 4 + pingBytes.size) // Magic(4) + Type(1) + clientId(4) + "PING"(4) = 13 bytes
        SystemCopy(MAGIC_BYTES, 0, buf, 0, 4)
        buf[4] = PKT_PING
        writeInt32Be(buf, 5, clientId)
        SystemCopy(pingBytes, 0, buf, 9, pingBytes.size)
        return buf
    }

    fun createHeartbeatPacket(clientId: Int): ByteArray {
        val buf = ByteArray(9)
        SystemCopy(MAGIC_BYTES, 0, buf, 0, 4)
        buf[4] = PKT_HEARTBEAT_SUB
        writeInt32Be(buf, 5, clientId)
        return buf
    }

    fun createUnsubscribePacket(): ByteArray {
        val buf = ByteArray(5)
        SystemCopy(MAGIC_BYTES, 0, buf, 0, 4)
        buf[4] = PKT_UNSUB
        return buf
    }

    fun createDiscoveryPacket(): ByteArray {
        val buf = ByteArray(5)
        SystemCopy(MAGIC_BYTES, 0, buf, 0, 4)
        buf[4] = PKT_DISCOVERY
        return buf
    }

    fun createMicPacket(
        seq: Long,
        sampleRate: Int,
        channels: Int,
        pcmData: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray {
        val buf = ByteArray(17 + length)
        SystemCopy(MAGIC_BYTES, 0, buf, 0, 4)
        buf[4] = PKT_MIC_UPLINK
        writeInt32Be(buf, 5, seq.toInt())
        writeInt32Be(buf, 9, sampleRate)
        buf[13] = channels.toByte()
        buf[14] = 0 // flags
        writeInt16Be(buf, 15, length)
        SystemCopy(pcmData, offset, buf, 17, length)
        return buf
    }

    fun parseAudioPacket(packet: ByteArray, length: Int): AudioChunk? {
        if (length < 17) return null
        if (!isMagicValid(packet)) return null
        if (packet[4] != PKT_AUDIO_DOWNLINK) return null

        val seq = readInt32Be(packet, 5).toLong() and 0xFFFFFFFFL
        val sampleRate = readInt32Be(packet, 9)
        val channels = packet[13].toInt() and 0xFF
        val bits = packet[14].toInt() and 0xFF
        val payloadLen = readInt16Be(packet, 15)

        val end = 17 + payloadLen
        if (end > length) return null

        val pcm = ByteArray(payloadLen)
        SystemCopy(packet, 17, pcm, 0, payloadLen)

        return AudioChunk(
            seq = seq,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bits,
            pcmBytes = pcm
        )
    }

    data class HeartbeatAck(val sampleRate: Int, val channels: Int, val serverTime: Long)

    data class EchoInfo(val clientId: Int, val sampleRate: Int)

    fun isEchoPacket(packet: ByteArray, length: Int): Boolean {
        if (length == 4 && packet[0] == 'E'.code.toByte() && packet[1] == 'C'.code.toByte() &&
            packet[2] == 'H'.code.toByte() && packet[3] == 'O'.code.toByte()
        ) {
            return true
        }
        if (length >= 5 && isMagicValid(packet) && packet[4] == PKT_ECHO) {
            return true
        }
        return false
    }

    fun parseEcho(packet: ByteArray, length: Int): EchoInfo? {
        if (length == 4 && packet[0] == 'E'.code.toByte() && packet[1] == 'C'.code.toByte() &&
            packet[2] == 'H'.code.toByte() && packet[3] == 'O'.code.toByte()
        ) {
            return EchoInfo(0, 0)
        }
        if (length < 9) return null
        if (!isMagicValid(packet)) return null
        if (packet[4] != PKT_ECHO) return null

        val cId = readInt32Be(packet, 5)
        val rate = if (length >= 17) readInt32Be(packet, 13) else 0
        return EchoInfo(cId, rate)
    }

    fun parseHeartbeatAck(packet: ByteArray, length: Int): HeartbeatAck? {
        if (length < 22) return null
        if (!isMagicValid(packet)) return null
        if (packet[4] != PKT_HEARTBEAT_ACK) return null

        val rate = readInt32Be(packet, 9)
        val channels = packet[13].toInt() and 0xFF
        val serverTime = readInt64Be(packet, 14)
        return HeartbeatAck(rate, channels, serverTime)
    }

    private fun isMagicValid(buf: ByteArray): Boolean {
        return buf[0] == MAGIC_BYTES[0] &&
                buf[1] == MAGIC_BYTES[1] &&
                buf[2] == MAGIC_BYTES[2] &&
                buf[3] == MAGIC_BYTES[3]
    }

    fun writeInt16Be(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 8).toByte()
        buf[offset + 1] = value.toByte()
    }

    fun writeInt32Be(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    fun readInt16Be(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 8) or
                (buf[offset + 1].toInt() and 0xFF)
    }

    fun readInt32Be(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
                ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                (buf[offset + 3].toInt() and 0xFF)
    }

    fun readInt64Be(buf: ByteArray, offset: Int): Long {
        var res = 0L
        for (i in 0 until 8) {
            res = (res shl 8) or (buf[offset + i].toLong() and 0xFFL)
        }
        return res
    }

    private fun SystemCopy(src: ByteArray, srcPos: Int, dest: ByteArray, destPos: Int, length: Int) {
        src.copyInto(dest, destPos, srcPos, srcPos + length)
    }
}
