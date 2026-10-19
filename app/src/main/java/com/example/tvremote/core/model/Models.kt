package com.example.tvremote.core.model

enum class ConnectionPhase {
    NOT_CONFIGURED, DISCOVERING, SELECTED, CONNECTING, WAITING_FOR_TV,
    CONNECTED, STANDBY, DISCONNECTED, RECONNECTING
}

enum class CapabilityState { UNKNOWN, SUPPORTED, UNSUPPORTED, DENIED }
enum class TvPowerState { UNKNOWN, ON, STANDBY }

data class TvDevice(
    val name: String,
    val host: String,
    val model: String? = null,
    val location: String? = null,
    val deviceId: String? = null,
)

data class TvApp(
    val id: String,
    val title: String,
    val iconUrl: String? = null,
    val favorite: Boolean = false,
)

data class TvInput(
    val id: String,
    val label: String,
    val port: Int? = null,
    val connected: Boolean = false,
    val plugged: Boolean = false,
)

data class PictureSettings(
    val brightness: Int? = null,
    val contrast: Int? = null,
    val backlight: Int? = null,
    val pictureMode: String? = null,
    val writeSupport: CapabilityState = CapabilityState.UNKNOWN,
)

data class UsageSession(
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val appId: String? = null,
    val origin: String = "observado",
)

data class UsageSummary(
    val observedTodayMs: Long = 0,
    val observedWeekMs: Long = 0,
    val currentSessionStartedAt: Long? = null,
    val coverageNote: String = "O app ainda não observou a TV hoje.",
)

data class YouTubePlaybackEstimate(
    val videoId: String,
    val title: String,
    val durationSeconds: Long?,
    val estimatedStartEpochMs: Long,
    val estimatedEndEpochMs: Long?,
)

data class YouTubeQueueItem(
    val id: String,
    val query: String,
)

data class TvCapabilities(
    val pointer: CapabilityState = CapabilityState.UNKNOWN,
    val keyboard: CapabilityState = CapabilityState.UNKNOWN,
    val wakeOnLan: CapabilityState = CapabilityState.UNKNOWN,
    val pictureRead: CapabilityState = CapabilityState.UNKNOWN,
    val pictureWrite: CapabilityState = CapabilityState.UNKNOWN,
)

data class TvUiState(
    val phase: ConnectionPhase = ConnectionPhase.NOT_CONFIGURED,
    val selectedTv: TvDevice? = null,
    val discoveredTvs: List<TvDevice> = emptyList(),
    val statusMessage: String = "Configure sua TV para começar",
    val errorMessage: String? = null,
    val volume: Int? = null,
    val maxVolume: Int = 100,
    val muted: Boolean = false,
    val foregroundAppId: String? = null,
    val apps: List<TvApp> = emptyList(),
    val appIcons: Map<String, ByteArray> = emptyMap(),
    val tvScreenshot: ByteArray? = null,
    val screenshotLoading: Boolean = false,
    val inputs: List<TvInput> = emptyList(),
    val picture: PictureSettings = PictureSettings(),
    val pictureBaseline: PictureSettings? = null,
    val pictureUndo: PictureSettings? = null,
    val keyboardFocused: Boolean = false,
    val macAddress: String? = null,
    val wakeOnWifiEnabled: Boolean? = null,
    val capabilities: TvCapabilities = TvCapabilities(),
    val usage: UsageSummary = UsageSummary(),
    val hapticsEnabled: Boolean = true,
    val pointerSensitivity: Float = 1f,
    val openFirstYouTubeResult: Boolean = true,
    val youtubeBufferSeconds: Int = 3,
    val youtubePlaybackEstimate: YouTubePlaybackEstimate? = null,
    val youtubeQueue: List<YouTubeQueueItem> = emptyList(),
    val resolvingYouTubeQueueItemId: String? = null,
    val commandInFlight: Boolean = false,
    val powerState: TvPowerState = TvPowerState.UNKNOWN,
)
