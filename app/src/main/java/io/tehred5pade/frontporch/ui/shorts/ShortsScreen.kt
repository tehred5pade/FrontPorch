package io.tehred5pade.frontporch.ui.shorts

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.media3.common.MediaItem
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
import io.tehred5pade.frontporch.ResolvedStream
import io.tehred5pade.frontporch.resolveShortStream
import io.tehred5pade.frontporch.ui.models.ShortItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SHORT_PREPARE_TIMEOUT_MS = 20_000L
private const val SHORT_RENDER_TIMEOUT_MS = 8_000L
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
    preferredAudioLanguage: String = "en"
) {
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

    LaunchedEffect(reloadTick) {
        if (reloadTick == 0) return@LaunchedEffect
        pagerState.scrollToPage(0)
        viewModel.reloadFeed()
    }
    val overscrollY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val streamCache = remember { mutableStateMapOf<String, ResolvedStream>() }

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
                            // Hide whatever this slot was playing. That can be the visible
                            // Short or the preloaded next one; either way it cannot render.
                            preparedVideoIds[slotIndex]?.let { viewModel.hideBrokenShort(it, "player-error") }
                        }
                    })
                }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            playerPool.forEach { it.release() }
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

    // Helper to load a resolved stream into a specific player slot
    fun prepareStreamOnPlayer(playerIndex: Int, videoId: String, stream: ResolvedStream, autoPlay: Boolean) {
        val player = playerPool[playerIndex]
        when (stream) {
            is ResolvedStream.Single -> player.setMediaItem(MediaItem.fromUri(stream.url))
            is ResolvedStream.Adaptive -> {
                val video = mediaSourceFactory.createMediaSource(MediaItem.fromUri(stream.videoUrl))
                val audio = mediaSourceFactory.createMediaSource(MediaItem.fromUri(stream.audioUrl))
                // true, true aligns mismatched start timestamps and clips trailing silence
                player.setMediaSource(MergingMediaSource(true, true, video, audio))
            }
        }
        preparedVideoIds[playerIndex] = videoId
        renderedFirstFrame[playerIndex] = false
        player.playWhenReady = autoPlay
        player.prepare()
    }

    val currentShortId = shorts.getOrNull(pagerState.currentPage)?.id

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
            val resolved = currentShort.resolvedStream
                ?: streamCache[currentShort.id]
                ?: resolveShortStream(app.okHttpClient, currentShort.id, app.cachedVisitorData, maxStreamHeight).getOrNull()?.also {
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
            val nextResolved = nextShort.resolvedStream
                ?: streamCache[nextShort.id]
                ?: resolveShortStream(app.okHttpClient, nextShort.id, app.cachedVisitorData, maxStreamHeight).getOrNull()?.also {
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

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // A YouTube thumbnail sits underneath the player. During a swipe the incoming page
            // is composed before its player is bound, so this prevents a pure-black frame.
            if (pageShort != null) {
                AsyncImage(
                    model = "https://i.ytimg.com/vi/${pageShort.id}/hqdefault.jpg",
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

            // Only attach a surface while Shorts is the visible tab. This keeps video from
            // bleeding into neighbouring tabs while the screen itself stays mounted, so the
            // pager position and buffered players survive tab switches.
            if (isCurrentPage && isPageActive) {
                AndroidView(
                    factory = { ctx ->
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
                    },
                    onRelease = { view ->
                        view.player = null
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (showSpinner) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}
