package io.tehred5pade.frontporch.data.local

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Stores how far the user has watched each video and restores that position when the video
 * is opened again. Writes run on an application-scoped coroutine so they still complete after
 * the player screen has been disposed.
 */
class WatchProgressRepository(
    private val dao: WatchHistoryDao,
    private val downloadedVideoDao: DownloadedVideoDao? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Watched fraction (0..1) per video, keyed by videoId. Drives the progress bar drawn on
     * video thumbnails; videos watched to the end are stored at 0 and therefore omitted.
     */
    val progressFlow: Flow<Map<String, Float>> = dao.observeProgress()
        .map { entries ->
            buildMap {
                for (entry in entries) {
                    if (entry.duration > 0L && entry.playbackPosition > 0L) {
                        put(
                            entry.videoId,
                            (entry.playbackPosition.toFloat() / entry.duration.toFloat())
                                .coerceIn(0f, 1f)
                        )
                    }
                }
            }
        }
        .distinctUntilChanged()

    /**
     * Returns the saved playback position for [videoId], or null when there is nothing to
     * resume from (never watched, or watched to the end).
     */
    suspend fun positionFor(videoId: String): Long? {
        val entry = dao.getForVideo(videoId) ?: return null
        val position = entry.playbackPosition
        if (position <= 0L) return null
        val duration = entry.duration
        if (duration > 0L && position >= duration - COMPLETION_SLACK_MS) return null
        return position
    }

    /**
     * Persists [positionMs] for [videoId]. A position within [COMPLETION_SLACK_MS] of the end
     * is treated as finished and stored as 0 so the next open starts from the beginning.
     */
    fun save(
        videoId: String,
        title: String,
        channelTitle: String,
        durationMs: Long,
        positionMs: Long
    ) {
        if (videoId.length != 11) return

        val safeDuration = durationMs.coerceAtLeast(0L)
        val safePosition = positionMs.coerceAtLeast(0L)
        val positionToStore = if (
            safeDuration > 0L && safePosition >= safeDuration - COMPLETION_SLACK_MS
        ) {
            0L
        } else {
            safePosition
        }

        scope.launch {
            dao.insertHistory(
                WatchHistoryEntity(
                    videoId = videoId,
                    title = title,
                    channelTitle = channelTitle,
                    thumbnailUrl = "https://img.youtube.com/vi/$videoId/0.jpg",
                    duration = safeDuration,
                    playbackPosition = positionToStore,
                    watchedTimestamp = System.currentTimeMillis()
                )
            )
            if (safeDuration > 0L && (safePosition.toFloat() / safeDuration.toFloat() >= 0.90f || positionToStore == 0L)) {
                downloadedVideoDao?.setWatched(videoId, true)
            }
        }
    }

    private companion object {
        const val COMPLETION_SLACK_MS = 5_000L
    }
}
