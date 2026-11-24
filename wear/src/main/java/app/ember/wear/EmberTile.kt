package app.ember.wear

import android.net.Uri
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.tasks.await

/**
 * A tile (swipe from the watch face): what's playing, skip back, play/pause, skip forward,
 * and a 30-minute sleep timer, without opening the app.
 */
class EmberTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> = scope.future {
        val clicked = requestParams.currentState.lastClickableId
        if (clicked.isNotEmpty()) {
            val (cmd, arg) = when (clicked) {
                "sleep30" -> "sleep" to "30"
                "sleepoff" -> "sleep" to "0"
                else -> clicked to ""
            }
            send(cmd, arg)
            delay(900) // let the phone answer, so the tile shows the new state
        }
        build(readNow())
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RES_VERSION)
                .apply {
                    for ((id, res) in listOf(
                        "play" to R.drawable.tile_play,
                        "pause" to R.drawable.tile_pause,
                        "back" to R.drawable.tile_back,
                        "forward" to R.drawable.tile_forward,
                        "sleep" to R.drawable.tile_sleep,
                    )) {
                        addIdToImageMapping(
                            id,
                            ResourceBuilders.ImageResource.Builder()
                                .setAndroidResourceByResId(ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(res).build())
                                .build(),
                        )
                    }
                }
                .build(),
        )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun send(cmd: String, arg: String) {
        try {
            val nodes = Wearable.getNodeClient(this).connectedNodes.await()
            nodes.forEach { Wearable.getMessageClient(this).sendMessage(it.id, "/ember/$cmd", arg.toByteArray()).await() }
        } catch (e: Exception) {
        }
    }

    private suspend fun readNow(): NowPlaying? = try {
        val items = Wearable.getDataClient(this).getDataItems(Uri.Builder().scheme("wear").path("/ember/now").build()).await()
        try {
            items.firstOrNull()?.let { item ->
                val m = DataMapItem.fromDataItem(item).dataMap
                NowPlaying(
                    m.getBoolean("hasMedia"), m.getString("title") ?: "", m.getString("author") ?: "", m.getString("chapter") ?: "",
                    m.getBoolean("playing"), m.getLong("sleepEndsAt"), m.getBoolean("sleepChapter"),
                    m.getInt("back", 15), m.getInt("forward", 30), m.getLong("position"), m.getLong("duration"),
                )
            }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        null
    }

    private fun build(now: NowPlaying?): TileBuilders.Tile {
        val ember = 0xFFF2994A.toInt()
        val ink = 0xFFF5EDE6.toInt()
        val muted = 0xFFB7A99F.toInt()
        val col = LayoutElementBuilders.Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        if (now == null || !now.hasMedia) {
            col.addContent(text("Ember", 16f, ember, bold = true))
            col.addContent(text("Open Ember on your phone to start a book", 13f, muted, lines = 3))
        } else {
            col.addContent(text(now.title, 15f, ink, bold = true, lines = 2))
            if (now.chapter.isNotBlank()) col.addContent(text(now.chapter, 12f, muted))
            col.addContent(spacer(10f))
            col.addContent(
                LayoutElementBuilders.Row.Builder()
                    .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                    .addContent(button("back", "back", 44f, 0x33FFFFFF))
                    .addContent(spacer(8f, horizontal = true))
                    .addContent(button("toggle", if (now.playing) "pause" else "play", 58f, ember))
                    .addContent(spacer(8f, horizontal = true))
                    .addContent(button("forward", "forward", 44f, 0x33FFFFFF))
                    .build(),
            )
            col.addContent(spacer(10f))
            val sleeping = now.sleepEndsAt > System.currentTimeMillis() || now.sleepChapter
            val label = when {
                now.sleepChapter -> "Sleep: end of chapter"
                sleeping -> "Sleep: ${((now.sleepEndsAt - System.currentTimeMillis()) / 60_000 + 1)} min · off"
                else -> "Sleep 30 min"
            }
            col.addContent(pill(if (sleeping) "sleepoff" else "sleep30", label, if (sleeping) ember else muted))
        }
        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(col.build())
            .build()
        return TileBuilders.Tile.Builder()
            .setResourcesVersion(RES_VERSION)
            .setFreshnessIntervalMillis(60_000)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
            .build()
    }

    private fun clickable(id: String) = ModifiersBuilders.Clickable.Builder()
        .setId(id)
        .setOnClick(ActionBuilders.LoadAction.Builder().build())
        .build()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, lines: Int = 1) =
        LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setMaxLines(lines)
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build(),
            )
            .build()

    private fun spacer(size: Float, horizontal: Boolean = false) =
        LayoutElementBuilders.Spacer.Builder().apply { if (horizontal) setWidth(dp(size)) else setHeight(dp(size)) }.build()

    private fun button(id: String, icon: String, size: Float, color: Int) =
        LayoutElementBuilders.Box.Builder()
            .setWidth(dp(size))
            .setHeight(dp(size))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable(id))
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(color))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(size / 2)).build())
                            .build(),
                    )
                    .build(),
            )
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(icon)
                    .setWidth(dp(size * 0.5f))
                    .setHeight(dp(size * 0.5f))
                    .build(),
            )
            .build()

    private fun pill(id: String, label: String, color: Int) =
        LayoutElementBuilders.Box.Builder()
            .setWidth(wrap())
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(clickable(id))
                    .setPadding(ModifiersBuilders.Padding.Builder().setStart(dp(12f)).setEnd(dp(12f)).setTop(dp(6f)).setBottom(dp(6f)).build())
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(0x26FFFFFF))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(16f)).build())
                            .build(),
                    )
                    .build(),
            )
            .addContent(text(label, 12f, color))
            .build()

    companion object {
        private const val RES_VERSION = "1"
    }
}

/** Refreshes the tile whenever the phone reports a change (play, pause, chapter, sleep timer). */
class TileRefresh : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        TileService.getUpdater(this).requestUpdate(EmberTileService::class.java)
    }
}
