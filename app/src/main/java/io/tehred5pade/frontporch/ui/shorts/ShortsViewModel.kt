package io.tehred5pade.frontporch.ui.shorts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.tehred5pade.frontporch.FrontPorchApp
import io.tehred5pade.frontporch.data.db.SubscriptionDao
import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import io.tehred5pade.frontporch.data.innertube.InnerTubeShortsService
import io.tehred5pade.frontporch.data.local.FrontPorchDatabase
import io.tehred5pade.frontporch.data.model.ShortsCategoryItem
import io.tehred5pade.frontporch.data.prefs.ShortRepeatCooldown
import io.tehred5pade.frontporch.data.prefs.UserSettings
import io.tehred5pade.frontporch.data.prefs.UserSettingsRepository
import io.tehred5pade.frontporch.data.recommendation.ShortsRecommendationEngine
import io.tehred5pade.frontporch.data.search.SearchFilter
import io.tehred5pade.frontporch.data.search.YouTubeSearchRepository
import io.tehred5pade.frontporch.data.search.YouTubeSearchResult
import io.tehred5pade.frontporch.data.util.SlopAndClickbaitFilter
import io.tehred5pade.frontporch.fetchVisitorData
import io.tehred5pade.frontporch.ui.models.ShortItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class ShortsViewModel(
    application: Application,
    private val okHttpClient: OkHttpClient,
    private val shortsService: InnerTubeShortsService,
    private val settingsRepository: UserSettingsRepository,
    private val subscriptionDao: SubscriptionDao,
    private val searchRepository: YouTubeSearchRepository
) : AndroidViewModel(application) {

    private val app: FrontPorchApp
        get() = getApplication()

    private val userSettings: StateFlow<UserSettings> = settingsRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = UserSettings()
    )

    private val _shorts = MutableStateFlow<List<ShortItem>>(emptyList())
    val shorts: StateFlow<List<ShortItem>> = _shorts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    var visitorData: String? = null
        private set

    private var continuationToken: String? = null
    private var batchGeneration = 0
    private var reloadJob: Job? = null

    // A tab re-tap tick must be handled only once. ShortsScreen is disposed while the feed
    // is empty, and its LaunchedEffect would otherwise re-fire with the same sticky tick
    // and reload forever, leaving no player alive long enough to play anything.
    private var consumedReloadTick = -1

    fun consumeReloadTick(tick: Int): Boolean {
        if (tick == 0 || tick == consumedReloadTick) return false
        consumedReloadTick = tick
        return true
    }

    /** Direct search requests still honor explicit dislikes and blocked channels. */
    fun canOpenRequestedShort(videoId: String, channelId: String): Boolean {
        if (videoId.isBlank()) return false
        val settings = userSettings.value
        if (videoId in blockedShortIds || videoId in settings.dislikedShortIds) return false
        return channelId.isBlank() || channelId !in settings.blockedShortChannelIds
    }

    /** Prepend a user-selected Short so it plays immediately instead of waiting for discovery. */
    fun openRequestedShort(short: ShortItem) {
        _shorts.update { current ->
            (listOf(short) + current.filterNot { it.id == short.id }).distinctBy { it.id }
        }
    }

    // Shorts that failed to resolve or render this session. Filtering them out of every
    // batch means an auto-skipped black screen never comes back on a later refresh.
    private val blockedShortIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * "YouTube Creator" is the placeholder name used when YouTube doesn't expose a real
     * channel. Those results are recommendation/clickbait noise, so never show them.
     */
    private fun isBlockedCreator(item: ShortItem): Boolean {
        val name = item.channelTitle.trim().removePrefix("@")
        return name.equals("YouTube Creator", ignoreCase = true) ||
            name.equals("YouTube", ignoreCase = true)
    }

    /** Disliked Shorts, blocked creators, and channels with 2+ dislikes are hidden everywhere. */
    private fun isHiddenByFeedback(item: ShortItem): Boolean {
        if (isBlockedCreator(item)) return true
        val settings = userSettings.value
        if (item.id in settings.dislikedShortIds) return true
        return item.channelId.isNotBlank() && item.channelId in settings.blockedShortChannelIds
    }

    /** Central allowlist filter: no blocked/hidden/disliked/slop/clickbait items. */
    private fun isAllowedShort(item: ShortItem, settings: UserSettings = userSettings.value): Boolean {
        if (item.id in blockedShortIds) return false
        if (isHiddenByFeedback(item)) return false
        return !SlopAndClickbaitFilter.isSlopOrClickbait(item.title, item.channelTitle)
    }

    private val recommendationEngine = ShortsRecommendationEngine(
        FrontPorchDatabase.getDatabase(application).recommendationDao(),
        subscriptionDao,
        shortsService,
        searchRepository
    )

    init {
        // Drop any placeholder-creator Shorts already present from an earlier session.
        _shorts.update { list -> list.filterNot { isBlockedCreator(it) } }

        viewModelScope.launch {
            recommendationEngine.seedSubscriptions()
        }

        viewModelScope.launch {
            combine(
                userSettings.map { listOf(it.selectedCategoryIds, it.customCategories, it.repeatCooldown) }.distinctUntilChanged(),
                // Only reload when the feed switches between "no subscriptions" and "has
                // subscriptions". A single subscribe/unsubscribe from the Shorts overlay
                // should not wipe the pager and jump back to the first video.
                subscriptionDao.observeAll().map { it.isEmpty() }.distinctUntilChanged()
            ) { _, _ -> Unit }
                .collect {
                    reloadFeed()
                }
        }
    }

    fun recordShortViewed(videoId: String) {
        viewModelScope.launch {
            settingsRepository.recordShortViewed(videoId)
        }
    }

    fun likeShort(short: ShortItem) {
        viewModelScope.launch {
            val isLiked = short.id !in userSettings.value.likedShortIds
            if (isLiked) {
                settingsRepository.setShortFeedback(short.id, short.channelId, liked = true)
            } else {
                settingsRepository.clearShortFeedback(short.id)
            }
            recommendationEngine.recordFeedback(
                videoId = short.id,
                channelId = short.channelId,
                channelName = short.channelTitle,
                title = short.title,
                isLiked = isLiked
            )
        }
    }

    fun dislikeShort(short: ShortItem) {
        viewModelScope.launch {
            val settings = userSettings.value
            val isDisliked = short.id !in settings.dislikedShortIds
            if (isDisliked) {
                settingsRepository.setShortFeedback(short.id, short.channelId, liked = false)
            } else {
                settingsRepository.clearShortFeedback(short.id)
            }
            recommendationEngine.recordFeedback(
                videoId = short.id,
                channelId = short.channelId,
                channelName = short.channelTitle,
                title = short.title,
                isLiked = false
            )

            val newCount = (settings.dislikedShortChannelCounts[short.channelId] ?: 0) + 1
            val channelNowBlocked = short.channelId.isNotBlank() && newCount >= 2
            _shorts.update { list ->
                if (channelNowBlocked) {
                    list.filterNot { it.channelId == short.channelId }
                } else {
                    list.filterNot { it.id == short.id }
                }
            }
            val ranked = recommendationEngine.rankCandidates(_shorts.value)
            _shorts.value = ranked
        }
    }

    fun reloadFeed() {
        // A new reload supersedes any in-flight one so a stale batch cannot overwrite it.
        reloadJob?.cancel()
        reloadJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            continuationToken = null
            batchGeneration = 0

            // Never clear the current feed here. Clearing it disposes ShortsScreen's pager and
            // both ExoPlayers, which is what made every restart feel like a cold start.
            // Start the visitor-token fetch alongside a small metadata-only first batch; the
            // screen resolves the actual stream for the visible Short (and the next one) only.
            val visitorDeferred = async(Dispatchers.IO) {
                if (visitorData == null) fetchVisitorData(okHttpClient) else visitorData
            }
            val quickBatch = fetchQuickShortsBatch()
            val token = visitorDeferred.await()
            if (token != null) {
                visitorData = token
                app.cachedVisitorData = token
            }

            if (quickBatch.isNotEmpty()) {
                _shorts.value = quickBatch
                _isLoading.value = false
            }

            // Enrich with subscriptions/similar/category Shorts in the background. Appending
            // keeps the already-playing first Short at page 0 instead of re-preparing a player.
            val fullBatch = fetchCombinedShortsBatch()
            val merged = (quickBatch + fullBatch).distinctBy { it.id }
            if (merged.isNotEmpty()) _shorts.value = merged
            _isLoading.value = false
        }
    }

    /**
     * Background refresh used when the Shorts tab is re-tapped. The feed and players stay
     * mounted; we only append newly discovered metadata at the end of the list.
     */
    fun refreshInBackground() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _errorMessage.value = null
            val fresh = fetchCombinedShortsBatch().take(12)
            if (fresh.isNotEmpty()) {
                _shorts.update { current -> (current + fresh).distinctBy { it.id } }
            }
        }
    }

    /**
     * Smallest possible first batch. No stream resolution happens here, so the pager can be
     * populated and the visible Short can start resolving immediately.
     */
    private suspend fun fetchQuickShortsBatch(): List<ShortItem> = coroutineScope {
        val settings = userSettings.value
        val raw = runCatching { subscriptionDao.getAllOnce() }.getOrDefault(emptyList())
        val candidates = if (raw.isEmpty()) {
            // No subscriptions: only honor explicitly selected categories and thumbs-up-similar.
            val categoryItems = selectedCategoryItems()
            val categoryResults = if (categoryItems.isEmpty()) {
                emptyList()
            } else {
                categoryItems.shuffled().take(2).map { cat ->
                    async(Dispatchers.IO) {
                        shortsService.searchShortsByQuery(
                            query = cat.seedQuery,
                            visitorData = visitorData,
                            paramsOverride = null,
                            preferredAudioLanguage = settings.preferredAudioLanguage
                        ).getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
            }
            categoryResults + fetchLikedSimilarShorts()
        } else {
            raw.shuffled().take(6).map { sub ->
                async(Dispatchers.IO) {
                    runCatching {
                        shortsService.fetchChannelShorts(sub.channelId, sub.name).map { it.item }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }

        val filtered = filterRepeats(candidates, settings.seenShortsTimestamps, settings.repeatCooldown)
            .filter { isAllowedShort(it, settings) }
            .distinctBy { it.id }
        recommendationEngine.rankCandidates(filtered).take(8)
    }

    fun loadMoreShorts() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val settings = userSettings.value
                val currentList = _shorts.value
                val existingIds = currentList.mapTo(HashSet()) { it.id }

                // Only use allowed sources: subscriptions, similar-to-subscribed,
                // selected categories, and thumbs-up-similar. Avoid generic reel drift.
                val mixedNew = fetchCombinedShortsBatch()
                    .asSequence()
                    .filter { it.id !in existingIds }
                    .filter { isAllowedShort(it, settings) }
                    .distinctBy { it.id }
                    .take(12)
                    .toList()
                if (mixedNew.isNotEmpty()) {
                    _shorts.value = currentList + mixedNew
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun hideBrokenShort(videoId: String, reason: String = "unknown") {
        blockedShortIds.add(videoId)
        android.util.Log.w("FRONTPORCH_SHORTS", "blocked $videoId ($reason)")
        _shorts.update { list -> list.filterNot { it.id == videoId } }
    }

    private fun filterRepeats(
        items: List<ShortItem>,
        seenMap: Map<String, Long>,
        cooldown: ShortRepeatCooldown
    ): List<ShortItem> {
        if (cooldown == ShortRepeatCooldown.ALLOW_ANYTIME) return items
        val now = System.currentTimeMillis()
        return items.filter { item ->
            val lastSeen = seenMap[item.id] ?: return@filter true
            if (cooldown == ShortRepeatCooldown.NEVER) false
            else (now - lastSeen) >= cooldown.durationMs
        }
    }

    private fun selectedCategoryItems(): List<ShortsCategoryItem> {
        val selectedIds = userSettings.value.selectedCategoryIds
        val customItems = userSettings.value.customCategories.mapNotNull { serialized ->
            val parts = serialized.split("::")
            if (parts.size >= 3) {
                ShortsCategoryItem(
                    id = parts[0],
                    label = parts[1],
                    seedQuery = parts[2],
                    isCustom = true
                )
            } else null
        }
        return (ShortsCategoryItem.presets + customItems)
            .filter { it.id in selectedIds }
            .shuffled()
            .take(6)
    }

    /**
     * Discovers channels similar to the user's subscriptions and selected topics, then
     * pulls their Shorts. This supplements reel-sequence recommendations with actual
     * channels in the same niches.
     */
    private suspend fun fetchSimilarChannelBatch(
        allSubs: List<SubscriptionEntity>,
        channelsWithSeeds: List<Pair<SubscriptionEntity, List<ShortItem>>>,
        visitorData: String?
    ): List<ShortItem> = coroutineScope {
        val subscribedIds = allSubs.mapTo(HashSet()) { it.channelId }
        val seedQueries = (
            channelsWithSeeds.map { it.first.name } + selectedCategoryItems().map {
                it.seedQuery.removeSuffix(" #shorts")
            }
        ).distinctBy { it.lowercase() }.shuffled().take(6)

        val settings = userSettings.value
        val discoveredChannels = seedQueries.map { seed ->
            async {
                runCatching {
                    searchRepository.searchFirstPage("$seed shorts", SearchFilter.CHANNELS).items
                        .filterIsInstance<YouTubeSearchResult.Channel>()
                }.getOrDefault(emptyList())
            }
        }.awaitAll()
            .flatten()
            .distinctBy { it.id }
            .filterNot { it.id in subscribedIds || it.id in settings.blockedShortChannelIds }
            .shuffled()
            .take(8)

        val discoveredShorts = discoveredChannels.map { channel ->
            async { shortsService.fetchChannelShorts(channel.id, channel.title).map { it.item } }
        }.awaitAll().flatten()

        val relatedReelShorts = channelsWithSeeds.shuffled().take(4).map { (sub, shorts) ->
            async {
                shortsService.fetchSimilarChannelShorts(
                    subscribedChannel = sub,
                    seedShortVideoId = shorts.firstOrNull()?.id,
                    visitorData = visitorData
                )
            }
        }.awaitAll().flatten()

        (discoveredShorts + relatedReelShorts)
            .filter { isAllowedShort(it, settings) }
            .filterNot { it.channelId.isNotBlank() && it.channelId in subscribedIds }
            .distinctBy { it.id }
            .shuffled()
    }

    /**
     * "More like this": seed YouTube's reel sequence from recently liked Shorts so the
     * next batch leans toward videos related to what the user actually liked.
     */
    private suspend fun fetchLikedSimilarShorts(): List<ShortItem> = coroutineScope {
        val settings = userSettings.value
        settings.likedShortIds.toList().takeLast(3).map { likedId ->
            async(Dispatchers.IO) {
                runCatching {
                    shortsService.fetchReelSequence(
                        seedVideoId = likedId,
                        continuation = null,
                        visitorData = visitorData,
                        preferredAudioLanguage = settings.preferredAudioLanguage
                    ).first
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
            .filter { isAllowedShort(it, settings) }
            .distinctBy { it.id }
    }

    private suspend fun fetchCombinedShortsBatch(): List<ShortItem> = coroutineScope {
        val allSubs = subscriptionDao.getAllOnce()
        val settings = userSettings.value
        val seenMap = settings.seenShortsTimestamps
        val cooldown = settings.repeatCooldown

        if (allSubs.isEmpty()) {
            // Strict source policy: no subscriptions means only selected categories and liked-similar.
            val categoryShorts = if (settings.selectedCategoryIds.isNotEmpty()) fetchCategoryBatch() else emptyList()
            val likedSimilar = fetchLikedSimilarShorts()
            return@coroutineScope (categoryShorts + likedSimilar)
                .filter { isAllowedShort(it, settings) }
                .distinctBy { it.id }
                .shuffled()
                .take(20)
        }

        // 1. Sample subscribed channels in parallel for their latest Shorts.
        val sampledSubs = allSubs.shuffled().take(12)
        val subResultsByChannel = sampledSubs.map { sub ->
            async {
                sub to shortsService.fetchChannelShorts(sub.channelId, sub.name)
                    .sortedByDescending { it.publishedIso }
                    .map { it.item }
            }
        }.awaitAll()

        val directSubShorts = subResultsByChannel
            .flatMap { it.second }
            .filter { isAllowedShort(it, settings) }

        // 2. Shorts from similar channels, selected categories, and thumbs-up-similar.
        val channelsWithSeeds = subResultsByChannel.filter { it.second.isNotEmpty() }.shuffled().take(4)
        val similarShorts = (fetchSimilarChannelBatch(allSubs, channelsWithSeeds, visitorData) +
            fetchLikedSimilarShorts())
            .filter { isAllowedShort(it, settings) }
            .distinctBy { it.id }
            .shuffled()
        val categoryShorts = if (settings.selectedCategoryIds.isNotEmpty()) {
            fetchCategoryBatch()
        } else {
            emptyList()
        }

        // 3. Apply repeat cooldown and dedupe across sources.
        val freshSubShorts = filterRepeats(directSubShorts, seenMap, cooldown)
            .filter { isAllowedShort(it, settings) }
            .distinctBy { it.id }
            .shuffled()

        val subVideoIds = freshSubShorts.mapTo(HashSet()) { it.id }
        val likedChannels = settings.likedShortChannelIds
        val freshSimilarShorts = filterRepeats(similarShorts, seenMap, cooldown)
            .filterNot { it.id in subVideoIds }
            .filter { isAllowedShort(it, settings) }
            .distinctBy { it.id }
            .shuffled()
            // Stable sort within the shuffled list: channels the user liked rank first.
            .sortedByDescending { it.channelId in likedChannels }

        val usedIds = subVideoIds + freshSimilarShorts.mapTo(HashSet()) { it.id }
        val freshCategoryShorts = filterRepeats(categoryShorts, seenMap, cooldown)
            .filterNot { it.id in usedIds }
            .filter { isAllowedShort(it, settings) }
            .distinctBy { it.id }
            .shuffled()

        // 4. Interleave 2 subscribed, 1 similar-channel, and 1 category Short.
        val blended = buildList {
            val subIter = freshSubShorts.iterator()
            val simIter = freshSimilarShorts.iterator()
            val catIter = freshCategoryShorts.iterator()
            while (subIter.hasNext() || simIter.hasNext() || catIter.hasNext()) {
                repeat(2) { if (subIter.hasNext()) add(subIter.next()) }
                if (simIter.hasNext()) add(simIter.next())
                if (catIter.hasNext()) add(catIter.next())
            }
        }

        recommendationEngine.rankCandidates(blended)
            .filter { isAllowedShort(it, settings) }
            .distinctBy { it.id }
            .take(20)
    }

    private suspend fun fetchCategoryBatch(): List<ShortItem> = coroutineScope {
        val settings = userSettings.value
        val activeSelectedCategories = selectedCategoryItems()
        if (activeSelectedCategories.isEmpty()) return@coroutineScope emptyList()

        val modifiers = listOf(
            "" to null,
            "clips" to "EgIIAw==",   // This week
            "tips" to "EgIIBA==",    // This month
            "builds" to null
        )
        val (suffix, sortParam) = modifiers[batchGeneration % modifiers.size]
        batchGeneration++

        val queries = activeSelectedCategories.map { cat ->
            val cleanBase = cat.seedQuery.removeSuffix(" #shorts")
            if (suffix.isEmpty()) "$cleanBase #shorts" else "$cleanBase $suffix #shorts"
        }

        val searchResults = queries
            .map { query ->
                async {
                    shortsService.searchShortsByQuery(
                        query = query,
                        visitorData = visitorData,
                        paramsOverride = sortParam,
                        preferredAudioLanguage = settings.preferredAudioLanguage
                    )
                        .onFailure { _errorMessage.value = it.message }
                        .getOrDefault(emptyList())
                }
            }
            .awaitAll()
            .flatten()
            .filter { isAllowedShort(it, settings) }
            .distinctBy { it.id }
            .shuffled()

        filterRepeats(searchResults, settings.seenShortsTimestamps, settings.repeatCooldown)
            .filter { isAllowedShort(it, settings) }
            .distinctBy { it.id }
    }
}
