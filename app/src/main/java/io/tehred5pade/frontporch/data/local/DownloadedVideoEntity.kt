package io.tehred5pade.frontporch.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "downloaded_videos")
data class DownloadedVideoEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelName: String,
    val localVideoPath: String,
    val localThumbnailPath: String?,
    val fileSizeBytes: Long,
    val downloadedAtMs: Long = System.currentTimeMillis(),
    val durationSeconds: Int = 0,
    val isShort: Boolean = false,
    val isWatched: Boolean = false
)

@Dao
interface DownloadedVideoDao {
    @Query("SELECT * FROM downloaded_videos ORDER BY downloadedAtMs DESC")
    fun observeAll(): Flow<List<DownloadedVideoEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: DownloadedVideoEntity)

    @Delete
    suspend fun delete(item: DownloadedVideoEntity)

    @Query("DELETE FROM downloaded_videos WHERE videoId = :videoId")
    suspend fun deleteById(videoId: String)

    @Query("UPDATE downloaded_videos SET isWatched = :watched WHERE videoId = :videoId")
    suspend fun setWatched(videoId: String, watched: Boolean = true)
}
