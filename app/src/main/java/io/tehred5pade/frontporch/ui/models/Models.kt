package io.tehred5pade.frontporch.ui.models

import io.tehred5pade.frontporch.ResolvedStream

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
    val resolvedStream: ResolvedStream? = null
)

data class CommentItem(
    val author: String,
    val text: String,
    val likes: String,
    val timestamp: String
)
