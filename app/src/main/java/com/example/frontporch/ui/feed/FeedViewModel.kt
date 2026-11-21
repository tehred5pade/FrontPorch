package com.example.frontporch.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.frontporch.data.db.SubscriptionDao
import com.example.frontporch.data.db.SubscriptionEntity
import com.example.frontporch.data.innertube.FeedVideoItem
import com.example.frontporch.data.innertube.SubscriptionFeedService
import com.example.frontporch.data.local.CachedFeedVideoDao
import com.example.frontporch.data.local.CachedFeedVideoEntity
import com.example.frontporch.data.local.FeedCacheStore
import com.example.frontporch.data.local.toCachedEntity
import com.example.frontporch.data.local.toFeedVideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Subscription feed backed by the Room cache. The visible list is a window over the newest
 * `cached_feed_videos` rows, so a cold start renders immediately from disk while
 * [SubscriptionFeedService.refreshSubscriptionsFast] merges fresh uploads behind it.
 */
class FeedViewModel(
    private val subscriptionDao: SubscriptionDao,
    private val feedService: SubscriptionFeedService,
    private val feedDao: CachedFeedVideoDao,
    private val cacheStore: FeedCacheStore
) : ViewModel() {

    val subscriptions: StateFlow<List<SubscriptionEntity>> =
        subscriptionDao.observeAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /** How many newest cached rows are visible. Grows as the user scrolls. */
    private val pageLimit = MutableStateFlow(PAGE_SIZE)

    val videos: StateFlow<List<FeedVideoItem>> = combine(
        feedDao.observeFeed(MAX_FEED_ITEMS),
        pageLimit
    ) { entities, limit ->
        entities.asSequence()
            .filter { !it.isShort }
            .map { it.toFeedVideoItem() }
            .take(limit)
            .toList()
    }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _newVideosAvailable = MutableStateFlow(false)
    val newVideosAvailable: StateFlow<Boolean> = _newVideosAvailable.asStateFlow()

    private var isEndReached = false

    init {
        viewModelScope.launch {
            subscriptionDao.observeAll()
                .distinctUntilChangedBy { list -> list.map { it.channelId } }
                .collect { subs ->
                    if (subs.isEmpty()) {
                        // No subscriptions left: the cached feed must not linger.
                        feedDao.clearAll()
                        _newVideosAvailable.value = false
                        pageLimit.value = PAGE_SIZE
                        isEndReached = false
                        return@collect
                    }

                    // Remove videos belonging to channels the user no longer follows.
                    feedDao.deleteChannelsNotIn(subs.map { it.channelId })
                    seedFromLegacyCacheIfNeeded(subs)
                    refreshFeed(subs)
                }
        }

        // If a refresh grows the cache enough to fill the current window again, paging can
        // continue.
        viewModelScope.launch {
            feedDao.observeFeed(MAX_FEED_ITEMS)
                .map { it.size }
                .distinctUntilChanged()
                .collect { count ->
                    if (count >= pageLimit.value) {
                        isEndReached = false
                    }
                }
        }
    }

    fun refreshFeed(subs: List<SubscriptionEntity> = subscriptions.value) {
        if (subs.isEmpty() || _isRefreshing.value) return
        _isRefreshing.value = true

        viewModelScope.launch {
            _errorMessage.value = null
            isEndReached = false

            // Videos already on screen before the refresh. Anything new arriving in Room
            // afterwards is advertised through the "New videos" arrow.
            val baseline = videos.value.mapTo(HashSet()) { it.videoId }

            try {
                feedService.refreshSubscriptionsFast(subscriptions = subs)
                    .onFailure { err ->
                        if (currentEntities().isEmpty()) {
                            _errorMessage.value = err.message
                                ?: "Could not refresh the subscription feed."
                        }
                    }

                if (baseline.isNotEmpty()) {
                    markNewVideosSince(baseline)
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadNextPage() {
        if (_isLoadingMore.value || isEndReached || _isRefreshing.value) return
        val subs = subscriptions.value
        if (subs.isEmpty()) return

        val currentLimit = pageLimit.value
        if (currentLimit >= MAX_FEED_ITEMS) {
            isEndReached = true
            return
        }

        val previousCount = videos.value.size
        _isLoadingMore.value = true

        viewModelScope.launch {
            try {
                pageLimit.value = (currentLimit + PAGE_SIZE).coerceAtMost(MAX_FEED_ITEMS)

                var grown = awaitVideoCountAbove(previousCount, PAGE_CACHE_WAIT_MS)
                if (grown == null && _isRefreshing.value) {
                    // A refresh is still filling Room from the network; wait for its
                    // results instead of issuing a second refresh.
                    grown = awaitVideoCountAbove(previousCount, PAGE_FETCH_WAIT_MS)
                }

                // If Room cannot fill the wider window, the cached feed is exhausted.
                if (grown == null || grown.size < pageLimit.value) {
                    isEndReached = true
                }
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun clearNewVideosAvailable() {
        _newVideosAvailable.value = false
    }

    /**
     * Migrates the old JSON feed cache into Room the first time it is empty, so upgrading
     * users keep their instantly-available feed. Upcoming/future entries are dropped.
     */
    private suspend fun seedFromLegacyCacheIfNeeded(subs: List<SubscriptionEntity>) {
        if (feedDao.count() > 0) return

        val allowedChannels = subs.mapTo(HashSet()) { it.channelId }
        val now = System.currentTimeMillis()
        val cached = withContext(Dispatchers.IO) { cacheStore.load() }
            .filter {
                !it.isShort &&
                    it.channelId in allowedChannels &&
                    it.publishedEpochMs <= now + UPCOMING_GRACE_MS
            }
            .sortedByDescending { it.publishedEpochMs }
            .take(MAX_FEED_ITEMS)

        if (cached.isNotEmpty()) {
            feedDao.upsertAll(cached.map { it.toCachedEntity() })
        }
    }

    private suspend fun markNewVideosSince(baseline: Set<String>) {
        val newIds = currentEntities()
            .asSequence()
            .filter { !it.isShort }
            .map { it.videoId }
            .filterNot { it in baseline }
            .toList()

        if (newIds.isNotEmpty()) {
            _newVideosAvailable.value = true
        }
    }

    private suspend fun currentEntities(): List<CachedFeedVideoEntity> =
        runCatching { feedDao.observeFeed(pageLimit.value).first() }
            .getOrDefault(emptyList())

    private suspend fun awaitVideoCountAbove(
        count: Int,
        timeoutMs: Long
    ): List<FeedVideoItem>? = withTimeoutOrNull(timeoutMs) {
        videos.first { it.size > count }
    }

    private companion object {
        const val PAGE_SIZE = 25
        const val MAX_FEED_ITEMS = 300
        const val PAGE_CACHE_WAIT_MS = 750L
        const val PAGE_FETCH_WAIT_MS = 4_000L
        const val UPCOMING_GRACE_MS = 60_000L
    }
}
