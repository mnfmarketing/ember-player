package app.ember.sync

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ember.AppContainer
import app.ember.container
import app.ember.data.BookEntity
import app.ember.data.BookmarkEntity
import app.ember.data.ClipEntity
import app.ember.data.ListenEventEntity
import app.ember.data.ProfilePhoto
import app.ember.data.ShelfBookEntity
import app.ember.data.ShelfEntity
import app.ember.drive.DriveAuth
import app.ember.playback.PlaybackService
import app.ember.playback.PlaybackStatus
import app.ember.podcasts.Feeds
import app.ember.podcasts.PodcastEntity
import app.ember.podcasts.QueueEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Keeps your place, notes, stats, podcasts and listening log the same on your other devices,
 * through a hidden Ember folder on Google Drive. See SyncModel.kt for the rules.
 *
 * Sends: on pause, every 5 minutes while playing, and when Ember goes to the background.
 * Checks: when Ember opens, and every 15 minutes in the background.
 */
class SyncManager(private val context: Context, private val c: AppContainer) {

    data class Status(
        val on: Boolean = false,
        val deviceName: String = "",
        val lastSyncAt: Long = 0,
        val running: Boolean = false,
        val error: String? = null,
        val otherDevices: List<String> = emptyList(),
    )

    /** The book you have open is further along on another device: "Go there?" */
    data class Prompt(val bookId: String, val positionMs: Long, val deviceName: String, val at: Long, val key: String)

    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)
    val auth = DriveAuth(context, DriveAuth.SCOPE_APPDATA, "drive_sync")
    private val lock = Mutex()
    private val dao get() = c.db.dao()
    private val media get() = c.media.dao()

    private val dir: File get() = File(context.filesDir, "sync").apply { mkdirs() }
    private val shadowFile: File get() = File(dir, "shadow.json")
    private val logIndexFile: File get() = File(dir, "logs.json")

    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: ("a-" + UUID.randomUUID().toString().replace("-", "").take(10)).also {
            prefs.edit().putString("deviceId", it).apply()
        }

    val isOn: Boolean get() = prefs.getBoolean("on", false)
    val deviceName: String get() = prefs.getString("name", null)?.takeIf { it.isNotBlank() } ?: defaultName()

    private var names: Map<String, String> = loadNames()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    val prompt = MutableStateFlow<Prompt?>(null)

    init {
        publish()
    }

    private fun defaultName(): String {
        val m = Build.MODEL ?: ""
        return when {
            m.startsWith("SM-F9") || m.contains("Fold", ignoreCase = true) -> "Fold"
            m.startsWith("SM-F7") || m.contains("Flip", ignoreCase = true) -> "Flip"
            else -> "Android"
        }
    }

    /** A device's name for the listening log ("iPhone"); this device's own name for its own entries. */
    fun nameOf(id: String): String = if (id.isEmpty() || id == deviceId) deviceName else names[id] ?: "Other device"

    private fun loadNames(): Map<String, String> = try {
        val o = JSONObject(prefs.getString("names", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optString(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun publish(running: Boolean = _status.value.running, error: String? = _status.value.error) {
        _status.value = Status(
            on = isOn,
            deviceName = deviceName,
            lastSyncAt = prefs.getLong("lastSyncAt", 0),
            running = running,
            error = error,
            otherDevices = names.filterKeys { it != deviceId }.values.distinct().sorted(),
        )
    }

    private fun remote(): SyncRemote = if (prefs.getBoolean("testFolder", false)) FolderRemote(testDir()) else DriveAppDataRemote(auth)

    /** The emulator checks use a plain folder instead of Drive. */
    fun testDir(): File = File(context.getExternalFilesDir(null), "sync-test")

    // ---------- Turning it on and off ----------

    fun turnOn() {
        prefs.edit().putBoolean("on", true).apply()
        schedule(context)
        publish(error = null)
        request("turned on", 0)
    }

    fun turnOff() {
        prefs.edit().putBoolean("on", false).apply()
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        prompt.value = null
        publish(error = null)
    }

    fun setDeviceName(name: String) {
        prefs.edit().putString("name", name.trim()).apply()
        publish()
        request("renamed", 0)
    }

    fun enableTestFolder() {
        prefs.edit().putBoolean("testFolder", true).putBoolean("on", true).putString("name", "Fold").apply()
        publish(error = null)
    }

    // ---------- When to sync ----------

    private var pending: Job? = null

    /** Syncs soon (several requests close together become one). */
    fun request(reason: String, delayMs: Long = 3_000) {
        if (!isOn) return
        pending?.cancel()
        pending = c.appScope.launch {
            delay(delayMs)
            syncNow(reason)
        }
    }

    // ---------- Syncing ----------

    suspend fun syncNow(reason: String = "now"): Boolean = lock.withLock {
        if (!isOn) return@withLock false
        withContext(Dispatchers.IO) {
            publish(running = true)
            try {
                val r = remote()
                var files = emptyMap<String, String>()
                var snap: Snapshot? = null
                for (attempt in 0 until 3) {
                    files = r.list()
                    val remoteStamp = files[STATE]
                    val remoteState = SyncState.parse(if (remoteStamp != null) r.read(STATE) else null)
                    val shadow = SyncState.parse(if (shadowFile.exists()) shadowFile.readText() else null)
                    val s = snapshot(shadow)
                    snap = s
                    val now = System.currentTimeMillis()
                    val frozen = shadow.unapplied + frozenKeys(s)
                    val result = SyncMerge.merge(remoteState.items, shadow.items, s.items, s.owners, shadow.owners, deviceId, now, frozen)

                    val devices = HashMap(remoteState.devices)
                    val mine = devices[deviceId]
                    devices[deviceId] = deviceName to now
                    val deviceStale = mine == null || mine.first != deviceName || now - mine.second > 6 * 3_600_000L
                    if (result.upload || deviceStale) {
                        // Another device wrote since we read: start over with its changes.
                        if (attempt < 2 && remoteStamp != null && r.stamp(STATE) != remoteStamp) continue
                        r.write(STATE, SyncState(result.merged.toMutableMap(), devices).toJson())
                    }
                    if ("photo" in result.won) uploadPhoto(r)
                    names = devices.mapValues { it.value.first }
                    prefs.edit().putString("names", JSONObject(names).toString()).apply()

                    val unapplied = apply(result.apply, result.merged, s, r)
                    val newShadow = SyncState(result.merged.toMutableMap(), HashMap(), s.owners.toMutableSet(), unapplied.toMutableSet())
                    shadowFile.writeText(newShadow.toJson())
                    break
                }
                snap?.let { syncLogs(r, it, files) }
                prefs.edit().putLong("lastSyncAt", System.currentTimeMillis()).apply()
                publish(running = false, error = null)
                true
            } catch (e: Exception) {
                publish(running = false, error = e.message ?: e.javaClass.simpleName)
                false
            }
        }
    }

    // ---------- This library as sync items ----------

    private class Snapshot(
        val items: HashMap<String, LocalItem> = HashMap(),
        val owners: HashSet<String> = hashSetOf("*"),
        /** Book key → this library's book. */
        val books: HashMap<String, BookEntity> = HashMap(),
        /** This library's book id → its key. */
        val keyOf: HashMap<String, String> = HashMap(),
        /** Feed → the show you follow here. */
        val shows: HashMap<String, PodcastEntity> = HashMap(),
        /** Day → minutes listened on this device (milliseconds). */
        val ownDays: HashMap<Long, Long> = HashMap(),
        val dbDays: HashMap<Long, Long> = HashMap(),
    )

    private suspend fun snapshot(shadow: SyncState): Snapshot {
        val s = Snapshot()
        val me = deviceId
        val books = dao.getBooks().sortedByDescending { it.lastPlayedAt }
        for (b in books) {
            val bk = SyncKeys.book(b.title, b.author, b.isExternal) ?: continue
            if (bk in s.books) continue
            s.books[bk] = b
            s.keyOf[b.id] = bk
            s.owners += bk
            s.items["b/$bk/pos"] = LocalItem(Canon.obj("p" to b.positionMs, "lp" to b.lastPlayedAt, "d" to b.durationMs / 1000), bk, b.lastPlayedAt, hinted = true)
            s.items["b/$bk/status"] = LocalItem(Canon.obj("s" to b.status, "f" to b.finishedAt), bk)
            s.items["b/$bk/speed"] = LocalItem(Canon.obj("v" to (b.speed * 100).roundToInt()), bk)
            s.items["b/$bk/rating"] = LocalItem(Canon.obj("v" to b.rating), bk)
            s.items["b/$bk/sound"] = LocalItem(
                Canon.obj("ce" to b.customEffects, "ts" to b.trimSilence, "vb" to b.voiceBoost, "si" to b.skipIntroSec, "so" to b.skipOutroSec),
                bk,
            )
        }
        for (m in dao.getAllBookmarks()) {
            val bk = s.keyOf[m.bookId] ?: continue
            s.items["mark/${m.createdAt}"] = LocalItem(Canon.obj("b" to bk, "p" to m.positionMs, "n" to m.note, "c" to m.chapterTitle), bk)
        }

        val shows = media.getPodcasts()
        val showById = shows.associateBy { it.id }
        for (p in shows) {
            s.shows[p.feedUrl] = p
            s.owners += SyncKeys.show(p.feedUrl)
            s.items["show/${p.feedUrl}"] = LocalItem(
                Canon.obj(
                    "ad" to p.autoDownload, "nt" to p.notify, "sp" to (p.speed * 100).roundToInt(), "aq" to p.autoQueue, "aa" to p.autoArchive,
                    "si" to p.skipIntroSec, "so" to p.skipOutroSec, "ce" to p.customEffects, "ts" to p.trimSilence, "vb" to p.voiceBoost,
                ),
                "*",
            )
        }
        for (e in media.getEpisodesWithProgress()) {
            val p = showById[e.podcastId] ?: continue
            val ek = SyncKeys.episode(p.feedUrl, e.guid)
            val owner = SyncKeys.show(p.feedUrl)
            s.items["ep/$ek"] = LocalItem(
                Canon.obj("f" to p.feedUrl, "g" to e.guid, "p" to e.positionMs, "pl" to e.played, "pa" to e.playedAt, "lp" to e.lastPlayedAt),
                owner, maxOf(e.lastPlayedAt, e.playedAt), hinted = true,
            )
            s.items["epa/$ek"] = LocalItem(Canon.obj("f" to p.feedUrl, "g" to e.guid, "v" to e.archived), owner)
        }
        s.items["podq"] = LocalItem(
            Canon.obj("v" to media.getQueue().mapNotNull { e -> showById[e.podcastId]?.let { SyncKeys.episode(it.feedUrl, e.guid) } }),
            "*",
        )

        for (k in dao.getClips()) {
            val v: String
            val owner: String
            if (k.mediaId.startsWith(PlaybackService.EPISODE_PREFIX)) {
                val ep = media.getEpisode(k.mediaId.removePrefix(PlaybackService.EPISODE_PREFIX)) ?: continue
                val show = showById[ep.podcastId] ?: continue
                owner = SyncKeys.show(show.feedUrl)
                v = Canon.obj("f" to show.feedUrl, "g" to ep.guid, "ti" to k.title, "su" to k.subtitle, "s" to k.startMs, "e" to k.endMs, "n" to k.note, "c" to k.chapterTitle)
            } else {
                owner = s.keyOf[k.mediaId] ?: continue
                v = Canon.obj("b" to owner, "ti" to k.title, "su" to k.subtitle, "s" to k.startMs, "e" to k.endMs, "n" to k.note, "c" to k.chapterTitle)
            }
            s.items["clip/${k.createdAt}"] = LocalItem(v, owner)
        }

        val shelves = dao.getShelves()
        val shelfName = shelves.associate { it.id to it.name }
        for (sh in shelves) s.items["shelf/${sh.name.lowercase()}"] = LocalItem(Canon.obj("n" to sh.name), "*")
        for (l in dao.getShelfLinks()) {
            val bk = s.keyOf[l.bookId] ?: continue
            val name = shelfName[l.shelfId]?.lowercase() ?: continue
            s.items["shelfbook/$name/$bk"] = LocalItem(Canon.obj("s" to name, "b" to bk), bk)
        }

        val st = c.settings.current()
        s.items["upnext"] = LocalItem(Canon.obj("v" to st.upNext.mapNotNull { s.keyOf[it] }), "*")
        s.items["goal"] = LocalItem(Canon.obj("d" to st.dailyGoalMin, "y" to st.yearlyGoalBooks), "*")
        s.items["name"] = LocalItem(Canon.obj("v" to st.profileName), "*")
        s.items["photo"] = LocalItem(Canon.obj("at" to st.profilePhotoAt), "*")

        // Daily listening: the library keeps one total per day; each device sends only its own share.
        val others = HashMap<Long, Long>()
        for ((k, e) in shadow.items) {
            if (!k.startsWith("day/") || e.v == null) continue
            val parts = k.split("/")
            if (parts.size != 3 || parts[2] == me) continue
            val day = parts[1].toLongOrNull() ?: continue
            others[day] = (others[day] ?: 0L) + JSONObject(e.v).optLong("v")
        }
        for (d in dao.getAllDays()) {
            s.dbDays[d.day] = d.listenedMs
            val own = (d.listenedMs - (others[d.day] ?: 0L)).coerceAtLeast(0)
            s.ownDays[d.day] = own
            if (own > 0) s.items["day/${d.day}/$me"] = LocalItem(Canon.obj("v" to own), "*")
        }
        return s
    }

    // ---------- Putting other devices' changes into this library ----------

    /** Returns the items that couldn't be applied yet (a show that wouldn't load). */
    private suspend fun apply(changes: Map<String, SyncEntry>, merged: Map<String, SyncEntry>, s: Snapshot, r: SyncRemote): Set<String> {
        val unapplied = HashSet<String>()
        // Shelves before what goes on them; shows before their episodes.
        val order = changes.keys.sortedBy {
            when {
                it.startsWith("shelf/") -> 0
                it.startsWith("show/") -> 1
                else -> 2
            }
        }
        var queueChanged = false
        for (key in order) {
            val e = changes.getValue(key)
            val v = e.v?.let { JSONObject(it) }
            try {
                when {
                    key.startsWith("b/") -> applyBook(key, e, v, s)
                    key.startsWith("mark/") -> applyMark(key, v, s)
                    key.startsWith("clip/") -> applyClip(key, v, s)
                    key.startsWith("shelf/") -> applyShelf(key, v)
                    key.startsWith("shelfbook/") -> applyShelfBook(key, v, merged, s)
                    key == "upnext" && v != null -> {
                        val ids = v.optJSONArray("v")?.let { a -> (0 until a.length()).mapNotNull { s.books[a.optString(it)]?.id } } ?: emptyList()
                        c.settings.editUpNext { ids }
                    }
                    key == "goal" && v != null -> {
                        c.settings.setDailyGoal(v.optInt("d", 30))
                        c.settings.setYearlyGoal(v.optInt("y", 24))
                    }
                    key == "name" && v != null -> c.settings.setProfileName(v.optString("v"))
                    key == "photo" && v != null -> applyPhoto(v.optLong("at"), r)
                    key.startsWith("show/") -> if (!applyShow(key.removePrefix("show/"), v, s)) unapplied += key
                    key.startsWith("ep/") -> applyEpisode(v)
                    key.startsWith("epa/") -> v?.let { o ->
                        episodeFor(o)?.let { ep -> if (ep.archived != o.optBoolean("v")) c.podcasts.setArchived(ep.id, o.optBoolean("v")) }
                    }
                    key == "podq" -> queueChanged = true
                }
            } catch (ex: Exception) {
                // One item that won't go in shouldn't stop the rest.
            }
        }
        if (queueChanged) applyQueue(merged["podq"]?.v)
        applyDays(merged, s)
        return unapplied
    }

    private suspend fun applyBook(key: String, e: SyncEntry, v: JSONObject?, s: Snapshot) {
        if (v == null) return
        val parts = key.split("/")
        if (parts.size != 3) return
        val book = s.books[parts[1]] ?: return
        when (parts[2]) {
            "pos" -> applyPlace(key, book, v, e)
            "status" -> dao.setStatus(book.id, v.optInt("s"), v.optLong("f"))
            "speed" -> dao.updateSpeed(book.id, (v.optInt("v", 100) / 100f).coerceIn(0.5f, 3.5f))
            "rating" -> dao.setRating(book.id, v.optInt("v"))
            "sound" -> dao.setSound(book.id, v.optBoolean("ce"), v.optBoolean("ts"), v.optBoolean("vb"), v.optInt("si"), v.optInt("so"))
        }
    }

    /**
     * Another device's place in a book. A book you aren't listening to here just moves. The book
     * loaded in the player asks first ("Go there?"), unless it's playing (then this device is newer).
     */
    private suspend fun applyPlace(key: String, book: BookEntity, v: JSONObject, e: SyncEntry) {
        val p = v.optLong("p")
        val lp = v.optLong("lp")
        // Not the same recording (different lengths): leave it.
        val d = v.optLong("d")
        val local = book.durationMs / 1000
        if (!book.isExternal && d > 0 && local > 0 && abs(d - local) > maxOf(5L, local / 200)) return
        val target = if (book.durationMs > 0) p.coerceIn(0, book.durationMs) else p
        val loaded = PlaybackService.running && PlaybackStatus.loadedId.value == book.id
        if (loaded) {
            if (PlaybackStatus.isPlaying.value) return
            if (abs(target - book.positionMs) < 15_000) return
            freeze(key, System.currentTimeMillis())
            prompt.value = Prompt(book.id, target, nameOf(e.d), lp, key)
            return
        }
        unfreeze(key)
        if (prompt.value?.key == key) prompt.value = null
        dao.setPlace(book.id, target, lp)
    }

    private suspend fun applyMark(key: String, v: JSONObject?, s: Snapshot) {
        val created = key.removePrefix("mark/").toLongOrNull() ?: return
        val existing = dao.getBookmarksCreatedAt(created)
        if (v == null) {
            existing.forEach { dao.deleteBookmark(it.id) }
            return
        }
        val book = s.books[v.optString("b")] ?: return
        val have = existing.firstOrNull { it.bookId == book.id }
        if (have != null) {
            dao.updateBookmark(have.copy(positionMs = v.optLong("p"), note = v.optString("n"), chapterTitle = v.optString("c")))
        } else {
            dao.insertBookmark(BookmarkEntity(bookId = book.id, positionMs = v.optLong("p"), note = v.optString("n"), chapterTitle = v.optString("c"), createdAt = created))
        }
    }

    private suspend fun applyClip(key: String, v: JSONObject?, s: Snapshot) {
        val created = key.removePrefix("clip/").toLongOrNull() ?: return
        val existing = dao.getClipsCreatedAt(created)
        if (v == null) {
            existing.forEach { dao.deleteClip(it.id) }
            return
        }
        val mediaId: String
        val cover: String?
        if (v.has("f")) {
            val ep = episodeFor(v) ?: return
            mediaId = PlaybackService.EPISODE_PREFIX + ep.id
            cover = media.getPodcast(ep.podcastId)?.artworkPath
        } else {
            val book = s.books[v.optString("b")] ?: return
            mediaId = book.id
            cover = book.coverPath
        }
        val have = existing.firstOrNull { it.mediaId == mediaId }
        val clip = ClipEntity(
            id = have?.id ?: 0,
            mediaId = mediaId,
            title = v.optString("ti"),
            subtitle = v.optString("su"),
            startMs = v.optLong("s"),
            endMs = v.optLong("e"),
            note = v.optString("n"),
            chapterTitle = v.optString("c"),
            coverPath = have?.coverPath ?: cover,
            createdAt = created,
        )
        if (have != null) dao.updateClip(clip) else dao.insertClip(clip)
    }

    private suspend fun applyShelf(key: String, v: JSONObject?) {
        val lower = key.removePrefix("shelf/")
        val shelf = dao.getShelves().firstOrNull { it.name.lowercase() == lower }
        if (v == null) {
            if (shelf != null) dao.deleteShelf(shelf.id)
        } else if (shelf == null) {
            dao.insertShelf(ShelfEntity(name = v.optString("n").ifBlank { lower }))
        }
    }

    private suspend fun applyShelfBook(key: String, v: JSONObject?, merged: Map<String, SyncEntry>, s: Snapshot) {
        val rest = key.removePrefix("shelfbook/")
        val cut = rest.lastIndexOf('/')
        if (cut <= 0) return
        val lower = rest.substring(0, cut)
        val book = s.books[rest.substring(cut + 1)] ?: return
        var shelf = dao.getShelves().firstOrNull { it.name.lowercase() == lower }
        if (v == null) {
            if (shelf != null) dao.removeFromShelf(shelf.id, book.id)
            return
        }
        if (shelf == null) {
            val name = merged["shelf/$lower"]?.v?.let { JSONObject(it).optString("n") }?.takeIf { it.isNotBlank() } ?: lower
            val id = dao.insertShelf(ShelfEntity(name = name))
            shelf = ShelfEntity(id = id, name = name)
        }
        dao.addToShelf(ShelfBookEntity(shelf.id, book.id))
    }

    private suspend fun applyPhoto(at: Long, r: SyncRemote) {
        if (at <= 0L) {
            ProfilePhoto.delete(context)
            c.settings.setProfilePhotoAt(0)
            return
        }
        val text = r.read(PHOTO) ?: return
        val bytes = Base64.decode(text, Base64.NO_WRAP)
        if (ProfilePhoto.saveBytes(context, bytes)) c.settings.setProfilePhotoAt(at)
    }

    private suspend fun uploadPhoto(r: SyncRemote) {
        val f = ProfilePhoto.file(context)
        if (f.exists() && f.length() in 1..600_000) r.write(PHOTO, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
    }

    /** Follows a show you followed on another device (or unfollows). False if its feed wouldn't load. */
    private suspend fun applyShow(feed: String, v: JSONObject?, s: Snapshot): Boolean {
        val have = s.shows[feed]
        if (v == null) {
            if (have != null) c.podcasts.unfollow(have.id)
            return true
        }
        val id = have?.id ?: c.podcasts.follow(feed) ?: return false
        val p = media.getPodcast(id) ?: return false
        media.updatePodcast(
            p.copy(
                autoDownload = v.optBoolean("ad"),
                notify = v.optBoolean("nt", true),
                speed = (v.optInt("sp", 100) / 100f).coerceIn(0.5f, 3.5f),
                autoQueue = v.optBoolean("aq"),
                autoArchive = v.optBoolean("aa", true),
                skipIntroSec = v.optInt("si"),
                skipOutroSec = v.optInt("so"),
                customEffects = v.optBoolean("ce"),
                trimSilence = v.optBoolean("ts"),
                voiceBoost = v.optBoolean("vb"),
            ),
        )
        return true
    }

    private suspend fun episodeFor(v: JSONObject) = media.getEpisodeByGuid(Feeds.podcastId(v.optString("f")), v.optString("g"))

    private suspend fun applyEpisode(v: JSONObject?) {
        if (v == null) return
        val ep = episodeFor(v) ?: return
        // The episode in the player: this device's own place wins once you play it.
        if (PlaybackService.running && PlaybackStatus.loadedId.value == PlaybackService.EPISODE_PREFIX + ep.id) return
        val played = v.optBoolean("pl")
        media.updateEpisode(
            ep.copy(
                positionMs = v.optLong("p"),
                played = played,
                playedAt = v.optLong("pa"),
                lastPlayedAt = v.optLong("lp"),
            ),
        )
        if (played) media.removeFromQueue(ep.id)
    }

    private suspend fun applyQueue(text: String?) {
        val o = text?.let { JSONObject(it) } ?: return
        val a = o.optJSONArray("v") ?: return
        val ids = (0 until a.length()).mapNotNull { i ->
            val key = a.optString(i)
            val cut = key.lastIndexOf('|')
            if (cut <= 0) return@mapNotNull null
            media.getEpisodeByGuid(Feeds.podcastId(key.substring(0, cut)), key.substring(cut + 1))?.takeIf { !it.played }?.id
        }
        media.clearQueue()
        ids.forEachIndexed { i, id -> media.putInQueue(QueueEntity(id, i.toLong())) }
    }

    /** Each day's total: this device's own listening plus every other device's. */
    private suspend fun applyDays(merged: Map<String, SyncEntry>, s: Snapshot) {
        val me = deviceId
        val others = HashMap<Long, Long>()
        for ((k, e) in merged) {
            if (!k.startsWith("day/") || e.v == null) continue
            val parts = k.split("/")
            if (parts.size != 3 || parts[2] == me) continue
            val day = parts[1].toLongOrNull() ?: continue
            others[day] = (others[day] ?: 0L) + JSONObject(e.v).optLong("v")
        }
        for (day in others.keys + s.ownDays.keys) {
            val total = (s.ownDays[day] ?: 0L) + (others[day] ?: 0L)
            if (total != (s.dbDays[day] ?: 0L)) dao.setDay(day, total)
        }
    }

    // ---------- "Go there?" ----------

    private fun frozenMap(): JSONObject = try {
        JSONObject(prefs.getString("frozen", "{}") ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun freeze(key: String, since: Long) {
        val m = frozenMap()
        if (!m.has(key)) m.put(key, since)
        prefs.edit().putString("frozen", m.toString()).apply()
    }

    private fun unfreeze(key: String) {
        val m = frozenMap()
        if (m.has(key)) {
            m.remove(key)
            prefs.edit().putString("frozen", m.toString()).apply()
        }
    }

    /** Places waiting on "Go there?". Listening here since then ends the wait (this device is newer). */
    private fun frozenKeys(s: Snapshot): Set<String> {
        val m = frozenMap()
        val out = HashSet<String>()
        for (k in m.keys()) {
            val since = m.optLong(k)
            val played = s.items[k]?.hint ?: 0L
            if (played > since) {
                unfreeze(k)
                if (prompt.value?.key == k) prompt.value = null
            } else {
                out += k
            }
        }
        return out
    }

    /** "Go there" ([go]) or "Stay here". Staying makes this device's place the newest, so it syncs out. */
    fun answer(go: Boolean) {
        val p = prompt.value ?: return
        prompt.value = null
        unfreeze(p.key)
        c.appScope.launch {
            if (!go) dao.getBook(p.bookId)?.let { dao.setLastPlayed(it.id, System.currentTimeMillis()) }
            request("answered", 2_000)
        }
    }

    // ---------- Listening logs ----------

    private fun uidOf(row: ListenEventEntity) = if (row.device.isEmpty()) "$deviceId:${row.id}" else row.uid

    private fun toEntry(row: ListenEventEntity) = LogEntry(
        uidOf(row), row.device.ifEmpty { deviceId }, row.at, row.kind, row.positionMs, row.toMs, (row.speed * 100).roundToInt(), row.reason,
    )

    private suspend fun syncLogs(r: SyncRemote, s: Snapshot, files: Map<String, String>) {
        val index = try {
            JSONObject(if (logIndexFile.exists()) logIndexFile.readText() else "{}")
        } catch (e: Exception) {
            JSONObject()
        }
        val now = System.currentTimeMillis()
        for ((bk, book) in s.books) {
            if (book.isExternal) continue
            val name = SyncKeys.logFile(bk)
            val sig = dao.listenLogSignature(book.id)
            val rec = index.optJSONObject(bk)
            val remoteMod = files[name]
            if (rec != null && rec.optString("sig") == sig.toString() && rec.optString("mod") == (remoteMod ?: "")) continue
            if (rec == null && sig.n == 0 && remoteMod == null) continue
            // Emptied here since the last sync: "Clear all".
            var localCleared = rec?.optLong("cl") ?: 0L
            if (rec != null && !rec.optString("sig").startsWith("0:") && sig.n == 0) localCleared = now

            val remoteFile = if (remoteMod != null) LogFile.parse(r.read(name)) else null
            val rows = dao.getListenLog(book.id)
            val (merged, cleared) = LogMerge.merge(remoteFile?.entries ?: emptyList(), remoteFile?.cleared ?: 0L, rows.map { toEntry(it) }, localCleared)

            val mergedIds = merged.associateBy { it.u }
            val rowByUid = rows.associateBy { uidOf(it) }
            val gone = rows.filter { uidOf(it) !in mergedIds }.map { it.id }
            if (gone.isNotEmpty()) gone.chunked(500).forEach { dao.deleteListenEvents(it) }
            val added = merged.filter { it.u !in rowByUid }.map { e ->
                ListenEventEntity(
                    bookId = book.id, at = e.at, kind = e.k, positionMs = e.p, toMs = e.to,
                    speed = e.s / 100f, reason = e.r, uid = e.u, device = e.d,
                )
            }
            if (added.isNotEmpty()) dao.insertListenEvents(added)
            for (m in merged) {
                val row = rowByUid[m.u] ?: continue
                if (row.device.isNotEmpty() && (row.at != m.at || row.toMs != m.to)) dao.updateListenEvent(row.copy(at = m.at, toMs = m.to))
            }

            var mod = remoteMod
            val same = remoteFile != null && remoteFile.cleared == cleared && remoteFile.entries == merged
            if (!same && !(remoteFile == null && merged.isEmpty() && cleared == 0L)) mod = r.write(name, LogFile(bk, cleared, merged).toJson())
            index.put(bk, JSONObject().put("sig", dao.listenLogSignature(book.id).toString()).put("mod", mod ?: "").put("cl", cleared))
        }
        logIndexFile.writeText(index.toString())
    }

    // ---------- Emulator checks ----------

    /** Writes a change as if an iPhone had made it: a newer place, a bookmark and two log entries. */
    suspend fun testFakeOtherDevice(bookId: String, positionMs: Long) = withContext(Dispatchers.IO) {
        val r = remote()
        val book = dao.getBook(bookId) ?: return@withContext
        val bk = SyncKeys.book(book.title, book.author, book.isExternal) ?: return@withContext
        val now = System.currentTimeMillis()
        val dev = "test-iphone"
        val st = SyncState.parse(r.read(STATE))
        st.items["b/$bk/pos"] = SyncEntry(Canon.obj("p" to positionMs, "lp" to now, "d" to book.durationMs / 1000), now, dev, bk)
        st.items["b/$bk/status"] = SyncEntry(Canon.obj("s" to BookEntity.STATUS_IN_PROGRESS, "f" to 0L), now, dev, bk)
        st.items["mark/$now"] = SyncEntry(Canon.obj("b" to bk, "p" to positionMs - 5_000, "n" to "Saved on the iPhone", "c" to ""), now, dev, bk)
        st.devices[dev] = "iPhone" to now
        r.write(STATE, st.toJson())
        val name = SyncKeys.logFile(bk)
        val lf = LogFile.parse(r.read(name))
        val entries = (lf?.entries ?: emptyList()) + listOf(
            LogEntry("$dev:1", dev, now - 120_000, ListenEventEntity.PLAY, positionMs - 110_000, -1, 100, 0),
            LogEntry("$dev:2", dev, now - 5_000, ListenEventEntity.PAUSE, positionMs, -1, 100, ListenEventEntity.REASON_UNPLUGGED),
        )
        r.write(name, LogFile(bk, lf?.cleared ?: 0L, entries.sortedByDescending { it.at }).toJson())
    }

    companion object {
        const val STATE = "state.json"
        const val PHOTO = "photo.b64"
        private const val WORK = "sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sync = applicationContext.container.sync
        if (!sync.isOn) return Result.success()
        sync.syncNow("background")
        return Result.success()
    }
}
