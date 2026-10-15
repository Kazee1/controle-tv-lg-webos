package com.example.tvremote.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeResolverTest {
    @Test
    fun parsesFirstVideoAndDuration() {
        val html = """
            <script>var ytInitialData = {"contents":[
              {"videoRenderer":{"videoId":"abc123","title":{"runs":[{"text":"Enemy"}]},"lengthText":{"simpleText":"3:41"}}},
              {"videoRenderer":{"videoId":"later","title":{"simpleText":"Later"},"lengthText":{"simpleText":"1:00:02"}}}
            ]};</script>
        """.trimIndent()

        assertEquals(YouTubeVideo("abc123", "Enemy", 221), YouTubeResolver.parseFirstVideo(html))
    }

    @Test
    fun parsesHoursAndRejectsInvalidDuration() {
        assertEquals(3_602L, YouTubeResolver.parseDuration("1:00:02"))
        assertNull(YouTubeResolver.parseDuration("AO VIVO"))
    }
}
