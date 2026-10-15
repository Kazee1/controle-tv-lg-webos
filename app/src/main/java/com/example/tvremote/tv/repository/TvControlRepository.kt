package com.example.tvremote.tv.repository

import com.example.tvremote.core.model.CapabilityState
import com.example.tvremote.core.model.ConnectionPhase
import com.example.tvremote.core.model.PictureSettings
import com.example.tvremote.core.model.TvApp
import com.example.tvremote.core.model.TvCapabilities
import com.example.tvremote.core.model.TvDevice
import com.example.tvremote.core.model.TvInput
import com.example.tvremote.core.model.TvUiState
import com.example.tvremote.core.model.UsageSummary
import com.example.tvremote.core.model.YouTubePlaybackEstimate
import com.example.tvremote.core.model.YouTubeQueueItem
import com.example.tvremote.core.network.TvTls
import com.example.tvremote.core.network.WakeOnLan
import com.example.tvremote.core.storage.PreferencesStore
import com.example.tvremote.core.storage.SecurePairingStore
import com.example.tvremote.tv.discovery.TvDiscoveryService
import com.example.tvremote.tv.pointer.PointerClient
import com.example.tvremote.tv.ssap.SsapClient
import com.example.tvremote.tv.ssap.SsapException
import com.example.tvremote.youtube.YouTubeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private var reconnectJob: Job? = null
    private var backgroundCloseJob: Job? = null
    private var youtubeQueueJob: Job? = null
    private var youtubeQueueAppId: String? = null
    private var appActive = false
    private var deliberateDisconnect = false

    private val _state = MutableStateFlow(TvUiState())
    val state: StateFlow<TvUiState> = _state.asStateFlow()

    init {
        ssap.onPairingPrompt = {
            _state.update { it.copy(phase = ConnectionPhase.WAITING_FOR_TV, statusMessage = "Confirme o pareamento na tela da TV") }
        }
        ssap.onDisconnected = {
            pointer.close()
            scope.launch { preferences.closeUsage(); refreshUsage() }
            if (!deliberateDisconnect) {
                _state.update { current ->
                    current.copy(
                        phase = if (current.phase == ConnectionPhase.CONNECTED) ConnectionPhase.DISCONNECTED else current.phase,
                        statusMessage = if (current.phase == ConnectionPhase.CONNECTED) "TV desconectada" else current.statusMessage,
                    )
                }
                scheduleReconnect()
            }
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
            if (tv != null && _state.value.phase != ConnectionPhase.CONNECTED) connect(tv)
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
                    deliberateDisconnect = false
                    _state.update { current -> current.copy(phase = ConnectionPhase.DISCONNECTED, statusMessage = "Conexão pausada") }
                }
            }
        }
    }

    fun discover() {
        scope.launch {
            _state.update { it.copy(phase = ConnectionPhase.DISCOVERING, statusMessage = "Procurando TVs na rede…", errorMessage = null) }
            runCatching { discovery.discover() }
                .onSuccess { devices ->
                    _state.update {
                        it.copy(
                            phase = if (it.selectedTv == null) ConnectionPhase.NOT_CONFIGURED else ConnectionPhase.SELECTED,
                            discoveredTvs = devices,
                            statusMessage = if (devices.isEmpty()) "Nenhuma TV encontrada. Use o IP manual." else "${devices.size} TV(s) encontrada(s)",
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
        scope.launch {
            preferences.saveTv(tv)
            _state.update { it.copy(selectedTv = tv, phase = ConnectionPhase.SELECTED, errorMessage = null) }
            if (!connectNow(tv)) scheduleReconnect()
        }
    }

    fun reconnect() = _state.value.selectedTv?.let(::connect)

    fun forgetTv() {
        scope.launch {
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

    fun sendButton(name: String) = command {
        if (!pointer.button(name)) throw SsapException("Canal do controle indisponível")
    }

    fun movePointer(dx: Float, dy: Float) {
        val gain = _state.value.pointerSensitivity
        pointer.move((dx * gain).toInt(), (dy * gain).toInt())
    }

    fun clickPointer() { if (!pointer.click()) showError("Touchpad indisponível") }
    fun scrollPointer(dy: Float) { pointer.scroll((dy * _state.value.pointerSensitivity).toInt().coerceIn(-30, 30)) }

    fun volumeUp() = command { ssap.request("ssap://audio/volumeUp") }
    fun volumeDown() = command { ssap.request("ssap://audio/volumeDown") }
    fun setVolume(volume: Int) = command { ssap.request("ssap://audio/setVolume", JSONObject().put("volume", volume)) }
    fun toggleMute() = command { ssap.request("ssap://audio/setMute", JSONObject().put("mute", !_state.value.muted)) }
    fun turnOff() = command {
        ssap.request("ssap://system/turnOff")
        _state.update { it.copy(phase = ConnectionPhase.STANDBY, statusMessage = "TV em standby") }
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
        scope.launch {
            runCatching { WakeOnLan.wake(mac, tv.host) }
                .onSuccess {
                    _state.update { it.copy(statusMessage = "Sinal de despertar enviado; aguardando a TV…", phase = ConnectionPhase.RECONNECTING) }
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

    private fun connect(tv: TvDevice) = scope.launch { if (!connectNow(tv)) scheduleReconnect() }

    private suspend fun connectNow(tv: TvDevice): Boolean {
        // Outra chamada já está estabelecendo a mesma sessão. Isso não é falha e não deve
        // agendar uma reconexão concorrente, pois ela fecharia o socket recém-aberto.
        if (!connecting.compareAndSet(false, true)) return true
        deliberateDisconnect = true
        try {
            _state.update { it.copy(phase = ConnectionPhase.CONNECTING, statusMessage = "Conectando a ${tv.name}…", errorMessage = null) }
            val secureTransport = preferences.preferences.first().secureTransport
            if (secureTransport) {
                try {
                    ssap.connect(tv.host, secure = true)
                } catch (secureError: Throwable) {
                    ssap.connect(tv.host, secure = false)
                    preferences.saveTv(tv, secureTransport = false)
                }
            } else {
                try {
                    ssap.connect(tv.host, secure = false)
                } catch (plainError: Throwable) {
                    ssap.connect(tv.host, secure = true)
                    preferences.saveTv(tv, secureTransport = true)
                }
            }
            val pointerPath = ssap.request("ssap://com.webos.service.networkinput/getPointerInputSocket").optString("socketPath")
            if (pointerPath.isNotBlank()) pointer.connect(tv.host, pointerPath)
            _state.update {
                it.copy(
                    phase = ConnectionPhase.CONNECTED,
                    statusMessage = "Conectada a ${tv.name}",
                    capabilities = it.capabilities.copy(pointer = if (pointerPath.isBlank()) CapabilityState.UNSUPPORTED else CapabilityState.SUPPORTED),
                )
            }
            preferences.startUsage(null)
            installSubscriptions()
            refreshAll()
            return true
        } catch (error: Throwable) {
            _state.update { it.copy(phase = ConnectionPhase.DISCONNECTED, statusMessage = "TV indisponível", errorMessage = friendly(error)) }
            return false
        } finally {
            deliberateDisconnect = false
            connecting.set(false)
        }
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
                // Alguns firmwares emitem "Suspend" transitoriamente mesmo mantendo o socket e
                // aceitando comandos. A conexão só muda de estado quando o socket realmente cai.
            }
        }
    }

    private suspend fun refreshAll() {
        runCatching { applyVolume(ssap.request("ssap://audio/getVolume")) }
        runCatching { loadApps() }
        runCatching { loadInputs() }
        runCatching { loadPicture() }
        runCatching { loadNetworkInfo() }
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
            }.onFailure { lastError = it }
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
        preferences.saveMac(mac)
        _state.update {
            it.copy(
                macAddress = mac,
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
        youtubeQueueJob = scope.launch { runYouTubeQueue() }
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
            ssap.request(
                "ssap://com.webos.applicationManager/launch",
                JSONObject().put("id", appId)
                    .put("params", JSONObject().put("contentTarget", "v=${video.id}")),
            )
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
        if (_state.value.phase != ConnectionPhase.CONNECTED) {
            showError("Conecte a TV antes de enviar comandos")
            return
        }
        scope.launch {
            _state.update { it.copy(commandInFlight = true, errorMessage = null) }
            runCatching { action() }.onFailure { showError(friendly(it)) }
            _state.update { it.copy(commandInFlight = false) }
        }
    }

    private fun scheduleReconnect() {
        if (!appActive || deliberateDisconnect || _state.value.selectedTv == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val tv = _state.value.selectedTv ?: return@launch
            for (attempt in 1..3) {
                _state.update { it.copy(phase = ConnectionPhase.RECONNECTING, statusMessage = "Reconectando ($attempt/3)…") }
                delay((attempt * attempt * 1_000).toLong())
                if (connectNow(tv)) return@launch
            }
            _state.update { it.copy(phase = ConnectionPhase.DISCONNECTED, statusMessage = "TV fora da rede") }
        }
    }

    private fun showError(message: String) = _state.update { it.copy(errorMessage = message) }
    private fun friendly(error: Throwable): String = error.message?.substringBefore("\n") ?: "Erro inesperado"
    private fun isValidHost(value: String) = value.matches(Regex("^[a-zA-Z0-9.-]+$")) && value.length in 1..253
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { optJSONObject(it) ?: JSONObject() }
private fun JSONObject.stringInt(key: String): Int? = optString(key).toIntOrNull() ?: if (has(key)) optInt(key) else null
