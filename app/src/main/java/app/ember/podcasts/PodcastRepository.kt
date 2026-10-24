package app.ember.podcasts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.ember.R
import app.ember.container
import app.ember.data.CoverStore
import app.ember.downloads.Downloads
import app.ember.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PodcastRepository(
    private val context: Context,
    private val db: MediaDatabase,
    private val covers: CoverStore,
    private val downloads: Downloads,
) {
    private val dao = db.dao()
    private val refreshLock = Mutex()


    suspend fun search(term: String): List<SearchResult> = withContext(Dispatchers.IO) {
        try {
            Feeds.search(term)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private val chartCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, List<SearchResult>>>()

    /** Top shows, cached for six hours per category. [genreId] -1 means all categories. */
    suspend fun topCharts(genreId: Int, genreName: String?): List<SearchResult> = withContext(Dispatchers.IO) {
        chartCache[genreId]?.let { (at, list) -> if (System.currentTimeMillis() - at < 6 * 3_600_000L && list.isNotEmpty()) return@withContext list }
        val list = try {
            Feeds.topCharts(genreId.takeIf { it > 0 }, genreName)
        } catch (e: Exception) {
            emptyList()
        }
        if (list.isNotEmpty()) chartCache[genreId] = System.currentTimeMillis() to list
        list
    }

    /** Marks every episode of a show as played (and clears their downloads). */
    suspend fun markAllPlayed(podcastId: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val archive = dao.getPodcast(podcastId)?.autoArchive ?: true
        dao.getEpisodes(podcastId).filter { !it.played }.forEach { e ->
            dao.updateEpisode(e.copy(played = true, playedAt = now, positionMs = e.durationMs, archived = e.archived || archive))
            dao.removeFromQueue(e.id)
        }
        cleanUpPlayed()
    }

    /** Hides an episode from your lists (and frees its download), or brings it back. */
    suspend fun setArchived(id: String, archived: Boolean, keepDownload: Boolean = false) = withContext(Dispatchers.IO) {
        val e = dao.getEpisode(id) ?: return@withContext
        dao.setArchived(id, archived)
        if (archived) {
            dao.removeFromQueue(id)
            if (!keepDownload && e.downloadState != EpisodeEntity.DL_NONE) downloads.removeEpisode(e)
        }
    }

    /** Archives every played episode of a show (for shows where auto archive was just turned on). */
    suspend fun archivePlayed(podcastId: String) = withContext(Dispatchers.IO) {
        dao.getEpisodes(podcastId).filter { it.played && !it.archived }.forEach { dao.setArchived(it.id, true) }
    }

    /** Follows a show. Returns its id, or null if the feed couldn't be read. */
    suspend fun follow(feedUrl: String, artworkHint: String? = null): String? = withContext(Dispatchers.IO) {
        val id = Feeds.podcastId(feedUrl)
        if (dao.getPodcast(id) != null) return@withContext id
        val feed = try {
            Feeds.fetch(feedUrl)
        } catch (e: Exception) {
            return@withContext null
        }
        val art = feed.artworkUrl.ifBlank { artworkHint ?: "" }
        dao.upsertPodcast(
            PodcastEntity(
                id = id,
                feedUrl = feedUrl,
                title = feed.title,
                author = feed.author,
                description = feed.description,
                artworkUrl = art,
                category = feed.category,
                lastRefreshAt = System.currentTimeMillis(),
            ),
        )
        dao.insertEpisodes(feed.episodes.map { it.toEntity(id) })
        if (art.isNotBlank()) {
            covers.download(id, art)?.let { saved ->
                dao.getPodcast(id)?.let { dao.updatePodcast(it.copy(artworkPath = saved.path, color = saved.color)) }
            }
        }
        // Nothing downloads on follow: auto-download is off until you turn it on, and then
        // only episodes published after that are fetched.
        id
    }

    suspend fun unfollow(id: String) {
        dao.getDownloadsFor(id).forEach { downloads.removeEpisode(it) }
        dao.deletePodcast(id)
    }

    suspend fun importOpml(uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val urls = try {
            context.contentResolver.openInputStream(uri)?.use { Feeds.parseOpml(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val gate = Semaphore(4)
        val results = coroutineScope {
            urls.map { u -> async { gate.withPermit { follow(u) != null } } }.awaitAll()
        }
        results.count { it } to urls.size
    }

    /** Refreshes every followed show. Returns new episodes found (for alerts). */
    suspend fun refreshAll(): List<Pair<PodcastEntity, EpisodeEntity>> = refreshLock.withLock {
        withContext(Dispatchers.IO) {
            val gate = Semaphore(4)
            val found = coroutineScope {
                dao.getPodcasts().map { p -> async { gate.withPermit { refresh(p) } } }.awaitAll()
            }.flatten()
            cleanUpPlayed()
            found
        }
    }

    /** Checks one show for new episodes. */
    suspend fun refreshOne(id: String): List<Pair<PodcastEntity, EpisodeEntity>> = refreshLock.withLock {
        withContext(Dispatchers.IO) { dao.getPodcast(id)?.let { refresh(it) } ?: emptyList() }
    }

    private suspend fun refresh(p: PodcastEntity): List<Pair<PodcastEntity, EpisodeEntity>> {
        val feed = try {
            Feeds.fetch(p.feedUrl)
        } catch (e: Exception) {
            return emptyList()
        }
        val known = dao.episodeIds(p.id).toHashSet()
        val all = feed.episodes.map { it.toEntity(p.id) }
        val fresh = all.filter { it.id !in known }
        dao.insertEpisodes(fresh)
        // Shows sometimes add a chapters file after publishing.
        all.filter { it.id in known && it.chaptersUrl.isNotBlank() }.forEach { dao.setChaptersUrl(it.id, it.chaptersUrl) }
        val updated = p.copy(
            title = feed.title.ifBlank { p.title },
            author = feed.author.ifBlank { p.author },
            description = feed.description.ifBlank { p.description },
            category = feed.category.ifBlank { p.category },
            lastRefreshAt = System.currentTimeMillis(),
        )
        dao.updatePodcast(updated)
        // "New" means published since the last check and since you followed (not back catalog).
        val newlyOut = fresh.filter { it.publishedAt > maxOf(p.lastRefreshAt, p.subscribedAt) - 60_000 }
        autoDownload(updated, newlyOut)
        if (updated.autoQueue) newlyOut.sortedBy { it.publishedAt }.forEach { addToQueue(it.id, next = false) }
        return newlyOut.map { updated to it }
    }

    /**
     * Downloads newly published episodes, newest first, until the show has as many unplayed
     * downloads as your setting allows. Older episodes are never fetched to fill the gap,
     * so removing a download doesn't pull in a different one.
     */
    private suspend fun autoDownload(p: PodcastEntity, newlyOut: List<EpisodeEntity>) {
        if (!p.autoDownload || newlyOut.isEmpty()) return
        val limit = context.container.settings.current().autoDownloadCount
        var have = dao.countUnplayedDownloads(p.id)
        for (e in newlyOut.sortedByDescending { it.publishedAt }) {
            if (have >= limit) break
            downloads.downloadEpisode(e, wifiOnly = true, title = p.title)
            have++
        }
    }

    /** Frees space: removes downloads of episodes you've finished. */
    suspend fun cleanUpPlayed() {
        dao.getPlayedDownloads().forEach { downloads.removeEpisode(it) }
    }

    /** [keepDownload]: leave the file for now (the caller removes it once Undo has passed). */
    suspend fun setPlayed(id: String, played: Boolean, keepDownload: Boolean = false) {
        val e = dao.getEpisode(id) ?: return
        val now = System.currentTimeMillis()
        val show = dao.getPodcast(e.podcastId)
        dao.updateEpisode(
            e.copy(
                played = played,
                playedAt = if (played) now else 0,
                positionMs = if (played) e.durationMs else 0,
                // Finished episodes tidy themselves away (per show); unplaying brings one back.
                archived = if (played) e.archived || (show?.autoArchive ?: true) else false,
            ),
        )
        if (played) {
            dao.removeFromQueue(id)
            // Free the space; nothing older is downloaded in its place.
            if (!keepDownload && e.downloadState != EpisodeEntity.DL_NONE) downloads.removeEpisode(e)
        }
    }

    suspend fun addToQueue(id: String, next: Boolean) {
        val pos = if (next) (dao.minQueuePosition() ?: 0L) - 1 else (dao.maxQueuePosition() ?: 0L) + 1
        dao.putInQueue(QueueEntity(id, pos))
    }

    suspend fun moveInQueue(id: String, up: Boolean) {
        val q = dao.getQueue()
        val i = q.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in q.indices) return
        val order = q.map { it.id }.toMutableList()
        order[i] = order[j].also { order[j] = order[i] }
        order.forEachIndexed { idx, eid -> dao.putInQueue(QueueEntity(eid, idx.toLong())) }
    }

    private fun ParsedEpisode.toEntity(podcastId: String) = EpisodeEntity(
        id = Feeds.episodeId(podcastId, guid),
        podcastId = podcastId,
        guid = guid,
        title = title,
        description = description,
        audioUrl = audioUrl,
        durationMs = durationMs,
        publishedAt = publishedAt,
        chaptersUrl = chaptersUrl,
    )

    // ---- Chapters ----

    /**
     * Finds an episode's chapters: the show's chapters file first, then chapter marks inside
     * the audio (MP3 tags, or M4A chapters once downloaded). Saves them for the player.
     */
    suspend fun loadChapters(episodeId: String, force: Boolean = false) = withContext(Dispatchers.IO) {
        val ep = dao.getEpisode(episodeId) ?: return@withContext
        if (!force && ep.chaptersState != 0) return@withContext
        var reached = false
        var found = emptyList<FoundChapter>()
        if (ep.chaptersUrl.isNotBlank()) {
            try {
                found = EpisodeChapters.parseJson(httpText(ep.chaptersUrl))
                reached = true
            } catch (e: Exception) {
            }
        }
        if (found.size < 2) {
            try {
                val local = ep.localPath?.let { java.io.File(it) }?.takeIf { ep.isDownloaded && it.exists() }
                found = if (local != null) embeddedFromFile(local) else embeddedFromUrl(ep.audioUrl)
                reached = true
            } catch (e: Exception) {
            }
        }
        if (found.size >= 2) {
            dao.replaceEpisodeChapters(
                episodeId,
                found.mapIndexed { i, c -> EpisodeChapterEntity(episodeId, i, c.title, c.startMs, c.imageUrl, c.url) },
            )
        }
        if (reached) dao.setChaptersState(episodeId, 1)
    }

    private fun embeddedFromFile(f: java.io.File): List<FoundChapter> {
        val head = ByteArray(10)
        f.inputStream().use { if (it.read(head) < 10) return emptyList() }
        val len = EpisodeChapters.id3TagLength(head)
        if (len > 0) {
            val tag = ByteArray(minOf(len, MAX_TAG))
            f.inputStream().use { input -> var n = 0; while (n < tag.size) { val r = input.read(tag, n, tag.size - n); if (r <= 0) break; n += r } }
            return EpisodeChapters.parseId3(tag)
        }
        // Not an MP3 tag: try MP4 chapters (M4A episodes).
        return app.ember.data.Mp4ChapterParser.parse(context, android.net.Uri.fromFile(f))
            .map { FoundChapter(it.startMs, it.title) }
    }

    /** Reads just the tag at the start of a streamed MP3, without downloading the episode. */
    private fun embeddedFromUrl(url: String): List<FoundChapter> {
        val head = httpRange(url, 0, 9)
        val len = EpisodeChapters.id3TagLength(head)
        if (len <= 10) return emptyList()
        return EpisodeChapters.parseId3(httpRange(url, 0, minOf(len, MAX_TAG) - 1L))
    }

    private fun httpText(url: String): String {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Ember-Audiobooks/1.0")
        }
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun httpRange(url: String, from: Long, to: Long): ByteArray {
        var current = url
        repeat(6) {
            val conn = (java.net.URL(current).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Ember-Audiobooks/1.0")
                setRequestProperty("Range", "bytes=$from-$to")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    current = java.net.URL(java.net.URL(current), conn.getHeaderField("Location") ?: return ByteArray(0)).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val want = (to - from + 1).toInt()
                val out = ByteArray(want)
                var n = 0
                conn.inputStream.use { input ->
                    while (n < want) {
                        val r = input.read(out, n, want - n)
                        if (r <= 0) break
                        n += r
                    }
                }
                return if (n == want) out else out.copyOf(n)
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("Too many redirects")
    }

    // ---- New-episode alerts ----

    fun notifyNew(found: List<Pair<PodcastEntity, EpisodeEntity>>) {
        val alerts = found.filter { it.first.notify }
        if (alerts.isEmpty()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "New podcast episodes", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).putExtra("ember_route", "podcasts").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alerts.take(5).forEach { (show, ep) ->
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(show.title)
                .setContentText(ep.title)
                .setGroup(GROUP)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify(ep.id.hashCode(), n)
        }
        if (alerts.size > 1) {
            val summary = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("${alerts.size} new episodes")
                .setGroup(GROUP)
                .setGroupSummary(true)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify(SUMMARY_ID, summary)
        }
    }

    companion object {
        private const val MAX_TAG = 4 * 1024 * 1024
        private const val CHANNEL = "new_episodes"
        private const val GROUP = "ember_new_episodes"
        private const val SUMMARY_ID = 4201

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PodcastRefreshWorker>(3, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("podcast-refresh", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

class PodcastRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.container
        if (c.media.dao().getPodcasts().isEmpty()) return Result.success()
        val found = c.podcasts.refreshAll()
        c.podcasts.notifyNew(found)
        c.downloads.reconcile()
        return Result.success()
    }
}
