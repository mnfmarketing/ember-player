package app.ember.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ember.AppContainer
import app.ember.container
import app.ember.podcasts.Feeds
import app.ember.podcasts.QueueEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Backs up everything you'd hate to lose: progress, bookmarks, clips, shelves, listening
 * history, edited details and chapters, books in other apps, podcasts and their progress,
 * and your settings. The audio itself isn't included (it stays in your folders and Drive).
 *
 * Backups are plain JSON files. Every night Ember writes one to its own storage (keeping the
 * last 14) and, if you've picked a backup folder, a copy there too.
 *
 * Restoring merges rather than replaces. Books are matched by their id, or by title and
 * author when the files live somewhere new (a new phone). Books that aren't in your library
 * yet are remembered and matched automatically once you add their folder.
 */
class BackupManager(private val context: Context, private val c: AppContainer) {

    data class Summary(
        val createdAt: Long,
        val books: Int,
        val bookmarks: Int,
        val clips: Int,
        val shelves: Int,
        val podcasts: Int,
    )

    data class RestoreResult(
        val matchedBooks: Int,
        val waitingBooks: Int,
        val addedBookmarks: Int,
        val addedClips: Int,
        val podcasts: Int,
    ) {
        fun describe(): String = buildString {
            append("Restored $matchedBooks book${if (matchedBooks == 1) "" else "s"}")
            if (addedBookmarks > 0) append(", $addedBookmarks bookmark${if (addedBookmarks == 1) "" else "s"}")
            if (addedClips > 0) append(", $addedClips clip${if (addedClips == 1) "" else "s"}")
            if (podcasts > 0) append(", $podcasts podcast${if (podcasts == 1) "" else "s"}")
            append(".")
            if (waitingBooks > 0) {
                append(" $waitingBooks more will be matched automatically when you add the folder${if (waitingBooks == 1) "" else "s"} they're in.")
            }
        }
    }

    private val dao get() = c.db.dao()
    private val media get() = c.media.dao()
    private val lock = Mutex()

    private val internalDir: File get() = File(context.filesDir, "backups").apply { mkdirs() }
    private val pendingFile: File get() = File(context.filesDir, "pending-restore.json")

    // ---------- Writing ----------

    suspend fun export(): JSONObject = withContext(Dispatchers.IO) {
        val books = dao.getBooks()
        val root = JSONObject()
        root.put("app", "Ember")
        root.put("format", FORMAT)
        root.put("createdAt", System.currentTimeMillis())

        root.put(
            "books",
            JSONArray().apply {
                books.forEach { b ->
                    val o = JSONObject()
                        .put("id", b.id)
                        .put("title", b.title)
                        .put("author", b.author)
                        .put("narrator", b.narrator)
                        .put("series", b.series)
                        .put("seriesIndex", b.seriesIndex?.toDouble() ?: JSONObject.NULL)
                        .put("description", b.description)
                        .put("source", b.source)
                        .put("durationMs", b.durationMs)
                        .put("positionMs", b.positionMs)
                        .put("status", b.status)
                        .put("rating", b.rating)
                        .put("speed", b.speed.toDouble())
                        .put("addedAt", b.addedAt)
                        .put("lastPlayedAt", b.lastPlayedAt)
                        .put("finishedAt", b.finishedAt)
                        .put("externalApp", b.externalApp)
                        .put("externalLink", b.externalLink)
                        .put("userEdited", b.userEdited)
                        .put("customEffects", b.customEffects)
                        .put("trimSilence", b.trimSilence)
                        .put("voiceBoost", b.voiceBoost)
                        .put("skipIntroSec", b.skipIntroSec)
                        .put("skipOutroSec", b.skipOutroSec)
                    if (b.userEdited && !b.isExternal) {
                        o.put(
                            "chapters",
                            JSONArray().apply {
                                dao.getChapters(b.id).forEach { ch ->
                                    put(JSONObject().put("title", ch.title).put("startMs", ch.startMs).put("endMs", ch.endMs))
                                }
                            },
                        )
                    }
                    // Books in other apps have nothing to rescan, so their cover travels with them.
                    if (b.isExternal) {
                        b.coverPath?.let { File(it) }?.takeIf { it.exists() && it.length() in 1..600_000 }?.let {
                            o.put("cover", Base64.encodeToString(it.readBytes(), Base64.NO_WRAP))
                        }
                    }
                    put(o)
                }
            },
        )
        root.put(
            "bookmarks",
            JSONArray().apply {
                dao.getAllBookmarks().forEach { m ->
                    put(
                        JSONObject().put("bookId", m.bookId).put("positionMs", m.positionMs).put("note", m.note)
                            .put("chapterTitle", m.chapterTitle).put("createdAt", m.createdAt),
                    )
                }
            },
        )
        root.put(
            "clips",
            JSONArray().apply {
                dao.getClips().forEach { k ->
                    put(
                        JSONObject().put("mediaId", k.mediaId).put("title", k.title).put("subtitle", k.subtitle)
                            .put("startMs", k.startMs).put("endMs", k.endMs).put("note", k.note)
                            .put("chapterTitle", k.chapterTitle).put("createdAt", k.createdAt),
                    )
                }
            },
        )
        // Books merged by hand, so a restore puts them back together on the next scan.
        root.put(
            "merges",
            JSONArray().apply {
                dao.getMerges().forEach { m ->
                    put(JSONObject().put("mergedKey", m.mergedKey).put("idx", m.idx).put("memberKey", m.memberKey).put("title", m.title).put("folderId", m.folderId))
                }
            },
        )
        val links = dao.getShelfLinks()
        root.put(
            "shelves",
            JSONArray().apply {
                dao.getShelves().forEach { s ->
                    put(
                        JSONObject().put("name", s.name).put("createdAt", s.createdAt)
                            .put("books", JSONArray(links.filter { it.shelfId == s.id }.map { it.bookId })),
                    )
                }
            },
        )
        root.put(
            "listening",
            JSONArray().apply {
                dao.getAllDays().filter { it.listenedMs > 0 }.forEach { put(JSONObject().put("day", it.day).put("ms", it.listenedMs)) }
            },
        )

        val shows = media.getPodcasts()
        val feedOf = shows.associate { it.id to it.feedUrl }
        root.put(
            "podcasts",
            JSONArray().apply {
                shows.forEach { p ->
                    put(
                        JSONObject().put("feedUrl", p.feedUrl).put("title", p.title).put("autoDownload", p.autoDownload)
                            .put("notify", p.notify).put("speed", p.speed.toDouble())
                            .put("autoQueue", p.autoQueue).put("autoArchive", p.autoArchive)
                            .put("skipIntroSec", p.skipIntroSec).put("skipOutroSec", p.skipOutroSec)
                            .put("customEffects", p.customEffects).put("trimSilence", p.trimSilence).put("voiceBoost", p.voiceBoost),
                    )
                }
            },
        )
        root.put(
            "episodes",
            JSONArray().apply {
                media.getEpisodesWithProgress().forEach { e ->
                    val feed = feedOf[e.podcastId] ?: return@forEach
                    put(
                        JSONObject().put("feedUrl", feed).put("guid", e.guid).put("positionMs", e.positionMs)
                            .put("played", e.played).put("playedAt", e.playedAt).put("lastPlayedAt", e.lastPlayedAt)
                            .put("archived", e.archived),
                    )
                }
            },
        )
        root.put(
            "queue",
            JSONArray().apply {
                media.getQueue().forEach { e ->
                    val feed = feedOf[e.podcastId] ?: return@forEach
                    put(JSONObject().put("feedUrl", feed).put("guid", e.guid))
                }
            },
        )

        val s = c.settings.current()
        root.put(
            "settings",
            JSONObject()
                .put("skipBackSec", s.skipBackSec)
                .put("skipForwardSec", s.skipForwardSec)
                .put("skipSilence", s.skipSilence)
                .put("autoRewindSec", s.autoRewindSec)
                .put("dailyGoalMin", s.dailyGoalMin)
                .put("yearlyGoalBooks", s.yearlyGoalBooks)
                .put("podcastListView", s.podcastListView)
                .put("bookTimeLeft", s.bookTimeLeft)
                .put("playerPaneFraction", s.playerPaneFraction.toDouble())
                .put("eqEnabled", s.eqEnabled)
                .put("eqPreset", s.eqPreset)
                .put("eqCustom", JSONArray(s.eqCustom))
                .put("narrationBoost", s.narrationBoost)
                .put("resumeOnConnect", s.resumeOnConnect)
                .put("seriesAutoPlay", s.seriesAutoPlay)
                .put("clipLengthSec", s.clipLengthSec)
                .put("carModeAuto", s.carModeAuto)
                .put("rateOnFinish", s.rateOnFinish)
                .put("btnDouble", s.btnDouble)
                .put("btnTriple", s.btnTriple)
                .put("btnLong", s.btnLong)
                .put("btnNext", s.btnNext)
                .put("btnPrevious", s.btnPrevious)
                .put("sleepFade", s.sleepFade)
                .put("bedtimeOn", s.bedtimeOn)
                .put("bedtimeFromMin", s.bedtimeFromMin)
                .put("bedtimeToMin", s.bedtimeToMin)
                .put("bedtimeTimerMin", s.bedtimeTimerMin)
                .put("upNext", JSONArray(s.upNext)),
        )
        root
    }

    /**
     * Writes a backup to Ember's own storage, plus a copy in your backup folder if you chose one.
     * Returns a short error message for the folder copy, or null if everything worked.
     */
    suspend fun backUpNow(tag: String = "auto"): String? = lock.withLock {
        withContext(Dispatchers.IO) {
            val json = export().toString()
            val stamp = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(Date())
            File(internalDir, "ember-$stamp-$tag.json").writeText(json)
            internalDir.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(KEEP_INTERNAL)
                ?.forEach { it.delete() }
            val folderError = copyToFolder(json, stamp)
            c.settings.setLastBackup(System.currentTimeMillis())
            folderError
        }
    }

    private suspend fun copyToFolder(json: String, stamp: String): String? {
        val tree = c.settings.current().backupFolderUri.takeIf { it.isNotBlank() }?.let { Uri.parse(it) } ?: return null
        return try {
            val resolver = context.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(resolver, parent, "application/json", "Ember backup $stamp.json")
                ?: return "Couldn't create a file in your backup folder."
            resolver.openOutputStream(doc, "wt")?.use { it.write(json.toByteArray()) } ?: return "Couldn't write to your backup folder."
            pruneFolder(tree)
            null
        } catch (e: SecurityException) {
            "Ember lost permission to your backup folder. Choose it again in Settings."
        } catch (e: Exception) {
            "Couldn't save to your backup folder (${e.message ?: "unknown error"})."
        }
    }

    /** Keeps the newest backups in your folder; only ever touches files Ember named. */
    private fun pruneFolder(tree: Uri) {
        val resolver = context.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val ours = mutableListOf<Pair<String, String>>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getString(0) ?: continue
                val name = cur.getString(1) ?: continue
                if (name.startsWith("Ember backup ") && name.endsWith(".json")) ours += id to name
            }
        }
        ours.sortedByDescending { it.second }.drop(KEEP_FOLDER).forEach { (id, _) ->
            try {
                DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id))
            } catch (e: Exception) {
            }
        }
    }

    suspend fun writeTo(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = export().toString(2)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } != null
        } catch (e: Exception) {
            false
        }
    }

    fun automaticBackups(): List<File> =
        internalDir.listFiles { f -> f.name.endsWith(".json") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    // ---------- Reading ----------

    fun readText(uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (e: Exception) {
        null
    }

    fun summarize(text: String): Summary? = try {
        val o = JSONObject(text)
        if (o.optString("app") != "Ember") {
            null
        } else {
            Summary(
                createdAt = o.optLong("createdAt"),
                books = o.optJSONArray("books")?.length() ?: 0,
                bookmarks = o.optJSONArray("bookmarks")?.length() ?: 0,
                clips = o.optJSONArray("clips")?.length() ?: 0,
                shelves = o.optJSONArray("shelves")?.length() ?: 0,
                podcasts = o.optJSONArray("podcasts")?.length() ?: 0,
            )
        }
    } catch (e: Exception) {
        null
    }

    // ---------- Restoring ----------

    /** Merges a backup into your library. A backup of the current state is saved first. */
    suspend fun restore(text: String): RestoreResult? {
        val root = try {
            JSONObject(text).takeIf { it.optString("app") == "Ember" }
        } catch (e: Exception) {
            null
        } ?: return null
        backUpNow("before-restore")
        return lock.withLock {
            withContext(Dispatchers.IO) {
                restoreMerges(root)
                val books = applyBooks(root)
                restoreListening(root)
                restoreSettings(root)
                val shows = restorePodcasts(root)
                RestoreResult(books.matched, books.waiting, books.bookmarks, books.clips, shows)
            }
        }
    }

    private suspend fun restoreMerges(root: JSONObject) {
        val list = root.optJSONArray("merges") ?: return
        val have = dao.getMerges().map { it.mergedKey }.toSet()
        val rows = (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.optString("mergedKey") !in have && it.optString("memberKey").isNotBlank() }
            .map { BookMergeEntity(it.getString("mergedKey"), it.optInt("idx"), it.getString("memberKey"), it.optString("title"), it.optLong("folderId")) }
        if (rows.isNotEmpty()) dao.insertMerges(rows)
    }

    /** Called after every library scan: matches books from an earlier restore that have now appeared. */
    suspend fun applyPending() {
        val f = pendingFile
        if (!f.exists()) return
        lock.withLock {
            withContext(Dispatchers.IO) {
                val root = try {
                    JSONObject(f.readText())
                } catch (e: Exception) {
                    f.delete()
                    return@withContext
                }
                applyBooks(root)
            }
        }
    }

    fun hasPending(): Boolean = pendingFile.exists()

    private data class BookOutcome(val matched: Int, val waiting: Int, val bookmarks: Int, val clips: Int)

    private fun key(title: String, author: String) =
        (title.lowercase().replace(Regex("[^a-z0-9]"), "") + "|" + author.lowercase().replace(Regex("[^a-z0-9]"), ""))

    private suspend fun applyBooks(root: JSONObject): BookOutcome {
        val existing = dao.getBooks()
        val byId = existing.associateBy { it.id }
        val byKey = existing.groupBy { key(it.title, it.author) }
        val usedTargets = HashSet<String>()
        val idMap = HashMap<String, String>()
        val unmatched = JSONArray()
        var matched = 0

        val arr = root.optJSONArray("books") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val oldId = o.getString("id")
            val external = o.optString("source") == BookEntity.SOURCE_EXTERNAL
            val target: BookEntity? = byId[oldId]
                ?: byKey[key(o.optString("title"), o.optString("author"))]?.firstOrNull { it.id !in usedTargets && it.isExternal == external }
            if (target == null) {
                if (external) {
                    val created = createExternal(o)
                    idMap[oldId] = created
                    matched++
                } else {
                    unmatched.put(o)
                }
                continue
            }
            usedTargets += target.id
            idMap[oldId] = target.id
            mergeBook(target, o)
            matched++
        }

        var bookmarks = 0
        val waitingMarks = JSONArray()
        root.optJSONArray("bookmarks")?.let { marks ->
            val have = dao.getAllBookmarks().map { it.bookId to it.positionMs / 1000 }.toHashSet()
            for (i in 0 until marks.length()) {
                val m = marks.getJSONObject(i)
                val newId = idMap[m.getString("bookId")]
                if (newId == null) {
                    waitingMarks.put(m)
                    continue
                }
                val pos = m.optLong("positionMs")
                if ((newId to pos / 1000) in have) continue
                dao.insertBookmark(
                    BookmarkEntity(
                        bookId = newId,
                        positionMs = pos,
                        note = m.optString("note"),
                        chapterTitle = m.optString("chapterTitle"),
                        createdAt = m.optLong("createdAt", System.currentTimeMillis()),
                    ),
                )
                bookmarks++
            }
        }

        var clips = 0
        val waitingClips = JSONArray()
        root.optJSONArray("clips")?.let { list ->
            val have = dao.getClips().map { it.mediaId to it.startMs / 1000 }.toHashSet()
            for (i in 0 until list.length()) {
                val k = list.getJSONObject(i)
                val oldMedia = k.getString("mediaId")
                val newMedia = if (oldMedia.startsWith("episode:")) oldMedia else idMap[oldMedia]
                if (newMedia == null) {
                    waitingClips.put(k)
                    continue
                }
                val start = k.optLong("startMs")
                if ((newMedia to start / 1000) in have) continue
                val cover = if (newMedia.startsWith("episode:")) {
                    media.getEpisode(newMedia.removePrefix("episode:"))?.let { media.getPodcast(it.podcastId)?.artworkPath }
                } else {
                    dao.getBook(newMedia)?.coverPath
                }
                dao.insertClip(
                    ClipEntity(
                        mediaId = newMedia,
                        title = k.optString("title"),
                        subtitle = k.optString("subtitle"),
                        startMs = start,
                        endMs = k.optLong("endMs"),
                        note = k.optString("note"),
                        chapterTitle = k.optString("chapterTitle"),
                        coverPath = cover,
                        createdAt = k.optLong("createdAt", System.currentTimeMillis()),
                    ),
                )
                clips++
            }
        }

        val waitingShelves = JSONArray()
        root.optJSONArray("shelves")?.let { list ->
            val shelves = dao.getShelves().associateBy { it.name.lowercase() }.toMutableMap()
            for (i in 0 until list.length()) {
                val s = list.getJSONObject(i)
                val name = s.optString("name").trim()
                if (name.isBlank()) continue
                val shelfId = shelves[name.lowercase()]?.id ?: dao.insertShelf(ShelfEntity(name = name, createdAt = s.optLong("createdAt", System.currentTimeMillis()))).also {
                    shelves[name.lowercase()] = ShelfEntity(id = it, name = name)
                }
                val ids = s.optJSONArray("books") ?: JSONArray()
                val waiting = JSONArray()
                for (j in 0 until ids.length()) {
                    val newId = idMap[ids.getString(j)]
                    if (newId != null) dao.addToShelf(ShelfBookEntity(shelfId, newId)) else waiting.put(ids.getString(j))
                }
                if (waiting.length() > 0) waitingShelves.put(JSONObject().put("name", name).put("books", waiting))
            }
        }

        if (unmatched.length() == 0) {
            pendingFile.delete()
        } else {
            val pending = JSONObject()
                .put("app", "Ember")
                .put("books", unmatched)
                .put("bookmarks", waitingMarks)
                .put("clips", waitingClips)
                .put("shelves", waitingShelves)
            pendingFile.writeText(pending.toString())
        }
        return BookOutcome(matched, unmatched.length(), bookmarks, clips)
    }

    /** Takes the backup's progress if it's more recent, and its details and chapters if you'd edited them. */
    private suspend fun mergeBook(target: BookEntity, o: JSONObject) {
        val backupPlayed = o.optLong("lastPlayedAt")
        var b = target
        val newer = backupPlayed > target.lastPlayedAt ||
            (backupPlayed == target.lastPlayedAt && target.status == BookEntity.STATUS_NEW && target.positionMs == 0L)
        if (newer) {
            b = b.copy(
                positionMs = o.optLong("positionMs").coerceIn(0, maxOf(b.durationMs, 0)),
                status = o.optInt("status", b.status),
                lastPlayedAt = backupPlayed,
                finishedAt = o.optLong("finishedAt", b.finishedAt),
                speed = o.optDouble("speed", b.speed.toDouble()).toFloat(),
            )
        }
        if (b.rating == 0) b = b.copy(rating = o.optInt("rating"))
        if (b.skipIntroSec == 0 && b.skipOutroSec == 0 && (o.optInt("skipIntroSec") > 0 || o.optInt("skipOutroSec") > 0)) {
            b = b.copy(skipIntroSec = o.optInt("skipIntroSec"), skipOutroSec = o.optInt("skipOutroSec"))
        }
        if (o.optBoolean("customEffects") && !b.customEffects) {
            b = b.copy(customEffects = true, trimSilence = o.optBoolean("trimSilence"), voiceBoost = o.optBoolean("voiceBoost"))
        }
        if (o.optBoolean("userEdited") && !b.userEdited) {
            b = b.copy(
                title = o.optString("title").ifBlank { b.title },
                author = o.optString("author"),
                narrator = o.optString("narrator"),
                series = o.optString("series"),
                seriesIndex = if (o.isNull("seriesIndex")) null else o.optDouble("seriesIndex").toFloat(),
                description = o.optString("description"),
                userEdited = true,
            )
            val chapters = o.optJSONArray("chapters")
            val sameLength = kotlin.math.abs(o.optLong("durationMs") - b.durationMs) < 5_000
            if (chapters != null && chapters.length() > 0 && sameLength) {
                dao.replaceChapters(
                    b.id,
                    (0 until chapters.length()).map { i ->
                        val ch = chapters.getJSONObject(i)
                        ChapterEntity(b.id, i, ch.optString("title"), ch.optLong("startMs"), ch.optLong("endMs"))
                    },
                )
            }
        }
        if (b != target) dao.updateBook(b)
    }

    private suspend fun createExternal(o: JSONObject): String {
        val id = o.getString("id")
        var cover: CoverStore.SavedCover? = null
        o.optString("cover").takeIf { it.isNotBlank() }?.let {
            cover = try {
                c.covers.saveBytes(id, Base64.decode(it, Base64.DEFAULT))
            } catch (e: Exception) {
                null
            }
        }
        dao.upsertBook(
            BookEntity(
                id = id,
                title = o.optString("title"),
                author = o.optString("author"),
                narrator = o.optString("narrator"),
                series = o.optString("series"),
                seriesIndex = if (o.isNull("seriesIndex")) null else o.optDouble("seriesIndex").toFloat(),
                description = o.optString("description"),
                coverPath = cover?.path,
                coverColor = cover?.color,
                source = BookEntity.SOURCE_EXTERNAL,
                durationMs = BookEntity.EXTERNAL_SCALE,
                positionMs = o.optLong("positionMs"),
                status = o.optInt("status"),
                rating = o.optInt("rating"),
                addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                lastPlayedAt = o.optLong("lastPlayedAt"),
                finishedAt = o.optLong("finishedAt"),
                externalApp = o.optString("externalApp"),
                externalLink = o.optString("externalLink"),
                userEdited = true,
            ),
        )
        return id
    }

    private suspend fun restoreListening(root: JSONObject) {
        val have = dao.getAllDays().associate { it.day to it.listenedMs }
        val days = root.optJSONArray("listening") ?: return
        for (i in 0 until days.length()) {
            val d = days.getJSONObject(i)
            val day = d.optLong("day")
            val ms = d.optLong("ms")
            if (ms > (have[day] ?: 0L)) dao.setDay(day, ms)
        }
    }

    private suspend fun restoreSettings(root: JSONObject) {
        val s = root.optJSONObject("settings") ?: return
        val cur = c.settings.current()
        val eq = s.optJSONArray("eqCustom")?.let { a -> (0 until a.length()).map { a.optInt(it) } }?.takeIf { it.size == 5 }
        c.settings.restore(
            cur.copy(
                skipBackSec = s.optInt("skipBackSec", cur.skipBackSec),
                skipForwardSec = s.optInt("skipForwardSec", cur.skipForwardSec),
                skipSilence = s.optBoolean("skipSilence", cur.skipSilence),
                autoRewindSec = s.optInt("autoRewindSec", cur.autoRewindSec),
                dailyGoalMin = s.optInt("dailyGoalMin", cur.dailyGoalMin),
                yearlyGoalBooks = s.optInt("yearlyGoalBooks", cur.yearlyGoalBooks),
                podcastListView = s.optBoolean("podcastListView", cur.podcastListView),
                bookTimeLeft = s.optBoolean("bookTimeLeft", cur.bookTimeLeft),
                playerPaneFraction = s.optDouble("playerPaneFraction", cur.playerPaneFraction.toDouble()).toFloat(),
                eqEnabled = s.optBoolean("eqEnabled", cur.eqEnabled),
                eqPreset = s.optString("eqPreset", cur.eqPreset),
                eqCustom = eq ?: cur.eqCustom,
                narrationBoost = s.optBoolean("narrationBoost", cur.narrationBoost),
                resumeOnConnect = s.optBoolean("resumeOnConnect", cur.resumeOnConnect),
                seriesAutoPlay = s.optBoolean("seriesAutoPlay", cur.seriesAutoPlay),
                clipLengthSec = s.optInt("clipLengthSec", cur.clipLengthSec),
                carModeAuto = s.optBoolean("carModeAuto", cur.carModeAuto),
                rateOnFinish = s.optBoolean("rateOnFinish", cur.rateOnFinish),
                btnDouble = s.optInt("btnDouble", cur.btnDouble),
                btnTriple = s.optInt("btnTriple", cur.btnTriple),
                btnLong = s.optInt("btnLong", cur.btnLong),
                btnNext = s.optInt("btnNext", cur.btnNext),
                btnPrevious = s.optInt("btnPrevious", cur.btnPrevious),
                sleepFade = s.optBoolean("sleepFade", cur.sleepFade),
                bedtimeOn = s.optBoolean("bedtimeOn", cur.bedtimeOn),
                bedtimeFromMin = s.optInt("bedtimeFromMin", cur.bedtimeFromMin),
                bedtimeToMin = s.optInt("bedtimeToMin", cur.bedtimeToMin),
                bedtimeTimerMin = s.optInt("bedtimeTimerMin", cur.bedtimeTimerMin),
                upNext = (cur.upNext + (s.optJSONArray("upNext")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()))
                    .filter { it.isNotBlank() }.distinct(),
            ),
        )
    }

    /** Follows any shows you're missing, then puts back episode progress and Up Next. */
    private suspend fun restorePodcasts(root: JSONObject): Int {
        val shows = root.optJSONArray("podcasts") ?: return 0
        var count = 0
        for (i in 0 until shows.length()) {
            val s = shows.getJSONObject(i)
            val feed = s.optString("feedUrl").takeIf { it.isNotBlank() } ?: continue
            val id = c.podcasts.follow(feed) ?: continue
            count++
            media.getPodcast(id)?.let {
                media.updatePodcast(
                    it.copy(
                        autoDownload = s.optBoolean("autoDownload", it.autoDownload),
                        notify = s.optBoolean("notify", it.notify),
                        speed = s.optDouble("speed", it.speed.toDouble()).toFloat(),
                        autoQueue = s.optBoolean("autoQueue", it.autoQueue),
                        autoArchive = s.optBoolean("autoArchive", it.autoArchive),
                        skipIntroSec = s.optInt("skipIntroSec", it.skipIntroSec),
                        skipOutroSec = s.optInt("skipOutroSec", it.skipOutroSec),
                        customEffects = s.optBoolean("customEffects", it.customEffects),
                        trimSilence = s.optBoolean("trimSilence", it.trimSilence),
                        voiceBoost = s.optBoolean("voiceBoost", it.voiceBoost),
                    ),
                )
            }
        }
        root.optJSONArray("episodes")?.let { eps ->
            for (i in 0 until eps.length()) {
                val e = eps.getJSONObject(i)
                val ep = media.getEpisodeByGuid(Feeds.podcastId(e.optString("feedUrl")), e.optString("guid")) ?: continue
                if (e.optLong("lastPlayedAt") < ep.lastPlayedAt && !e.optBoolean("played")) continue
                media.updateEpisode(
                    ep.copy(
                        positionMs = e.optLong("positionMs"),
                        played = e.optBoolean("played"),
                        playedAt = e.optLong("playedAt"),
                        lastPlayedAt = maxOf(ep.lastPlayedAt, e.optLong("lastPlayedAt")),
                        archived = e.optBoolean("archived", e.optBoolean("played")),
                    ),
                )
            }
        }
        root.optJSONArray("queue")?.let { q ->
            val have = media.getQueue().map { it.id }.toHashSet()
            var pos = (media.maxQueuePosition() ?: 0L) + 1
            for (i in 0 until q.length()) {
                val e = q.getJSONObject(i)
                val ep = media.getEpisodeByGuid(Feeds.podcastId(e.optString("feedUrl")), e.optString("guid")) ?: continue
                if (ep.id in have || ep.played) continue
                media.putInQueue(QueueEntity(ep.id, pos++))
            }
        }
        return count
    }

    companion object {
        const val FORMAT = 1
        private const val KEEP_INTERNAL = 14
        private const val KEEP_FOLDER = 10

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("nightly-backup", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (c.db.dao().getBooks().isEmpty() && c.media.dao().getPodcasts().isEmpty()) return Result.success()
        c.backup.backUpNow()
        return Result.success()
    }
}
