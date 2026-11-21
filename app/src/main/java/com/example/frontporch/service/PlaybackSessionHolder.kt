package com.example.frontporch.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import com.example.frontporch.ui.player.BackgroundWebView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlaybackSessionHolder {
    /**
     * YouTube's mobile player starts muted for autoplay. Setting the DOM video's
     * `muted` flag alone is overridden by the player's own state, so we also call
     * its internal unMute/setVolume API. This is safe to run repeatedly and does
     * not touch the (CSS-hidden) unmute button, so it cannot flicker.
     */
    const val ENSURE_AUDIO_JS = """
    (function() {
        var mp = document.getElementById('movie_player');
        if (mp) {
            try { if (typeof mp.unMute === 'function') mp.unMute(); } catch (e) {}
            try { if (typeof mp.setVolume === 'function') mp.setVolume(100); } catch (e) {}
        }
        var v = document.querySelector('video.html5-main-video') || document.querySelector('video');
        if (v) { v.muted = false; v.volume = 1.0; }
    })();
    """

    data class NowPlayingMeta(
        val videoId: String,
        val title: String,
        val channelName: String,
        val isPlaying: Boolean,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val hasPrevious: Boolean = false,
        val hasNext: Boolean = false
    )

    private val _nowPlaying = MutableStateFlow<NowPlayingMeta?>(null)
    val nowPlaying: StateFlow<NowPlayingMeta?> = _nowPlaying.asStateFlow()

    var activeWebView: BackgroundWebView? = null
    var activeExoPlayer: ExoPlayer? = null

    // Registered by MainActivity / PlaybackQueueController for notification Prev/Next taps
    var onSkipPrevious: (() -> Unit)? = null
    var onSkipNext: (() -> Unit)? = null
    var onSeekToMs: ((Long) -> Unit)? = null
    var onRequestAudioFocus: (() -> Unit)? = null

    fun attachWebPlayer(
        context: Context,
        webView: BackgroundWebView,
        videoId: String,
        title: String,
        channelName: String,
        hasPrevious: Boolean = false,
        hasNext: Boolean = false
    ) {
        if (activeWebView !== webView) {
            releaseWebViewOnly()
        }
        releaseExoPlayerOnly()
        activeWebView = webView
        _nowPlaying.value = NowPlayingMeta(
            videoId = videoId,
            title = title,
            channelName = channelName,
            isPlaying = true,
            hasPrevious = hasPrevious,
            hasNext = hasNext
        )
        startForegroundService(context)
    }

    fun attachOfflinePlayer(
        context: Context,
        exoPlayer: ExoPlayer,
        videoId: String,
        title: String,
        channelName: String
    ) {
        releaseWebViewOnly()
        if (activeExoPlayer !== exoPlayer) {
            releaseExoPlayerOnly()
        }
        activeExoPlayer = exoPlayer
        _nowPlaying.value = NowPlayingMeta(
            videoId = videoId,
            title = title,
            channelName = channelName,
            isPlaying = true
        )
        startForegroundService(context)
    }

    fun updatePlayState(isPlaying: Boolean) {
        _nowPlaying.value = _nowPlaying.value?.copy(isPlaying = isPlaying)
    }

    fun updateProgress(positionMs: Long, durationMs: Long, hasPrevious: Boolean, hasNext: Boolean) {
        val current = _nowPlaying.value ?: return
        _nowPlaying.value = current.copy(
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L),
            hasPrevious = hasPrevious,
            hasNext = hasNext
        )
    }

    fun togglePlayPause() {
        val current = _nowPlaying.value ?: return
        setPlaying(!current.isPlaying)
    }

    fun setPlaying(play: Boolean) {
        updatePlayState(play)
        if (play) {
            onRequestAudioFocus?.invoke()
        }
        activeExoPlayer?.let { player ->
            if (play) player.play() else player.pause()
        }
        activeWebView?.let { wv ->
            if (play) {
                wv.keepAliveInBackground = true
                wv.onResume()
                wv.resumeTimers()
                wv.evaluateJavascript(ENSURE_AUDIO_JS, null)
                wv.evaluateJavascript(
                    """
                    (function() {
                        var v = document.querySelector('video.html5-main-video') || document.querySelector('video');
                        if (!v) return;
                        var p = v.play();
                        if (p && typeof p.catch === 'function') {
                            p.catch(function() {
                                var mp = document.getElementById('movie_player');
                                if (mp && typeof mp.playVideo === 'function') mp.playVideo();
                            });
                        }
                    })();
                    """.trimIndent(),
                    null
                )
            } else {
                wv.evaluateJavascript(
                    "(function(){ var v=document.querySelector('video.html5-main-video') || document.querySelector('video'); if(v) v.pause(); })();",
                    null
                )
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val clampedMs = positionMs.coerceAtLeast(0L)
        val current = _nowPlaying.value
        if (current != null) {
            _nowPlaying.value = current.copy(positionMs = clampedMs)
        }
        activeExoPlayer?.seekTo(clampedMs)
        val targetSec = clampedMs / 1000.0
        activeWebView?.evaluateJavascript(
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
        onSeekToMs?.invoke(clampedMs)
    }

    fun seekRelative(deltaMs: Long) {
        val current = _nowPlaying.value ?: return
        val maxDur = if (current.durationMs > 0L) current.durationMs else Long.MAX_VALUE
        seekTo((current.positionMs + deltaMs).coerceIn(0L, maxDur))
    }

    fun stopAndReleaseAll() {
        _nowPlaying.value = null
        releaseWebViewOnly()
        releaseExoPlayerOnly()
    }

    private fun releaseWebViewOnly() {
        activeWebView?.let { wv ->
            wv.keepAliveInBackground = false
            runCatching {
                (wv.parent as? android.view.ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.destroy()
            }
        }
        activeWebView = null
    }

    private fun releaseExoPlayerOnly() {
        runCatching { activeExoPlayer?.release() }
        activeExoPlayer = null
    }

    private fun startForegroundService(context: Context) {
        val intent = Intent(context.applicationContext, BackgroundPlaybackService::class.java)
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }
}
