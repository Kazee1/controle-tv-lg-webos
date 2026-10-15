package com.example.tvremote.tv.discovery

import android.content.Context
import android.net.wifi.WifiManager
import com.example.tvremote.core.model.TvDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

class TvDiscoveryService(context: Context) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    suspend fun discover(timeoutMs: Int = 4_000): List<TvDevice> = withContext(Dispatchers.IO) {
        val lock = wifiManager.createMulticastLock("tv-remote-ssdp").apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 500
                val target = InetAddress.getByName("239.255.255.250")
                SEARCH_TARGETS.forEach { st ->
                    val bytes = buildSearch(st).toByteArray(Charsets.US_ASCII)
                    socket.send(DatagramPacket(bytes, bytes.size, target, 1900))
                }
                val found = linkedMapOf<String, TvDevice>()
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val buffer = ByteArray(8_192)
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        SsdpParser.parse(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let { found[it.host] = it }
                    } catch (_: SocketTimeoutException) { /* keep listening until deadline */ }
                }
                found.values.map { enrich(it) }
            }
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

    private fun enrich(tv: TvDevice): TvDevice = runCatching {
        val connection = URL(tv.location).openConnection().apply {
            connectTimeout = 1_500
            readTimeout = 1_500
        }
        connection.getInputStream().use { stream ->
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }
            val doc = factory.newDocumentBuilder().parse(stream)
            val name = doc.getElementsByTagName("friendlyName").item(0)?.textContent?.trim()
            val model = doc.getElementsByTagName("modelName").item(0)?.textContent?.trim()
            tv.copy(name = name.orEmpty().ifBlank { tv.name }, model = model)
        }
    }.getOrDefault(tv)

    private fun buildSearch(st: String) = "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: $st\r\n\r\n"

    private companion object {
        val SEARCH_TARGETS = listOf(
            "urn:lge-com:service:webos-second-screen:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "ssdp:all",
        )
    }
}
