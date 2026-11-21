package com.example.frontporch.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val channelId: String, // 24-char "UC..." ID
    val name: String,
    val url: String = "https://www.youtube.com/channel/$channelId",
    val importedAt: Long = System.currentTimeMillis(),
    val thumbnailUrl: String = "",
    val subscriberCountText: String = ""
) {
    val channelTitle: String get() = name
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY name ASC")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions")
    fun getAllSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions")
    suspend fun getAllOnce(): List<SubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<SubscriptionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: SubscriptionEntity)

    @Query("UPDATE subscriptions SET thumbnailUrl = :thumbnailUrl WHERE channelId = :channelId")
    suspend fun updateThumbnail(channelId: String, thumbnailUrl: String)

    @Query("DELETE FROM subscriptions WHERE channelId = :channelId")
    suspend fun deleteSubscription(channelId: String)

    @Query("DELETE FROM subscriptions")
    suspend fun clearAll()
}
