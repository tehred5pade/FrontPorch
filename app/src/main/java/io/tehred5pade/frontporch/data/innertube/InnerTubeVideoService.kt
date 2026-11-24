package io.tehred5pade.frontporch.data.innertube

import io.tehred5pade.frontporch.ResolvedStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class VideoComment(
    val id: String,
    val author: String,
    val text: String,
    val likeCount: String,
    val publishedTime: String
)

data class PlayableCandidate(
    val stream: ResolvedStream,
    val userAgent: String,
    val clientLabel: String
)

data class FullVideoDetails(
    val videoId: String,
    val title: String,
    val channelName: String,
    val viewCount: String,
    val publishDate: String,
    val description: String,
    val resolvedVisitorData: String?,
    val candidates: List<PlayableCandidate>,
    val captions: List<CaptionTrack> = emptyList()
)

class InnerTubeVideoService(
    private val okHttpClient: OkHttpClient
) {
    private val vrClientName = "ANDROID_VR"
    private val vrClientVersion = "1.61.48"
    private val vrClientId = "28"
    private val vrUserAgent =
        "com.google.android.apps.youtube.vr.oculus/$vrClientVersion (Linux; U; Android 12L; Quest 3 Build/SQ3A.220605.009.A1) gzip"

    private val iosClientName = "IOS"
    private val iosClientVersion = "20.10.4"
    private val iosClientId = "5"
    private val iosUserAgent =
        "com.google.ios.youtube/$iosClientVersion (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

    private val androidUserAgent =
        "com.google.android.youtube/20.10.38 (Linux; U; Android 15; en_US) gzip"
    private val webUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    suspend fun fetchBasicMetadata(videoId: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json")
                .header("User-Agent", webUserAgent)
                .get()
                .build()

            val json = okHttpClient.newCall(req).execute().use { it.body?.string().orEmpty() }
            val obj = JSONObject(json)
            val title = obj.optString("title").takeIf { it.isNotBlank() } ?: return@runCatching null
            val author = obj.optString("author_name", "Channel")
            title to author
        }.getOrNull()
    }

    @Volatile
    private var cachedSessionVisitorData: String? = null

    @Volatile
    private var cachedSessionCookie: String? = null

    /**
     * Fetches a real browser-equivalent visitorData + VISITOR_INFO1_LIVE cookie
     * from YouTube's service worker bootstrap endpoint over plain OkHttp.
     */
    private fun getSessionVisitorData(): Pair<String?, String?> {
        if (!cachedSessionVisitorData.isNullOrBlank()) {
            return cachedSessionVisitorData to cachedSessionCookie
        }

        return runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/sw.js_data")
                .header("User-Agent", webUserAgent)
                .header("Accept", "*/*")
                .get()
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty().removePrefix(")]}'")
                val cookies = resp.headers("Set-Cookie")
                    .joinToString("; ") { it.substringBefore(";") }
                    .takeIf { it.isNotBlank() }

                // Extract Cgt... visitorData token from sw.js_data JSON array
                val match = Regex(""""(Cgt[a-zA-Z0-9_-]{10,})"""").find(raw)
                val vd = match?.groupValues?.getOrNull(1)

                if (!vd.isNullOrBlank()) {
                    cachedSessionVisitorData = vd
                    cachedSessionCookie = cookies
                }
                vd to cookies
            }
        }.getOrDefault(null to null)
    }

    suspend fun loadFullVideo(
        videoId: String,
        fallbackVisitorData: String? = null
    ): Result<FullVideoDetails> = withContext(Dispatchers.IO) {
        val (scrapedVisitor, sessionCookie) = getSessionVisitorData()
        val activeVisitor = scrapedVisitor ?: fallbackVisitorData

        var title = "YouTube Video"
        var channelName = "Channel"
        var viewCount = ""
        var publishDate = ""
        var description = ""
        var captions: List<CaptionTrack> = emptyList()
        val candidates = mutableListOf<PlayableCandidate>()
        val errors = mutableListOf<String>()

        // 1. IOS client with sw.js_data visitorData (returns HLS manifest + muxed MP4)
        queryPlayerClient(
            videoId = videoId,
            visitorData = activeVisitor,
            cookieHeader = sessionCookie,
            clientName = "IOS",
            clientVersion = "20.10.4",
            clientId = "5",
            userAgent = iosUserAgent,
            extraClientFields = mapOf(
                "deviceMake" to "Apple",
                "deviceModel" to "iPhone16,2",
                "osName" to "iPhone",
                "osVersion" to "18.3.2.22D82"
            )
        ).onSuccess { root ->
            extractMetadata(root)?.let { meta ->
                title = meta.title
                channelName = meta.channelName
                viewCount = meta.viewCount
                publishDate = meta.publishDate
                description = meta.description
            }
            captions = parseCaptionTracks(root)
            root.optJSONObject("streamingData")?.let { sd ->
                val hlsUrl = sd.optString("hlsManifestUrl")
                if (hlsUrl.isNotEmpty()) {
                    candidates.add(PlayableCandidate(ResolvedStream.Single(hlsUrl), iosUserAgent, "IOS-hls"))
                }
                parseMuxedStream(sd)?.let {
                    candidates.add(PlayableCandidate(it, iosUserAgent, "IOS-muxed"))
                }
            }
        }.onFailure { errors.add("IOS: ${it.message}") }

        // 2. ANDROID_VR client with sw.js_data visitorData
        if (candidates.isEmpty()) {
            queryPlayerClient(
                videoId = videoId,
                visitorData = activeVisitor,
                cookieHeader = sessionCookie,
                clientName = "ANDROID_VR",
                clientVersion = "1.61.48",
                clientId = "28",
                userAgent = vrUserAgent,
                extraClientFields = mapOf(
                    "deviceMake" to "Oculus",
                    "deviceModel" to "Quest 3",
                    "osName" to "Android",
                    "osVersion" to "12L"
                )
            ).onSuccess { root ->
                if (title == "YouTube Video") {
                    extractMetadata(root)?.let { meta ->
                        title = meta.title
                        channelName = meta.channelName
                        viewCount = meta.viewCount
                        publishDate = meta.publishDate
                        description = meta.description
                    }
                }
                if (captions.isEmpty()) captions = parseCaptionTracks(root)
                root.optJSONObject("streamingData")?.let { sd ->
                    parseAdaptiveStream(sd)?.let {
                        candidates.add(PlayableCandidate(it, vrUserAgent, "ANDROID_VR-adaptive"))
                    }
                    parseMuxedStream(sd)?.let {
                        candidates.add(PlayableCandidate(it, vrUserAgent, "ANDROID_VR-muxed"))
                    }
                }
            }.onFailure {
                cachedSessionVisitorData = null
                errors.add("VR: ${it.message}")
            }
        }

        // Some videos fail stream resolution (and fall back to the WebView player) while
        // still exposing perfectly good caption tracks through the IOS/VR player endpoint.
        // Fetch them separately so CC works in both playback paths.
        if (captions.isEmpty()) {
            captions = fetchCaptionTracksOnly(videoId)
        }

        if (candidates.isEmpty()) {
            Result.failure(Exception(errors.joinToString(" | ").ifEmpty { "LOGIN_REQUIRED" }))
        } else {
            Result.success(
                FullVideoDetails(
                    videoId = videoId,
                    title = title,
                    channelName = channelName,
                    viewCount = viewCount,
                    publishDate = publishDate,
                    description = description,
                    resolvedVisitorData = activeVisitor,
                    candidates = candidates,
                    captions = captions
                )
            )
        }
    }

    /**
     * Caption-only lookup that does not depend on stream resolution succeeding. Tries the
     * same mobile clients used for playback but without the scraped visitor token/cookie,
     * which can make the player endpoint reject an otherwise playable video.
     */
    suspend fun fetchCaptionTracksOnly(videoId: String): List<CaptionTrack> = withContext(Dispatchers.IO) {
        val clients = listOf(
            CaptionClient("IOS", "20.10.4", "5", iosUserAgent),
            CaptionClient("ANDROID_VR", "1.61.48", "28", vrUserAgent)
        )
        for (client in clients) {
            val root = queryPlayerClient(
                videoId = videoId,
                visitorData = null,
                cookieHeader = null,
                clientName = client.name,
                clientVersion = client.version,
                clientId = client.id,
                userAgent = client.userAgent
            ).getOrNull() ?: continue

            val tracks = parseCaptionTracks(root)
            if (tracks.isNotEmpty()) return@withContext tracks
        }
        emptyList()
    }

    /** Downloads the WebVTT body for a parsed caption track. */
    suspend fun fetchCaptionVtt(caption: CaptionTrack): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(caption.url)
                .header("User-Agent", iosUserAgent)
                .header("Referer", "https://www.youtube.com/")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull()
    }

    private data class CaptionClient(
        val name: String,
        val version: String,
        val id: String,
        val userAgent: String
    )

    suspend fun fetchVideoComments(
        videoId: String,
        visitorData: String?
    ): List<VideoComment> = withContext(Dispatchers.IO) {
        val webComments = fetchCommentsWithClient(
            videoId = videoId,
            visitorData = visitorData,
            clientName = "WEB",
            clientVersion = "2.20261101.01.00",
            clientId = "1",
            userAgent = webUserAgent
        )
        if (webComments.isNotEmpty()) return@withContext webComments

        fetchCommentsWithClient(
            videoId = videoId,
            visitorData = visitorData,
            clientName = "ANDROID",
            clientVersion = "20.10.38",
            clientId = "3",
            userAgent = androidUserAgent
        )
    }

    private fun fetchCommentsWithClient(
        videoId: String,
        visitorData: String?,
        clientName: String,
        clientVersion: String,
        clientId: String,
        userAgent: String
    ): List<VideoComment> = runCatching {
        val clientObj = JSONObject().apply {
            if (visitorData != null) put("visitorData", visitorData)
            put("clientName", clientName)
            put("clientVersion", clientVersion)
            put("hl", "en")
            put("gl", "US")
        }

        val initialPayload = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().put("client", clientObj))
        }

        val initialReq = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/next?prettyPrint=false")
            .header("User-Agent", userAgent)
            .header("X-YouTube-Client-Name", clientId)
            .header("X-YouTube-Client-Version", clientVersion)
            .apply { if (visitorData != null) header("X-Goog-Visitor-Id", visitorData) }
            .post(initialPayload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val initialJson = okHttpClient.newCall(initialReq).execute().use { it.body?.string().orEmpty() }
        val initialRoot = JSONObject(initialJson)

        val comments = mutableListOf<VideoComment>()
        extractCommentsRecursive(initialRoot, comments)
        if (comments.isNotEmpty()) return@runCatching comments.distinctBy { it.id }.take(30)

        val token = findCommentsContinuationToken(initialRoot) ?: return@runCatching emptyList()

        val contPayload = JSONObject().apply {
            put("continuation", token)
            put("context", JSONObject().put("client", clientObj))
        }

        val contReq = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/next?prettyPrint=false")
            .header("User-Agent", userAgent)
            .header("X-YouTube-Client-Name", clientId)
            .header("X-YouTube-Client-Version", clientVersion)
            .apply { if (visitorData != null) header("X-Goog-Visitor-Id", visitorData) }
            .post(contPayload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val contJson = okHttpClient.newCall(contReq).execute().use { it.body?.string().orEmpty() }
        extractCommentsRecursive(JSONObject(contJson), comments)
        comments.distinctBy { it.id }.take(30)
    }.getOrDefault(emptyList())

    private fun findCommentsContinuationToken(root: JSONObject): String? {
        val panels = root.optJSONArray("engagementPanels")
        if (panels != null) {
            for (i in 0 until panels.length()) {
                val panelStr = panels.optJSONObject(i)?.toString().orEmpty()
                if (panelStr.contains("comment", ignoreCase = true)) {
                    val match = Regex(""""token"\s*:\s*"([a-zA-Z0-9_%=-]{24,})"""").find(panelStr)
                    if (match != null) return match.groupValues[1]
                }
            }
        }
        val watchNextRegex = Regex(
            """"token"\s*:\s*"([a-zA-Z0-9_%=-]{24,})"\s*,\s*"request"\s*:\s*"CONTINUATION_REQUEST_TYPE_WATCH_NEXT""""
        )
        return watchNextRegex.find(root.toString())?.groupValues?.getOrNull(1)
    }

    private fun queryPlayerClient(
        videoId: String,
        visitorData: String?,
        cookieHeader: String? = null,
        clientName: String,
        clientVersion: String,
        clientId: String,
        userAgent: String,
        extraClientFields: Map<String, String> = emptyMap()
    ): Result<JSONObject> = runCatching {
        val clientObj = JSONObject().apply {
            if (!visitorData.isNullOrBlank()) put("visitorData", visitorData)
            put("clientName", clientName)
            put("clientVersion", clientVersion)
            put("hl", "en")
            put("gl", "US")
            extraClientFields.forEach { (k, v) -> put(k, v) }
        }

        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().put("client", clientObj))
        }

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", userAgent)
            .header("X-YouTube-Client-Name", clientId)
            .header("X-YouTube-Client-Version", clientVersion)
            .apply {
                if (!visitorData.isNullOrBlank()) header("X-Goog-Visitor-Id", visitorData)
                if (!cookieHeader.isNullOrBlank()) header("Cookie", cookieHeader)
            }
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val (rawJson, isSuccess, code) = okHttpClient.newCall(request).execute().use { resp ->
            Triple(resp.body?.string().orEmpty(), resp.isSuccessful, resp.code)
        }
        if (!isSuccess) error("HTTP $code")

        val root = JSONObject(rawJson)
        val playability = root.optJSONObject("playabilityStatus")
        val status = playability?.optString("status") ?: "ERROR"
        if (status != "OK") {
            error("$status (${playability?.optString("reason").orEmpty()})")
        }
        root
    }

    private data class ParsedMeta(
        val title: String,
        val channelName: String,
        val viewCount: String,
        val publishDate: String,
        val description: String
    )

    private fun extractMetadata(root: JSONObject): ParsedMeta? {
        val details = root.optJSONObject("videoDetails") ?: return null
        val rawViews = details.optString("viewCount", "0").toLongOrNull() ?: 0L
        val publishDate = root.optJSONObject("microformat")
            ?.optJSONObject("playerMicroformatRenderer")
            ?.optString("publishDate")
            ?.take(10)
            .orEmpty()

        return ParsedMeta(
            title = details.optString("title", "YouTube Video"),
            channelName = details.optString("author", "Channel"),
            viewCount = formatViewCount(rawViews),
            publishDate = publishDate,
            description = details.optString("shortDescription").orEmpty()
        )
    }

    private fun parseAdaptiveStream(streamingData: JSONObject): ResolvedStream.Adaptive? {
        val adaptive = streamingData.optJSONArray("adaptiveFormats") ?: return null
        var bestVideoUrl: String? = null
        var bestVideoHeight = 0
        var bestAudioUrl: String? = null
        var bestAudioBitrate = 0

        for (i in 0 until adaptive.length()) {
            val item = adaptive.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (url.isEmpty()) continue
            val mime = item.optString("mimeType")

            if (mime.startsWith("video/mp4") && mime.contains("avc1")) {
                val height = item.optInt("height", 0)
                if (height in 1..1080 && height >= bestVideoHeight) {
                    bestVideoHeight = height
                    bestVideoUrl = url
                }
            } else if (mime.startsWith("audio/mp4") && mime.contains("mp4a")) {
                val bitrate = item.optInt("bitrate", 0)
                if (bitrate >= bestAudioBitrate) {
                    bestAudioBitrate = bitrate
                    bestAudioUrl = url
                }
            }
        }
        return if (bestVideoUrl != null && bestAudioUrl != null) {
            ResolvedStream.Adaptive(bestVideoUrl, bestAudioUrl)
        } else null
    }

    private fun parseMuxedStream(streamingData: JSONObject): ResolvedStream.Single? {
        val formats = streamingData.optJSONArray("formats") ?: return null
        var bestUrl: String? = null
        var bestHeight = 0
        for (i in 0 until formats.length()) {
            val item = formats.optJSONObject(i) ?: continue
            val url = item.optString("url")
            if (url.isEmpty()) continue
            val height = item.optInt("height", 0)
            if (height >= bestHeight) {
                bestHeight = height
                bestUrl = url
            }
        }
        return bestUrl?.let { ResolvedStream.Single(it) }
    }

    private fun extractCommentsRecursive(node: Any?, out: MutableList<VideoComment>) {
        when (node) {
            is JSONObject -> {
                val entity = node.optJSONObject("commentEntityPayload")
                if (entity != null) {
                    val props = entity.optJSONObject("properties")
                    val authorObj = entity.optJSONObject("author")
                    val toolbar = entity.optJSONObject("toolbar")

                    val id = props?.optString("commentId").orEmpty()
                    val text = props?.optJSONObject("content")?.optString("content").orEmpty().trim()
                    val author = authorObj?.optString("displayName").orEmpty().ifBlank { "Viewer" }
                    val time = props?.optString("publishedTime").orEmpty()
                    val likes = toolbar?.optString("likeCountNotliked").orEmpty()

                    if (id.isNotEmpty() && text.isNotEmpty()) {
                        out.add(VideoComment(id, author, text, likes, time))
                    }
                }

                val legacy = node.optJSONObject("commentRenderer")
                if (legacy != null) {
                    val id = legacy.optString("commentId")
                    val author = legacy.optJSONObject("authorText")?.optString("simpleText")
                        ?: legacy.optJSONObject("authorText")?.optJSONArray("runs")
                            ?.optJSONObject(0)?.optString("text")
                        ?: "Viewer"
                    val runs = legacy.optJSONObject("contentText")?.optJSONArray("runs")
                    val text = buildString {
                        if (runs != null) {
                            for (i in 0 until runs.length()) {
                                append(runs.optJSONObject(i)?.optString("text").orEmpty())
                            }
                        }
                    }.trim()
                    val likes = legacy.optJSONObject("voteCount")?.optString("simpleText").orEmpty()
                    val time = legacy.optJSONObject("publishedTimeText")?.optJSONArray("runs")
                        ?.optJSONObject(0)?.optString("text").orEmpty()

                    if (id.isNotEmpty() && text.isNotEmpty()) {
                        out.add(VideoComment(id, author, text, likes, time))
                    }
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    extractCommentsRecursive(node.get(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractCommentsRecursive(node.get(i), out)
                }
            }
        }
    }

    private fun formatViewCount(views: Long): String = when {
        views >= 1_000_000 -> String.format("%.1fM views", views / 1_000_000.0)
        views >= 1_000 -> String.format("%.1fK views", views / 1_000.0)
        else -> "$views views"
    }
}
