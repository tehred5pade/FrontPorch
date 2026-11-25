package io.tehred5pade.frontporch.util

import android.net.Uri
import java.util.Locale

data class YouTubeLink(
    val videoId: String,
    val startMs: Long = 0L
)

/** Parses the YouTube link forms users normally share into a video id and optional start time. */
object YouTubeLinkParser {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
    private val YOUTUBE_HOSTS = setOf(
        "youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtube-nocookie.com"
    )
    private val VIDEO_PATHS = setOf("shorts", "reel", "embed", "v", "live")

    fun isValidVideoId(videoId: String?): Boolean {
        return !videoId.isNullOrBlank() && VIDEO_ID.matches(videoId)
    }

    fun parse(rawUrl: String?): YouTubeLink? {
        val raw = rawUrl?.trim().orEmpty()
        if (raw.isEmpty()) return null

        val initial = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        // Links pasted without a scheme (e.g. "youtu.be/...") still deserve to work.
        val uri = if (initial.scheme == null) {
            runCatching { Uri.parse("https://$raw") }.getOrNull() ?: return null
        } else {
            initial
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme == "frontporch") return parseCustomScheme(uri)
        if (scheme != "http" && scheme != "https") return null

        val host = uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return null
        val startMs = timestampMs(uri)

        if (host == "youtu.be") {
            val id = uri.pathSegments.firstOrNull { isValidVideoId(it) } ?: return null
            return YouTubeLink(id, startMs)
        }
        if (host !in YOUTUBE_HOSTS) return null

        val segments = uri.pathSegments.filter { it.isNotBlank() }
        val candidate = when {
            segments.firstOrNull() == "watch" -> uri.getQueryParameter("v")
            segments.size >= 2 && VIDEO_PATHS.contains(segments[0]) -> segments[1]
            // Bare youtube.com/VIDEO_ID links are occasionally shared too.
            segments.size == 1 && isValidVideoId(segments[0]) -> segments[0]
            else -> null
        }
        val id = candidate?.takeIf { isValidVideoId(it) } ?: return null
        return YouTubeLink(id, startMs)
    }

    private fun parseCustomScheme(uri: Uri): YouTubeLink? {
        val id = uri.getQueryParameter("v")
            ?.takeIf { isValidVideoId(it) }
            ?: uri.pathSegments.firstOrNull { isValidVideoId(it) }
            ?: return null
        return YouTubeLink(id, timestampMs(uri))
    }

    private fun timestampMs(uri: Uri): Long {
        for (key in arrayOf("t", "start", "time_continue")) {
            val value = uri.getQueryParameter(key)
            if (!value.isNullOrBlank()) return parseTimestamp(value)
        }
        return 0L
    }

    /** Accepts plain seconds, h/m/s shorthand, and h:mm:ss forms. */
    private fun parseTimestamp(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val value = raw.trim().lowercase(Locale.ROOT)
        if (value.matches(Regex("\\d+"))) {
            return value.toLongOrNull()?.times(1000L)?.coerceAtLeast(0L) ?: 0L
        }

        if (value.contains(":")) {
            val parts = value.split(":")
            if (parts.size in 2..3 && parts.all { it.matches(Regex("\\d+")) }) {
                var seconds = 0L
                parts.forEach { seconds = seconds * 60L + (it.toLongOrNull() ?: 0L) }
                return seconds.times(1000L).coerceAtLeast(0L)
            }
        }

        var seconds = 0L
        var digits = StringBuilder()
        var foundUnit = false
        for (ch in value) {
            when {
                ch in '0'..'9' -> digits.append(ch)
                ch == 'h' || ch == 'm' || ch == 's' -> {
                    if (digits.isEmpty()) return 0L
                    val amount = digits.toString().toLongOrNull() ?: return 0L
                    seconds += amount * when (ch) {
                        'h' -> 3600L
                        'm' -> 60L
                        else -> 1L
                    }
                    digits.setLength(0)
                    foundUnit = true
                }
                // Ignore separators such as spaces and commas.
                else -> Unit
            }
        }
        if (!foundUnit && digits.isNotEmpty()) {
            return digits.toString().toLongOrNull()?.times(1000L)?.coerceAtLeast(0L) ?: 0L
        }
        // Trailing digits after a unit (e.g. "1m30") are ambiguous; ignore them.
        if (digits.isNotEmpty()) return 0L
        return seconds.times(1000L).coerceAtLeast(0L)
    }
}
