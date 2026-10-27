package app.ember.data

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import app.ember.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Test hooks used by the emulator checks in CI (ci/smoke.sh). Reports go to
 * Downloads/EmberTest so the test can read them; nothing outside Ember is changed.
 */
object SelfCheck {

    private const val FOLDER = "Download/EmberTest/"

    /** Writes [text] to Downloads/EmberTest/[name], replacing any earlier copy. */
    private fun publish(context: Context, name: String, text: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.delete(
            collection,
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, FOLDER),
        )
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            // The type must match the extension, or Android renames the file (x.json becomes x.json.txt).
            put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".json")) "application/json" else "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
        }
        val uri = resolver.insert(collection, values) ?: return
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
    }

    /** Writes a book's Markdown notes where the test can read them. */
    suspend fun exportNotes(context: Context, bookId: String) {
        publish(context, "notes-$bookId.txt", NotesExport.markdown(context.container.db.dao(), bookId))
    }

    private suspend fun summary(context: Context): JSONObject {
        val c = context.container
        val dao = c.db.dao()
        val media = c.media.dao()
        val books = dao.getBooks()
        return JSONObject()
            .put("libraryDbVersion", c.db.openHelper.readableDatabase.version)
            .put("podcastDbVersion", c.media.openHelper.readableDatabase.version)
            .put("books", books.size)
            .put("externalBooks", books.count { it.isExternal })
            .put("inProgress", books.count { it.status == BookEntity.STATUS_IN_PROGRESS })
            .put("finished", books.count { it.status == BookEntity.STATUS_FINISHED })
            .put("rated", books.count { it.rating > 0 })
            .put("bookmarks", dao.getAllBookmarks().size)
            .put("clips", dao.getClips().size)
            .put("shelves", dao.getShelves().size)
            .put("shelfLinks", dao.getShelfLinks().size)
            .put("listeningDays", dao.getAllDays().count { it.listenedMs > 0 })
            .put("podcasts", media.getPodcasts().size)
            .put("demo0PositionMs", books.firstOrNull { it.id == "demo-0" }?.positionMs ?: -1)
            .put("pendingRestore", c.backup.hasPending())
            .put("automaticBackups", c.backup.automaticBackups().size)
            .put("lastBackupAt", c.settings.current().lastBackupAt)
            .put("torrents", c.torrents.views.value.joinToString { "${it.job.name}: ${it.job.state} ${(it.progress * 100).toInt()}% ${it.phase}" })
            .put("torrentDebug", c.torrents.debug.values.joinToString(" | "))
            .put("wifiHold", c.torrents.waitingForWifi.value)
            .put("bookTitles", books.joinToString { it.title })
            .put("demo0Pdf", books.firstOrNull { it.id == "demo-0" }?.pdfUri ?: "")
            .put("booksWithPdf", books.count { it.hasPdf })
            .put("listenLogEntries", dao.countListenLog())
            .put("upNext", c.settings.current().upNext.joinToString(","))
            .put("demo0Effects", books.firstOrNull { it.id == "demo-0" }?.let { "${it.customEffects},${it.trimSilence},${it.voiceBoost}" } ?: "")
            .put("sleepLog", dao.countSleepMarks())
            .put("dbSnapshots", DbSnapshots.dir(context).list()?.sorted()?.joinToString() ?: "")
            .put("playing", app.ember.playback.PlaybackStatus.isPlaying.value)
            .put("flipToPause", c.settings.current().flipToPause)
            .put("positions", books.joinToString { "${it.id}=${it.positionMs}" })
            .put("syncOn", c.sync.status.value.on)
            .put("syncLastAt", c.sync.status.value.lastSyncAt)
            .put("syncError", c.sync.status.value.error ?: "")
            .put("syncDevices", c.sync.status.value.otherDevices.joinToString())
            .put("syncFiles", c.sync.testDir().list()?.sorted()?.joinToString() ?: "")
            .put("syncPrompt", c.sync.prompt.value?.let { "${it.bookId} at ${it.positionMs} from ${it.deviceName}" } ?: "")
            .put("demo0Bookmarks", dao.getBookmarks("demo-0").joinToString { "${it.positionMs}:${it.note}" })
            .put("demo0Log", dao.getListenLog("demo-0").sortedByDescending { it.at }.take(12).joinToString { "${it.kind}@${it.positionMs}/${it.device.ifEmpty { "here" }}" })
            .put("look", c.settings.current().let { "${it.appearance},${it.themeId},${it.trueBlack},${it.accentMode},${Integer.toHexString(it.accentColor)}" })
            .put("lastBookId", c.settings.current().lastBookId ?: "")
            .put("merges", dao.getMerges().size)
            .put("profileName", c.settings.current().profileName)
            .put("profilePhoto", ProfilePhoto.exists(context))
            .put(
                "recentLog",
                org.json.JSONArray().apply {
                    dao.recentListenLog(8).forEach { e ->
                        put(JSONObject().put("bookId", e.bookId).put("kind", e.kind).put("reason", e.reason).put("positionMs", e.positionMs).put("toMs", e.toMs))
                    }
                },
            )
            .put(
                "library",
                org.json.JSONArray().apply {
                    books.filter { !it.id.startsWith("demo-") && !it.isExternal }.forEach { b ->
                        put(
                            JSONObject()
                                .put("id", b.id)
                                .put("title", b.title)
                                .put("durationMs", b.durationMs)
                                .put("positionMs", b.positionMs)
                                .put("status", b.status)
                                .put("skips", "${b.skipIntroSec},${b.skipOutroSec}")
                                .put("tracks", dao.getTracks(b.id).joinToString(" | ") { "${it.title} (${it.durationMs / 1000}s)" })
                                .put("chapters", dao.getChapters(b.id).joinToString(" | ") { "${it.title}@${it.startMs / 1000}" })
                                .put("bookmarks", dao.getBookmarks(b.id).joinToString(",") { "${it.positionMs}" })
                                .put("log", dao.recentListenLog(400).count { it.bookId == b.id }),
                        )
                    }
                },
            )
    }

    suspend fun report(context: Context, name: String) = withContext(Dispatchers.IO) {
        val text = try {
            summary(context).toString(2)
        } catch (e: Exception) {
            "error: $e"
        }
        publish(context, name, text)
    }

    /**
     * Backs up, empties the library completely, then restores from that backup, reporting
     * after each step. Stands in for "lost my phone, restored on a new one".
     */
    suspend fun wipeAndRestore(context: Context) = withContext(Dispatchers.IO) {
        val c = context.container
        try {
            val json = c.backup.export().toString()
            File(context.filesDir, "test-backup.json").writeText(json)
            publish(context, "backup-export.json", JSONObject(json).toString(2))
            c.db.clearAllTables()
            c.media.clearAllTables()
            c.backup.automaticBackups().forEach { it.delete() }
            report(context, "after-wipe.json")
            val r = c.backup.restore(json)
            publish(context, "restore-result.txt", r?.describe() ?: "not a backup")
            report(context, "after-restore-before-folders.json")
        } catch (e: Exception) {
            publish(context, "restore-result.txt", "error: $e")
        }
    }

    /** Removes the demo audio so the player has a missing file to explain. */
    fun deleteDemoAudio(context: Context) {
        context.getExternalFilesDir(null)?.listFiles { f -> f.name.startsWith("demo.") }?.forEach { it.delete() }
    }
}
