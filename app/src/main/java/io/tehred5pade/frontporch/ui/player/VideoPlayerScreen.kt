package io.tehred5pade.frontporch.ui.player

import android.content.res.Configuration
import android.net.Uri
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView
import io.tehred5pade.frontporch.ResolvedStream
import io.tehred5pade.frontporch.data.innertube.*
import io.tehred5pade.frontporch.data.local.WatchProgressRepository
import io.tehred5pade.frontporch.data.sponsorblock.SponsorBlockPreferences
import io.tehred5pade.frontporch.data.sponsorblock.SponsorBlockRepository
import io.tehred5pade.frontporch.data.sponsorblock.SponsorSegment
import io.tehred5pade.frontporch.data.sponsorblock.SponsorSkipBehavior
import io.tehred5pade.frontporch.service.PlaybackSessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

private class PlaybackSnapshot(
    var posMs: Long = 0L,
    var durMs: Long = 0L,
    var canSave: Boolean = false
) {
    var pos: Long
        get() = posMs / 1000L
        set(value) { posMs = value * 1000L }
    var dur: Long
        get() = durMs / 1000L
        set(value) { durMs = value * 1000L }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    videoId: String,
    okHttpClient: OkHttpClient,
    visitorData: String?,
    watchProgressRepository: WatchProgressRepository,
    preferredAudioLanguage: String = "en",
    localVideoPath: String? = null,
    fallbackTitle: String? = null,
    fallbackChannel: String? = null,
    embeddedMode: Boolean = false,
    showExpandedControls: Boolean = true,
    hasPreviousVideo: Boolean = false,
    hasNextVideo: Boolean = false,
    onPreviousVideo: () -> Unit = {},
    onNextVideo: () -> Unit = {},
    onBack: () -> Unit = {},
    onPlaybackEnded: () -> Unit = {},
    playerGesturesEnabled: Boolean = true,
    isControlsVisible: Boolean = false,
    onPullDown: ((Float) -> Unit)? = null,
    onPullDownEnd: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // IO scope that survives Composable disposal on navigate back or app backgrounding
    val persistScope = remember { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    val videoService = remember { InnerTubeVideoService(okHttpClient) }
    val sponsorBlockRepo = remember { SponsorBlockRepository(okHttpClient) }
    val sponsorBlockEnabled by SponsorBlockPreferences.isEnabled.collectAsState()
    val categoryBehaviors by SponsorBlockPreferences.categoryBehaviors.collectAsState()
    var rawSegments by remember(videoId) { mutableStateOf<List<SponsorSegment>>(emptyList()) }
    val skippedUuids = remember(videoId) { mutableStateListOf<String>() }
    var skipBannerText by remember(videoId) { mutableStateOf<String?>(null) }

    // Categories set to "Nothing" are ignored entirely - hidden from markers and never acted on
    val activeSegments = remember(rawSegments, categoryBehaviors, sponsorBlockEnabled) {
        if (!sponsorBlockEnabled) emptyList()
        else rawSegments.filter { seg ->
            (categoryBehaviors[seg.category] ?: seg.category.defaultBehavior) != SponsorSkipBehavior.NOTHING
        }
    }

    var details by remember { mutableStateOf<FullVideoDetails?>(null) }
    var comments by remember { mutableStateOf<List<VideoComment>>(emptyList()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var candidateIndex by remember(videoId) { mutableIntStateOf(0) }
    var useFallbackPlayer by remember(videoId) { mutableStateOf(false) }
    var fallbackTitle by remember(videoId) { mutableStateOf<String?>(null) }
    var fallbackChannel by remember(videoId) { mutableStateOf<String?>(null) }

    // Mutable snapshot holder immune to Compose lambda/DisposableEffect closure capture
    val snapshot = remember(videoId) { PlaybackSnapshot() }

    var watchedPositionMs by remember(videoId) { mutableLongStateOf(0L) }
    var videoDurationMs by remember(videoId) { mutableLongStateOf(0L) }
    var currentSeconds by remember(videoId) { mutableLongStateOf(0L) }

    // Resume gates: block Room progress writes until initial resume timestamp is loaded and applied
    var savedPositionLoaded by remember(videoId) { mutableStateOf(false) }
    var pendingResumeMs by remember(videoId) { mutableLongStateOf(0L) }

    var captionsEnabled by remember(videoId) { mutableStateOf(false) }
    val nowPlaying by PlaybackSessionHolder.nowPlaying.collectAsState()
    var localIsPlaying by remember(videoId) { mutableStateOf(true) }
    val isPlaying = nowPlaying?.isPlaying ?: localIsPlaying

    KeepScreenOnEffect(keepScreenOn = isPlaying)
    var isScrubbing by remember(videoId) { mutableStateOf(false) }
    var scrubFraction by remember(videoId) { mutableFloatStateOf(0f) }
    var webViewRef by remember(videoId) { mutableStateOf<BackgroundWebView?>(null) }
    var showControls by remember(videoId) { mutableStateOf(true) }

    // Auto-hide controls 3 seconds after playing
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(3000L)
            showControls = false
        }
    }

    fun saveSnapshotNow() {
        val pos = snapshot.posMs
        val dur = snapshot.durMs
        if (!snapshot.canSave || dur <= 0L || pos <= 0L) return
        persistScope.launch {
            watchProgressRepository.save(
                videoId = videoId,
                title = details?.title ?: fallbackTitle.orEmpty(),
                channelTitle = details?.channelName ?: fallbackChannel.orEmpty(),
                durationMs = dur,
                positionMs = pos
            )
        }
    }

    // Kept fresh so the long-lived ExoPlayer listener never calls a stale callback.
    val onPlaybackEndedUpdated = rememberUpdatedState(onPlaybackEnded)

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
            .build()
            .apply {
                playWhenReady = true
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .apply {
                        if (preferredAudioLanguage.isNotBlank() && preferredAudioLanguage != "auto") {
                            setPreferredAudioLanguage(preferredAudioLanguage)
                        } else {
                            setPreferredAudioLanguage(null)
                        }
                    }
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            onPlaybackEndedUpdated.value()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val list = details?.candidates.orEmpty()
                        val nextIdx = candidateIndex + 1
                        if (nextIdx < list.size) {
                            candidateIndex = nextIdx
                            list.getOrNull(nextIdx)?.let { candidate ->
                                val activeToken = details?.resolvedVisitorData ?: visitorData
                                val headers = buildMap {
                                    if (!activeToken.isNullOrBlank()) put("X-Goog-Visitor-Id", activeToken)
                                }
                                val httpFactory = DefaultHttpDataSource.Factory()
                                    .setUserAgent(candidate.userAgent)
                                    .setDefaultRequestProperties(headers)
                                    .setAllowCrossProtocolRedirects(true)
                                val sourceFactory = DefaultMediaSourceFactory(httpFactory)

                                when (val s = candidate.stream) {
                                    is ResolvedStream.Single -> {
                                        setMediaSource(sourceFactory.createMediaSource(MediaItem.fromUri(s.url)))
                                    }
                                    is ResolvedStream.Adaptive -> {
                                        val v = sourceFactory.createMediaSource(MediaItem.fromUri(s.videoUrl))
                                        val a = sourceFactory.createMediaSource(MediaItem.fromUri(s.audioUrl))
                                        setMediaSource(MergingMediaSource(true, true, v, a))
                                    }
                                }
                                prepare()
                                play()
                            }
                        } else {
                            val httpCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                            errorMessage = if (httpCode != null) {
                                "Playback HTTP $httpCode (${error.errorCodeName})"
                            } else {
                                "Playback error: ${error.errorCodeName}"
                            }
                        }
                    }
                })
            }
    }

    fun playCandidate(candidate: PlayableCandidate) {
        val activeToken = details?.resolvedVisitorData ?: visitorData
        val headers = buildMap {
            if (!activeToken.isNullOrBlank()) put("X-Goog-Visitor-Id", activeToken)
        }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(candidate.userAgent)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        val sourceFactory = DefaultMediaSourceFactory(httpFactory)

        when (val s = candidate.stream) {
            is ResolvedStream.Single -> {
                exoPlayer.setMediaSource(sourceFactory.createMediaSource(MediaItem.fromUri(s.url)))
            }
            is ResolvedStream.Adaptive -> {
                val v = sourceFactory.createMediaSource(MediaItem.fromUri(s.videoUrl))
                val a = sourceFactory.createMediaSource(MediaItem.fromUri(s.audioUrl))
                exoPlayer.setMediaSource(MergingMediaSource(true, true, v, a))
            }
        }
        exoPlayer.prepare()
        exoPlayer.play()
        PlaybackSessionHolder.attachOfflinePlayer(
            context = context,
            exoPlayer = exoPlayer,
            videoId = videoId,
            title = details?.title ?: fallbackTitle.orEmpty(),
            channelName = details?.channelName ?: fallbackChannel.orEmpty()
        )
    }

    // 1. Load SponsorBlock segments for the active video
    LaunchedEffect(videoId, sponsorBlockEnabled) {
        skippedUuids.clear()
        rawSegments = if (sponsorBlockEnabled) {
            sponsorBlockRepo.getSegments(videoId)
        } else {
            emptyList()
        }

        val saved = watchProgressRepository.positionFor(videoId)
        val startAt = if (saved != null && saved > 3000L) saved else 0L

        snapshot.posMs = startAt
        snapshot.canSave = (startAt == 0L)

        pendingResumeMs = startAt
        watchedPositionMs = startAt
        savedPositionLoaded = true
    }

    // Persist position immediately on app backgrounding, screen locks, or player close
    DisposableEffect(lifecycleOwner, videoId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                saveSnapshotNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            saveSnapshotNow()
        }
    }

    // Keep progress in sync for ExoPlayer and apply initial resume seek
    LaunchedEffect(exoPlayer, videoId, savedPositionLoaded, hasPreviousVideo, hasNextVideo) {
        if (!savedPositionLoaded) return@LaunchedEffect
        while (true) {
            if (!useFallbackPlayer) {
                val duration = exoPlayer.duration
                if (duration > 0L) {
                    videoDurationMs = duration
                    snapshot.durMs = duration
                    if (!isScrubbing) {
                        watchedPositionMs = exoPlayer.currentPosition.coerceIn(0L, duration)
                        snapshot.posMs = watchedPositionMs
                    }
                } else {
                    videoDurationMs = 0L
                    if (!isScrubbing) {
                        watchedPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                        snapshot.posMs = watchedPositionMs
                    }
                }
                localIsPlaying = exoPlayer.isPlaying
                PlaybackSessionHolder.updatePlayState(exoPlayer.isPlaying)
                PlaybackSessionHolder.updateProgress(
                    positionMs = watchedPositionMs,
                    durationMs = videoDurationMs,
                    hasPrevious = hasPreviousVideo,
                    hasNext = hasNextVideo
                )

                if (!snapshot.canSave && exoPlayer.playbackState == Player.STATE_READY) {
                    if (pendingResumeMs > 3000L) {
                        exoPlayer.seekTo(pendingResumeMs)
                    }
                    snapshot.canSave = true
                }

                if (snapshot.canSave && videoDurationMs > 0L && watchedPositionMs > 0L) {
                    saveSnapshotNow()
                }
            }
            delay(250)
        }
    }

    // Seek helper shared by auto-skip and manual-skip actions
    fun jumpToSeconds(targetSec: Float) {
        currentSeconds = targetSec.toLong()
        snapshot.pos = targetSec.toLong()
        val jumpToMs = (targetSec * 1000f).toLong()
        watchedPositionMs = jumpToMs
        if (!useFallbackPlayer) {
            exoPlayer.seekTo(jumpToMs)
        }
        webViewRef?.evaluateJavascript(
            """
            (function() {
                var mp = document.getElementById('movie_player');
                if (mp && typeof mp.seekTo === 'function') mp.seekTo($targetSec, true);
                var v = document.querySelector('video.html5-main-video') || document.querySelector('video');
                if (v && isFinite(v.duration)) v.currentTime = $targetSec;
            })();
            """.trimIndent(),
            null
        )
    }

    fun seekRelativeSeconds(deltaSec: Long) {
        val baseSec = watchedPositionMs / 1000L
        val target = (baseSec + deltaSec).coerceIn(0L, (videoDurationMs / 1000L).coerceAtLeast(0L))
        currentSeconds = target
        snapshot.pos = target
        watchedPositionMs = target * 1000L
        snapshot.canSave = true
        saveSnapshotNow()
        if (!useFallbackPlayer) {
            exoPlayer.seekTo(target * 1000L)
        }
        webViewRef?.evaluateJavascript(
            """
            (function() {
                var mp = document.getElementById('movie_player');
                if (mp && typeof mp.seekTo === 'function') mp.seekTo($target, true);
                var v = document.querySelector('video.html5-main-video') || document.querySelector('video');
                if (v && isFinite(v.duration)) v.currentTime = $target;
            })();
            """.trimIndent(),
            null
        )
    }

    fun evaluateSponsorSegments(currentSec: Float) {
        if (currentSec < 0f || !sponsorBlockEnabled || activeSegments.isEmpty()) return

        // 1. AUTO_SKIP: jump immediately and show a brief toast
        val autoHit = activeSegments.firstOrNull { seg ->
            val mode = categoryBehaviors[seg.category] ?: seg.category.defaultBehavior
            mode == SponsorSkipBehavior.AUTO_SKIP &&
                seg.actionType == "skip" &&
                seg.uuid !in skippedUuids &&
                currentSec >= (seg.startSec - 0.25f) &&
                currentSec < (seg.endSec - 0.10f)
        }
        if (autoHit != null) {
            skippedUuids.add(autoHit.uuid)
            val totalDurSec = if (!useFallbackPlayer && exoPlayer.duration > 0L) exoPlayer.duration / 1000f else (videoDurationMs / 1000f)
            val isEndOfVideo = totalDurSec > 0f && autoHit.endSec >= (totalDurSec - 0.75f)
            val targetSec = if (isEndOfVideo) totalDurSec else autoHit.endSec
            jumpToSeconds(targetSec)
            skipBannerText = "Skipped ${autoHit.category.title}"
            return
        }
    }

    // 2. Fast 200ms watcher to auto-skip YouTube ads and SponsorBlock segments
    LaunchedEffect(videoId, webViewRef, exoPlayer, activeSegments, categoryBehaviors, sponsorBlockEnabled, useFallbackPlayer) {
        while (true) {
            delay(200L)
            if (isScrubbing) continue

            if (useFallbackPlayer) {
                val wv = webViewRef ?: continue
                wv.evaluateJavascript(
                    """
                    (function() {
                        var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        var videos = Array.prototype.slice.call(document.querySelectorAll('video'));
                        var v = document.querySelector('video.html5-main-video') || videos[0];
                        if (!v) return 'NO_VIDEO';

                        // A previous ad may have left a video element sped up. Restore every
                        // element before deciding whether a new ad is actually showing.
                        for (var i = 0; i < videos.length; i++) {
                            if (videos[i].playbackRate > 2.0) {
                                try { videos[i].playbackRate = 1.0; } catch (e) {}
                            }
                        }

                        var adShowing = !!(mp && (mp.classList.contains('ad-showing') || mp.classList.contains('ad-interrupting')));
                        var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-slot button, .ytp-ad-skip-button-container button');
                        var skipActionable = false;
                        if (skipBtn && !skipBtn.disabled && skipBtn.getAttribute('aria-disabled') !== 'true') {
                            var s = window.getComputedStyle(skipBtn);
                            skipActionable = s.display !== 'none' && s.visibility !== 'hidden' && skipBtn.getClientRects().length > 0;
                        }

                        // Only YouTube's own player state may trigger the speed-seek.
                        // Ad containers and skip buttons stay in the DOM when no ad is
                        // running, so treating their mere presence as an ad seeked the
                        // real video to its end and left it stuck at 16x speed.
                        if (adShowing) {
                            if (skipActionable) {
                                try { skipBtn.click(); } catch (e) {}
                            }

                            // Ads use a separate short video element when one exists. Never
                            // seek the main content element - that is what jumped videos
                            // straight to the end.
                            var adV = null;
                            for (var j = 0; j < videos.length; j++) {
                                var candidate = videos[j];
                                if (candidate === v) continue;
                                if (isFinite(candidate.duration) && candidate.duration > 0 && candidate.duration <= 180) {
                                    if (!adV || (!candidate.paused && adV.paused)) adV = candidate;
                                }
                            }
                            if (adV) {
                                try {
                                    adV.muted = true;
                                    adV.playbackRate = 16.0;
                                    adV.currentTime = adV.duration;
                                } catch (e) {}
                            }
                            return 'AD_SKIPPED';
                        }

                        if (skipActionable) {
                            try { skipBtn.click(); } catch (e) {}
                            return 'AD_SKIPPED';
                        }

                        if (v.paused || !isFinite(v.currentTime)) return 'PAUSED';
                        var dur = isFinite(v.duration) ? v.duration : 0;
                        return v.currentTime + '|' + dur;
                    })();
                    """.trimIndent()
                ) { raw ->
                    val cleaned = raw?.trim('"') ?: return@evaluateJavascript
                    val parts = cleaned.split("|")
                    if (parts.size != 2) return@evaluateJavascript

                    val currentSec = parts[0].toFloatOrNull() ?: return@evaluateJavascript
                    val totalDurSec = parts[1].toFloatOrNull() ?: 0f

                    if (sponsorBlockEnabled && activeSegments.isNotEmpty()) {
                        val autoHit = activeSegments.firstOrNull { seg ->
                            val mode = categoryBehaviors[seg.category] ?: seg.category.defaultBehavior
                            mode == SponsorSkipBehavior.AUTO_SKIP &&
                                seg.actionType == "skip" &&
                                seg.uuid !in skippedUuids &&
                                currentSec >= (seg.startSec - 0.25f) &&
                                currentSec < (seg.endSec - 0.10f)
                        }

                        if (autoHit != null) {
                            skippedUuids.add(autoHit.uuid)
                            val isEndOfVideo = totalDurSec > 0f && autoHit.endSec >= (totalDurSec - 0.75f)
                            val targetSec = if (isEndOfVideo) totalDurSec else autoHit.endSec
                            jumpToSeconds(targetSec)
                            skipBannerText = "Skipped ${autoHit.category.title}"
                        }
                    }
                }
            } else {
                evaluateSponsorSegments(exoPlayer.currentPosition / 1000f)
            }
        }
    }

    // Auto-hide the brief "Skipped ..." pill after 2.2 seconds
    LaunchedEffect(skipBannerText) {
        if (skipBannerText != null) {
            delay(2200L)
            skipBannerText = null
        }
    }

    // Poll fallback WebView player for progress, duration & resume seek
    LaunchedEffect(
        useFallbackPlayer,
        webViewRef,
        savedPositionLoaded,
        hasPreviousVideo,
        hasNextVideo
    ) {
        if (!useFallbackPlayer || !savedPositionLoaded) return@LaunchedEffect
        while (true) {
            delay(500)
            val wv = webViewRef ?: continue
            val pendingSeekSec = if (!snapshot.canSave && pendingResumeMs > 3000L) pendingResumeMs / 1000L else -1L

            wv.evaluateJavascript(
                """
                (function() {
                    // Keep YouTube's player unmuted - mobile web can re-mute on
                    // quality/ad transitions without any visible control.
                    var mp = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (mp) {
                        try { if (typeof mp.unMute === 'function') mp.unMute(); } catch (e) {}
                        try { if (typeof mp.setVolume === 'function') mp.setVolume(100); } catch (e) {}
                    }
                    var v = document.querySelector('video.html5-main-video') || document.querySelector('video');
                    if (!v) return '0';
                    if (v.muted) v.muted = false;
                    if (v.volume < 1) v.volume = 1.0;

                    // While an ad is running the video element reports ad duration and
                    // position. Publishing those would overwrite the real video's duration
                    // and corrupt resume/SponsorBlock math, so skip this sample entirely.
                    var adActive = !!(mp && (mp.classList.contains('ad-showing') || mp.classList.contains('ad-interrupting')));
                    if (adActive) return '1|0|0|0|1';

                    var target = $pendingSeekSec;
                    var seekVerified = (target <= 0);

                    if (target > 0) {
                        if (Math.abs(v.currentTime - target) > 3.0) {
                            if (mp && typeof mp.seekTo === 'function') {
                                mp.seekTo(target, true);
                            }
                            if (v.readyState >= 1) {
                                v.currentTime = target;
                            }
                        } else if (!v.seeking && v.readyState >= 3) {
                            seekVerified = true;
                        }
                    }

                    var playing = (!v.paused && !v.ended && v.readyState >= 2) ? 1 : 0;
                    var pos = isFinite(v.currentTime) ? Math.floor(v.currentTime) : 0;
                    var dur = (isFinite(v.duration) && v.duration > 0) ? Math.floor(v.duration) : 0;
                    return playing + '|' + pos + '|' + dur + '|' + (seekVerified ? 1 : 0) + '|0';
                })();
                """.trimIndent()
            ) { raw ->
                val parts = raw?.trim('"')?.split("|") ?: return@evaluateJavascript
                if (parts.size >= 5) {
                    if (parts[4] == "1") return@evaluateJavascript
                    val nowPlaying = parts[0] == "1"
                    val posSec = parts[1].toLongOrNull() ?: 0L
                    val durSec = parts[2].toLongOrNull() ?: 0L
                    val posMs = posSec * 1000L
                    val durMs = durSec * 1000L
                    val seekVerified = parts.getOrNull(3) == "1"

                    if (localIsPlaying != nowPlaying) {
                        localIsPlaying = nowPlaying
                        PlaybackSessionHolder.updatePlayState(nowPlaying)
                    }

                    PlaybackSessionHolder.updateProgress(
                        positionMs = posMs,
                        durationMs = durMs,
                        hasPrevious = hasPreviousVideo,
                        hasNext = hasNextVideo
                    )

                    if (durMs > 0L) {
                        videoDurationMs = durMs
                        snapshot.durMs = durMs
                    }

                    if (seekVerified) {
                        snapshot.canSave = true
                        if (!isScrubbing) {
                            watchedPositionMs = posMs
                            snapshot.posMs = posMs
                        }
                        saveSnapshotNow()
                    }
                }
            }
        }
    }

    DisposableEffect(videoId) {
        onDispose {
            exoPlayer.release()
        }
    }

    // A scrub from the notification/lock screen is applied by PlaybackSessionHolder;
    // mirror it into the in-app player state so the controls and resume position agree.
    DisposableEffect(videoId) {
        val seekCallback: (Long) -> Unit = { ms ->
            currentSeconds = ms / 1000L
            watchedPositionMs = ms
            snapshot.posMs = ms
            snapshot.canSave = true
        }
        PlaybackSessionHolder.onSeekToMs = seekCallback
        onDispose {
            if (PlaybackSessionHolder.onSeekToMs === seekCallback) {
                PlaybackSessionHolder.onSeekToMs = null
            }
        }
    }

    LaunchedEffect(videoId, localVideoPath) {
        if (!localVideoPath.isNullOrBlank()) {
            isLoading = false
            val t = fallbackTitle.orEmpty()
            val c = fallbackChannel.orEmpty()
            val fileUri = runCatching { Uri.parse(localVideoPath) }.getOrNull() ?: Uri.EMPTY
            exoPlayer.setMediaItem(MediaItem.fromUri(fileUri))
            exoPlayer.prepare()
            exoPlayer.play()
            PlaybackSessionHolder.attachOfflinePlayer(
                context = context,
                exoPlayer = exoPlayer,
                videoId = videoId,
                title = t,
                channelName = c
            )
            return@LaunchedEffect
        }

        isLoading = true
        errorMessage = null
        useFallbackPlayer = false

        videoService.loadFullVideo(videoId, visitorData)
            .onSuccess { loaded ->
                details = loaded
                candidateIndex = 0
                loaded.candidates.firstOrNull()?.let { playCandidate(it) }
            }
            .onFailure { err ->
                val msg = err.message.orEmpty()
                if (msg.contains("LOGIN_REQUIRED", ignoreCase = true) ||
                    msg.contains("confirm", ignoreCase = true)
                ) {
                    useFallbackPlayer = true
                    videoService.fetchBasicMetadata(videoId)?.let { (t, c) ->
                        fallbackTitle = t
                        fallbackChannel = c
                    }
                } else {
                    errorMessage = msg.ifEmpty { "Failed to load video" }
                }
            }

        isLoading = false
        comments = videoService.fetchVideoComments(videoId, visitorData)
    }

    // Seamless orientation changes without recreating Activity or WebView
    DisposableEffect(isLandscape) {
        val window = (context as? android.app.Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (isLandscape) {
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    val playerContent: @Composable () -> Unit = {
        PlayerGestureOverlay(
            gesturesEnabled = playerGesturesEnabled,
            isPlaying = localIsPlaying,
            isControlsVisible = showControls,
            videoId = videoId,
            durationMs = videoDurationMs,
            currentPositionMs = watchedPositionMs,
            onTogglePlayPause = { PlaybackSessionHolder.togglePlayPause() },
            onSeekRelative = { deltaMs -> seekRelativeSeconds(deltaMs / 1000L) },
            onSeekToMs = { targetMs -> jumpToSeconds((targetMs / 1000L).toFloat()) },
            onSingleTap = { showControls = !showControls },
            onPullDown = onPullDown,
            onPullDownEnd = onPullDownEnd,
            modifier = (if (isLandscape) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            }).background(Color.Black)
        ) {
            key(videoId) {
                if (useFallbackPlayer) {
                    DisposableEffect(videoId) {
                        onDispose {
                            webViewRef?.let { wv ->
                                runCatching {
                                    (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                                    wv.stopLoading()
                                    wv.loadUrl("about:blank")
                                    wv.destroy()
                                }
                            }
                            webViewRef = null
                        }
                    }

                    AndroidView(
                        factory = { ctx ->
                            BackgroundWebView(ctx).apply {
                                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                setBackgroundColor(android.graphics.Color.BLACK)
                                setInitialScale(100)
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.userAgentString = settings.userAgentString
                                    .replace("; wv", "")
                                    .replace("Version/4.0 ", "")

                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun getDefaultVideoPoster(): android.graphics.Bitmap {
                                        return android.graphics.Bitmap.createBitmap(
                                            1,
                                            1,
                                            android.graphics.Bitmap.Config.ARGB_8888
                                        )
                                    }
                                }
                                webViewClient = object : android.webkit.WebViewClient() {
                                    override fun onPageStarted(
                                        view: android.webkit.WebView?,
                                        url: String?,
                                        favicon: android.graphics.Bitmap?
                                    ) {
                                        val ccVal = if (captionsEnabled) "true" else "false"
                                        view?.evaluateJavascript(
                                            """
                                            try {
                                                var exp = Date.now() + 2592000000;
                                                localStorage.setItem('yt-player-quality', JSON.stringify({
                                                    data: JSON.stringify({ quality: 720, previousQuality: 720 }),
                                                    expiration: exp,
                                                    creation: Date.now()
                                                }));
                                                localStorage.setItem('yt-player-caption-display-settings', JSON.stringify({
                                                    data: JSON.stringify({ visibility: $ccVal }),
                                                    expiration: exp,
                                                    creation: Date.now()
                                                }));
                                            } catch (e) {}
                                            """.trimIndent(),
                                            null
                                        )
                                    }

                                    override fun onPageCommitVisible(view: android.webkit.WebView?, url: String?) {
                                        injectCleanPlayerJs(view)
                                        applyClosedCaptionsJs(view, captionsEnabled)
                                    }

                                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                        injectCleanPlayerJs(view)
                                        applyClosedCaptionsJs(view, captionsEnabled)
                                    }
                                }

                                val startSec = pendingResumeMs / 1000L
                                val watchUrl = if (startSec > 0L) {
                                    "https://m.youtube.com/watch?v=$videoId&t=${startSec}s"
                                } else {
                                    "https://m.youtube.com/watch?v=$videoId"
                                }
                                loadUrl(watchUrl)
                                webViewRef = this
                                PlaybackSessionHolder.attachWebPlayer(
                                    context = ctx,
                                    webView = this,
                                    videoId = videoId,
                                    title = details?.title ?: fallbackTitle.orEmpty(),
                                    channelName = details?.channelName ?: fallbackChannel.orEmpty(),
                                    hasPrevious = hasPreviousVideo,
                                    hasNext = hasNextVideo
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                // The app draws its own VideoPlayerControlsOverlay on top of
                                // this surface. ExoPlayer's built-in controller would render a
                                // second, duplicate set of controls (play/pause, seek, next...).
                                useController = false
                                player = exoPlayer
                                keepScreenOn = true
                            }
                        },
                        update = { playerView ->
                            playerView.keepScreenOn = isPlaying
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (isLoading) {
                CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Custom Scrubbable Player Controls Bar Overlay
            val durationSeconds = videoDurationMs / 1000L
            val displayedSeconds = if (isScrubbing && durationSeconds > 0L) {
                (scrubFraction * durationSeconds).toLong()
            } else {
                watchedPositionMs / 1000L
            }
            val sliderValue = if (isScrubbing) {
                scrubFraction
            } else if (durationSeconds > 0L) {
                (watchedPositionMs.toFloat() / videoDurationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }

            // Optional floating pill notification when a segment is skipped
            if (showExpandedControls && skipBannerText != null) {
                Surface(
                    color = Color.Black.copy(alpha = 0.80f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 14.dp, bottom = 54.dp)
                ) {
                    Text(
                        text = skipBannerText.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }



            if (!showControls && durationSeconds > 0L && showExpandedControls) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                ) {
                    if (sponsorBlockEnabled && durationSeconds > 0L && activeSegments.isNotEmpty()) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val totalSec = durationSeconds.toFloat()
                            activeSegments.forEach { seg ->
                                val startFrac = (seg.startSec / totalSec).coerceIn(0f, 1f)
                                val endFrac = (seg.endSec / totalSec).coerceIn(startFrac, 1f)
                                val leftPx = startFrac * size.width
                                val widthPx = ((endFrac - startFrac) * size.width).coerceAtLeast(2.dp.toPx())

                                drawRect(
                                    color = seg.category.badgeColor,
                                    topLeft = Offset(leftPx, 0f),
                                    size = Size(widthPx, size.height)
                                )
                            }
                        }
                    }
                    LinearProgressIndicator(
                        progress = { sliderValue },
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xFFE53935),
                        trackColor = Color.White.copy(alpha = 0.2f)
                    )
                }
            }

            if (showExpandedControls) {
                VideoPlayerControlsOverlay(
                    visible = showControls,
                    isPlaying = isPlaying,
                    currentSeconds = watchedPositionMs / 1000L,
                    durationSeconds = durationSeconds,
                    hasPreviousVideo = hasPreviousVideo,
                    hasNextVideo = hasNextVideo,
                    sponsorSegments = if (sponsorBlockEnabled) activeSegments else emptyList(),
                    onToggleControls = { showControls = !showControls },
                    onPreviousVideo = onPreviousVideo,
                    onReplay10 = { seekRelativeSeconds(-10L) },
                    onTogglePlayPause = { PlaybackSessionHolder.togglePlayPause() },
                    onForward10 = { seekRelativeSeconds(10L) },
                    onNextVideo = onNextVideo,
                    onSeekToSeconds = { targetSec ->
                        currentSeconds = targetSec
                        watchedPositionMs = targetSec * 1000L
                        snapshot.pos = targetSec
                        snapshot.canSave = true
                        saveSnapshotNow()
                        if (!useFallbackPlayer) {
                            exoPlayer.seekTo(targetSec * 1000L)
                        }
                        webViewRef?.evaluateJavascript(
                            """
                            (function() {
                                var mp = document.getElementById('movie_player');
                                if (mp && typeof mp.seekTo === 'function') mp.seekTo($targetSec, true);
                                var v = document.querySelector('video.html5-main-video') || document.querySelector('video');
                                if (v && isFinite(v.duration)) v.currentTime = $targetSec;
                            })();
                            """.trimIndent(),
                            null
                        )
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

        }
    }

    if (embeddedMode) {
        playerContent()
    } else {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                playerContent()

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                if (!isLandscape) {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    ) {
                        item {
                            val displayTitle = details?.title ?: fallbackTitle ?: "Loading..."
                            val displayChannel = details?.channelName ?: fallbackChannel.orEmpty()

                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            if (displayChannel.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = displayChannel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Comments (${comments.size})",
                                style = MaterialTheme.typography.titleSmall
                            )
                        }

                        items(items = comments, key = { it.id }) { comment ->
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = comment.author,
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = comment.text,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (comment.likeCount.isNotEmpty() || comment.publishedTime.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = listOf(
                                            if (comment.likeCount.isNotEmpty()) "${comment.likeCount} likes" else "",
                                            comment.publishedTime
                                        ).filter { it.isNotEmpty() }.joinToString(" • "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun applyClosedCaptionsJs(webView: android.webkit.WebView?, enabled: Boolean) {
    webView?.evaluateJavascript(
        """
        (function() {
            var want = $enabled;
            var ccBtn = document.querySelector('.ytp-subtitles-button');
            if (ccBtn) {
                var pressed = ccBtn.getAttribute('aria-pressed') === 'true';
                if (pressed !== want) ccBtn.click();
            }
            var v = document.querySelector('video.html5-main-video') || document.querySelector('video');
            if (v && v.textTracks) {
                for (var i = 0; i < v.textTracks.length; i++) {
                    v.textTracks[i].mode = want ? 'showing' : 'disabled';
                }
            }
        })();
        """.trimIndent(),
        null
    )
}

private fun formatDuration(totalSeconds: Long): String {
    return formatDurationSeconds(totalSeconds.toInt())
}

private fun injectCleanPlayerJs(webView: android.webkit.WebView?) {
    webView?.evaluateJavascript(
        """
        (function() {
            try {
                Object.defineProperty(document, 'hidden', { get: function() { return false; } });
                Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; } });
                window.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                document.addEventListener('visibilitychange', function(e) { e.stopImmediatePropagation(); }, true);
                window.addEventListener('resize', function(e) { e.stopImmediatePropagation(); }, true);
            } catch (e) {}

            var style = document.getElementById('fp-clean-style');
            if (!style && document.head) {
                style = document.createElement('style');
                style.id = 'fp-clean-style';
                document.head.appendChild(style);
            }
            if (style) {
                style.textContent = `
                    ytm-mobile-topbar-renderer,
                    #header-bar,
                    .watch-below-the-player,
                    ytm-slim-video-metadata-section-renderer,
                    ytm-item-section-renderer,
                    ytm-single-column-watch-next-results-renderer > ytm-item-section-renderer,
                    /* Suppress all YouTube mobile & desktop player buttons, next/prev, and overlays */
                    ytm-custom-control,
                    .player-controls-background-container,
                    .player-controls-content,
                    .player-controls-top,
                    .player-controls-middle,
                    .player-controls-bottom,
                    .ytp-chrome-top,
                    .ytp-chrome-bottom,
                    .ytp-gradient-top,
                    .ytp-gradient-bottom,
                    .ytp-large-play-button,
                    .ytp-cued-thumbnail-overlay,
                    .ytp-spinner,
                    .ytp-ce-element,
                    .html5-endscreen,
                    .ytp-autonav-endscreen-upnext-container,
                    .ytp-upnext,
                    .ytp-videowall-still,
                    .ytp-pause-overlay,
                    ytm-autonav-overlay-renderer,
                    .ytm-autonav-bar,
                    .ytp-unmute,
                    .ytp-mute-button,
                    video::-webkit-media-controls-start-playback-button {
                        display: none !important;
                        opacity: 0 !important;
                        visibility: hidden !important;
                        pointer-events: none !important;
                    }
                    /* Full-screen ambient canvas/blur behind the player throttles the
                       decoder and causes audio to lead the video. */
                    ytm-cinematic-container-renderer,
                    #cinematics,
                    .ytp-ambient-light,
                    canvas.ytp-ambient-canvas {
                        display: none !important;
                        visibility: hidden !important;
                    }
                    html, body, ytm-app {
                        background: #000 !important;
                        overflow: hidden !important;
                        margin: 0 !important;
                        padding: 0 !important;
                        width: 100% !important;
                        height: 100% !important;
                    }
                    #player-container-id,
                    #player,
                    #movie_player,
                    .html5-video-container {
                        position: fixed !important;
                        top: 0 !important;
                        left: 0 !important;
                        right: 0 !important;
                        bottom: 0 !important;
                        width: 100% !important;
                        height: 100% !important;
                        margin: 0 !important;
                        padding: 0 !important;
                        z-index: 9999 !important;
                    }
                    video,
                    video.html5-main-video {
                        position: fixed !important;
                        top: 0 !important;
                        left: 0 !important;
                        width: 100% !important;
                        height: 100% !important;
                        max-width: 100% !important;
                        max-height: 100% !important;
                        object-fit: contain !important;
                        transform: none !important;
                    }
                `;
            }

            // Unmute through YouTube's own player API - setting the DOM flag alone
            // is overwritten by the player's internal mute state on mobile web.
            var mp = document.getElementById('movie_player');
            if (mp) {
                try { if (typeof mp.unMute === 'function') mp.unMute(); } catch (e) {}
                try { if (typeof mp.setVolume === 'function') mp.setVolume(100); } catch (e) {}
            }
            var video = document.querySelector('video.html5-main-video') || document.querySelector('video');
            if (video) {
                video.controls = false;
                video.muted = false;
                video.volume = 1.0;
            }
        })();
        """.trimIndent(),
        null
    )
}
