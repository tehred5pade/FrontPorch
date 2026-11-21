package io.tehred5pade.frontporch.ui.shorts

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.tehred5pade.frontporch.data.db.SubscriptionDao
import io.tehred5pade.frontporch.data.innertube.InnerTubeShortsService
import io.tehred5pade.frontporch.data.model.ShortsCategoryItem
import io.tehred5pade.frontporch.data.prefs.ShortRepeatCooldown
import io.tehred5pade.frontporch.data.prefs.UserSettings
import io.tehred5pade.frontporch.data.prefs.UserSettingsRepository
import io.tehred5pade.frontporch.data.util.SlopAndClickbaitFilter
import io.tehred5pade.frontporch.currentStreamMaxHeight
import io.tehred5pade.frontporch.fetchVisitorData
import io.tehred5pade.frontporch.resolveShortStream
import io.tehred5pade.frontporch.ui.models.ShortItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient

class ShortsViewModel(
    application: Application,
    private val okHttpClient: OkHttpClient,
    private val shortsService: InnerTubeShortsService,
    private val settingsRepository: UserSettingsRepository,
    private val subscriptionDao: SubscriptionDao
) : AndroidViewModel(application) {

    private val context: Context
        get() = getApplication<Application>()

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

    // Shorts that failed to resolve or render this session. Filtering them out of every
    // batch means an auto-skipped black screen never comes back on a later refresh.
    private val blockedShortIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        viewModelScope.launch {
            combine(
                userSettings.map { listOf(it.selectedCategoryIds, it.customCategories, it.repeatCooldown) }.distinctUntilChanged(),
                subscriptionDao.observeAll().map { it.size }.distinctUntilChanged()
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

    fun reloadFeed() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            continuationToken = null
            batchGeneration = 0
            _shorts.value = emptyList()

            if (visitorData == null) {
                visitorData = fetchVisitorData(okHttpClient)
            }

            val rawBatch = fetchCombinedShortsBatch()
            _shorts.value = filterPlayableShorts(rawBatch)
            _isLoading.value = false
        }
    }

    fun loadMoreShorts() {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            val currentList = _shorts.value
            val seedId = currentList.lastOrNull()?.id

            val (moreItems, nextToken) = shortsService.fetchReelSequence(
                seedVideoId = if (continuationToken == null) seedId else null,
                continuation = continuationToken,
                visitorData = visitorData,
                preferredAudioLanguage = userSettings.value.preferredAudioLanguage
            )

            continuationToken = nextToken
            val filteredMore = filterRepeats(moreItems, userSettings.value.seenShortsTimestamps, userSettings.value.repeatCooldown)
            val existingIds = currentList.mapTo(HashSet()) { it.id }
            val uniqueNew = filteredMore.filterNot { it.id in existingIds }

            val playableNew = filterPlayableShorts(uniqueNew)
            if (playableNew.isNotEmpty()) {
                _shorts.value = currentList + playableNew
            } else {
                val freshBatch = filterPlayableShorts(fetchCombinedShortsBatch().filterNot { it.id in existingIds })
                _shorts.value = currentList + freshBatch
            }
            _isLoading.value = false
        }
    }

    private suspend fun filterPlayableShorts(
        candidates: List<ShortItem>
    ): List<ShortItem> = coroutineScope {
        val semaphore = Semaphore(6)
        val maxHeight = context.currentStreamMaxHeight(userSettings.value)
        val prefLang = userSettings.value.preferredAudioLanguage
        // Skip anything already known to resolve into a blank/black player so a broken
        // Short never reappears when a later batch or refresh pulls it in again.
        candidates.filterNot { it.id in blockedShortIds }.map { item ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val stream = item.resolvedStream
                        ?: resolveShortStream(okHttpClient, item.id, visitorData, maxHeight, prefLang).getOrNull()
                    if (stream != null) item.copy(resolvedStream = stream) else null
                }
            }
        }.awaitAll().filterNotNull()
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

    private suspend fun fetchCombinedShortsBatch(): List<ShortItem> = coroutineScope {
        val allSubs = subscriptionDao.getAllOnce()
        val seenMap = userSettings.value.seenShortsTimestamps
        val cooldown = userSettings.value.repeatCooldown

        if (allSubs.isEmpty()) {
            val categoryItems = fetchCategoryBatch()
            return@coroutineScope filterPlayableShorts(filterRepeats(categoryItems, seenMap, cooldown))
        }

        // 1. Sample up to 12 subscribed channels in parallel to pull their latest Shorts
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

        // 2. Pick up to 4 subscribed channels that have Shorts and pull similar-channel Shorts
        val channelsWithSeeds = subResultsByChannel
            .filter { it.second.isNotEmpty() }
            .shuffled()
            .take(4)

        val similarShorts = channelsWithSeeds.map { (sub, shorts) ->
            async {
                shortsService.fetchSimilarChannelShorts(
                    subscribedChannel = sub,
                    seedShortVideoId = shorts.firstOrNull()?.id,
                    visitorData = visitorData
                )
            }
        }.awaitAll()
            .flatten()
            .filterNot { SlopAndClickbaitFilter.isSlopOrClickbait(it.title, it.channelTitle) }

        // 3. Apply repeat-cooldown filter to both pools
        val freshSubShorts = filterRepeats(directSubShorts, seenMap, cooldown)
            .distinctBy { it.id }
            .shuffled()

        val subVideoIds = freshSubShorts.mapTo(HashSet()) { it.id }
        val freshSimilarShorts = filterRepeats(similarShorts, seenMap, cooldown)
            .filterNot { it.id in subVideoIds }
            .distinctBy { it.id }
            .shuffled()

        // 4. Interleave: 2 subscribed Shorts followed by 2 similar-channel Shorts
        val blended = buildList {
            val subIter = freshSubShorts.iterator()
            val simIter = freshSimilarShorts.iterator()
            while (subIter.hasNext() || simIter.hasNext()) {
                repeat(2) { if (subIter.hasNext()) add(subIter.next()) }
                repeat(2) { if (simIter.hasNext()) add(simIter.next()) }
            }
        }

        // If all subscribed/similar Shorts were already watched recently, blend in category Shorts
        val batch = if (blended.size < 6) {
            val categoryFallback = fetchCategoryBatch()
            (blended + categoryFallback).distinctBy { it.id }
        } else {
            blended
        }
        filterPlayableShorts(batch)
    }

    private suspend fun fetchCategoryBatch(): List<ShortItem> = coroutineScope {
        val selectedIds = userSettings.value.selectedCategoryIds
        val customSet = userSettings.value.customCategories

        val customItems = customSet.mapNotNull { serialized ->
            val parts = serialized.split("::")
            if (parts.size >= 3) {
                ShortsCategoryItem(id = parts[0], label = parts[1], seedQuery = parts[2], isCustom = true)
            } else null
        }
        val allCategories = ShortsCategoryItem.presets + customItems
        val activeSelectedCategories = allCategories.filter { it.id in selectedIds }

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
        filterPlayableShorts(results)
    }
}
