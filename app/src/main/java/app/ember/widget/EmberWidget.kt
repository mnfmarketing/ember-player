package app.ember.widget

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.ember.R
import app.ember.container
import app.ember.data.CoverStore
import app.ember.playback.PlaybackService
import app.ember.playback.PlaybackStatus
import app.ember.ui.MainActivity
import app.ember.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await

/** Bumped whenever the widget should redraw (play/pause, skips, progress). */
object WidgetBus {
    val version = MutableStateFlow(0L)
    fun poke() {
        version.value = version.value + 1
    }
}

private data class WidgetData(
    val hasItem: Boolean = false,
    val playable: Boolean = false,
    val title: String = "Ember",
    val byline: String = "",
    val detail: String = "Add your audiobooks to start",
    val progress: Float = 0f,
    val cover: Bitmap? = null,
    val backdrop: Bitmap? = null,
    val accent: Color = Color(0xFFF2A65A),
    val skipBack: Int = 15,
    val skipForward: Int = 30,
)

private val White = Color(0xFFFFF7F0)
private val Muted = Color(0xCCF3DCCB)

private fun deep(color: Color, lightness: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(color.toArgb(), hsl)
    hsl[1] = (hsl[1] * 0.8f).coerceAtMost(0.65f)
    hsl[2] = lightness
    return Color(ColorUtils.HSLToColor(hsl))
}

private fun onAccent(c: Color): Color =
    if (ColorUtils.calculateLuminance(c.toArgb()) > 0.45) Color(0xFF1A1210) else Color(0xFFFFF7F0)

/** Soft-focus copy of the cover for the background (Glance can't blur, so we shrink and re-enlarge). */
private fun soften(src: Bitmap): Bitmap = try {
    val tiny = Bitmap.createScaledBitmap(src, 16, 16, true)
    Bitmap.createScaledBitmap(tiny, 240, 240, true)
} catch (e: Exception) {
    src
}

private suspend fun loadData(context: Context): WidgetData {
    val c = context.container
    val s = c.settings.flow.first()
    val lastId = s.lastBookId ?: return WidgetData()

    if (lastId.startsWith(PlaybackService.EPISODE_PREFIX)) {
        val ep = c.media.dao().getEpisode(lastId.removePrefix(PlaybackService.EPISODE_PREFIX)) ?: return WidgetData()
        val show = c.media.dao().getPodcast(ep.podcastId)
        val cover = show?.artworkPath?.let { CoverStore.decodeFile(it, 360) }
        return WidgetData(
            hasItem = true,
            playable = true,
            title = ep.title,
            byline = show?.title ?: "Podcast",
            detail = if (ep.durationMs > 0) "${formatDuration(ep.remainingMs)} left" else "Podcast episode",
            progress = ep.progress,
            cover = cover,
            backdrop = cover?.let { soften(it) },
            accent = show?.color?.let { Color(it) } ?: Color(0xFFF2A65A),
            skipBack = s.skipBackSec,
            skipForward = s.skipForwardSec,
        )
    }

    val dao = c.db.dao()
    val book = dao.getBook(lastId) ?: return WidgetData()
    val chapters = dao.getChapters(book.id)
    val chIdx = chapters.indexOfLast { it.startMs <= book.positionMs }
    val chapter = chapters.getOrNull(chIdx)
    val detailParts = mutableListOf<String>()
    if (chapter != null && chapters.size > 1) detailParts += chapter.title
    if (book.isExternal) {
        detailParts += "${(book.progress * 100).toInt()}% · tap to open"
    } else if (book.durationMs > 0) {
        detailParts += "${formatDuration(book.remainingMs)} left"
    }
    val byline = listOfNotNull(
        book.author.takeIf { it.isNotBlank() }?.let { "By $it" },
        book.narrator.takeIf { it.isNotBlank() }?.let { "Read by $it" },
    ).joinToString(" · ")
    val cover = book.coverPath?.let { CoverStore.decodeFile(it, 360) }
    return WidgetData(
        hasItem = true,
        playable = !book.isExternal,
        title = book.title,
        byline = byline,
        detail = detailParts.joinToString(" · "),
        progress = book.progress,
        cover = cover,
        backdrop = cover?.let { soften(it) },
        accent = book.coverColor?.let { Color(it) } ?: Color(0xFFF2A65A),
        skipBack = s.skipBackSec,
        skipForward = s.skipForwardSec,
    )
}

class EmberWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(DpSize(220.dp, 70.dp), DpSize(260.dp, 140.dp), DpSize(320.dp, 170.dp)),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val initial = loadData(context)
        provideContent {
            // Redraw whenever playback changes, while the widget is on screen.
            val version by WidgetBus.version.collectAsState()
            val playing by PlaybackStatus.isPlaying.collectAsState()
            val data by produceState(initial, version, playing) { value = loadData(context) }
            Content(data, playing)
        }
    }

    @Composable
    private fun Content(d: WidgetData, playing: Boolean) {
        val size = LocalSize.current
        val tall = size.height >= 130.dp
        Box(
            GlanceModifier
                .fillMaxSize()
                .cornerRadius(28.dp)
                .background(ColorProvider(deep(d.accent, 0.12f)))
                // Tapping anywhere but the buttons opens straight to the player.
                .clickable(
                    androidx.glance.appwidget.action.actionStartActivity(
                        android.content.Intent(androidx.glance.LocalContext.current, MainActivity::class.java)
                            .putExtra("ember_route", if (d.playable) "player" else "home")
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    ),
                ),
        ) {
            if (d.backdrop != null) {
                Image(
                    ImageProvider(d.backdrop),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize(),
                )
            }
            // Darken the artwork so text stays readable.
            Box(GlanceModifier.fillMaxSize().background(ColorProvider(deep(d.accent, 0.10f).copy(alpha = 0.78f)))) {}
            if (tall) Large(d, playing) else Compact(d, playing)
        }
    }

    @Composable
    private fun CoverImage(d: WidgetData, sizeDp: Int) {
        if (d.cover != null) {
            Image(
                ImageProvider(d.cover),
                contentDescription = d.title,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.size(sizeDp.dp).cornerRadius(14.dp),
            )
        } else {
            Box(
                GlanceModifier.size(sizeDp.dp).cornerRadius(14.dp).background(ColorProvider(d.accent)),
                contentAlignment = Alignment.Center,
            ) {
                Image(ImageProvider(R.drawable.ic_w_flame), null, GlanceModifier.size((sizeDp / 3).dp))
            }
        }
    }

    @Composable
    private fun Large(d: WidgetData, playing: Boolean) {
        Column(GlanceModifier.fillMaxSize().padding(16.dp)) {
            Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(d, 92)
                Spacer(GlanceModifier.width(14.dp))
                Column(GlanceModifier.defaultWeight()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(ImageProvider(R.drawable.ic_w_flame), null, GlanceModifier.size(10.dp, 14.dp))
                        Spacer(GlanceModifier.width(5.dp))
                        Text(
                            "Ember",
                            style = TextStyle(color = ColorProvider(d.accent), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                    Spacer(GlanceModifier.height(2.dp))
                    Text(
                        d.title,
                        maxLines = 2,
                        style = TextStyle(color = ColorProvider(White), fontSize = 19.sp, fontFamily = FontFamily.Serif),
                    )
                    if (d.byline.isNotBlank()) {
                        Text(d.byline, maxLines = 1, style = TextStyle(color = ColorProvider(Muted), fontSize = 13.sp))
                    }
                    if (d.detail.isNotBlank()) {
                        Text(d.detail, maxLines = 1, style = TextStyle(color = ColorProvider(Muted), fontSize = 13.sp))
                    }
                }
            }
            if (d.hasItem) {
                Spacer(GlanceModifier.height(10.dp))
                LinearProgressIndicator(
                    progress = d.progress,
                    modifier = GlanceModifier.fillMaxWidth().height(4.dp).cornerRadius(2.dp),
                    color = ColorProvider(d.accent),
                    backgroundColor = ColorProvider(Color.White.copy(alpha = 0.18f)),
                )
            }
            if (d.playable) {
                Spacer(GlanceModifier.height(8.dp))
                Row(GlanceModifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
                    SkipButton(R.drawable.ic_w_back, d.skipBack, actionRunCallback<SkipBackAction>(), 44)
                    Spacer(GlanceModifier.width(26.dp))
                    PlayButton(d, playing, 56)
                    Spacer(GlanceModifier.width(26.dp))
                    SkipButton(R.drawable.ic_w_forward, d.skipForward, actionRunCallback<SkipForwardAction>(), 44)
                }
            }
        }
    }

    @Composable
    private fun Compact(d: WidgetData, playing: Boolean) {
        Row(GlanceModifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(d, 56)
            Spacer(GlanceModifier.width(12.dp))
            Column(GlanceModifier.defaultWeight()) {
                Text(d.title, maxLines = 1, style = TextStyle(color = ColorProvider(White), fontSize = 15.sp, fontFamily = FontFamily.Serif))
                Text(d.detail.ifBlank { d.byline }, maxLines = 1, style = TextStyle(color = ColorProvider(Muted), fontSize = 13.sp))
                if (d.hasItem) {
                    Spacer(GlanceModifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = d.progress,
                        modifier = GlanceModifier.fillMaxWidth().height(3.dp).cornerRadius(2.dp),
                        color = ColorProvider(d.accent),
                        backgroundColor = ColorProvider(Color.White.copy(alpha = 0.18f)),
                    )
                }
            }
            if (d.playable) {
                Spacer(GlanceModifier.width(8.dp))
                SkipButton(R.drawable.ic_w_back, d.skipBack, actionRunCallback<SkipBackAction>(), 38)
                Spacer(GlanceModifier.width(6.dp))
                PlayButton(d, playing, 46)
                Spacer(GlanceModifier.width(6.dp))
                SkipButton(R.drawable.ic_w_forward, d.skipForward, actionRunCallback<SkipForwardAction>(), 38)
            }
        }
    }

    @Composable
    private fun PlayButton(d: WidgetData, playing: Boolean, sizeDp: Int) {
        Box(
            GlanceModifier
                .size(sizeDp.dp)
                .cornerRadius((sizeDp / 2).dp)
                .background(ColorProvider(d.accent))
                .clickable(actionRunCallback<TogglePlayAction>()),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                ImageProvider(if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play),
                contentDescription = if (playing) "Pause" else "Play",
                colorFilter = ColorFilter.tint(ColorProvider(onAccent(d.accent))),
                modifier = GlanceModifier.size((sizeDp * 0.5f).dp),
            )
        }
    }

    @Composable
    private fun SkipButton(icon: Int, seconds: Int, action: androidx.glance.action.Action, sizeDp: Int) {
        Box(
            GlanceModifier.size(sizeDp.dp).cornerRadius((sizeDp / 2).dp).clickable(action),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(icon), contentDescription = null, modifier = GlanceModifier.size((sizeDp * 0.82f).dp))
            Box(GlanceModifier.padding(top = (sizeDp * 0.1f).dp), contentAlignment = Alignment.Center) {
                Text("$seconds", style = TextStyle(color = ColorProvider(White), fontSize = (sizeDp * 0.2f).sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

/** Connects to the player, runs [block], and refreshes the widget. */
private suspend fun withController(context: Context, block: suspend (MediaController) -> Unit) {
    // The player only accepts commands on the main thread. Widget taps arrive on a
    // background thread, which is why the buttons silently did nothing before.
    withContext(Dispatchers.Main) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controller = MediaController.Builder(context, token).buildAsync().await()
        try {
            block(controller)
            delay(400)
        } finally {
            controller.release()
        }
    }
    WidgetBus.poke()
    EmberWidget().updateAll(context)
}

private suspend fun ensureLoaded(context: Context, controller: MediaController): Boolean {
    if (controller.mediaItemCount > 0) return true
    val id = context.container.settings.flow.first().lastBookId ?: return false
    val mediaId = if (id.startsWith(PlaybackService.EPISODE_PREFIX)) id else "book:$id"
    controller.setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
    controller.prepare()
    return true
}

class TogglePlayAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        // Flip the icon right away so the tap feels instant.
        val wasPlaying = PlaybackStatus.isPlaying.value
        PlaybackStatus.isPlaying.value = !wasPlaying
        withController(context) { c ->
            PlaybackStatus.isPlaying.value = !c.isPlaying
            if (c.isPlaying) {
                c.pause()
            } else if (ensureLoaded(context, c)) {
                c.play()
            }
        }
    }
}

class SkipBackAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { c -> if (ensureLoaded(context, c)) c.seekBack() }
    }
}

class SkipForwardAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        withController(context) { c -> if (ensureLoaded(context, c)) c.seekForward() }
    }
}

class EmberWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = EmberWidget()
}
