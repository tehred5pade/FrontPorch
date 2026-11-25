package io.tehred5pade.frontporch.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.tehred5pade.frontporch.data.db.SubscriptionDao
import io.tehred5pade.frontporch.data.db.SubscriptionEntity
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "watch_history")
data class WatchHistoryEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelTitle: String,
    val thumbnailUrl: String,
    val duration: Long,
    val playbackPosition: Long,
    val watchedTimestamp: Long
)

/** Lightweight projection used to draw watched-progress bars on video thumbnails. */
data class WatchProgressEntry(
    val videoId: String,
    val playbackPosition: Long,
    val duration: Long
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val channelTitle: String,
    val thumbnailUrl: String,
    val localUri: String,
    val fileSize: Long,
    val downloadTimestamp: Long
)

@Dao
interface WatchHistoryDao {
    @Query("SELECT * FROM watch_history ORDER BY watchedTimestamp DESC")
    fun getHistory(): Flow<List<WatchHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: WatchHistoryEntity)

    @Query("SELECT videoId FROM watch_history")
    suspend fun getAllWatchedIds(): List<String>

    @Query("SELECT * FROM watch_history WHERE videoId = :videoId LIMIT 1")
    suspend fun getForVideo(videoId: String): WatchHistoryEntity?

    @Query(
        "SELECT videoId, playbackPosition, duration FROM watch_history " +
            "WHERE playbackPosition > 0 AND duration > 0"
    )
    fun observeProgress(): Flow<List<WatchProgressEntry>>
}

@Dao
interface DownloadsDao {
    @Query("SELECT * FROM downloads ORDER BY downloadTimestamp DESC")
    fun getAllDownloads(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownload(download: DownloadEntity)

    @Query("DELETE FROM downloads WHERE videoId = :videoId")
    suspend fun deleteDownload(videoId: String)
}

@Database(
    entities = [
        SubscriptionEntity::class,
        WatchHistoryEntity::class,
        DownloadEntity::class,
        DownloadedVideoEntity::class,
        CachedFeedVideoEntity::class,
        ShortFeedbackEntity::class,
        ChannelAffinityEntity::class,
        TagAffinityEntity::class,
        SeenShortEntity::class,
        AffinityScoreEntity::class
    ],
    version = 9,
    exportSchema = false
)
abstract class FrontPorchDatabase : RoomDatabase() {
    abstract fun subscriptionsDao(): SubscriptionDao
    abstract fun watchHistoryDao(): WatchHistoryDao
    abstract fun downloadsDao(): DownloadsDao
    abstract fun downloadedVideoDao(): DownloadedVideoDao
    abstract fun cachedFeedVideoDao(): CachedFeedVideoDao
    abstract fun recommendationDao(): RecommendationDao

    companion object {
        @Volatile
        private var INSTANCE: FrontPorchDatabase? = null

        /**
         * Adds the Room-backed subscription feed cache. Kept as a real migration so the
         * existing subscriptions, watch history and downloads are preserved on upgrade.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `cached_feed_videos` (
                        `videoId` TEXT NOT NULL,
                        `channelId` TEXT NOT NULL,
                        `channelName` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `publishedEpochMs` INTEGER NOT NULL,
                        `views` INTEGER NOT NULL,
                        `isShort` INTEGER NOT NULL,
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /** Adds the cached video length used for the thumbnail duration badge. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `cached_feed_videos` " +
                        "ADD COLUMN `durationSeconds` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** Adds durationSeconds and isShort columns to downloaded_videos. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `downloaded_videos` " +
                        "ADD COLUMN `durationSeconds` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE `downloaded_videos` " +
                        "ADD COLUMN `isShort` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** Removes empty/corrupt downloads saved by earlier versions (shown as 0.0 MB). */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM `downloaded_videos` WHERE `fileSizeBytes` < 4096")
            }
        }

        /** Adds isWatched column to downloaded_videos. */
        private val MYNIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `downloaded_videos` " +
                        "ADD COLUMN `isWatched` INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `short_feedback` (
                        `videoId` TEXT NOT NULL,
                        `channelId` TEXT NOT NULL,
                        `channelName` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `tags` TEXT NOT NULL,
                        `feedbackType` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `channel_affinities` (
                        `channelId` TEXT NOT NULL,
                        `channelName` TEXT NOT NULL,
                        `score` REAL NOT NULL,
                        `lastUpdated` INTEGER NOT NULL,
                        PRIMARY KEY(`channelId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `tag_affinities` (
                        `tag` TEXT NOT NULL,
                        `score` REAL NOT NULL,
                        `lastUpdated` INTEGER NOT NULL,
                        PRIMARY KEY(`tag`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `seen_shorts` (
                        `videoId` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `completionPercent` REAL NOT NULL DEFAULT 0.0,
                        `implicitApplied` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`videoId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `affinity_scores` (
                        `affinityKey` TEXT NOT NULL,
                        `type` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `score` REAL NOT NULL,
                        `dislikeCount` INTEGER NOT NULL,
                        `muted` INTEGER NOT NULL,
                        `lastUpdated` INTEGER NOT NULL,
                        PRIMARY KEY(`affinityKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): FrontPorchDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FrontPorchDatabase::class.java,
                    "front_porch_database"
                )
                .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_8_9)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
