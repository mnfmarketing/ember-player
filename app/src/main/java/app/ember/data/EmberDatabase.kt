package app.ember.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryDao {

    // Books
    @Query("SELECT * FROM books ORDER BY lastPlayedAt DESC, addedAt DESC")
    abstract fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY lastPlayedAt DESC, addedAt DESC")
    abstract suspend fun getBooks(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id")
    abstract fun observeBook(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id")
    abstract suspend fun getBook(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE folderId = :folderId")
    abstract suspend fun getBooksInFolder(folderId: Long): List<BookEntity>

    @Upsert
    abstract suspend fun upsertBook(book: BookEntity)

    @Update
    abstract suspend fun updateBook(book: BookEntity)

    @Query("UPDATE books SET positionMs = :positionMs, lastPlayedAt = :playedAt, status = :status WHERE id = :id")
    abstract suspend fun updateProgress(id: String, positionMs: Long, playedAt: Long, status: Int)

    @Query("UPDATE books SET positionMs = :positionMs WHERE id = :id")
    abstract suspend fun updatePosition(id: String, positionMs: Long)

    @Query("UPDATE books SET speed = :speed WHERE id = :id")
    abstract suspend fun updateSpeed(id: String, speed: Float)

    // One field at a time, for changes arriving from your other devices (sync).
    @Query("UPDATE books SET positionMs = :positionMs, lastPlayedAt = :playedAt WHERE id = :id")
    abstract suspend fun setPlace(id: String, positionMs: Long, playedAt: Long)

    @Query("UPDATE books SET lastPlayedAt = :playedAt WHERE id = :id")
    abstract suspend fun setLastPlayed(id: String, playedAt: Long)

    @Query("UPDATE books SET status = :status, finishedAt = :finishedAt WHERE id = :id")
    abstract suspend fun setStatus(id: String, status: Int, finishedAt: Long)

    @Query("UPDATE books SET rating = :rating WHERE id = :id")
    abstract suspend fun setRating(id: String, rating: Int)

    @Query("UPDATE books SET customEffects = :custom, trimSilence = :trim, voiceBoost = :boost, skipIntroSec = :intro, skipOutroSec = :outro WHERE id = :id")
    abstract suspend fun setSound(id: String, custom: Boolean, trim: Boolean, boost: Boolean, intro: Int, outro: Int)

    @Query("DELETE FROM books WHERE id = :id")
    abstract suspend fun deleteBookRow(id: String)

    @Transaction
    open suspend fun deleteBook(id: String) {
        deleteBookRow(id)
        deleteTracks(id)
        deleteChapters(id)
        deleteBookmarksFor(id)
        deleteShelfLinksFor(id)
        deleteClipsFor(id)
        clearListenLog(id)
    }

    // Tracks
    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY idx")
    abstract suspend fun getTracks(bookId: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY idx")
    abstract fun observeTracks(bookId: String): Flow<List<TrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertTracks(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks WHERE bookId = :bookId")
    abstract suspend fun deleteTracks(bookId: String)

    // Chapters
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY idx")
    abstract suspend fun getChapters(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY idx")
    abstract fun observeChapters(bookId: String): Flow<List<ChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    abstract suspend fun deleteChapters(bookId: String)

    @Transaction
    open suspend fun replaceChapters(bookId: String, chapters: List<ChapterEntity>) {
        deleteChapters(bookId)
        insertChapters(chapters)
    }

    /** A scanned book's files and chapters. File-style names ("Book - Track 029") become "Chapter 29". */
    @Transaction
    open suspend fun replaceContent(book: BookEntity, tracks: List<TrackEntity>, chapters: List<ChapterEntity>) {
        upsertBook(book)
        deleteTracks(book.id)
        deleteChapters(book.id)
        insertTracks(tracks.map { it.copy(title = app.ember.util.Names.chapter(it.title, book.title, it.idx)) })
        insertChapters(chapters.map { it.copy(title = app.ember.util.Names.chapter(it.title, book.title, it.idx)) })
    }

    /** The same tidying for books already in the library (once, after updating). */
    @Transaction
    open suspend fun tidyNames(book: BookEntity) {
        val tracks = getTracks(book.id)
        val tidyTracks = tracks.map { it.copy(title = app.ember.util.Names.chapter(it.title, book.title, it.idx)) }
        if (tidyTracks != tracks) insertTracks(tidyTracks)
        val chapters = getChapters(book.id)
        val tidyChapters = chapters.map { it.copy(title = app.ember.util.Names.chapter(it.title, book.title, it.idx)) }
        if (tidyChapters != chapters) insertChapters(tidyChapters)
    }

    // Bookmarks
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY positionMs")
    abstract fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    @Insert
    abstract suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId")
    abstract suspend fun getBookmarks(bookId: String): List<BookmarkEntity>

    @Query("SELECT shelfId FROM shelf_books WHERE bookId = :bookId")
    abstract suspend fun getShelfIdsFor(bookId: String): List<Long>

    @Query("DELETE FROM bookmarks WHERE id = :id")
    abstract suspend fun deleteBookmark(id: Long)

    @Update
    abstract suspend fun updateBookmark(bookmark: BookmarkEntity)

    @Query("SELECT * FROM bookmarks WHERE createdAt = :at")
    abstract suspend fun getBookmarksCreatedAt(at: Long): List<BookmarkEntity>

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    abstract suspend fun deleteBookmarksFor(bookId: String)

    // Listening log
    @Insert
    abstract suspend fun insertListenEvent(e: ListenEventEntity): Long

    @Update
    abstract suspend fun updateListenEvent(e: ListenEventEntity)

    /** This device's latest entry (entries synced from other devices never merge with new ones). */
    @Query("SELECT * FROM listen_log WHERE bookId = :bookId AND device = '' ORDER BY at DESC, id DESC LIMIT 1")
    abstract suspend fun lastListenEvent(bookId: String): ListenEventEntity?

    @Query("SELECT * FROM listen_log WHERE bookId = :bookId")
    abstract suspend fun getListenLog(bookId: String): List<ListenEventEntity>

    @Query("SELECT COUNT(*) AS n, IFNULL(MAX(at), 0) AS maxAt, IFNULL(MAX(id), 0) AS maxId FROM listen_log WHERE bookId = :bookId")
    abstract suspend fun listenLogSignature(bookId: String): LogSignature

    @Insert
    abstract suspend fun insertListenEvents(e: List<ListenEventEntity>)

    @Query("DELETE FROM listen_log WHERE id IN (:ids)")
    abstract suspend fun deleteListenEvents(ids: List<Long>)

    @Query("SELECT * FROM listen_log WHERE bookId = :bookId ORDER BY at DESC, id DESC LIMIT 400")
    abstract fun observeListenLog(bookId: String): Flow<List<ListenEventEntity>>

    @Query("SELECT * FROM listen_log ORDER BY at DESC, id DESC LIMIT :n")
    abstract suspend fun recentListenLog(n: Int): List<ListenEventEntity>

    @Query("SELECT COUNT(*) FROM listen_log")
    abstract suspend fun countListenLog(): Int

    /** Sleep timer pauses that remember where you were last awake. */
    @Query("SELECT COUNT(*) FROM listen_log WHERE reason = 1 AND toMs >= 0")
    abstract suspend fun countSleepMarks(): Int

    @Query("DELETE FROM listen_log WHERE bookId = :bookId")
    abstract suspend fun clearListenLog(bookId: String)

    /** Keeps the newest 400 entries per book. */
    @Query("DELETE FROM listen_log WHERE bookId = :bookId AND id NOT IN (SELECT id FROM listen_log WHERE bookId = :bookId ORDER BY at DESC, id DESC LIMIT 400)")
    abstract suspend fun trimListenLog(bookId: String)

    @Query("UPDATE books SET pdfUri = :uri WHERE id = :bookId")
    abstract suspend fun setPdf(bookId: String, uri: String?)

    // Clips
    @Query("SELECT * FROM clips ORDER BY createdAt DESC")
    abstract fun observeClips(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE mediaId = :mediaId ORDER BY startMs")
    abstract fun observeClipsFor(mediaId: String): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips")
    abstract suspend fun getClips(): List<ClipEntity>

    @Query("SELECT * FROM clips WHERE id = :id")
    abstract suspend fun getClip(id: Long): ClipEntity?

    @Query("SELECT * FROM clips WHERE createdAt = :at")
    abstract suspend fun getClipsCreatedAt(at: Long): List<ClipEntity>

    @Insert
    abstract suspend fun insertClip(clip: ClipEntity): Long

    @Update
    abstract suspend fun updateClip(clip: ClipEntity)

    @Query("DELETE FROM clips WHERE id = :id")
    abstract suspend fun deleteClip(id: Long)

    @Query("DELETE FROM clips WHERE mediaId = :mediaId")
    abstract suspend fun deleteClipsFor(mediaId: String)

    // Everything, for backups
    @Query("SELECT * FROM bookmarks")
    abstract suspend fun getAllBookmarks(): List<BookmarkEntity>

    @Query("SELECT * FROM shelves")
    abstract suspend fun getShelves(): List<ShelfEntity>

    @Query("SELECT * FROM shelf_books")
    abstract suspend fun getShelfLinks(): List<ShelfBookEntity>

    @Query("SELECT * FROM daily_listening")
    abstract suspend fun getAllDays(): List<DailyListeningEntity>

    @Query("INSERT OR REPLACE INTO daily_listening (day, listenedMs) VALUES (:day, :ms)")
    abstract suspend fun setDay(day: Long, ms: Long)

    // Listening stats
    @Query("SELECT * FROM daily_listening WHERE day >= :fromDay ORDER BY day")
    abstract fun observeDays(fromDay: Long): Flow<List<DailyListeningEntity>>

    @Query("SELECT * FROM daily_listening ORDER BY day")
    abstract fun observeAllDays(): Flow<List<DailyListeningEntity>>

    @Query("INSERT OR IGNORE INTO daily_listening (day, listenedMs) VALUES (:day, 0)")
    abstract suspend fun ensureDay(day: Long)

    @Query("UPDATE daily_listening SET listenedMs = listenedMs + :ms WHERE day = :day")
    abstract suspend fun incrementDay(day: Long, ms: Long)

    @Transaction
    open suspend fun addListening(day: Long, ms: Long) {
        ensureDay(day)
        incrementDay(day, ms)
    }

    // Shelves
    @Query("SELECT * FROM shelves ORDER BY name COLLATE NOCASE")
    abstract fun observeShelves(): Flow<List<ShelfEntity>>

    @Query("SELECT * FROM shelf_books")
    abstract fun observeShelfLinks(): Flow<List<ShelfBookEntity>>

    @Insert
    abstract suspend fun insertShelf(shelf: ShelfEntity): Long

    @Query("DELETE FROM shelves WHERE id = :id")
    abstract suspend fun deleteShelfRow(id: Long)

    @Query("DELETE FROM shelf_books WHERE shelfId = :id")
    abstract suspend fun deleteShelfLinks(id: Long)

    @Transaction
    open suspend fun deleteShelf(id: Long) {
        deleteShelfLinks(id)
        deleteShelfRow(id)
    }

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun addToShelf(link: ShelfBookEntity)

    @Query("DELETE FROM shelf_books WHERE shelfId = :shelfId AND bookId = :bookId")
    abstract suspend fun removeFromShelf(shelfId: Long, bookId: String)

    @Query("DELETE FROM shelf_books WHERE bookId = :bookId")
    abstract suspend fun deleteShelfLinksFor(bookId: String)

    // Books merged by hand
    @Query("SELECT * FROM book_merges ORDER BY mergedKey, idx")
    abstract suspend fun getMerges(): List<BookMergeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMerges(rows: List<BookMergeEntity>)

    @Query("DELETE FROM book_merges WHERE mergedKey = :mergedKey")
    abstract suspend fun deleteMerge(mergedKey: String)

    @Query("UPDATE books SET skipIntroSec = :intro, skipOutroSec = :outro WHERE id = :id")
    abstract suspend fun setSkips(id: String, intro: Int, outro: Int)

    /** Moves a book's bookmarks between [lo] and [hi] to another book, shifting their times (merging and splitting books). */
    @Query("UPDATE bookmarks SET bookId = :to, positionMs = positionMs + :shiftMs WHERE bookId = :from AND positionMs >= :lo AND positionMs < :hi")
    abstract suspend fun moveBookmarks(from: String, to: String, shiftMs: Long, lo: Long, hi: Long)

    @Query("UPDATE clips SET mediaId = :to, startMs = startMs + :shiftMs, endMs = endMs + :shiftMs WHERE mediaId = :from AND startMs >= :lo AND startMs < :hi")
    abstract suspend fun moveClips(from: String, to: String, shiftMs: Long, lo: Long, hi: Long)

    @Query("UPDATE listen_log SET bookId = :to, positionMs = positionMs + :shiftMs, toMs = CASE WHEN toMs >= 0 THEN toMs + :shiftMs ELSE toMs END WHERE bookId = :from AND positionMs >= :lo AND positionMs < :hi")
    abstract suspend fun moveListenLog(from: String, to: String, shiftMs: Long, lo: Long, hi: Long)

    @Query("UPDATE OR IGNORE shelf_books SET bookId = :to WHERE bookId = :from")
    abstract suspend fun moveShelfLinks(from: String, to: String)

    // Folders
    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE")
    abstract fun observeFolders(): Flow<List<LibraryFolderEntity>>

    @Query("SELECT * FROM folders")
    abstract suspend fun getFolders(): List<LibraryFolderEntity>

    @Query("SELECT * FROM folders WHERE uri = :uri")
    abstract suspend fun getFolderByUri(uri: String): LibraryFolderEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertFolder(folder: LibraryFolderEntity): Long

    @Update
    abstract suspend fun updateFolder(folder: LibraryFolderEntity)

    @Query("DELETE FROM folders WHERE id = :id")
    abstract suspend fun deleteFolder(id: Long)
}

/** How a book's listening log looks right now, to tell whether it changed since the last sync. */
data class LogSignature(val n: Int, val maxAt: Long, val maxId: Long) {
    override fun toString() = "$n:$maxAt:$maxId"
}

@Database(
    entities = [
        BookEntity::class,
        TrackEntity::class,
        ChapterEntity::class,
        BookmarkEntity::class,
        DailyListeningEntity::class,
        ShelfEntity::class,
        ShelfBookEntity::class,
        LibraryFolderEntity::class,
        ClipEntity::class,
        ListenEventEntity::class,
        BookMergeEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class EmberDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        const val NAME = "ember.db"

        /**
         * Opens your library. Upgrades only ever go through the step-by-step migrations in
         * [DbMigrations]; Ember never wipes the database to make room for a new version.
         */
        fun build(context: Context): EmberDatabase {
            DbSnapshots.beforeOpen(context, NAME)
            return Room.databaseBuilder(context, EmberDatabase::class.java, NAME)
                .addMigrations(*DbMigrations.ember)
                .build()
        }
    }
}
