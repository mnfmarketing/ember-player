package app.ember.podcasts

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A followed podcast show. */
@Entity(tableName = "podcasts")
data class PodcastEntity(
    @PrimaryKey val id: String,
    val feedUrl: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val artworkUrl: String = "",
    val artworkPath: String? = null,
    val color: Int? = null,
    val subscribedAt: Long = System.currentTimeMillis(),
    val lastRefreshAt: Long = 0,
    /** Off for new follows; turn on per show. Only new episodes are downloaded. */
    val autoDownload: Boolean = false,
    val notify: Boolean = true,
    val speed: Float = 1f,
    /** Put new episodes at the end of Up Next as they arrive. */
    @ColumnInfo(defaultValue = "0") val autoQueue: Boolean = false,
    /** Archive episodes once they're played. */
    @ColumnInfo(defaultValue = "1") val autoArchive: Boolean = true,
    /** Seconds to skip at the start (intros) and end (outros) of each episode. */
    @ColumnInfo(defaultValue = "0") val skipIntroSec: Int = 0,
    @ColumnInfo(defaultValue = "0") val skipOutroSec: Int = 0,
    /** When on, this show uses its own trim-silence and voice-boost instead of the app's. */
    @ColumnInfo(defaultValue = "0") val customEffects: Boolean = false,
    @ColumnInfo(defaultValue = "0") val trimSilence: Boolean = false,
    @ColumnInfo(defaultValue = "0") val voiceBoost: Boolean = false,
    /** The feed's category, such as Comedy. */
    @ColumnInfo(defaultValue = "") val category: String = "",
)

@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val podcastId: String,
    val guid: String,
    val title: String,
    val description: String = "",
    val audioUrl: String,
    val durationMs: Long = 0,
    val publishedAt: Long = 0,
    val positionMs: Long = 0,
    val played: Boolean = false,
    val playedAt: Long = 0,
    val lastPlayedAt: Long = 0,
    val downloadState: Int = DL_NONE,
    val localPath: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** Podcasting 2.0 chapters file from the feed, if the show publishes one. */
    @ColumnInfo(defaultValue = "") val chaptersUrl: String = "",
    /** 0 = not looked for yet, 1 = looked (chapters saved if found). */
    @ColumnInfo(defaultValue = "0") val chaptersState: Int = 0,
    /** Hidden from lists. Played episodes are archived automatically. */
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
) {
    val isDownloaded: Boolean get() = downloadState == DL_DONE && localPath != null
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    companion object {
        const val DL_NONE = 0
        const val DL_RUNNING = 1
        const val DL_DONE = 2
        const val DL_FAILED = 3
    }
}

/** One chapter of a podcast episode, from the feed's chapters file or the audio itself. */
@Entity(tableName = "episode_chapters", primaryKeys = ["episodeId", "idx"])
data class EpisodeChapterEntity(
    val episodeId: String,
    val idx: Int,
    val title: String,
    val startMs: Long,
    val imageUrl: String? = null,
    val url: String? = null,
)

/** The Up Next queue, in play order. */
@Entity(tableName = "queue")
data class QueueEntity(
    @PrimaryKey val episodeId: String,
    val position: Long,
)

/** A file being downloaded by Android's download manager (podcast episode or Drive audio). */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val downloadId: Long,
    val kind: String,
    val targetId: String,
    val path: String,
    val state: Int = EpisodeEntity.DL_RUNNING,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_EPISODE = "episode"
        const val KIND_DRIVE = "drive"
    }
}

/** Per-show summary for the Shows view. */
data class ShowStats(
    val podcastId: String,
    val newCount: Int,
    val latest: Long,
)

data class EpisodeWithShow(
    val episode: EpisodeEntity,
    val show: PodcastEntity?,
)

@Dao
abstract class MediaDao {
    // Shows
    @Query("SELECT * FROM podcasts ORDER BY title COLLATE NOCASE")
    abstract fun observePodcasts(): Flow<List<PodcastEntity>>

    @Query("SELECT * FROM podcasts")
    abstract suspend fun getPodcasts(): List<PodcastEntity>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    abstract fun observePodcast(id: String): Flow<PodcastEntity?>

    @Query("SELECT * FROM podcasts WHERE id = :id")
    abstract suspend fun getPodcast(id: String): PodcastEntity?

    @Upsert
    abstract suspend fun upsertPodcast(p: PodcastEntity)

    @Update
    abstract suspend fun updatePodcast(p: PodcastEntity)

    @Query("DELETE FROM podcasts WHERE id = :id")
    abstract suspend fun deletePodcastRow(id: String)

    @Query("DELETE FROM episodes WHERE podcastId = :id")
    abstract suspend fun deleteEpisodesFor(id: String)

    @Query("DELETE FROM queue WHERE episodeId IN (SELECT id FROM episodes WHERE podcastId = :id)")
    abstract suspend fun deleteQueueFor(id: String)

    @Transaction
    open suspend fun deletePodcast(id: String) {
        deleteQueueFor(id)
        deleteEpisodeChaptersForShow(id)
        deleteEpisodesFor(id)
        deletePodcastRow(id)
    }

    @Query(
        "SELECT podcastId, SUM(CASE WHEN played = 0 AND archived = 0 AND publishedAt >= :since THEN 1 ELSE 0 END) AS newCount, " +
            "MAX(publishedAt) AS latest FROM episodes GROUP BY podcastId",
    )
    abstract fun observeShowStats(since: Long): Flow<List<ShowStats>>

    // Episodes
    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY publishedAt DESC")
    abstract fun observeEpisodes(podcastId: String): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId ORDER BY publishedAt DESC")
    abstract suspend fun getEpisodes(podcastId: String): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE played = 0 AND archived = 0 AND publishedAt >= :since ORDER BY publishedAt DESC LIMIT :limit")
    abstract fun observeRecentUnplayed(since: Long, limit: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE played = 0 AND archived = 0 ORDER BY publishedAt DESC LIMIT :limit")
    abstract suspend fun getLatestUnplayed(limit: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    abstract fun observeEpisode(id: String): Flow<EpisodeEntity?>

    @Query("SELECT * FROM episodes WHERE id = :id")
    abstract suspend fun getEpisode(id: String): EpisodeEntity?

    @Query("SELECT id FROM episodes WHERE podcastId = :podcastId")
    abstract suspend fun episodeIds(podcastId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertEpisodes(eps: List<EpisodeEntity>)

    @Update
    abstract suspend fun updateEpisode(e: EpisodeEntity)

    @Query("UPDATE episodes SET positionMs = :pos, lastPlayedAt = :at WHERE id = :id")
    abstract suspend fun updateEpisodePosition(id: String, pos: Long, at: Long)

    @Query("UPDATE episodes SET downloadState = :state, localPath = :path WHERE id = :id")
    abstract suspend fun setEpisodeDownload(id: String, state: Int, path: String?)

    @Query("UPDATE episodes SET durationMs = :ms WHERE id = :id AND durationMs = 0")
    abstract suspend fun fillDuration(id: String, ms: Long)

    @Query("SELECT * FROM episodes WHERE downloadState = 2 AND played = 1")
    abstract suspend fun getPlayedDownloads(): List<EpisodeEntity>

    @Query("UPDATE episodes SET archived = :archived WHERE id = :id")
    abstract suspend fun setArchived(id: String, archived: Boolean)

    @Query("SELECT COUNT(*) FROM episodes WHERE podcastId = :podcastId AND downloadState IN (1, 2) AND played = 0 AND archived = 0")
    abstract suspend fun countUnplayedDownloads(podcastId: String): Int

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND downloadState IN (1, 2)")
    abstract suspend fun getDownloadsFor(podcastId: String): List<EpisodeEntity>

    @Query("UPDATE episodes SET chaptersUrl = :url WHERE id = :id AND chaptersUrl != :url")
    abstract suspend fun setChaptersUrl(id: String, url: String)

    @Query("UPDATE episodes SET chaptersState = :state WHERE id = :id")
    abstract suspend fun setChaptersState(id: String, state: Int)

    @Query("SELECT * FROM episodes WHERE downloadState = 2")
    abstract fun observeDownloadedEpisodes(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE played = 1 OR positionMs > 0 OR archived = 1")
    abstract suspend fun getEpisodesWithProgress(): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE podcastId = :podcastId AND guid = :guid LIMIT 1")
    abstract suspend fun getEpisodeByGuid(podcastId: String, guid: String): EpisodeEntity?

    @Query(
        "SELECT * FROM episodes WHERE title LIKE :q OR description LIKE :q ORDER BY publishedAt DESC LIMIT :limit",
    )
    abstract suspend fun searchEpisodes(q: String, limit: Int): List<EpisodeEntity>

    // Episode chapters
    @Query("SELECT * FROM episode_chapters WHERE episodeId = :id ORDER BY idx")
    abstract fun observeEpisodeChapters(id: String): Flow<List<EpisodeChapterEntity>>

    @Query("SELECT * FROM episode_chapters WHERE episodeId = :id ORDER BY idx")
    abstract suspend fun getEpisodeChapters(id: String): List<EpisodeChapterEntity>

    @Query("DELETE FROM episode_chapters WHERE episodeId = :id")
    abstract suspend fun deleteEpisodeChapters(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertEpisodeChapters(rows: List<EpisodeChapterEntity>)

    @Transaction
    open suspend fun replaceEpisodeChapters(id: String, rows: List<EpisodeChapterEntity>) {
        deleteEpisodeChapters(id)
        insertEpisodeChapters(rows)
    }

    @Query("DELETE FROM episode_chapters WHERE episodeId IN (SELECT id FROM episodes WHERE podcastId = :id)")
    abstract suspend fun deleteEpisodeChaptersForShow(id: String)

    // Up Next
    @Query("SELECT e.* FROM episodes e JOIN queue q ON q.episodeId = e.id ORDER BY q.position")
    abstract fun observeQueue(): Flow<List<EpisodeEntity>>

    @Query("SELECT e.* FROM episodes e JOIN queue q ON q.episodeId = e.id ORDER BY q.position")
    abstract suspend fun getQueue(): List<EpisodeEntity>

    @Query("SELECT position FROM queue WHERE episodeId = :id")
    abstract suspend fun queuePosition(id: String): Long?

    @Query("SELECT MAX(position) FROM queue")
    abstract suspend fun maxQueuePosition(): Long?

    @Query("SELECT MIN(position) FROM queue")
    abstract suspend fun minQueuePosition(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putInQueue(q: QueueEntity)

    @Query("DELETE FROM queue WHERE episodeId = :id")
    abstract suspend fun removeFromQueue(id: String)

    @Query("DELETE FROM queue")
    abstract suspend fun clearQueue()

    // Downloads
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertDownload(d: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE downloadId = :id")
    abstract suspend fun getDownload(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE targetId = :targetId ORDER BY createdAt DESC LIMIT 1")
    abstract suspend fun getDownloadFor(targetId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE kind = :kind")
    abstract fun observeDownloads(kind: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE kind = :kind")
    abstract suspend fun getDownloadsOfKind(kind: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE state = 1")
    abstract suspend fun getRunningDownloads(): List<DownloadEntity>

    @Query("UPDATE downloads SET state = :state WHERE downloadId = :id")
    abstract suspend fun setDownloadState(id: Long, state: Int)

    @Query("DELETE FROM downloads WHERE targetId = :targetId")
    abstract suspend fun deleteDownloadsFor(targetId: String)
}

@Database(
    entities = [PodcastEntity::class, EpisodeEntity::class, QueueEntity::class, DownloadEntity::class, EpisodeChapterEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class MediaDatabase : RoomDatabase() {
    abstract fun dao(): MediaDao

    companion object {
        const val NAME = "media.db"

        fun build(context: Context): MediaDatabase {
            app.ember.data.DbSnapshots.beforeOpen(context, NAME)
            return Room.databaseBuilder(context, MediaDatabase::class.java, NAME)
                .addMigrations(*app.ember.data.DbMigrations.media)
                .build()
        }
    }
}
