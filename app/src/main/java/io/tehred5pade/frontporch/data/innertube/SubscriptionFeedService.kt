package io.tehred5pade.frontporch.data.innertube

import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import io.tehred5pade.frontporch.data.local.CachedFeedVideoDao
import io.tehred5pade.frontporch.data.local.CachedFeedVideoEntity
import io.tehred5pade.frontporch.data.local.DownloadedVideoDao
import io.tehred5pade.frontporch.data.local.toCachedEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class FeedVideoItem(
    val videoId: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val publishedIso: String,
    val publishedEpochMs: Long,
    val thumbnailUrl: String,
    val isShort: Boolean = false,
    val views: Long = -1L,
    /** Total length in seconds. 0 when the source did not report one. */
    val durationSeconds: Int = 0
)

/** One page of a channel playlist plus the token needed to fetch the next page. */
data class ChannelVideoPage(
    val items: List<FeedVideoItem>,
    val nextContinuation: String?,
    /** False when the page came from the lightweight RSS feed instead of InnerTube. */
    val fromInnerTube: Boolean = true
)

class SubscriptionFeedService(
    baseClient: OkHttpClient,
    private val feedDao: CachedFeedVideoDao? = null,
    private val downloadedDao: DownloadedVideoDao? = null
) {
    // Dedicated pooled client for the parallel XML bursts. The shared pool lets all channel
    // feeds reuse TLS connections instead of handshaking per channel.
    private val feedClient: OkHttpClient = baseClient.newBuilder()
        .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val okHttpClient: OkHttpClient = baseClient

    private val androidUserAgent =
        "com.google.android.youtube/20.10.38 (Linux; U; Android 15; en_US) gzip"

    // ETag per channel feed so unchanged channels return 304 instead of XML.
    private val channelEtags = ConcurrentHashMap<String, String>()

    // Channels whose XML feed returned 404 this session; avoids retrying it on every refresh.
    private val channelXmlUnavailable = ConcurrentHashMap.newKeySet<String>()

    private val entryRegex = Regex("""<entry>(.*?)</entry>""", RegexOption.DOT_MATCHES_ALL)
    private val videoIdRegex = Regex("""<yt:videoId>([a-zA-Z0-9_-]{11})</yt:videoId>""")
    private val titleRegex = Regex("""<title>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
    private val publishedRegex = Regex("""<published>(.*?)</published>""")
    private val linkRegex = Regex("""<link[^>]+href="([^"]+)"""")
    private val viewsRegex = Regex("""<media:statistics[^>]*\bviews="(-?\d+)"""")
    private val durationRegex = Regex("""<media:content[^>]*\bduration="(\d+)"""")
    private val viewCountRegex = Regex("""([\d.,]+)\s*([KMB]?)\s*views?""", RegexOption.IGNORE_CASE)
    private val relativeAgeRegex = Regex(
        """(\d+)\s+(second|minute|hour|day|week|month|year)s?\s+ago""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Session cache of upcoming-stream checks. "Aired" verdicts are kept for the session;
     * "upcoming" verdicts expire so a stream that goes live still appears without a restart.
     */
    private data class UpcomingCacheEntry(
        val isUpcoming: Boolean,
        val checkedAtMs: Long
    )

    private val upcomingStatusCache = ConcurrentHashMap<String, UpcomingCacheEntry>()

    /**
     * Upcoming video IDs discovered while parsing. Stale rows from earlier sessions are
     * deleted from Room at the end of each refresh so they cannot linger in the feed.
     */
    private val detectedUpcomingIds = ConcurrentHashMap.newKeySet<String>()

    private companion object {
        const val MAX_SHORT_DURATION_SECONDS = 180
        const val FIRST_BATCH_SIZE = 25
        const val UPCOMING_CACHE_TTL_MS = 2 * 60 * 1000L
    }

    /**
     * First page of a channel's Videos tab. The ~20 KB RSS feed is used when available so
     * the list renders immediately; scrolling then continues through the InnerTube uploads
     * playlist with [fetchChannelVideosPage], which has no 15-video cap.
     */
    suspend fun fetchChannelVideosFirstPage(
        subscription: SubscriptionEntity
    ): Result<ChannelVideoPage> = withContext(Dispatchers.IO) {
        val sub = normalizedChannel(subscription)
            ?: return@withContext Result.failure(
                Exception("Invalid channelId format: ${subscription.channelId}")
            )

        val xmlItems = fetchFromXmlFeed(sub).getOrNull()
            .orEmpty()
            .filterNot { it.isShort }
            .filterNot { item ->
                // Premieres report their creation time in <published>, so each entry has to
                // be checked instead of trusting the timestamp.
                isUpcomingLiveStream(
                    videoId = item.videoId,
                    publishedEpochMs = item.publishedEpochMs,
                    rssViews = item.views
                )
            }

        if (xmlItems.isNotEmpty()) {
            Result.success(
                ChannelVideoPage(
                    items = xmlItems,
                    nextContinuation = null,
                    fromInnerTube = false
                )
            )
        } else {
            fetchChannelVideosPage(sub, continuation = null)
        }
    }

    /**
     * One page of a channel's uploads playlist. Pass `null` for the first page, then the
     * token from the previous page. First pages return ~20 videos, continuation pages ~60.
     */
    suspend fun fetchChannelVideosPage(
        subscription: SubscriptionEntity,
        continuation: String?,
        beforeEpochMs: Long = System.currentTimeMillis()
    ): Result<ChannelVideoPage> = withContext(Dispatchers.IO) {
        val sub = normalizedChannel(subscription)
            ?: return@withContext Result.failure(
                Exception("Invalid channelId format: ${subscription.channelId}")
            )

        fetchPlaylistPage(
            sub = sub,
            browseId = if (continuation == null) {
                "VLUU" + sub.channelId.removePrefix("UC")
            } else {
                null
            },
            continuation = continuation,
            shortsOnly = false,
            beforeEpochMs = beforeEpochMs
        )
    }

    /**
     * One page of a channel's Shorts playlist. Same continuation scheme as
     * [fetchChannelVideosPage].
     */
    suspend fun fetchChannelShortsPage(
        subscription: SubscriptionEntity,
        continuation: String?,
        beforeEpochMs: Long = System.currentTimeMillis()
    ): Result<ChannelVideoPage> = withContext(Dispatchers.IO) {
        val sub = normalizedChannel(subscription)
            ?: return@withContext Result.failure(
                Exception("Invalid channelId format: ${subscription.channelId}")
            )

        fetchPlaylistPage(
            sub = sub,
            browseId = if (continuation == null) {
                "VLUUSH" + sub.channelId.removePrefix("UC")
            } else {
                null
            },
            continuation = continuation,
            shortsOnly = true,
            beforeEpochMs = beforeEpochMs
        )
    }

    private fun normalizedChannel(sub: SubscriptionEntity): SubscriptionEntity? {
        val cleanId = sub.channelId.trim()
        if (!cleanId.startsWith("UC")) return null
        return sub.copy(channelId = cleanId)
    }

    /**
     * Fetches the whole subscription set with conditional RSS requests, persisting results to
     * Room as they arrive. Channels whose XML feed is unavailable fall back to InnerTube.
     * Returns the number of entries received in this refresh (0 for a fully 304 response).
     */
    suspend fun refreshSubscriptionsFast(
        subscriptions: List<SubscriptionEntity>,
        onFirstBatchReady: (() -> Unit)? = null
    ): Result<Int> = coroutineScope {
        if (subscriptions.isEmpty()) return@coroutineScope Result.success(0)

        val dao = feedDao
        val semaphore = Semaphore(12)
        val buffer = java.util.Collections.synchronizedList(
            mutableListOf<CachedFeedVideoEntity>()
        )
        val firstFlushDone = AtomicBoolean(false)
        val receivedAny = AtomicBoolean(false)
        var lastError: String? = null

        subscriptions.map { sub ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val entries = runCatching { fetchChannelCachedEntries(sub) }
                        .onFailure { lastError = it.message }
                        .getOrNull()

                    when {
                        entries == null -> {
                            // 304 Not Modified: the existing Room rows are still current.
                            receivedAny.set(true)
                        }

                        else -> {
                            receivedAny.set(true)
                            if (entries.isNotEmpty()) {
                                buffer.addAll(entries)
                            }

                            if (dao != null) {
                                val fetchedVideoIds = entries.mapTo(HashSet()) { it.videoId }
                                if (fetchedVideoIds.isNotEmpty()) {
                                    val existingVideoIds = dao.getVideoIdsForChannel(sub.channelId)
                                    val removedVideoIds = existingVideoIds.filter { it !in fetchedVideoIds }
                                    if (removedVideoIds.isNotEmpty()) {
                                        val downloadedIds = downloadedDao?.getAllDownloadedVideoIds()?.toSet() ?: emptySet()
                                        val toDelete = removedVideoIds.filter { it !in downloadedIds }
                                        if (toDelete.isNotEmpty()) {
                                            dao.deleteVideos(toDelete)
                                        }
                                    }
                                }
                            }

                            // Flush the first batch quickly so Room can render the newest
                            // videos before slower channels have finished.
                            if (dao != null &&
                                buffer.size >= FIRST_BATCH_SIZE &&
                                firstFlushDone.compareAndSet(false, true)
                            ) {
                                val snapshot = synchronized(buffer) {
                                    buffer.sortedByDescending { it.publishedEpochMs }
                                }
                                dao.upsertAll(snapshot)
                                onFirstBatchReady?.invoke()
                            }
                        }
                    }
                }
            }
        }.awaitAll()

        if (dao != null && buffer.isNotEmpty()) {
            dao.upsertAll(buffer.toList())
            dao.trimOldEntries()
        }

        // Remove any previously cached rows that this refresh identified as upcoming
        // (for example, an RSS entry whose <published> is the creation time rather than
        // the scheduled start).
        if (dao != null) {
            val upcomingIds = detectedUpcomingIds.toList()
            if (upcomingIds.isNotEmpty()) {
                dao.deleteVideos(upcomingIds)
                detectedUpcomingIds.removeAll(upcomingIds.toHashSet())
            }
        }

        if (!receivedAny.get() && buffer.isEmpty()) {
            Result.failure(
                Exception(lastError ?: "No videos returned for ${subscriptions.size} channels.")
            )
        } else {
            Result.success(buffer.size)
        }
    }

    /**
     * Returns the channel's cached-ready entries, or null when the RSS feed was unchanged
     * (304) and the previously stored rows should be kept.
     */
    private suspend fun fetchChannelCachedEntries(
        sub: SubscriptionEntity
    ): List<CachedFeedVideoEntity>? {
        val xml = fetchChannelXmlWithEtag(sub)
        if (xml.notModified) return null

        val cleanId = sub.channelId.trim()
        val innerTubeEntries by lazy {
            if (!cleanId.startsWith("UC")) emptyList()
            else fetchFromInnerTubeBrowse(sub.copy(channelId = cleanId))
                .getOrDefault(emptyList())
                .filter { !it.isShort }
                .map { it.toCachedEntity() }
        }

        if (xml.ok && xml.entries.isNotEmpty()) {
            val needsDuration = xml.entries.any { it.durationSeconds == 0 }
            val durationMap = if (needsDuration) innerTubeEntries.associate { it.videoId to it.durationSeconds } else emptyMap()
            val enriched = xml.entries.map { entry ->
                val existingDuration = feedDao?.getDurationForVideo(entry.videoId) ?: 0
                val duration = when {
                    entry.durationSeconds > 0 -> entry.durationSeconds
                    existingDuration > 0 -> existingDuration
                    else -> durationMap[entry.videoId] ?: 0
                }
                if (duration != entry.durationSeconds) entry.copy(durationSeconds = duration) else entry
            }
            return enriched
        }

        if (xml.ok) return xml.entries

        if (!cleanId.startsWith("UC")) return emptyList()
        return innerTubeEntries
    }

    private data class XmlFetchResult(
        val entries: List<CachedFeedVideoEntity>,
        val ok: Boolean,
        val notModified: Boolean
    )

    private suspend fun fetchChannelXmlWithEtag(sub: SubscriptionEntity): XmlFetchResult {
        if (sub.channelId in channelXmlUnavailable) {
            return XmlFetchResult(emptyList(), ok = false, notModified = false)
        }

        val builder = Request.Builder()
            .url("https://www.youtube.com/feeds/videos.xml?channel_id=${sub.channelId}")
            .header("User-Agent", androidUserAgent)

        // Only send If-None-Match when Room actually stores this channel's videos. Sending
        // it after the cache was cleared would yield 304 + an empty list, permanently
        // dropping that channel's uploads from the feed.
        if (feedDao?.hasVideosForChannel(sub.channelId) == true) {
            channelEtags[sub.channelId]?.let { builder.header("If-None-Match", it) }
        }

        return runCatching {
            feedClient.newCall(builder.build()).execute().use { response ->
                if (response.code == 304) {
                    return@use XmlFetchResult(emptyList(), ok = true, notModified = true)
                }
                if (!response.isSuccessful) {
                    if (response.code == 404) {
                        channelXmlUnavailable.add(sub.channelId)
                    }
                    return@use XmlFetchResult(emptyList(), ok = false, notModified = false)
                }

                response.header("ETag")?.let { channelEtags[sub.channelId] = it }
                val xml = response.body?.string().orEmpty()
                val entries = parseXmlEntries(xml, sub)
                    .filter { !it.isShort }
                    .map { it.toCachedEntity() }
                    .filterNot { isUpcomingFast(it) }

                XmlFetchResult(entries, ok = true, notModified = false)
            }
        }.getOrElse {
            XmlFetchResult(emptyList(), ok = false, notModified = false)
        }
    }

    private suspend fun isUpcomingFast(item: CachedFeedVideoEntity): Boolean =
        isUpcomingLiveStream(
            videoId = item.videoId,
            publishedEpochMs = item.publishedEpochMs,
            rssViews = item.views
        )

    private fun fetchFromXmlFeed(sub: SubscriptionEntity): Result<List<FeedVideoItem>> = runCatching {
        val request = Request.Builder()
            .url("https://www.youtube.com/feeds/videos.xml?channel_id=${sub.channelId}")
            .header("User-Agent", androidUserAgent)
            .get()
            .build()

        val (xml, isSuccess, code) = feedClient.newCall(request).execute().use { resp ->
            Triple(resp.body?.string().orEmpty(), resp.isSuccessful, resp.code)
        }
        if (!isSuccess) {
            error("XML HTTP $code for ${sub.name}")
        }

        parseXmlEntries(xml, sub)
    }

    private fun parseXmlEntries(xml: String, sub: SubscriptionEntity): List<FeedVideoItem> =
        entryRegex.findAll(xml).mapNotNull { match ->
            val entry = match.groupValues[1]
            val linkHref = linkRegex.find(entry)?.groupValues?.getOrNull(1).orEmpty()
            if (linkHref.contains("/shorts/")) return@mapNotNull null

            val videoId = videoIdRegex.find(entry)?.groupValues?.getOrNull(1)?.trim()
                ?: return@mapNotNull null
            val rawTitle = titleRegex.find(entry)?.groupValues?.getOrNull(1).orEmpty()
            val publishedIso = publishedRegex.find(entry)?.groupValues?.getOrNull(1).orEmpty()
            val epochMs = runCatching { Instant.parse(publishedIso).toEpochMilli() }.getOrDefault(0L)
            val views = viewsRegex.find(entry)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
            val durationSeconds = durationRegex.find(entry)?.groupValues?.getOrNull(1)
                ?.toIntOrNull() ?: 0

            FeedVideoItem(
                videoId = videoId,
                title = decodeXmlEntities(rawTitle),
                channelId = sub.channelId,
                channelName = sub.name,
                publishedIso = publishedIso,
                publishedEpochMs = epochMs,
                thumbnailUrl = "https://img.youtube.com/vi/$videoId/0.jpg",
                views = views,
                durationSeconds = durationSeconds
            )
        }.toList()

    private fun fetchFromInnerTubeBrowse(sub: SubscriptionEntity): Result<List<FeedVideoItem>> =
        fetchFromInnerTubePlaylist(
            sub = sub,
            browseId = "VLUU" + sub.channelId.removePrefix("UC"),
            shortsOnly = false
        )

    private fun fetchFromInnerTubePlaylist(
        sub: SubscriptionEntity,
        browseId: String,
        shortsOnly: Boolean
    ): Result<List<FeedVideoItem>> =
        fetchPlaylistPage(
            sub = sub,
            browseId = browseId,
            continuation = null,
            shortsOnly = shortsOnly,
            beforeEpochMs = System.currentTimeMillis()
        ).map { it.items }

    /**
     * Fetches one page of a channel playlist (uploads or Shorts) and extracts the next
     * continuation token. Pass [continuation] for pages after the first.
     */
    private fun fetchPlaylistPage(
        sub: SubscriptionEntity,
        browseId: String?,
        continuation: String?,
        shortsOnly: Boolean,
        beforeEpochMs: Long
    ): Result<ChannelVideoPage> = runCatching {
        val clientObj = JSONObject().apply {
            put("clientName", "ANDROID")
            put("clientVersion", "20.10.38")
            put("androidSdkVersion", 35)
            put("hl", "en")
            put("gl", "US")
        }

        val payload = JSONObject().apply {
            if (continuation != null) {
                put("continuation", continuation)
            } else {
                put("browseId", browseId)
            }
            put("context", JSONObject().put("client", clientObj))
        }

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .header("User-Agent", androidUserAgent)
            .header("X-YouTube-Client-Name", "3")
            .header("X-YouTube-Client-Version", "20.10.38")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val (rawJson, isSuccess, code) = okHttpClient.newCall(request).execute().use { resp ->
            Triple(resp.body?.string().orEmpty(), resp.isSuccessful, resp.code)
        }
        if (!isSuccess) {
            error("Browse HTTP $code for ${sub.name}: ${rawJson.take(120)}")
        }

        val json = JSONObject(rawJson)
        ChannelVideoPage(
            items = parsePlaylistItems(json, sub, shortsOnly, beforeEpochMs),
            nextContinuation = extractContinuationToken(json)
        )
    }

    private fun parsePlaylistItems(
        json: JSONObject,
        sub: SubscriptionEntity,
        shortsOnly: Boolean,
        beforeEpochMs: Long
    ): List<FeedVideoItem> {
        val items = mutableListOf<FeedVideoItem>()
        val seen = mutableSetOf<String>()
        extractPlaylistVideosFromJson(
            node = json,
            sub = sub,
            out = items,
            seen = seen,
            shortsOnly = shortsOnly
        )

        // Playlists are ordered newest-first. Use the relative age from each renderer where
        // available and keep the timestamps strictly descending otherwise, continuing from
        // the oldest video already on screen so pages never interleave.
        var lastEpochMs = beforeEpochMs
        return items.map { item ->
            val epochMs = when {
                item.publishedEpochMs > 0L -> item.publishedEpochMs.coerceAtMost(lastEpochMs)
                else -> lastEpochMs - 60_000L
            }
            lastEpochMs = epochMs
            item.copy(publishedEpochMs = epochMs)
        }
    }

    /**
     * Continuation tokens live under `playlistVideoListRenderer.continuations[0]` on the
     * first page and `playlistVideoListContinuation.continuations[0]` on later pages.
     */
    private fun extractContinuationToken(json: JSONObject): String? =
        findContinuationInRenderer(json, "playlistVideoListRenderer")
            ?: findContinuationInRenderer(json, "playlistVideoListContinuation")

    private fun findContinuationInRenderer(node: Any?, rendererKey: String): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject(rendererKey)?.let { renderer ->
                    val token = renderer.optJSONArray("continuations")
                        ?.optJSONObject(0)
                        ?.optJSONObject("nextContinuationData")
                        ?.optString("continuation")
                        .orEmpty()
                    if (token.isNotBlank()) return token
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    findContinuationInRenderer(node.get(keys.next()), rendererKey)?.let {
                        return it
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findContinuationInRenderer(node.get(i), rendererKey)?.let { return it }
                }
            }
        }
        return null
    }

    private fun extractPlaylistVideosFromJson(
        node: Any?,
        sub: SubscriptionEntity,
        out: MutableList<FeedVideoItem>,
        seen: MutableSet<String>,
        shortsOnly: Boolean
    ) {
        when (node) {
            is JSONObject -> {
                parseVideoRenderer(node.optJSONObject("playlistVideoRenderer"), sub, out, seen, shortsOnly)
                parseVideoRenderer(node.optJSONObject("videoRenderer"), sub, out, seen, shortsOnly)
                parseVideoRenderer(node.optJSONObject("gridVideoRenderer"), sub, out, seen, shortsOnly)

                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    // Explicit Shorts renderers/endpoints are never part of the feed.
                    if (key == "reelWatchEndpoint" ||
                        key == "shortsLockupViewModel" ||
                        key == "reelItemRenderer"
                    ) {
                        continue
                    }
                    extractPlaylistVideosFromJson(node.get(key), sub, out, seen, shortsOnly)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractPlaylistVideosFromJson(node.get(i), sub, out, seen, shortsOnly)
                }
            }
        }
    }

    private fun parseVideoRenderer(
        renderer: JSONObject?,
        sub: SubscriptionEntity,
        out: MutableList<FeedVideoItem>,
        seen: MutableSet<String>,
        shortsOnly: Boolean
    ) {
        if (renderer == null) return

        val videoId = renderer.optString("videoId").trim()
        if (videoId.length != 11 || !seen.add(videoId)) return

        // Scheduled/premiere streams have not been posted yet, so they do not belong in
        // the subscriptions feed or a channel's posted videos. Remember the ID so a stale
        // row from an earlier session can be purged from Room.
        if (isUpcomingRenderer(renderer)) {
            detectedUpcomingIds.add(videoId)
            return
        }

        val lengthSeconds = renderer.optInt("lengthSeconds", 0)
            .takeIf { it > 0 }
            ?: parseLengthText(renderer.optJSONObject("lengthText"))
            ?: 0

        // YouTube Shorts are currently up to three minutes long. The uploads playlist
        // exposes exact lengths, so use those to keep Shorts out of the subscriptions tab.
        if (shortsOnly) {
            if (lengthSeconds > MAX_SHORT_DURATION_SECONDS) return
        } else if (lengthSeconds in 1..MAX_SHORT_DURATION_SECONDS) {
            return
        }

        val title = renderer.optJSONObject("title")
            ?.optJSONArray("runs")
            ?.optJSONObject(0)
            ?.optString("text")
            ?: renderer.optJSONObject("title")?.optString("simpleText")
            ?: "${sub.name} Video"

        val publishedTimeText = renderer.optJSONObject("publishedTimeText")
            ?.let { textObjectToString(it) }
            .orEmpty()
        val videoInfoText = renderer.optJSONObject("videoInfo")
            ?.let { textObjectToString(it) }
            .orEmpty()
        val relativeAge = publishedTimeText.ifBlank { videoInfoText }

        out.add(
            FeedVideoItem(
                videoId = videoId,
                title = title,
                channelId = sub.channelId,
                channelName = sub.name,
                publishedIso = "",
                publishedEpochMs = parseRelativeAgeToEpochMs(relativeAge) ?: 0L,
                thumbnailUrl = "https://img.youtube.com/vi/$videoId/0.jpg",
                isShort = shortsOnly,
                views = parseViewCountText(videoInfoText),
                durationSeconds = lengthSeconds
            )
        )
    }

    /**
     * Scheduled live streams, premieres, and other not-yet-posted videos expose either an
     * `upcomingEventData` block or an "Upcoming" time-status overlay.
     */
    private fun isUpcomingRenderer(renderer: JSONObject): Boolean {
        if (renderer.has("upcomingEventData")) return true

        val overlays = renderer.optJSONArray("thumbnailOverlays") ?: return false
        for (i in 0 until overlays.length()) {
            val status = overlays.optJSONObject(i)
                ?.optJSONObject("thumbnailOverlayTimeStatusRenderer")
                ?: continue
            if (status.optString("style").equals("UPCOMING", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     * Checks whether a video is a scheduled/upcoming live stream or premiere that has not
     * started yet. Negative ("upcoming") results expire after two minutes so a stream that
     * goes live shows up on the next refresh instead of staying hidden for the session.
     */
    private suspend fun isUpcomingLiveStream(
        videoId: String,
        publishedEpochMs: Long,
        rssViews: Long
    ): Boolean {
        val now = System.currentTimeMillis()

        // 1. A future publish timestamp is always an upcoming premiere/stream.
        if (publishedEpochMs > now + 60_000L) {
            detectedUpcomingIds.add(videoId)
            return true
        }

        // 2. Return the cached result. "Aired" verdicts are permanent; "upcoming" verdicts
        // are re-checked after the TTL so newly started streams are not hidden forever.
        upcomingStatusCache[videoId]?.let { cached ->
            if (!cached.isUpcoming || (now - cached.checkedAtMs) < UPCOMING_CACHE_TTL_MS) {
                if (cached.isUpcoming) {
                    detectedUpcomingIds.add(videoId)
                }
                return cached.isUpcoming
            }
        }

        // 3. Videos older than 14 days with views > 0 are already-aired VODs.
        val fourteenDaysMs = 14L * 24 * 60 * 60 * 1000
        if (rssViews > 0L && (now - publishedEpochMs) > fourteenDaysMs) {
            upcomingStatusCache[videoId] = UpcomingCacheEntry(
                isUpcoming = false,
                checkedAtMs = now
            )
            return false
        }

        val isUpcoming = withContext(Dispatchers.IO) { fetchVideoUpcomingStatus(videoId) }
        upcomingStatusCache[videoId] = UpcomingCacheEntry(
            isUpcoming = isUpcoming,
            checkedAtMs = System.currentTimeMillis()
        )
        if (isUpcoming) {
            detectedUpcomingIds.add(videoId)
        }
        return isUpcoming
    }

    /**
     * Asks the lightweight InnerTube player endpoint whether a video is scheduled. The
     * oEmbed endpoint cannot be used here: it returns 200 for unstarted premieres. The
     * player response is ~8 KB versus a ~150 KB watch page and exposes
     * `videoDetails.isUpcoming` / `LIVE_STREAM_OFFLINE`.
     */
    private fun fetchVideoUpcomingStatus(videoId: String): Boolean = runCatching {
        val clientObj = JSONObject().apply {
            put("clientName", "ANDROID")
            put("clientVersion", "20.10.38")
            put("androidSdkVersion", 35)
            put("hl", "en")
            put("gl", "US")
        }

        val payload = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().put("client", clientObj))
        }

        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", androidUserAgent)
            .header("X-YouTube-Client-Name", "3")
            .header("X-YouTube-Client-Version", "20.10.38")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        feedClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use false

            val body = runCatching {
                JSONObject(resp.body?.string().orEmpty())
            }.getOrNull() ?: return@use false

            val isUpcoming = body.optJSONObject("videoDetails")
                ?.optBoolean("isUpcoming", false) == true
            val status = body.optJSONObject("playabilityStatus")
                ?.optString("status")
                .orEmpty()

            isUpcoming || status == "LIVE_STREAM_OFFLINE"
        }
    }.getOrDefault(false)

    private fun parseViewCountText(raw: String): Long {
        if (raw.isBlank()) return -1L
        val match = viewCountRegex.find(raw) ?: return -1L
        val number = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return -1L
        val multiplier = when (match.groupValues[2].uppercase()) {
            "K" -> 1_000L
            "M" -> 1_000_000L
            "B" -> 1_000_000_000L
            else -> 1L
        }
        return (number * multiplier).toLong()
    }

    private fun textObjectToString(textObject: JSONObject): String {
        textObject.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        val runs = textObject.optJSONArray("runs") ?: return ""
        return buildList {
            for (i in 0 until runs.length()) {
                runs.optJSONObject(i)?.optString("text")?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }.joinToString(" ")
    }

    private fun parseLengthText(lengthText: JSONObject?): Int? {
        if (lengthText == null) return null
        val raw = lengthText.optString("simpleText")
            .takeIf { it.isNotBlank() }
            ?: lengthText.optJSONArray("runs")
                ?.optJSONObject(0)
                ?.optString("text")
                .orEmpty()
        return parseDurationSeconds(raw)
    }

    private fun parseDurationSeconds(raw: String): Int? {
        if (raw.isBlank()) return null
        val parts = raw.split(":").map { it.toIntOrNull() ?: return@parseDurationSeconds null }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            1 -> parts[0]
            else -> null
        }
    }

    private fun parseRelativeAgeToEpochMs(raw: String): Long? {
        if (raw.isBlank()) return null
        val match = relativeAgeRegex.find(raw) ?: return null
        val value = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()

        val millis = when {
            unit.startsWith("second") -> value * 1_000L
            unit.startsWith("minute") -> value * 60_000L
            unit.startsWith("hour") -> value * 3_600_000L
            unit.startsWith("day") -> value * 86_400_000L
            unit.startsWith("week") -> value * 604_800_000L
            unit.startsWith("month") -> value * 2_592_000_000L
            unit.startsWith("year") -> value * 31_536_000_000L
            else -> return null
        }
        return System.currentTimeMillis() - millis
    }

    private fun decodeXmlEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
}

/** Formats a video length as `m:ss` or `h:mm:ss`. Returns "" for unknown lengths. */
fun formatDurationSeconds(totalSeconds: Int): String {
    if (totalSeconds <= 0) return ""
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

fun formatRelativeTime(publishedEpochMs: Long): String {
    if (publishedEpochMs <= 0L) return ""
    val diff = Duration.between(Instant.ofEpochMilli(publishedEpochMs), Instant.now())
    val minutes = diff.toMinutes()
    return when {
        minutes < 60 -> "${minutes.coerceAtLeast(1)}m ago"
        minutes < 60 * 24 -> "${diff.toHours()}h ago"
        minutes < 60 * 24 * 30 -> "${diff.toDays()}d ago"
        else -> "${diff.toDays() / 30}mo ago"
    }
}
