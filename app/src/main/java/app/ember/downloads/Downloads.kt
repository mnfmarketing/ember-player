package app.ember.downloads

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.ember.container
import app.ember.podcasts.DownloadEntity
import app.ember.podcasts.EpisodeEntity
import kotlinx.coroutines.launch
import java.io.File

/**
 * Downloads podcast episodes and Google Drive audio with Android's download manager,
 * which keeps going in the background and shows its own progress notification.
 */
class Downloads(private val context: Context) {

    private val dm = context.getSystemService(DownloadManager::class.java)
    private val media get() = context.container.media.dao()

    fun episodeFile(episodeId: String): File =
        File(context.getExternalFilesDir("podcasts"), "$episodeId.audio")

    fun driveFile(fileId: String): File =
        File(context.getExternalFilesDir("drive"), "$fileId.audio")

    suspend fun downloadEpisode(ep: EpisodeEntity, wifiOnly: Boolean, title: String) {
        if (ep.downloadState == EpisodeEntity.DL_RUNNING || ep.isDownloaded) return
        val file = episodeFile(ep.id)
        file.delete()
        val req = DownloadManager.Request(Uri.parse(ep.audioUrl))
            .setTitle(ep.title)
            .setDescription(title)
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedOverMetered(!wifiOnly)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .addRequestHeader("User-Agent", "Ember-Audiobooks/1.0")
        val id = dm.enqueue(req)
        media.insertDownload(DownloadEntity(id, DownloadEntity.KIND_EPISODE, ep.id, file.absolutePath))
        media.setEpisodeDownload(ep.id, EpisodeEntity.DL_RUNNING, null)
    }

    suspend fun removeEpisode(ep: EpisodeEntity) {
        media.getDownloadFor(ep.id)?.let { d ->
            if (d.state == EpisodeEntity.DL_RUNNING) dm.remove(d.downloadId)
        }
        episodeFile(ep.id).delete()
        media.deleteDownloadsFor(ep.id)
        media.setEpisodeDownload(ep.id, EpisodeEntity.DL_NONE, null)
    }

    suspend fun downloadDriveFile(fileId: String, token: String, title: String, wifiOnly: Boolean) {
        val existing = media.getDownloadFor(fileId)
        if (existing != null && existing.state != EpisodeEntity.DL_FAILED && File(existing.path).exists()) return
        val file = driveFile(fileId)
        file.delete()
        val req = DownloadManager.Request(Uri.parse("https://www.googleapis.com/drive/v3/files/$fileId?alt=media"))
            .setTitle(title)
            .setDescription("Google Drive")
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedOverMetered(!wifiOnly)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .addRequestHeader("Authorization", "Bearer $token")
        val id = dm.enqueue(req)
        media.insertDownload(DownloadEntity(id, DownloadEntity.KIND_DRIVE, fileId, file.absolutePath))
    }

    suspend fun removeDriveFile(fileId: String) {
        media.getDownloadFor(fileId)?.let { d ->
            if (d.state == EpisodeEntity.DL_RUNNING) dm.remove(d.downloadId)
        }
        driveFile(fileId).delete()
        media.deleteDownloadsFor(fileId)
    }

    /** A finished local copy of a Drive file, if there is one. */
    suspend fun driveLocal(fileId: String): File? {
        val d = media.getDownloadFor(fileId) ?: return null
        if (d.state != EpisodeEntity.DL_DONE) return null
        return File(d.path).takeIf { it.exists() && it.length() > 0 }
    }

    /** Checks the download manager for any downloads that finished while we weren't looking. */
    suspend fun reconcile() {
        for (d in media.getRunningDownloads()) update(d.downloadId)
    }

    suspend fun update(downloadId: Long) {
        val d = media.getDownload(downloadId) ?: return
        val status = dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { c ->
            if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
        }
        val state = when (status) {
            DownloadManager.STATUS_SUCCESSFUL -> EpisodeEntity.DL_DONE
            DownloadManager.STATUS_FAILED, null -> EpisodeEntity.DL_FAILED
            else -> return
        }
        media.setDownloadState(downloadId, state)
        if (d.kind == DownloadEntity.KIND_EPISODE) {
            media.setEpisodeDownload(
                d.targetId,
                if (state == EpisodeEntity.DL_DONE) EpisodeEntity.DL_DONE else EpisodeEntity.DL_NONE,
                if (state == EpisodeEntity.DL_DONE) d.path else null,
            )
            // The downloaded file may carry chapter marks the stream check couldn't see (M4A).
            if (state == EpisodeEntity.DL_DONE && media.getEpisodeChapters(d.targetId).isEmpty()) {
                context.container.podcasts.loadChapters(d.targetId, force = true)
            }
        }
    }
}

class DownloadCompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0) return
        val pending = goAsync()
        val c = context.container
        c.appScope.launch {
            try {
                c.downloads.update(id)
            } finally {
                pending.finish()
            }
        }
    }
}
