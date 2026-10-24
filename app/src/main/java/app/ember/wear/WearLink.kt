package app.ember.wear

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import app.ember.container
import app.ember.data.CoverStore
import app.ember.playback.PlaybackService
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The phone's side of the Galaxy Watch controls.
 *
 * The watch app sends short messages ("/ember/toggle", "/ember/sleep" with "30", ...);
 * the phone sends back what's playing as a data item ("/ember/now") plus the cover ("/ember/cover").
 * Both apps share Ember's package name and signing key, which is what lets them talk.
 */
object WearLink {

    const val CMD_BOOKMARK = "ember.bookmark"
    const val CMD_RESUME = "ember.resume"
    const val CMD_SLEEP = "ember.sleep"
    const val CMD_CHAPTER = "ember.chapter"

    data class Status(
        val hasMedia: Boolean,
        val title: String,
        val author: String,
        val chapter: String,
        val playing: Boolean,
        val sleepEndsAt: Long,
        val sleepChapter: Boolean,
        val backSec: Int,
        val forwardSec: Int,
        val coverPath: String?,
        val positionMs: Long,
        val durationMs: Long,
    )

    private var lastKey = ""
    private var lastCover: String? = "\u0000"
    private var nodesCheckedAt = 0L
    private var hasWatch = false
    private var unavailableUntil = 0L

    /** Sends what's playing to a connected watch (skipped when nothing changed or no watch is connected). */
    suspend fun publish(context: Context, s: Status, force: Boolean = false) = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            // No Wear OS on this phone: don't keep asking (the watch app's "status" call still forces a check).
            if (!force && now < unavailableUntil) return@withContext
            if (force || now - nodesCheckedAt > 60_000) {
                nodesCheckedAt = now
                hasWatch = Wearable.getNodeClient(context).connectedNodes.await().isNotEmpty()
            }
            if (!hasWatch) return@withContext
            // Position only matters to the second when you glance; send it at most every 30 s.
            val key = listOf(s.hasMedia, s.title, s.author, s.chapter, s.playing, s.sleepEndsAt, s.sleepChapter, s.backSec, s.forwardSec, s.durationMs, s.positionMs / 30_000).joinToString("|")
            if (!force && key == lastKey) return@withContext
            lastKey = key
            val data = Wearable.getDataClient(context)
            val req = PutDataMapRequest.create("/ember/now").apply {
                dataMap.putBoolean("hasMedia", s.hasMedia)
                dataMap.putString("title", s.title)
                dataMap.putString("author", s.author)
                dataMap.putString("chapter", s.chapter)
                dataMap.putBoolean("playing", s.playing)
                dataMap.putLong("sleepEndsAt", s.sleepEndsAt)
                dataMap.putBoolean("sleepChapter", s.sleepChapter)
                dataMap.putInt("back", s.backSec)
                dataMap.putInt("forward", s.forwardSec)
                dataMap.putLong("position", s.positionMs)
                dataMap.putLong("duration", s.durationMs)
                dataMap.putLong("at", now)
            }.asPutDataRequest().setUrgent()
            data.putDataItem(req).await()
            if (force || s.coverPath != lastCover) {
                lastCover = s.coverPath
                val bytes = s.coverPath?.let { path ->
                    CoverStore.decodeFile(path, 240)?.let { bmp ->
                        ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
                    }
                }
                val cover = PutDataMapRequest.create("/ember/cover").apply {
                    dataMap.putString("path", s.coverPath ?: "")
                    if (bytes != null) dataMap.putAsset("cover", Asset.createFromBytes(bytes))
                }.asPutDataRequest().setUrgent()
                data.putDataItem(cover).await()
            }
        } catch (e: Exception) {
            // No watch, or Wear OS isn't set up on this phone: nothing to do (check again in half an hour).
            hasWatch = false
            unavailableUntil = System.currentTimeMillis() + 30 * 60_000L
        }
    }

    /** Runs a command from the watch against the player (starting it if needed). */
    suspend fun run(context: Context, cmd: String, arg: String) = withContext(Dispatchers.Main) {
        if (cmd == "status") {
            PlaybackService.requestWearStatus()
            if (!PlaybackService.running) {
                // Nothing loaded yet: tell the watch so it can show "start a book on your phone".
                val last = context.container.settings.current().lastBookId
                val book = last?.takeIf { !it.startsWith(PlaybackService.EPISODE_PREFIX) }?.let { context.container.db.dao().getBook(it) }
                lastKey = ""
                publish(
                    context,
                    Status(false, book?.title ?: "", book?.author ?: "", "", false, 0, false, 0, 0, book?.coverPath, 0, 0),
                    force = true,
                )
            }
            return@withContext
        }
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = try {
            MediaController.Builder(context, token).buildAsync().await()
        } catch (e: Exception) {
            return@withContext
        }
        try {
            when (cmd) {
                "toggle" -> when {
                    controller.mediaItemCount == 0 -> controller.sendCustomCommand(SessionCommand(CMD_RESUME, Bundle.EMPTY), Bundle.EMPTY).await()
                    controller.playWhenReady -> controller.pause()
                    else -> {
                        if (controller.playbackState == androidx.media3.common.Player.STATE_IDLE) controller.prepare()
                        controller.play()
                    }
                }
                "back" -> controller.seekBack()
                "forward" -> controller.seekForward()
                "bookmark" -> controller.sendCustomCommand(SessionCommand(CMD_BOOKMARK, Bundle.EMPTY), Bundle.EMPTY).await()
                "chapter" -> controller.sendCustomCommand(
                    SessionCommand(CMD_CHAPTER, Bundle.EMPTY),
                    Bundle().apply { putInt("dir", arg.toIntOrNull() ?: 1) },
                ).await()
                "sleep" -> controller.sendCustomCommand(
                    SessionCommand(CMD_SLEEP, Bundle.EMPTY),
                    Bundle().apply { putInt("minutes", arg.toIntOrNull() ?: 0) },
                ).await()
            }
            // Give the player a moment before letting go of it.
            delay(500)
        } catch (e: Exception) {
        } finally {
            controller.release()
        }
    }
}

/** Receives the watch's button presses. */
class WearCommandService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (!event.path.startsWith("/ember/")) return
        val cmd = event.path.removePrefix("/ember/")
        val arg = String(event.data ?: ByteArray(0))
        val app = applicationContext
        app.container.appScope.launch { WearLink.run(app, cmd, arg) }
    }
}
