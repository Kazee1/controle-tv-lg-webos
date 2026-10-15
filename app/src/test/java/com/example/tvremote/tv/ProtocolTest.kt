package com.example.tvremote.tv

import com.example.tvremote.core.network.WakeOnLan
import com.example.tvremote.tv.discovery.SsdpParser
import com.example.tvremote.tv.pointer.PointerClient
import com.example.tvremote.tv.ssap.PairingManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test
    fun `recognizes LG SSDP response and extracts host`() {
        val packet = """HTTP/1.1 200 OK
            |LOCATION: http://192.168.1.50:3000/device.xml
            |SERVER: webOS/7.0 UPnP/1.0 LG Electronics
            |ST: urn:lge-com:service:webos-second-screen:1
            |
        """.trimMargin()

        val tv = SsdpParser.parse(packet)

        assertNotNull(tv)
        assertEquals("192.168.1.50", tv?.host)
    }

    @Test
    fun `ignores unrelated UPnP devices`() {
        val packet = """HTTP/1.1 200 OK
            |LOCATION: http://192.168.1.10/device.xml
            |SERVER: Generic Router UPnP/1.0
            |ST: ssdp:all
        """.trimMargin()
        assertNull(SsdpParser.parse(packet))
    }

    @Test
    fun `pointer button frame follows blank-line grammar`() {
        assertEquals("type:button\nname:HOME\n\n", PointerClient.buttonFrame("HOME"))
        assertEquals("type:button\nname:MENU\n\n", PointerClient.buttonFrame("MENU"))
    }

    @Test
    fun `pairing payload contains required signed identity`() {
        val payload = PairingManifest.payload("secret")
        val signed = payload.getJSONObject("manifest").getJSONObject("signed")
        assertEquals("PROMPT", payload.getString("pairingType"))
        assertEquals("secret", payload.getString("client-key"))
        assertEquals("com.lge.test", signed.getString("appId"))
        val permissions = signed.getJSONArray("permissions")
        assertTrue((0 until permissions.length()).any { permissions.getString(it) == "CONTROL_MOUSE_AND_KEYBOARD" })
    }

    @Test
    fun `validates MAC without accepting partial values`() {
        assertTrue(WakeOnLan.isValidMac("AA:BB:CC:11:22:33"))
        assertTrue(WakeOnLan.isValidMac("aa-bb-cc-11-22-33"))
        assertFalse(WakeOnLan.isValidMac("AA:BB:CC"))
    }
}
