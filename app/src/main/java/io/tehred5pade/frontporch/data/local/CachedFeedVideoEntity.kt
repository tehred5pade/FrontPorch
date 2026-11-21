package io.tehred5pade.frontporch.data.local

import androidx.room.*
import io.tehred5pade.frontporch.data.innertube.FeedVideoItem
import kotlinx.coroutines.flow.Flow

/**
 * Persisted feed entry. The subscription feed renders from this table on launch, then fresh
 * RSS/InnerTube results are merged in behind it.
 */
@Entity(tableName = "cached_feed_videos")
data class CachedFeedVideoEntity(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val publishedEpochMs: Long,
    val views: Long,
    val isShort: Boolean,
    /** Total length in seconds; 0 when unknown (older cached rows). */
    val durationSeconds: Int = 0
)

@Dao
interface CachedFeedVideoDao {
    @Query("SELECT * FROM cached_feed_videos ORDER BY publishedEpochMs DESC LIMIT :limit")
    fun observeFeed(limit: Int): Flow<List<CachedFeedVideoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(videos: List<CachedFeedVideoEntity>)

    @Query("SELECT COUNT(*) FROM cached_feed_videos")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) > 0 FROM cached_feed_videos WHERE channelId = :channelId")
    suspend fun hasVideosForChannel(channelId: String): Boolean

    @Query("SELECT durationSeconds FROM cached_feed_videos WHERE videoId = :videoId LIMIT 1")
    suspend fun getDurationForVideo(videoId: String): Int?

    @Query("DELETE FROM cached_feed_videos WHERE channelId NOT IN (:channelIds)")
    suspend fun deleteChannelsNotIn(channelIds: List<String>)

    @Query("DELETE FROM cached_feed_videos")
    suspend fun clearAll()

    @Query("DELETE FROM cached_feed_videos WHERE videoId IN (:videoIds)")
    suspend fun deleteVideos(videoIds: List<String>)

    @Query(
        "DELETE FROM cached_feed_videos WHERE videoId NOT IN " +
            "(SELECT videoId FROM cached_feed_videos ORDER BY publishedEpochMs DESC LIMIT 500)"
    )
    suspend fun trimOldEntries()
}

fun CachedFeedVideoEntity.toFeedVideoItem(): FeedVideoItem = FeedVideoItem(
    videoId = videoId,
    title = title,
    channelId = channelId,
    channelName = channelName,
    publishedIso = "",
    publishedEpochMs = publishedEpochMs,
    thumbnailUrl = "https://img.youtube.com/vi/$videoId/0.jpg",
    isShort = isShort,
    views = views,
    durationSeconds = durationSeconds
)

fun FeedVideoItem.toCachedEntity(): CachedFeedVideoEntity = CachedFeedVideoEntity(
    videoId = videoId,
    channelId = channelId,
    channelName = channelName,
    title = title,
    publishedEpochMs = publishedEpochMs,
    views = views,
    isShort = isShort,
    durationSeconds = durationSeconds
)
