package com.example.tvremote.core.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLan {
    private val macPattern = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")

    fun isValidMac(mac: String) = macPattern.matches(mac)

    suspend fun wake(mac: String, tvHost: String) = withContext(Dispatchers.IO) {
        require(isValidMac(mac)) { "Endereço MAC inválido" }
        val macBytes = mac.split(':', '-').map { it.toInt(16).toByte() }.toByteArray()
        val packet = ByteArray(102) { index -> if (index < 6) 0xFF.toByte() else macBytes[(index - 6) % 6] }
        val octets = tvHost.split('.')
        val addresses = buildSet {
            add("255.255.255.255")
            if (octets.size == 4) add("${octets[0]}.${octets[1]}.${octets[2]}.255")
        }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(3) {
                addresses.forEach { address ->
                    listOf(9, 7).forEach { port ->
                        socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(address), port))
                    }
                }
            }
        }
    }
}
