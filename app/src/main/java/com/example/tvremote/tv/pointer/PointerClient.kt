package com.example.tvremote.tv.pointer

import com.example.tvremote.core.network.TvTls
import com.example.tvremote.core.storage.SecurePairingStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong

class PointerClient(private val secureStore: SecurePairingStore) {
    private var socket: WebSocket? = null
    private var client: OkHttpClient? = null
    private val generation = AtomicLong()
    @Volatile var isConnected = false
        private set

    suspend fun connect(host: String, socketPath: String) {
        close()
        val session = generation.incrementAndGet()
        val opened = CompletableDeferred<Unit>()
        val dedicated = if (socketPath.startsWith("wss:")) TvTls.client(host, secureStore) else OkHttpClient()
        client = dedicated
        socket = dedicated.newWebSocket(Request.Builder().url(socketPath).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (session != generation.get()) { webSocket.cancel(); return }
                isConnected = true
                opened.complete(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (session != generation.get()) return
                isConnected = false
                opened.completeExceptionally(t)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (session != generation.get()) return
                isConnected = false
                webSocket.close(code, reason)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (session == generation.get()) isConnected = false
            }
        })
        try { withTimeout(5_000) { opened.await() } } catch (error: Throwable) { close(); throw error }
    }

    fun button(name: String): Boolean {
        require(name in BUTTONS) { "Botão não reconhecido: $name" }
        return send(buttonFrame(name))
    }

    private fun send(frame: String): Boolean {
        if (!isConnected) return false
        val accepted = socket?.send(frame) ?: false
        if (!accepted) isConnected = false
        return accepted
    }
    fun click() = send("type:click\n\n")
    fun move(dx: Int, dy: Int, down: Boolean = false) =
        send("type:move\ndx:$dx\ndy:$dy\ndown:${if (down) 1 else 0}\n\n")
    fun scroll(dy: Int) = send("type:scroll\ndx:0\ndy:$dy\n\n")

    fun close() {
        generation.incrementAndGet()
        isConnected = false
        socket?.close(1000, "App em pausa")
        socket = null
        client?.dispatcher?.executorService?.shutdown()
        client = null
    }

    companion object {
        val BUTTONS = setOf("UP", "DOWN", "LEFT", "RIGHT", "ENTER", "BACK", "EXIT", "HOME", "MENU")
        fun buttonFrame(name: String) = "type:button\nname:$name\n\n"
    }
}
