package com.example.tvremote.youtube

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

data class YouTubeVideo(
    val id: String,
    val title: String,
    val durationSeconds: Long?,
)

class YouTubeResolver(private val client: OkHttpClient = OkHttpClient()) {
    fun firstVideo(query: String): YouTubeVideo? {
        val url = "https://www.youtube.com/results".toHttpUrl().newBuilder()
            .addQueryParameter("search_query", query)
            .addQueryParameter("hl", "pt-BR")
            .addQueryParameter("gl", "BR")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept-Language", "pt-BR,pt;q=0.9")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            parseFirstVideo(response.body.string())
        }
    }

    companion object {
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0 Safari/537.36"

        internal fun parseFirstVideo(html: String): YouTubeVideo? {
            val json = extractInitialData(html) ?: return null
            return findVideoRenderer(JSONObject(json))
        }

        private fun extractInitialData(html: String): String? {
            val markers = listOf("var ytInitialData =", "window[\"ytInitialData\"] =", "ytInitialData =")
            val markerIndex = markers.asSequence().map { html.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: return null
            val start = html.indexOf('{', markerIndex).takeIf { it >= 0 } ?: return null
            var depth = 0
            var inString = false
            var escaped = false
            for (index in start until html.length) {
                val char = html[index]
                if (inString) {
                    if (escaped) escaped = false
                    else if (char == '\\') escaped = true
                    else if (char == '"') inString = false
                } else when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return html.substring(start, index + 1)
                    }
                }
            }
            return null
        }

        private fun findVideoRenderer(value: Any?): YouTubeVideo? = when (value) {
            is JSONObject -> {
                value.optJSONObject("videoRenderer")?.let(::videoFromRenderer)
                    ?: value.keys().asSequence().mapNotNull { findVideoRenderer(value.opt(it)) }.firstOrNull()
            }
            is JSONArray -> (0 until value.length()).asSequence().mapNotNull { findVideoRenderer(value.opt(it)) }.firstOrNull()
            else -> null
        }

        private fun videoFromRenderer(renderer: JSONObject): YouTubeVideo? {
            val id = renderer.optString("videoId").takeIf(String::isNotBlank) ?: return null
            val title = textOf(renderer.optJSONObject("title")).ifBlank { "Vídeo do YouTube" }
            val duration = textOf(renderer.optJSONObject("lengthText")).takeIf(String::isNotBlank)?.let(::parseDuration)
            return YouTubeVideo(id, title, duration)
        }

        private fun textOf(value: JSONObject?): String {
            if (value == null) return ""
            value.optString("simpleText").takeIf(String::isNotBlank)?.let { return it }
            val runs = value.optJSONArray("runs") ?: return ""
            return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        }

        internal fun parseDuration(value: String): Long? {
            val parts = value.trim().split(':').map { it.toLongOrNull() ?: return null }
            if (parts.size !in 2..3) return null
            return parts.fold(0L) { total, part -> total * 60 + part }
        }
    }
}
