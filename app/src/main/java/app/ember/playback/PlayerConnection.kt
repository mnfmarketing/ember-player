package app.ember.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** UI-side handle to the playback service. */
class PlayerConnection(private val context: Context) {

    data class State(
        val connected: Boolean = false,
        val bookId: String? = null,
        val isPlaying: Boolean = false,
        val trackIndex: Int = 0,
        val positionInTrack: Long = 0,
        val speed: Float = 1f,
        val buffering: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var poller: Job? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync()
        }
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            try {
                val c = f.get()
                controller = c
                c.addListener(listener)
                sync()
                val queued = pending.toList()
                pending.clear()
                queued.forEach { it(c) }
                startPolling()
            } catch (e: Exception) {
                future = null
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        poller?.cancel()
        poller = null
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        _state.value = State()
    }

    private fun startPolling() {
        poller?.cancel()
        poller = scope.launch {
            while (isActive) {
                delay(if (controller?.isPlaying == true) 250 else 1000)
                sync()
            }
        }
    }

    private fun sync() {
        val c = controller ?: return
        val id = c.currentMediaItem?.mediaId?.let { PlaybackService.bookIdFromTrack(it) }
        _state.value = State(
            connected = true,
            bookId = id,
            isPlaying = c.isPlaying,
            trackIndex = c.currentMediaItemIndex,
            positionInTrack = c.currentPosition.coerceAtLeast(0),
            speed = c.playbackParameters.speed,
            buffering = c.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) {
            block(c)
            sync()
        } else {
            pending += block
            connect()
        }
    }

    fun playBook(bookId: String) = withController { c ->
        val loaded = c.currentMediaItem?.mediaId?.let { PlaybackService.bookIdFromTrack(it) }
        if (loaded == bookId && c.mediaItemCount > 0) {
            if (c.playbackState == Player.STATE_ENDED) c.seekTo(0, 0)
            c.play()
        } else {
            c.setMediaItem(MediaItem.Builder().setMediaId(mediaIdFor(bookId)).build())
            c.prepare()
            c.play()
        }
    }

    /** Book ids load as "book:<id>"; podcast episodes are already "episode:<id>". */
    private fun mediaIdFor(id: String) = if (id.startsWith(PlaybackService.EPISODE_PREFIX)) id else "book:$id"

    /** After an error the player sits idle; get it ready again before playing. */
    fun play() = withController {
        if (it.playbackState == Player.STATE_IDLE && it.mediaItemCount > 0) it.prepare()
        it.play()
    }
    fun pause() = withController { it.pause() }
    fun seekBack() = withController { it.seekBack() }
    fun seekForward() = withController { it.seekForward() }
    fun seekTo(trackIndex: Int, positionMs: Long) = withController { it.seekTo(trackIndex, positionMs.coerceAtLeast(0)) }
    fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed) }

    /** Loads a book and starts playing at its saved position (set before calling). */
    fun reloadBook(bookId: String) = withController { c ->
        c.setMediaItem(MediaItem.Builder().setMediaId(mediaIdFor(bookId)).build())
        c.prepare()
        c.play()
    }
}
