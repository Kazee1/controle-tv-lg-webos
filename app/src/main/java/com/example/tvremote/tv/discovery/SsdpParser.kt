package com.example.tvremote.tv.discovery

import com.example.tvremote.core.model.TvDevice
import java.net.URI

object SsdpParser {
    private val lgMarker = Regex("lge?[-_ ]?com|webos|lg electronics|lgsmarttv", RegexOption.IGNORE_CASE)

    fun parse(packet: String): TvDevice? {
        val headers = packet.lineSequence().drop(1).mapNotNull { line ->
            val split = line.indexOf(':')
            if (split <= 0) null else line.substring(0, split).trim().lowercase() to line.substring(split + 1).trim()
        }.toMap()
        if (headers.values.none { lgMarker.containsMatchIn(it) }) return null
        val location = headers["location"] ?: return null
        val host = runCatching { URI(location).host }.getOrNull() ?: return null
        return TvDevice(name = headers["friendlyname"] ?: "LG webOS TV", host = host, location = location)
    }
}
