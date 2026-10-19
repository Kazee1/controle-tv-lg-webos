package com.example.tvremote.tv

import com.example.tvremote.core.model.TvDevice
import com.example.tvremote.core.model.TvPowerState
import com.example.tvremote.tv.discovery.SsdpParser
import com.example.tvremote.tv.repository.ConnectionPolicy
import org.junit.Assert.*
import org.junit.Test

class ConnectionPolicyTest {
    @Test fun `retains stable SSDP identity when DHCP changes host`() {
        fun response(host: String) = SsdpParser.parse("HTTP/1.1 200 OK\r\nLOCATION: http://$host:3000/device.xml\r\nSERVER: LG Electronics webOS\r\nUSN: uuid:MY-TV::urn:lge-com:service:webos-second-screen:1\r\n")!!
        val old = response("192.168.1.15")
        val moved = response("192.168.1.18")
        assertEquals("uuid:my-tv", old.deviceId)
        assertTrue(ConnectionPolicy.sameDevice(old, moved))
        assertFalse(ConnectionPolicy.sameDevice(old, moved.copy(deviceId = "uuid:other-tv")))
    }

    @Test fun `matching generic names never proves identity`() {
        assertFalse(ConnectionPolicy.sameDevice(TvDevice("LG webOS TV", "old"), TvDevice("LG webOS TV", "new")))
    }

    @Test fun `processing transition cannot claim TV is on and stable suspend means standby`() {
        assertEquals(TvPowerState.STANDBY, ConnectionPolicy.powerState("Suspend"))
        assertEquals(TvPowerState.UNKNOWN, ConnectionPolicy.powerState("Active", "Request Power Off"))
        assertEquals(TvPowerState.UNKNOWN, ConnectionPolicy.powerState(null))
        assertEquals(TvPowerState.UNKNOWN, ConnectionPolicy.powerState("garbage"))
        assertEquals(TvPowerState.ON, ConnectionPolicy.powerState("Active"))
        assertEquals(TvPowerState.STANDBY, ConnectionPolicy.powerState("Active Standby"))
    }

    @Test fun `network loss is not reported as confirmed power off`() {
        assertTrue(ConnectionPolicy.offlineMessage(false).contains("ou fora da rede"))
        assertEquals("TV em standby", ConnectionPolicy.offlineMessage(true))
    }
}
