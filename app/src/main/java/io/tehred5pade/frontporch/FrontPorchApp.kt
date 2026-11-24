package io.tehred5pade.frontporch

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import io.tehred5pade.frontporch.data.download.VideoDownloadManager
import io.tehred5pade.frontporch.data.innertube.CaptionTrack
import io.tehred5pade.frontporch.data.innertube.parseCaptionTracks
import io.tehred5pade.frontporch.data.local.FrontPorchDatabase
import io.tehred5pade.frontporch.data.prefs.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

sealed interface ResolvedStream {
    data class Single(val url: String) : ResolvedStream
    data class Adaptive(val videoUrl: String, val audioUrl: String) : ResolvedStream
}

/** A resolved stream together with caption tracks and the creator's channel identity. */
data class ResolvedShortStream(
    val stream: ResolvedStream,
    val captions: List<CaptionTrack> = emptyList(),
    val channelId: String = "",
    val channelTitle: String = ""
)

class FrontPorchApp : Application() {
    val okHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        // Parallel ranged chunk downloads issue several requests to the same media host at
        // once. OkHttp's default dispatcher allows only 5 per host, which would queue them.
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 24
        })
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    var cachedVisitorData: String? = null

    val downloadManager by lazy {
        VideoDownloadManager(
            context = applicationContext,
            okHttpClient = okHttpClient,
            dao = FrontPorchDatabase.getDatabase(applicationContext).downloadedVideoDao()
        )
    }

    override fun onCreate() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = throwable.stackTraceToString()
            Log.e("FRONTPORCH_CRASH", stackTrace)
            runCatching {
                File(filesDir, "last_crash.txt").writeText(stackTrace)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        super.onCreate()
    }
}

private fun Context.activeNetworkCapabilities(): NetworkCapabilities? {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val network = manager?.activeNetwork ?: return null
    return manager.getNetworkCapabilities(network)
}

private fun Context.isUnmeteredConnection(): Boolean {
    val capabilities = activeNetworkCapabilities()
    return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true ||
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
}

private data class AutoStreamQuality(val maxHeight: Int, val targetFps: Int)

/**
 * Auto is stream-only. It starts from the current link's estimated downstream bandwidth and
 * picks a lower resolution/frame rate when the connection is slow so playback can continue
 * instead of buffering on a high-bitrate variant. Unknown/zero bandwidth keeps the best cap.
 */
private fun Context.autoStreamQuality(allowHighRes: Boolean): AutoStreamQuality {
    val kbps = activeNetworkCapabilities()?.getLinkDownstreamBandwidthKbps() ?: 0
    val (preferredHeight, preferredFps) = when {
        kbps <= 0 -> if (allowHighRes) 4320 to 0 else 1080 to 0
        kbps < 1_000 -> 240 to 30
        kbps < 2_000 -> 360 to 30
        kbps < 4_000 -> 480 to 30
        kbps < 8_000 -> 720 to 30
        kbps < 20_000 -> 1080 to 0
        kbps < 45_000 -> 1440 to 60
        else -> 2160 to 60
    }
    val maxHeight = if (allowHighRes) preferredHeight else minOf(preferredHeight, 1080)
    val targetFps = if (maxHeight < 720 && preferredFps == 0) 30 else preferredFps
    return AutoStreamQuality(maxHeight, targetFps)
}

/** Caps Shorts stream resolution to what the current connection can handle. */
fun Context.currentStreamMaxHeight(settings: UserSettings): Int {
    val configured = if (isUnmeteredConnection()) {
        settings.wifiStreamMaxHeight
    } else {
        settings.mobileStreamMaxHeight
    }
    // Height 0 is the stream-only Auto option.
    if (configured <= 0) return autoStreamQuality(settings.allowHighResContent).maxHeight
    return if (settings.allowHighResContent) configured else minOf(configured, 1080)
}

/** Preferred frame rate for Shorts on the current connection (0 means no explicit preference). */
fun Context.currentStreamTargetFps(settings: UserSettings): Int {
    val configuredHeight = if (isUnmeteredConnection()) {
        settings.wifiStreamMaxHeight
    } else {
        settings.mobileStreamMaxHeight
    }
    if (configuredHeight <= 0) return autoStreamQuality(settings.allowHighResContent).targetFps
    return if (isUnmeteredConnection()) settings.wifiStreamTargetFps else settings.mobileStreamTargetFps
}

/**
 * Caps downloads to the quality the user chose in Settings for the current connection.
 * Wi-Fi / Ethernet (or any unmetered connection) uses the high cap; cellular uses the mobile cap.
 */
fun Context.currentDownloadMaxHeight(settings: UserSettings): Int {
    val configured = if (isUnmeteredConnection()) {
        settings.wifiDownloadMaxHeight
    } else {
        settings.mobileDownloadMaxHeight
    }
    val safe = if (configured <= 0) 1080 else configured
    return if (settings.allowHighResContent) safe else minOf(safe, 1080)
}

/** Download FPS follows the Settings dropdown; Auto is never part of the download options. */
fun Context.currentDownloadTargetFps(settings: UserSettings): Int {
    val configuredHeight = if (isUnmeteredConnection()) {
        settings.wifiDownloadMaxHeight
    } else {
        settings.mobileDownloadMaxHeight
    }
    if (configuredHeight <= 0) return 0
    return if (isUnmeteredConnection()) settings.wifiDownloadTargetFps else settings.mobileDownloadTargetFps
}

suspend fun fetchVisitorData(okHttpClient: OkHttpClient): String? = withContext(Dispatchers.IO) {
    runCatching {
        val body = """{"context":{"client":{"clientName":"ANDROID_VR","clientVersion":"1.60.19"}}}"""
            .toRequestBody("application/json".toMediaType())

        val req = okhttp3.Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
            .header("X-YouTube-Client-Name", "28")
            .header("X-YouTube-Client-Version", "1.60.19")
            .post(body)
            .build()

        val json = JSONObject(okHttpClient.newCall(req).execute().body?.string() ?: "")
        json.optJSONObject("responseContext")?.optString("visitorData")?.ifEmpty { null }
    }.getOrNull()
}

private fun isTrackLanguageMatch(trackNameOrId: String, preferredAudioLanguage: String): Boolean {
    if (preferredAudioLanguage.isBlank() || preferredAudioLanguage.equals("auto", ignoreCase = true)) return true
    val cleanPref = preferredAudioLanguage.lowercase().trim()
    val cleanName = trackNameOrId.lowercase().trim()

    val code = cleanName.split('-', '_', '.', ' ', '(').firstOrNull().orEmpty()
    if (code == cleanPref) return true
    if (cleanName.startsWith(cleanPref)) return true

    val langNameMap = mapOf(
        "en" to listOf("english"),
        "es" to listOf("spanish", "español"),
        "fr" to listOf("french", "français"),
        "de" to listOf("german", "deutsch"),
        "ja" to listOf("japanese", "日本語"),
        "ko" to listOf("korean", "한국어"),
        "pt" to listOf("portuguese", "português"),
        "ru" to listOf("russian", "русский"),
        "zh" to listOf("chinese", "中文"),
        "hi" to listOf("hindi", "हिंदी"),
        "it" to listOf("italian", "italiano")
    )

    val names = langNameMap[cleanPref] ?: listOf(cleanPref)
    return names.any { cleanName.contains(it) }
}

/**
 * ExoPlayer already applies the user's preferred audio language through
 * `trackSelectionParameters`. Rejecting an otherwise playable Short here just because
 * YouTube labels its audio tracks differently was a frequent cause of shorts failing to
 * load and being auto-skipped, so resolution never fails on language metadata alone.
 */
private fun hasMatchingAudioTrack(root: JSONObject, preferredAudioLanguage: String): Boolean = true

suspend fun resolveShortStream(
    okHttpClient: OkHttpClient,
    videoId: String,
    visitorData: String?,
    maxHeight: Int = 1080,
    preferredAudioLanguage: String = "en",
    targetFps: Int = 0
): Result<ResolvedShortStream> = withContext(Dispatchers.IO) {
    val hlCode = if (preferredAudioLanguage.isNotBlank() && !preferredAudioLanguage.equals("auto", ignoreCase = true)) {
        preferredAudioLanguage.lowercase()
    } else {
        "en"
    }

    val clients = listOf(
        Triple("ANDROID_VR", "1.60.19", "28") to "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; Quest 3 Build/SQ3A.220605.009.A1) gzip",
        Triple("IOS", "20.10.4", "5") to "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X;)",
        Triple("ANDROID", "20.10.38", "3") to "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US; Pixel 8 Build/UQ1A.240205.004; gzip)"
    )

    var lastError = "Unknown error"

    for ((clientInfo, userAgent) in clients) {
        val (clientName, clientVersion, clientId) = clientInfo
        val visitorField = if (visitorData != null) """"visitorData": "$visitorData",""" else ""
        val sdkField = if (clientName == "ANDROID") """"androidSdkVersion": 35,""" else ""

        val jsonBody = """
            {
              "videoId": "$videoId",
              "context": {
                "client": {
                  $visitorField
                  $sdkField
                  "clientName": "$clientName",
                  "clientVersion": "$clientVersion",
                  "hl": "$hlCode",
                  "gl": "US"
                }
              }
            }
        """.trimIndent()

        val request = okhttp3.Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", userAgent)
            .header("X-YouTube-Client-Name", clientId)
            .header("X-YouTube-Client-Version", clientVersion)
            .apply { if (visitorData != null) header("X-Goog-Visitor-Id", visitorData) }
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val responseText = runCatching { okHttpClient.newCall(request).execute().body?.string() }.getOrNull()
            ?: continue

        val root = JSONObject(responseText)
        val captions = parseCaptionTracks(root)

        val videoDetails = root.optJSONObject("videoDetails")
        val lengthSeconds = videoDetails
            ?.optString("lengthSeconds")
            ?.toIntOrNull() ?: 0
        val resolvedChannelId = videoDetails?.optString("channelId").orEmpty()
        val resolvedChannelTitle = videoDetails?.optString("author").orEmpty()

        if (lengthSeconds > 185) {
            return@withContext Result.failure(Exception("Skipped long video (${lengthSeconds}s)"))
        }

        val hasAiDisclosure = responseText.contains("\"containsAiContent\":true") ||
            responseText.contains("Altered or synthetic content", ignoreCase = true)

        if (hasAiDisclosure) {
            return@withContext Result.failure(Exception("Skipped AI-disclosed video"))
        }

        if (!hasMatchingAudioTrack(root, preferredAudioLanguage)) {
            return@withContext Result.failure(Exception("Skipped Short without matching language ($preferredAudioLanguage)"))
        }

        val streamingData = root.optJSONObject("streamingData")
        // Frame rate is only a tie-breaker at the chosen height; it never drops a higher
        // resolution just because a lower one has the preferred frame rate. An explicit 30fps
        // preference (including Auto on a slow link) wins ties over 60fps when both exist.
        fun frameRateScore(fps: Int): Int {
            val isHighFps = fps >= 55
            return when {
                targetFps >= 55 -> if (isHighFps) 2 else 1
                targetFps in 1..54 -> if (isHighFps) 0 else 1
                else -> if (isHighFps) 1 else 0
            }
        }

        if (streamingData != null) {
            // 1. Prefer a muxed progressive format - one URL starts faster than a merged stream.
            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                var bestMuxedUrl: String? = null
                var bestMuxedHeight = 0
                var bestMuxedFpsScore = -1
                for (i in 0 until formats.length()) {
                    val format = formats.optJSONObject(i) ?: continue
                    val url = format.optString("url").ifEmpty { continue }
                    val height = format.optInt("height", 0)
                    val fpsScore = frameRateScore(format.optInt("fps", 30))
                    if (height in 1..maxHeight &&
                        (height > bestMuxedHeight ||
                            (height == bestMuxedHeight && fpsScore > bestMuxedFpsScore))
                    ) {
                        bestMuxedHeight = height
                        bestMuxedFpsScore = fpsScore
                        bestMuxedUrl = url
                    }
                }
                if (bestMuxedUrl != null) {
                    return@withContext Result.success(ResolvedShortStream(ResolvedStream.Single(bestMuxedUrl), captions, resolvedChannelId, resolvedChannelTitle))
                }
            }

            // 2. Adaptive formats (standard for ANDROID_VR & Shorts), capped for this connection.
            val adaptive = streamingData.optJSONArray("adaptiveFormats")
            if (adaptive != null) {
                var bestVideoUrl: String? = null
                var bestVideoHeight = 0
                var bestVideoFpsScore = -1
                var smallestVideoUrl: String? = null
                var smallestVideoHeight = Int.MAX_VALUE

                var bestMatchingAudioUrl: String? = null
                var bestMatchingAudioBitrate = -1
                var bestDefaultAudioUrl: String? = null
                var bestDefaultAudioBitrate = -1
                var bestFallbackAudioUrl: String? = null
                var bestFallbackAudioBitrate = -1

                for (i in 0 until adaptive.length()) {
                    val item = adaptive.optJSONObject(i) ?: continue
                    val url = item.optString("url").ifEmpty { continue }
                    val mime = item.optString("mimeType")

                    if (mime.startsWith("video/mp4") && mime.contains("avc1")) {
                        val height = item.optInt("height", 0)
                        val fpsScore = frameRateScore(item.optInt("fps", 30))
                        if (height in 1..maxHeight &&
                            (height > bestVideoHeight ||
                                (height == bestVideoHeight && fpsScore > bestVideoFpsScore))
                        ) {
                            bestVideoHeight = height
                            bestVideoFpsScore = fpsScore
                            bestVideoUrl = url
                        }
                        if (height in 1 until smallestVideoHeight) {
                            smallestVideoHeight = height
                            smallestVideoUrl = url
                        }
                    } else if (mime.startsWith("audio/mp4") && mime.contains("mp4a")) {
                        val bitrate = item.optInt("bitrate", 0)
                        val audioTrack = item.optJSONObject("audioTrack")
                        val trackId = audioTrack?.optString("id").orEmpty()
                        val displayName = audioTrack?.optString("displayName").orEmpty()
                        val isDefault = audioTrack?.optBoolean("audioIsDefault") ?: false

                        val isMatch = isTrackLanguageMatch(
                            trackNameOrId = if (trackId.isNotBlank()) trackId else displayName,
                            preferredAudioLanguage = preferredAudioLanguage
                        )

                        if (isMatch) {
                            if (bitrate > bestMatchingAudioBitrate) {
                                bestMatchingAudioBitrate = bitrate
                                bestMatchingAudioUrl = url
                            }
                        }
                        if (isDefault) {
                            if (bitrate > bestDefaultAudioBitrate) {
                                bestDefaultAudioBitrate = bitrate
                                bestDefaultAudioUrl = url
                            }
                        }
                        if (bitrate > bestFallbackAudioBitrate) {
                            bestFallbackAudioBitrate = bitrate
                            bestFallbackAudioUrl = url
                        }
                    }
                }

                val bestAudioUrl = bestMatchingAudioUrl ?: bestDefaultAudioUrl ?: bestFallbackAudioUrl

                // If nothing fits the cap, take the smallest available variant rather than dropping the Short.
                val videoUrl = bestVideoUrl ?: smallestVideoUrl
                if (videoUrl != null && bestAudioUrl != null) {
                    return@withContext Result.success(ResolvedShortStream(ResolvedStream.Adaptive(videoUrl, bestAudioUrl), captions, resolvedChannelId, resolvedChannelTitle))
                }
            }

            // 3. HLS manifest last (IOS client); adaptive bitrate handles slow links, but the
            //    extra manifest round-trip delays first frame when a direct URL is available.
            val hls = streamingData.optString("hlsManifestUrl")
            if (hls.isNotEmpty()) {
                return@withContext Result.success(ResolvedShortStream(ResolvedStream.Single(hls), captions, resolvedChannelId, resolvedChannelTitle))
            }
        }

        val playability = root.optJSONObject("playabilityStatus")
        lastError = "${playability?.optString("status")}: ${playability?.optString("reason")}"
    }

    Result.failure(Exception(lastError))
}
