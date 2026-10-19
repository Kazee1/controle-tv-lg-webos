package com.example.tvremote.tv.repository

import com.example.tvremote.core.model.CapabilityState
import com.example.tvremote.core.model.ConnectionPhase
import com.example.tvremote.core.model.PictureSettings
import com.example.tvremote.core.model.TvApp
import com.example.tvremote.core.model.TvCapabilities
import com.example.tvremote.core.model.TvDevice
import com.example.tvremote.core.model.TvInput
import com.example.tvremote.core.model.TvUiState
import com.example.tvremote.core.model.TvPowerState
import com.example.tvremote.core.model.UsageSummary
import com.example.tvremote.core.model.YouTubePlaybackEstimate
import com.example.tvremote.core.model.YouTubeQueueItem
import com.example.tvremote.core.network.TvTls
import com.example.tvremote.core.network.WakeOnLan
import com.example.tvremote.core.storage.PreferencesStore
import com.example.tvremote.core.storage.SecurePairingStore
import com.example.tvremote.core.storage.PairingStore
import com.example.tvremote.tv.discovery.TvDiscoveryService
import com.example.tvremote.tv.pointer.PointerClient
import com.example.tvremote.tv.ssap.SsapClient
import com.example.tvremote.tv.ssap.SsapException
import com.example.tvremote.tv.ssap.SsapConnectionException
import com.example.tvremote.youtube.YouTubeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLException

class TvControlRepository(
    private val preferences: PreferencesStore,
    private val secureStore: SecurePairingStore,
    private val discovery: TvDiscoveryService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ssap = SsapClient(secureStore)
    private val pointer = PointerClient(secureStore)
    private val youtubeResolver = YouTubeResolver()
    private val connecting = AtomicBoolean(false)
    private val connectionMutex = Mutex()
    private val pointerMutex = Mutex()
    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var refreshJob: Job? = null
    private var backgroundCloseJob: Job? = null
    private var youtubeQueueJob: Job? = null
    private var youtubeQueueAppId: String? = null
    @Volatile private var appActive = false
    @Volatile private var deliberateDisconnect = false

    private val _state = MutableStateFlow(TvUiState())
    val state: StateFlow<TvUiState> = _state.asStateFlow()

    init {
        ssap.onPairingPrompt = {
            _state.update { it.copy(phase = ConnectionPhase.WAITING_FOR_TV, statusMessage = "Confirme o pareamento na tela da TV") }
        }
        ssap.onDisconnected = {
            if (!deliberateDisconnect && !connecting.get()) connectionLost()
        }
        scope.launch {
            val saved = preferences.preferences.first()
            _state.update {
                it.copy(
                    phase = if (saved.tv == null) ConnectionPhase.NOT_CONFIGURED else ConnectionPhase.SELECTED,
                    selectedTv = saved.tv,
                    macAddress = saved.mac,
                    hapticsEnabled = saved.haptics,
                    pointerSensitivity = saved.sensitivity,
                    openFirstYouTubeResult = saved.openFirstYouTubeResult,
                    youtubeBufferSeconds = saved.youtubeBufferSeconds,
                    statusMessage = if (saved.tv == null) "Encontre sua TV para começar" else "Pronta para conectar",
                )
            }
            refreshUsage()
            if (appActive && saved.tv != null) connect(saved.tv)
        }
    }

    fun setAppActive(active: Boolean) {
        appActive = active
        if (active) {
            backgroundCloseJob?.cancel()
            val tv = _state.value.selectedTv
            if (tv != null && !ssap.isConnected) connect(tv)
            else if (ssap.isConnected) monitorSession()
        } else {
            reconnectJob?.cancel()
            scope.launch { preferences.closeUsage(); refreshUsage() }
            backgroundCloseJob?.cancel()
            backgroundCloseJob = scope.launch {
                delay(5 * 60_000L)
                if (!appActive && youtubeQueueJob?.isActive != true) {
                    deliberateDisconnect = true
                    ssap.close()
                    pointer.close()
                    heartbeatJob?.cancel()
                    refreshJob?.cancel()
                    deliberateDisconnect = false
                    _state.update { current -> current.copy(phase = ConnectionPhase.DISCONNECTED, powerState = TvPowerState.UNKNOWN, keyboardFocused = false, statusMessage = "Conexão pausada") }
                }
            }
        }
    }

    fun discover() {
        scope.launch {
            val previous = _state.value.phase
            _state.update { it.copy(phase = if (ssap.isConnected) it.phase else ConnectionPhase.DISCOVERING, errorMessage = null) }
            runCatching { discovery.discover() }
                .onSuccess { devices ->
                    _state.update {
                        it.copy(
                            phase = if (ssap.isConnected) it.phase else if (it.selectedTv == null) ConnectionPhase.NOT_CONFIGURED else previous,
                            discoveredTvs = devices,
                            statusMessage = if (ssap.isConnected) it.statusMessage else if (devices.isEmpty()) "Nenhuma TV encontrada. Use o IP manual." else "${devices.size} TV(s) encontrada(s)",
                        )
                    }
                }
                .onFailure { showError("Falha na busca: ${friendly(it)}") }
        }
    }

    fun connectManual(host: String) {
        val clean = host.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
        if (!isValidHost(clean)) {
            showError("Informe um IP ou endereço local válido")
            return
        }
        selectTv(TvDevice("LG webOS TV", clean))
    }

    fun selectTv(tv: TvDevice) {
        reconnectJob?.cancel()
        scope.launch {
            connectionMutex.withLock {
                preferences.saveTv(tv)
                _state.update { it.copy(selectedTv = tv, phase = ConnectionPhase.SELECTED, errorMessage = null) }
            }
            if (!connectNow(tv)) scheduleReconnect()
        }
    }

    fun reconnect() = _state.value.selectedTv?.let(::connect)

    fun forgetTv() {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        refreshJob?.cancel()
        scope.launch {
            connectionMutex.withLock {
                deliberateDisconnect = true
                val host = _state.value.selectedTv?.host
                ssap.close()
                pointer.close()
                host?.let(secureStore::forget)
                preferences.clearTv()
                deliberateDisconnect = false
                _state.value = TvUiState(statusMessage = "Encontre sua TV para começar")
            }
        }
    }

    fun sendButton(name: String) = command {
        ensurePointer()
        if (!pointer.button(name)) {
            ensurePointer()
            if (!pointer.button(name)) throw SsapException("Canal do controle indisponível")
        }
    }

    fun movePointer(dx: Float, dy: Float) {
        val gain = _state.value.pointerSensitivity
        pointer.move((dx * gain).toInt(), (dy * gain).toInt())
    }

    fun clickPointer() = command { ensurePointer(); if (!pointer.click()) throw SsapException("Touchpad indisponível") }
    fun scrollPointer(dy: Float) { pointer.scroll((dy * _state.value.pointerSensitivity).toInt().coerceIn(-30, 30)) }

    fun volumeUp() = command { ssap.request("ssap://audio/volumeUp") }
    fun volumeDown() = command { ssap.request("ssap://audio/volumeDown") }
    fun setVolume(volume: Int) = command { ssap.request("ssap://audio/setVolume", JSONObject().put("volume", volume)) }
    fun toggleMute() = command { ssap.request("ssap://audio/setMute", JSONObject().put("mute", !_state.value.muted)) }
    fun turnOff() = command {
        ssap.request("ssap://system/turnOff")
        _state.update { it.copy(phase = ConnectionPhase.STANDBY, powerState = TvPowerState.STANDBY, keyboardFocused = false, statusMessage = "TV em standby") }
    }

    fun launchApp(id: String) = command { ssap.request("ssap://system.launcher/launch", JSONObject().put("id", id)) }
    fun switchInput(id: String) = command { ssap.request("ssap://tv/switchInput", JSONObject().put("inputId", id)) }

    fun setFavorite(id: String, favorite: Boolean) {
        scope.launch {
            val ids = preferences.preferences.first().favoriteIds.toMutableSet().apply {
                if (favorite) add(id) else remove(id)
            }
            preferences.saveFavorites(ids)
            _state.update { s -> s.copy(apps = s.apps.map { if (it.id == id) it.copy(favorite = favorite) else it }) }
            if (favorite) _state.value.apps.firstOrNull { it.id == id }?.let { loadAppIcons(listOf(it)) }
        }
    }

    fun sendText(text: String, replace: Boolean = true) {
        if (text.isEmpty()) return
        // Há firmwares/aplicativos que aceitam insertText, mas não notificam corretamente
        // currentWidgetFocus. A própria resposta do comando é a fonte de verdade.
        command {
            ssap.request(
                "ssap://com.webos.service.ime/insertText",
                JSONObject().put("text", text).put("replace", if (replace) 1 else 0),
            )
        }
    }

    fun sendTextAndEnter(text: String) {
        if (text.isEmpty()) return
        command {
            val foreground = _state.value.foregroundAppId.orEmpty()
            val youtube = _state.value.apps.firstOrNull {
                it.id.equals(foreground, true) && (it.id.contains("youtube", true) || it.title.contains("youtube", true))
            }
            if (youtube != null || foreground.contains("youtube", true)) {
                val appId = youtube?.id ?: foreground
                if (_state.value.openFirstYouTubeResult) {
                    enqueueYouTube(text, appId)
                } else {
                    launchYouTubeSearch(appId, text)
                    _state.update { it.copy(statusMessage = "Resultados para “$text”") }
                }
                return@command
            }
            // O IME do webOS só recebe texto depois que o teclado virtual da TV está
            // realmente focado. Se a busca estiver apenas selecionada, ENTER a ativa.
            if (!_state.value.keyboardFocused) {
                if (!pointer.button("ENTER")) throw SsapException("Não foi possível ativar o campo de texto")
                for (attempt in 0 until 15) {
                    if (_state.value.keyboardFocused) break
                    delay(100)
                }
            }
            // O Connect SDK oficial usa replace numérico (0), não booleano. Limpar antes
            // evita concatenar a busca anterior sem depender de replace=true.
            runCatching {
                ssap.request("ssap://com.webos.service.ime/deleteCharacters", JSONObject().put("count", 200))
            }
            ssap.request(
                "ssap://com.webos.service.ime/insertText",
                JSONObject().put("text", text).put("replace", 0),
            )
            delay(120)
            ssap.request("ssap://com.webos.service.ime/sendEnterKey")
        }
    }

    fun addToYouTubeQueue(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        command {
            val appId = findYouTubeAppId() ?: throw SsapException("YouTube não encontrado na TV")
            enqueueYouTube(query, appId)
        }
    }

    fun removeYouTubeQueueItem(id: String) {
        _state.update { state -> state.copy(youtubeQueue = state.youtubeQueue.filterNot { it.id == id }) }
        if (_state.value.resolvingYouTubeQueueItemId == id) {
            youtubeQueueJob?.cancel()
            _state.update { it.copy(resolvingYouTubeQueueItemId = null) }
            startYouTubeQueue()
        }
    }

    fun moveYouTubeQueueItem(id: String, offset: Int) {
        _state.update { state ->
            val from = state.youtubeQueue.indexOfFirst { it.id == id }
            if (from < 0) return@update state
            val to = (from + offset).coerceIn(0, state.youtubeQueue.lastIndex)
            if (from == to) return@update state
            val reordered = state.youtubeQueue.toMutableList()
            val item = reordered.removeAt(from)
            reordered.add(to, item)
            state.copy(youtubeQueue = reordered)
        }
    }

    fun moveYouTubeQueueItemToTop(id: String) {
        _state.update { state ->
            val from = state.youtubeQueue.indexOfFirst { it.id == id }
            if (from <= 0) return@update state
            val reordered = state.youtubeQueue.toMutableList()
            reordered.add(0, reordered.removeAt(from))
            state.copy(youtubeQueue = reordered)
        }
    }

    fun skipCurrentYouTubeItem() {
        youtubeQueueJob?.cancel()
        _state.update { it.copy(youtubePlaybackEstimate = null, resolvingYouTubeQueueItemId = null) }
        startYouTubeQueue()
    }

    fun clearYouTubeQueue() {
        _state.update { it.copy(youtubeQueue = emptyList()) }
    }

    fun keyboardEnter() = keyboardCommand("ssap://com.webos.service.ime/sendEnterKey")
    fun keyboardBackspace() = keyboardCommand("ssap://com.webos.service.ime/deleteCharacters", JSONObject().put("count", 1))

    fun refreshPicture() = scope.launch {
        runCatching { loadPicture() }.onFailure { error ->
            val denied = friendly(error).contains("allowed", true) || friendly(error).contains("access", true)
            _state.update { current ->
                current.copy(capabilities = current.capabilities.copy(pictureRead = if (denied) CapabilityState.DENIED else CapabilityState.UNSUPPORTED))
            }
            showError("Imagem indisponível: ${friendly(error)}")
        }
    }

    fun setPictureValue(key: String, value: Int) = command {
        require(key in setOf("brightness", "contrast", "backlight"))
        try {
            val before = _state.value.picture
            _state.update { it.copy(pictureUndo = before) }
            writePictureSettings(JSONObject().put(key, value))
            loadPicture()
            _state.update {
                it.copy(
                    picture = it.picture.copy(writeSupport = CapabilityState.SUPPORTED),
                    capabilities = it.capabilities.copy(pictureWrite = CapabilityState.SUPPORTED),
                )
            }
        } catch (error: Throwable) {
            _state.update {
                it.copy(
                    picture = it.picture.copy(writeSupport = if (friendly(error).contains("access", true)) CapabilityState.DENIED else CapabilityState.UNSUPPORTED),
                    capabilities = it.capabilities.copy(pictureWrite = CapabilityState.DENIED),
                )
            }
            throw error
        }
    }

    fun undoPicture() {
        val previous = _state.value.pictureUndo ?: return
        applyPicture(previous, "Ajuste anterior restaurado")
    }

    fun restorePictureBaseline() {
        val baseline = _state.value.pictureBaseline ?: return
        applyPicture(baseline, "Valores iniciais restaurados")
    }

    private fun applyPicture(values: PictureSettings, successMessage: String) = command {
        val settings = JSONObject().apply {
            values.brightness?.let { put("brightness", it) }
            values.contrast?.let { put("contrast", it) }
            values.backlight?.let { put("backlight", it) }
        }
        if (settings.length() == 0) throw SsapException("Não há valores de imagem para restaurar")
        val before = _state.value.picture
        writePictureSettings(settings)
        loadPicture()
        _state.update { it.copy(pictureUndo = before, statusMessage = successMessage) }
    }

    private suspend fun writePictureSettings(settings: JSONObject) {
        val payload = JSONObject().put("category", "picture").put("settings", settings)
        val publicResult = runCatching { ssap.request("ssap://settings/setSystemSettings", payload) }
        if (publicResult.isSuccess) return

        // Alguns televisores expõem leitura pelo SSAP, mas o adaptador público quebra a
        // escrita com "key(s): undefined". O fallback usa a ponte Luna adotada por outros
        // controles webOS: cria um alerta invisível com a ação e o fecha imediatamente.
        val lunaUri = "luna://com.webos.settingsservice/setSystemSettings"
        val action = JSONObject().put("uri", lunaUri).put("params", payload)
        val alert = ssap.request(
            "ssap://system.notifications/createAlert",
            JSONObject()
                .put("message", " ")
                .put("buttons", JSONArray().put(JSONObject().put("label", "").put("onClick", lunaUri).put("params", payload)))
                .put("onclose", action)
                .put("onfail", action),
        )
        val alertId = alert.optString("alertId")
        if (alertId.isBlank()) throw publicResult.exceptionOrNull() ?: SsapException("Não foi possível alterar a imagem")
        ssap.request("ssap://system.notifications/closeAlert", JSONObject().put("alertId", alertId))
        delay(350)
    }

    fun wake() {
        val tv = _state.value.selectedTv ?: return
        val mac = _state.value.macAddress
        if (mac == null) {
            showError("O MAC da interface ativa ainda não foi identificado")
            return
        }
        reconnectJob?.cancel()
        scope.launch {
            runCatching { WakeOnLan.wake(mac, tv.host) }
                .onSuccess {
                    _state.update { it.copy(statusMessage = "Sinal de despertar enviado; aguardando a TV…", powerState = TvPowerState.UNKNOWN, errorMessage = null, phase = ConnectionPhase.RECONNECTING) }
                    delay(3_000)
                    if (!connectNow(tv)) scheduleReconnect()
                }
                .onFailure { showError(friendly(it)) }
        }
    }

    fun setHaptics(enabled: Boolean) = scope.launch {
        preferences.saveHaptics(enabled)
        _state.update { it.copy(hapticsEnabled = enabled) }
    }

    fun setSensitivity(value: Float) = scope.launch {
        preferences.saveSensitivity(value)
        _state.update { it.copy(pointerSensitivity = value) }
    }

    fun setOpenFirstYouTubeResult(enabled: Boolean) = scope.launch {
        preferences.saveOpenFirstYouTubeResult(enabled)
        _state.update { it.copy(openFirstYouTubeResult = enabled) }
    }

    fun setYouTubeBufferSeconds(seconds: Int) = scope.launch {
        val safeValue = seconds.coerceIn(0, 30)
        preferences.saveYouTubeBufferSeconds(safeValue)
        _state.update { it.copy(youtubeBufferSeconds = safeValue) }
    }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    fun captureTvScreen() = command {
        _state.update { it.copy(screenshotLoading = true) }
        try {
            val payload = ssap.request("ssap://tv/executeOneShot", timeoutMs = 12_000)
            val imageUri = payload.optString("imageUri").takeIf(String::isNotBlank)
                ?: throw SsapException("A TV não retornou a imagem da tela")
            val host = _state.value.selectedTv?.host ?: throw SsapException("TV não configurada")
            val client = if (imageUri.startsWith("https://")) TvTls.client(host, secureStore) else OkHttpClient()
            try {
                client.newCall(Request.Builder().url(imageUri).build()).execute().use { response ->
                    if (!response.isSuccessful) throw SsapException("Falha ao baixar a captura da TV")
                    val bytes = response.body.bytes()
                    if (bytes.isEmpty()) throw SsapException("A captura retornou vazia")
                    _state.update { it.copy(tvScreenshot = bytes) }
                }
            } finally {
                client.dispatcher.executorService.shutdown()
            }
        } finally {
            _state.update { it.copy(screenshotLoading = false) }
        }
    }

    private fun connect(tv: TvDevice) {
        reconnectJob?.cancel()
        scope.launch { if (!connectNow(tv)) scheduleReconnect() }
    }

    private fun reachable(host: String): Boolean = listOf(3001, 3000).any { port ->
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 1_000) }; true }.getOrDefault(false)
    }

    private suspend fun resolveEndpoint(saved: TvDevice): TvDevice? {
        val pin = secureStore.getCertificatePin(saved.host)
        if (reachable(saved.host) && (pin == null || TvTls.matchesSavedIdentity(saved.host, pin))) return saved
        val devices = try { discovery.discover(2_500) } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) { emptyList() }
        _state.update { it.copy(discoveredTvs = devices) }
        // LG can share certificates across TVs. A matching certificate alone is NOT
        // unique identity: also require UDN or a MAC read through the existing pairing.
        val expectedMac = _state.value.macAddress
        val oldKey = secureStore.getClientKey(saved.host)
        val candidate = devices.firstOrNull { device ->
            if (device.host == saved.host || pin == null || !TvTls.matchesSavedIdentity(device.host, pin)) return@firstOrNull false
            if (saved.deviceId != null && !ConnectionPolicy.sameDevice(saved, device)) return@firstOrNull false
            if (expectedMac == null) return@firstOrNull ConnectionPolicy.sameDevice(saved, device)
            if (oldKey == null) return@firstOrNull false
            val temporaryStore = object : PairingStore {
                override fun getClientKey(host: String) = oldKey
                override fun saveClientKey(host: String, key: String) = Unit
                override fun getCertificatePin(host: String) = pin
                override fun saveCertificatePin(host: String, pin: String) = Unit
            }
            val probe = SsapClient(temporaryStore)
            probe.onPairingPrompt = { probe.close() } // Never silently pair a different TV.
            try {
                probe.connect(device.host)
                val info = probe.request("ssap://com.webos.service.connectionmanager/getinfo", timeoutMs = 3_000)
                listOf("wifiInfo", "wiredInfo").any { kind ->
                    info.optJSONObject(kind)?.optString("macAddress")?.replace("-", ":")?.equals(expectedMac.replace("-", ":"), true) == true
                }
            } catch (cancel: CancellationException) { throw cancel
            } catch (_: Exception) { false
            } finally { probe.close() }
        } ?: return null
        secureStore.copyVerifiedIdentity(saved.host, candidate.host)
        preferences.saveTv(candidate)
        _state.update { it.copy(selectedTv = candidate, appIcons = emptyMap(), statusMessage = "Endereço da TV atualizado para ${candidate.host}") }
        return candidate
    }

    private suspend fun connectNow(requested: TvDevice, quiet: Boolean = false): Boolean = connectionMutex.withLock {
        if (_state.value.selectedTv?.host != requested.host) return@withLock false
        if (ssap.connectedHost == requested.host) return@withLock true
        connecting.set(true)
        deliberateDisconnect = true
        heartbeatJob?.cancel()
        refreshJob?.cancel()
        ssap.close()
        pointer.close()
        try {
            if (!quiet) _state.update { it.copy(phase = ConnectionPhase.CONNECTING, statusMessage = "Verificando a TV na rede…", errorMessage = null) }
            val tv = resolveEndpoint(requested)
                ?: throw SsapConnectionException("TV não encontrada no endereço salvo; use Procurar TVs se o endereço mudou")
            _state.update { it.copy(phase = ConnectionPhase.CONNECTING, statusMessage = "Conectando a ${tv.host}…", errorMessage = null) }
            val secureTransport = preferences.preferences.first().secureTransport
            if (secureTransport) {
                try {
                    ssap.connect(tv.host, secure = true)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (secureError: Throwable) {
                    // Never bypass a certificate/pairing rejection using plaintext.
                    if (generateSequence(secureError) { it.cause }.any { it is SSLException || it is SsapException }) throw secureError
                    ssap.connect(tv.host, secure = false)
                    preferences.saveTv(tv, secureTransport = false)
                }
            } else {
                try {
                    ssap.connect(tv.host, secure = false)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (plainError: Throwable) {
                    if (plainError is SsapException) throw plainError
                    ssap.connect(tv.host, secure = true)
                    preferences.saveTv(tv, secureTransport = true)
                }
            }
            _state.update {
                it.copy(
                    phase = ConnectionPhase.CONNECTED,
                    powerState = TvPowerState.UNKNOWN,
                    statusMessage = "Conectada • ${tv.host}",
                    errorMessage = null,
                )
            }
            preferences.startUsage(null)
            installSubscriptions()
            monitorSession()
            refreshJob?.cancel()
            refreshJob = scope.launch {
                try {
                    try { ensurePointer()
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (error: SsapConnectionException) { throw error
                    } catch (_: Exception) {
                        _state.update { it.copy(capabilities = it.capabilities.copy(pointer = CapabilityState.UNKNOWN)) }
                    }
                    refreshAll()
                } catch (cancel: CancellationException) { throw cancel
                } catch (error: Exception) {
                    if (error is SsapConnectionException) connectionLost()
                }
            }
            return@withLock true
        } catch (cancel: CancellationException) {
            ssap.close()
            throw cancel
        } catch (error: Throwable) {
            ssap.close()
            pointer.close()
            _state.update { it.copy(phase = if (it.powerState == TvPowerState.STANDBY) ConnectionPhase.STANDBY else ConnectionPhase.DISCONNECTED,
                keyboardFocused = false, commandInFlight = false,
                statusMessage = ConnectionPolicy.offlineMessage(it.powerState == TvPowerState.STANDBY),
                errorMessage = if (quiet) it.errorMessage else friendly(error)) }
            return@withLock false
        } finally {
            deliberateDisconnect = false
            connecting.set(false)
        }
    }

    private suspend fun ensurePointer() = pointerMutex.withLock {
        if (pointer.isConnected) return@withLock
        val tv = _state.value.selectedTv ?: throw SsapException("TV não configurada")
        val path = ssap.request("ssap://com.webos.service.networkinput/getPointerInputSocket").optString("socketPath")
        if (path.isBlank()) throw SsapException("Canal do controle indisponível")
        pointer.connect(tv.host, path)
        _state.update { it.copy(capabilities = it.capabilities.copy(pointer = CapabilityState.SUPPORTED)) }
    }

    private fun applyPower(payload: JSONObject) {
        val power = ConnectionPolicy.powerState(payload.optString("state"), payload.optString("processing"))
        if (power == TvPowerState.UNKNOWN) return
        _state.update { current ->
            current.copy(powerState = power,
                phase = if (power == TvPowerState.ON) ConnectionPhase.CONNECTED else ConnectionPhase.STANDBY,
                statusMessage = if (power == TvPowerState.ON) "TV ligada • ${current.selectedTv?.host}" else "TV em standby",
                errorMessage = null)
        }
    }

    private fun monitorSession() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (ssap.isConnected) {
                try {
                    // Request/response confirms a live session, unlike a cached socket.
                    ssap.request("ssap://com.webos.applicationManager/getForegroundAppInfo", timeoutMs = 4_000)
                    try { applyPower(ssap.request("ssap://com.webos.service.tvpower/power/getPowerState", timeoutMs = 4_000))
                    } catch (_: SsapException) { /* unsupported power API: keep power unknown */ }
                } catch (cancel: CancellationException) { throw cancel
                } catch (error: SsapConnectionException) { connectionLost(); return@launch
                } catch (_: SsapException) { /* API denial is not a transport failure */ }
                delay(10_000)
            }
        }
    }

    private fun connectionLost() {
        pointer.close()
        refreshJob?.cancel()
        _state.update { current -> current.copy(
            phase = if (current.powerState == TvPowerState.STANDBY) ConnectionPhase.STANDBY else ConnectionPhase.DISCONNECTED,
            powerState = if (current.powerState == TvPowerState.STANDBY) TvPowerState.STANDBY else TvPowerState.UNKNOWN,
            keyboardFocused = false, commandInFlight = false,
            statusMessage = ConnectionPolicy.offlineMessage(current.powerState == TvPowerState.STANDBY), errorMessage = null,
        ) }
        scope.launch { preferences.closeUsage(); refreshUsage() }
        scheduleReconnect()
    }

    private fun installSubscriptions() {
        runCatching { ssap.subscribe("ssap://audio/getVolume", onValue = ::applyVolume) }
        runCatching {
            ssap.subscribe("ssap://com.webos.applicationManager/getForegroundAppInfo") { payload ->
                val appId = payload.optString("appId").takeIf(String::isNotBlank)
                _state.update { it.copy(foregroundAppId = appId) }
                scope.launch { preferences.updateActiveApp(appId) }
            }
        }
        runCatching {
            ssap.subscribe("ssap://com.webos.service.ime/registerRemoteKeyboard") { payload ->
                val focused = payload.optJSONObject("currentWidget")?.optBoolean("focus", false)
                    ?: payload.optBoolean("currentWidgetFocus", payload.optBoolean("focus", false))
                _state.update {
                    it.copy(
                        keyboardFocused = focused,
                        capabilities = it.capabilities.copy(keyboard = CapabilityState.SUPPORTED),
                    )
                }
            }
        }.onFailure { _state.update { s -> s.copy(capabilities = s.capabilities.copy(keyboard = CapabilityState.UNSUPPORTED)) } }
        runCatching {
            ssap.subscribe("ssap://com.webos.service.tvpower/power/getPowerState") { payload ->
                applyPower(payload)
            }
        }
    }

    private suspend fun refreshAll() {
        suspend fun optional(action: suspend () -> Unit) {
            try { action() } catch (cancel: CancellationException) { throw cancel
            } catch (error: SsapConnectionException) { throw error
            } catch (_: Exception) { /* optional firmware capability */ }
        }
        optional { applyVolume(ssap.request("ssap://audio/getVolume")) }
        optional { loadApps() }
        optional { loadInputs() }
        optional { loadNetworkInfo() }
        optional { loadPicture() }
        refreshUsage()
    }

    private suspend fun loadApps() {
        val payload = ssap.request("ssap://com.webos.applicationManager/listLaunchPoints")
        val saved = preferences.preferences.first()
        val rawItems = payload.optJSONArray("launchPoints").objects().mapNotNull { item ->
            val id = item.optString("id")
            if (id.isBlank()) null else TvApp(id, item.optString("title", id), item.optString("icon").takeIf(String::isNotBlank))
        }
        val favorites = if (saved.favoritesInitialized) saved.favoriteIds else defaultFavoriteIds(rawItems).also { preferences.saveFavorites(it) }
        val items = rawItems.map { it.copy(favorite = it.id in favorites) }
            .sortedWith(compareByDescending<TvApp> { it.favorite }.thenBy { it.title.lowercase() })
        _state.update { it.copy(apps = items) }
        loadAppIcons(items)
    }

    private suspend fun loadInputs() {
        val payload = ssap.request("ssap://tv/getExternalInputList")
        val items = payload.optJSONArray("devices").objects().mapNotNull { item ->
            val id = item.optString("id")
            if (id.isBlank()) null else TvInput(
                id = id,
                label = item.optString("label", id),
                port = item.optInt("port").takeIf { item.has("port") },
                connected = item.optBoolean("connected"),
                plugged = item.optBoolean("hdmiPlugIn"),
            )
        }
        _state.update { it.copy(inputs = items) }
    }

    private suspend fun loadPicture() {
        val settings = JSONObject()
        var lastError: Throwable? = null
        listOf("brightness", "contrast", "backlight", "pictureMode").forEach { key ->
            runCatching {
                ssap.request(
                    "ssap://settings/getSystemSettings",
                    JSONObject().put("category", "picture").put("keys", JSONArray(listOf(key))),
                )
            }.onSuccess { payload ->
                payload.optJSONObject("settings")?.takeIf { it.has(key) }?.let { settings.put(key, it.get(key)) }
            }.onFailure { if (it is CancellationException || it is SsapConnectionException) throw it; lastError = it }
        }
        if (settings.length() == 0) throw lastError ?: SsapException("A TV não liberou ajustes de imagem")
        _state.update { current ->
            val loaded = PictureSettings(
                    brightness = settings.stringInt("brightness"),
                    contrast = settings.stringInt("contrast"),
                    backlight = settings.stringInt("backlight"),
                    pictureMode = settings.optString("pictureMode").takeIf(String::isNotBlank),
                    writeSupport = current.picture.writeSupport,
                )
            current.copy(
                picture = loaded,
                pictureBaseline = current.pictureBaseline ?: loaded,
                capabilities = current.capabilities.copy(pictureRead = CapabilityState.SUPPORTED),
            )
        }
    }

    private fun defaultFavoriteIds(apps: List<TvApp>): Set<String> {
        val patterns = listOf(
            listOf("netflix"), listOf("disney"), listOf("globoplay", "globo"),
            listOf("max", "hbo"), listOf("crunchyroll"), listOf("youtube"),
        )
        return patterns.mapNotNull { aliases ->
            apps.firstOrNull { app -> aliases.any { alias -> app.title.contains(alias, true) || app.id.contains(alias, true) } }?.id
        }.toSet()
    }

    private fun loadAppIcons(apps: List<TvApp>) {
        val host = _state.value.selectedTv?.host ?: return
        apps.filter { it.iconUrl != null && _state.value.appIcons[it.id] == null }.forEach { app ->
            scope.launch {
                val raw = app.iconUrl ?: return@launch
                val candidates = when {
                    raw.startsWith("wss://") -> listOf(raw.replaceFirst("wss://", "https://"))
                    raw.startsWith("ws://") -> listOf(raw.replaceFirst("ws://", "http://"))
                    raw.startsWith("http://") || raw.startsWith("https://") -> listOf(raw)
                    else -> listOf("http://$host:3000/${raw.trimStart('/')}", "https://$host:3001/${raw.trimStart('/')}")
                }
                for (url in candidates) {
                    val client = if (url.startsWith("https://")) TvTls.client(host, secureStore) else OkHttpClient()
                    try {
                        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                            if (response.isSuccessful) {
                                val bytes = response.body.bytes()
                                if (bytes.isNotEmpty() && bytes.size <= 1_500_000) {
                                    _state.update { it.copy(appIcons = it.appIcons + (app.id to bytes)) }
                                    return@launch
                                }
                            }
                        }
                    } catch (_: Throwable) {
                        // Tenta a próxima forma da URL; a UI ainda desenha a marca conhecida.
                    } finally {
                        client.dispatcher.executorService.shutdown()
                    }
                }
            }
        }
    }

    private suspend fun loadNetworkInfo() {
        val info = ssap.request("ssap://com.webos.service.connectionmanager/getinfo")
        val status = ssap.request("ssap://com.webos.service.connectionmanager/getstatus")
        val wifiActive = status.optJSONObject("wifi")?.optString("state") == "connected"
        val wiredActive = status.optJSONObject("wired")?.optString("state") == "connected"
        val mac = when {
            wifiActive -> info.optJSONObject("wifiInfo")?.optString("macAddress")
            wiredActive -> info.optJSONObject("wiredInfo")?.optString("macAddress")
            else -> null
        }?.takeIf(WakeOnLan::isValidMac)
        val wolEnabled = if (wifiActive) status.optJSONObject("wifi")?.optBoolean("isWakeOnWifiEnabled") else wiredActive
        if (mac != null) preferences.saveMac(mac)
        _state.update {
            it.copy(
                macAddress = mac ?: it.macAddress,
                wakeOnWifiEnabled = wolEnabled,
                capabilities = it.capabilities.copy(wakeOnLan = when {
                    mac == null -> CapabilityState.UNKNOWN
                    wolEnabled == false -> CapabilityState.UNSUPPORTED
                    else -> CapabilityState.SUPPORTED
                }),
            )
        }
    }

    private fun applyVolume(payload: JSONObject) {
        val nested = payload.optJSONObject("volumeStatus")
        val volume = when {
            payload.has("volume") -> payload.optInt("volume")
            nested?.has("volume") == true -> nested.optInt("volume")
            else -> null
        }
        val muted = when {
            payload.has("muted") -> payload.optBoolean("muted")
            payload.has("mute") -> payload.optBoolean("mute")
            nested?.has("muteStatus") == true -> nested.optBoolean("muteStatus")
            else -> _state.value.muted
        }
        _state.update { it.copy(volume = volume ?: it.volume, maxVolume = nested?.optInt("maxVolume", 100) ?: it.maxVolume, muted = muted) }
    }

    private suspend fun refreshUsage() {
        val (sessions, activeSince) = preferences.usageSessions()
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val week = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
        fun observedSince(boundary: Long): Long = sessions.sumOf { session ->
            val end = session.endedAtEpochMs ?: now
            (end - maxOf(session.startedAtEpochMs, boundary)).coerceAtLeast(0)
        } + if (activeSince != null) (now - maxOf(activeSince, boundary)).coerceAtLeast(0) else 0
        val todayMs = observedSince(today)
        _state.update {
            it.copy(usage = UsageSummary(
                observedTodayMs = todayMs,
                observedWeekMs = observedSince(week),
                currentSessionStartedAt = activeSince,
                coverageNote = if (todayMs == 0L) "Sem dados registrados hoje. O app mede apenas enquanto acompanha a TV." else "Tempo observado pelo app; períodos offline são lacunas.",
            ))
        }
    }

    private fun keyboardCommand(uri: String, payload: JSONObject? = null) = command { ssap.request(uri, payload) }

    private suspend fun launchYouTubeSearch(appId: String, text: String) {
        ssap.request(
            "ssap://com.webos.applicationManager/launch",
            JSONObject().put("id", appId)
                .put("params", JSONObject().put("contentTarget", "q=$text")),
        )
    }

    private fun findYouTubeAppId(): String? = _state.value.apps.firstOrNull {
        it.id.contains("youtube", true) || it.title.contains("youtube", true)
    }?.id ?: _state.value.foregroundAppId?.takeIf { it.contains("youtube", true) }

    private fun enqueueYouTube(text: String, appId: String) {
        val item = YouTubeQueueItem(UUID.randomUUID().toString(), text.trim())
        youtubeQueueAppId = appId
        _state.update { state ->
            val waiting = state.youtubePlaybackEstimate != null || state.resolvingYouTubeQueueItemId != null || state.youtubeQueue.isNotEmpty()
            state.copy(
                youtubeQueue = state.youtubeQueue + item,
                statusMessage = if (waiting) "“${item.query}” adicionada à fila" else "Preparando “${item.query}”…",
            )
        }
        startYouTubeQueue()
    }

    private fun startYouTubeQueue() {
        if (youtubeQueueJob?.isActive == true) return
        youtubeQueueJob = scope.launch {
            try { runYouTubeQueue() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                _state.update { it.copy(resolvingYouTubeQueueItemId = null) }
                showError("Fila pausada: ${friendly(error)}")
            }
        }
    }

    private suspend fun runYouTubeQueue() {
        while (true) {
            while (_state.value.phase != ConnectionPhase.CONNECTED) delay(1_000)

            val current = _state.value.youtubePlaybackEstimate
            val currentEnd = current?.estimatedEndEpochMs
            if (current != null) {
                if (currentEnd == null) return
                val remaining = currentEnd - System.currentTimeMillis()
                if (remaining > 0) {
                    delay(remaining)
                    continue
                }
                _state.update { it.copy(youtubePlaybackEstimate = null) }
            }

            val next = _state.value.youtubeQueue.firstOrNull() ?: return
            _state.update {
                it.copy(
                    resolvingYouTubeQueueItemId = next.id,
                    statusMessage = "Buscando “${next.query}”…",
                )
            }
            val video = runCatching { youtubeResolver.firstVideo(next.query) }.getOrNull()
            if (_state.value.youtubeQueue.none { it.id == next.id }) continue
            if (video == null) {
                _state.update {
                    it.copy(
                        youtubeQueue = it.youtubeQueue.filterNot { queued -> queued.id == next.id },
                        resolvingYouTubeQueueItemId = null,
                    )
                }
                showError("Não foi possível encontrar “${next.query}”; item ignorado")
                continue
            }

            val appId = youtubeQueueAppId ?: findYouTubeAppId()
            if (appId == null) {
                showError("YouTube não encontrado na TV")
                return
            }
            try {
                ssap.request(
                    "ssap://com.webos.applicationManager/launch",
                    JSONObject().put("id", appId)
                        .put("params", JSONObject().put("contentTarget", "v=${video.id}")),
                )
            } catch (cancel: CancellationException) { throw cancel
            } catch (error: SsapConnectionException) {
                _state.update { it.copy(resolvingYouTubeQueueItemId = null) }
                connectionLost()
                continue // Keep the item in the queue until the TV reconnects.
            }
            val estimatedStart = System.currentTimeMillis() + _state.value.youtubeBufferSeconds * 1_000L
            val estimate = YouTubePlaybackEstimate(
                videoId = video.id,
                title = video.title,
                durationSeconds = video.durationSeconds,
                estimatedStartEpochMs = estimatedStart,
                estimatedEndEpochMs = video.durationSeconds?.let { estimatedStart + it * 1_000L },
            )
            _state.update {
                it.copy(
                    youtubeQueue = it.youtubeQueue.filterNot { queued -> queued.id == next.id },
                    resolvingYouTubeQueueItemId = null,
                    youtubePlaybackEstimate = estimate,
                    statusMessage = "Tocando ${video.title}${video.durationSeconds?.let { seconds -> " • ${formatDuration(seconds)}" }.orEmpty()}",
                )
            }
            if (estimate.estimatedEndEpochMs == null) {
                showError("Não foi possível obter a duração; a fila foi pausada")
                return
            }
        }
    }

    private fun formatDuration(seconds: Long): String {
        val hours = seconds / 3_600
        val minutes = (seconds % 3_600) / 60
        val remaining = seconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remaining)
        else "%d:%02d".format(minutes, remaining)
    }

    private fun command(action: suspend () -> Unit) {
        if (_state.value.phase != ConnectionPhase.CONNECTED || !ssap.isConnected) {
            if (!ssap.isConnected && _state.value.selectedTv != null) scheduleReconnect()
            showError(if (_state.value.powerState == TvPowerState.STANDBY) "Ligue a TV antes de enviar comandos" else "Sem conexão com a TV; verificando a rede")
            return
        }
        scope.launch {
            _state.update { it.copy(commandInFlight = true, errorMessage = null) }
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: SsapConnectionException) { connectionLost() }
            catch (error: Exception) { showError(friendly(error)) }
            finally { _state.update { it.copy(commandInFlight = false) } }
        }
    }

    private fun scheduleReconnect() {
        if ((!appActive && youtubeQueueJob?.isActive != true) || deliberateDisconnect || _state.value.selectedTv == null) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = 0
            while (appActive || youtubeQueueJob?.isActive == true) {
                delay(if (attempt < 3) (1_000L shl attempt) else 30_000L)
                val tv = _state.value.selectedTv ?: return@launch
                if (ssap.isConnected || connectNow(tv, quiet = true)) return@launch
                attempt++
            }
        }
    }

    private fun showError(message: String) = _state.update { it.copy(errorMessage = message) }
    private fun friendly(error: Throwable): String = when (error) {
        is SsapConnectionException, is java.net.ConnectException -> "TV não encontrada na rede. O endereço pode ter mudado; use Procurar TVs."
        is java.net.SocketTimeoutException -> "A TV não respondeu. Verifique se ela e o celular estão na mesma rede."
        else -> error.message?.substringBefore("\n") ?: "Erro inesperado"
    }
    private fun isValidHost(value: String) = value.matches(Regex("^[a-zA-Z0-9.-]+$")) && value.length in 1..253
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) ?: JSONObject() }
private fun JSONObject.stringInt(key: String): Int? = optString(key).toIntOrNull() ?: if (has(key)) optInt(key) else null
