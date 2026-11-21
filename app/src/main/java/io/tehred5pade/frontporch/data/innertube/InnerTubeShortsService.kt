package io.tehred5pade.frontporch.data.innertube

import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import io.tehred5pade.frontporch.data.model.ShortsCategory
import io.tehred5pade.frontporch.data.util.SlopAndClickbaitFilter
import io.tehred5pade.frontporch.ui.models.ShortItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val ANTI_SLOP_OPERATORS =
    """-ai -sora -kling -midjourney -runway -veo -luma -"ai generated" -"ai art" -"ai video" -prank -fake"""

private val entryRegex = Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)
private val videoIdTagRegex = Regex("""<yt:videoId>([a-zA-Z0-9_-]{11})</yt:videoId>""")
private val titleTagRegex = Regex("""<title>(.*?)</title>""")
private val publishedTagRegex = Regex("""<published>(.*?)</published>""")

data class DatedShortItem(
    val item: ShortItem,
    val publishedIso: String
)

sealed interface ShortsFeedFilter {
    data object All : ShortsFeedFilter
    data object Subscriptions : ShortsFeedFilter
    data class Category(val category: ShortsCategory) : ShortsFeedFilter
}

class InnerTubeShortsService(
    private val okHttpClient: OkHttpClient
) {
    private val androidUserAgent =
        "com.google.android.youtube/20.10.38 (Linux; U; Android 15; en_US) gzip"

    suspend fun searchShortsByQuery(
        query: String,
        visitorData: String?,
        paramsOverride: String? = null,
        preferredAudioLanguage: String = "en"
    ): Result<List<ShortItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanQuery = "$query $ANTI_SLOP_OPERATORS"
            val hlCode = if (preferredAudioLanguage.isNotBlank() && !preferredAudioLanguage.equals("auto", ignoreCase = true)) {
                preferredAudioLanguage.lowercase()
            } else {
                "en"
            }

            val clientObj = JSONObject().apply {
                if (visitorData != null) put("visitorData", visitorData)
                put("clientName", "ANDROID")
                put("clientVersion", "20.10.38")
                put("androidSdkVersion", 35)
                put("hl", hlCode)
                put("gl", "US")
            }

            val payloadObj = JSONObject().apply {
                put("query", cleanQuery)
                if (paramsOverride != null) put("params", paramsOverride)
                put("context", JSONObject().put("client", clientObj))
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
                .header("User-Agent", androidUserAgent)
                .header("X-YouTube-Client-Name", "3")
                .header("X-YouTube-Client-Version", "20.10.38")
                .apply { if (visitorData != null) header("X-Goog-Visitor-Id", visitorData) }
                .post(payloadObj.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val (rawJson, isSuccess, code) = okHttpClient.newCall(request).execute().use { resp ->
                Triple(resp.body?.string().orEmpty(), resp.isSuccessful, resp.code)
            }

            if (!isSuccess) {
                error("Search HTTP $code: ${rawJson.take(120)}")
            }

            val results = mutableListOf<ShortItem>()
            val seenIds = mutableSetOf<String>()

            // 1. Recursive JSON scan for both reelWatchEndpoint AND watchEndpoint
            extractShortsFromJson(JSONObject(rawJson), results, seenIds)

            // 2. Regex fallback matching both watchEndpoint and reelWatchEndpoint videoIds
            if (results.isEmpty()) {
                val endpointRegex = Regex(""""(?:reelWatchEndpoint|watchEndpoint)"\s*:\s*\{\s*"videoId"\s*:\s*"([a-zA-Z0-9_-]{11})"""")
                endpointRegex.findAll(rawJson).forEach { match ->
                    val id = match.groupValues[1]
                    if (seenIds.add(id)) {
                        val title = query.removeSuffix(" #shorts")
                        if (!SlopAndClickbaitFilter.isSlopOrClickbait(title)) {
                            results.add(ShortItem(id = id, title = title))
                        }
                    }
                }
            }

            if (results.isEmpty()) {
                val keys = JSONObject(rawJson).keys().asSequence().toList()
                error("HTTP 200 (keys: $keys) but 0 IDs. Tail: ${rawJson.takeLast(180)}")
            }

            results
        }
    }

    suspend fun fetchReelSequence(
        seedVideoId: String? = null,
        continuation: String? = null,
        visitorData: String?,
        preferredAudioLanguage: String = "en"
    ): Pair<List<ShortItem>, String?> = withContext(Dispatchers.IO) {
        runCatching {
            val hlCode = if (preferredAudioLanguage.isNotBlank() && !preferredAudioLanguage.equals("auto", ignoreCase = true)) {
                preferredAudioLanguage.lowercase()
            } else {
                "en"
            }

            val clientObj = JSONObject().apply {
                if (visitorData != null) put("visitorData", visitorData)
                put("clientName", "ANDROID")
                put("clientVersion", "20.10.38")
                put("androidSdkVersion", 35)
                put("hl", hlCode)
                put("gl", "US")
            }

            val payloadObj = JSONObject().apply {
                if (continuation != null) put("continuation", continuation)
                else if (seedVideoId != null) put("seedVideoId", seedVideoId)
                put("context", JSONObject().put("client", clientObj))
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/reel/reel_watch_sequence?prettyPrint=false")
                .header("User-Agent", androidUserAgent)
                .header("X-YouTube-Client-Name", "3")
                .header("X-YouTube-Client-Version", "20.10.38")
                .apply { if (visitorData != null) header("X-Goog-Visitor-Id", visitorData) }
                .post(payloadObj.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val rawJson = okHttpClient.newCall(request).execute().use { it.body?.string() }
                ?: return@runCatching (emptyList<ShortItem>() to null)

            val root = JSONObject(rawJson)
            val items = mutableListOf<ShortItem>()
            val seenIds = mutableSetOf<String>()
            extractShortsFromJson(root, items, seenIds)

            val tokenRegex = Regex(""""(?:continuation|token)"\s*:\s*"([a-zA-Z0-9_%=-]{20,})"""")
            val nextContinuation = tokenRegex.find(rawJson)?.groupValues?.getOrNull(1)
                ?: root.optString("continuation").ifEmpty { null }

            items to nextContinuation
        }.getOrDefault(emptyList<ShortItem>() to null)
    }

    suspend fun fetchChannelShorts(
        channelId: String,
        channelName: String
    ): List<DatedShortItem> = withContext(Dispatchers.IO) {
        runCatching {
            val shortsPlaylistId = "UUSH" + channelId.removePrefix("UC")
            val feedUrl = "https://www.youtube.com/feeds/videos.xml?playlist_id=$shortsPlaylistId"

            val request = Request.Builder()
                .url(feedUrl)
                .header("User-Agent", androidUserAgent)
                .get()
                .build()

            val xml = okHttpClient.newCall(request).execute().use { it.body?.string().orEmpty() }
            if (xml.isBlank()) return@runCatching emptyList()

            entryRegex.findAll(xml).mapNotNull { match ->
                val block = match.groupValues[1]
                val videoId = videoIdTagRegex.find(block)?.groupValues?.getOrNull(1) ?: return@mapNotNull null
                val rawTitle = titleTagRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()
                val published = publishedTagRegex.find(block)?.groupValues?.getOrNull(1).orEmpty()

                if (SlopAndClickbaitFilter.isSlopOrClickbait(rawTitle, channelName)) return@mapNotNull null

                DatedShortItem(
                    item = ShortItem(
                        id = videoId,
                        title = rawTitle,
                        channelTitle = channelName
                    ),
                    publishedIso = published
                )
            }.toList()
        }.getOrDefault(emptyList())
    }

    suspend fun fetchSimilarChannelShorts(
        subscribedChannel: SubscriptionEntity,
        seedShortVideoId: String?,
        visitorData: String?
    ): List<ShortItem> = withContext(Dispatchers.IO) {
        val combined = mutableListOf<ShortItem>()
        val seenIds = mutableSetOf<String>()

        if (seedShortVideoId != null) {
            val (relatedReelItems, _) = fetchReelSequence(
                seedVideoId = seedShortVideoId,
                visitorData = visitorData
            )
            relatedReelItems.forEach { item ->
                if (seenIds.add(item.id)) {
                    combined.add(item)
                }
            }
        }

        val lookalikeQuery = "${subscribedChannel.name} #shorts"
        val searchItems = searchShortsByQuery(
            query = lookalikeQuery,
            visitorData = visitorData
        ).getOrDefault(emptyList())

        searchItems.forEach { item ->
            if (seenIds.add(item.id)) {
                combined.add(item)
            }
        }

        combined
    }

    private fun extractShortsFromJson(
        node: Any?,
        out: MutableList<ShortItem>,
        seenIds: MutableSet<String>
    ) {
        when (node) {
            is JSONObject -> {
                val endpoint = node.optJSONObject("reelWatchEndpoint")
                    ?: node.optJSONObject("watchEndpoint")

                if (endpoint != null) {
                    val videoId = endpoint.optString("videoId")
                    if (videoId != null && videoId.length == 11 && seenIds.add(videoId)) {
                        val title = node.optJSONObject("headline")?.optString("simpleText")
                            ?: node.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                            ?: node.optJSONObject("title")?.optString("simpleText")
                            ?: node.optString("accessibilityText").substringBefore(",")
                            .ifEmpty { "YouTube Short" }
                        val channelName = node.optJSONObject("channelTitle")?.optString("simpleText")
                            ?: node.optJSONObject("owner")?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                            ?: node.optJSONObject("shortBylineText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                            ?: ""
                        if (!SlopAndClickbaitFilter.isSlopOrClickbait(title, channelName)) {
                            out.add(ShortItem(id = videoId, title = title))
                        }
                    }
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    extractShortsFromJson(node.get(keys.next()), out, seenIds)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractShortsFromJson(node.get(i), out, seenIds)
                }
            }
        }
    }
}
