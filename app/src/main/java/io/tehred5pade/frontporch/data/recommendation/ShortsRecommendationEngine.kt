package io.tehred5pade.frontporch.data.recommendation

import io.tehred5pade.frontporch.data.db.SubscriptionDao
import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import io.tehred5pade.frontporch.data.innertube.ShortsFeedSource
import io.tehred5pade.frontporch.data.local.AffinityScoreEntity
import io.tehred5pade.frontporch.data.local.RecommendationDao
import io.tehred5pade.frontporch.data.local.SeenShortEntity
import io.tehred5pade.frontporch.data.local.ShortFeedbackEntity
import io.tehred5pade.frontporch.data.model.ShortsCategoryItem
import io.tehred5pade.frontporch.data.prefs.UserSettings
import io.tehred5pade.frontporch.data.search.YouTubeSearchRepository
import io.tehred5pade.frontporch.data.util.ShortsLanguageFilter
import io.tehred5pade.frontporch.data.util.SlopAndClickbaitFilter
import io.tehred5pade.frontporch.ui.models.ShortItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.random.Random

/**
 * Local, Room-backed recommendation engine for the Shorts feed.
 *
 * Candidate sources, in priority order:
 *  1. A weighted random sample of subscribed channels (cold-start + core source).
 *  2. Discovered channels the user is *not* subscribed to. Their IDs are learned from related
 *     reels of liked / fully-watched Shorts and from related reels of fresh subscription picks,
 *     then sampled and fetched from their own Shorts tabs with the same algorithm as (1).
 *  3. Related reel chains from Shorts the user liked or watched to completion.
 *  4. A targeted topic search only when the first three leave fewer than [MIN_UNSEEN_POOL]
 *     unseen candidates.
 *
 * Every candidate is filtered for valid short-form duration, English-only (when requested),
 * placeholder creators, slop/clickbait, muted channels and the seen history before ranking.
 * Ranking is `channelScore + sum(keywordScores) + jitter`, then a creator-bucketed rotation
 * prevents two items from the same creator appearing back-to-back.
 */
class ShortsRecommendationEngine(
    private val recommendationDao: RecommendationDao,
    private val subscriptionDao: SubscriptionDao,
    private val shortsService: ShortsFeedSource,
    private val searchRepository: YouTubeSearchRepository? = null
) {
    companion object {
        const val CHANNEL_LIKE_DELTA = 5.0
        const val CHANNEL_DISLIKE_DELTA = -10.0
        const val KEYWORD_LIKE_DELTA = 2.0
        const val KEYWORD_DISLIKE_DELTA = -3.0
        const val IMPLICIT_COMPLETE_CHANNEL_DELTA = 2.0
        const val IMPLICIT_SWIPE_CHANNEL_DELTA = -1.5
        const val IMPLICIT_SWIPE_KEYWORD_DELTA = -0.5
        const val SUBSCRIPTION_BASELINE = 4.0

        /** Small positive prior for channels found outside the subscription list. */
        const val DISCOVERY_BASELINE = 2.0

        const val CHANNEL_MUTE_SCORE = -15.0
        const val CHANNEL_MUTE_DISLIKES = 2
        const val WATCH_COMPLETE_PERCENT = 85.0
        const val QUICK_SWIPE_MS = 2_500L

        const val MIN_UNSEEN_POOL = 15
        const val MAX_RELATED_SEEDS = 12

        private const val TYPE_CHANNEL = "CHANNEL"
        private const val TYPE_TAG = "TAG"
        private const val FEEDBACK_LIKE = "LIKE"
        private const val FEEDBACK_DISLIKE = "DISLIKE"

        private const val CHANNEL_PREFIX = "channel:"
        private const val TAG_PREFIX = "tag:"

        private const val JITTER_RANGE = 1.0
        private const val CHANNEL_SAMPLE_SIZE = 12
        private const val CHANNEL_PASSES_PER_FETCH = 4

        /** Reserve part of every channel batch for unsubscribed creators once discovered. */
        private const val DISCOVERY_SAMPLE_QUOTA = 4
        private const val MAX_DISCOVERED_CHANNELS = 200
        private const val MIN_DISCOVERY_CHANNELS = 8

        /** Related reels of fresh picks teach us new channels even before any Like/watch. */
        private const val DISCOVERY_SEEDS_PER_FETCH = 3
        private const val MAX_DISCOVERY_SEEDS = 300

        private const val RELATED_EXPANSIONS_PER_FETCH = 3
        private const val TOPIC_QUERY_LIMIT = 2
        private const val SAMPLE_WEIGHT_FLOOR = 6.0
        private const val MIN_SAMPLE_WEIGHT = 0.25
        private const val SEEN_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

        /** Cap how many queued Shorts can come from one creator so the pool stays diverse. */
        private const val MAX_POOL_ITEMS_PER_CHANNEL = 8

        /** Channels that returned no Shorts are skipped for a while instead of eating samples. */
        private const val DRY_CHANNEL_RETRY_MS = 15L * 60 * 1000

        private val STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "with", "by", "is", "of", "this", "that", "it", "from", "as", "be",
            "are", "was", "were", "shorts", "short", "video", "youtube", "clip",
            "new", "best", "how", "why", "what", "when", "you", "your", "his", "her"
        )
    }

    private data class ScoredShort(val short: ShortItem, val score: Double)

    /** A channel eligible for sampling, whether or not the user subscribed to it. */
    private data class CandidateChannel(
        val channelId: String,
        val name: String,
        val subscribed: Boolean
    )

    private val poolMutex = Mutex()
    private val affinityMutex = Mutex()
    private val random = Random.Default

    /** Fetched but not yet handed to the pager. Guarded by [poolMutex]. */
    private val candidatePool = mutableListOf<ShortItem>()

    /** Channels already given their cold-start baseline in this process. Guarded by [affinityMutex]. */
    private val seededChannels = HashSet<String>()

    /**
     * Rotation state for channel sampling. Guarded by [poolMutex]:
     * - [recentlySampledChannels] prevents the same high-affinity creators from being picked
     *   on every pass while other subscriptions never get a turn.
     * - [dryChannelsUntil] skips channels that just returned zero Shorts (many subscriptions
     *   have no Shorts tab), which otherwise waste most of a sample and leave only 1-2 creators.
     */
    private val recentlySampledChannels = ArrayDeque<String>()
    private val recentlySampledSet = HashSet<String>()
    private val dryChannelsUntil = HashMap<String, Long>()

    /** Video IDs queued for related-item expansion. Guarded by synchronized blocks. */
    private val relatedSeeds = ArrayDeque<String>()
    private val expandedSeeds = HashSet<String>()

    /**
     * Unsubscribed channel IDs learned from related reels, guarded by [poolMutex]. Combined
     * with non-muted channel affinity rows this survives process restarts for channels the
     * user has given feedback to.
     */
    private val discoveredChannels = LinkedHashMap<String, String>()
    private val expandedDiscoverySeeds = HashSet<String>()

    // ---------------------------------------------------------------------------------------
    // Cold start
    // ---------------------------------------------------------------------------------------

    /** Gives every subscribed channel the +4.0 baseline exactly once. */
    suspend fun seedSubscriptions() = withContext(Dispatchers.IO) {
        affinityMutex.withLock { seedSubscriptionsLocked() }
    }

    private suspend fun seedSubscriptionsLocked() {
        val subscriptions = runCatching { subscriptionDao.getAllOnce() }.getOrDefault(emptyList())
        if (subscriptions.isEmpty()) return

        val now = System.currentTimeMillis()
        for (sub in subscriptions) {
            if (sub.channelId.isBlank()) continue
            if (sub.channelId in seededChannels) continue
            val key = channelKey(sub.channelId)
            if (recommendationDao.getAffinity(key) == null) {
                recommendationDao.upsertAffinity(
                    AffinityScoreEntity(
                        affinityKey = key,
                        type = TYPE_CHANNEL,
                        label = sub.name,
                        score = SUBSCRIPTION_BASELINE,
                        dislikeCount = 0,
                        muted = false,
                        lastUpdated = now
                    )
                )
            }
            seededChannels.add(sub.channelId)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Explicit feedback
    // ---------------------------------------------------------------------------------------

    suspend fun setLike(short: ShortItem) = setExplicitFeedback(short, FEEDBACK_LIKE)

    suspend fun clearLike(short: ShortItem) = clearExplicitFeedback(short, FEEDBACK_LIKE)

    suspend fun setDislike(short: ShortItem) = setExplicitFeedback(short, FEEDBACK_DISLIKE)

    suspend fun clearDislike(short: ShortItem) = clearExplicitFeedback(short, FEEDBACK_DISLIKE)

    private suspend fun setExplicitFeedback(short: ShortItem, feedbackType: String) =
        withContext(Dispatchers.IO) {
            val existing = recommendationDao.getFeedback(short.id)
            if (existing != null) {
                if (existing.feedbackType == feedbackType) return@withContext
                reverseFeedback(existing)
                recommendationDao.deleteFeedback(short.id)
            }

            recommendationDao.insertFeedback(
                ShortFeedbackEntity(
                    videoId = short.id,
                    channelId = short.channelId,
                    channelName = short.channelTitle,
                    title = short.title,
                    tags = extractTags(short.title).joinToString(","),
                    feedbackType = feedbackType,
                    timestamp = System.currentTimeMillis()
                )
            )
            recordSeenInternal(short, completionPercent = 0.0)
            applyFeedbackDelta(
                short = short,
                feedbackType = feedbackType,
                tags = extractTags(short.title),
                reverse = false,
                explicit = true
            )
            if (feedbackType == FEEDBACK_LIKE) enqueueRelatedSeed(short.id)
        }

    private suspend fun clearExplicitFeedback(short: ShortItem, feedbackType: String) =
        withContext(Dispatchers.IO) {
            val existing = recommendationDao.getFeedback(short.id)
            if (existing == null || existing.feedbackType != feedbackType) return@withContext
            reverseFeedback(existing)
            recommendationDao.deleteFeedback(short.id)
        }

    private suspend fun reverseFeedback(feedback: ShortFeedbackEntity) {
        val short = ShortItem(
            id = feedback.videoId,
            title = feedback.title,
            channelTitle = feedback.channelName,
            channelId = feedback.channelId
        )
        val tags = feedback.tags.split(",").filter { it.isNotBlank() }
        applyFeedbackDelta(
            short = short,
            feedbackType = feedback.feedbackType,
            tags = tags,
            reverse = true,
            explicit = true
        )
    }

    private suspend fun applyFeedbackDelta(
        short: ShortItem,
        feedbackType: String,
        tags: List<String>,
        reverse: Boolean,
        explicit: Boolean
    ) {
        affinityMutex.withLock {
            val now = System.currentTimeMillis()
            val sign = if (reverse) -1.0 else 1.0

            if (short.channelId.isNotBlank()) {
                val channelDelta = if (feedbackType == FEEDBACK_LIKE) CHANNEL_LIKE_DELTA else CHANNEL_DISLIKE_DELTA
                val dislikeDelta = when {
                    !explicit -> 0
                    feedbackType != FEEDBACK_DISLIKE -> 0
                    reverse -> -1
                    else -> 1
                }
                updateAffinity(
                    key = channelKey(short.channelId),
                    type = TYPE_CHANNEL,
                    label = short.channelTitle,
                    delta = channelDelta * sign,
                    dislikeDelta = dislikeDelta,
                    now = now
                )
            }

            val keywordDelta = if (feedbackType == FEEDBACK_LIKE) KEYWORD_LIKE_DELTA else KEYWORD_DISLIKE_DELTA
            for (tag in tags.distinct()) {
                updateAffinity(
                    key = tagKey(tag),
                    type = TYPE_TAG,
                    label = tag,
                    delta = keywordDelta * sign,
                    dislikeDelta = 0,
                    now = now
                )
            }
        }
    }

    private suspend fun updateAffinity(
        key: String,
        type: String,
        label: String,
        delta: Double,
        dislikeDelta: Int,
        now: Long
    ) {
        val existing = recommendationDao.getAffinity(key)
        val newScore = (existing?.score ?: 0.0) + delta
        val newDislikeCount = ((existing?.dislikeCount ?: 0) + dislikeDelta).coerceAtLeast(0)
        val muted = type == TYPE_CHANNEL &&
            (newDislikeCount >= CHANNEL_MUTE_DISLIKES || newScore <= CHANNEL_MUTE_SCORE)

        recommendationDao.upsertAffinity(
            AffinityScoreEntity(
                affinityKey = key,
                type = type,
                label = label.ifBlank { existing?.label.orEmpty() },
                score = newScore,
                dislikeCount = newDislikeCount,
                muted = muted,
                lastUpdated = now
            )
        )
    }

    // ---------------------------------------------------------------------------------------
    // Implicit feedback
    // ---------------------------------------------------------------------------------------

    /** Marks a Short as shown so its videoId can never be recommended again. */
    suspend fun recordSeen(short: ShortItem, completionPercent: Double = 0.0) =
        withContext(Dispatchers.IO) {
            recordSeenInternal(short, completionPercent)
        }

    /**
     * Applies the one-shot implicit watch signal:
     *  - >= 85% watched: +2.0 channel and queue the video for related-item chaining.
     *  - swiped away in < 2.5s: -1.5 channel, -0.5 per keyword.
     * Explicit dislikes win and suppress the positive signal.
     */
    suspend fun recordWatch(short: ShortItem, completionPercent: Double, watchedMs: Long) =
        withContext(Dispatchers.IO) {
            val existing = recommendationDao.getSeen(short.id)
            val completion = maxOf(existing?.completionPercent ?: 0.0, completionPercent)
            if (existing?.implicitApplied == true) {
                recordSeenInternal(short, completion)
                return@withContext
            }

            val explicitlyDisliked =
                recommendationDao.getFeedback(short.id)?.feedbackType == FEEDBACK_DISLIKE

            when {
                explicitlyDisliked -> Unit
                completionPercent >= WATCH_COMPLETE_PERCENT -> {
                    applyImplicitDelta(
                        short,
                        channelDelta = IMPLICIT_COMPLETE_CHANNEL_DELTA,
                        keywordDelta = 0.0
                    )
                    enqueueRelatedSeed(short.id)
                }
                watchedMs in 1L until QUICK_SWIPE_MS -> {
                    applyImplicitDelta(
                        short,
                        channelDelta = IMPLICIT_SWIPE_CHANNEL_DELTA,
                        keywordDelta = IMPLICIT_SWIPE_KEYWORD_DELTA
                    )
                }
            }

            // Neutral watches still mark the signal consumed so the same video cannot be
            // penalized/rewarded twice if it is re-opened from search.
            recommendationDao.insertSeen(
                SeenShortEntity(
                    videoId = short.id,
                    timestamp = System.currentTimeMillis(),
                    completionPercent = completion,
                    implicitApplied = true
                )
            )
            recommendationDao.pruneSeenOlderThan(System.currentTimeMillis() - SEEN_RETENTION_MS)
        }

    private suspend fun applyImplicitDelta(
        short: ShortItem,
        channelDelta: Double,
        keywordDelta: Double
    ) {
        affinityMutex.withLock {
            val now = System.currentTimeMillis()
            if (short.channelId.isNotBlank() && channelDelta != 0.0) {
                updateAffinity(
                    key = channelKey(short.channelId),
                    type = TYPE_CHANNEL,
                    label = short.channelTitle,
                    delta = channelDelta,
                    dislikeDelta = 0,
                    now = now
                )
            }
            if (keywordDelta != 0.0) {
                for (tag in extractTags(short.title)) {
                    updateAffinity(
                        key = tagKey(tag),
                        type = TYPE_TAG,
                        label = tag,
                        delta = keywordDelta,
                        dislikeDelta = 0,
                        now = now
                    )
                }
            }
        }
    }

    private suspend fun recordSeenInternal(short: ShortItem, completionPercent: Double) {
        if (short.id.isBlank()) return
        val existing = recommendationDao.getSeen(short.id)
        val completion = maxOf(existing?.completionPercent ?: 0.0, completionPercent)
        recommendationDao.insertSeen(
            SeenShortEntity(
                videoId = short.id,
                timestamp = System.currentTimeMillis(),
                completionPercent = completion,
                implicitApplied = existing?.implicitApplied == true
            )
        )
        recommendationDao.pruneSeenOlderThan(System.currentTimeMillis() - SEEN_RETENTION_MS)
    }

    // ---------------------------------------------------------------------------------------
    // Related-item chaining
    // ---------------------------------------------------------------------------------------

    /**
     * Expands a liked / fully-watched Short into YouTube's related reel sequence and feeds the
     * results straight into the candidate pool.
     */
    suspend fun seedRelated(
        short: ShortItem,
        settings: UserSettings,
        visitorData: String?
    ) = withContext(Dispatchers.IO) {
        if (short.id.isBlank()) return@withContext
        synchronized(relatedSeeds) { relatedSeeds.remove(short.id) }
        val related = expandRelatedSeed(short.id, settings, visitorData)
        if (related.isEmpty()) return@withContext
        poolMutex.withLock {
            addToPool(
                items = related,
                settings = settings,
                mutedChannels = mutedChannelIds(),
                seenIds = recommendationDao.getAllSeenIds().toHashSet()
            )
            rememberDiscoveredChannels(related)
        }
    }

    private suspend fun expandRelatedSeed(
        seedVideoId: String,
        settings: UserSettings,
        visitorData: String?
    ): List<ShortItem> {
        val shouldExpand = synchronized(expandedSeeds) { expandedSeeds.add(seedVideoId) }
        if (!shouldExpand) return emptyList()

        return runCatching {
            shortsService.fetchReelSequence(
                seedVideoId = seedVideoId,
                continuation = null,
                visitorData = visitorData,
                preferredAudioLanguage = settings.preferredAudioLanguage
            ).first
        }.getOrDefault(emptyList())
    }

    private fun enqueueRelatedSeed(videoId: String) {
        if (videoId.isBlank()) return
        synchronized(relatedSeeds) {
            if (videoId in expandedSeeds) return
            relatedSeeds.remove(videoId)
            relatedSeeds.addLast(videoId)
            while (relatedSeeds.size > MAX_RELATED_SEEDS) relatedSeeds.removeFirst()
        }
    }

    private fun takeRelatedSeeds(limit: Int): List<String> {
        val out = ArrayList<String>(limit)
        synchronized(relatedSeeds) {
            while (out.size < limit && relatedSeeds.isNotEmpty()) {
                val id = relatedSeeds.removeFirst()
                if (expandedSeeds.add(id)) out.add(id)
            }
        }
        return out
    }

    // ---------------------------------------------------------------------------------------
    // Candidate fetching + ranking
    // ---------------------------------------------------------------------------------------

    /**
     * Returns up to [count] unseen, scored Shorts ordered for playback. Fetches from the
     * configured sources only when the local pool is thin, then removes returned items from
     * the internal pool.
     */
    suspend fun fetchNextShorts(
        existingIds: Set<String>,
        lastChannelId: String?,
        count: Int,
        settings: UserSettings,
        visitorData: String?
    ): List<ShortItem> = withContext(Dispatchers.IO) {
        if (count <= 0) return@withContext emptyList()

        poolMutex.withLock {
            seedSubscriptions()
            val mutedChannels = mutedChannelIds()
            val seenIds = recommendationDao.getAllSeenIds().toHashSet()

            prunePool(existingIds, seenIds, settings, mutedChannels)

            if (candidatePool.size < count || candidatePool.size < MIN_UNSEEN_POOL) {
                gatherCandidates(
                    settings = settings,
                    visitorData = visitorData,
                    mutedChannels = mutedChannels,
                    seenIds = seenIds,
                    target = maxOf(count, MIN_UNSEEN_POOL)
                )
            }

            // Topic fallback is deliberately last: only when discovered/subscribed channels
            // and related chains could not keep at least 15 unseen Shorts ready.
            if (candidatePool.size < MIN_UNSEEN_POOL) {
                val fallbackItems = fetchTopicFallback(settings, visitorData)
                addToPool(
                    items = fallbackItems,
                    settings = settings,
                    mutedChannels = mutedChannels,
                    seenIds = seenIds
                )
                rememberDiscoveredChannels(fallbackItems)
            }

            val affinities = loadAffinityMap()
            val scored = candidatePool.asSequence()
                .filterNot { it.id in existingIds || it.id in seenIds }
                .filterNot { isExcluded(it, settings, mutedChannels) }
                .map { ScoredShort(it, scoreShort(it, affinities)) }
                .toList()

            val ordered = orderWithCreatorSpacing(scored, lastChannelId).take(count)
            if (ordered.isNotEmpty()) {
                val handedOut = ordered.mapTo(HashSet()) { it.id }
                candidatePool.removeAll { it.id in handedOut }
            }
            ordered
        }
    }

    /**
     * Re-scores an already-fetched list (the unshown tail after the current page) without
     * fetching. Used immediately after Like/Dislike.
     */
    suspend fun rankUnshown(
        items: List<ShortItem>,
        settings: UserSettings,
        firstChannelId: String?
    ): List<ShortItem> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()

        val seenIds = recommendationDao.getAllSeenIds().toHashSet()
        val mutedChannels = mutedChannelIds()
        val affinities = loadAffinityMap()
        val filtered = items.filterNot {
            it.id in seenIds ||
                isExcluded(it, settings, mutedChannels, enforceSourceFilters = false)
        }
        orderWithCreatorSpacing(
            filtered.map { ScoredShort(it, scoreShort(it, affinities)) },
            firstChannelId
        )
    }

    private suspend fun gatherCandidates(
        settings: UserSettings,
        visitorData: String?,
        mutedChannels: Set<String>,
        seenIds: Set<String>,
        target: Int
    ) {
        // 1. Related chains from recent Like / 85%+ watch signals.
        val seeds = takeRelatedSeeds(RELATED_EXPANSIONS_PER_FETCH)
        if (seeds.isNotEmpty()) {
            val related = coroutineScope {
                seeds.map { seed ->
                    async { expandRelatedSeed(seed, settings, visitorData) }
                }.awaitAll().flatten()
            }
            addToPool(related, settings, mutedChannels, seenIds)
            rememberDiscoveredChannels(related)
        }

        val subscriptions = runCatching { subscriptionDao.getAllOnce() }
            .getOrDefault(emptyList())
        val subscribedIds = subscriptions.mapTo(HashSet()) { it.channelId }
        val subscribedChannels = subscriptions.map {
            CandidateChannel(it.channelId, it.name, subscribed = true)
        }
        val blockedChannels = mutedChannels + settings.blockedShortChannelIds

        // 2. Weighted sampling of subscribed AND discovered channels. A discovered channel is
        //    fetched from its own Shorts tab with the same rotation, dry-channel skip and
        //    per-creator pool cap as a subscription.
        var pass = 0
        while (candidatePool.size < target && pass < CHANNEL_PASSES_PER_FETCH) {
            val affinities = loadAffinityMap()
            val discoveredChannelsNow = discoveredChannelCandidates(subscribedIds, affinities)
            val sample = weightedChannelSample(
                subscribed = subscribedChannels,
                discovered = discoveredChannelsNow,
                blockedChannels = blockedChannels,
                limit = CHANNEL_SAMPLE_SIZE,
                affinities = affinities
            )
            if (sample.isEmpty()) break

            val fetchedPerChannel = coroutineScope {
                sample.map { channel ->
                    async {
                        channel to runCatching {
                            shortsService.fetchChannelShorts(channel.channelId, channel.name)
                                .map { it.item }
                        }
                    }
                }.awaitAll()
            }

            val now = System.currentTimeMillis()
            val channelShorts = ArrayList<ShortItem>()
            for ((channel, result) in fetchedPerChannel) {
                markChannelSampled(channel.channelId)
                val items = result.getOrNull()
                if (items == null) continue // network failure: retry later, do not mark dry
                if (items.isEmpty()) {
                    dryChannelsUntil[channel.channelId] = now + DRY_CHANNEL_RETRY_MS
                } else {
                    channelShorts.addAll(items)
                }
            }
            addToPool(channelShorts, settings, mutedChannels, seenIds)

            // 3. Mine a few fresh Shorts for related creators. This teaches the feed about
            //    unsubscribed channels even before the user has liked or finished one, and
            //    never falls back to a generic keyword search.
            if (discoveredChannelsNow.size < MIN_DISCOVERY_CHANNELS) {
                val discoverySeeds = channelShorts
                    .map { it.id }
                    .filter { id ->
                        id.isNotBlank() && expandedDiscoverySeeds.size < MAX_DISCOVERY_SEEDS &&
                            expandedDiscoverySeeds.add(id)
                    }
                    .take(DISCOVERY_SEEDS_PER_FETCH)
                if (discoverySeeds.isNotEmpty()) {
                    val related = coroutineScope {
                        discoverySeeds.map { seed ->
                            async { expandRelatedSeedForDiscovery(seed, settings, visitorData) }
                        }.awaitAll().flatten()
                    }
                    addToPool(related, settings, mutedChannels, seenIds)
                    rememberDiscoveredChannels(related)
                }
            }
            pass++
        }
    }

    private suspend fun fetchTopicFallback(
        settings: UserSettings,
        visitorData: String?
    ): List<ShortItem> = coroutineScope {
        val queries = categorySeedQueries(settings)
        if (queries.isEmpty()) return@coroutineScope emptyList()

        queries.shuffled(random).take(TOPIC_QUERY_LIMIT).map { query ->
            async {
                runCatching {
                    shortsService.searchShortsByQuery(
                        query = query,
                        visitorData = visitorData,
                        paramsOverride = null,
                        preferredAudioLanguage = settings.preferredAudioLanguage
                    ).getOrDefault(emptyList())
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    /**
     * Same weighted, LRU-rotated sampling for subscriptions and discovered channels. A slice
     * of each batch is reserved for discovered creators so unsubscribed channels actually get
     * playback time instead of only ever appearing as one-off related clips.
     */
    private fun weightedChannelSample(
        subscribed: List<CandidateChannel>,
        discovered: List<CandidateChannel>,
        blockedChannels: Set<String>,
        limit: Int,
        affinities: Map<String, AffinityScoreEntity>
    ): List<CandidateChannel> {
        if (limit <= 0) return emptyList()
        val now = System.currentTimeMillis()
        fun available(channels: List<CandidateChannel>) = channels.filter {
            it.channelId.isNotBlank() &&
                it.channelId !in blockedChannels &&
                (dryChannelsUntil[it.channelId] ?: 0L) <= now
        }

        val subscribedAvailable = available(subscribed)
        val discoveredAvailable = available(discovered)
        val allAvailable = subscribedAvailable + discoveredAvailable

        // Prefer channels that have not had a turn recently. Once every channel has been
        // sampled, clear the rotation so the next pass starts from a fresh weighted draw.
        if (allAvailable.isNotEmpty() && allAvailable.all { it.channelId in recentlySampledSet }) {
            recentlySampledSet.clear()
            recentlySampledChannels.clear()
        }
        val subscribedPool = subscribedAvailable.filterNot { it.channelId in recentlySampledSet }
        val discoveredPool = discoveredAvailable.filterNot { it.channelId in recentlySampledSet }

        val picked = ArrayList<CandidateChannel>(minOf(limit, allAvailable.size))
        val discoveryQuota = if (discoveredPool.isEmpty()) {
            0
        } else {
            minOf(DISCOVERY_SAMPLE_QUOTA, limit)
        }
        picked.addAll(weightedPick(discoveredPool, discoveryQuota, affinities))
        picked.addAll(weightedPick(subscribedPool, limit - picked.size, affinities))

        // If one side ran dry, let the other fill the batch instead of under-fetching.
        if (picked.size < limit) {
            val pickedIds = picked.mapTo(HashSet()) { it.channelId }
            val leftovers = (discoveredPool + subscribedPool).filterNot { it.channelId in pickedIds }
            picked.addAll(weightedPick(leftovers, limit - picked.size, affinities))
        }
        return picked.shuffled(random)
    }

    private fun weightedPick(
        candidates: List<CandidateChannel>,
        limit: Int,
        affinities: Map<String, AffinityScoreEntity>
    ): List<CandidateChannel> {
        if (limit <= 0 || candidates.isEmpty()) return emptyList()
        val remaining = candidates.toMutableList()
        val picked = ArrayList<CandidateChannel>(minOf(limit, remaining.size))
        while (picked.size < limit && remaining.isNotEmpty()) {
            val weights = remaining.map { channel ->
                val baseline = if (channel.subscribed) SUBSCRIPTION_BASELINE else DISCOVERY_BASELINE
                val score = affinities[channelKey(channel.channelId)]?.score ?: baseline
                (score + SAMPLE_WEIGHT_FLOOR).coerceAtLeast(MIN_SAMPLE_WEIGHT)
            }
            val totalWeight = weights.sum()
            var roll = random.nextDouble() * totalWeight
            var index = remaining.lastIndex
            for (i in remaining.indices) {
                roll -= weights[i]
                if (roll <= 0.0) {
                    index = i
                    break
                }
            }
            picked.add(remaining.removeAt(index))
        }
        return picked
    }

    /** Remembers creator IDs from related Shorts so their channels can be sampled directly. */
    private suspend fun rememberDiscoveredChannels(items: List<ShortItem>) {
        if (items.isEmpty()) return
        val subscribedIds = runCatching { subscriptionDao.getAllOnce() }
            .getOrDefault(emptyList()).mapTo(HashSet()) { it.channelId }
        for (item in items) {
            val id = item.channelId
            if (!id.startsWith("UC") || id in subscribedIds) continue
            val name = item.channelTitle.trim()
            if (name.isBlank() || isPlaceholderCreator(name)) continue
            if (discoveredChannels.containsKey(id)) continue
            discoveredChannels[id] = name
            while (discoveredChannels.size > MAX_DISCOVERED_CHANNELS) {
                discoveredChannels.remove(discoveredChannels.keys.first())
            }
        }
    }

    /**
     * Persisted discovery memory from affinity rows (past likes/dislikes) plus channels found
     * this session. Channels the user has actively disliked are excluded from discovery.
     */
    private fun discoveredChannelCandidates(
        subscribedIds: Set<String>,
        affinities: Map<String, AffinityScoreEntity>
    ): List<CandidateChannel> {
        val found = LinkedHashMap<String, String>()
        for (affinity in affinities.values) {
            if (affinity.type != TYPE_CHANNEL || affinity.muted || affinity.score < 0.0) continue
            val id = affinity.affinityKey.removePrefix(CHANNEL_PREFIX)
            if (!id.startsWith("UC") || id in subscribedIds) continue
            val label = affinity.label.trim()
            if (label.isNotBlank() && !isPlaceholderCreator(label)) found[id] = label
        }
        for ((id, name) in discoveredChannels) {
            if (!id.startsWith("UC") || id in subscribedIds) continue
            found[id] = name
        }
        return found.map { CandidateChannel(it.key, it.value, subscribed = false) }
    }

    /** Expands a fresh pick for channel discovery only; feedback seeds stay in [expandedSeeds]. */
    private suspend fun expandRelatedSeedForDiscovery(
        seedVideoId: String,
        settings: UserSettings,
        visitorData: String?
    ): List<ShortItem> = runCatching {
        shortsService.fetchReelSequence(
            seedVideoId = seedVideoId,
            continuation = null,
            visitorData = visitorData,
            preferredAudioLanguage = settings.preferredAudioLanguage
        ).first
    }.getOrDefault(emptyList())

    private fun markChannelSampled(channelId: String) {
        if (channelId.isBlank()) return
        if (recentlySampledSet.add(channelId)) {
            recentlySampledChannels.addLast(channelId)
            // Keep the LRU bounded even for users with hundreds of subscriptions.
            while (recentlySampledChannels.size > 512) {
                recentlySampledSet.remove(recentlySampledChannels.removeFirst())
            }
        }
    }

    private fun prunePool(
        existingIds: Set<String>,
        seenIds: Set<String>,
        settings: UserSettings,
        mutedChannels: Set<String>
    ) {
        candidatePool.removeAll { item ->
            item.id in existingIds ||
                item.id in seenIds ||
                isExcluded(item, settings, mutedChannels)
        }
    }

    private fun addToPool(
        items: List<ShortItem>,
        settings: UserSettings,
        mutedChannels: Set<String>,
        seenIds: Set<String>
    ) {
        if (items.isEmpty()) return
        val known = candidatePool.mapTo(HashSet()) { it.id }
        val perChannelCounts = candidatePool
            .filter { it.channelId.isNotBlank() }
            .groupingBy { it.channelId }
            .eachCount()
            .toMutableMap()

        for (item in items) {
            if (!known.add(item.id)) continue
            if (item.id in seenIds) continue
            if (isExcluded(item, settings, mutedChannels)) continue

            // Cap how many queued Shorts can come from one creator. Without this, a channel
            // that publishes a lot of Shorts fills the whole buffer and the feed looks like
            // it only has one or two creators.
            if (item.channelId.isNotBlank()) {
                val existing = perChannelCounts[item.channelId] ?: 0
                if (existing >= MAX_POOL_ITEMS_PER_CHANNEL) continue
                perChannelCounts[item.channelId] = existing + 1
            }
            candidatePool.add(item)
        }
    }

    private fun isExcluded(
        item: ShortItem,
        settings: UserSettings,
        mutedChannels: Set<String>,
        enforceSourceFilters: Boolean = true
    ): Boolean {
        if (item.id.isBlank()) return true
        if (item.channelId.isNotBlank() && item.channelId in mutedChannels) return true
        if (item.id in settings.dislikedShortIds) return true
        if (item.channelId.isNotBlank() && item.channelId in settings.blockedShortChannelIds) {
            return true
        }
        if (isPlaceholderCreator(item.channelTitle)) return true

        // Duration / language / slop are candidate-source gates. Items already accepted into
        // the active queue (or explicitly opened by the user) only need the feedback gates.
        if (!enforceSourceFilters) return false

        if (!isValidShort(item)) return true
        if (SlopAndClickbaitFilter.isSlopOrClickbait(item.title, item.channelTitle)) return true
        if (!ShortsLanguageFilter.isLikelyEnglish(
                item.title,
                item.channelTitle,
                settings.preferredAudioLanguage
            )
        ) {
            return true
        }
        return false
    }

    private fun isPlaceholderCreator(channelTitle: String): Boolean {
        val name = channelTitle.trim().removePrefix("@")
        return name.equals("YouTube Creator", ignoreCase = true) ||
            name.equals("YouTube", ignoreCase = true)
    }

    /** Strict Shorts validity: a short-form source flag or a measured duration <= 60s. */
    private fun isValidShort(item: ShortItem): Boolean {
        if (item.durationSeconds > 60) return false
        return item.isShortForm || item.durationSeconds in 1..60
    }

    private fun scoreShort(
        item: ShortItem,
        affinities: Map<String, AffinityScoreEntity>
    ): Double {
        val channelScore = if (item.channelId.isBlank()) {
            0.0
        } else {
            // Unknown channels get the discovery prior so unsubscribed creators are not
            // permanently buried behind subscribed channels with a +4 baseline.
            affinities[channelKey(item.channelId)]?.score ?: DISCOVERY_BASELINE
        }
        val keywordScore = extractTags(item.title).sumOf { tag ->
            affinities[tagKey(tag)]?.score ?: 0.0
        }
        val jitter = random.nextDouble(-JITTER_RANGE, JITTER_RANGE)
        return channelScore + keywordScore + jitter
    }

    /**
     * Creator-fair ordering: bucket items per creator, order creators by their best score,
     * then take one item from each creator per rotation. This keeps affinity ranking inside a
     * creator while guaranteeing the queue cycles through every available creator instead of
     * letting the two highest-scoring channels monopolise the feed. The rotation also avoids
     * placing two Shorts from the same channel back-to-back.
     */
    private fun orderWithCreatorSpacing(
        scored: List<ScoredShort>,
        firstChannelId: String?
    ): List<ShortItem> {
        if (scored.isEmpty()) return emptyList()

        val buckets = LinkedHashMap<String, ArrayDeque<ShortItem>>()
        for (candidate in scored.sortedByDescending { it.score }) {
            val key = candidate.short.channelId.ifBlank { "video:${candidate.short.id}" }
            buckets.getOrPut(key) { ArrayDeque() }.addLast(candidate.short)
        }

        // Bucket insertion order = creator priority (their best item's score).
        val priority = buckets.keys.withIndex().associate { (index, key) -> key to index }
        val rotation = buckets.keys.toMutableList()
        val ordered = ArrayList<ShortItem>(scored.size)
        var lastChannelId = firstChannelId?.takeIf { it.isNotBlank() }

        while (rotation.isNotEmpty()) {
            val eligible = rotation.filter { key ->
                key.startsWith("video:") || key != lastChannelId
            }.ifEmpty { rotation }

            // Take from the creator with the most remaining Shorts first so a creator with a
            // single item is not stranded at the end next to another of its own videos.
            // Equal-size buckets fall back to creator score priority.
            var key = eligible.first()
            for (candidateKey in eligible) {
                val candidateSize = buckets[candidateKey]?.size ?: 0
                val bestSize = buckets[key]?.size ?: 0
                val candidatePriority = priority[candidateKey] ?: Int.MAX_VALUE
                val bestPriority = priority[key] ?: Int.MAX_VALUE
                if (candidateSize > bestSize ||
                    (candidateSize == bestSize && candidatePriority < bestPriority)
                ) {
                    key = candidateKey
                }
            }

            rotation.remove(key)
            val bucket = buckets[key] ?: continue
            val item = bucket.removeFirst()
            ordered.add(item)

            if (item.channelId.isNotBlank()) lastChannelId = item.channelId
            if (bucket.isNotEmpty()) rotation.add(key) else buckets.remove(key)
        }
        return ordered
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /** Normalizes title words + hashtags into lowercase keyword tokens. */
    fun extractTags(title: String): List<String> {
        return Regex("""[a-z0-9#][a-z0-9_#]{2,}""")
            .findAll(title.lowercase(Locale.ROOT))
            .map { it.value.removePrefix("#") }
            .filter { it !in STOP_WORDS && it.length >= 3 }
            .distinct()
            .toList()
    }

    private fun categorySeedQueries(settings: UserSettings): List<String> {
        val customItems = settings.customCategories.mapNotNull { serialized ->
            val parts = serialized.split("::")
            if (parts.size >= 3) {
                ShortsCategoryItem(
                    id = parts[0],
                    label = parts[1],
                    seedQuery = parts[2],
                    isCustom = true
                )
            } else {
                null
            }
        }
        return (ShortsCategoryItem.presets + customItems)
            .filter { it.id in settings.selectedCategoryIds }
            .map { it.seedQuery }
            .distinct()
    }

    private suspend fun loadAffinityMap(): Map<String, AffinityScoreEntity> =
        recommendationDao.getAllAffinities().associateBy { it.affinityKey }

    private suspend fun mutedChannelIds(): Set<String> =
        recommendationDao.getMutedChannelKeys()
            .mapTo(HashSet()) { it.removePrefix(CHANNEL_PREFIX) }

    private fun channelKey(channelId: String): String = "$CHANNEL_PREFIX$channelId"

    private fun tagKey(tag: String): String = "$TAG_PREFIX$tag"

    suspend fun recordFeedback(
        videoId: String,
        channelId: String,
        channelName: String,
        title: String,
        isLiked: Boolean
    ) {
        val short = ShortItem(
            id = videoId,
            channelId = channelId,
            channelTitle = channelName,
            title = title
        )
        if (isLiked) {
            setLike(short)
        } else {
            setDislike(short)
        }
    }

    suspend fun rankCandidates(items: List<ShortItem>): List<ShortItem> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        val affinities = loadAffinityMap()
        val scored = items.map { item ->
            ScoredShort(item, scoreShort(item, affinities))
        }
        orderWithCreatorSpacing(scored, null)
    }
}
