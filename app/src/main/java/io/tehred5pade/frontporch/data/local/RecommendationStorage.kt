package io.tehred5pade.frontporch.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "short_feedback")
data class ShortFeedbackEntity(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val tags: String,
    val feedbackType: String, // "LIKE" or "DISLIKE"
    val timestamp: Long
)

@Entity(tableName = "channel_affinities")
data class ChannelAffinityEntity(
    @PrimaryKey val channelId: String,
    val channelName: String,
    val score: Float,
    val lastUpdated: Long
)

@Entity(tableName = "tag_affinities")
data class TagAffinityEntity(
    @PrimaryKey val tag: String,
    val score: Float,
    val lastUpdated: Long
)

@Entity(tableName = "seen_shorts")
data class SeenShortEntity(
    @PrimaryKey val videoId: String,
    val timestamp: Long,
    val completionPercent: Double = 0.0,
    val implicitApplied: Boolean = false
)

@Entity(tableName = "affinity_scores")
data class AffinityScoreEntity(
    @PrimaryKey val affinityKey: String,
    val type: String,
    val label: String,
    val score: Double,
    val dislikeCount: Int,
    val muted: Boolean,
    val lastUpdated: Long
)

@Dao
interface RecommendationDao {
    @Query("SELECT * FROM short_feedback WHERE videoId = :videoId LIMIT 1")
    suspend fun getFeedback(videoId: String): ShortFeedbackEntity?

    @Query("SELECT * FROM short_feedback")
    suspend fun getAllFeedback(): List<ShortFeedbackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeedback(feedback: ShortFeedbackEntity)

    @Query("DELETE FROM short_feedback WHERE videoId = :videoId")
    suspend fun deleteFeedback(videoId: String)

    @Query("SELECT * FROM channel_affinities")
    suspend fun getAllChannelAffinities(): List<ChannelAffinityEntity>

    @Query("SELECT * FROM channel_affinities WHERE channelId = :channelId LIMIT 1")
    suspend fun getChannelAffinity(channelId: String): ChannelAffinityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannelAffinity(affinity: ChannelAffinityEntity)

    @Query("SELECT * FROM tag_affinities")
    suspend fun getAllTagAffinities(): List<TagAffinityEntity>

    @Query("SELECT * FROM tag_affinities WHERE tag = :tag LIMIT 1")
    suspend fun getTagAffinity(tag: String): TagAffinityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTagAffinity(affinity: TagAffinityEntity)

    @Query("SELECT * FROM affinity_scores")
    suspend fun getAllAffinities(): List<AffinityScoreEntity>

    @Query("SELECT * FROM affinity_scores WHERE affinityKey = :key LIMIT 1")
    suspend fun getAffinity(key: String): AffinityScoreEntity?

    @Query("SELECT * FROM affinity_scores WHERE type = :type")
    suspend fun getAffinitiesByType(type: String): List<AffinityScoreEntity>

    @Query("SELECT affinityKey FROM affinity_scores WHERE type = 'CHANNEL' AND muted = 1")
    suspend fun getMutedChannelKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAffinity(affinity: AffinityScoreEntity)

    @Query("SELECT videoId FROM seen_shorts")
    suspend fun getAllSeenIds(): List<String>

    @Query("SELECT * FROM seen_shorts")
    suspend fun getAllSeen(): List<SeenShortEntity>

    @Query("SELECT * FROM seen_shorts WHERE videoId = :videoId LIMIT 1")
    suspend fun getSeen(videoId: String): SeenShortEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeen(seen: SeenShortEntity)

    @Query("DELETE FROM seen_shorts WHERE timestamp < :cutoffTimestamp")
    suspend fun pruneSeenOlderThan(cutoffTimestamp: Long)
}
