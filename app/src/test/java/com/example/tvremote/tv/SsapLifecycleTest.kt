package com.example.tvremote.tv

import com.example.tvremote.core.storage.PairingStore
import com.example.tvremote.tv.ssap.SsapClient
import com.example.tvremote.tv.ssap.SsapConnectionException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SsapLifecycleTest {
    private class MemoryKeys : PairingStore {
        override fun getClientKey(host: String) = "test-key"
        override fun saveClientKey(host: String, key: String) = Unit
        override fun getCertificatePin(host: String): String? = null
        override fun saveCertificatePin(host: String, pin: String) = Unit
    }
    private class FakeSocket(val req: Request, val listener: WebSocketListener) : WebSocket {
        var accepts = true
        var cancelled = false
        override fun request() = req
        override fun queueSize() = 0L
        override fun send(text: String) = accepts
        override fun send(bytes: ByteString) = accepts
        override fun close(code: Int, reason: String?) = true
        override fun cancel() { cancelled = true }
        fun register() {
            listener.onOpen(this, Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
            listener.onMessage(this, """{"id":"register_0","type":"registered","payload":{"client-key":"test-key"}}""")
        }
    }

    @Test fun `transport failure clears connected flag and invalidates socket exactly once`() = runTest {
        lateinit var socket: FakeSocket
        val client = SsapClient(MemoryKeys()) { request, listener -> FakeSocket(request, listener).also { socket = it } }
        var disconnects = 0
        client.onDisconnected = { disconnects++ }
        val connection = async(start = CoroutineStart.UNDISPATCHED) { client.connect("tv.test", secure = false) }
        socket.register(); connection.await()
        assertTrue(client.isConnected)
        socket.listener.onFailure(socket, IOException("network lost"), null)
        socket.listener.onClosed(socket, 1006, "lost")
        assertFalse(client.isConnected)
        assertNull(client.connectedHost)
        assertTrue(socket.cancelled)
        assertEquals(1, disconnects)
        assertTrue(runCatching { client.request("ssap://audio/volumeUp") }.exceptionOrNull() is SsapConnectionException)
    }

    @Test fun `late failure from old session cannot disconnect new session`() = runTest {
        lateinit var socket: FakeSocket
        val client = SsapClient(MemoryKeys()) { req, listener -> FakeSocket(req, listener).also { socket = it } }
        val first = async(start = CoroutineStart.UNDISPATCHED) { client.connect("old.test", false) }
        socket.register(); first.await()
        val old = socket
        val second = async(start = CoroutineStart.UNDISPATCHED) { client.connect("new.test", false) }
        socket.register(); second.await()
        old.listener.onFailure(old, IOException("late callback"), null)
        assertTrue(client.isConnected)
        assertEquals("new.test", client.connectedHost)
        client.close()
    }

    @Test fun `request timeout invalidates apparently connected session`() = runTest {
        lateinit var socket: FakeSocket
        val client = SsapClient(MemoryKeys()) { req, listener -> FakeSocket(req, listener).also { socket = it } }
        val connection = async(start = CoroutineStart.UNDISPATCHED) { client.connect("tv.test", false) }
        socket.register(); connection.await()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { client.request("ssap://audio/getVolume", timeoutMs = 100) }.exceptionOrNull()
        }
        advanceTimeBy(101); runCurrent()
        assertTrue(request.await() is SsapConnectionException)
        assertFalse(client.isConnected)
        assertTrue(socket.cancelled)
    }

    @Test fun `failed send is a connection failure not an API error`() = runTest {
        lateinit var socket: FakeSocket
        val client = SsapClient(MemoryKeys()) { req, listener -> FakeSocket(req, listener).also { socket = it } }
        val connection = async(start = CoroutineStart.UNDISPATCHED) { client.connect("tv.test", false) }
        socket.register(); connection.await()
        socket.accepts = false
        assertTrue(runCatching { client.request("ssap://audio/volumeUp") }.exceptionOrNull() is SsapConnectionException)
        assertFalse(client.isConnected)
    }
}
