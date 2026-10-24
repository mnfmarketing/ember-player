package app.ember.torrent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.DocumentsContract
import app.ember.container
import app.ember.data.LibraryFolderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import java.io.File
import java.util.concurrent.Executors

/** One file inside a torrent, for choosing what to download. */
data class TorrentFile(val index: Int, val path: String, val size: Long) {
    val ext: String get() = path.substringAfterLast('.', "").lowercase()
    val isAudio: Boolean get() = ext in AUDIO
    val isImage: Boolean get() = ext in IMAGES

    companion object {
        val AUDIO = setOf("m4b", "m4a", "mp3", "aac", "ogg", "opus", "flac", "wav", "wma", "mka", "aax")
        val IMAGES = setOf("jpg", "jpeg", "png", "webp")
    }
}

/** What's inside a torrent, before you start it. */
data class TorrentPreview(val hash: String, val name: String, val files: List<TorrentFile>, val bytes: ByteArray) {
    val totalSize: Long get() = files.sumOf { it.size }

    /** Audio and pictures (covers) are picked by default; everything else isn't. */
    fun defaultSelection(): Set<Int> {
        val picked = files.filter { it.isAudio || it.isImage }.map { it.index }.toSet()
        return picked.ifEmpty { files.map { it.index }.toSet() }
    }
}

/** A torrent Ember is working on, saved so it carries on after a restart. */
data class TorrentJob(
    val hash: String,
    val name: String,
    val selected: List<Int>,
    val wantedBytes: Long,
    val destTree: String,
    val destName: String,
    val state: String = DOWNLOADING,
    val error: String = "",
    val addedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
) {
    companion object {
        const val DOWNLOADING = "downloading"
        const val PAUSED = "paused"
        const val MOVING = "moving"
        const val DONE = "done"
        const val FAILED = "failed"
    }

    val active: Boolean get() = state == DOWNLOADING || state == MOVING
}

/** A job plus how it's going right now. */
data class TorrentView(
    val job: TorrentJob,
    val progress: Float = 0f,
    val bytesDone: Long = 0,
    val rateBps: Long = 0,
    val peers: Int = 0,
    val etaSec: Long = -1,
    val phase: String = "",
)

/**
 * Downloads audiobooks over BitTorrent with libtorrent, then moves the finished files into
 * your chosen library folder and scans it. Files download into Ember's own storage first,
 * because libtorrent needs ordinary file paths. Sharing stops as soon as a book is done.
 */
class TorrentEngine(private val context: Context) {

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ember-torrent").apply { isDaemon = true } }
    private val io = exec.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + io)

    private val metaDir = File(context.filesDir, "torrents").apply { mkdirs() }
    private val dataRoot: File get() = (context.getExternalFilesDir("torrents") ?: File(context.filesDir, "torrent-data")).apply { mkdirs() }
    private val jobsFile = File(metaDir, "jobs.json")

    private var session: SessionManager? = null
    private val jobs = mutableListOf<TorrentJob>()
    private val _views = MutableStateFlow<List<TorrentView>>(emptyList())
    val views: StateFlow<List<TorrentView>> = _views
    private val _waitingForWifi = MutableStateFlow(false)
    val waitingForWifi: StateFlow<Boolean> = _waitingForWifi
    private var loaded = false

    /** A .torrent file or magnet link opened from another app, waiting for the add screen. */
    val incoming = MutableStateFlow<String?>(null)

    /** Emulator test hook: pick only the smallest audio file so the test download is quick. */
    @Volatile var testPickSmallest = false

    // ---------- Jobs on disk ----------

    private fun load() {
        if (loaded) return
        loaded = true
        jobs.clear()
        val arr = try {
            JSONArray(jobsFile.readText())
        } catch (e: Exception) {
            JSONArray()
        }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val sel = o.optJSONArray("selected") ?: JSONArray()
            var state = o.optString("state", TorrentJob.DOWNLOADING)
            // A move that was cut off restarts from "downloading": the files are still there.
            if (state == TorrentJob.MOVING) state = TorrentJob.DOWNLOADING
            jobs += TorrentJob(
                hash = o.getString("hash"),
                name = o.optString("name"),
                selected = (0 until sel.length()).map { sel.getInt(it) },
                wantedBytes = o.optLong("wantedBytes"),
                destTree = o.optString("destTree"),
                destName = o.optString("destName"),
                state = state,
                error = o.optString("error"),
                addedAt = o.optLong("addedAt"),
                finishedAt = o.optLong("finishedAt"),
            )
        }
        publish(emptyMap())
    }

    private fun save() {
        val arr = JSONArray()
        jobs.forEach { j ->
            arr.put(
                JSONObject().put("hash", j.hash).put("name", j.name).put("selected", JSONArray(j.selected))
                    .put("wantedBytes", j.wantedBytes).put("destTree", j.destTree).put("destName", j.destName)
                    .put("state", j.state).put("error", j.error).put("addedAt", j.addedAt).put("finishedAt", j.finishedAt),
            )
        }
        jobsFile.writeText(arr.toString())
    }

    private fun update(hash: String, change: (TorrentJob) -> TorrentJob) {
        val i = jobs.indexOfFirst { it.hash == hash }
        if (i < 0) return
        jobs[i] = change(jobs[i])
        save()
    }

    private fun torrentFile(hash: String) = File(metaDir, "$hash.torrent")
    private fun dataDir(hash: String) = File(dataRoot, hash)

    // ---------- Session ----------

    private fun ensureSession(): SessionManager {
        session?.let { return it }
        val sm = SessionManager(false)
        // Plain file reads and writes instead of libtorrent's default memory-mapped files.
        // Memory-mapping on Android's shared storage crashes the app (SIGBUS) once data
        // starts arriving quickly, which is what happened on the Fold.
        val params = org.libtorrent4j.SessionParams().apply { setPosixDiskIO() }
        sm.start(params)
        session = sm
        return sm
    }

    private fun stopSession() {
        session?.stop()
        session = null
    }

    // ---------- Reading torrents ----------

    private fun preview(bytes: ByteArray): TorrentPreview {
        val ti = TorrentInfo.bdecode(bytes)
        val fs = ti.files()
        val files = (0 until fs.numFiles())
            .filter { !fs.padFileAt(it) }
            .map { TorrentFile(it, fs.filePath(it), fs.fileSize(it)) }
        return TorrentPreview(ti.infoHash().toHex(), ti.name(), files, bytes)
    }

    suspend fun readFile(uri: Uri): TorrentPreview = withContext(io) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't open that file.")
        try {
            preview(bytes)
        } catch (e: Throwable) {
            error("That isn't a torrent file Ember can read.")
        }
    }

    /** A link to a .torrent file on the web. */
    suspend fun readUrl(url: String): TorrentPreview = withContext(io) {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Ember-Audiobooks/1.0")
        }
        val bytes = try {
            if (conn.responseCode !in 200..299) error("The link answered with error ${conn.responseCode}.")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
        try {
            preview(bytes)
        } catch (e: Throwable) {
            error("That link isn't a torrent file.")
        }
    }

    /** Asks other people in the swarm for the torrent's details (a magnet link has only its id). */
    suspend fun fetchMagnet(magnet: String): TorrentPreview = withContext(io) {
        if (!magnet.trim().startsWith("magnet:", ignoreCase = true)) error("That doesn't look like a magnet link.")
        val sm = ensureSession()
        val tmp = File(context.cacheDir, "magnet").apply { mkdirs() }
        val bytes = try {
            sm.fetchMagnet(magnet.trim(), 90, tmp)
        } catch (e: Throwable) {
            null
        } finally {
            if (jobs.none { it.active }) stopSession()
        }
        bytes ?: error("No one sharing this torrent answered in time. Try again later, or use a .torrent file.")
        preview(bytes)
    }

    // ---------- Starting and controlling ----------

    /** Starts downloading [selected] files of [p]. Returns an error message, or null when it started. */
    suspend fun start(p: TorrentPreview, selected: Set<Int>, destTree: String, destName: String): String? = withContext(io) {
        load()
        if (selected.isEmpty()) return@withContext "Pick at least one file."
        val wanted = p.files.filter { it.index in selected }.sumOf { it.size }
        // Room for the download plus a copy while it moves into your folder.
        if (dataRoot.usableSpace < wanted * 2 + 200L * 1024 * 1024) {
            return@withContext "Not enough free space: this needs about ${android.text.format.Formatter.formatShortFileSize(context, wanted * 2)}."
        }
        torrentFile(p.hash).writeBytes(p.bytes)
        jobs.removeAll { it.hash == p.hash }
        jobs += TorrentJob(p.hash, p.name, selected.sorted(), wanted, destTree, destName)
        save()
        addToSession(jobs.last())
        publish(emptyMap())
        null
    }

    private fun addToSession(job: TorrentJob) {
        val sm = ensureSession()
        val ti = TorrentInfo(torrentFile(job.hash))
        val priorities = Array(ti.numFiles()) { if (it in job.selected) Priority.DEFAULT else Priority.IGNORE }
        sm.download(ti, dataDir(job.hash).apply { mkdirs() }, null, priorities, null, org.libtorrent4j.swig.torrent_flags_t())
    }

    private fun handle(hash: String): TorrentHandle? =
        session?.find(org.libtorrent4j.Sha1Hash.parseHex(hash))?.takeIf { it.isValid }

    fun pause(hash: String) = scope.launch {
        load()
        update(hash) { it.copy(state = TorrentJob.PAUSED) }
        handle(hash)?.let {
            it.unsetFlags(TorrentFlags.AUTO_MANAGED)
            it.pause()
        }
        publish(emptyMap())
    }

    fun resume(hash: String) = scope.launch {
        load()
        update(hash) { it.copy(state = TorrentJob.DOWNLOADING, error = "") }
        publish(emptyMap())
    }

    /** Stops a download and deletes what was downloaded so far. */
    fun cancel(hash: String) = scope.launch {
        load()
        handle(hash)?.let { session?.remove(it) }
        jobs.removeAll { it.hash == hash }
        save()
        dataDir(hash).deleteRecursively()
        torrentFile(hash).delete()
        publish(emptyMap())
    }

    fun clearFinished() = scope.launch {
        load()
        jobs.filter { it.state == TorrentJob.DONE }.forEach { torrentFile(it.hash).delete() }
        jobs.removeAll { it.state == TorrentJob.DONE }
        save()
        publish(emptyMap())
    }

    /** Restarts the background downloader if anything was still downloading. */
    fun startIfNeeded(context: Context) = scope.launch {
        load()
        if (jobs.any { it.active }) TorrentService.start(context)
    }

    /** Loads saved jobs (on app start) so the list shows them. */
    fun init() = scope.launch { load() }

    // ---------- The loop run by TorrentService ----------

    private var moving = false

    /** Technical details per download, for the emulator test reports. */
    val debug = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Called every second while the service runs. Returns false when there's nothing left to do. */
    suspend fun tick(): Boolean = withContext(io) {
        load()
        val active = jobs.filter { it.state == TorrentJob.DOWNLOADING }
        if (active.isEmpty() && !moving) {
            if (session != null) stopSession()
            publish(emptyMap())
            return@withContext false
        }
        val sm = ensureSession()

        // Wi-Fi only: hold everything while on mobile data.
        val wifiOnly = context.container.settings.current().torrentWifiOnly
        val hold = wifiOnly && !onUnmeteredNetwork()
        _waitingForWifi.value = hold
        if (hold && !sm.isPaused) sm.pause()
        if (!hold && sm.isPaused) sm.resume()

        val stats = HashMap<String, TorrentStatus>()
        for (job in jobs) {
            val h = handle(job.hash)
            when (job.state) {
                TorrentJob.DOWNLOADING -> {
                    if (h == null) {
                        try {
                            addToSession(job)
                        } catch (e: Throwable) {
                            update(job.hash) { it.copy(state = TorrentJob.FAILED, error = "Couldn't start: ${e.message}") }
                        }
                        continue
                    }
                    // Ember runs its downloads itself (no queue), so make sure each one is going.
                    val flags = h.getFlags()
                    if (flags.and_(TorrentFlags.AUTO_MANAGED).to_int() != 0) h.unsetFlags(TorrentFlags.AUTO_MANAGED)
                    if (flags.and_(TorrentFlags.PAUSED).to_int() != 0 && !hold) h.resume()
                    // Web seeds (servers that also hand out the files, like the Internet Archive's)
                    // aren't picked up from the torrent automatically by this version of libtorrent.
                    val seeds = webSeeds(job.hash)
                    if (seeds.isNotEmpty() && h.urlSeeds().isEmpty()) seeds.forEach { h.addUrlSeed(it) }
                    val st = h.status()
                    debug[job.hash] = "state=${st.state()} flags=${h.getFlags().to_int()} peers=${st.numPeers()} conns=${st.numConnections()} " +
                        "seeds=${h.urlSeeds()} err=${st.errorCode()?.message.orEmpty()} wanted=${st.totalWanted()} done=${st.totalWantedDone()}"
                    stats[job.hash] = st
                    val done = st.totalWanted() > 0 && st.totalWantedDone() >= st.totalWanted()
                    if (done && !moving) {
                        moving = true
                        update(job.hash) { it.copy(state = TorrentJob.MOVING) }
                        scope.launch { finish(job.hash) }
                    }
                }
                TorrentJob.PAUSED -> h?.let {
                    it.unsetFlags(TorrentFlags.AUTO_MANAGED)
                    it.pause()
                }
                else -> {}
            }
        }
        publish(stats)
        true
    }

    private val seedCache = HashMap<String, List<String>>()

    /** The "url-list" (web seeds) from a saved .torrent file. */
    private fun webSeeds(hash: String): List<String> = seedCache.getOrPut(hash) {
        try {
            val e = org.libtorrent4j.Entry.bdecode(torrentFile(hash).readBytes()).dictionary()["url-list"] ?: return@getOrPut emptyList()
            when (e.swig().type()) {
                org.libtorrent4j.swig.entry.data_type.list_t -> e.list().mapNotNull { runCatching { it.string() }.getOrNull() }
                org.libtorrent4j.swig.entry.data_type.string_t -> listOf(e.string())
                else -> emptyList()
            }.filter { it.startsWith("http", ignoreCase = true) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun onUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun publish(stats: Map<String, TorrentStatus>) {
        _views.value = jobs.sortedByDescending { it.addedAt }.map { job ->
            val st = stats[job.hash]
            if (st == null) {
                TorrentView(job, progress = if (job.state == TorrentJob.DONE) 1f else 0f, phase = phaseFor(job, null))
            } else {
                val wanted = st.totalWanted().coerceAtLeast(1)
                val done = st.totalWantedDone()
                val rate = st.downloadPayloadRate().toLong()
                TorrentView(
                    job,
                    progress = (done.toFloat() / wanted).coerceIn(0f, 1f),
                    bytesDone = done,
                    rateBps = rate,
                    peers = st.numPeers(),
                    etaSec = if (rate > 0) (wanted - done) / rate else -1,
                    phase = phaseFor(job, st),
                )
            }
        }
    }

    private fun phaseFor(job: TorrentJob, st: TorrentStatus?): String = when (job.state) {
        TorrentJob.PAUSED -> "Paused"
        TorrentJob.MOVING -> "Adding to your library…"
        TorrentJob.DONE -> "In your library"
        TorrentJob.FAILED -> job.error.ifBlank { "Something went wrong" }
        else -> when {
            _waitingForWifi.value -> "Waiting for Wi-Fi"
            st == null -> "Starting…"
            st.state() == TorrentStatus.State.CHECKING_FILES || st.state() == TorrentStatus.State.CHECKING_RESUME_DATA -> "Checking what's already downloaded…"
            st.state() == TorrentStatus.State.DOWNLOADING_METADATA -> "Finding people sharing it…"
            st.numPeers() == 0 && st.downloadPayloadRate() == 0 -> "Looking for people sharing it…"
            else -> "Downloading"
        }
    }

    // ---------- Finishing: move into your folder ----------

    private suspend fun finish(hash: String) {
        try {
            val job = jobs.firstOrNull { it.hash == hash } ?: return
            val ti = TorrentInfo(torrentFile(hash))
            val fs = ti.files()
            // Close the files before copying them.
            handle(hash)?.let { session?.remove(it) }
            val tree = Uri.parse(job.destTree)
            val resolver = context.contentResolver
            val rootDoc = DocumentsContract.getTreeDocumentId(tree)
            for (i in job.selected) {
                val rel = fs.filePath(i).replace('\\', '/')
                val src = File(dataDir(hash), rel)
                if (!src.exists()) error("A downloaded file is missing: ${src.name}")
                val parts = rel.split('/').filter { it.isNotBlank() }
                var parent = rootDoc
                for (dir in parts.dropLast(1)) parent = findOrCreateDir(tree, parent, dir)
                val name = parts.last()
                val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parent)
                val existing = findChild(tree, parent, name)
                if (existing != null) {
                    // Already there (from an earlier attempt): replace it.
                    DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, existing))
                }
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                val out = DocumentsContract.createDocument(resolver, parentUri, mime, name) ?: error("Couldn't create $name in your folder.")
                resolver.openOutputStream(out, "w")?.use { o -> src.inputStream().use { it.copyTo(o, 256 * 1024) } }
                    ?: error("Couldn't write $name to your folder.")
            }
            dataDir(hash).deleteRecursively()
            update(hash) { it.copy(state = TorrentJob.DONE, finishedAt = System.currentTimeMillis(), error = "") }
            publish(emptyMap())
            TorrentService.notifyDone(context, job.name, job.destName)
            addAndScan(job)
        } catch (e: SecurityException) {
            update(hash) { it.copy(state = TorrentJob.FAILED, error = "Ember lost permission to your downloads folder. Choose it again in Settings, then tap Retry.") }
        } catch (e: Throwable) {
            update(hash) { it.copy(state = TorrentJob.FAILED, error = e.message ?: "Couldn't move the book into your folder.") }
        } finally {
            moving = false
            publish(emptyMap())
        }
    }

    /** After a failed move, try again (the files are still in Ember's storage). */
    fun retry(hash: String) = scope.launch {
        load()
        update(hash) { it.copy(state = TorrentJob.DOWNLOADING, error = "") }
        publish(emptyMap())
    }

    private suspend fun addAndScan(job: TorrentJob) {
        val c = context.container
        val dao = c.db.dao()
        val folder = dao.getFolderByUri(job.destTree) ?: run {
            val id = dao.insertFolder(LibraryFolderEntity(uri = job.destTree, name = job.destName))
            LibraryFolderEntity(id = id, uri = job.destTree, name = job.destName)
        }
        c.scanner.scanFolder(folder)
    }

    private fun findChild(tree: Uri, parentDoc: String, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDoc)
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0)
        }
        return null
    }

    private fun findOrCreateDir(tree: Uri, parentDoc: String, name: String): String {
        findChild(tree, parentDoc, name)?.let { return it }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(tree, parentDoc)
        val created = DocumentsContract.createDocument(context.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: error("Couldn't create the folder \"$name\".")
        return DocumentsContract.getDocumentId(created)
    }
}
