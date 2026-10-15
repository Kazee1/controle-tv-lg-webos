package com.example.tvremote.tv.ssap

import android.util.Log
import com.example.tvremote.core.network.TvTls
import com.example.tvremote.core.storage.SecurePairingStore
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

class SsapException(message: String) : Exception(message)

class SsapClient(private val secureStore: SecurePairingStore) {
    private val counter = AtomicLong()
    private val generation = AtomicLong()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val subscriptions = ConcurrentHashMap<String, (JSONObject) -> Unit>()
    private var socket: WebSocket? = null
    private var registration: CompletableDeferred<String>? = null
    private var activeHost: String? = null
    private var activeClient: OkHttpClient? = null
    @Volatile private var pairingPrompted = false

    var onPairingPrompt: (() -> Unit)? = null
    var onDisconnected: ((Throwable?) -> Unit)? = null

    suspend fun connect(host: String, secure: Boolean = true): String {
        close()
        activeHost = host
        val client = if (secure) TvTls.client(host, secureStore) else OkHttpClient.Builder().build()
        activeClient = client
        val result = CompletableDeferred<String>()
        registration = result
        pairingPrompted = false
        val url = if (secure) "wss://$host:3001" else "ws://$host:3000"
        val session = generation.incrementAndGet()
        socket = client.newWebSocket(Request.Builder().url(url).build(), listener(host, session))
        return try {
            withTimeout(12_000) { result.await() }
        } catch (timeout: TimeoutCancellationException) {
            if (!pairingPrompted) throw SsapException("A TV não respondeu ao handshake")
            withTimeout(60_000) { result.await() }
        }
    }

    suspend fun request(uri: String, payload: JSONObject? = null, timeoutMs: Long = 8_000): JSONObject {
        val ws = socket ?: throw SsapException("TV desconectada")
        val id = "tv-${counter.incrementAndGet()}"
        val response = CompletableDeferred<JSONObject>()
        pending[id] = response
        val message = JSONObject().put("id", id).put("type", "request").put("uri", uri)
        payload?.let { message.put("payload", it) }
        Log.d(LOG_TAG, "request id=$id uri=$uri")
        if (!ws.send(message.toString())) {
            pending.remove(id)
            throw SsapException("Não foi possível enviar o comando")
        }
        return try {
            withTimeout(timeoutMs) { response.await() }
        } finally {
            pending.remove(id)
        }
    }

    fun subscribe(uri: String, payload: JSONObject? = null, onValue: (JSONObject) -> Unit): String {
        val ws = socket ?: throw SsapException("TV desconectada")
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

    fun close() {
        generation.incrementAndGet()
        socket?.close(1000, "App em pausa")
        socket = null
        activeClient?.dispatcher?.executorService?.shutdown()
        activeClient = null
        val error = SsapException("Conexão encerrada")
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
                Log.d(LOG_TAG, "response id=$id type=$type ok=${payload.opt("returnValue")} error=${errorText(frame)}")
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
            subscriptions[id]?.invoke(payload)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (session == generation.get()) failAll(t) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (session == generation.get()) failAll(null) }
    }

    private fun failAll(error: Throwable?) {
        val failure = error ?: SsapException("A TV encerrou a conexão")
        registration?.takeUnless { it.isCompleted }?.completeExceptionally(failure)
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        subscriptions.clear()
        onDisconnected?.invoke(error)
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
