package com.example.frontporch.ui.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.frontporch.data.db.SubscriptionEntity
import com.example.frontporch.data.innertube.FeedVideoItem
import com.example.frontporch.data.innertube.SubscriptionFeedService
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Loads a single channel's posted videos, split into regular videos and Shorts, and pages
 * through the rest of each playlist as the user scrolls.
 */
class ChannelDetailViewModel(
    private val subscription: SubscriptionEntity,
    private val feedService: SubscriptionFeedService
) : ViewModel() {

    private val _videos = MutableStateFlow<List<FeedVideoItem>>(emptyList())
    val videos: StateFlow<List<FeedVideoItem>> = _videos.asStateFlow()

    private val _shorts = MutableStateFlow<List<FeedVideoItem>>(emptyList())
    val shorts: StateFlow<List<FeedVideoItem>> = _shorts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _videosEndReached = MutableStateFlow(false)
    val videosEndReached: StateFlow<Boolean> = _videosEndReached.asStateFlow()

    private val _shortsEndReached = MutableStateFlow(false)
    val shortsEndReached: StateFlow<Boolean> = _shortsEndReached.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Continuation token for the next InnerTube page. The first video page comes from RSS,
    // so `videosInnerTubeStarted` tracks whether the uploads playlist has been opened yet.
    private var videosContinuation: String? = null
    private var videosInnerTubeStarted = false
    private var shortsContinuation: String? = null

    private var videosLoadJob: Job? = null
    private var shortsLoadJob: Job? = null

    init {
        load()
    }

    fun load() {
        if (_isLoading.value) return
        _isLoading.value = true
        _errorMessage.value = null
        videosLoadJob?.cancel()
        shortsLoadJob?.cancel()
        _isLoadingMore.value = false
        videosContinuation = null
        videosInnerTubeStarted = false
        shortsContinuation = null
        _videosEndReached.value = false
        _shortsEndReached.value = false

        viewModelScope.launch {
            try {
                val videosDeferred = async { feedService.fetchChannelVideosFirstPage(subscription) }
                val shortsDeferred = async { feedService.fetchChannelShortsPage(subscription, null) }
                val videosResult = videosDeferred.await()
                val shortsResult = shortsDeferred.await()

                val videoPage = videosResult.getOrNull()
                _videos.value = videoPage?.items.orEmpty()
                    .filter { !it.isShort }
                    .sortedByDescending { it.publishedEpochMs }
                videosInnerTubeStarted = videoPage?.fromInnerTube == true
                videosContinuation = videoPage?.nextContinuation
                _videosEndReached.value = videoPage?.fromInnerTube == true &&
                    videoPage.nextContinuation == null

                val shortsPage = shortsResult.getOrNull()
                _shorts.value = shortsPage?.items.orEmpty()
                    .filter { it.isShort }
                    .sortedByDescending { it.publishedEpochMs }
                shortsContinuation = shortsPage?.nextContinuation
                _shortsEndReached.value = shortsPage != null && shortsPage.nextContinuation == null

                if (_videos.value.isEmpty() && _shorts.value.isEmpty()) {
                    _errorMessage.value = videosResult.exceptionOrNull()?.message
                        ?: shortsResult.exceptionOrNull()?.message
                        ?: "No videos found for ${subscription.name}."
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadMoreVideos() {
        if (_isLoading.value || _videosEndReached.value || videosLoadJob?.isActive == true) return
        videosLoadJob = viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val continuation = if (videosInnerTubeStarted) videosContinuation else null
                if (videosInnerTubeStarted && continuation == null) {
                    _videosEndReached.value = true
                    return@launch
                }

                val page = feedService.fetchChannelVideosPage(
                    subscription = subscription,
                    continuation = continuation,
                    beforeEpochMs = _videos.value.minOfOrNull { it.publishedEpochMs }
                        ?: System.currentTimeMillis()
                ).getOrNull() ?: return@launch

                videosInnerTubeStarted = true
                videosContinuation = page.nextContinuation
                _videos.value = mergeVideos(_videos.value, page.items.filter { !it.isShort })
                _videosEndReached.value = page.nextContinuation == null
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    fun loadMoreShorts() {
        if (_isLoading.value || _shortsEndReached.value || shortsLoadJob?.isActive == true) return
        shortsLoadJob = viewModelScope.launch {
            _isLoadingMore.value = true
            try {
                val continuation = shortsContinuation ?: run {
                    _shortsEndReached.value = true
                    return@launch
                }

                val page = feedService.fetchChannelShortsPage(
                    subscription = subscription,
                    continuation = continuation,
                    beforeEpochMs = _shorts.value.minOfOrNull { it.publishedEpochMs }
                        ?: System.currentTimeMillis()
                ).getOrNull() ?: return@launch

                shortsContinuation = page.nextContinuation
                _shorts.value = mergeVideos(_shorts.value, page.items.filter { it.isShort })
                _shortsEndReached.value = page.nextContinuation == null
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /**
     * Appends a page while keeping the list newest-first. Videos already loaded win over the
     * incoming copy: the RSS page has exact publish timestamps, while InnerTube only reports
     * approximate relative ages.
     */
    private fun mergeVideos(
        existing: List<FeedVideoItem>,
        incoming: List<FeedVideoItem>
    ): List<FeedVideoItem> {
        if (incoming.isEmpty()) return existing

        val byId = LinkedHashMap<String, FeedVideoItem>(existing.size + incoming.size)
        existing.forEach { byId[it.videoId] = it }
        incoming.forEach { byId.putIfAbsent(it.videoId, it) }
        return byId.values.sortedByDescending { it.publishedEpochMs }
    }
}
