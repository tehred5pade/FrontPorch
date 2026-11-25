package io.tehred5pade.frontporch.ui.models

import io.tehred5pade.frontporch.ResolvedStream
import io.tehred5pade.frontporch.data.innertube.CaptionTrack

data class VideoItem(
    val id: String,
    val title: String,
    val channelTitle: String,
    val channelId: String,
    val thumbnailUrl: String,
    val viewCountText: String,
    val publishedText: String,
    val durationText: String,
    val isWatched: Boolean = false,
    val localUri: String? = null
)

data class ShortItem(
    val id: String,
    val title: String,
    val channelTitle: String = "YouTube Creator",
    val videoUrl: String = "",
    val likesCount: String = "",
    val resolvedStream: ResolvedStream? = null,
    val resolvedCaptions: List<CaptionTrack> = emptyList(),
    val channelId: String = "",
    /** Runtime in seconds. 0 means YouTube did not expose a length for this item. */
    val durationSeconds: Int = 0,
    /**
     * True when the source explicitly identified the item as short-form (reel endpoint,
     * channel Shorts tab, or reel watch sequence). Shorts validity accepts this as an
     * alternative to a measured duration.
     */
    val isShortForm: Boolean = false
)

data class CommentItem(
    val author: String,
    val text: String,
    val likes: String,
    val timestamp: String
)
