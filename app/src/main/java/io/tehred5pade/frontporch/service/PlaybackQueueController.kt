package io.tehred5pade.frontporch.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class QueueVideoItem(
    val videoId: String,
    val title: String,
    val channelName: String
)

class PlaybackQueueController {
    var queue by mutableStateOf<List<QueueVideoItem>>(emptyList())
        private set

    var currentIndex by mutableIntStateOf(-1)
        private set

    val currentVideo: QueueVideoItem?
        get() = queue.getOrNull(currentIndex)

    val hasPrevious: Boolean
        get() = currentIndex > 0

    val hasNext: Boolean
        get() = currentIndex >= 0 && currentIndex < queue.lastIndex

    fun openVideo(item: QueueVideoItem, contextList: List<QueueVideoItem> = emptyList()) {
        val list = if (contextList.any { it.videoId == item.videoId }) {
            contextList
        } else {
            listOf(item)
        }
        queue = list
        currentIndex = list.indexOfFirst { it.videoId == item.videoId }.coerceAtLeast(0)
    }

    fun playPrevious(): QueueVideoItem? {
        if (!hasPrevious) return null
        currentIndex -= 1
        return currentVideo
    }

    fun playNext(): QueueVideoItem? {
        if (!hasNext) return null
        currentIndex += 1
        return currentVideo
    }

    fun clear() {
        queue = emptyList()
        currentIndex = -1
    }
}
