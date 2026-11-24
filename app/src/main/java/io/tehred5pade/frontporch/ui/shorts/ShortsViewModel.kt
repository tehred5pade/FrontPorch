package io.tehred5pade.frontporch.ui.shorts

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.tehred5pade.frontporch.data.db.SubscriptionDao
import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import io.tehred5pade.frontporch.data.innertube.InnerTubeShortsService
import io.tehred5pade.frontporch.data.model.ShortsCategoryItem
import io.tehred5pade.frontporch.data.prefs.ShortRepeatCooldown
import io.tehred5pade.frontporch.data.prefs.UserSettings
import io.tehred5pade.frontporch.data.prefs.UserSettingsRepository
import io.tehred5pade.frontporch.data.search.SearchFilter
import io.tehred5pade.frontporch.data.search.YouTubeSearchRepository
import io.tehred5pade.frontporch.data.search.YouTubeSearchResult
import io.tehred5pade.frontporch.FrontPorchApp
import io.tehred5pade.frontporch.data.util.SlopAndClickbaitFilter
import io.tehred5pade.frontporch.fetchVisitorData
import io.tehred5pade.frontporch.ui.models.ShortItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
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

    init {
        // Drop any placeholder-creator Shorts already present from an earlier session.
        _shorts.update { list -> list.filterNot { isBlockedCreator(it) } }

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
            if (short.id in userSettings.value.likedShortIds) {
                settingsRepository.clearShortFeedback(short.id)
            } else {
                settingsRepository.setShortFeedback(short.id, short.channelId, liked = true)
            }
            // No pager reorder here: liked IDs seed the next batch in fetchCombinedShortsBatch.
        }
    }

    fun dislikeShort(short: ShortItem) {
        viewModelScope.launch {
            val settings = userSettings.value
            if (short.id in settings.dislikedShortIds) {
                settingsRepository.clearShortFeedback(short.id)
                return@launch
            }
            settingsRepository.setShortFeedback(short.id, short.channelId, liked = false)

            val newCount = (settings.dislikedShortChannelCounts[short.channelId] ?: 0) + 1
            val channelNowBlocked = short.channelId.isNotBlank() && newCount >= 2
            _shorts.update { list ->
                if (channelNowBlocked) {
                    list.filterNot { it.channelId == short.channelId }
                } else {
                    list.filterNot { it.id == short.id }
                }
            }
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
            val query = selectedCategoryItems().firstOrNull()?.seedQuery?.removeSuffix(" #shorts")
                ?: "trending"
            shortsService.searchShortsByQuery(
                query = "$query #shorts",
                visitorData = visitorData,
                paramsOverride = null,
                preferredAudioLanguage = settings.preferredAudioLanguage
            ).getOrDefault(emptyList())
        } else {
            raw.shuffled().take(6).map { sub ->
                async(Dispatchers.IO) {
                    runCatching {
                        shortsService.fetchChannelShorts(sub.channelId, sub.name).map { it.item }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }

        filterRepeats(candidates, settings.seenShortsTimestamps, settings.repeatCooldown)
            .filterNot { SlopAndClickbaitFilter.isSlopOrClickbait(it.title, it.channelTitle) }
            .filterNot { isHiddenByFeedback(it) || it.id in blockedShortIds }
            .distinctBy { it.id }
            .shuffled()
            .take(8)
    }

    fun loadMoreShorts() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            val currentList = _shorts.value
            val existingIds = currentList.mapTo(HashSet()) { it.id }

            // Keep the configured sources primary when paging. Generic reel sequences
            // drift away from the user's subscriptions and selected categories.
            val mixedNew = fetchCombinedShortsBatch()
                .filterNot { it.id in existingIds || isHiddenByFeedback(it) }
                .take(12)
            if (mixedNew.isNotEmpty()) {
                _shorts.value = currentList + mixedNew
                _isLoading.value = false
                return@launch
            }

            val seedId = currentList.lastOrNull()?.id
            val (moreItems, nextToken) = shortsService.fetchReelSequence(
                seedVideoId = if (continuationToken == null) seedId else null,
                continuation = continuationToken,
                visitorData = visitorData,
                preferredAudioLanguage = userSettings.value.preferredAudioLanguage
            )

            continuationToken = nextToken
            val filteredMore = filterRepeats(moreItems, userSettings.value.seenShortsTimestamps, userSettings.value.repeatCooldown)
            val uniqueNew = filteredMore
                .filterNot { it.id in existingIds || isHiddenByFeedback(it) || it.id in blockedShortIds }
                .take(12)
            if (uniqueNew.isNotEmpty()) {
                _shorts.value = currentList + uniqueNew
            }
            _isLoading.value = false
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
            .filterNot { it.id in subscribedIds }
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
            .filterNot { SlopAndClickbaitFilter.isSlopOrClickbait(it.title, it.channelTitle) }
            .distinctBy { it.id }
            .shuffled()
    }

    /**
     * "More like this": seed YouTube's reel sequence from recently liked Shorts so the
     * next batch leans toward videos related to what the user actually liked.
     */
    private suspend fun fetchLikedSimilarShorts(): List<ShortItem> = coroutineScope {
        userSettings.value.likedShortIds.toList().takeLast(3).map { likedId ->
            async(Dispatchers.IO) {
                runCatching {
                    shortsService.fetchReelSequence(
                        seedVideoId = likedId,
                        visitorData = visitorData,
                        preferredAudioLanguage = userSettings.value.preferredAudioLanguage
                    ).first
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
    }

    private suspend fun fetchCombinedShortsBatch(): List<ShortItem> = coroutineScope {
        val allSubs = subscriptionDao.getAllOnce()
        val settings = userSettings.value
        val seenMap = settings.seenShortsTimestamps
        val cooldown = settings.repeatCooldown

        if (allSubs.isEmpty()) {
            val categoryShorts = fetchCategoryBatch()
            val likedSimilar = fetchLikedSimilarShorts().filterNot { isHiddenByFeedback(it) }
            return@coroutineScope (categoryShorts + likedSimilar)
                .filterNot { isHiddenByFeedback(it) || it.id in blockedShortIds }
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
            .filterNot { SlopAndClickbaitFilter.isSlopOrClickbait(it.title, it.channelTitle) }

        // 2. Shorts from similar channels plus the user's selected categories.
        val channelsWithSeeds = subResultsByChannel.filter { it.second.isNotEmpty() }.shuffled().take(4)
        val similarShorts = (fetchSimilarChannelBatch(allSubs, channelsWithSeeds, visitorData) +
            fetchLikedSimilarShorts())
            .filterNot { SlopAndClickbaitFilter.isSlopOrClickbait(it.title, it.channelTitle) }
            .distinctBy { it.id }
            .shuffled()
        val categoryShorts = if (settings.selectedCategoryIds.isNotEmpty()) {
            fetchCategoryBatch()
        } else {
            emptyList()
        }

        // 3. Apply repeat cooldown and dedupe across sources.
        val freshSubShorts = filterRepeats(directSubShorts, seenMap, cooldown)
            .distinctBy { it.id }
            .shuffled()

        val subVideoIds = freshSubShorts.mapTo(HashSet()) { it.id }
        val likedChannels = settings.likedShortChannelIds
        val freshSimilarShorts = filterRepeats(similarShorts, seenMap, cooldown)
            .filterNot { it.id in subVideoIds }
            .distinctBy { it.id }
            .shuffled()
            // Stable sort within the shuffled list: channels the user liked rank first.
            .sortedByDescending { it.channelId in likedChannels }

        val usedIds = subVideoIds + freshSimilarShorts.mapTo(HashSet()) { it.id }
        val freshCategoryShorts = filterRepeats(categoryShorts, seenMap, cooldown)
            .filterNot { it.id in usedIds }
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

        val batch = if (blended.isEmpty()) {
            fetchCategoryBatch()
        } else {
            blended
        }
        batch.filterNot { isHiddenByFeedback(it) || it.id in blockedShortIds }
            .distinctBy { it.id }
            .take(20)
    }

    private suspend fun fetchCategoryBatch(): List<ShortItem> = coroutineScope {
        val activeSelectedCategories = selectedCategoryItems()

        val modifiers = listOf(
            "" to null,
            "clips" to "EgIIAw==",   // This week
            "tips" to "EgIIBA==",    // This month
            "builds" to null
        )
        val (suffix, sortParam) = modifiers[batchGeneration % modifiers.size]
        batchGeneration++

        val baseQueries = if (activeSelectedCategories.isNotEmpty()) {
            activeSelectedCategories.map { it.seedQuery }
        } else {
            listOf("trending #shorts")
        }

        val queries = baseQueries.map { catQuery ->
            val cleanBase = catQuery.removeSuffix(" #shorts")
            if (suffix.isEmpty()) "$cleanBase #shorts" else "$cleanBase $suffix #shorts"
        }

        val searchResults = queries
            .map { query ->
                async {
                    shortsService.searchShortsByQuery(
                        query = query,
                        visitorData = visitorData,
                        paramsOverride = sortParam,
                        preferredAudioLanguage = userSettings.value.preferredAudioLanguage
                    )
                        .onFailure { _errorMessage.value = it.message }
                        .getOrDefault(emptyList())
                }
            }
            .awaitAll()
            .flatten()
            .distinctBy { it.id }
            .shuffled()

        val filtered = filterRepeats(searchResults, userSettings.value.seenShortsTimestamps, userSettings.value.repeatCooldown)

        val seedId = filtered.firstOrNull()?.id ?: searchResults.firstOrNull()?.id
        val results = if (seedId != null) {
            val (reelItems, nextToken) = shortsService.fetchReelSequence(
                seedVideoId = seedId,
                visitorData = visitorData,
                preferredAudioLanguage = userSettings.value.preferredAudioLanguage
            )
            continuationToken = nextToken
            (filtered + reelItems).distinctBy { it.id }
        } else {
            filtered
        }
        results
    }
}
