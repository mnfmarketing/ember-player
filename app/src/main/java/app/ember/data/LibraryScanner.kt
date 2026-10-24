package app.ember.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import app.ember.drive.DriveApi
import app.ember.drive.DriveAuth
import app.ember.drive.DriveFile
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID
import app.ember.container

/** Walks folders picked by the user and turns audio files into books. */
class LibraryScanner(
    private val context: Context,
    private val db: EmberDatabase,
    private val covers: CoverStore,
    private val drive: DriveApi,
    private val driveAuth: DriveAuth,
) {
    data class Progress(val folder: String, val booksFound: Int, val current: String)

    private val dao = db.dao()
    private val mutex = Mutex()
    private val _problems = MutableStateFlow<Map<Long, String>>(emptyMap())
    /** Folders Ember couldn't read on the last scan, with a plain explanation. */
    val problems: StateFlow<Map<Long, String>> = _problems

    private fun problem(folder: LibraryFolderEntity, text: String?) {
        _problems.value = if (text == null) _problems.value - folder.id else _problems.value + (folder.id to text)
    }

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    private data class Doc(
        val docId: String,
        val name: String,
        val mime: String,
        val uri: Uri,
    ) {
        val isDir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
        val ext get() = name.substringAfterLast('.', "").lowercase()
        val isAudio get() = mime.startsWith("audio/") || ext in AUDIO_EXT
        val isImage get() = mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp")
        val isPdf get() = mime == "application/pdf" || ext == "pdf"
    }

    private data class Candidate(
        val key: String,
        val files: List<Doc>,
        val fallbackTitle: String,
        val cover: Doc?,
        val sourceUri: Uri,
        /** A companion PDF beside the audio (maps, extras), if there is one. */
        val pdf: Doc? = null,
        /** For a book made of several (CD1, CD2, ... or merged by hand): each part's scan key and file count, in order. */
        val parts: List<Pair<String, Int>> = emptyList(),
        /** The name you gave a book merged by hand. */
        val forcedTitle: String? = null,
    )

    /** Runs after every scan (used to match books from a restored backup). */
    var afterScan: (suspend () -> Unit)? = null

    suspend fun scanAll() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                for (folder in dao.getFolders()) scanFolderLocked(folder)
                _progress.value = null
            }
        }
        runCatching { afterScan?.invoke() }
    }

    suspend fun scanFolder(folder: LibraryFolderEntity) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                scanFolderLocked(folder)
                _progress.value = null
            }
        }
        runCatching { afterScan?.invoke() }
    }

    private suspend fun scanFolderLocked(folder: LibraryFolderEntity, keep: Set<String> = emptySet()) {
        if (folder.uri.startsWith(DRIVE_PREFIX)) {
            scanDriveLocked(folder)
            return
        }
        val treeUri = Uri.parse(folder.uri)
        val rootId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            problem(folder, "Ember can't open this folder. Remove it and add it again.")
            return
        }
        val stillAllowed = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == folder.uri && it.isReadPermission }
        _progress.value = Progress(folder.name, 0, "Looking for books")
        val candidates = mutableListOf<Candidate>()
        val listedRoot = try {
            walk(treeUri, rootId, folder.name, 0, candidates)
            true
        } catch (e: Exception) {
            false
        }
        if (!listedRoot) {
            problem(
                folder,
                if (!stillAllowed) {
                    "Android no longer lets Ember read this folder. Choose it again to give access back. Your progress is kept."
                } else {
                    "Ember couldn't read this folder. It may have been moved, renamed or deleted, or the SD card is out."
                },
            )
            return
        }
        problem(folder, null)
        applyManualMerges(folder.id, candidates)

        val foundIds = HashSet<String>(keep)
        candidates.forEachIndexed { i, c ->
            val id = bookIdFor(c.key)
            foundIds += id
            _progress.value = Progress(folder.name, i + 1, c.fallbackTitle)
            try {
                importCandidate(id, c, folder.id)
            } catch (e: Exception) {
                // Skip unreadable books; keep scanning.
            }
        }

        if (candidates.isNotEmpty()) {
            dao.getBooksInFolder(folder.id)
                .filter { it.id !in foundIds && it.source == BookEntity.SOURCE_LOCAL }
                .forEach { dao.deleteBook(it.id) }
        }
        dao.updateFolder(folder.copy(lastScanAt = System.currentTimeMillis()))
    }

    private fun walk(treeUri: Uri, docId: String, name: String, depth: Int, out: MutableList<Candidate>) {
        if (depth > 8) return
        val children = listChildren(treeUri, docId)
        val audio = children.filter { !it.isDir && it.isAudio }.sortedWith(naturalOrder)
        val images = children.filter { !it.isDir && it.isImage }
        val pdfs = children.filter { !it.isDir && it.isPdf }.sortedWith(naturalOrder)
        val dirs = children.filter { it.isDir }.sortedWith(naturalOrder)
        val folderCover = pickCover(images)
        val folderUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

        // CD1, CD2, CD3 (or Disc 1, Part 2...) folders with nothing else beside them: one book.
        if (audio.isEmpty() && dirs.size >= 2 && dirs.all { isDiscName(it.name) }) {
            val discs = dirs.map { it to listChildren(treeUri, it.docId) }
            val usable = discs.all { (_, kids) -> kids.none { k -> k.isDir } && kids.any { k -> !k.isDir && k.isAudio } }
            if (usable) {
                val parts = discs.map { (d, kids) -> d to kids.filter { k -> !k.isDir && k.isAudio }.sortedWith(naturalOrder) }
                val discCover = discs.firstNotNullOfOrNull { (_, kids) -> pickCover(kids.filter { k -> !k.isDir && k.isImage }) }
                val pdf = pdfs.firstOrNull() ?: discs.firstNotNullOfOrNull { (_, kids) -> kids.filter { k -> !k.isDir && k.isPdf }.minWithOrNull(naturalOrder) }
                out += Candidate(
                    key = docId,
                    files = parts.flatMap { it.second },
                    fallbackTitle = name,
                    cover = folderCover ?: discCover,
                    sourceUri = folderUri,
                    pdf = pdf,
                    parts = parts.map { (d, files) -> d.docId to files.size },
                )
                return
            }
        }

        if (audio.isNotEmpty()) {
            val allContainers = audio.all { it.ext in CONTAINER_EXT }
            val loose = depth == 0 && (dirs.isNotEmpty() || allContainers) ||
                (allContainers && audio.size > 1)
            if (loose) {
                audio.forEach { f ->
                    val base = f.name.substringBeforeLast('.').lowercase()
                    val pdf = pdfs.firstOrNull { it.name.substringBeforeLast('.').lowercase() == base } ?: pdfs.singleOrNull()?.takeIf { audio.size == 1 }
                    out += Candidate(f.docId, listOf(f), f.name.substringBeforeLast('.'), matchingCover(images, f) ?: folderCover.takeIf { audio.size == 1 }, f.uri, pdf)
                }
            } else {
                out += Candidate(docId, audio, name, folderCover, folderUri, pdfs.firstOrNull())
            }
        }
        for (d in dirs) walk(treeUri, d.docId, d.name, depth + 1, out)
    }

    private fun listChildren(treeUri: Uri, parentId: String): List<Doc> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        val out = mutableListOf<Doc>()
        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: ""
                if (name.startsWith(".")) continue
                out += Doc(id, name, c.getString(2) ?: "", DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
            }
        }
        return out
    }

    private fun pickCover(images: List<Doc>): Doc? {
        if (images.isEmpty()) return null
        val preferred = listOf("cover", "folder", "front", "art", "poster")
        return images.minByOrNull { img ->
            val base = img.name.substringBeforeLast('.').lowercase()
            val rank = preferred.indexOfFirst { base.contains(it) }
            if (rank < 0) 99 else rank
        }
    }

    private fun matchingCover(images: List<Doc>, audio: Doc): Doc? {
        val base = audio.name.substringBeforeLast('.').lowercase()
        return images.firstOrNull { it.name.substringBeforeLast('.').lowercase() == base }
    }

    /** Books you merged by hand in this folder replace their parts in the scan. */
    private suspend fun applyManualMerges(folderId: Long, candidates: MutableList<Candidate>) {
        // Matched by the parts' keys (the folder's id can differ after restoring a backup on a new phone).
        val merges = dao.getMerges().groupBy { it.mergedKey }
        for ((mergedKey, rows) in merges) {
            val members = rows.sortedBy { it.idx }.mapNotNull { r -> candidates.firstOrNull { it.key == r.memberKey } }
            if (members.size < 2) continue
            val at = candidates.indexOf(members.first())
            candidates.removeAll(members.toSet())
            candidates.add(
                at.coerceAtMost(candidates.size),
                Candidate(
                    key = mergedKey,
                    files = members.flatMap { it.files },
                    fallbackTitle = rows.first().title,
                    cover = members.firstNotNullOfOrNull { it.cover },
                    sourceUri = members.first().sourceUri,
                    pdf = members.firstNotNullOfOrNull { it.pdf },
                    parts = members.map { it.key to it.files.size },
                    forcedTitle = rows.first().title,
                ),
            )
        }
    }

    private suspend fun importCandidate(id: String, c: Candidate, folderId: Long) {
        val existing = dao.getBook(id)
        if (existing != null) {
            val tracks = dao.getTracks(id)
            if (tracks.size == c.files.size) {
                // A PDF added to the folder later still shows up (unless you removed it by hand).
                if (existing.pdfUri == null && c.pdf != null) dao.setPdf(id, c.pdf.uri.toString())
                return
            }
        }

        val retriever = MediaMetadataRetriever()
        val trackRows = mutableListOf<TrackEntity>()
        var title: String? = null
        var author: String? = null
        var narrator: String? = null
        var art: ByteArray? = null
        var offset = 0L
        val chapters = mutableListOf<ChapterEntity>()

        c.files.forEachIndexed { index, f ->
            var duration = 0L
            var trackTitle: String? = null
            try {
                retriever.setDataSource(context, f.uri)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                trackTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                if (index == 0) {
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
                    narrator = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                    if (c.files.size == 1 && title.isNullOrBlank()) title = trackTitle
                }
                if (art == null && index == 0) art = retriever.embeddedPicture
            } catch (e: Exception) {
                // Unreadable file; still keep it as a track.
            }
            val fileTitle = f.name.substringBeforeLast('.')
            trackRows += TrackEntity(id, index, f.uri.toString(), trackTitle?.takeIf { it.isNotBlank() } ?: fileTitle, duration)

            if (c.files.size == 1) {
                val raw = if (f.ext in CONTAINER_EXT) Mp4ChapterParser.parse(context, f.uri) else emptyList()
                if (raw.isNotEmpty()) {
                    raw.forEachIndexed { ci, rc ->
                        val end = raw.getOrNull(ci + 1)?.startMs ?: duration
                        chapters += ChapterEntity(id, ci, rc.title, rc.startMs, maxOf(end, rc.startMs))
                    }
                } else {
                    chapters += ChapterEntity(id, 0, title?.takeIf { it.isNotBlank() } ?: c.fallbackTitle, 0, duration)
                }
            } else {
                chapters += ChapterEntity(id, index, cleanChapterTitle(trackTitle, fileTitle), offset, offset + duration)
            }
            offset += duration
        }
        try {
            retriever.release()
        } catch (e: Exception) {
        }

        val bookTitle = c.forcedTitle
            ?: title?.takeIf { it.isNotBlank() }?.let { if (c.parts.isNotEmpty()) stripDisc(it) else it }
            ?: c.fallbackTitle
        if (c.parts.isNotEmpty()) renumberAcrossDiscs(chapters, bookTitle)
        var coverPath = existing?.coverPath
        var coverColor = existing?.coverColor
        if (coverPath == null) {
            val saved = c.cover?.let { covers.saveFromUri(id, it.uri) } ?: art?.let { covers.saveBytes(id, it) }
            if (saved != null) {
                coverPath = saved.path
                coverColor = saved.color
            }
        }

        val base = existing ?: BookEntity(id = id, title = bookTitle)
        val keepMeta = existing?.userEdited == true
        val book = base.copy(
            title = if (keepMeta) base.title else bookTitle,
            author = if (keepMeta) base.author else (author ?: "").trim(),
            narrator = if (keepMeta) base.narrator else (narrator ?: "").trim(),
            coverPath = coverPath,
            coverColor = coverColor,
            source = BookEntity.SOURCE_LOCAL,
            sourceUri = c.sourceUri.toString(),
            folderId = folderId,
            durationMs = offset,
            pdfUri = base.pdfUri ?: c.pdf?.uri?.toString(),
            userEdited = base.userEdited || c.forcedTitle != null,
        )
        dao.replaceContent(book, trackRows, chapters)
        if (existing == null && c.parts.isNotEmpty()) carryOver(id, c.parts, trackRows, "", manual = c.forcedTitle != null)
    }

    /**
     * When separate disc books become one, everything moves to the merged book: bookmarks, clips and the
     * listening log (shifted to the merged timeline), shelves, Up Next, and your place, rating, speed and
     * status from the disc you played most recently. The disc books then go away.
     */
    private suspend fun carryOver(newId: String, parts: List<Pair<String, Int>>, tracks: List<TrackEntity>, keyPrefix: String, manual: Boolean) {
        var fileIdx = 0
        val olds = mutableListOf<Pair<BookEntity, Long>>()
        for ((key, count) in parts) {
            val offset = tracks.take(fileIdx).sumOf { it.durationMs }
            fileIdx += count
            val old = dao.getBook(bookIdFor(keyPrefix + key)) ?: continue
            if (old.id != newId) olds += old to offset
        }
        if (olds.isEmpty()) return
        val merged = dao.getBook(newId) ?: return
        for ((old, offset) in olds) {
            dao.moveBookmarks(old.id, newId, offset, Long.MIN_VALUE, Long.MAX_VALUE)
            dao.moveClips(old.id, newId, offset, Long.MIN_VALUE, Long.MAX_VALUE)
            dao.moveListenLog(old.id, newId, offset, Long.MIN_VALUE, Long.MAX_VALUE)
            dao.moveShelfLinks(old.id, newId)
        }
        val played = olds.filter { it.first.lastPlayedAt > 0 || it.first.status != BookEntity.STATUS_NEW }
        val recent = played.maxByOrNull { it.first.lastPlayedAt }
        val status = when {
            olds.all { it.first.status == BookEntity.STATUS_FINISHED } -> BookEntity.STATUS_FINISHED
            recent != null -> BookEntity.STATUS_IN_PROGRESS
            else -> BookEntity.STATUS_NEW
        }
        val r = recent?.first
        val firstCover = olds.firstNotNullOfOrNull { it.first.coverPath }
        dao.updateBook(
            merged.copy(
                positionMs = recent?.let { (b, off) -> (off + b.positionMs).coerceAtMost(merged.durationMs) } ?: 0,
                status = status,
                lastPlayedAt = r?.lastPlayedAt ?: 0,
                finishedAt = if (status == BookEntity.STATUS_FINISHED) olds.maxOf { it.first.finishedAt } else 0,
                addedAt = olds.minOf { it.first.addedAt },
                rating = r?.rating?.takeIf { it > 0 } ?: olds.maxOf { it.first.rating },
                speed = r?.speed ?: merged.speed,
                customEffects = r?.customEffects ?: merged.customEffects,
                trimSilence = r?.trimSilence ?: merged.trimSilence,
                voiceBoost = r?.voiceBoost ?: merged.voiceBoost,
                skipIntroSec = r?.skipIntroSec ?: merged.skipIntroSec,
                skipOutroSec = r?.skipOutroSec ?: merged.skipOutroSec,
                coverPath = if (manual) firstCover ?: merged.coverPath else merged.coverPath ?: firstCover,
                coverColor = if (manual && firstCover != null) olds.first { it.first.coverPath != null }.first.coverColor else merged.coverColor ?: olds.firstNotNullOfOrNull { it.first.coverColor },
            ),
        )
        val oldIds = olds.map { it.first.id }.toSet()
        runCatching {
            val settings = context.container.settings
            settings.editUpNext { list -> list.map { if (it in oldIds) newId else it } }
            if (settings.current().lastBookId in oldIds) settings.setLastBook(newId)
        }
        oldIds.forEach { dao.deleteBook(it) }
    }

    /** "Book Title CD1" / "Book Title - Disc 2" as an album tag on a disc: just the book's title. */
    private fun stripDisc(t: String): String =
        t.replace(Regex("[\\s,:._-]*[\\(\\[]?\\s*(cd|disc|disk|part|pt)\\.?\\s*[-_#]?\\s*\\d{1,3}(\\s*(of|/)\\s*\\d{1,3})?\\s*[\\)\\]]?\\s*$", RegexOption.IGNORE_CASE), "").trim().ifBlank { t }

    /** Disc 2's "Track 01" would read "Chapter 1" again: when file numbers restart on each disc, number straight through. */
    private fun renumberAcrossDiscs(chapters: MutableList<ChapterEntity>, bookTitle: String) {
        val names = chapters.map { app.ember.util.Names.chapter(it.title, bookTitle, it.idx) }
        val nums = names.map { Regex("^Chapter (\\d+)$").find(it)?.groupValues?.get(1)?.toInt() }
        val numbered = nums.filterNotNull()
        val restarts = numbered.zipWithNext().any { (a, b) -> b <= a }
        if (!restarts) return
        for (i in chapters.indices) {
            if (nums[i] != null) chapters[i] = chapters[i].copy(title = "Chapter ${i + 1}")
        }
    }

    // ---------------- Merging by hand ----------------

    /** The scan key a local book was found under (its folder or file), or null if it can't be merged by hand. */
    private fun scanKeyOf(b: BookEntity): String? {
        if (b.source != BookEntity.SOURCE_LOCAL || b.sourceUri.isBlank()) return null
        val key = runCatching { DocumentsContract.getDocumentId(Uri.parse(b.sourceUri)) }.getOrNull() ?: return null
        return key.takeIf { bookIdFor(it) == b.id }
    }

    /** Whether these books can become one: local books from the same library folder, none merged already. */
    fun canMerge(books: List<BookEntity>): Boolean =
        books.size >= 2 && books.map { it.folderId }.distinct().size == 1 && books.first().folderId != null && books.all { scanKeyOf(it) != null }

    /** Merges books (in this order) into one with this name; returns the merged book's id, or null if they can't be merged. */
    suspend fun merge(books: List<BookEntity>, title: String): String? {
        if (!canMerge(books)) return null
        val folder = dao.getFolders().firstOrNull { it.id == books.first().folderId } ?: return null
        val mergedKey = "merge:" + System.currentTimeMillis()
        val keys = books.map { scanKeyOf(it)!! }
        dao.insertMerges(keys.mapIndexed { i, k -> BookMergeEntity(mergedKey, i, k, title.trim().ifBlank { books.first().title }, folder.id) })
        mutex.withLock {
            withContext(Dispatchers.IO) {
                scanFolderLocked(folder)
                _progress.value = null
            }
        }
        return bookIdFor(mergedKey).takeIf { dao.getBook(it) != null }
    }

    /** Whether this book was merged by hand (so it can be split back apart). */
    suspend fun isManualMerge(bookId: String): Boolean = dao.getMerges().any { bookIdFor(it.mergedKey) == bookId }

    /** Splits a book merged by hand back into its parts, each getting back its own bookmarks, clips, log and place. */
    suspend fun split(bookId: String): Boolean {
        val rows = dao.getMerges().filter { bookIdFor(it.mergedKey) == bookId }.sortedBy { it.idx }
        if (rows.isEmpty()) return false
        val merged = dao.getBook(bookId) ?: return false
        val folder = dao.getFolders().firstOrNull { it.id == merged.folderId }
        dao.deleteMerge(rows.first().mergedKey)
        val shelves = dao.getShelfIdsFor(bookId)
        mutex.withLock {
            withContext(Dispatchers.IO) {
                if (folder != null) scanFolderLocked(folder, keep = setOf(bookId))
                _progress.value = null
                var offset = 0L
                val pos = merged.positionMs
                for (r in rows) {
                    val id = bookIdFor(r.memberKey)
                    val part = dao.getBook(id) ?: continue
                    val end = offset + part.durationMs
                    dao.moveBookmarks(bookId, id, -offset, offset, end)
                    dao.moveClips(bookId, id, -offset, offset, end)
                    dao.moveListenLog(bookId, id, -offset, offset, end)
                    shelves.forEach { dao.addToShelf(ShelfBookEntity(it, id)) }
                    val status = when {
                        merged.status == BookEntity.STATUS_FINISHED || (merged.status != BookEntity.STATUS_NEW && pos >= end) -> BookEntity.STATUS_FINISHED
                        merged.status != BookEntity.STATUS_NEW && pos in offset until end -> BookEntity.STATUS_IN_PROGRESS
                        else -> BookEntity.STATUS_NEW
                    }
                    dao.updateBook(
                        part.copy(
                            positionMs = if (pos in offset until end) pos - offset else 0,
                            status = status,
                            lastPlayedAt = if (status != BookEntity.STATUS_NEW) merged.lastPlayedAt else 0,
                            finishedAt = if (status == BookEntity.STATUS_FINISHED) merged.finishedAt.takeIf { it > 0 } ?: merged.lastPlayedAt else 0,
                            rating = merged.rating,
                            speed = merged.speed,
                        ),
                    )
                    if (pos in offset until end) {
                        runCatching {
                            val settings = context.container.settings
                            settings.editUpNext { list -> list.map { if (it == bookId) id else it } }
                            if (settings.current().lastBookId == bookId) settings.setLastBook(id)
                        }
                    }
                    offset = end
                }
                dao.deleteBook(bookId)
            }
        }
        runCatching { afterScan?.invoke() }
        return true
    }

    private fun cleanChapterTitle(tag: String?, fileTitle: String): String {
        if (!tag.isNullOrBlank()) return tag.trim()
        val stripped = fileTitle.replace(Regex("^[\\d\\s._-]+"), "").trim()
        return stripped.ifBlank { fileTitle }
    }

    // ---------------- Google Drive ----------------

    private data class DriveCandidate(
        val key: String,
        val files: List<DriveFile>,
        val fallbackTitle: String,
        val cover: DriveFile?,
        val sourceId: String,
        val parts: List<Pair<String, Int>> = emptyList(),
    )

    private fun DriveFile.ext() = name.substringAfterLast('.', "").lowercase()
    private fun DriveFile.isAudioFile() = !isFolder && (mimeType.startsWith("audio/") || ext() in AUDIO_EXT)
    private fun DriveFile.isImageFile() = !isFolder && (mimeType.startsWith("image/") || ext() in setOf("jpg", "jpeg", "png", "webp"))

    private suspend fun scanDriveLocked(folder: LibraryFolderEntity) {
        val rootId = folder.uri.removePrefix(DRIVE_PREFIX)
        if (driveAuth.token() == null) {
            problem(folder, "Google Drive needs you to sign in again. Tap Connect Google Drive below.")
            return
        }
        _progress.value = Progress(folder.name, 0, "Looking for books in Drive")
        val candidates = mutableListOf<DriveCandidate>()
        try {
            walkDrive(rootId, folder.name, 0, candidates)
        } catch (e: Exception) {
            problem(folder, "Couldn't reach this Drive folder. Check your connection, or that the folder still exists in Drive.")
            return
        }
        problem(folder, null)
        val foundIds = candidates.map { bookIdFor(DRIVE_PREFIX + it.key) }.toHashSet()
        // Read several books at once; each appears in the library as soon as it's ready.
        val gate = Semaphore(4)
        val done = AtomicInteger(0)
        coroutineScope {
            candidates.forEach { c ->
                launch {
                    gate.withPermit {
                        try {
                            importDriveCandidate(bookIdFor(DRIVE_PREFIX + c.key), c, folder.id)
                        } catch (e: Exception) {
                        }
                        _progress.value = Progress(folder.name, done.incrementAndGet(), c.fallbackTitle)
                    }
                }
            }
        }
        if (candidates.isNotEmpty()) {
            dao.getBooksInFolder(folder.id)
                .filter { it.id !in foundIds && it.source == BookEntity.SOURCE_DRIVE }
                .forEach { dao.deleteBook(it.id) }
        }
        dao.updateFolder(folder.copy(lastScanAt = System.currentTimeMillis()))
    }

    private suspend fun walkDrive(folderId: String, name: String, depth: Int, out: MutableList<DriveCandidate>) {
        if (depth > 8) return
        val children = drive.list(folderId)
        val audio = children.filter { it.isAudioFile() }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        val images = children.filter { it.isImageFile() }
        val dirs = children.filter { it.isFolder }.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        val preferred = listOf("cover", "folder", "front", "art", "poster")
        val folderCover = images.minByOrNull { img ->
            val base = img.name.substringBeforeLast('.').lowercase()
            val rank = preferred.indexOfFirst { base.contains(it) }
            if (rank < 0) 99 else rank
        }
        if (audio.isEmpty() && dirs.size >= 2 && dirs.all { isDiscName(it.name) }) {
            val discs = dirs.map { it to drive.list(it.id) }
            if (discs.all { (_, kids) -> kids.none { k -> k.isFolder } && kids.any { k -> k.isAudioFile() } }) {
                val parts = discs.map { (d, kids) -> d to kids.filter { k -> k.isAudioFile() }.sortedWith { a, b -> naturalCompare(a.name, b.name) } }
                val discCover = discs.firstNotNullOfOrNull { (_, kids) -> kids.firstOrNull { k -> k.isImageFile() } }
                out += DriveCandidate(folderId, parts.flatMap { it.second }, name, folderCover ?: discCover, folderId, parts.map { (d, f) -> d.id to f.size })
                return
            }
        }
        if (audio.isNotEmpty()) {
            val allContainers = audio.all { it.ext() in CONTAINER_EXT }
            val loose = depth == 0 && (dirs.isNotEmpty() || allContainers) || (allContainers && audio.size > 1)
            if (loose) {
                audio.forEach { f ->
                    val base = f.name.substringBeforeLast('.').lowercase()
                    val match = images.firstOrNull { it.name.substringBeforeLast('.').lowercase() == base }
                    out += DriveCandidate(f.id, listOf(f), f.name.substringBeforeLast('.'), match ?: folderCover.takeIf { audio.size == 1 }, f.id)
                }
            } else {
                out += DriveCandidate(folderId, audio, name, folderCover, folderId)
            }
        }
        for (d in dirs) walkDrive(d.id, d.name, depth + 1, out)
    }

    private suspend fun importDriveCandidate(id: String, c: DriveCandidate, folderId: Long) {
        val existing = dao.getBook(id)
        if (existing != null && dao.getTracks(id).size == c.files.size) return
        val token = driveAuth.token() ?: return
        val headers = mapOf("Authorization" to "Bearer $token")

        val retriever = MediaMetadataRetriever()
        val trackRows = mutableListOf<TrackEntity>()
        val chapters = mutableListOf<ChapterEntity>()
        var title: String? = null
        var author: String? = null
        var narrator: String? = null
        var art: ByteArray? = null
        var offset = 0L

        c.files.forEachIndexed { index, f ->
            var duration = 0L
            var trackTitle: String? = null
            try {
                retriever.setDataSource(DriveApi.mediaUrl(f.id), headers)
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                trackTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                if (index == 0) {
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    narrator = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                    if (c.files.size == 1 && title.isNullOrBlank()) title = trackTitle
                    art = retriever.embeddedPicture
                }
            } catch (e: Exception) {
            }
            val fileTitle = f.name.substringBeforeLast('.')
            trackRows += TrackEntity(id, index, DRIVE_TRACK_PREFIX + f.id, trackTitle?.takeIf { it.isNotBlank() } ?: fileTitle, duration)
            if (c.files.size == 1) {
                val raw = if (f.ext() in CONTAINER_EXT && f.size > 0) {
                    Mp4ChapterParser.parse(CachedRemoteSource(f.size) { pos, len -> drive.readRange(f.id, token, pos, len) })
                } else {
                    emptyList()
                }
                if (raw.isNotEmpty()) {
                    raw.forEachIndexed { ci, rc ->
                        val end = raw.getOrNull(ci + 1)?.startMs ?: duration
                        chapters += ChapterEntity(id, ci, rc.title, rc.startMs, maxOf(end, rc.startMs))
                    }
                } else {
                    chapters += ChapterEntity(id, 0, title?.takeIf { it.isNotBlank() } ?: c.fallbackTitle, 0, duration)
                }
            } else {
                chapters += ChapterEntity(id, index, cleanChapterTitle(trackTitle, fileTitle), offset, offset + duration)
            }
            offset += duration
        }
        try {
            retriever.release()
        } catch (e: Exception) {
        }

        var coverPath = existing?.coverPath
        var coverColor = existing?.coverColor
        if (coverPath == null) {
            val bytes = c.cover?.let { drive.download(it.id) } ?: art
            bytes?.let { covers.saveBytes(id, it) }?.let {
                coverPath = it.path
                coverColor = it.color
            }
        }
        val bookTitle = title?.takeIf { it.isNotBlank() }?.let { if (c.parts.isNotEmpty()) stripDisc(it) else it } ?: c.fallbackTitle
        if (c.parts.isNotEmpty()) renumberAcrossDiscs(chapters, bookTitle)
        // Same book you already started through the Drive app's folder picker? Keep your place.
        val previous = if (existing == null) findDriveAppTwin(bookTitle, offset) else null
        val base = existing ?: previous?.let {
            it.copy(id = id, coverPath = coverPath ?: it.coverPath, coverColor = coverColor ?: it.coverColor)
        } ?: BookEntity(id = id, title = bookTitle)
        val keepMeta = base.userEdited
        dao.replaceContent(
            base.copy(
                title = if (keepMeta) base.title else bookTitle,
                author = if (keepMeta) base.author else (author ?: "").trim(),
                narrator = if (keepMeta) base.narrator else (narrator ?: "").trim(),
                coverPath = coverPath,
                coverColor = coverColor,
                source = BookEntity.SOURCE_DRIVE,
                sourceUri = DRIVE_PREFIX + c.sourceId,
                folderId = folderId,
                durationMs = offset,
            ),
            trackRows,
            chapters,
        )
        if (previous != null) {
            dao.getBookmarks(previous.id).forEach { dao.insertBookmark(it.copy(id = 0, bookId = id)) }
            dao.getShelfIdsFor(previous.id).forEach { dao.addToShelf(ShelfBookEntity(it, id)) }
        }
        if (existing == null && c.parts.isNotEmpty()) carryOver(id, c.parts, trackRows, DRIVE_PREFIX, manual = false)
    }

    private suspend fun findDriveAppTwin(title: String, durationMs: Long): BookEntity? =
        dao.getBooks().firstOrNull { b ->
            b.source == BookEntity.SOURCE_LOCAL && isDriveAppUri(b.sourceUri) &&
                b.title.equals(title, ignoreCase = true) &&
                (durationMs <= 0 || b.durationMs <= 0 || kotlin.math.abs(b.durationMs - durationMs) < 10_000)
        }

    /** Reads a remote file in 256 KB blocks so chapter parsing needs a handful of requests, not hundreds. */
    private class CachedRemoteSource(
        override val size: Long,
        private val fetch: (Long, Int) -> ByteArray,
    ) : Mp4ChapterParser.ByteSource {
        private val blockSize = 256 * 1024
        private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 64
        }

        override fun read(buf: ByteBuffer, pos: Long) {
            var p = pos
            while (buf.hasRemaining() && p < size) {
                val start = p / blockSize * blockSize
                val block = blocks.getOrPut(start) { fetch(start, minOf(blockSize.toLong(), size - start).toInt()) }
                val off = (p - start).toInt()
                if (off >= block.size) break
                val n = minOf(buf.remaining(), block.size - off)
                buf.put(block, off, n)
                p += n
            }
        }
    }

    companion object {
        /** Folders chosen through Android's picker from the Drive app (slow: it downloads whole files). */
        fun isDriveAppUri(uri: String): Boolean = uri.contains("com.google.android.apps.docs")

        const val DRIVE_PREFIX = "drive:"
        const val DRIVE_TRACK_PREFIX = "drive://"

        val AUDIO_EXT = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "mp4")
        val CONTAINER_EXT = setOf("m4b", "m4a", "mp4")

        private val discName = Regex(
            "^(?:.*[\\s._-])?(?:cd|disc|disk|part|pt)\\.?\\s*[-_#]?\\s*\\d{1,3}(?:\\s*(?:of|/)\\s*\\d{1,3})?$|^\\d{1,3}$",
            RegexOption.IGNORE_CASE,
        )

        /** "CD1", "CD 01", "Disc 2", "Disk 3", "Part 4", "Pt 5", "01", or "Book Title CD1". */
        fun isDiscName(name: String): Boolean = discName.matches(name.trim())

        fun bookIdFor(key: String): String = UUID.nameUUIDFromBytes(key.toByteArray()).toString()

        private val naturalOrder = Comparator<Doc> { a, b -> naturalCompare(a.name, b.name) }

        fun naturalCompare(a: String, b: String): Int {
            val re = Regex("\\d+|\\D+")
            val pa = re.findAll(a.lowercase()).map { it.value }.toList()
            val pb = re.findAll(b.lowercase()).map { it.value }.toList()
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val cmp = if (x[0].isDigit() && y[0].isDigit()) {
                    val nx = x.trimStart('0').ifEmpty { "0" }
                    val ny = y.trimStart('0').ifEmpty { "0" }
                    if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
                } else {
                    x.compareTo(y)
                }
                if (cmp != 0) return cmp
            }
            return pa.size - pb.size
        }
    }
}
