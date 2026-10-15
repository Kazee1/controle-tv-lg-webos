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

class PointerClient(private val secureStore: SecurePairingStore) {
    private var socket: WebSocket? = null
    private var client: OkHttpClient? = null

    suspend fun connect(host: String, socketPath: String) {
        close()
        val opened = CompletableDeferred<Unit>()
        val dedicated = if (socketPath.startsWith("wss:")) TvTls.client(host, secureStore) else OkHttpClient()
        client = dedicated
        socket = dedicated.newWebSocket(Request.Builder().url(socketPath).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(Unit) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { opened.completeExceptionally(t) }
        })
        withTimeout(8_000) { opened.await() }
    }

    fun button(name: String): Boolean {
        require(name in BUTTONS) { "Botão não reconhecido: $name" }
        return socket?.send(buttonFrame(name)) ?: false
    }

    fun click() = socket?.send("type:click\n\n") ?: false
    fun move(dx: Int, dy: Int, down: Boolean = false) =
        socket?.send("type:move\ndx:$dx\ndy:$dy\ndown:${if (down) 1 else 0}\n\n") ?: false
    fun scroll(dy: Int) = socket?.send("type:scroll\ndx:0\ndy:$dy\n\n") ?: false

    fun close() {
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
