package app.ember.ui

import app.ember.data.BookInfo
import kotlinx.coroutines.delay
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ember.container
import app.ember.cast.CastStatus
import app.ember.data.BookEntity
import app.ember.data.BookmarkEntity
import app.ember.data.ChapterEntity
import app.ember.data.DailyListeningEntity
import app.ember.data.LibraryFolderEntity
import android.media.MediaMetadataRetriever
import app.ember.data.Mp4ChapterParser
import app.ember.data.Mp4TagWriter
import app.ember.data.Settings
import app.ember.data.ShelfBookEntity
import app.ember.data.ShelfEntity
import app.ember.data.TrackEntity
import app.ember.playback.PlayerConnection
import app.ember.playback.PlaybackIssue
import app.ember.playback.PlaybackIssues
import app.ember.playback.PlaybackStatus
import app.ember.data.ClipEntity
import app.ember.playback.SleepTimer
import app.ember.util.todayEpochDay
import app.ember.util.yearOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import android.app.Activity
import android.app.PendingIntent
import app.ember.data.LibraryScanner
import app.ember.drive.DriveAuth
import app.ember.drive.DriveFile
import app.ember.playback.PlaybackService
import app.ember.podcasts.DownloadEntity
import app.ember.podcasts.EpisodeEntity
import app.ember.podcasts.PodcastEntity
import app.ember.podcasts.SearchResult
import app.ember.podcasts.ShowStats
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import app.ember.playback.SleepMarks
import app.ember.data.NotesExport
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

data class NowPlaying(
    val book: BookEntity,
    val tracks: List<TrackEntity>,
    val chapters: List<ChapterEntity>,
    val offsets: LongArray,
    val position: Long,
    val isPlaying: Boolean,
    val loaded: Boolean,
    val speed: Float,
    val chapterIndex: Int,
    val buffering: Boolean,
    /** Set when this is a podcast episode rather than a book. */
    val episodeId: String? = null,
    val podcastId: String? = null,
) {
    val isEpisode: Boolean get() = episodeId != null
    val chapter: ChapterEntity? get() = chapters.getOrNull(chapterIndex)
    val chapterStart: Long get() = chapter?.startMs ?: 0L
    val chapterEnd: Long get() = chapter?.endMs?.takeIf { it > chapterStart } ?: book.durationMs
    val chapterPosition: Long get() = (position - chapterStart).coerceAtLeast(0)
    val chapterLength: Long get() = (chapterEnd - chapterStart).coerceAtLeast(1)
    val remaining: Long get() = (book.durationMs - position).coerceAtLeast(0)
    val progress: Float get() = if (book.durationMs > 0) (position.toFloat() / book.durationMs).coerceIn(0f, 1f) else 0f
}

data class StatsSummary(
    val todayMs: Long = 0,
    val streak: Int = 0,
    val longestStreak: Int = 0,
    val week: List<Pair<LocalDate, Long>> = emptyList(),
    val totalMs: Long = 0,
    val last30AvgMs: Long = 0,
    val finishedThisYear: List<BookEntity> = emptyList(),
    val finishedAllTime: Int = 0,
    val inProgress: Int = 0,
    val dailyGoalMin: Int = 30,
    val yearlyGoal: Int = 24,
)

enum class ExternalApp(val label: String, val packageName: String) {
    AUDIBLE("Audible", "com.audible.application"),
    LIBBY("Libby", "com.overdrive.mobile.android.libby"),
    SPOTIFY("Spotify", "com.spotify.music"),
    OTHER("Other", ""),
    ;

    companion object {
        fun from(key: String): ExternalApp = entries.firstOrNull { it.name == key } ?: OTHER
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** How long the Undo bar stays up. */
        const val UNDO_WINDOW_MS = 6_000L
    }


    private val c = app.container
    private val dao = c.db.dao()
    private val conn = c.player
    private val mediaDao = c.media.dao()

    val settings: StateFlow<Settings> = c.settings.flow.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val books: StateFlow<List<BookEntity>> = dao.observeBooks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val folders: StateFlow<List<LibraryFolderEntity>> = dao.observeFolders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val shelves: StateFlow<List<ShelfEntity>> = dao.observeShelves().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val shelfLinks: StateFlow<List<ShelfBookEntity>> = dao.observeShelfLinks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val scanProgress = c.scanner.progress
    val folderProblems: StateFlow<Map<Long, String>> = c.scanner.problems
    val sleep = SleepTimer.state
    val playerState: StateFlow<PlayerConnection.State> = conn.state

    val stats: StateFlow<StatsSummary> = combine(dao.observeAllDays(), books, settings) { d, b, s ->
        computeStats(d, b, s)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StatsSummary())

    private val currentBookId: Flow<String?> =
        combine(conn.state, settings) { p, s -> p.bookId ?: s.lastBookId }.distinctUntilChanged()

    val nowPlaying: StateFlow<NowPlaying?> = currentBookId.flatMapLatest { id ->
        if (id == null) {
            flowOf(null)
        } else if (id.startsWith(PlaybackService.EPISODE_PREFIX)) {
            mediaDao.observeEpisode(id.removePrefix(PlaybackService.EPISODE_PREFIX)).flatMapLatest { ep ->
                if (ep == null) {
                    flowOf(null)
                } else {
                    combine(mediaDao.observePodcast(ep.podcastId), mediaDao.observeEpisodeChapters(ep.id), conn.state) { show, chs, p ->
                        buildEpisodeNowPlaying(id, ep, show, chs, p)
                    }
                }
            }
        } else {
            combine(dao.observeBook(id), dao.observeTracks(id), dao.observeChapters(id), conn.state) { b, t, ch, p ->
                buildNowPlaying(b, t, ch, p)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Shows an episode in the same shape as a book so the player screens work unchanged. */
    private fun buildEpisodeNowPlaying(
        key: String,
        ep: EpisodeEntity,
        show: PodcastEntity?,
        episodeChapters: List<app.ember.podcasts.EpisodeChapterEntity>,
        p: PlayerConnection.State,
    ): NowPlaying {
        val loaded = p.bookId == key
        val duration = ep.durationMs.coerceAtLeast(1)
        // Podcast chapters work just like a book's; without them the episode is one long chapter.
        val chapters = if (episodeChapters.size >= 2) {
            episodeChapters.mapIndexed { i, c ->
                val end = episodeChapters.getOrNull(i + 1)?.startMs ?: duration
                ChapterEntity(key, i, c.title, c.startMs, maxOf(end, c.startMs))
            }
        } else {
            listOf(ChapterEntity(key, 0, show?.title ?: ep.title, 0, duration))
        }
        val book = BookEntity(
            id = key,
            title = ep.title,
            author = show?.title ?: "",
            coverPath = show?.artworkPath,
            coverColor = show?.color,
            durationMs = duration,
            positionMs = ep.positionMs,
            speed = show?.speed ?: 1f,
            description = ep.description,
        )
        val pos = (if (loaded) p.positionInTrack else ep.positionMs).coerceIn(0, duration)
        return NowPlaying(
            book = book,
            tracks = listOf(TrackEntity(key, 0, ep.audioUrl, ep.title, duration)),
            chapters = chapters,
            offsets = longArrayOf(0L),
            position = pos,
            isPlaying = loaded && p.isPlaying,
            loaded = loaded,
            speed = if (loaded) p.speed else (show?.speed ?: 1f),
            chapterIndex = chapters.indexOfLast { it.startMs <= pos }.coerceAtLeast(0),
            buffering = loaded && p.buffering,
            episodeId = ep.id,
            podcastId = ep.podcastId,
        )
    }

    private fun buildNowPlaying(
        b: BookEntity?,
        t: List<TrackEntity>,
        ch: List<ChapterEntity>,
        p: PlayerConnection.State,
    ): NowPlaying? {
        if (b == null || b.isExternal || t.isEmpty()) return null
        val offsets = LongArray(t.size)
        var acc = 0L
        t.forEachIndexed { i, tr ->
            offsets[i] = acc
            acc += tr.durationMs
        }
        val loaded = p.bookId == b.id
        val pos = if (loaded) offsets.getOrElse(p.trackIndex) { 0L } + p.positionInTrack else b.positionMs
        val chIdx = ch.indexOfLast { it.startMs <= pos }.coerceAtLeast(0)
        return NowPlaying(
            book = b,
            tracks = t,
            chapters = ch,
            offsets = offsets,
            position = pos.coerceIn(0, maxOf(b.durationMs, 0)),
            isPlaying = loaded && p.isPlaying,
            loaded = loaded,
            speed = if (loaded) p.speed else b.speed,
            chapterIndex = chIdx,
            buffering = loaded && p.buffering,
        )
    }

    fun observeBook(id: String): Flow<BookEntity?> = dao.observeBook(id)
    fun observeChapters(id: String): Flow<List<ChapterEntity>> = dao.observeChapters(id)
    fun observeBookmarks(id: String): Flow<List<BookmarkEntity>> = dao.observeBookmarks(id)

    // ---- Playback ----

    fun play(bookId: String) = conn.playBook(bookId)

    fun togglePlay() {
        val np = nowPlaying.value ?: return
        when {
            !np.loaded -> conn.playBook(np.book.id)
            np.isPlaying -> conn.pause()
            else -> conn.play()
        }
    }

    fun skipBack() {
        val np = nowPlaying.value ?: return
        if (np.loaded) conn.seekBack() else seekGlobal(np.position - settings.value.skipBackSec * 1000L)
    }

    fun skipForward() {
        val np = nowPlaying.value ?: return
        if (np.loaded) conn.seekForward() else seekGlobal(np.position + settings.value.skipForwardSec * 1000L)
    }

    fun seekGlobal(ms: Long) {
        val np = nowPlaying.value ?: return
        val target = ms.coerceIn(0, maxOf(np.book.durationMs - 500, 0))
        if (np.loaded) {
            var idx = 0
            for (i in np.offsets.indices) if (np.offsets[i] <= target) idx = i
            conn.seekTo(idx, target - np.offsets[idx])
        } else if (np.isEpisode) {
            viewModelScope.launch { mediaDao.updateEpisodePosition(np.episodeId!!, target, System.currentTimeMillis()) }
        } else {
            viewModelScope.launch { dao.updatePosition(np.book.id, target) }
        }
    }

    fun previousChapter() {
        val np = nowPlaying.value ?: return
        val ch = np.chapter ?: return
        val target = if (np.position - ch.startMs > 3_000 || np.chapterIndex == 0) {
            ch.startMs
        } else {
            np.chapters[np.chapterIndex - 1].startMs
        }
        seekGlobal(target)
    }

    fun nextChapter() {
        val np = nowPlaying.value ?: return
        np.chapters.getOrNull(np.chapterIndex + 1)?.let { seekGlobal(it.startMs) }
    }

    /** Starts [bookId] at [positionMs], loading it first if needed. */
    fun playFrom(bookId: String, positionMs: Long) {
        val np = nowPlaying.value
        if (np != null && np.loaded && np.book.id == bookId) {
            seekGlobal(positionMs)
            conn.play()
        } else {
            viewModelScope.launch {
                if (bookId.startsWith(PlaybackService.EPISODE_PREFIX)) {
                    mediaDao.updateEpisodePosition(bookId.removePrefix(PlaybackService.EPISODE_PREFIX), positionMs, System.currentTimeMillis())
                } else {
                    dao.updateProgress(bookId, positionMs, System.currentTimeMillis(), BookEntity.STATUS_IN_PROGRESS)
                }
                conn.reloadBook(bookId)
            }
        }
    }

    fun setSpeed(speed: Float) {
        val np = nowPlaying.value ?: return
        if (np.loaded) conn.setSpeed(speed)
        viewModelScope.launch {
            val showId = np.podcastId
            if (showId != null) {
                mediaDao.getPodcast(showId)?.let { mediaDao.updatePodcast(it.copy(speed = speed)) }
            } else {
                dao.updateSpeed(np.book.id, speed)
            }
        }
    }

    fun sleepFor(minutes: Int) = SleepTimer.start(minutes)

    fun sleepEndOfChapter() {
        val np = nowPlaying.value ?: return
        SleepTimer.endOfChapter(np.chapterEnd)
    }

    fun cancelSleep() = SleepTimer.cancel()

    private val _homeHeroShown = MutableStateFlow(false)
    /** Home's "Continue listening" card is on screen (the mini player steps aside). */
    val homeHeroShown: StateFlow<Boolean> = _homeHeroShown

    fun setHomeHeroShown(shown: Boolean) {
        _homeHeroShown.value = shown
    }

    fun setSleepFade(on: Boolean) = viewModelScope.launch { c.settings.setSleepFade(on) }
    fun setFlipToPause(on: Boolean) = viewModelScope.launch { c.settings.setFlipToPause(on) }
    fun setBedtime(on: Boolean, fromMin: Int, toMin: Int, timerMin: Int) = viewModelScope.launch { c.settings.setBedtime(on, fromMin, toMin, timerMin) }

    /** Where you probably fell asleep last night (see SleepMarks). */
    val sleepMark: StateFlow<SleepMarks.Mark?> = SleepMarks.mark

    fun goBackToSleepMark() {
        val m = sleepMark.value ?: return
        dismissSleepMark()
        val np = nowPlaying.value
        if (np != null && np.loaded && np.book.id == m.bookId) seekGlobal(m.awakeMs) else playFrom(m.bookId, m.awakeMs)
    }

    fun dismissSleepMark() = SleepMarks.clear(getApplication())

    // ---- Up Next (books) ----

    /** Books lined up to play after this one, in order. */
    val upNext: StateFlow<List<BookEntity>> = combine(settings, books) { s, all ->
        val byId = all.associateBy { it.id }
        s.upNext.mapNotNull { byId[it] }.filter { !it.isExternal }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun addToUpNext(bookIds: Collection<String>) {
        val ids = bookIds.filter { id -> books.value.firstOrNull { it.id == id }?.isExternal == false }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val before = settings.value.upNext
            c.settings.editUpNext { it + ids }
            offerUndo(if (ids.size == 1) "Added to Up Next" else "${ids.size} books added to Up Next") {
                c.settings.editUpNext { cur -> cur.filter { it !in ids || it in before } }
            }
        }
    }

    fun removeFromUpNext(bookId: String) {
        viewModelScope.launch {
            val before = settings.value.upNext
            c.settings.editUpNext { it - bookId }
            offerUndo("Removed from Up Next") { c.settings.editUpNext { before } }
        }
    }

    /** Moves a book earlier (-1) or later (+1) in Up Next. */
    fun moveInUpNext(bookId: String, delta: Int) {
        viewModelScope.launch {
            c.settings.editUpNext { cur ->
                val i = cur.indexOf(bookId)
                val j = (i + delta).coerceIn(0, cur.size - 1)
                if (i < 0 || i == j) cur else cur.toMutableList().also { it.add(j, it.removeAt(i)) }
            }
        }
    }

    fun clearUpNext() {
        viewModelScope.launch {
            val before = settings.value.upNext
            c.settings.editUpNext { emptyList() }
            offerUndo("Up Next cleared") { c.settings.editUpNext { before } }
        }
    }

    // ---- A book's own sound ----

    fun setBookEffects(bookId: String, custom: Boolean, trimSilence: Boolean, voiceBoost: Boolean) =
        updateBook(bookId) { it.copy(customEffects = custom, trimSilence = trimSilence, voiceBoost = voiceBoost) }

    // ---- Appearance ----

    fun setAppearance(mode: String) = viewModelScope.launch { c.settings.setAppearance(mode) }
    fun setTheme(id: String) = viewModelScope.launch { c.settings.setTheme(id) }
    fun setTrueBlack(on: Boolean) = viewModelScope.launch { c.settings.setTrueBlack(on) }
    fun setAccent(mode: String, color: Int? = null) = viewModelScope.launch { c.settings.setAccent(mode, color) }

    // ---- Your profile (name and photo) ----

    fun setProfileName(name: String) {
        viewModelScope.launch { c.settings.setProfileName(name) }
    }

    fun setProfilePhoto(square: android.graphics.Bitmap) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { app.ember.data.ProfilePhoto.save(getApplication<Application>(), square) }
            if (ok) c.settings.setProfilePhotoAt(System.currentTimeMillis())
        }
    }

    fun removeProfilePhoto() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.ember.data.ProfilePhoto.delete(getApplication<Application>()) }
            c.settings.setProfilePhotoAt(0)
        }
    }

    // ---- Merging books (CD1, CD2...) by hand ----

    fun canMerge(ids: Collection<String>): Boolean = c.scanner.canMerge(books.value.filter { it.id in ids })

    suspend fun isManualMerge(id: String): Boolean = c.scanner.isManualMerge(id)

    fun mergeBooks(orderedIds: List<String>, title: String) {
        viewModelScope.launch {
            val all = c.db.dao().getBooks().associateBy { it.id }
            val picked = orderedIds.mapNotNull { all[it] }
            val id = c.scanner.merge(picked, title)
            app.ember.playback.PlaybackStatus.notice.value = if (id != null) "Merged ${picked.size} books into one" else "Those books can't be merged"
        }
    }

    fun splitBook(id: String) {
        viewModelScope.launch {
            val ok = c.scanner.split(id)
            app.ember.playback.PlaybackStatus.notice.value = if (ok) "Split back into its parts" else "That book can't be split"
        }
    }

    /** Seconds skipped at the start / end of each of this book's files when playback moves on by itself. */
    fun setBookSkips(bookId: String, intro: Int, outro: Int) {
        viewModelScope.launch { c.db.dao().setSkips(bookId, intro, outro) }
    }

    // ---- Notes export (Markdown, for Notion and the like) ----

    /** A book's details, rating, bookmarks and clips as one Markdown page. */
    suspend fun notesMarkdown(bookId: String): String = NotesExport.markdown(dao, bookId)

    private fun notesFileName(title: String) = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().ifBlank { "Book" }.take(60) + " notes.md"

    suspend fun notesFileNameFor(bookId: String): String = notesFileName(dao.getBook(bookId)?.title ?: "Book")

    /** Opens the share sheet (Notion, Drive, email…) with the notes as text and as a .md file. */
    fun shareNotes(context: Context, bookId: String) {
        viewModelScope.launch {
            val md = notesMarkdown(bookId)
            if (md.isBlank()) return@launch
            val title = dao.getBook(bookId)?.title ?: "Book"
            val uri = withContext(Dispatchers.IO) {
                val dir = java.io.File(context.cacheDir, "notes").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val f = java.io.File(dir, notesFileName(title))
                f.writeText(md)
                androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
            }
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "$title notes")
                .putExtra(Intent.EXTRA_TEXT, md)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(Intent.createChooser(send, "Share notes").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
            }
        }
    }

    /** Saves the notes as a Markdown file where you chose (Downloads, Drive…). */
    fun saveNotes(uri: Uri, bookId: String) {
        viewModelScope.launch {
            val md = notesMarkdown(bookId)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { it.write(md.toByteArray()) } ?: error("no stream")
                }.isSuccess
            }
            PlaybackStatus.notice.value = if (ok) "Notes saved. In Notion, use Import → Markdown." else "Couldn't save the file. Try another folder."
        }
    }

    fun addBookmark(note: String) {
        val np = nowPlaying.value ?: return
        viewModelScope.launch {
            dao.insertBookmark(
                BookmarkEntity(
                    bookId = np.book.id,
                    positionMs = np.position,
                    note = note.trim(),
                    chapterTitle = np.chapter?.title ?: "",
                ),
            )
        }
    }

    fun deleteBookmark(id: Long) {
        viewModelScope.launch { dao.deleteBookmark(id) }
    }

    // ---- Book management ----

    fun setRating(bookId: String, rating: Int) = updateBook(bookId) { it.copy(rating = rating) }

    fun markFinished(bookId: String) = markFinished(listOf(bookId))

    /** Marks books finished (linked books go to 100%), with Undo. */
    fun markFinished(bookIds: Collection<String>) = changeBooks(
        bookIds,
        if (bookIds.size == 1) "Marked as finished" else "${bookIds.size} books marked as finished",
    ) {
        it.copy(
            status = BookEntity.STATUS_FINISHED,
            finishedAt = if (it.finishedAt > 0) it.finishedAt else System.currentTimeMillis(),
            positionMs = if (it.isExternal) 100L else it.durationMs,
        )
    }

    fun resetProgress(bookId: String) = resetProgress(listOf(bookId))

    /** Back to the start and not finished, with Undo. */
    fun resetProgress(bookIds: Collection<String>) = changeBooks(
        bookIds,
        if (bookIds.size == 1) "Progress reset" else "${bookIds.size} books reset to the start",
    ) {
        it.copy(status = BookEntity.STATUS_NEW, positionMs = 0, finishedAt = 0)
    }

    fun addToShelf(shelfId: Long, bookIds: Collection<String>) {
        viewModelScope.launch {
            val before = dao.getShelfLinks().filter { it.shelfId == shelfId }.map { it.bookId }.toSet()
            val added = bookIds.filter { it !in before }
            added.forEach { dao.addToShelf(ShelfBookEntity(shelfId, it)) }
            val name = dao.getShelves().firstOrNull { it.id == shelfId }?.name ?: "shelf"
            offerUndo(if (bookIds.size == 1) "Added to $name" else "${bookIds.size} books added to $name") {
                added.forEach { dao.removeFromShelf(shelfId, it) }
            }
        }
    }

    fun createShelfWith(name: String, bookIds: Collection<String>) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = dao.insertShelf(ShelfEntity(name = name.trim()))
            bookIds.forEach { dao.addToShelf(ShelfBookEntity(id, it)) }
        }
    }

    fun removeBooks(bookIds: Collection<String>) {
        viewModelScope.launch { bookIds.forEach { dao.deleteBook(it) } }
    }

    // ---- Undo ----

    /** The last one-tap change, offered back for a few seconds. */
    class UndoOffer(val message: String, val restore: suspend () -> Unit, val actionLabel: String = "Undo")

    private val _undo = MutableStateFlow<UndoOffer?>(null)
    val undo: StateFlow<UndoOffer?> = _undo

    private fun offerUndo(message: String, actionLabel: String = "Undo", restore: suspend () -> Unit) {
        _undo.value = UndoOffer(message, restore, actionLabel)
    }

    // ---- Car mode, pocket lock, buttons ----

    private val _locked = MutableStateFlow(false)
    /** Pocket lock: the screen ignores touches until you press and hold. */
    val locked: StateFlow<Boolean> = _locked

    fun setLocked(on: Boolean) {
        _locked.value = on
    }

    fun setCarModeAuto(on: Boolean) = viewModelScope.launch { c.settings.setCarModeAuto(on) }
    fun setCarDevices(addresses: Set<String>) = viewModelScope.launch { c.settings.setCarDevices(addresses) }
    fun setRateOnFinish(on: Boolean) = viewModelScope.launch { c.settings.setRateOnFinish(on) }
    fun setButton(which: String, action: Int) = viewModelScope.launch { c.settings.setButton(which, action) }

    /** A bookmark with no note, for car mode. */
    fun quickBookmark(): String? {
        val np = nowPlaying.value ?: return null
        addBookmark("")
        return app.ember.util.formatClock(np.position)
    }

    // ---- Finished a book: rate it, log it ----

    private val _finishedPrompt = MutableStateFlow<String?>(null)
    /** The book just finished, while the "rate it" sheet is up. */
    val finishedPrompt: StateFlow<String?> = _finishedPrompt

    fun showFinishedPrompt(bookId: String) {
        _finishedPrompt.value = bookId
    }

    fun dismissFinishedPrompt() {
        _finishedPrompt.value = null
    }

    /** Opens the book in StoryGraph or Goodreads (their apps open it if installed). */
    fun openOnReadingSite(context: Context, book: BookEntity, site: String) {
        val q = Uri.encode(listOf(BookInfo.cleanTitle(book.title), book.author.substringBefore(',')).filter { it.isNotBlank() }.joinToString(" "))
        val url = if (site == "goodreads") "https://www.goodreads.com/search?q=$q" else "https://app.thestorygraph.com/browse?search_term=$q"
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
        }
    }

    /** Every book as a Goodreads-style CSV, which StoryGraph and Goodreads can both import. */
    fun exportBooksCsv(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val books = dao.getBooks()
                    val shelves = dao.getShelves().associateBy { it.id }
                    val links = dao.getShelfLinks().groupBy { it.bookId }
                    val day = java.text.SimpleDateFormat("yyyy/MM/dd", java.util.Locale.US)
                    fun cell(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
                    val header = listOf(
                        "Book Id", "Title", "Author", "Author l-f", "Additional Authors", "ISBN", "ISBN13", "My Rating",
                        "Average Rating", "Publisher", "Binding", "Number of Pages", "Year Published", "Original Publication Year",
                        "Date Read", "Date Added", "Bookshelves", "Bookshelves with positions", "Exclusive Shelf", "My Review",
                        "Spoiler", "Private Notes", "Read Count", "Owned Copies",
                    )
                    val rows = books.sortedBy { it.addedAt }.map { b ->
                        val authors = b.author.split(',', '&').map { it.trim() }.filter { it.isNotBlank() }
                        val first = authors.firstOrNull().orEmpty()
                        val lf = first.substringAfterLast(' ', "").let { last -> if (last.isBlank() || last == first) first else "$last, ${first.substringBeforeLast(' ')}" }
                        val shelf = when (b.status) {
                            BookEntity.STATUS_FINISHED -> "read"
                            BookEntity.STATUS_IN_PROGRESS -> "currently-reading"
                            else -> "to-read"
                        }
                        val myShelves = (links[b.id].orEmpty().mapNotNull { shelves[it.shelfId]?.name } + "audiobook")
                            .map { it.lowercase().replace(' ', '-') }
                        listOf(
                            "", b.title, first, lf, authors.drop(1).joinToString(", "), "", "", b.rating.toString(),
                            "", "", "Audiobook", "", "", "",
                            if (b.finishedAt > 0) day.format(java.util.Date(b.finishedAt)) else "",
                            day.format(java.util.Date(b.addedAt)),
                            myShelves.joinToString(", "), "", shelf, "",
                            "", "", if (b.status == BookEntity.STATUS_FINISHED) "1" else "0", "0",
                        ).joinToString(",") { cell(it) }
                    }
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write((header.joinToString(",") + "\n" + rows.joinToString("\n") + "\n").toByteArray())
                    } ?: error("can't write")
                    books.size
                }.getOrNull()
            }
            PlaybackStatus.notice.value = if (ok != null) "Exported $ok books. Import the file in StoryGraph or Goodreads." else "Couldn't save the file. Try another folder."
        }
    }

    // ---- Book info from Audible's catalog ----

    suspend fun searchBookInfo(title: String, author: String, keywords: String? = null): List<BookInfo.Match> =
        BookInfo.search(title, author, keywords)

    /** Applies the chosen [fields] ("title", "author", "narrator", "series", "description", "cover"). */
    fun applyBookInfo(bookId: String, m: BookInfo.Match, fields: Set<String>) {
        viewModelScope.launch {
            val b = dao.getBook(bookId) ?: return@launch
            dao.updateBook(
                b.copy(
                    title = if ("title" in fields && m.title.isNotBlank()) m.title else b.title,
                    author = if ("author" in fields && m.authors.isNotBlank()) m.authors else b.author,
                    narrator = if ("narrator" in fields && m.narrators.isNotBlank()) m.narrators else b.narrator,
                    series = if ("series" in fields && m.series.isNotBlank()) m.series else b.series,
                    seriesIndex = if ("series" in fields && m.series.isNotBlank()) m.seriesIndex else b.seriesIndex,
                    description = if ("description" in fields && m.description.isNotBlank()) m.description else b.description,
                    userEdited = true,
                ),
            )
            if ("cover" in fields && m.coverUrl != null) applyCoverUrlNow(bookId, m.coverUrl)
            PlaybackStatus.notice.value = "Book info updated"
        }
    }

    private val _infoFill = MutableStateFlow<String?>(null)
    /** Progress of "Fill in missing book info", or its result. */
    val infoFill: StateFlow<String?> = _infoFill

    /**
     * Looks up every book that's missing its series, narrator, description or cover, and fills
     * in only what's missing, only when the match is clear (title, author and length agree).
     */
    fun fillMissingInfo() {
        if (_infoFill.value?.endsWith("…") == true) return
        viewModelScope.launch {
            val todo = dao.getBooks().filter { b ->
                b.series.isBlank() || b.narrator.isBlank() || b.description.isBlank() || b.coverPath == null
            }
            var updated = 0
            todo.forEachIndexed { i, b ->
                _infoFill.value = "Looking up ${i + 1} of ${todo.size}…"
                val m = BookInfo.confident(b, BookInfo.search(b.title, b.author))
                if (m != null) {
                    val cur = dao.getBook(b.id) ?: return@forEachIndexed
                    var n = cur.copy(
                        narrator = cur.narrator.ifBlank { m.narrators },
                        series = cur.series.ifBlank { m.series },
                        seriesIndex = if (cur.series.isBlank() && m.series.isNotBlank()) m.seriesIndex else cur.seriesIndex,
                        description = cur.description.ifBlank { m.description },
                    )
                    if (n != cur) n = n.copy(userEdited = true)
                    dao.updateBook(n)
                    if (cur.coverPath == null && m.coverUrl != null) {
                        c.covers.download(b.id, m.coverUrl)?.let { saved ->
                            dao.getBook(b.id)?.let { dao.updateBook(it.copy(coverPath = saved.path, coverColor = saved.color)) }
                        }
                    }
                    updated++
                }
                delay(300)
            }
            _infoFill.value = when {
                todo.isEmpty() -> "Every book already has its details."
                updated == todo.size -> "Filled in $updated ${if (updated == 1) "book" else "books"}."
                else -> "Filled in $updated of ${todo.size}. The rest had no clear match; use Find book info on each one to choose."
            }
        }
    }

    // ---- Listening recap ----

    class Recap(
        val label: String,
        val listenedMs: Long,
        val daysListened: Int,
        val finished: List<BookEntity>,
        val topAuthor: String?,
        val topNarrator: String?,
        val longestStreak: Int,
        val bestDayLabel: String?,
        val bestDayMs: Long,
        val favorite: BookEntity?,
        val clips: Int,
        val clipQuote: String?,
        val accent: Int?,
    )

    /** [period]: 0 = this month, 1 = last month, 2 = this year. */
    suspend fun recap(period: Int): Recap = withContext(Dispatchers.IO) {
        val today = LocalDate.now()
        val (start, end, label) = when (period) {
            1 -> {
                val m = today.minusMonths(1)
                Triple(m.withDayOfMonth(1), m.withDayOfMonth(m.lengthOfMonth()), m.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()))
            }
            2 -> Triple(today.withDayOfYear(1), today, today.year.toString())
            else -> Triple(today.withDayOfMonth(1), today, today.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()))
        }
        val zone = java.time.ZoneId.systemDefault()
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val days = dao.getAllDays().filter { it.day in start.toEpochDay()..end.toEpochDay() && it.listenedMs > 0 }
        var streak = 0
        var run = 0
        var prev = Long.MIN_VALUE
        days.map { it.day }.sorted().forEach { d ->
            run = if (d == prev + 1) run + 1 else 1
            streak = maxOf(streak, run)
            prev = d
        }
        val best = days.maxByOrNull { it.listenedMs }
        val books = dao.getBooks()
        val finished = books.filter { it.status == BookEntity.STATUS_FINISHED && it.finishedAt in from until to }.sortedBy { it.finishedAt }
        val pool = finished.ifEmpty { books.filter { it.lastPlayedAt in from until to } }
        fun top(names: List<String>) = names.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val clips = dao.getClips().filter { it.createdAt in from until to }
        Recap(
            label = label,
            listenedMs = days.sumOf { it.listenedMs },
            daysListened = days.size,
            finished = finished,
            topAuthor = top(pool.map { it.author.substringBefore(',').trim() }),
            topNarrator = top(pool.map { it.narrator.substringBefore(',').trim() }),
            longestStreak = streak,
            bestDayLabel = best?.let { LocalDate.ofEpochDay(it.day).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")) },
            bestDayMs = best?.listenedMs ?: 0,
            favorite = finished.filter { it.rating > 0 }.maxByOrNull { it.rating * 10_000_000_000L + it.finishedAt },
            clips = clips.size,
            clipQuote = clips.filter { it.note.isNotBlank() }.maxByOrNull { it.createdAt }?.note,
            accent = (finished.lastOrNull() ?: pool.firstOrNull())?.coverColor,
        )
    }

    // ---- Companion PDF ----

    /** Attaches a PDF you picked (null removes it; a rescan won't add it back). */
    fun setPdf(bookId: String, uri: Uri?) {
        viewModelScope.launch {
            if (uri != null) {
                runCatching {
                    getApplication<Application>().contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            dao.setPdf(bookId, uri?.toString() ?: "")
        }
    }

    /** The PDF's file name, for showing in Edit details. */
    suspend fun pdfName(uri: String): String = withContext(Dispatchers.IO) {
        val u = Uri.parse(uri)
        if (u.scheme == "file") return@withContext u.lastPathSegment ?: "PDF"
        runCatching {
            getApplication<Application>().contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: "PDF"
    }

    /** Opens the PDF in another app (Samsung Notes, Drive, a PDF reader…). */
    fun openPdfElsewhere(context: Context, uri: String): Boolean {
        val u = Uri.parse(uri)
        val shareable = if (u.scheme == "file") {
            androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", java.io.File(u.path ?: return false))
        } else {
            u
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(shareable, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(Intent.createChooser(intent, "Open PDF with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---- Listening log ----

    fun observeListenLog(bookId: String): Flow<List<app.ember.data.ListenEventEntity>> = dao.observeListenLog(bookId)

    fun clearListenLog(bookId: String) {
        viewModelScope.launch { dao.clearListenLog(bookId) }
    }

    /** Takes you back to the spot a log entry remembers. */
    fun goToLogEntry(e: app.ember.data.ListenEventEntity) {
        val np = nowPlaying.value
        if (np != null && np.loaded && np.book.id == e.bookId) seekGlobal(e.positionMs) else playFrom(e.bookId, e.positionMs)
    }

    /** "Chapter 7: Among Giants · 23:22" for a spot in the book that's playing. */
    fun describeSpot(chapters: List<ChapterEntity>, ms: Long): String {
        val ch = chapters.lastOrNull { it.startMs <= ms }
        return if (ch != null) "${ch.title} · ${app.ember.util.formatClock(ms - ch.startMs)}" else app.ember.util.formatClock(ms)
    }

    init {
        SleepMarks.load(getApplication())
        // Pressing play on the book that's asking "Go there?" means you're staying here.
        viewModelScope.launch {
            PlaybackStatus.isPlaying.collect { playing ->
                val p = c.sync.prompt.value
                if (playing && p != null && PlaybackStatus.loadedId.value == p.bookId) c.sync.answer(false)
            }
        }
        // A book played to its end: ask for a rating (and offer StoryGraph / Goodreads).
        viewModelScope.launch {
            PlaybackStatus.finished.collect { f ->
                if (f == null) return@collect
                PlaybackStatus.finished.value = null
                if (settings.value.rateOnFinish) _finishedPrompt.value = f.bookId
            }
        }
        // A big jump (the scrubber, a chapter): offer to go straight back.
        viewModelScope.launch {
            PlaybackStatus.jumped.collect { j ->
                if (j == null) return@collect
                PlaybackStatus.jumped.value = null
                val chapters = nowPlaying.value?.takeIf { it.book.id == j.bookId }?.chapters.orEmpty()
                offerUndo("Moved from ${describeSpot(chapters, j.fromMs)}", actionLabel = "Go back") {
                    val np = nowPlaying.value
                    if (np != null && np.loaded && np.book.id == j.bookId) seekGlobal(j.fromMs)
                }
            }
        }
    }

    fun undoLast(offer: UndoOffer) {
        if (_undo.value === offer) _undo.value = null
        viewModelScope.launch { offer.restore() }
    }

    fun dismissUndo(offer: UndoOffer) {
        if (_undo.value === offer) _undo.value = null
    }

    /** Applies [transform] to each book and offers to put them back exactly as they were. */
    private fun changeBooks(ids: Collection<String>, message: String, transform: (BookEntity) -> BookEntity) {
        viewModelScope.launch {
            val before = ids.mapNotNull { dao.getBook(it) }
            if (before.isEmpty()) return@launch
            before.forEach { dao.updateBook(transform(it)) }
            offerUndo(message) { before.forEach { dao.updateBook(it) } }
        }
    }

    fun saveDetails(
        bookId: String,
        title: String,
        author: String,
        narrator: String,
        series: String,
        seriesIndex: Float?,
        description: String,
        externalLink: String? = null,
        writeFile: Boolean = false,
    ) {
        updateBook(bookId) {
        it.copy(
            title = title.trim().ifBlank { it.title },
            author = author.trim(),
            narrator = narrator.trim(),
            series = series.trim(),
            seriesIndex = seriesIndex,
            description = description.trim(),
            externalLink = externalLink?.trim() ?: it.externalLink,
            userEdited = true,
        )
        }
        if (writeFile && title.isNotBlank()) {
            viewModelScope.launch {
                val cover = withContext(Dispatchers.IO) { dao.getBook(bookId)?.coverPath?.let { java.io.File(it).takeIf { f -> f.exists() }?.readBytes() } }
                writeToFile(bookId, Mp4TagWriter.Tags(title.trim(), author.trim(), narrator.trim(), description.trim()), null, cover)
            }
        }
    }

    /**
     * Saves edited chapters. Each chapter runs until the next one starts; the first
     * always starts at 0. Marks the book as edited so rescans keep your changes.
     */
    fun saveChapters(bookId: String, edited: List<Pair<Long, String>>, writeFile: Boolean = false) {
        viewModelScope.launch {
            val book = dao.getBook(bookId) ?: return@launch
            val sorted = edited.sortedBy { it.first }.distinctBy { it.first }
            if (sorted.isEmpty()) return@launch
            val starts = sorted.mapIndexed { i, (start, _) -> if (i == 0) 0L else start.coerceIn(0, book.durationMs) }
            val rows = sorted.mapIndexed { i, (_, title) ->
                val end = starts.getOrNull(i + 1) ?: book.durationMs
                ChapterEntity(bookId, i, title.trim().ifBlank { "Chapter ${i + 1}" }, starts[i], maxOf(end, starts[i]))
            }
            dao.replaceChapters(bookId, rows)
            dao.updateBook(book.copy(userEdited = true))
            if (writeFile) writeToFile(bookId, null, rows.map { it.startMs to it.title })
        }
    }

    suspend fun chaptersFor(bookId: String): List<ChapterEntity> = dao.getChapters(bookId)

    // ---- Saving edits into the audio file ----

    enum class FileEditSupport { SUPPORTED, NEEDS_ACCESS, NOT_M4B, CLOUD, NONE }

    data class FileNotice(val text: String, val isError: Boolean)

    private val _fileNotice = MutableStateFlow<FileNotice?>(null)
    val fileNotice: StateFlow<FileNotice?> = _fileNotice
    private val _folderAccessRequest = MutableStateFlow<LibraryFolderEntity?>(null)
    /** Set when Ember needs you to re-pick a folder so it may save into its files. */
    val folderAccessRequest: StateFlow<LibraryFolderEntity?> = _folderAccessRequest
    private var pendingFileWrite: (suspend () -> Unit)? = null

    fun clearFileNotice() {
        _fileNotice.value = null
    }

    private data class BookFile(val uri: Uri, val folder: LibraryFolderEntity)

    private suspend fun bookFile(bookId: String): Pair<FileEditSupport, BookFile?> = withContext(Dispatchers.IO) {
        val book = dao.getBook(bookId) ?: return@withContext FileEditSupport.NONE to null
        if (book.isExternal) return@withContext FileEditSupport.NONE to null
        if (book.source == BookEntity.SOURCE_DRIVE || LibraryScanner.isDriveAppUri(book.sourceUri)) {
            return@withContext FileEditSupport.CLOUD to null
        }
        val tracks = dao.getTracks(bookId)
        if (tracks.size != 1) return@withContext FileEditSupport.NOT_M4B to null
        val uri = Uri.parse(tracks[0].uri)
        val name = displayName(uri) ?: uri.toString()
        if (name.substringAfterLast('.', "").lowercase() !in LibraryScanner.CONTAINER_EXT) {
            return@withContext FileEditSupport.NOT_M4B to null
        }
        val folder = book.folderId?.let { id -> dao.getFolders().firstOrNull { it.id == id } }
            ?: return@withContext FileEditSupport.NONE to null
        val canWrite = getApplication<Application>().contentResolver.persistedUriPermissions
            .any { it.uri.toString() == folder.uri && it.isWritePermission }
        (if (canWrite) FileEditSupport.SUPPORTED else FileEditSupport.NEEDS_ACCESS) to BookFile(uri, folder)
    }

    private fun displayName(uri: Uri): String? = try {
        getApplication<Application>().contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    suspend fun fileEditSupport(bookId: String): FileEditSupport = bookFile(bookId).first

    suspend fun hasOriginalFileBackup(bookId: String): Boolean {
        val f = bookFile(bookId).second ?: return false
        return Mp4TagWriter.hasOriginalBackup(getApplication(), f.uri)
    }

    /** Writes tags and/or chapters into the book's M4B file, asking for folder access first if needed. */
    private suspend fun writeToFile(bookId: String, tags: Mp4TagWriter.Tags?, chapters: List<Pair<Long, String>>?, cover: ByteArray? = null) {
        val (support, file) = bookFile(bookId)
        when (support) {
            FileEditSupport.CLOUD -> {
                _fileNotice.value = FileNotice("Saved in Ember. Books on Google Drive can't have their files changed yet.", false)
                return
            }
            FileEditSupport.NOT_M4B -> {
                _fileNotice.value = FileNotice("Saved in Ember. Only single-file M4B/M4A books can be changed on disk.", false)
                return
            }
            FileEditSupport.NONE -> return
            FileEditSupport.NEEDS_ACCESS -> {
                pendingFileWrite = { writeToFile(bookId, tags, chapters, cover) }
                _folderAccessRequest.value = file?.folder
                return
            }
            FileEditSupport.SUPPORTED -> {}
        }
        val f = file ?: return
        val book = dao.getBook(bookId) ?: return
        _fileNotice.value = FileNotice("Saving into the audio file…", false)
        val result = withContext(Dispatchers.IO) {
            Mp4TagWriter.write(getApplication(), f.uri, tags, chapters, book.durationMs, cover)
        }
        _fileNotice.value = when (result) {
            is Mp4TagWriter.Result.Ok -> FileNotice("Saved into the audio file.", false)
            is Mp4TagWriter.Result.Failed -> FileNotice("Saved in Ember only. ${result.reason}", true)
        }
    }

    /** Called with the folder you picked when Ember asked for permission to save into files. */
    fun grantFolderAccess(picked: Uri?) {
        val wanted = _folderAccessRequest.value
        _folderAccessRequest.value = null
        val pending = pendingFileWrite
        pendingFileWrite = null
        if (picked == null || wanted == null) {
            _fileNotice.value = FileNotice("Saved in Ember only. The audio file wasn't changed.", false)
            return
        }
        if (picked.toString() != wanted.uri) {
            _fileNotice.value = FileNotice("That was a different folder. Pick \"${wanted.name}\" to let Ember save into its files.", true)
            return
        }
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                picked,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            _fileNotice.value = FileNotice("Android didn't grant permission to change files there.", true)
            return
        }
        if (pending != null) viewModelScope.launch { pending() }
    }

    /** Puts the M4B back exactly as it was before Ember first changed it, and reloads its details. */
    fun restoreOriginalFile(bookId: String) {
        viewModelScope.launch {
            val f = bookFile(bookId).second ?: return@launch
            val ctx = getApplication<Application>()
            val result = withContext(Dispatchers.IO) { Mp4TagWriter.restoreOriginal(ctx, f.uri) }
            if (result is Mp4TagWriter.Result.Failed) {
                _fileNotice.value = FileNotice(result.reason, true)
                return@launch
            }
            withContext(Dispatchers.IO) {
                val book = dao.getBook(bookId) ?: return@withContext
                val raw = Mp4ChapterParser.parse(ctx, f.uri)
                if (raw.isNotEmpty()) {
                    dao.replaceChapters(
                        bookId,
                        raw.mapIndexed { i, rc ->
                            ChapterEntity(bookId, i, rc.title, rc.startMs, raw.getOrNull(i + 1)?.startMs ?: book.durationMs)
                        },
                    )
                }
                val r = MediaMetadataRetriever()
                var title = book.title
                var author = book.author
                var narrator = book.narrator
                try {
                    r.setDataSource(ctx, f.uri)
                    title = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))?.takeIf { it.isNotBlank() } ?: title
                    author = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)) ?: author
                    narrator = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER) ?: narrator
                } catch (e: Exception) {
                } finally {
                    try {
                        r.release()
                    } catch (e: Exception) {
                    }
                }
                dao.updateBook(book.copy(title = title, author = author, narrator = narrator, userEdited = false))
            }
            _fileNotice.value = FileNotice("The audio file is back to its original state.", false)
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch { dao.deleteBook(bookId) }
    }

    private fun updateBook(bookId: String, transform: (BookEntity) -> BookEntity) {
        viewModelScope.launch {
            val b = dao.getBook(bookId) ?: return@launch
            dao.updateBook(transform(b))
        }
    }

    // ---- Folders ----

    fun addFolder(uri: Uri) {
        viewModelScope.launch {
            val folder = withContext(Dispatchers.IO) {
                val ctx = getApplication<Application>()
                try {
                    try {
                        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    } catch (e: SecurityException) {
                        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } catch (e: SecurityException) {
                }
                val existing = dao.getFolderByUri(uri.toString())
                existing ?: run {
                    val name = folderName(ctx, uri)
                    val id = dao.insertFolder(LibraryFolderEntity(uri = uri.toString(), name = name))
                    LibraryFolderEntity(id = id, uri = uri.toString(), name = name)
                }
            }
            c.scanner.scanFolder(folder)
        }
    }

    private fun folderName(ctx: Context, uri: Uri): String {
        try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            ctx.contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0)?.let { return it }
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/') ?: "Audiobooks"
    }

    fun removeFolder(folder: LibraryFolderEntity) {
        viewModelScope.launch {
            dao.getBooksInFolder(folder.id).forEach { dao.deleteBook(it.id) }
            dao.deleteFolder(folder.id)
            try {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(
                    Uri.parse(folder.uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: Exception) {
            }
        }
    }

    fun rescan() {
        viewModelScope.launch { c.scanner.scanAll() }
    }

    // ---- Books in other apps ----

    fun addExternal(
        title: String,
        author: String,
        series: String,
        seriesIndex: Float?,
        app: ExternalApp,
        link: String,
        coverUrl: String?,
        onDone: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            val id = "ext-" + UUID.randomUUID().toString()
            dao.upsertBook(
                BookEntity(
                    id = id,
                    title = title.trim(),
                    author = author.trim(),
                    series = series.trim(),
                    seriesIndex = seriesIndex,
                    source = BookEntity.SOURCE_EXTERNAL,
                    durationMs = BookEntity.EXTERNAL_SCALE,
                    positionMs = 0,
                    externalApp = app.name,
                    externalLink = link.trim(),
                    userEdited = true,
                ),
            )
            onDone(id)
            val url = coverUrl ?: c.covers.searchOpenLibrary(title, author).firstOrNull()
            if (url != null) applyCoverUrlNow(id, url)
        }
    }

    fun setExternalProgress(bookId: String, percent: Int) = updateBook(bookId) {
        val p = percent.coerceIn(0, 100)
        val now = System.currentTimeMillis()
        it.copy(
            positionMs = p.toLong(),
            status = when (p) {
                0 -> BookEntity.STATUS_NEW
                100 -> BookEntity.STATUS_FINISHED
                else -> BookEntity.STATUS_IN_PROGRESS
            },
            finishedAt = if (p == 100) (if (it.finishedAt > 0) it.finishedAt else now) else 0,
            lastPlayedAt = now,
        )
    }

    fun logListening(bookId: String, minutes: Int) {
        if (minutes <= 0) return
        viewModelScope.launch {
            dao.addListening(todayEpochDay(), minutes * 60_000L)
            val b = dao.getBook(bookId) ?: return@launch
            dao.updateBook(
                b.copy(
                    lastPlayedAt = System.currentTimeMillis(),
                    status = if (b.status == BookEntity.STATUS_NEW) BookEntity.STATUS_IN_PROGRESS else b.status,
                ),
            )
        }
    }

    fun openExternal(context: Context, book: BookEntity) {
        val app = ExternalApp.from(book.externalApp)
        val intent = when {
            book.externalLink.isNotBlank() -> Intent(Intent.ACTION_VIEW, Uri.parse(book.externalLink))
            app.packageName.isNotBlank() -> context.packageManager.getLaunchIntentForPackage(app.packageName)
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${app.packageName}"))
            else -> null
        } ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
        }
    }

    // ---- Shelves ----

    fun createShelf(name: String, thenAddBook: String? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = dao.insertShelf(ShelfEntity(name = name.trim()))
            if (thenAddBook != null) dao.addToShelf(ShelfBookEntity(id, thenAddBook))
        }
    }

    fun deleteShelf(id: Long) {
        viewModelScope.launch { dao.deleteShelf(id) }
    }

    fun setOnShelf(shelfId: Long, bookId: String, on: Boolean) {
        viewModelScope.launch {
            if (on) dao.addToShelf(ShelfBookEntity(shelfId, bookId)) else dao.removeFromShelf(shelfId, bookId)
        }
    }

    // ---- Covers ----

    suspend fun searchCovers(title: String, author: String): List<String> = c.covers.searchOpenLibrary(title, author)

    fun applyCoverUrl(bookId: String, url: String) {
        viewModelScope.launch { applyCoverUrlNow(bookId, url) }
    }

    private suspend fun applyCoverUrlNow(bookId: String, url: String) {
        val saved = c.covers.download(bookId, url) ?: return
        val b = dao.getBook(bookId) ?: return
        dao.updateBook(b.copy(coverPath = saved.path, coverColor = saved.color))
        saveCoverIntoFile(bookId, saved.path)
    }

    /** New cover chosen: put it into the M4B too, for books whose files Ember can change. */
    private suspend fun saveCoverIntoFile(bookId: String, path: String) {
        val support = fileEditSupport(bookId)
        if (support != FileEditSupport.SUPPORTED && support != FileEditSupport.NEEDS_ACCESS) return
        val bytes = withContext(Dispatchers.IO) { java.io.File(path).takeIf { it.exists() }?.readBytes() } ?: return
        writeToFile(bookId, null, null, bytes)
    }

    fun applyCoverImage(bookId: String, uri: Uri) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { c.covers.saveFromUri(bookId, uri) } ?: return@launch
            val b = dao.getBook(bookId) ?: return@launch
            dao.updateBook(b.copy(coverPath = saved.path, coverColor = saved.color))
            saveCoverIntoFile(bookId, saved.path)
        }
    }

    // ---- Podcasts ----

    val podcasts: StateFlow<List<PodcastEntity>> = mediaDao.observePodcasts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val queue: StateFlow<List<EpisodeEntity>> = mediaDao.observeQueue().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val newEpisodes: StateFlow<List<EpisodeEntity>> =
        mediaDao.observeRecentUnplayed(System.currentTimeMillis() - 21L * 86_400_000L, 60)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val _podcastBusy = MutableStateFlow<String?>(null)
    /** A short status line while following, importing or refreshing. */
    val podcastBusy: StateFlow<String?> = _podcastBusy

    /** "New" = unplayed episodes from the last two weeks. */
    val showStats: StateFlow<Map<String, ShowStats>> =
        mediaDao.observeShowStats(System.currentTimeMillis() - 14L * 86_400_000L)
            .map { list -> list.associateBy { it.podcastId } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun setPodcastListView(on: Boolean) = viewModelScope.launch { c.settings.setPodcastListView(on) }
    fun setBookTimeLeft(on: Boolean) = viewModelScope.launch { c.settings.setBookTimeLeft(on) }
    fun setPlayerPaneFraction(f: Float) = viewModelScope.launch { c.settings.setPlayerPaneFraction(f) }

    fun observePodcast(id: String): Flow<PodcastEntity?> = mediaDao.observePodcast(id)
    fun observeEpisodes(podcastId: String): Flow<List<EpisodeEntity>> = mediaDao.observeEpisodes(podcastId)
    fun observeEpisode(id: String): Flow<EpisodeEntity?> = mediaDao.observeEpisode(id)

    suspend fun searchPodcasts(term: String): List<SearchResult> = c.podcasts.search(term)

    suspend fun topPodcasts(genreId: Int, genreName: String?): List<SearchResult> = c.podcasts.topCharts(genreId, genreName)

    fun markAllPlayed(podcastId: String) {
        viewModelScope.launch {
            val before = mediaDao.getEpisodes(podcastId)
            val queued = before.mapNotNull { e -> mediaDao.queuePosition(e.id)?.let { e.id to it } }
            c.podcasts.markAllPlayed(podcastId)
            val count = before.count { !it.played }
            if (count == 0) return@launch
            offerUndo(if (count == 1) "1 episode marked as played" else "$count episodes marked as played") {
                // Downloads freed along the way stay freed; everything else goes back.
                before.forEach { b ->
                    val now = mediaDao.getEpisode(b.id) ?: return@forEach
                    mediaDao.updateEpisode(b.copy(downloadState = now.downloadState, localPath = now.localPath))
                }
                queued.forEach { (id, pos) -> mediaDao.putInQueue(app.ember.podcasts.QueueEntity(id, pos)) }
            }
        }
    }

    fun refreshShow(id: String) {
        viewModelScope.launch {
            _podcastBusy.value = "Checking for new episodes…"
            val found = c.podcasts.refreshOne(id)
            _podcastBusy.value = null
            app.ember.playback.PlaybackStatus.notice.value = when (found.size) {
                0 -> "No new episodes"
                1 -> "1 new episode"
                else -> "${found.size} new episodes"
            }
        }
    }

    fun follow(feedUrl: String, artworkHint: String? = null, onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            _podcastBusy.value = "Following…"
            val id = c.podcasts.follow(feedUrl.trim(), artworkHint)
            _podcastBusy.value = if (id == null) "Couldn't read that feed. Check the link and try again." else null
            onDone(id)
        }
    }

    fun unfollow(id: String) {
        viewModelScope.launch { c.podcasts.unfollow(id) }
    }

    fun importOpml(uri: Uri) {
        viewModelScope.launch {
            _podcastBusy.value = "Importing your shows…"
            val (ok, total) = c.podcasts.importOpml(uri)
            _podcastBusy.value = when {
                total == 0 -> "No shows found in that file."
                ok == total -> null
                else -> "Imported $ok of $total shows. Some feeds couldn't be reached."
            }
        }
    }

    fun refreshPodcasts() {
        viewModelScope.launch {
            _podcastBusy.value = "Checking for new episodes…"
            c.podcasts.refreshAll()
            c.downloads.reconcile()
            _podcastBusy.value = null
        }
    }

    fun clearPodcastMessage() {
        _podcastBusy.value = null
    }

    fun playEpisode(id: String) = conn.playBook(PlaybackService.EPISODE_PREFIX + id)

    fun addToQueue(id: String, next: Boolean) {
        viewModelScope.launch { c.podcasts.addToQueue(id, next) }
    }

    fun removeFromQueue(id: String) {
        viewModelScope.launch { mediaDao.removeFromQueue(id) }
    }

    fun moveInQueue(id: String, up: Boolean) {
        viewModelScope.launch { c.podcasts.moveInQueue(id, up) }
    }

    fun setPlayed(id: String, played: Boolean) {
        if (!played) {
            viewModelScope.launch { c.podcasts.setPlayed(id, false) }
            return
        }
        changeEpisode(id, "Marked as played") { c.podcasts.setPlayed(id, true, keepDownload = true) }
    }

    /**
     * Played and archived episodes lose their download, but only once the Undo has
     * passed, so Undo brings back the episode exactly as it was, file and Up Next spot included.
     */
    private fun changeEpisode(id: String, message: String, change: suspend () -> Unit) {
        viewModelScope.launch {
            val before = mediaDao.getEpisode(id) ?: return@launch
            val queuedAt = mediaDao.queuePosition(id)
            change()
            offerUndo(message) {
                mediaDao.updateEpisode(before)
                if (queuedAt != null) mediaDao.putInQueue(app.ember.podcasts.QueueEntity(id, queuedAt))
            }
            delay(UNDO_WINDOW_MS + 2_000)
            val now = mediaDao.getEpisode(id) ?: return@launch
            if ((now.played || now.archived) && now.downloadState != EpisodeEntity.DL_NONE) c.downloads.removeEpisode(now)
        }
    }

    fun downloadEpisode(ep: EpisodeEntity) {
        viewModelScope.launch {
            val show = mediaDao.getPodcast(ep.podcastId)
            c.downloads.downloadEpisode(ep, wifiOnly = false, title = show?.title ?: "Podcast")
        }
    }

    fun removeEpisodeDownload(ep: EpisodeEntity) {
        viewModelScope.launch { c.downloads.removeEpisode(ep) }
    }

    /** Changes a show's own settings; [change] gets the current show and returns the new one. */
    fun updateShow(id: String, change: (PodcastEntity) -> PodcastEntity) {
        viewModelScope.launch {
            val p = mediaDao.getPodcast(id) ?: return@launch
            val n = change(p)
            mediaDao.updatePodcast(n)
            if (n.autoArchive && !p.autoArchive) c.podcasts.archivePlayed(id)
        }
    }

    fun setPodcastOptions(id: String, autoDownload: Boolean? = null, notify: Boolean? = null) =
        updateShow(id) { it.copy(autoDownload = autoDownload ?: it.autoDownload, notify = notify ?: it.notify) }

    fun setShowSpeed(id: String, speed: Float) {
        updateShow(id) { it.copy(speed = speed) }
        val np = nowPlaying.value
        if (np != null && np.loaded && np.podcastId == id) conn.setSpeed(speed)
    }

    fun setArchived(episodeId: String, archived: Boolean) {
        if (!archived) {
            viewModelScope.launch { c.podcasts.setArchived(episodeId, false) }
            return
        }
        changeEpisode(episodeId, "Archived") { c.podcasts.setArchived(episodeId, true, keepDownload = true) }
    }

    /** Reads a show's feed without following it, to show its details first. */
    suspend fun previewFeed(url: String): app.ember.podcasts.ParsedFeed? = withContext(Dispatchers.IO) {
        runCatching { app.ember.podcasts.Feeds.fetch(url) }.getOrNull()
    }

    fun setAutoDownloadCount(n: Int) = viewModelScope.launch { c.settings.setAutoDownloadCount(n) }

    // ---- Sync between devices ----

    val syncStatus: StateFlow<app.ember.sync.SyncManager.Status> = c.sync.status
    val syncPrompt: StateFlow<app.ember.sync.SyncManager.Prompt?> = c.sync.prompt

    /** Starts turning sync on. Returns Google's consent screen when it needs showing, or null when it's already on. */
    suspend fun syncBegin(activity: Activity): PendingIntent? {
        val pi = c.sync.auth.begin(activity)
        if (pi == null && c.sync.auth.isConnected) c.sync.turnOn()
        return pi
    }

    fun syncComplete(data: Intent?): Boolean {
        val ok = c.sync.auth.complete(data)
        if (ok) c.sync.turnOn()
        return ok
    }

    val syncAuthError: StateFlow<String?> = c.sync.auth.lastError

    fun turnSyncOff() = c.sync.turnOff()

    fun syncNow() {
        viewModelScope.launch { c.sync.syncNow("tapped") }
    }

    fun renameThisDevice(name: String) {
        if (name.isNotBlank()) c.sync.setDeviceName(name)
    }

    /** "Go there" moves the player to the other device's place; "Stay here" keeps yours (and sends it). */
    fun answerSyncPrompt(go: Boolean) {
        val p = c.sync.prompt.value ?: return
        if (go) {
            val np = nowPlaying.value
            if (np != null && np.loaded && np.book.id == p.bookId) seekGlobal(p.positionMs)
            else viewModelScope.launch { dao.setPlace(p.bookId, p.positionMs, System.currentTimeMillis()) }
        }
        c.sync.answer(go)
    }

    /** The device a log entry came from ("iPhone"), shown once the log has entries from more than one. */
    fun logDeviceName(e: app.ember.data.ListenEventEntity): String = c.sync.nameOf(e.device)

    fun isOtherDevice(e: app.ember.data.ListenEventEntity): Boolean = e.device.isNotEmpty() && e.device != c.sync.deviceId

    // ---- Google Drive ----

    val driveStatus: StateFlow<DriveAuth.Status> = c.driveAuth.status
    val driveError: StateFlow<String?> = c.driveAuth.lastError
    val driveDownloads: StateFlow<List<DownloadEntity>> =
        mediaDao.observeDownloads(DownloadEntity.KIND_DRIVE).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    suspend fun driveBegin(activity: Activity): PendingIntent? = c.driveAuth.begin(activity)

    fun driveComplete(data: Intent?) = c.driveAuth.complete(data)

    fun disconnectDrive() = c.driveAuth.disconnect()

    suspend fun driveFolders(folderId: String): List<DriveFile> = c.drive.list(folderId, foldersOnly = true)

    fun addDriveFolder(folderId: String, name: String) {
        viewModelScope.launch {
            val uri = LibraryScanner.DRIVE_PREFIX + folderId
            val folder = dao.getFolderByUri(uri) ?: run {
                val id = dao.insertFolder(LibraryFolderEntity(uri = uri, name = name))
                LibraryFolderEntity(id = id, uri = uri, name = name)
            }
            c.scanner.scanFolder(folder)
        }
    }

    /** Downloads every file of a Drive book for offline listening (any network). */
    fun downloadDriveBook(bookId: String) {
        viewModelScope.launch {
            val book = dao.getBook(bookId) ?: return@launch
            val token = c.driveAuth.token() ?: return@launch
            dao.getTracks(bookId).forEach { t ->
                val fileId = t.uri.removePrefix(LibraryScanner.DRIVE_TRACK_PREFIX)
                if (c.downloads.driveLocal(fileId) == null) {
                    c.downloads.downloadDriveFile(fileId, token, "${book.title} · ${t.title}", wifiOnly = false)
                }
            }
        }
    }

    fun removeDriveBookDownload(bookId: String) {
        viewModelScope.launch {
            dao.getTracks(bookId).forEach { t ->
                c.downloads.removeDriveFile(t.uri.removePrefix(LibraryScanner.DRIVE_TRACK_PREFIX))
            }
        }
    }

    suspend fun trackFileIds(bookId: String): List<String> =
        dao.getTracks(bookId).map { it.uri.removePrefix(LibraryScanner.DRIVE_TRACK_PREFIX) }

    // ---- Sound and Cast ----

    val castDevice: StateFlow<String?> = CastStatus.device

    fun setEqEnabled(on: Boolean) = viewModelScope.launch { c.settings.setEqEnabled(on) }
    fun setEqPreset(key: String) = viewModelScope.launch { c.settings.setEqPreset(key) }
    fun setEqCustom(gains: List<Int>) = viewModelScope.launch { c.settings.setEqCustom(gains) }
    fun setNarrationBoost(on: Boolean) = viewModelScope.launch { c.settings.setNarrationBoost(on) }

    // ---- Settings ----

    fun setSkipBack(sec: Int) = viewModelScope.launch { c.settings.setSkipBack(sec) }
    fun setSkipForward(sec: Int) = viewModelScope.launch { c.settings.setSkipForward(sec) }
    fun setSkipSilence(on: Boolean) = viewModelScope.launch { c.settings.setSkipSilence(on) }
    fun setAutoRewind(sec: Int) = viewModelScope.launch { c.settings.setAutoRewind(sec) }
    fun setDailyGoal(min: Int) = viewModelScope.launch { c.settings.setDailyGoal(min) }
    fun setYearlyGoal(books: Int) = viewModelScope.launch { c.settings.setYearlyGoal(books) }

    fun setResumeOnConnect(on: Boolean) = viewModelScope.launch { c.settings.setResumeOnConnect(on) }
    fun setSeriesAutoPlay(on: Boolean) = viewModelScope.launch { c.settings.setSeriesAutoPlay(on) }
    fun setClipLength(sec: Int) = viewModelScope.launch { c.settings.setClipLength(sec) }

    // ---- Playback problems and notices ----

    val playbackIssue: StateFlow<PlaybackIssue?> = PlaybackIssues.current
    val playbackNotice: StateFlow<String?> = PlaybackStatus.notice

    fun clearPlaybackNotice() {
        app.ember.playback.PlaybackStatus.notice.value = null
    }

    fun dismissPlaybackIssue() = app.ember.playback.PlaybackIssues.clear()

    /** Tries the item that failed again from where it stopped. */
    fun retryPlayback() {
        val issue = playbackIssue.value
        app.ember.playback.PlaybackIssues.clear()
        val id = issue?.itemId ?: nowPlaying.value?.book?.id ?: return
        viewModelScope.launch {
            if (id.startsWith(PlaybackService.EPISODE_PREFIX)) {
                // Picks up a new audio link if the show has moved its files.
                mediaDao.getEpisode(id.removePrefix(PlaybackService.EPISODE_PREFIX))?.let { ep ->
                    mediaDao.getPodcast(ep.podcastId)?.let { runCatching { c.podcasts.refreshAll() } }
                }
            }
            conn.reloadBook(id)
        }
    }

    /** The downloaded copy is gone: forget it and stream the episode. */
    fun streamInstead() {
        val id = playbackIssue.value?.itemId ?: return
        app.ember.playback.PlaybackIssues.clear()
        viewModelScope.launch {
            mediaDao.getEpisode(id.removePrefix(PlaybackService.EPISODE_PREFIX))?.let { c.downloads.removeEpisode(it) }
            conn.reloadBook(id)
        }
    }

    // ---- Clips ----

    val clips: StateFlow<List<ClipEntity>> = dao.observeClips().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun observeClipsFor(mediaId: String): Flow<List<app.ember.data.ClipEntity>> = dao.observeClipsFor(mediaId)

    /** The stretch of audio a clip would capture right now. */
    fun clipDraft(): app.ember.data.ClipEntity? {
        val np = nowPlaying.value ?: return null
        val end = np.position.coerceAtLeast(1_000)
        val start = (end - settings.value.clipLengthSec * 1000L).coerceAtLeast(0)
        return app.ember.data.ClipEntity(
            mediaId = np.book.id,
            title = np.book.title,
            subtitle = np.book.author,
            startMs = start,
            endMs = end,
            chapterTitle = np.chapters.lastOrNull { it.startMs <= start }?.title?.takeIf { np.chapters.size > 1 } ?: "",
            coverPath = np.book.coverPath,
        )
    }

    fun saveClip(draft: app.ember.data.ClipEntity, note: String, onSaved: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = dao.insertClip(draft.copy(note = note.trim(), createdAt = System.currentTimeMillis()))
            onSaved(id)
        }
    }

    fun updateClipNote(id: Long, note: String) {
        viewModelScope.launch { dao.getClip(id)?.let { dao.updateClip(it.copy(note = note.trim())) } }
    }

    fun deleteClip(id: Long) {
        viewModelScope.launch { dao.deleteClip(id) }
    }

    suspend fun getClip(id: Long): app.ember.data.ClipEntity? = dao.getClip(id)

    /** Plays just the clip, stopping at its end. */
    fun playClip(clip: app.ember.data.ClipEntity) {
        app.ember.playback.PlaybackStatus.clipRange.value = clip.startMs to clip.endMs
        playFrom(clip.mediaId, clip.startMs)
    }

    /** Plays a moment that hasn't been saved yet (previewing a new clip). */
    fun previewRange(mediaId: String, startMs: Long, endMs: Long) {
        app.ember.playback.PlaybackStatus.clipRange.value = startMs to endMs
        playFrom(mediaId, startMs)
    }

    /** Everything a quote card needs, from a clip or a bookmark. */
    data class CardSource(
        val title: String,
        val author: String,
        val quote: String,
        val detail: String,
        val coverPath: String?,
        val color: Int?,
    )

    suspend fun cardSource(kind: String, id: Long): CardSource? = withContext(Dispatchers.IO) {
        when (kind) {
            "clip" -> {
                // id 0 means "the newest clip".
                val k = (if (id > 0) dao.getClip(id) else dao.getClips().maxByOrNull { it.createdAt }) ?: return@withContext null
                val book = if (k.mediaId.startsWith(PlaybackService.EPISODE_PREFIX)) null else dao.getBook(k.mediaId)
                val show = if (k.mediaId.startsWith(PlaybackService.EPISODE_PREFIX)) {
                    mediaDao.getEpisode(k.mediaId.removePrefix(PlaybackService.EPISODE_PREFIX))?.let { mediaDao.getPodcast(it.podcastId) }
                } else {
                    null
                }
                CardSource(
                    title = book?.title ?: k.title,
                    author = book?.author ?: k.subtitle,
                    quote = k.note,
                    detail = listOf(k.chapterTitle, app.ember.util.formatClock(k.startMs)).filter { it.isNotBlank() }.joinToString(" · "),
                    coverPath = book?.coverPath ?: show?.artworkPath ?: k.coverPath,
                    color = book?.coverColor ?: show?.color,
                )
            }
            else -> {
                val m = dao.getAllBookmarks().firstOrNull { it.id == id } ?: return@withContext null
                val book = dao.getBook(m.bookId) ?: return@withContext null
                CardSource(
                    title = book.title,
                    author = book.author,
                    quote = m.note,
                    detail = listOf(m.chapterTitle, app.ember.util.formatClock(m.positionMs)).filter { it.isNotBlank() }.joinToString(" · "),
                    coverPath = book.coverPath,
                    color = book.coverColor,
                )
            }
        }
    }

    // ---- Search everything ----

    data class SearchResults(
        val query: String = "",
        val books: List<BookEntity> = emptyList(),
        val people: List<Pair<String, String>> = emptyList(),
        val series: List<String> = emptyList(),
        val shows: List<PodcastEntity> = emptyList(),
        val episodes: List<EpisodeEntity> = emptyList(),
        val bookmarks: List<Pair<BookmarkEntity, BookEntity>> = emptyList(),
        val clips: List<app.ember.data.ClipEntity> = emptyList(),
    ) {
        val isEmpty: Boolean get() = books.isEmpty() && people.isEmpty() && series.isEmpty() && shows.isEmpty() &&
            episodes.isEmpty() && bookmarks.isEmpty() && clips.isEmpty()
    }

    suspend fun searchEverything(raw: String): SearchResults = withContext(Dispatchers.Default) {
        val q = raw.trim()
        if (q.length < 2) return@withContext SearchResults(q)
        val needle = fold(q)
        fun hit(s: String?) = s != null && fold(s).contains(needle)
        val allBooks = books.value
        val bookHits = allBooks.filter { hit(it.title) || hit(it.author) || hit(it.narrator) || hit(it.series) }
            .sortedWith(compareByDescending<BookEntity> { hit(it.title) }.thenByDescending { it.lastPlayedAt })
        val people = buildList {
            allBooks.map { it.author }.filter { it.isNotBlank() && hit(it) }.distinct().forEach { add(it to "Author") }
            allBooks.map { it.narrator }.filter { it.isNotBlank() && hit(it) }.distinct()
                .filter { n -> none { it.first == n } }.forEach { add(it to "Narrator") }
        }
        val series = allBooks.map { it.series }.filter { it.isNotBlank() && hit(it) }.distinct()
        val shows = podcasts.value.filter { hit(it.title) || hit(it.author) }
        val episodes = withContext(Dispatchers.IO) { mediaDao.searchEpisodes("%$q%", 40) }
        val byId = allBooks.associateBy { it.id }
        val marks = withContext(Dispatchers.IO) { dao.getAllBookmarks() }
            .filter { hit(it.note) || hit(it.chapterTitle) }
            .mapNotNull { m -> byId[m.bookId]?.let { m to it } }
        val clipHits = clips.value.filter { hit(it.note) || hit(it.title) || hit(it.chapterTitle) }
        SearchResults(q, bookHits, people, series, shows, episodes, marks, clipHits)
    }

    /** Lowercase without accents, so "Bronte" finds "Brontë". */
    private fun fold(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    // ---- Downloads and storage ----

    data class StoredItem(
        val key: String,
        val title: String,
        val subtitle: String,
        val bytes: Long,
        val coverPath: String?,
        val kind: Kind,
        val played: Boolean,
        val episode: EpisodeEntity? = null,
        val bookId: String? = null,
    ) {
        enum class Kind { EPISODE, DRIVE_BOOK }
    }

    data class StorageSummary(
        val items: List<StoredItem> = emptyList(),
        val episodeBytes: Long = 0,
        val driveBytes: Long = 0,
        val coverBytes: Long = 0,
        val backupBytes: Long = 0,
        val freeBytes: Long = 0,
        val inProgress: Int = 0,
    )

    private val _storage = MutableStateFlow(StorageSummary())
    val storage: StateFlow<StorageSummary> = _storage

    fun refreshStorage() {
        viewModelScope.launch {
            _storage.value = withContext(Dispatchers.IO) { computeStorage() }
        }
    }

    private suspend fun computeStorage(): StorageSummary {
        val ctx = getApplication<Application>()
        val items = mutableListOf<StoredItem>()
        val shows = mediaDao.getPodcasts().associateBy { it.id }
        var running = 0
        for (show in shows.values) {
            for (ep in mediaDao.getDownloadsFor(show.id)) {
                if (ep.downloadState == EpisodeEntity.DL_RUNNING) {
                    running++
                    continue
                }
                val f = ep.localPath?.let { java.io.File(it) } ?: c.downloads.episodeFile(ep.id)
                items += StoredItem("ep:${ep.id}", ep.title, show.title, f.length(), show.artworkPath, StoredItem.Kind.EPISODE, ep.played, episode = ep)
            }
        }
        // Drive books: add up every downloaded file of each book.
        val driveDone = mediaDao.getDownloadsOfKind(DownloadEntity.KIND_DRIVE)
        val sizeByFile = driveDone.filter { it.state == EpisodeEntity.DL_DONE }.associate { it.targetId to java.io.File(it.path).length() }
        running += driveDone.count { it.state == EpisodeEntity.DL_RUNNING }
        for (b in dao.getBooks().filter { it.source == BookEntity.SOURCE_DRIVE }) {
            val ids = dao.getTracks(b.id).map { it.uri.removePrefix(LibraryScanner.DRIVE_TRACK_PREFIX) }
            val bytes = ids.sumOf { sizeByFile[it] ?: 0L }
            if (bytes > 0) {
                val complete = ids.all { it in sizeByFile }
                items += StoredItem(
                    "drive:${b.id}", b.title,
                    (b.author.ifBlank { "Google Drive" }) + if (complete) "" else " · partly downloaded",
                    bytes, b.coverPath, StoredItem.Kind.DRIVE_BOOK, b.status == BookEntity.STATUS_FINISHED, bookId = b.id,
                )
            }
        }
        fun dirSize(d: java.io.File?): Long = d?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        val ext = ctx.getExternalFilesDir(null)
        return StorageSummary(
            items = items.sortedByDescending { it.bytes },
            episodeBytes = items.filter { it.kind == StoredItem.Kind.EPISODE }.sumOf { it.bytes },
            driveBytes = items.filter { it.kind == StoredItem.Kind.DRIVE_BOOK }.sumOf { it.bytes },
            coverBytes = dirSize(java.io.File(ctx.filesDir, "covers")),
            backupBytes = dirSize(java.io.File(ctx.filesDir, "backups")) + dirSize(java.io.File(ctx.filesDir, "db-snapshots")),
            freeBytes = (ext ?: ctx.filesDir).usableSpace,
            inProgress = running,
        )
    }

    fun removeStored(items: List<StoredItem>) {
        viewModelScope.launch {
            items.forEach { item ->
                when (item.kind) {
                    StoredItem.Kind.EPISODE -> item.episode?.let { c.downloads.removeEpisode(it) }
                    StoredItem.Kind.DRIVE_BOOK -> item.bookId?.let { id ->
                        dao.getTracks(id).forEach { t -> c.downloads.removeDriveFile(t.uri.removePrefix(LibraryScanner.DRIVE_TRACK_PREFIX)) }
                    }
                }
            }
            _storage.value = withContext(Dispatchers.IO) { computeStorage() }
        }
    }

    // ---- Torrent downloads ----

    val torrentViews: StateFlow<List<app.ember.torrent.TorrentView>> = c.torrents.views
    val torrentWaitingForWifi: StateFlow<Boolean> = c.torrents.waitingForWifi

    sealed interface TorrentAdd {
        data object Idle : TorrentAdd
        data class Loading(val message: String) : TorrentAdd
        data class Ready(val preview: app.ember.torrent.TorrentPreview) : TorrentAdd
        data class Failed(val message: String) : TorrentAdd
    }

    private val _torrentAdd = MutableStateFlow<TorrentAdd>(TorrentAdd.Idle)
    val torrentAdd: StateFlow<TorrentAdd> = _torrentAdd

    fun resetTorrentAdd() {
        _torrentAdd.value = TorrentAdd.Idle
    }

    /** A .torrent file (content Uri) or a magnet link. */
    fun openTorrent(source: String) {
        viewModelScope.launch {
            val magnet = source.trim().startsWith("magnet:", ignoreCase = true)
            val web = source.trim().startsWith("http", ignoreCase = true)
            _torrentAdd.value = TorrentAdd.Loading(if (magnet) "Finding the book's details from people sharing it… (up to a minute and a half)" else "Reading the torrent…")
            _torrentAdd.value = try {
                TorrentAdd.Ready(
                    when {
                        magnet -> c.torrents.fetchMagnet(source)
                        web -> c.torrents.readUrl(source.trim())
                        else -> c.torrents.readFile(Uri.parse(source))
                    },
                )
            } catch (e: java.io.IOException) {
                TorrentAdd.Failed("Couldn't reach that link. Check your connection and try again.")
            } catch (e: Throwable) {
                TorrentAdd.Failed(e.message ?: "Couldn't read that torrent.")
            }
        }
    }

    /** Emulator test hook (see TorrentEngine.testPickSmallest). */
    fun testSelection(p: app.ember.torrent.TorrentPreview): Set<Int>? {
        if (!c.torrents.testPickSmallest) return null
        c.torrents.testPickSmallest = false
        val small = p.files.filter { it.isAudio }.minByOrNull { it.size } ?: return null
        return setOf(small.index)
    }

    fun takeIncomingTorrent(): String? = c.torrents.incoming.value.also { c.torrents.incoming.value = null }

    /** Starts the download. Calls [onDone] with an error message, or null once it's running. */
    fun startTorrent(selected: Set<Int>, onDone: (String?) -> Unit) {
        val p = (torrentAdd.value as? TorrentAdd.Ready)?.preview ?: return
        val s = settings.value
        if (s.torrentFolderUri.isBlank()) {
            onDone("Choose where downloaded books should go first.")
            return
        }
        viewModelScope.launch {
            val err = c.torrents.start(p, selected, s.torrentFolderUri, s.torrentFolderName)
            if (err == null) {
                app.ember.torrent.TorrentService.start(getApplication())
                _torrentAdd.value = TorrentAdd.Idle
            }
            onDone(err)
        }
    }

    fun setTorrentFolder(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
                _fileNotice.value = FileNotice("Android didn't allow Ember to save in that folder. Try another one.", true)
                return@launch
            }
            c.settings.setTorrentFolder(uri.toString(), withContext(Dispatchers.IO) { folderName(ctx, uri) })
        }
    }

    fun setTorrentWifiOnly(on: Boolean) = viewModelScope.launch { c.settings.setTorrentWifiOnly(on) }

    fun pauseTorrent(hash: String) = c.torrents.pause(hash)

    fun resumeTorrent(hash: String) {
        c.torrents.resume(hash)
        app.ember.torrent.TorrentService.start(getApplication())
    }

    fun retryTorrent(hash: String) {
        c.torrents.retry(hash)
        app.ember.torrent.TorrentService.start(getApplication())
    }

    fun cancelTorrent(hash: String) = c.torrents.cancel(hash)

    fun clearFinishedTorrents() = c.torrents.clearFinished()

    // ---- Backup and restore ----

    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage
    private val _backupBusy = MutableStateFlow(false)
    val backupBusy: StateFlow<Boolean> = _backupBusy

    fun clearBackupMessage() {
        _backupMessage.value = null
    }

    fun backUpNow() {
        viewModelScope.launch {
            _backupBusy.value = true
            val err = c.backup.backUpNow("manual")
            _backupBusy.value = false
            _backupMessage.value = err ?: "Backed up."
        }
    }

    fun setBackupFolder(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: SecurityException) {
                _backupMessage.value = "Android didn't allow Ember to save in that folder. Try another one."
                return@launch
            }
            val old = settings.value.backupFolderUri
            if (old.isNotBlank() && old != uri.toString()) {
                try {
                    ctx.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (e: Exception) {
                }
            }
            c.settings.setBackupFolder(uri.toString(), withContext(Dispatchers.IO) { folderName(ctx, uri) })
            backUpNow()
        }
    }

    fun clearBackupFolder() {
        viewModelScope.launch { c.settings.setBackupFolder("", "") }
    }

    fun saveBackupTo(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _backupBusy.value = true
            val ok = c.backup.writeTo(uri)
            _backupBusy.value = false
            _backupMessage.value = if (ok) "Backup saved." else "Couldn't save the backup there."
        }
    }

    data class PendingRestore(val text: String, val summary: app.ember.data.BackupManager.Summary, val label: String)

    private val _pendingRestore = MutableStateFlow<PendingRestore?>(null)
    /** A backup you picked, waiting for you to confirm the restore. */
    val pendingRestore: StateFlow<PendingRestore?> = _pendingRestore

    fun pickRestore(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { c.backup.readText(uri) }
            val summary = text?.let { c.backup.summarize(it) }
            if (text == null || summary == null) {
                _backupMessage.value = "That file isn't an Ember backup."
                return@launch
            }
            _pendingRestore.value = PendingRestore(text, summary, displayName(uri) ?: "Backup file")
        }
    }

    fun pickAutomaticRestore(file: java.io.File) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { file.readText() }.getOrNull() }
            val summary = text?.let { c.backup.summarize(it) }
            if (text == null || summary == null) {
                _backupMessage.value = "That backup couldn't be read."
                return@launch
            }
            _pendingRestore.value = PendingRestore(text, summary, "Automatic backup")
        }
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    fun confirmRestore() {
        val p = _pendingRestore.value ?: return
        _pendingRestore.value = null
        viewModelScope.launch {
            _backupBusy.value = true
            _backupMessage.value = "Restoring…"
            val r = c.backup.restore(p.text)
            // Books merged by hand come back together once the library is scanned.
            val dao = c.db.dao()
            if (dao.getMerges().any { dao.getBook(app.ember.data.LibraryScanner.bookIdFor(it.mergedKey)) == null }) c.scanner.scanAll()
            _backupBusy.value = false
            _backupMessage.value = r?.describe() ?: "That backup couldn't be read."
        }
    }

    suspend fun automaticBackups(): List<java.io.File> = withContext(Dispatchers.IO) { c.backup.automaticBackups() }

    // ---- Stats ----

    private fun computeStats(days: List<DailyListeningEntity>, books: List<BookEntity>, s: Settings): StatsSummary {
        val map = days.associate { it.day to it.listenedMs }
        val today = LocalDate.now()
        val t = today.toEpochDay()
        fun qualifies(d: Long) = (map[d] ?: 0L) >= 60_000L

        var streak = 0
        var d = if (qualifies(t)) t else t - 1
        while (qualifies(d)) {
            streak++
            d--
        }

        var longest = 0
        var run = 0
        var prev: Long? = null
        for (day in days.map { it.day }.sorted()) {
            if (!qualifies(day)) {
                run = 0
                prev = day
                continue
            }
            run = if (prev != null && day == prev + 1 && run > 0) run + 1 else 1
            longest = maxOf(longest, run)
            prev = day
        }

        val week = (6 downTo 0).map { i -> today.minusDays(i.toLong()) to (map[t - i] ?: 0L) }
        val last30 = (0 until 30).sumOf { i -> map[t - i] ?: 0L } / 30
        val year = today.year
        val finishedThisYear = books
            .filter { it.status == BookEntity.STATUS_FINISHED && it.finishedAt > 0 && yearOf(it.finishedAt) == year }
            .sortedByDescending { it.finishedAt }

        return StatsSummary(
            todayMs = map[t] ?: 0L,
            streak = streak,
            longestStreak = maxOf(longest, streak),
            week = week,
            totalMs = days.sumOf { it.listenedMs },
            last30AvgMs = last30,
            finishedThisYear = finishedThisYear,
            finishedAllTime = books.count { it.status == BookEntity.STATUS_FINISHED },
            inProgress = books.count { it.status == BookEntity.STATUS_IN_PROGRESS },
            dailyGoalMin = s.dailyGoalMin,
            yearlyGoal = s.yearlyGoalBooks,
        )
    }
}
