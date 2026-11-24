package io.tehred5pade.frontporch.ui.shorts

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.compose.AsyncImage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import io.tehred5pade.frontporch.FrontPorchApp
import io.tehred5pade.frontporch.R
import io.tehred5pade.frontporch.ResolvedShortStream
import io.tehred5pade.frontporch.ResolvedStream
import io.tehred5pade.frontporch.resolveShortStream
import io.tehred5pade.frontporch.data.sponsorblock.SponsorBlockPreferences
import io.tehred5pade.frontporch.data.sponsorblock.SponsorBlockRepository
import io.tehred5pade.frontporch.data.sponsorblock.SponsorSegment
import io.tehred5pade.frontporch.data.sponsorblock.SponsorSkipBehavior
import io.tehred5pade.frontporch.ui.models.ShortItem
import io.tehred5pade.frontporch.ui.player.KeepScreenOnEffect
import io.tehred5pade.frontporch.ui.player.applyCaptionTrackSelection
import io.tehred5pade.frontporch.ui.player.isGenericCaptionSelection
import io.tehred5pade.frontporch.ui.player.resolveCaptionTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SHORT_PREPARE_TIMEOUT_MS = 30_000L
private const val SHORT_RENDER_TIMEOUT_MS = 16_000L
private const val SHORT_WATCHDOG_POLL_MS = 200L

private fun isResumed(lifecycle: Lifecycle?): Boolean =
    lifecycle == null || lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

@OptIn(UnstableApi::class)
@Composable
fun ShortsScreen(
    viewModel: ShortsViewModel,
    // Bumped when the Shorts tab is tapped again: reload and restart from the first Short.
    reloadTick: Int = 0,
    shorts: List<ShortItem>,
    isLoading: Boolean,
    errorMessage: String?,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onShortViewed: (String) -> Unit = {},
    isPageActive: Boolean = true,
    maxStreamHeight: Int = 1080,
    streamTargetFps: Int = 0,
    preferredAudioLanguage: String = "en",
    subscribedChannelIds: Set<String> = emptySet(),
    likedShortIds: Set<String> = emptySet(),
    dislikedShortIds: Set<String> = emptySet(),
    onLikeShort: (ShortItem) -> Unit = {},
    onDislikeShort: (ShortItem) -> Unit = {},
    onOpenChannel: (channelId: String, channelTitle: String) -> Unit = { _, _ -> },
    onToggleSubscribe: (channelId: String, channelTitle: String) -> Unit = { _, _ -> }
) {
    KeepScreenOnEffect(keepScreenOn = isPageActive && shorts.isNotEmpty())

    val context = LocalContext.current
    val app = context.applicationContext as FrontPorchApp

    if (shorts.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> CircularProgressIndicator(color = Color.White)
                errorMessage != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(text = errorMessage, color = Color.White)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = onRetry) { Text("Retry") }
                    }
                }
                else -> Text(text = "No Shorts available", color = Color.White)
            }
        }
        return
    }

    val pagerState = rememberPagerState(pageCount = { shorts.size })

    // Keep the pager index valid when a batch shrinks (e.g. a broken Short is hidden).
    LaunchedEffect(shorts.size) {
        if (pagerState.currentPage >= shorts.size) {
            pagerState.scrollToPage((shorts.size - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(reloadTick) {
        // Tapping the Shorts tab again only rewinds to the first Short. Keep the feed and
        // both players alive so playback resumes from the already-buffered first Short
        // instead of repeating the slow cold-start discovery. New content is appended in
        // the background. The tick is consumed once in the ViewModel (which outlives this
        // composable) so it cannot re-fire after an empty-list disposal.
        if (!viewModel.consumeReloadTick(reloadTick)) return@LaunchedEffect
        pagerState.scrollToPage(0)
        viewModel.refreshInBackground()
    }
    val overscrollY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val streamCache = remember { mutableStateMapOf<String, ResolvedShortStream>() }

    // Upcoming streams are resolved here, outside the pager's LaunchedEffects. Those effects
    // are canceled on every swipe, so prefetching inside them would abort mid-request and
    // leave the next Short waiting on a fresh 1-10s YouTube player request.
    val prefetchScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    val prefetchInFlight = remember { java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }

    var showShortsControls by remember { mutableStateOf(true) }
    var currentShortCaption by remember { mutableStateOf("Off") }

    // Used by the watchdog to avoid counting time while the screen is off or the app is
    // backgrounded, when no surface can paint regardless of stream health.
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()?.lifecycle

    val slotBuffering = remember { mutableStateListOf(false, false) }
    var isResolvingCurrent by remember { mutableStateOf(false) }

    // The videoId currently loaded into each player slot. Declared before the player pool so
    // the error listener can map a failing slot back to the Short that caused it.
    val preparedVideoIds = remember { mutableStateListOf<String?>(null, null) }

    // Set once a slot actually paints a frame. A Short that resolves but never reaches this
    // point is the blank-black-screen case the watchdog blocks.
    val renderedFirstFrame = remember { mutableStateListOf(false, false) }

    val mediaSourceFactory = remember {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; Quest 3 Build/SQ3A.220605.009.A1) gzip")
            .setAllowCrossProtocolRedirects(true)
        DefaultMediaSourceFactory(httpFactory)
    }

    // Strictly 2 ExoPlayer instances
    val playerPool = remember {
        List(2) { slotIndex ->
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
                .apply {
                    repeatMode = Player.REPEAT_MODE_ONE
                    trackSelectionParameters = trackSelectionParameters
                        .buildUpon()
                        .apply {
                            if (preferredAudioLanguage.isNotBlank() && preferredAudioLanguage != "auto") {
                                setPreferredAudioLanguage(preferredAudioLanguage)
                            } else {
                                setPreferredAudioLanguage(null)
                            }
                        }
                        .build()
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            slotBuffering[slotIndex] = (playbackState == Player.STATE_BUFFERING)
                        }

                        override fun onRenderedFirstFrame() {
                            renderedFirstFrame[slotIndex] = true
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            // A standby player can report an error from the video it held before
                            // being reassigned. Blocking that id hides a Short that never failed.
                            // Only the Short actually on screen is treated as broken; failures in
                            // a preloaded player will surface again when it becomes active.
                            if (slotIndex != pagerState.currentPage % 2) return
                            preparedVideoIds[slotIndex]?.let { viewModel.hideBrokenShort(it, "player-error") }
                        }
                    })
                }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            prefetchScope.cancel()
            playerPool.forEach { it.release() }
        }
    }

    val sponsorBlockRepo = remember { SponsorBlockRepository(app.okHttpClient) }
    val sponsorBlockEnabled by SponsorBlockPreferences.isEnabled.collectAsState()
    val categoryBehaviors by SponsorBlockPreferences.categoryBehaviors.collectAsState()

    val currentShortId = shorts.getOrNull(pagerState.currentPage)?.id
    val activeCaptionTracks = streamCache[currentShortId]?.captions
        ?: shorts.getOrNull(pagerState.currentPage)?.resolvedCaptions.orEmpty()
    val availableShortCaptions = listOf("Off", "On", "Auto")
    LaunchedEffect(currentShortId) {
        currentShortCaption = "Off"
    }

    var rawShortSegments by remember(currentShortId) { mutableStateOf<List<SponsorSegment>>(emptyList()) }
    val skippedShortUuids = remember(currentShortId) { mutableStateListOf<String>() }
    var shortSkipBannerText by remember(currentShortId) { mutableStateOf<String?>(null) }

    val activeShortSegments = remember(rawShortSegments, categoryBehaviors, sponsorBlockEnabled) {
        if (!sponsorBlockEnabled) emptyList()
        else rawShortSegments.filter { seg ->
            (categoryBehaviors[seg.category] ?: seg.category.defaultBehavior) != SponsorSkipBehavior.NOTHING
        }
    }

    LaunchedEffect(currentShortId, sponsorBlockEnabled) {
        skippedShortUuids.clear()
        shortSkipBannerText = null
        rawShortSegments = if (sponsorBlockEnabled && !currentShortId.isNullOrBlank()) {
            sponsorBlockRepo.getSegments(currentShortId)
        } else {
            emptyList()
        }
    }

    // High-frequency position watcher to auto-skip ads, sponsors, self-promos, and intros/outros on YouTube Shorts
    LaunchedEffect(currentShortId, isPageActive, activeShortSegments, categoryBehaviors, sponsorBlockEnabled) {
        if (!isPageActive || !sponsorBlockEnabled || activeShortSegments.isEmpty() || currentShortId.isNullOrBlank()) return@LaunchedEffect

        while (true) {
            delay(120L)
            val activeSlot = pagerState.currentPage % 2
            val activePlayer = playerPool.getOrNull(activeSlot) ?: continue
            if (!activePlayer.isPlaying || activePlayer.playbackState != Player.STATE_READY) continue

            val currentSec = activePlayer.currentPosition / 1000f
            val totalDurSec = if (activePlayer.duration > 0L) activePlayer.duration / 1000f else 0f

            val autoHit = activeShortSegments.firstOrNull { seg ->
                val mode = categoryBehaviors[seg.category] ?: seg.category.defaultBehavior
                mode == SponsorSkipBehavior.AUTO_SKIP &&
                    seg.actionType == "skip" &&
                    seg.uuid !in skippedShortUuids &&
                    currentSec >= (seg.startSec - 0.25f) &&
                    currentSec < (seg.endSec - 0.10f)
            }

            if (autoHit != null) {
                skippedShortUuids.add(autoHit.uuid)
                val isEndOfVideo = totalDurSec > 0f && autoHit.endSec >= (totalDurSec - 0.75f)
                if (isEndOfVideo) {
                    if (pagerState.currentPage < shorts.lastIndex) {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    } else {
                        activePlayer.seekTo(activePlayer.duration)
                    }
                } else {
                    activePlayer.seekTo((autoHit.endSec * 1000f).toLong())
                }
                shortSkipBannerText = "Skipped ${autoHit.category.title}"
            }
        }
    }

    LaunchedEffect(shortSkipBannerText) {
        if (shortSkipBannerText != null) {
            delay(2200L)
            shortSkipBannerText = null
        }
    }

    LaunchedEffect(currentShortCaption, activeCaptionTracks) {
        val selected = resolveCaptionTrack(activeCaptionTracks, currentShortCaption)
        val enableIfNoTrack = isGenericCaptionSelection(currentShortCaption)
        playerPool.forEach { player ->
            applyCaptionTrackSelection(player, selected, enableIfNoTrack)
        }
    }

    // HorizontalPager keeps adjacent pages mounted, so pause Shorts as soon as the
    // user swipes away and resume only when this tab is visible again.
    LaunchedEffect(isPageActive) {
        if (!isPageActive) {
            playerPool.forEach { player ->
                player.playWhenReady = false
                player.pause()
            }
        } else {
            val index = pagerState.currentPage
            val shortId = shorts.getOrNull(index)?.id
            val slot = index % 2
            if (shortId != null && preparedVideoIds.getOrNull(slot) == shortId) {
                playerPool.getOrNull(slot)?.let { player ->
                    player.playWhenReady = true
                    player.play()
                }
            }
        }
    }

    // Trigger pagination 3 Shorts before the end
    LaunchedEffect(pagerState.currentPage, shorts.size) {
        if (pagerState.currentPage >= shorts.size - 3) {
            onLoadMore()
        }
    }

    // Resolve streams several Shorts ahead. Only the immediate next Short can be buffered
    // into the standby ExoPlayer, but the resolved-stream cache is cheap to fill. By the time
    // the user reaches a Short its player request has already finished, so no spinner is
    // needed for resolution.
    LaunchedEffect(shorts, pagerState.currentPage) {
        val first = pagerState.currentPage + 1
        if (first > shorts.lastIndex) return@LaunchedEffect
        val last = (first + 5).coerceAtMost(shorts.lastIndex)
        for (index in first..last) {
            if (prefetchInFlight.size >= 4) break
            val short = shorts.getOrNull(index) ?: continue
            if (short.id.isBlank() || short.resolvedStream != null || short.id in streamCache) continue
            if (!prefetchInFlight.add(short.id)) continue
            prefetchScope.launch {
                try {
                    resolveShortStream(
                        app.okHttpClient,
                        short.id,
                        app.cachedVisitorData,
                        maxStreamHeight,
                        preferredAudioLanguage,
                        streamTargetFps
                    ).getOrNull()?.let { streamCache[short.id] = it }
                } finally {
                    prefetchInFlight.remove(short.id)
                }
            }
        }
    }

    // Helper to load a resolved stream into a specific player slot
    fun prepareStreamOnPlayer(
        playerIndex: Int,
        videoId: String,
        resolved: ResolvedShortStream,
        autoPlay: Boolean
    ) {
        val player = playerPool[playerIndex]
        val subtitles = resolved.captions.map { caption ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(caption.url))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(caption.languageCode)
                .setLabel(caption.label)
                .build()
        }
        when (val stream = resolved.stream) {
            is ResolvedStream.Single -> player.setMediaItem(
                MediaItem.Builder()
                    .setUri(stream.url)
                    .setSubtitleConfigurations(subtitles)
                    .build()
            )
            is ResolvedStream.Adaptive -> {
                val video = mediaSourceFactory.createMediaSource(
                    MediaItem.Builder()
                        .setUri(stream.videoUrl)
                        .setSubtitleConfigurations(subtitles)
                        .build()
                )
                val audio = mediaSourceFactory.createMediaSource(MediaItem.fromUri(stream.audioUrl))
                // true, true aligns mismatched start timestamps and clips trailing silence
                player.setMediaSource(MergingMediaSource(true, true, video, audio))
            }
        }
        // Never let the next Short inherit the previous one's caption selection.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .setPreferredTextLanguage(null)
            .build()
        preparedVideoIds[playerIndex] = videoId
        renderedFirstFrame[playerIndex] = false
        player.playWhenReady = autoPlay
        player.prepare()
    }

    // Key ONLY on page index and current videoId so list appends never restart playback
    LaunchedEffect(pagerState.currentPage, currentShortId) {
        val currentIndex = pagerState.currentPage
        val currentShort = shorts.getOrNull(currentIndex) ?: return@LaunchedEffect

        val activeSlot = currentIndex % 2
        val preloadSlot = (currentIndex + 1) % 2
        val activePlayer = playerPool[activeSlot]
        val preloadPlayer = playerPool[preloadSlot]

        // Pause standby player immediately
        preloadPlayer.playWhenReady = false
        preloadPlayer.pause()

        if (preparedVideoIds[activeSlot] == currentShort.id && activePlayer.playbackState != Player.STATE_IDLE) {
            isResolvingCurrent = false
            if (isPageActive) {
                activePlayer.playWhenReady = true
                activePlayer.play()
            } else {
                activePlayer.playWhenReady = false
                activePlayer.pause()
            }
        } else {
            activePlayer.stop()
            isResolvingCurrent = true
            val resolved = currentShort.resolvedStream?.let { ResolvedShortStream(it, currentShort.resolvedCaptions) }
                ?: streamCache[currentShort.id]
                ?: resolveShortStream(app.okHttpClient, currentShort.id, app.cachedVisitorData, maxStreamHeight, preferredAudioLanguage, streamTargetFps).getOrNull()?.also {
                    streamCache[currentShort.id] = it
                }

            isResolvingCurrent = false

            if (resolved != null) {
                prepareStreamOnPlayer(activeSlot, currentShort.id, resolved, autoPlay = isPageActive)
            } else {
                viewModel.hideBrokenShort(currentShort.id, "resolve-failed")
                return@LaunchedEffect
            }
        }

        // Record watch timestamp without triggering feed reloads
        onShortViewed(currentShort.id)

        // Pre-buffer next Short after giving current video a head start
        val nextShort = shorts.getOrNull(currentIndex + 1) ?: return@LaunchedEffect
        if (preparedVideoIds[preloadSlot] != nextShort.id) {
            delay(120L)
            val nextResolved = nextShort.resolvedStream?.let { ResolvedShortStream(it, nextShort.resolvedCaptions) }
                ?: streamCache[nextShort.id]
                ?: resolveShortStream(app.okHttpClient, nextShort.id, app.cachedVisitorData, maxStreamHeight, preferredAudioLanguage, streamTargetFps).getOrNull()?.also {
                    streamCache[nextShort.id] = it
                }

            if (nextResolved != null) {
                preloadPlayer.stop()
                prepareStreamOnPlayer(preloadSlot, nextShort.id, nextResolved, autoPlay = false)
            }
        }
    }

    // Watchdog: a resolved stream can still fail to produce frames (unsupported codec,
    // audio-only selection, decoder stall), which shows up as a permanently black page.
    // If the visible Short never renders, block it so the pager moves on instead of
    // leaving the user staring at a black screen.
    LaunchedEffect(pagerState.currentPage, currentShortId) {
        val index = pagerState.currentPage
        val short = shorts.getOrNull(index) ?: return@LaunchedEffect
        val slot = index % 2
        val player = playerPool[slot]

        // Wait until the playback effect has handed this Short to its player slot.
        var waited = 0L
        while (preparedVideoIds[slot] != short.id) {
            if (player.playerError != null) {
                viewModel.hideBrokenShort(short.id, "prep-player-error")
                return@LaunchedEffect
            }
            if (!isResumed(lifecycle)) {
                waited = 0L
            } else if (waited >= SHORT_PREPARE_TIMEOUT_MS) {
                viewModel.hideBrokenShort(short.id, "prep-timeout")
                return@LaunchedEffect
            } else {
                waited += SHORT_WATCHDOG_POLL_MS
            }
            delay(SHORT_WATCHDOG_POLL_MS)
        }

        // Then require an actual rendered frame. A player that reports a video track but
        // never paints is exactly the blank-black-screen case.
        waited = 0L
        while (!renderedFirstFrame[slot]) {
            if (player.playerError != null) {
                viewModel.hideBrokenShort(short.id, "render-player-error")
                return@LaunchedEffect
            }
            if (!isResumed(lifecycle)) {
                waited = 0L
            } else if (waited >= SHORT_RENDER_TIMEOUT_MS) {
                viewModel.hideBrokenShort(short.id, "render-timeout")
                return@LaunchedEffect
            } else {
                waited += SHORT_WATCHDOG_POLL_MS
            }
            delay(SHORT_WATCHDOG_POLL_MS)
        }
    }

    // Top-edge spring bounce when pulling down on the first Short
    val topBounceConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (pagerState.currentPage == 0 && available.y > 0f) {
                    scope.launch {
                        val damped = (overscrollY.value + available.y * 0.35f).coerceAtMost(180f)
                        overscrollY.snapTo(damped)
                    }
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (overscrollY.value > 0f) {
                    overscrollY.animateTo(
                        targetValue = 0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                }
                return Velocity.Zero
            }
        }
    }

    VerticalPager(
        state = pagerState,
        beyondViewportPageCount = 1,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .nestedScroll(topBounceConnection)
            .graphicsLayer { translationY = overscrollY.value }
    ) { page ->
        val activeSlot = page % 2
        val slotPlayer = playerPool[activeSlot]
        val isCurrentPage = (page == pagerState.currentPage)
        val pageShort = shorts.getOrNull(page)
        // Keep the loading indicator visible until the player actually paints a frame so a
        // buffering Short never looks like a dead black screen.
        val showSpinner = isCurrentPage && isPageActive &&
            (isResolvingCurrent || slotBuffering[activeSlot] || !renderedFirstFrame[activeSlot])

        // ExoPlayer's surface paints black until the first frame arrives, so a poster has to be
        // drawn ABOVE the PlayerView, not behind it, or every buffer shows as a black screen.
        val hasRenderedFrame = pageShort != null &&
            preparedVideoIds.getOrNull(activeSlot) == pageShort.id &&
            renderedFirstFrame[activeSlot]
        val showPoster = !isCurrentPage || !isPageActive || !hasRenderedFrame

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { showShortsControls = !showShortsControls })
                }
        ) {
            // Only attach a surface while Shorts is the visible tab. This keeps video from
            // bleeding into neighbouring tabs while the screen itself stays mounted, so the
            // pager position and buffered players survive tab switches.
            if (isCurrentPage && isPageActive) {
                AndroidView(
                    factory = { ctx ->
                        @Suppress("InflateParams")
                        (LayoutInflater.from(ctx).inflate(R.layout.view_shorts_player, null) as PlayerView).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            player = slotPlayer
                        }
                    },
                    update = { view ->
                        if (view.player !== slotPlayer) {
                            view.player = slotPlayer
                            if (slotPlayer.playbackState == Player.STATE_READY) {
                                slotPlayer.seekTo(slotPlayer.currentPosition)
                            }
                        }
                        // PlayerView owns subtitleView visibility; only style the view.
                        view.subtitleView?.setStyle(
                            androidx.media3.ui.CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK,
                                null
                            )
                        )
                    },
                    onRelease = { view ->
                        view.player = null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Poster sits above the player surface until this exact Short renders its first
            // frame. During resolution/buffering the user sees the video thumbnail instead of
            // ExoPlayer's black surface; it disappears as soon as real video is on screen.
            if (showPoster) {
                pageShort?.let { posterShort ->
                    AsyncImage(
                        model = "https://i.ytimg.com/vi/${posterShort.id}/hqdefault.jpg",
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                    )
                }
            }

            if (showSpinner) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (isCurrentPage && shortSkipBannerText != null) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Black.copy(alpha = 0.80f),
                    modifier = Modifier
                        .padding(top = 56.dp)
                        .align(Alignment.TopCenter)
                ) {
                    Text(
                        text = shortSkipBannerText.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            if (isCurrentPage && showShortsControls && pageShort != null) {
                val channelId = pageShort.channelId
                val channelLabel = pageShort.channelTitle.ifBlank { "Creator" }
                val isSubscribed = channelId.isNotBlank() && channelId in subscribedChannelIds

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 80.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Tapping the creator opens their in-app Shorts channel page.
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.5f))
                            .clickable {
                                if (channelId.isNotBlank()) {
                                    onOpenChannel(channelId, channelLabel)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = channelLabel.take(1).uppercase(),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "@$channelLabel",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                    }

                    if (channelId.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (isSubscribed) Color.White.copy(alpha = 0.2f)
                                    else MaterialTheme.colorScheme.primary
                                )
                                .clickable { onToggleSubscribe(channelId, channelLabel) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isSubscribed) "Subscribed" else "Subscribe",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            if (isCurrentPage && showShortsControls && pageShort != null) {
                var captionMenuExpanded by remember { mutableStateOf(false) }
                val isLiked = pageShort.id in likedShortIds
                val isDisliked = pageShort.id in dislikedShortIds

                // Like / dislike sit directly above the CC button on the right edge.
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(
                        onClick = { onLikeShort(pageShort) },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ThumbUp,
                            contentDescription = if (isLiked) "Unlike Short" else "Like Short",
                            tint = if (isLiked) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = { onDislikeShort(pageShort) },
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ThumbDown,
                            contentDescription = if (isDisliked) "Remove Dislike" else "Dislike Short",
                            tint = if (isDisliked) MaterialTheme.colorScheme.primary else Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { captionMenuExpanded = true },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.45f))
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ClosedCaption,
                                contentDescription = "Closed Captions",
                                tint = if (currentShortCaption != "Off") MaterialTheme.colorScheme.primary else Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = captionMenuExpanded,
                            onDismissRequest = { captionMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Subtitles / CC") },
                                onClick = {},
                                enabled = false
                            )
                            HorizontalDivider()
                            availableShortCaptions.forEach { cap ->
                                DropdownMenuItem(
                                    text = { Text(cap) },
                                    onClick = {
                                        captionMenuExpanded = false
                                        currentShortCaption = cap
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
