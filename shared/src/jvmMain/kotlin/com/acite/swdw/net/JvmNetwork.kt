package com.acite.swdw.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class JvmUdpSocket : PlatformUdpSocket {
    private var socket: DatagramSocket? = null

    override fun open(bindPort: Int) {
        close()
        socket = if (bindPort > 0) DatagramSocket(bindPort) else DatagramSocket()
        socket?.receiveBufferSize = 256 * 1024
        socket?.sendBufferSize = 64 * 1024
    }

    override fun send(packet: ByteArray, offset: Int, length: Int, host: String, port: Int) {
        val s = socket ?: return
        try {
            val addr = InetAddress.getByName(host)
            val dp = DatagramPacket(packet, offset, length, addr, port)
            s.send(dp)
        } catch (_: Exception) {}
    }

    override fun receive(buffer: ByteArray): UdpPacket? {
        val s = socket ?: return null
        return try {
            val dp = DatagramPacket(buffer, buffer.size)
            s.receive(dp)
            UdpPacket(
                data = buffer,
                length = dp.length,
                senderHost = dp.address?.hostAddress ?: "",
                senderPort = dp.port
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
    }
}

class JvmHttpFetcher : HttpFetcher {
    override suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}

actual fun createPlatformUdpSocket(): PlatformUdpSocket = JvmUdpSocket()
actual fun createHttpFetcher(): HttpFetcher = JvmHttpFetcher()
