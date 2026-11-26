package io.tehred5pade.frontporch.data.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

enum class SearchSort(val label: String, val spParam: String?) {
    RELEVANCE("Relevance", null),           // YouTube's default relevance ranking
    POPULARITY("Popularity", "CAMSAhAB"),   // Ranked by popularity / view count
    UPLOAD_DATE("Upload Date", "CAISAhAB")  // Most recent uploads when supported
}

enum class SearchFilter(val label: String, val spParam: String?) {
    ALL("All", null),                     // Pure YouTube relevance across videos, Shorts, and channels
    VIDEOS("Videos", "EgIQAQ%3D%3D"),     // Videos only, ranked by relevance
    SHORTS("Shorts", "EgIQAw%3D%3D"),     // Shorts / vertical reels only
    CHANNELS("Channels", "EgIQAg%3D%3D")  // Channels only, ranked by relevance
}

sealed class YouTubeSearchResult {
    abstract val id: String

    data class Video(
        override val id: String,
        val title: String,
        val channelName: String,
        val channelId: String,
        val durationText: String,
        val metaText: String
    ) : YouTubeSearchResult()

    data class Short(
        override val id: String,
        val title: String,
        val viewsText: String,
        val thumbnailUrl: String,
        val channelTitle: String = "",
        val channelId: String = ""
    ) : YouTubeSearchResult()

    data class Channel(
        override val id: String,
        val title: String,
        val handle: String,
        val subscriberCountText: String,
        val avatarUrl: String
    ) : YouTubeSearchResult()
}

data class SearchPage(
    val items: List<YouTubeSearchResult>,
    val continuationToken: String?
)

class YouTubeSearchRepository(private val okHttpClient: OkHttpClient) {

    private val webUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    suspend fun searchFirstPage(
        query: String,
        filter: SearchFilter = SearchFilter.ALL,
        sort: SearchSort = SearchSort.RELEVANCE
    ): SearchPage = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext SearchPage(emptyList(), null)

        val effectiveQuery = if (filter == SearchFilter.SHORTS && !trimmed.contains("shorts", ignoreCase = true)) {
            "$trimmed #shorts"
        } else {
            trimmed
        }

        // Relevance keeps the category filter's spParam. Explicit sort params replace it,
        // matching YouTube's own ordering requests.
        val effectiveParams = if (sort == SearchSort.RELEVANCE) {
            filter.spParam
        } else {
            sort.spParam
        }

        val payload = JSONObject().apply {
            put("query", effectiveQuery)
            effectiveParams?.let { put("params", it) }
            put("context", buildClientContext())
        }

        executeSearchRequest(payload, filter)
    }

    suspend fun searchNextPage(
        continuationToken: String,
        filter: SearchFilter = SearchFilter.ALL
    ): SearchPage = withContext(Dispatchers.IO) {
        if (continuationToken.isBlank()) return@withContext SearchPage(emptyList(), null)

        val payload = JSONObject().apply {
            put("continuation", continuationToken)
            put("context", buildClientContext())
        }

        executeSearchRequest(payload, filter)
    }

    /**
     * Kept for callers that only need the first batch. New code should use [searchFirstPage]
     * so it can paginate.
     */
    suspend fun search(
        query: String,
        filter: SearchFilter = SearchFilter.ALL
    ): List<YouTubeSearchResult> = searchFirstPage(query, filter, SearchSort.RELEVANCE).items

    /**
     * Resolves a channel's avatar from its browse page. Imported subscriptions only carry
     * an ID and name, so this fills in the thumbnail used by the channel list.
     */
    suspend fun fetchChannelAvatar(channelId: String): String? = withContext(Dispatchers.IO) {
        if (channelId.isBlank()) return@withContext null

        val payload = JSONObject().apply {
            put("browseId", channelId)
            put("context", buildClientContext())
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .header("User-Agent", webUa)
            .header("Accept-Language", "en-US,en;q=0.9")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val root = JSONObject(resp.body?.string().orEmpty())
                val header = root.optJSONObject("header") ?: return@runCatching null

                // Current channel pages put the avatar in pageHeaderViewModel.image.
                val sources = header.optJSONObject("pageHeaderRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("pageHeaderViewModel")
                    ?.optJSONObject("image")
                    ?.optJSONObject("decoratedAvatarViewModel")
                    ?.optJSONObject("avatar")
                    ?.optJSONObject("avatarViewModel")
                    ?.optJSONObject("image")
                    ?.optJSONArray("sources")

                // Older channel pages used channelHeaderRenderer.thumbnail.thumbnails.
                val thumbnails = header.optJSONObject("channelHeaderRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")

                val rawUrl = when {
                    sources != null -> (sources.length() - 1 downTo 0)
                        .map { sources.optJSONObject(it)?.optString("url").orEmpty() }
                        .firstOrNull { it.isNotBlank() }

                    thumbnails != null -> (thumbnails.length() - 1 downTo 0)
                        .map { thumbnails.optJSONObject(it)?.optString("url").orEmpty() }
                        .firstOrNull { it.isNotBlank() }

                    else -> null
                } ?: return@runCatching null

                if (rawUrl.startsWith("//")) "https:$rawUrl" else rawUrl
            }
        }.getOrNull()
    }

    private fun buildClientContext(): JSONObject = JSONObject().put(
        "client",
        JSONObject().apply {
            put("clientName", "WEB")
            put("clientVersion", "2.20261101.00.00")
            put("hl", "en")
            put("gl", "US")
        }
    )

    private fun executeSearchRequest(
        payload: JSONObject,
        filter: SearchFilter
    ): SearchPage {
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .header("User-Agent", webUa)
            .header("Accept-Language", "en-US,en;q=0.9")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return runCatching {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching SearchPage(emptyList(), null)
                val root = JSONObject(resp.body?.string().orEmpty())

                val rawResults = mutableListOf<YouTubeSearchResult>()
                val seenIds = mutableSetOf<String>()

                // Initial result sections.
                root.optJSONObject("contents")
                    ?.optJSONObject("twoColumnSearchResultsRenderer")
                    ?.optJSONObject("primaryContents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")
                    ?.let { parseSectionArray(it, rawResults, seenIds) }

                // Continuation batches arrive in onResponseReceivedCommands.
                root.optJSONArray("onResponseReceivedCommands")?.let { commands ->
                    for (i in 0 until commands.length()) {
                        commands.optJSONObject(i)
                            ?.optJSONObject("appendContinuationItemsAction")
                            ?.optJSONArray("continuationItems")
                            ?.let { parseSectionArray(it, rawResults, seenIds) }
                    }
                }

                val nextToken = findContinuationToken(root)

                val filtered = when (filter) {
                    SearchFilter.SHORTS -> rawResults.mapNotNull { result ->
                        when (result) {
                            is YouTubeSearchResult.Short -> result
                            is YouTubeSearchResult.Video -> YouTubeSearchResult.Short(
                                id = result.id,
                                title = result.title,
                                viewsText = result.metaText,
                                thumbnailUrl = "https://i.ytimg.com/vi/${result.id}/hqdefault.jpg",
                                channelTitle = result.channelName,
                                channelId = result.channelId
                            )
                            is YouTubeSearchResult.Channel -> null
                        }
                    }
                    SearchFilter.VIDEOS -> rawResults.filterIsInstance<YouTubeSearchResult.Video>()
                    SearchFilter.CHANNELS -> rawResults.filterIsInstance<YouTubeSearchResult.Channel>()
                    SearchFilter.ALL -> rawResults
                }

                SearchPage(items = filtered, continuationToken = nextToken)
            }
        }.getOrDefault(SearchPage(emptyList(), null))
    }

    private fun parseSectionArray(
        sections: JSONArray,
        out: MutableList<YouTubeSearchResult>,
        seenIds: MutableSet<String>
    ) {
        for (i in 0 until sections.length()) {
            parseNodeRecursive(sections.opt(i), out, seenIds)
        }
    }

    private fun parseNodeRecursive(
        node: Any?,
        out: MutableList<YouTubeSearchResult>,
        seenIds: MutableSet<String>
    ) {
        when (node) {
            is JSONObject -> {
                // 1. videoRenderer
                node.optJSONObject("videoRenderer")?.let { vr ->
                    parseVideo(vr)?.let { parsed ->
                        val key = when (parsed) {
                            is YouTubeSearchResult.Video -> "v_${parsed.id}"
                            is YouTubeSearchResult.Short -> "s_${parsed.id}"
                            is YouTubeSearchResult.Channel -> "c_${parsed.id}"
                        }
                        if (seenIds.add(key)) out.add(parsed)
                    }
                }

                // 2. channelRenderer
                node.optJSONObject("channelRenderer")?.let { cr ->
                    parseChannel(cr)?.let { parsed ->
                        if (seenIds.add("c_${parsed.id}")) out.add(parsed)
                    }
                }

                // 3. reelItemRenderer
                node.optJSONObject("reelItemRenderer")?.let { rir ->
                    parseShortEntry(JSONObject().put("reelItemRenderer", rir))?.let { parsed ->
                        if (seenIds.add("s_${parsed.id}")) out.add(parsed)
                    }
                }

                // 4. shortsLockupViewModel
                node.optJSONObject("shortsLockupViewModel")?.let { slv ->
                    parseShortEntry(JSONObject().put("shortsLockupViewModel", slv))?.let { parsed ->
                        if (seenIds.add("s_${parsed.id}")) out.add(parsed)
                    }
                }

                // 5. lockupViewModel
                node.optJSONObject("lockupViewModel")?.let { lvm ->
                    parseLockupViewModel(lvm)?.let { parsed ->
                        val key = when (parsed) {
                            is YouTubeSearchResult.Video -> "v_${parsed.id}"
                            is YouTubeSearchResult.Short -> "s_${parsed.id}"
                            is YouTubeSearchResult.Channel -> "c_${parsed.id}"
                        }
                        if (seenIds.add(key)) out.add(parsed)
                    }
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    parseNodeRecursive(node.opt(keys.next()), out, seenIds)
                }
            }
            is JSONArray -> {
                for (j in 0 until node.length()) {
                    parseNodeRecursive(node.opt(j), out, seenIds)
                }
            }
        }
    }

    private fun parseLockupViewModel(lvm: JSONObject): YouTubeSearchResult? {
        val lvmStr = lvm.toString()
        val rawId = lvm.optString("contentId").takeIf { it.isNotBlank() }
            ?: lvm.optJSONObject("contentId")?.optString("videoId")
            ?: Regex(""""videoId"\s*:\s*"([^"]+)"""").find(lvmStr)?.groupValues?.getOrNull(1)
        val contentId = rawId.orEmpty()
        if (contentId.isBlank()) return null

        val metadata = lvm.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = metadata?.optJSONObject("title")?.optString("content")
            ?: readText(metadata?.optJSONObject("title"))
        val subtitle = metadata?.optJSONObject("subtitle")?.optString("content")
            ?: readText(metadata?.optJSONObject("subtitle"))

        val isShort = lvmStr.contains("reelWatchEndpoint") ||
            lvmStr.contains("SHORTS", ignoreCase = true) ||
            contentId.startsWith("shorts") ||
            title.contains("#shorts", ignoreCase = true)

        return if (isShort) {
            YouTubeSearchResult.Short(
                id = contentId,
                title = title,
                viewsText = subtitle,
                thumbnailUrl = "https://i.ytimg.com/vi/$contentId/hqdefault.jpg"
            )
        } else {
            YouTubeSearchResult.Video(
                id = contentId,
                title = title,
                channelName = subtitle,
                channelId = "",
                durationText = "",
                metaText = ""
            )
        }
    }

    /**
     * Finds the opaque "load more" token for the result list.
     *
     * Continuation responses also contain chip-filter continuation commands in
     * `header.searchHeaderRenderer`. Those are *not* next-page tokens. The old
     * tree walk returned whichever token appeared first, so after the first page
     * it followed a chip token and pagination effectively stopped. Only tokens
     * owned by a continuationItemRenderer / continuationItemViewModel are valid.
     */
    private fun findContinuationToken(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                val loadMoreContainer = node.optJSONObject("continuationItemRenderer")
                    ?: node.optJSONObject("continuationItemViewModel")
                if (loadMoreContainer != null) {
                    findAnyContinuationToken(loadMoreContainer)?.let { return it }
                }

                val keys = node.keys()
                while (keys.hasNext()) {
                    findContinuationToken(node.opt(keys.next()))?.let { return it }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findContinuationToken(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    /** Returns the first continuation token anywhere inside an already-identified load-more node. */
    private fun findAnyContinuationToken(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("continuationCommand")
                    ?.optString("token")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }

                val keys = node.keys()
                while (keys.hasNext()) {
                    findAnyContinuationToken(node.opt(keys.next()))?.let { return it }
                }
            }

            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findAnyContinuationToken(node.opt(i))?.let { return it }
                }
            }
        }
        return null
    }

    private fun parseVideo(vr: JSONObject): YouTubeSearchResult? {
        val videoId = vr.optString("videoId")
        if (videoId.isBlank()) return null

        val title = readText(vr.optJSONObject("title"))
        val ownerRuns = vr.optJSONObject("ownerText")?.optJSONArray("runs")
        val channelName = ownerRuns?.optJSONObject(0)?.optString("text").orEmpty()
        val channelId = ownerRuns?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId").orEmpty()
        val duration = readText(vr.optJSONObject("lengthText"))
        val views = readText(vr.optJSONObject("shortViewCountText")).ifBlank {
            readText(vr.optJSONObject("viewCountText"))
        }
        val published = readText(vr.optJSONObject("publishedTimeText"))
        val meta = listOf(views, published).filter { it.isNotBlank() }.joinToString(" • ")

        val isShort = duration.equals("SHORTS", ignoreCase = true) ||
            vr.optJSONObject("navigationEndpoint")?.optJSONObject("reelWatchEndpoint") != null

        return if (isShort) {
            YouTubeSearchResult.Short(
                id = videoId,
                title = title,
                viewsText = views,
                thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                channelTitle = channelName,
                channelId = channelId
            )
        } else if (title.isNotBlank()) {
            YouTubeSearchResult.Video(
                id = videoId,
                title = title,
                channelName = channelName,
                channelId = channelId,
                durationText = duration,
                metaText = meta
            )
        } else {
            null
        }
    }

    private fun parseChannel(cr: JSONObject): YouTubeSearchResult.Channel? {
        val channelId = cr.optString("channelId")
        if (channelId.isBlank()) return null

        val title = readText(cr.optJSONObject("title"))
        if (title.isBlank()) return null

        val subCount = readText(cr.optJSONObject("subscriberCountText"))
        val handle = readText(cr.optJSONObject("ownerText"))
        val thumbs = cr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val rawAvatar = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
        val avatarUrl = if (rawAvatar.startsWith("//")) "https:$rawAvatar" else rawAvatar

        return YouTubeSearchResult.Channel(
            id = channelId,
            title = title,
            handle = handle,
            subscriberCountText = subCount,
            avatarUrl = avatarUrl
        )
    }

    private fun parseShortEntry(entry: JSONObject?): YouTubeSearchResult.Short? {
        if (entry == null) return null

        entry.optJSONObject("shortsLockupViewModel")?.let { lockup ->
            val videoId = lockup.optJSONObject("onTap")
                ?.optJSONObject("innertubeCommand")
                ?.optJSONObject("reelWatchEndpoint")
                ?.optString("videoId").orEmpty()
            if (videoId.isBlank()) return null
            val overlay = lockup.optJSONObject("overlayMetadata")
            return YouTubeSearchResult.Short(
                id = videoId,
                title = overlay?.optJSONObject("primaryText")?.optString("content").orEmpty(),
                viewsText = overlay?.optJSONObject("secondaryText")?.optString("content").orEmpty(),
                thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            )
        }

        val rir = entry.optJSONObject("reelItemRenderer") ?: return null
        val videoId = rir.optString("videoId")
        if (videoId.isBlank()) return null
        val ownerRuns = rir.optJSONObject("ownerText")?.optJSONArray("runs")
        val channelTitle = ownerRuns?.optJSONObject(0)?.optString("text").orEmpty()
        val channelId = ownerRuns?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId").orEmpty()
        return YouTubeSearchResult.Short(
            id = videoId,
            title = readText(rir.optJSONObject("headline")),
            viewsText = readText(rir.optJSONObject("viewCountText")),
            thumbnailUrl = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
            channelTitle = channelTitle,
            channelId = channelId
        )
    }

    private fun readText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = obj.optJSONArray("runs") ?: return ""
        return buildString {
            for (i in 0 until runs.length()) {
                append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
    }
}
