package com.example.frontporch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import com.example.frontporch.MainActivity
import kotlinx.coroutines.*
import java.net.URL

class BackgroundPlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "frontporch_media_panel"
        const val NOTIFICATION_ID = 4102
        const val ACTION_PREV = "com.example.frontporch.ACTION_PREV"
        const val ACTION_REWIND_10 = "com.example.frontporch.ACTION_REWIND_10"
        const val ACTION_TOGGLE_PLAY = "com.example.frontporch.ACTION_TOGGLE_PLAY"
        const val ACTION_FORWARD_10 = "com.example.frontporch.ACTION_FORWARD_10"
        const val ACTION_NEXT = "com.example.frontporch.ACTION_NEXT"
        const val ACTION_STOP = "com.example.frontporch.ACTION_STOP"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSessionCompat

    private var cachedArtworkVideoId: String? = null
    private var cachedArtworkBitmap: Bitmap? = null
    private var lastPostedSecond: Long = -1L
    private var lastPostedPlaying: Boolean? = null

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            // Never pause the video on focus loss. Chromium requests its own focus
            // for the <video> track, so pausing here stopped playback mid-video and
            // it never resumed. Keep the player running; only adjust volume.
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                audioFocusGranted = false
                restorePlaybackVolume()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                PlaybackSessionHolder.activeWebView?.evaluateJavascript(
                    "(function(){ var v=document.querySelector('video'); if(v) v.volume = 0.25; })();",
                    null
                )
                PlaybackSessionHolder.activeExoPlayer?.volume = 0.25f
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                audioFocusGranted = true
                restorePlaybackVolume()
            }
        }
    }

    private fun restorePlaybackVolume() {
        PlaybackSessionHolder.activeWebView?.evaluateJavascript(
            PlaybackSessionHolder.ENSURE_AUDIO_JS,
            null
        )
        PlaybackSessionHolder.activeExoPlayer?.volume = 1.0f
    }

    private fun requestMediaAudioFocus(): Boolean {
        // Chromium manages audio focus for the WebView <video> track itself.
        // Requesting focus here steals it from Chromium and pauses the video.
        if (PlaybackSessionHolder.activeWebView != null) return true
        if (audioFocusGranted) return true
        if (!::audioManager.isInitialized) {
            audioManager = getSystemService(AudioManager::class.java)
        }
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        audioFocusGranted = granted
        return granted
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        PlaybackSessionHolder.onRequestAudioFocus = { requestMediaAudioFocus() }
        requestMediaAudioFocus()

        mediaSession = MediaSessionCompat(this, "FrontPorchMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    requestMediaAudioFocus()
                    PlaybackSessionHolder.setPlaying(true)
                }

                override fun onPause() {
                    PlaybackSessionHolder.setPlaying(false)
                }

                override fun onSeekTo(pos: Long) {
                    PlaybackSessionHolder.seekTo(pos)
                }

                override fun onSkipToPrevious() {
                    if (PlaybackSessionHolder.nowPlaying.value?.hasPrevious == true) {
                        PlaybackSessionHolder.onSkipPrevious?.invoke()
                    } else {
                        PlaybackSessionHolder.seekRelative(-10_000L)
                    }
                }

                override fun onSkipToNext() {
                    if (PlaybackSessionHolder.nowPlaying.value?.hasNext == true) {
                        PlaybackSessionHolder.onSkipNext?.invoke()
                    } else {
                        PlaybackSessionHolder.seekRelative(10_000L)
                    }
                }

                override fun onRewind() {
                    PlaybackSessionHolder.seekRelative(-10_000L)
                }

                override fun onFastForward() {
                    PlaybackSessionHolder.seekRelative(10_000L)
                }

                override fun onStop() {
                    PlaybackSessionHolder.stopAndReleaseAll()
                    stopForegroundAndSelf()
                }
            })
            isActive = true
        }

        serviceScope.launch {
            PlaybackSessionHolder.nowPlaying.collect { meta ->
                if (meta == null) {
                    stopForegroundAndSelf()
                } else {
                    if (cachedArtworkVideoId != meta.videoId) {
                        cachedArtworkVideoId = meta.videoId
                        cachedArtworkBitmap = null
                        loadArtworkAsync(meta)
                    }
                    syncMediaSession(meta, cachedArtworkBitmap)

                    // Avoid spamming NotificationManager every sub-second unless play state or video changed
                    val currentSec = meta.positionMs / 1000L
                    if (lastPostedPlaying != meta.isPlaying || currentSec != lastPostedSecond) {
                        lastPostedPlaying = meta.isPlaying
                        lastPostedSecond = currentSec
                        postForegroundNotification(meta, cachedArtworkBitmap)
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        when (intent?.action) {
            ACTION_PREV -> PlaybackSessionHolder.onSkipPrevious?.invoke()
            ACTION_REWIND_10 -> PlaybackSessionHolder.seekRelative(-10_000L)
            ACTION_TOGGLE_PLAY -> PlaybackSessionHolder.togglePlayPause()
            ACTION_FORWARD_10 -> PlaybackSessionHolder.seekRelative(10_000L)
            ACTION_NEXT -> PlaybackSessionHolder.onSkipNext?.invoke()
            ACTION_STOP -> {
                PlaybackSessionHolder.stopAndReleaseAll()
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            else -> {
                PlaybackSessionHolder.nowPlaying.value?.let { meta ->
                    syncMediaSession(meta, cachedArtworkBitmap)
                    postForegroundNotification(meta, cachedArtworkBitmap)
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (PlaybackSessionHolder.nowPlaying.value == null) {
            stopForegroundAndSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession.isActive = false
        mediaSession.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                if (::audioManager.isInitialized) {
                    audioManager.abandonAudioFocusRequest(request)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            if (::audioManager.isInitialized) {
                audioManager.abandonAudioFocus(audioFocusListener)
            }
        }
        audioFocusGranted = false
        PlaybackSessionHolder.onRequestAudioFocus = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadArtworkAsync(meta: PlaybackSessionHolder.NowPlayingMeta) {
        serviceScope.launch(Dispatchers.IO) {
            val bmp = runCatching {
                URL("https://i.ytimg.com/vi/${meta.videoId}/hqdefault.jpg").openStream().use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()

            if (bmp != null && cachedArtworkVideoId == meta.videoId) {
                withContext(Dispatchers.Main.immediate) {
                    cachedArtworkBitmap = bmp
                    PlaybackSessionHolder.nowPlaying.value?.let { latest ->
                        syncMediaSession(latest, bmp)
                        postForegroundNotification(latest, bmp)
                    }
                }
            }
        }
    }

    private fun syncMediaSession(
        meta: PlaybackSessionHolder.NowPlayingMeta,
        artwork: Bitmap?
    ) {
        // System UI only renders/animates the media scrubber when duration is positive.
        val safeDurationMs = if (meta.durationMs > 0L) meta.durationMs else 1_000L
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, meta.videoId)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, meta.title.ifBlank { "Playing video" })
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, meta.channelName)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, safeDurationMs)

        if (artwork != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, artwork)
        }
        mediaSession.setMetadata(metadataBuilder.build())

        var actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_REWIND or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_STOP

        if (meta.hasPrevious) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (meta.hasNext) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT

        val state = if (meta.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, meta.positionMs, if (meta.isPlaying) 1.0f else 0.0f)
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun postForegroundNotification(
        meta: PlaybackSessionHolder.NowPlayingMeta,
        artwork: Bitmap?
    ) {
        val notification = buildMediaNotification(meta, artwork)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
        )
    }

    private fun buildMediaNotification(
        meta: PlaybackSessionHolder.NowPlayingMeta,
        artwork: Bitmap?
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("resume_video_id", meta.videoId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(artwork)
            .setContentTitle(meta.title.ifBlank { "Playing video" })
            .setContentText(meta.channelName)
            .setSubText("Front Porch")
            .setContentIntent(openAppIntent)
            .setDeleteIntent(servicePendingIntent(ACTION_STOP, 99))
            .setOngoing(meta.isPlaying)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val compactIndices = mutableListOf<Int>()
        var actionIndex = 0

        // Slot 0: Previous Video (if available) or Rewind 10s
        if (meta.hasPrevious) {
            builder.addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                servicePendingIntent(ACTION_PREV, 1)
            )
            compactIndices.add(actionIndex++)
        } else {
            builder.addAction(
                android.R.drawable.ic_media_rew,
                "Rewind 10s",
                servicePendingIntent(ACTION_REWIND_10, 2)
            )
            compactIndices.add(actionIndex++)
        }

        // Slot 1: Play / Pause
        val playPauseIcon = if (meta.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseLabel = if (meta.isPlaying) "Pause" else "Play"
        builder.addAction(
            playPauseIcon,
            playPauseLabel,
            servicePendingIntent(ACTION_TOGGLE_PLAY, 3)
        )
        compactIndices.add(actionIndex++)

        // Slot 2: Next Video (if available) or Forward 10s
        if (meta.hasNext) {
            builder.addAction(
                android.R.drawable.ic_media_next,
                "Next",
                servicePendingIntent(ACTION_NEXT, 4)
            )
            compactIndices.add(actionIndex++)
        } else {
            builder.addAction(
                android.R.drawable.ic_media_ff,
                "Forward 10s",
                servicePendingIntent(ACTION_FORWARD_10, 5)
            )
            compactIndices.add(actionIndex++)
        }

        // Slot 3: Close / Stop
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Stop",
            servicePendingIntent(ACTION_STOP, 6)
        )

        builder.setStyle(
            MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(*compactIndices.take(3).toIntArray())
        )

        return builder.build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, BackgroundPlaybackService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Playback Controls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Lock screen and notification panel media player"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
