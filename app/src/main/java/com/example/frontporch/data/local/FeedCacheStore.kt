package com.example.frontporch.data.local

import android.content.Context
import com.example.frontporch.data.innertube.FeedVideoItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Small persistent cache of the subscription feed so the last known videos can be shown
 * immediately on app launch while the network refresh is still running.
 */
class FeedCacheStore(context: Context) {
    private val cacheFile = File(context.filesDir, "subscription_feed_cache.json")

    fun load(): List<FeedVideoItem> = runCatching {
        if (!cacheFile.exists()) return emptyList()

        val text = cacheFile.readText()
        // Version 1 stored a bare list that could still contain upcoming streams. Drop it
        // so upgrading users do not keep stale scheduled videos until the next refresh.
        val root = JSONObject(text)
        if (root.optInt("version", 0) < CURRENT_VERSION) return emptyList()

        val array = root.optJSONArray("videos") ?: return emptyList()
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val videoId = item.optString("videoId").trim()
                if (videoId.length != 11) continue

                add(
                    FeedVideoItem(
                        videoId = videoId,
                        title = item.optString("title"),
                        channelId = item.optString("channelId"),
                        channelName = item.optString("channelName"),
                        publishedIso = item.optString("publishedIso"),
                        publishedEpochMs = item.optLong("publishedEpochMs"),
                        thumbnailUrl = item.optString("thumbnailUrl"),
                        isShort = item.optBoolean("isShort", false),
                        views = item.optLong("views", -1L),
                        durationSeconds = item.optInt("durationSeconds", 0)
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    fun save(videos: List<FeedVideoItem>) {
        runCatching {
            val array = JSONArray()
            videos.forEach { video ->
                array.put(
                    JSONObject().apply {
                        put("videoId", video.videoId)
                        put("title", video.title)
                        put("channelId", video.channelId)
                        put("channelName", video.channelName)
                        put("publishedIso", video.publishedIso)
                        put("publishedEpochMs", video.publishedEpochMs)
                        put("thumbnailUrl", video.thumbnailUrl)
                        put("isShort", video.isShort)
                        put("views", video.views)
                        put("durationSeconds", video.durationSeconds)
                    }
                )
            }
            val root = JSONObject().apply {
                put("version", CURRENT_VERSION)
                put("videos", array)
            }
            cacheFile.writeText(root.toString())
        }
    }

    private companion object {
        const val CURRENT_VERSION = 2
    }
}
