package app.ember.wear

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** What the phone says is playing. */
data class NowPlaying(
    val hasMedia: Boolean,
    val title: String,
    val author: String,
    val chapter: String,
    val playing: Boolean,
    val sleepEndsAt: Long,
    val sleepChapter: Boolean,
    val backSec: Int,
    val forwardSec: Int,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * The watch's side of the link to Ember on the phone: reads what's playing (and the cover)
 * from the Wear OS data layer, and sends button presses as messages.
 */
class PhoneLink(private val context: Context) : DataClient.OnDataChangedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now
    private val _cover = MutableStateFlow<ImageBitmap?>(null)
    val cover: StateFlow<ImageBitmap?> = _cover
    /** Null until we've checked; false when the phone can't be reached. */
    private val _phone = MutableStateFlow<Boolean?>(null)
    val phone: StateFlow<Boolean?> = _phone

    /** For screenshots without a phone. */
    var demo = false

    fun start() {
        if (demo) {
            _now.value = NowPlaying(true, "Pride and Prejudice", "Jane Austen", "Chapter 3", true, System.currentTimeMillis() + 23 * 60_000L, false, 15, 30, 1_800_000, 41_000_000)
            _phone.value = true
            return
        }
        Wearable.getDataClient(context).addListener(this)
        scope.launch {
            load()
            send("status")
        }
    }

    fun stop() {
        if (!demo) Wearable.getDataClient(context).removeListener(this)
    }

    /** Sends a button press to the phone ("toggle", "back", "forward", "bookmark", "sleep" with minutes, "chapter" with 1 or -1). */
    fun send(cmd: String, arg: String = "") {
        if (demo) return
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(context).connectedNodes.await()
                _phone.value = nodes.isNotEmpty()
                nodes.forEach { node ->
                    Wearable.getMessageClient(context).sendMessage(node.id, "/ember/$cmd", arg.toByteArray()).await()
                }
            } catch (e: Exception) {
                _phone.value = false
            }
        }
    }

    private suspend fun load() {
        try {
            val client = Wearable.getDataClient(context)
            for (path in listOf("/ember/now", "/ember/cover")) {
                val items = client.getDataItems(Uri.Builder().scheme("wear").path(path).build()).await()
                try {
                    items.forEach { read(it) }
                } finally {
                    items.release()
                }
            }
        } catch (e: Exception) {
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { e -> if (e.type == DataEvent.TYPE_CHANGED) read(e.dataItem) }
    }

    private fun read(item: DataItem) {
        val map = DataMapItem.fromDataItem(item).dataMap
        when (item.uri.path) {
            "/ember/now" -> _now.value = NowPlaying(
                hasMedia = map.getBoolean("hasMedia"),
                title = map.getString("title") ?: "",
                author = map.getString("author") ?: "",
                chapter = map.getString("chapter") ?: "",
                playing = map.getBoolean("playing"),
                sleepEndsAt = map.getLong("sleepEndsAt"),
                sleepChapter = map.getBoolean("sleepChapter"),
                backSec = map.getInt("back", 15),
                forwardSec = map.getInt("forward", 30),
                positionMs = map.getLong("position"),
                durationMs = map.getLong("duration"),
            )
            "/ember/cover" -> {
                val asset = map.getAsset("cover")
                if (asset == null) _cover.value = null else scope.launch { _cover.value = decode(asset) }
            }
        }
    }

    private suspend fun decode(asset: Asset): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            Wearable.getDataClient(context).getFdForAsset(asset).await().inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}
