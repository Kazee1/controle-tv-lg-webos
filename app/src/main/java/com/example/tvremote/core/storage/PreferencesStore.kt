package com.example.tvremote.core.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.tvremote.core.model.TvDevice
import com.example.tvremote.core.model.UsageSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore by preferencesDataStore("tv_remote_preferences")

data class AppPreferences(
    val tv: TvDevice? = null,
    val mac: String? = null,
    val secureTransport: Boolean = true,
    val haptics: Boolean = true,
    val sensitivity: Float = 1f,
    val openFirstYouTubeResult: Boolean = true,
    val youtubeBufferSeconds: Int = 3,
    val favoriteIds: Set<String> = emptySet(),
    val favoritesInitialized: Boolean = false,
)

class PreferencesStore(private val context: Context) {
    private object Keys {
        val host = stringPreferencesKey("tv_host")
        val name = stringPreferencesKey("tv_name")
        val model = stringPreferencesKey("tv_model")
        val location = stringPreferencesKey("tv_location")
        val mac = stringPreferencesKey("tv_mac")
        val secure = booleanPreferencesKey("secure_transport")
        val haptics = booleanPreferencesKey("haptics")
        val sensitivity = floatPreferencesKey("pointer_sensitivity")
        val openFirstYouTubeResult = booleanPreferencesKey("open_first_youtube_result")
        val youtubeBufferSeconds = intPreferencesKey("youtube_buffer_seconds")
        val favorites = stringPreferencesKey("favorite_app_ids")
        val favoritesInitialized = booleanPreferencesKey("favorites_initialized")
        val usage = stringPreferencesKey("usage_sessions")
        val activeSince = longPreferencesKey("active_since")
        val activeApp = stringPreferencesKey("active_app")
    }

    val preferences: Flow<AppPreferences> = context.dataStore.data.map { p ->
        AppPreferences(
            tv = p[Keys.host]?.let { host ->
                TvDevice(
                    name = p[Keys.name] ?: "LG webOS TV",
                    host = host,
                    model = p[Keys.model],
                    location = p[Keys.location],
                )
            },
            mac = p[Keys.mac],
            secureTransport = p[Keys.secure] ?: true,
            haptics = p[Keys.haptics] ?: true,
            sensitivity = p[Keys.sensitivity] ?: 1f,
            openFirstYouTubeResult = p[Keys.openFirstYouTubeResult] ?: true,
            youtubeBufferSeconds = p[Keys.youtubeBufferSeconds] ?: 3,
            favoriteIds = p[Keys.favorites]?.split(',')?.filter(String::isNotBlank)?.toSet() ?: emptySet(),
            favoritesInitialized = p[Keys.favoritesInitialized] ?: false,
        )
    }

    suspend fun saveTv(tv: TvDevice, secureTransport: Boolean = true) = context.dataStore.edit { p ->
        p[Keys.host] = tv.host
        p[Keys.name] = tv.name
        tv.model?.let { p[Keys.model] = it } ?: p.remove(Keys.model)
        tv.location?.let { p[Keys.location] = it } ?: p.remove(Keys.location)
        p[Keys.secure] = secureTransport
    }

    suspend fun clearTv() = context.dataStore.edit { p ->
        listOf(Keys.host, Keys.name, Keys.model, Keys.location, Keys.mac).forEach(p::remove)
    }

    suspend fun saveMac(mac: String?) = context.dataStore.edit { p ->
        mac?.let { p[Keys.mac] = it } ?: p.remove(Keys.mac)
    }

    suspend fun saveHaptics(enabled: Boolean) = context.dataStore.edit { it[Keys.haptics] = enabled }
    suspend fun saveSensitivity(value: Float) = context.dataStore.edit { it[Keys.sensitivity] = value }
    suspend fun saveOpenFirstYouTubeResult(enabled: Boolean) = context.dataStore.edit {
        it[Keys.openFirstYouTubeResult] = enabled
    }
    suspend fun saveYouTubeBufferSeconds(seconds: Int) = context.dataStore.edit {
        it[Keys.youtubeBufferSeconds] = seconds
    }
    suspend fun saveFavorites(ids: Set<String>) = context.dataStore.edit {
        it[Keys.favorites] = ids.joinToString(",")
        it[Keys.favoritesInitialized] = true
    }

    suspend fun startUsage(appId: String?) = context.dataStore.edit { p ->
        if (p[Keys.activeSince] == null) p[Keys.activeSince] = System.currentTimeMillis()
        appId?.let { p[Keys.activeApp] = it }
    }

    suspend fun updateActiveApp(appId: String?) = context.dataStore.edit { p ->
        appId?.let { p[Keys.activeApp] = it } ?: p.remove(Keys.activeApp)
    }

    suspend fun closeUsage() = context.dataStore.edit { p ->
        val started = p[Keys.activeSince] ?: return@edit
        val sessions = decodeSessions(p[Keys.usage]).toMutableList()
        sessions += UsageSession(started, System.currentTimeMillis(), p[Keys.activeApp])
        p[Keys.usage] = encodeSessions(sessions.takeLast(500))
        p.remove(Keys.activeSince)
        p.remove(Keys.activeApp)
    }

    suspend fun usageSessions(): Pair<List<UsageSession>, Long?> {
        val p = context.dataStore.data.first()
        return decodeSessions(p[Keys.usage]) to p[Keys.activeSince]
    }

    private fun encodeSessions(sessions: List<UsageSession>) = JSONArray().apply {
        sessions.forEach { s -> put(JSONObject().apply {
            put("start", s.startedAtEpochMs)
            put("end", s.endedAtEpochMs)
            put("app", s.appId)
            put("origin", s.origin)
        }) }
    }.toString()

    private fun decodeSessions(raw: String?): List<UsageSession> = runCatching {
        val array = JSONArray(raw ?: "[]")
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            UsageSession(
                startedAtEpochMs = item.getLong("start"),
                endedAtEpochMs = item.optLong("end").takeIf { !item.isNull("end") },
                appId = item.optString("app").takeIf { it.isNotBlank() },
                origin = item.optString("origin", "observado"),
            )
        }
    }.getOrDefault(emptyList())
}
