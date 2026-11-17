package com.acite.swdw.net

data class UdpPacket(
    val data: ByteArray,
    val length: Int,
    val senderHost: String,
    val senderPort: Int
)

interface PlatformUdpSocket {
    fun open(bindPort: Int = 0)
    fun send(packet: ByteArray, offset: Int, length: Int, host: String, port: Int)
    fun receive(buffer: ByteArray): UdpPacket?
    fun close()
}

interface HttpFetcher {
    suspend fun get(url: String): String?
}

expect fun createPlatformUdpSocket(): PlatformUdpSocket
expect fun createHttpFetcher(): HttpFetcher
