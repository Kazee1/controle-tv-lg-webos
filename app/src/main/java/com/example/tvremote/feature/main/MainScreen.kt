package com.example.tvremote.feature.main

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tvremote.core.model.*
import com.example.tvremote.tv.repository.TvControlRepository
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.roundToInt

private enum class Destination(val label: String) {
    REMOTE("Controle"), QUEUE("Fila"), TOUCHPAD("Touchpad"), SETTINGS("Configurações")
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.selectedTv == null) SetupScreen(state, viewModel.repository)
    else AppShell(state, viewModel.repository)
}

@Composable
private fun SetupScreen(state: TvUiState, repository: TvControlRepository) {
    var ip by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(Icons.Rounded.SettingsRemote, null, Modifier.padding(14.dp).size(32.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("Seu controle, direto na rede", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Celular e TV precisam estar na mesma rede Wi‑Fi.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(22.dp))
        Button(
            onClick = repository::discover,
            enabled = state.phase != ConnectionPhase.DISCOVERING,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            if (state.phase == ConnectionPhase.DISCOVERING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Rounded.Refresh, null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.phase == ConnectionPhase.DISCOVERING) "Procurando…" else "Procurar TVs")
        }
        state.discoveredTvs.forEach { tv ->
            Card(onClick = { repository.selectTv(tv) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(tv.name, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(tv.model, tv.host).joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("IP da TV") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { repository.connectManual(ip) }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { repository.connectManual(ip) }, modifier = Modifier.height(56.dp)) { Text("Conectar") }
        }
        Spacer(Modifier.height(8.dp))
        Text(state.statusMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun AppShell(state: TvUiState, repository: TvControlRepository) {
    var destination by remember { mutableStateOf(Destination.REMOTE) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbar.showSnackbar(it); repository.dismissError() }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { ConnectionBar(state, repository) },
        bottomBar = {
            NavigationBar(modifier = Modifier.navigationBarsPadding()) {
                Destination.entries.forEach { item ->
                    val icon = when (item) {
                        Destination.REMOTE -> Icons.Rounded.SettingsRemote
                        Destination.QUEUE -> Icons.AutoMirrored.Rounded.QueueMusic
                        Destination.TOUCHPAD -> Icons.Rounded.TouchApp
                        Destination.SETTINGS -> Icons.Rounded.Settings
                    }
                    NavigationBarItem(
                        selected = destination == item,
                        onClick = { destination = item },
                        icon = { Icon(icon, item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (destination) {
            Destination.REMOTE -> RemoteScreen(state, repository, Modifier.padding(padding))
            Destination.QUEUE -> QueueScreen(state, repository, Modifier.padding(padding))
            Destination.TOUCHPAD -> TouchpadScreen(state, repository, Modifier.padding(padding))
            Destination.SETTINGS -> SettingsScreen(state, repository, Modifier.padding(padding)) { destination = Destination.REMOTE }
        }
    }
}

@Composable
private fun ConnectionBar(state: TvUiState, repository: TvControlRepository) {
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(50.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (state.phase == ConnectionPhase.CONNECTED) Color(0xFF58D68D) else MaterialTheme.colorScheme.error))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(state.selectedTv?.name ?: "TV", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.statusMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.phase !in setOf(ConnectionPhase.CONNECTED, ConnectionPhase.CONNECTING, ConnectionPhase.WAITING_FOR_TV)) IconButton(onClick = repository::reconnect) { Icon(Icons.Rounded.Refresh, "Reconectar") }
        }
    }
}

@Composable
private fun RemoteScreen(state: TvUiState, repository: TvControlRepository, modifier: Modifier) {
    var confirmPower by remember { mutableStateOf(false) }
    var editApps by remember { mutableStateOf(false) }
    var remoteText by remember { mutableStateOf("") }
    fun submitText() {
        val text = remoteText.trim()
        if (text.isNotEmpty()) {
            repository.sendTextAndEnter(text)
            remoteText = ""
        }
    }
    val connected = state.phase == ConnectionPhase.CONNECTED
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                OutlinedTextField(
                    value = remoteText,
                    onValueChange = { remoteText = it },
                    placeholder = { Text("Pesquisar ou digitar na TV") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = ::submitText, enabled = connected && remoteText.isNotEmpty()) {
                            Icon(Icons.AutoMirrored.Rounded.Send, "Enviar texto para a TV")
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitText() }),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = { if (connected) confirmPower = true else repository.wake() },
                    enabled = connected || state.macAddress != null,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Rounded.PowerSettingsNew, if (connected) "Desligar TV" else "Ligar TV pela rede")
                }
            }
        }
        item { RemoteControlPad(connected, state.hapticsEnabled, repository::sendButton) }
        item { VolumeControl(state, repository, connected) }
        item {
            QuickApps(
                apps = state.apps.filter { it.favorite }, icons = state.appIcons, enabled = connected,
                onLaunch = repository::launchApp, onEdit = { editApps = true },
            )
        }
        item { InputsRow(state, repository, connected) }
    }
    if (confirmPower) AlertDialog(
        onDismissRequest = { confirmPower = false },
        title = { Text("Desligar a TV?") },
        confirmButton = { TextButton(onClick = { confirmPower = false; repository.turnOff() }) { Text("Desligar") } },
        dismissButton = { TextButton(onClick = { confirmPower = false }) { Text("Cancelar") } },
    )
    if (editApps) AppsEditor(state.apps, state.appIcons, repository, onDismiss = { editApps = false })
}

@Composable
private fun RemoteControlPad(enabled: Boolean, haptics: Boolean, onCommand: (String) -> Unit) {
    val feedback = LocalHapticFeedback.current
    val send: (String) -> Unit = { command ->
        if (enabled) {
            if (haptics) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onCommand(command)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(226.dp)
                .pointerInput(enabled) {
                    detectTapGestures { position ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val delta = position - center
                        val command = if (delta.getDistance() < size.width * .27f) "ENTER" else {
                            val angle = Math.toDegrees(atan2(delta.y, delta.x).toDouble())
                            when {
                                angle in -45.0..45.0 -> "RIGHT"
                                angle in 45.0..135.0 -> "DOWN"
                                angle >= 135.0 || angle <= -135.0 -> "LEFT"
                                else -> "UP"
                            }
                        }
                        send(command)
                    }
                }
                .semantics { contentDescription = "Anel direcional com botão OK no centro" },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val c = center
                val radius = size.minDimension / 2f - 3.dp.toPx()
                val innerRadius = radius * .54f
                drawCircle(Color(0xFF15181F), radius, c)
                drawCircle(Color(0xFF2A2E37), radius * .76f, c, style = Stroke(width = radius * .48f))
                drawCircle(Color(0xFF0E1117), innerRadius, c)
                drawCircle(Color(0xFF343A45), innerRadius, c, style = Stroke(width = 2.dp.toPx()))
                drawCircle(Color(0xFF78AEFF).copy(alpha = if (enabled) .8f else .25f), innerRadius, c, style = Stroke(width = 3.dp.toPx()))
                val arrowColor = if (enabled) Color(0xFFE9EDF5) else Color(0xFF6F7580)
                val d = radius * .64f
                val arm = 10.dp.toPx()
                fun chevron(point: Offset, a: Offset, b: Offset) {
                    drawLine(arrowColor, point + a, point, 3.dp.toPx(), StrokeCap.Round)
                    drawLine(arrowColor, point + b, point, 3.dp.toPx(), StrokeCap.Round)
                }
                chevron(Offset(c.x, c.y - d), Offset(-arm, arm), Offset(arm, arm))
                chevron(Offset(c.x, c.y + d), Offset(-arm, -arm), Offset(arm, -arm))
                chevron(Offset(c.x - d, c.y), Offset(arm, -arm), Offset(arm, arm))
                chevron(Offset(c.x + d, c.y), Offset(-arm, -arm), Offset(-arm, arm))
            }
            Text("OK", modifier = Modifier.align(Alignment.Center), fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 2.dp)) {
            RoundRemoteButton(enabled, { send("BACK") }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
            RoundRemoteButton(enabled, { send("HOME") }) { Icon(Icons.Rounded.Home, "Início") }
            RoundRemoteButton(enabled, { send("MENU") }) { Icon(Icons.Rounded.Settings, "Configurações da TV") }
        }
    }
}

@Composable
private fun RoundRemoteButton(enabled: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(58.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) { Box(contentAlignment = Alignment.Center) { content() } }
}

@Composable
private fun VolumeControl(state: TvUiState, repository: TvControlRepository, enabled: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).clip(RoundedCornerShape(34.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = repository::volumeDown, enabled = enabled, modifier = Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Rounded.VolumeDown, "Diminuir volume", Modifier.size(30.dp)) }
        IconButton(onClick = repository::toggleMute, enabled = enabled, modifier = Modifier.size(56.dp)) {
            Icon(Icons.AutoMirrored.Rounded.VolumeOff, if (state.muted) "Ativar som" else "Silenciar", Modifier.size(31.dp), tint = if (state.muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = repository::volumeUp, enabled = enabled, modifier = Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Aumentar volume", Modifier.size(30.dp)) }
    }
}

@Composable
private fun QuickApps(apps: List<TvApp>, icons: Map<String, ByteArray>, enabled: Boolean, onLaunch: (String) -> Unit, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Aplicativos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onEdit) { Icon(Icons.Rounded.Add, null); Text("Editar") }
        }
        if (apps.isEmpty()) OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth()) { Text("Escolher atalhos") }
        else LazyVerticalGrid(
            columns = GridCells.Fixed(3), modifier = Modifier.height((((apps.size + 2) / 3) * 92).dp), userScrollEnabled = false,
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(apps, key = { it.id }) { app ->
                Surface(onClick = { onLaunch(app.id) }, enabled = enabled, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.height(86.dp)) {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        AppArtwork(app, icons[app.id], Modifier.size(44.dp))
                        Spacer(Modifier.height(5.dp))
                        Text(app.title, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun InputsRow(state: TvUiState, repository: TvControlRepository, enabled: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Text("Entradas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        if (state.inputs.isEmpty()) Text("Nenhuma entrada anunciada", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.inputs.take(4).forEach { input ->
                OutlinedButton(onClick = { repository.switchInput(input.id) }, enabled = enabled, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp)) {
                    Icon(Icons.Rounded.SettingsInputHdmi, null, Modifier.size(18.dp)); Spacer(Modifier.width(3.dp)); Text(input.port?.let { "HDMI $it" } ?: input.label, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun AppsEditor(apps: List<TvApp>, icons: Map<String, ByteArray>, repository: TvControlRepository, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Atalhos do controle") },
        text = {
            if (apps.isEmpty()) Text("Conecte a TV para carregar os aplicativos instalados.")
            else LazyColumn(Modifier.height(420.dp)) {
                items(apps, key = { it.id }) { app ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppArtwork(app, icons[app.id], Modifier.size(42.dp))
                        Text(app.title, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                        Switch(checked = app.favorite, onCheckedChange = { repository.setFavorite(app.id, it) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Concluir") } },
    )
}

@Composable
private fun AppArtwork(app: TvApp, bytes: ByteArray?, modifier: Modifier) {
    val bitmap = remember(bytes) { bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    if (bitmap != null) Image(bitmap, app.title, modifier.clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
    else Surface(modifier, shape = RoundedCornerShape(10.dp), color = brandColor(app.title)) {
        Box(contentAlignment = Alignment.Center) { BrandFallback(app.title) }
    }
}

@Composable
private fun BrandFallback(title: String) {
    val mark = when {
        title.contains("netflix", true) -> "N"
        title.contains("disney", true) -> "Disney+"
        title.contains("globo", true) -> "G"
        title.contains("max", true) || title.contains("hbo", true) -> "max"
        title.contains("crunchy", true) -> "C"
        title.contains("youtube", true) -> "▶"
        else -> title.take(1).uppercase()
    }
    Text(mark, fontWeight = FontWeight.Black, color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
}

private fun brandColor(title: String) = when {
    title.contains("netflix", true) -> Color(0xFFE50914)
    title.contains("disney", true) -> Color(0xFF113CCF)
    title.contains("globo", true) -> Color(0xFFF58220)
    title.contains("max", true) || title.contains("hbo", true) -> Color(0xFF6A36E8)
    title.contains("crunchy", true) -> Color(0xFFF47521)
    title.contains("youtube", true) -> Color(0xFFFF0000)
    else -> Color(0xFF394457)
}

@Composable
private fun QueueScreen(state: TvUiState, repository: TvControlRepository, modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    fun add() {
        val text = query.trim()
        if (text.isNotEmpty()) {
            repository.addToYouTubeQueue(text)
            query = ""
        }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Adicionar música") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.weight(1f),
            )
            FilledIconButton(onClick = ::add, enabled = query.isNotBlank()) {
                Icon(Icons.Rounded.Add, "Adicionar à fila")
            }
        }

        state.youtubePlaybackEstimate?.let { PlayingNowRow(it, repository::skipCurrentYouTubeItem) }

        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.youtubeQueue.isEmpty()) "Fila vazia" else "A seguir · ${state.youtubeQueue.size}",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (state.youtubeQueue.isNotEmpty()) {
                TextButton(onClick = repository::clearYouTubeQueue) { Text("Limpar") }
            }
        }
        HorizontalDivider()

        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(state.youtubeQueue, key = { _, item -> item.id }) { index, item ->
                val resolving = state.resolvingYouTubeQueueItemId == item.id
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(start = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (index + 1).toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(30.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(item.query, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(
                            if (resolving) "Buscando vídeo…" else "Na fila",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (resolving) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (index > 1) {
                        IconButton(onClick = { repository.moveYouTubeQueueItemToTop(item.id) }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Rounded.VerticalAlignTop, "Mover para o início", Modifier.size(19.dp))
                        }
                    }
                    IconButton(
                        onClick = { repository.moveYouTubeQueueItem(item.id, -1) },
                        enabled = index > 0,
                        modifier = Modifier.size(38.dp),
                    ) { Icon(Icons.Rounded.KeyboardArrowUp, "Subir", Modifier.size(21.dp)) }
                    IconButton(
                        onClick = { repository.moveYouTubeQueueItem(item.id, 1) },
                        enabled = index < state.youtubeQueue.lastIndex,
                        modifier = Modifier.size(38.dp),
                    ) { Icon(Icons.Rounded.KeyboardArrowDown, "Descer", Modifier.size(21.dp)) }
                    IconButton(onClick = { repository.removeYouTubeQueueItem(item.id) }, modifier = Modifier.size(42.dp)) {
                        Icon(Icons.Rounded.Close, "Remover da fila", tint = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PlayingNowRow(item: YouTubePlaybackEstimate, onSkip: () -> Unit) {
    var now by remember(item.videoId, item.estimatedStartEpochMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(item.videoId, item.estimatedStartEpochMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val durationMs = item.durationSeconds?.times(1_000L)
    val elapsedMs = (now - item.estimatedStartEpochMs).coerceAtLeast(0L)
    val remainingMs = item.estimatedEndEpochMs?.let { (it - now).coerceAtLeast(0L) }
    val progress = durationMs?.takeIf { it > 0 }?.let { (elapsedMs.toFloat() / it).coerceIn(0f, 1f) }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Tocando agora", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                remainingMs?.let {
                    Text("Faltam ${formatQueueTime((it + 999) / 1_000)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = onSkip) { Icon(Icons.Rounded.SkipNext, "Tocar próxima") }
        }
        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

private fun formatQueueTime(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remaining = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remaining)
    else "%d:%02d".format(minutes, remaining)
}

@Composable
private fun TouchpadScreen(state: TvUiState, repository: TvControlRepository, modifier: Modifier) {
    val feedback = LocalHapticFeedback.current
    Box(
        modifier.fillMaxSize().background(Color(0xFF0E1117))
            .pointerInput(state.pointerSensitivity) { detectTapGestures(onTap = { if (state.hapticsEnabled) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove); repository.clickPointer() }) }
            .pointerInput(state.pointerSensitivity) { detectDragGestures { change, drag -> change.consume(); repository.movePointer(drag.x, drag.y) } }
            .semantics { contentDescription = "Touchpad em tela cheia: arraste para mover e toque para clicar" },
    )
}

@Composable
private fun SettingsScreen(state: TvUiState, repository: TvControlRepository, modifier: Modifier, onBack: () -> Unit) {
    var confirmForget by remember { mutableStateOf(false) }
    var chooseTv by remember { mutableStateOf(false) }
    var manualHost by remember { mutableStateOf("") }
    if (chooseTv) AlertDialog(
        onDismissRequest = { chooseTv = false },
        title = { Text("Encontrar a TV") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Selecione sua TV se o roteador mudou o endereço dela.")
                state.discoveredTvs.forEach { tv ->
                    TextButton(onClick = { repository.selectTv(tv); chooseTv = false }) { Text("${tv.name} • ${tv.host}") }
                }
                OutlinedTextField(manualHost, { manualHost = it }, label = { Text("IP da TV") }, singleLine = true)
                TextButton(onClick = repository::discover) { Text("Procurar novamente") }
            }
        },
        confirmButton = { TextButton(onClick = { repository.connectManual(manualHost); chooseTv = false }, enabled = manualHost.isNotBlank()) { Text("Conectar pelo IP") } },
        dismissButton = { TextButton(onClick = { chooseTv = false }) { Text("Voltar") } },
    )
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                Spacer(Modifier.width(6.dp))
                Text("Voltar ao controle")
            }
        }
        item {
            SettingsBlock("Conexão") {
                Text(state.selectedTv?.let { "${it.name} • ${it.host}" } ?: "—")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = repository::reconnect) { Text("Reconectar") }
                    TextButton(onClick = { chooseTv = true; repository.discover() }) { Text("Procurar TVs / IP") }
                }
                Text("Sem conexão não significa necessariamente desligada. O botão power envia o sinal de ligar pela rede quando não há controle ativo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            SettingsBlock("Controle") {
                SettingSwitch("Resposta tátil", state.hapticsEnabled, repository::setHaptics)
                Text("Sensibilidade do touchpad: ${"%.1f".format(state.pointerSensitivity)}×")
                Slider(state.pointerSensitivity, repository::setSensitivity, valueRange = .5f..2f, steps = 5)
            }
        }
        item {
            SettingsBlock("YouTube") {
                var bufferSeconds by remember(state.youtubeBufferSeconds) {
                    mutableFloatStateOf(state.youtubeBufferSeconds.toFloat())
                }
                SettingSwitch(
                    "Abrir primeiro resultado",
                    state.openFirstYouTubeResult,
                    repository::setOpenFirstYouTubeResult,
                )
                Text(
                    if (state.openFirstYouTubeResult) "As pesquisas entram na fila e abrem o primeiro vídeo encontrado."
                    else "Após pesquisar, permanece na lista de resultados.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Buffer entre vídeos: ${bufferSeconds.roundToInt()}s")
                Slider(
                    value = bufferSeconds,
                    onValueChange = { bufferSeconds = it },
                    onValueChangeFinished = { repository.setYouTubeBufferSeconds(bufferSeconds.roundToInt()) },
                    valueRange = 0f..30f,
                    steps = 29,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0s", style = MaterialTheme.typography.labelSmall)
                    Text("30s", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item { PictureSettingsBlock(state, repository) }
        item {
            SettingsBlock("Tela da TV") {
                Button(onClick = repository::captureTvScreen, enabled = !state.screenshotLoading) {
                    if (state.screenshotLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.PhotoCamera, null)
                    Spacer(Modifier.width(7.dp))
                    Text(if (state.screenshotLoading) "Capturando…" else "Capturar tela")
                }
                state.tvScreenshot?.let { bytes ->
                    val bitmap = remember(bytes) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }
                    bitmap?.let {
                        Spacer(Modifier.height(8.dp))
                        Image(it, "Captura atual da tela da TV", Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Fit)
                    }
                }
            }
        }
        item {
            SettingsBlock("Tempo observado") {
                val today = state.usage.observedTodayMs / 60_000
                val week = state.usage.observedWeekMs / 60_000
                Text("Hoje: ${today / 60}h ${today % 60}min  •  Semana: ${week / 60}h ${week % 60}min", fontWeight = FontWeight.SemiBold)
                Text(state.usage.coverageNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { OutlinedButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth()) { Text("Esquecer esta TV") } }
    }
    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false }, title = { Text("Esquecer a TV?") },
        confirmButton = { TextButton(onClick = { confirmForget = false; repository.forgetTv() }) { Text("Esquecer") } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun PictureSettingsBlock(state: TvUiState, repository: TvControlRepository) = SettingsBlock("Imagem") {
    val picture = state.picture
    val support = state.capabilities.pictureRead
    if (support != CapabilityState.SUPPORTED) {
        Text("Os controles só aparecem para parâmetros liberados pela TV.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = repository::refreshPicture) { Text("Verificar compatibilidade") }
    } else {
        picture.brightness?.let { PictureSlider("Brilho", "brightness", it, repository) }
        picture.contrast?.let { PictureSlider("Contraste", "contrast", it, repository) }
        picture.backlight?.let { PictureSlider("Retroiluminação", "backlight", it, repository) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = repository::undoPicture, enabled = state.pictureUndo != null, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                Icon(Icons.AutoMirrored.Rounded.Undo, null)
                Spacer(Modifier.width(4.dp))
                Text("Desfazer")
            }
            OutlinedButton(onClick = repository::restorePictureBaseline, enabled = state.pictureBaseline != null, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 6.dp)) {
                Icon(Icons.Rounded.Restore, null)
                Spacer(Modifier.width(4.dp))
                Text("Valores iniciais")
            }
        }
        if (picture.writeSupport == CapabilityState.DENIED) Text("A TV permite leitura, mas bloqueou alterações.", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun PictureSlider(label: String, key: String, actual: Int, repository: TvControlRepository) {
    var value by remember(actual) { mutableFloatStateOf(actual.toFloat()) }
    Text("$label: ${value.roundToInt()}")
    Slider(value, { value = it }, onValueChangeFinished = { repository.setPictureValue(key, value.roundToInt()) }, valueRange = 0f..100f)
}

@Composable
private fun SettingsBlock(title: String, content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(9.dp)); content()
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f)); Switch(checked, onChange)
    }
}
