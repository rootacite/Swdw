package com.acite.swdw

import com.acite.swdw.model.ConnectionState
import com.acite.swdw.protocol.SwdProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SharedCommonTest {

    @Test
    fun testPingPacketCreation() {
        val clientId = 12345
        val packet = SwdProtocol.createPingPacket(clientId)
        assertEquals(13, packet.size)

        // Magic bytes
        assertEquals('S'.code.toByte(), packet[0])
        assertEquals('W'.code.toByte(), packet[1])
        assertEquals('D'.code.toByte(), packet[2])
        assertEquals(1, packet[3])

        // Type
        assertEquals(SwdProtocol.PKT_PING, packet[4])

        // Client ID
        val parsedClientId = SwdProtocol.readInt32Be(packet, 5)
        assertEquals(clientId, parsedClientId)

        // Command "PING"
        val cmd = packet.copyOfRange(9, 13).decodeToString()
        assertEquals("PING", cmd)
    }

    @Test
    fun testEchoPacketParsing() {
        // 1. Raw ECHO
        val rawEcho = "ECHO".encodeToByteArray()
        assertTrue(SwdProtocol.isEchoPacket(rawEcho, rawEcho.size))
        val rawParsed = SwdProtocol.parseEcho(rawEcho, rawEcho.size)
        assertNotNull(rawParsed)

        // 2. Protocol ECHO
        val protoEcho = ByteArray(17)
        SwdProtocol.MAGIC_BYTES.copyInto(protoEcho, 0)
        protoEcho[4] = SwdProtocol.PKT_ECHO
        SwdProtocol.writeInt32Be(protoEcho, 5, 54321)
        "ECHO".encodeToByteArray().copyInto(protoEcho, 9)
        SwdProtocol.writeInt32Be(protoEcho, 13, 96000)

        assertTrue(SwdProtocol.isEchoPacket(protoEcho, protoEcho.size))
        val protoParsed = SwdProtocol.parseEcho(protoEcho, protoEcho.size)
        assertNotNull(protoParsed)
        assertEquals(54321, protoParsed.clientId)
        assertEquals(96000, protoParsed.sampleRate)

        // 3. Invalid packet
        val invalid = byteArrayOf(1, 2, 3)
        assertFalse(SwdProtocol.isEchoPacket(invalid, invalid.size))
    }

    @Test
    fun testOfflineConnectionState() {
        assertEquals("OFFLINE", ConnectionState.OFFLINE.name)
    }
}