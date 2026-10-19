package com.example.tvremote.tv.ssap

import android.util.Log
import com.example.tvremote.core.network.TvTls
import com.example.tvremote.core.storage.PairingStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException

class SsapException(message: String) : Exception(message)
class SsapConnectionException(message: String, cause: Throwable? = null) : IOException(message, cause)

class SsapClient(
    private val secureStore: PairingStore,
    private val socketFactory: ((Request, WebSocketListener) -> WebSocket)? = null,
) {
    private val counter = AtomicLong()
    private val generation = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val subscriptions = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private var socket: WebSocket? = null
    private var registration: CompletableDeferred<String>? = null
    private var activeHost: String? = null
    private var activeClient: OkHttpClient? = null
    @Volatile private var pairingPrompted = false
    @Volatile var isConnected = false
        private set
    val connectedHost: String? get() = activeHost?.takeIf { isConnected }

    var onPairingPrompt: (() -> Unit)? = null
    var onDisconnected: ((Throwable?) -> Unit)? = null

    suspend fun connect(host: String, secure: Boolean = true): String {
        val result = CompletableDeferred<String>()
        synchronized(this) {
            close()
            activeHost = host
            val client = (if (secure) TvTls.client(host, secureStore).newBuilder() else OkHttpClient.Builder())
                .connectTimeout(4, TimeUnit.SECONDS).pingInterval(10, TimeUnit.SECONDS).build()
            activeClient = client
            registration = result
            pairingPrompted = false
            val url = if (secure) "wss://$host:3001" else "ws://$host:3000"
            val session = generation.incrementAndGet()
            val request = Request.Builder().url(url).build()
            val listener = listener(host, session)
            socket = socketFactory?.invoke(request, listener) ?: client.newWebSocket(request, listener)
        }
        try {
            return try {
                withTimeout(8_000) { result.await() }
            } catch (timeout: TimeoutCancellationException) {
                if (!pairingPrompted) throw SsapConnectionException("A TV não respondeu à conexão", timeout)
                withTimeout(60_000) { result.await() }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    suspend fun request(uri: String, payload: JSONObject? = null, timeoutMs: Long = 8_000): JSONObject {
        val session = generation.get()
        val ws = socket?.takeIf { isConnected } ?: throw SsapConnectionException("TV desconectada")
        val id = "tv-${counter.incrementAndGet()}"
        val response = CompletableDeferred<JSONObject>()
        pending[id] = response
        val message = JSONObject().put("id", id).put("type", "request").put("uri", uri)
        payload?.let { message.put("payload", it) }
        Log.d(LOG_TAG, "request id=$id uri=$uri")
        if (!ws.send(message.toString())) {
            pending.remove(id)
            val error = SsapConnectionException("A conexão com a TV foi interrompida")
            failAll(error, session)
            throw error
        }
        return try {
            withTimeout(timeoutMs) { response.await() }
        } catch (timeout: TimeoutCancellationException) {
            val error = SsapConnectionException("A TV parou de responder", timeout)
            failAll(error, session)
            throw error
        } finally {
            pending.remove(id)
        }
    }

    fun subscribe(uri: String, payload: JSONObject? = null, onValue: (JSONObject) -> Unit): String {
        val ws = socket?.takeIf { isConnected } ?: throw SsapConnectionException("TV desconectada")
        val id = "sub-${counter.incrementAndGet()}"
        subscriptions[id] = onValue
        val message = JSONObject().put("id", id).put("type", "subscribe").put("uri", uri)
        payload?.let { message.put("payload", it) }
        if (!ws.send(message.toString())) {
            subscriptions.remove(id)
            throw SsapException("Não foi possível iniciar acompanhamento")
        }
        return id
    }

    fun unsubscribe(id: String) {
        subscriptions.remove(id)
        socket?.send(JSONObject().put("id", id).put("type", "unsubscribe").toString())
    }

    @Synchronized fun close() {
        generation.incrementAndGet()
        isConnected = false
        socket?.close(1000, "App em pausa")
        socket = null
        activeClient?.dispatcher?.executorService?.shutdown()
        activeClient = null
        val error = SsapConnectionException("Conexão encerrada")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        subscriptions.clear()
        registration?.takeUnless { it.isCompleted }?.completeExceptionally(error)
        registration = null
    }

    private fun listener(host: String, session: Long) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (session != generation.get()) return
            val frame = JSONObject()
                .put("id", REGISTER_ID)
                .put("type", "register")
                .put("payload", PairingManifest.payload(secureStore.getClientKey(host)))
            webSocket.send(frame.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (session != generation.get()) return
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            val id = frame.optString("id")
            val type = frame.optString("type")
            val payload = frame.optJSONObject("payload") ?: JSONObject()
            if (id != REGISTER_ID) {
                val error = if (type == "error" || payload.opt("returnValue") == false) errorText(frame) else "none"
                Log.d(LOG_TAG, "response id=$id type=$type ok=${payload.opt("returnValue")} error=$error")
                if (payload.has("state")) Log.d(LOG_TAG, "power state=${payload.optString("state")} processing=${payload.optString("processing")}")
                payload.optJSONObject("currentWidget")?.let { widget ->
                    Log.d(LOG_TAG, "keyboard focus=${widget.optBoolean("focus", false)} length=${widget.optInt("surroundingTextLength", -1)}")
                }
            }
            if (id == REGISTER_ID) {
                when (type) {
                    "response" -> {
                        pairingPrompted = true
                        onPairingPrompt?.invoke()
                    }
                    "registered" -> {
                        val key = payload.optString("client-key")
                        if (key.isBlank()) registration?.completeExceptionally(SsapException("A TV não forneceu a chave de pareamento"))
                        else {
                            secureStore.saveClientKey(host, key)
                            isConnected = true
                            registration?.complete(key)
                        }
                    }
                    "error" -> registration?.completeExceptionally(SsapException(frame.optString("error", "Pareamento recusado")))
                }
                return
            }
            if (type == "error") {
                pending.remove(id)?.completeExceptionally(SsapException(errorText(frame)))
                subscriptions.remove(id)
                return
            }
            if (type == "response" && payload.opt("returnValue") == false) {
                pending.remove(id)?.completeExceptionally(SsapException(payload.optString("errorText", "Comando recusado")))
                return
            }
            pending.remove(id)?.complete(payload)
            subscriptions[id]?.let { callback ->
                runCatching { callback(payload) }.onFailure { Log.w(LOG_TAG, "Subscription callback failed", it) }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { failAll(t, session) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { failAll(null, session) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { failAll(null, session) }
    }

    @Synchronized private fun failAll(error: Throwable?, session: Long) {
        if (!generation.compareAndSet(session, session + 1)) return
        isConnected = false
        val failedSocket = socket
        socket = null
        failedSocket?.cancel()
        activeClient?.dispatcher?.executorService?.shutdown()
        activeClient = null
        val failure = SsapConnectionException("A conexão com a TV foi interrompida", error)
        registration?.takeUnless { it.isCompleted }?.completeExceptionally(failure)
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        subscriptions.clear()
        onDisconnected?.invoke(failure)
    }

    private fun errorText(frame: JSONObject): String {
        val payload = frame.optJSONObject("payload")
        return payload?.optString("errorText")?.takeIf(String::isNotBlank)
            ?: frame.optString("error", "Comando rejeitado pela TV")
    }

    private companion object {
        const val REGISTER_ID = "register_0"
        const val LOG_TAG = "TvRemoteSSAP"
    }
}
