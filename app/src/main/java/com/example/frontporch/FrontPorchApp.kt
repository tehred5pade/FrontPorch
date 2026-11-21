package com.example.frontporch

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.frontporch.data.download.VideoDownloadManager
import com.example.frontporch.data.local.FrontPorchDatabase
import com.example.frontporch.data.prefs.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File

sealed interface ResolvedStream {
    data class Single(val url: String) : ResolvedStream
    data class Adaptive(val videoUrl: String, val audioUrl: String) : ResolvedStream
}

class FrontPorchApp : Application() {
    val okHttpClient = OkHttpClient.Builder().build()
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

/**
 * Caps Shorts stream resolution to what the current connection can handle. Wi-Fi / Ethernet
 * (or any unmetered connection) uses the user's high cap; cellular uses the mobile cap.
 */
fun Context.currentStreamMaxHeight(settings: UserSettings): Int {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val network = manager?.activeNetwork
    val capabilities = network?.let { manager.getNetworkCapabilities(it) }
    val isUnmetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true ||
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    return if (isUnmetered) settings.wifiStreamMaxHeight else settings.mobileStreamMaxHeight
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

/**
 * YouTube reports per-format audio tracks (for example "English (Original)" or "French")
 * when a Short has more than one language. Block a Short when the response explicitly lists
 * audio tracks and none of them are English. When no language metadata is present we let it
 * through, so ordinary single-track Shorts are never dropped by this check.
 */
private fun hasEnglishAudioTrack(root: JSONObject): Boolean {
    val trackNames = mutableListOf<String>()

    root.optJSONObject("streamingData")?.let { streamingData ->
        listOf("formats", "adaptiveFormats").forEach { key ->
            val formats = streamingData.optJSONArray(key) ?: return@forEach
            for (i in 0 until formats.length()) {
                val format = formats.optJSONObject(i) ?: continue
                if (!format.optString("mimeType").startsWith("audio/")) continue
                val track = format.optJSONObject("audioTrack") ?: continue
                val name = track.optString("displayName").ifEmpty { track.optString("locale") }
                if (name.isNotBlank()) trackNames.add(name)
            }
        }
    }

    root.optJSONObject("captions")
        ?.optJSONObject("playerCaptionsTracklistRenderer")
        ?.optJSONArray("audioTracks")?.let { tracks ->
            for (i in 0 until tracks.length()) {
                val track = tracks.optJSONObject(i) ?: continue
                val name = track.optString("displayName").ifEmpty { track.optString("languageCode") }
                if (name.isNotBlank()) trackNames.add(name)
            }
        }

    if (trackNames.isEmpty()) return true

    return trackNames.any { name ->
        val code = name.split('-', '_', '.', ' ', '(').firstOrNull().orEmpty()
        name.contains("english", ignoreCase = true) || code.equals("en", ignoreCase = true)
    }
}

suspend fun resolveShortStream(
    okHttpClient: OkHttpClient,
    videoId: String,
    visitorData: String?,
    maxHeight: Int = 1080
): Result<ResolvedStream> = withContext(Dispatchers.IO) {
    val clients = listOf(
        Triple("ANDROID_VR", "1.60.19", "28") to "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; Quest 3 Build/SQ3A.220605.009.A1) gzip",
        Triple("IOS", "20.10.4", "5") to "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_1 like Mac OS X;)"
    )

    var lastError = "Unknown error"

    for ((clientInfo, userAgent) in clients) {
        val (clientName, clientVersion, clientId) = clientInfo
        val visitorField = if (visitorData != null) """"visitorData": "$visitorData",""" else ""

        val jsonBody = """
            {
              "videoId": "$videoId",
              "context": {
                "client": {
                  $visitorField
                  "clientName": "$clientName",
                  "clientVersion": "$clientVersion",
                  "hl": "en",
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

        val lengthSeconds = root.optJSONObject("videoDetails")
            ?.optString("lengthSeconds")
            ?.toIntOrNull() ?: 0

        if (lengthSeconds > 185) {
            return@withContext Result.failure(Exception("Skipped long video (${lengthSeconds}s)"))
        }

        val hasAiDisclosure = responseText.contains("\"containsAiContent\":true") ||
            responseText.contains("Altered or synthetic content", ignoreCase = true)

        if (hasAiDisclosure) {
            return@withContext Result.failure(Exception("Skipped AI-disclosed video"))
        }

        if (!hasEnglishAudioTrack(root)) {
            return@withContext Result.failure(Exception("Skipped non-English Short"))
        }

        val streamingData = root.optJSONObject("streamingData")

        if (streamingData != null) {
            // 1. Prefer a muxed progressive format - one URL starts faster than a merged stream.
            val formats = streamingData.optJSONArray("formats")
            if (formats != null) {
                var bestMuxedUrl: String? = null
                var bestMuxedHeight = 0
                for (i in 0 until formats.length()) {
                    val format = formats.optJSONObject(i) ?: continue
                    val url = format.optString("url").ifEmpty { continue }
                    val height = format.optInt("height", 0)
                    if (height in 1..maxHeight && height > bestMuxedHeight) {
                        bestMuxedHeight = height
                        bestMuxedUrl = url
                    }
                }
                if (bestMuxedUrl != null) {
                    return@withContext Result.success(ResolvedStream.Single(bestMuxedUrl))
                }
            }

            // 2. Adaptive formats (standard for ANDROID_VR & Shorts), capped for this connection.
            val adaptive = streamingData.optJSONArray("adaptiveFormats")
            if (adaptive != null) {
                var bestVideoUrl: String? = null
                var bestVideoHeight = 0
                var smallestVideoUrl: String? = null
                var smallestVideoHeight = Int.MAX_VALUE
                var bestAudioUrl: String? = null
                var bestAudioBitrate = 0

                for (i in 0 until adaptive.length()) {
                    val item = adaptive.optJSONObject(i) ?: continue
                    val url = item.optString("url").ifEmpty { continue }
                    val mime = item.optString("mimeType")

                    if (mime.startsWith("video/mp4") && mime.contains("avc1")) {
                        val height = item.optInt("height", 0)
                        if (height in 1..maxHeight && height >= bestVideoHeight) {
                            bestVideoHeight = height
                            bestVideoUrl = url
                        }
                        if (height in 1 until smallestVideoHeight) {
                            smallestVideoHeight = height
                            smallestVideoUrl = url
                        }
                    } else if (mime.startsWith("audio/mp4") && mime.contains("mp4a")) {
                        val bitrate = item.optInt("bitrate", 0)
                        if (bitrate >= bestAudioBitrate) {
                            bestAudioBitrate = bitrate
                            bestAudioUrl = url
                        }
                    }
                }

                // If nothing fits the cap, take the smallest available variant rather than dropping the Short.
                val videoUrl = bestVideoUrl ?: smallestVideoUrl
                if (videoUrl != null && bestAudioUrl != null) {
                    return@withContext Result.success(ResolvedStream.Adaptive(videoUrl, bestAudioUrl))
                }
            }

            // 3. HLS manifest last (IOS client); adaptive bitrate handles slow links, but the
            //    extra manifest round-trip delays first frame when a direct URL is available.
            val hls = streamingData.optString("hlsManifestUrl")
            if (hls.isNotEmpty()) {
                return@withContext Result.success(ResolvedStream.Single(hls))
            }
        }

        val playability = root.optJSONObject("playabilityStatus")
        lastError = "${playability?.optString("status")}: ${playability?.optString("reason")}"
    }

    Result.failure(Exception(lastError))
}
