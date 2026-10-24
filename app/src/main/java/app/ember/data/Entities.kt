package app.ember.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val narrator: String = "",
    val series: String = "",
    val seriesIndex: Float? = null,
    val description: String = "",
    val coverPath: String? = null,
    val coverColor: Int? = null,
    val source: String = SOURCE_LOCAL,
    val sourceUri: String = "",
    val folderId: Long? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    val status: Int = STATUS_NEW,
    val rating: Int = 0,
    val speed: Float = 1f,
    val addedAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long = 0,
    val finishedAt: Long = 0,
    val externalApp: String = "",
    val externalLink: String = "",
    val userEdited: Boolean = false,
    /** A companion PDF (maps, extras): found next to the audio, or attached by hand. "" = removed by hand. */
    val pdfUri: String? = null,
    /** Its own trim silence and voice boost, instead of the app-wide switches. */
    @ColumnInfo(defaultValue = "0") val customEffects: Boolean = false,
    @ColumnInfo(defaultValue = "0") val trimSilence: Boolean = false,
    @ColumnInfo(defaultValue = "0") val voiceBoost: Boolean = false,
) {
    val isExternal: Boolean get() = source == SOURCE_EXTERNAL
    /** The title without filler like ": A Novel" or "(Unabridged)", for covers, rows and players. */
    val displayTitle: String get() = app.ember.util.Names.shortTitle(title)
    val hasPdf: Boolean get() = !pdfUri.isNullOrBlank()

    val progress: Float
        get() = when {
            status == STATUS_FINISHED -> 1f
            durationMs <= 0 -> 0f
            else -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        }

    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)

    companion object {
        const val SOURCE_LOCAL = "local"
        const val SOURCE_DRIVE = "drive"
        const val SOURCE_EXTERNAL = "external"

        const val STATUS_NEW = 0
        const val STATUS_IN_PROGRESS = 1
        const val STATUS_FINISHED = 2

        /** External books store progress as a percentage: positionMs / 100. */
        const val EXTERNAL_SCALE = 100L
    }
}

@Entity(tableName = "tracks", primaryKeys = ["bookId", "idx"])
data class TrackEntity(
    val bookId: String,
    val idx: Int,
    val uri: String,
    val title: String,
    val durationMs: Long,
)

@Entity(tableName = "chapters", primaryKeys = ["bookId", "idx"])
data class ChapterEntity(
    val bookId: String,
    val idx: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val positionMs: Long,
    val note: String = "",
    val chapterTitle: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "daily_listening")
data class DailyListeningEntity(
    @PrimaryKey val day: Long,
    val listenedMs: Long,
)

@Entity(tableName = "shelves")
data class ShelfEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "shelf_books", primaryKeys = ["shelfId", "bookId"])
data class ShelfBookEntity(
    val shelfId: Long,
    val bookId: String,
)

@Entity(tableName = "folders")
data class LibraryFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val name: String,
    val lastScanAt: Long = 0,
)

/**
 * A saved moment: the stretch of audio just before you tapped Clip, with an optional note.
 * [mediaId] is a book id, or "episode:<id>" for a podcast episode.
 */
@Entity(tableName = "clips")
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: String,
    val title: String,
    val subtitle: String = "",
    val startMs: Long,
    val endMs: Long,
    val note: String = "",
    val chapterTitle: String = "",
    val coverPath: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/**
 * One entry in a book's listening log: play, pause, a skip or a jump. [positionMs] is where
 * you were (for skips and jumps, where you were before), so any entry can take you back there.
 * [bookId] is a book id, or "episode:<id>" for a podcast episode.
 */
@Entity(tableName = "listen_log", indices = [Index("bookId")])
data class ListenEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val at: Long,
    val kind: Int,
    val positionMs: Long,
    /** For skips and jumps: where you ended up. */
    val toMs: Long = -1,
    val speed: Float = 1f,
    /** Why it paused: see the REASON_ constants. */
    val reason: Int = 0,
) {
    companion object {
        const val PLAY = 1
        const val PAUSE = 2
        const val SKIP = 3
        const val JUMP = 4

        const val REASON_NONE = 0
        const val REASON_SLEEP = 1
        const val REASON_UNPLUGGED = 2
        const val REASON_OTHER_APP = 3
        const val REASON_FINISHED = 4
    }
}
