@file:OptIn(ExperimentalMaterial3Api::class)

package app.ember.ui.screens

import kotlinx.coroutines.launch

import androidx.compose.ui.input.pointer.positionChange

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Replay30
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ember.data.BookmarkEntity
import app.ember.playback.SleepTimer
import app.ember.ui.MainViewModel
import app.ember.ui.NowPlaying
import app.ember.ui.components.BookCover
import app.ember.ui.components.CoverBackdrop
import app.ember.ui.components.GlassPanel
import app.ember.ui.components.ThinProgress
import app.ember.ui.components.bookAccent
import app.ember.ui.theme.EmberColors
import app.ember.ui.theme.onColorFor
import app.ember.util.formatClock
import app.ember.util.formatDuration
import app.ember.util.formatSpeed

private val GlassText: Color get() = EmberColors.GlassText
private val GlassMuted: Color get() = EmberColors.GlassMuted

/**
 * The immersive "glassy" player.
 * [embedded] = shown as the right-hand pane on the unfolded screen.
 */
@Composable
fun PlayerScreen(
    vm: MainViewModel,
    embedded: Boolean,
    onCollapse: (() -> Unit)?,
    onOpenBook: (String) -> Unit,
    focused: Boolean = false,
    onToggleFocus: (() -> Unit)? = null,
    onMinimize: (() -> Unit)? = null,
    /** Opens another screen by route (used for quote cards). */
    onOpenRoute: (String) -> Unit = {},
) {
    val np by vm.nowPlaying.collectAsState()
    val current = np ?: run {
        Box(Modifier.fillMaxSize().background(EmberColors.Background))
        return
    }
    val bookmarks by remember(current.book.id) { vm.observeBookmarks(current.book.id) }.collectAsState(initial = emptyList())
    val settings by vm.settings.collectAsState()
    val sleep by vm.sleep.collectAsState()

    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showAddBookmark by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var showSound by remember { mutableStateOf(false) }
    var showCast by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val onLog: () -> Unit = { showLog = true }
    val onPdf: (() -> Unit)? = if (current.book.hasPdf) ({ onOpenRoute("pdf/${current.book.id}") }) else null
    var clipDraft by remember { mutableStateOf<app.ember.data.ClipEntity?>(null) }
    var editingClip by remember { mutableStateOf<app.ember.data.ClipEntity?>(null) }
    val castDevice by vm.castDevice.collectAsState()
    val clipsHere by remember(current.book.id) { vm.observeClipsFor(current.book.id) }.collectAsState(initial = emptyList())
    val saved = SavedExtras(
        clips = clipsHere,
        onPlayClip = vm::playClip,
        onClipCard = { onOpenRoute("card/clip/$it") },
        onEditClip = { editingClip = it },
        onDeleteClip = vm::deleteClip,
        onBookmarkCard = if (current.isEpisode) null else { id -> onOpenRoute("card/bookmark/$id") },
    )
    val onClip: () -> Unit = { clipDraft = vm.clipDraft() }

    val onChapter: (Long) -> Unit = { start ->
        if (current.loaded) vm.seekGlobal(start) else vm.playFrom(current.book.id, start)
    }

    Box(Modifier.fillMaxSize().then(if (onCollapse != null && !embedded) Modifier.swipeDownToClose(onCollapse) else Modifier)) {
        CoverBackdrop(current.book, Modifier.fillMaxSize())
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            val wide = maxWidth >= 700.dp
            val availHeight = maxHeight.value
            if (embedded && maxWidth < 700.dp) {
                // Slim column beside the library on the unfolded screen.
                SidePanePlayer(
                    np = current,
                    vm = vm,
                    bookmarks = bookmarks,
                    skipBack = settings.skipBackSec,
                    skipForward = settings.skipForwardSec,
                    sleep = sleep,
                    castDevice = castDevice,
                    showList = false,
                    onMinimize = onMinimize,
                    onExpand = onToggleFocus,
                    onOpenBook = onOpenBook,
                    onCast = { showCast = true },
                    onSpeed = { showSpeed = true },
                    onSleep = { showSleep = true },
                    onBookmark = { showAddBookmark = true },
                    onSound = { showSound = true },
                    onClip = onClip,
                    onChapters = { showList = true },
                    onChapter = onChapter,
                    bookTime = settings.bookTimeLeft,
                    onToggleTime = { vm.setBookTimeLeft(!settings.bookTimeLeft) },
                    onLog = onLog,
                    onPdf = onPdf,
                    onLock = { vm.setLocked(true) },
                )
            } else if (wide) {
                Column(Modifier.fillMaxSize()) {
                    PlayerTopBar(current, embedded, onCollapse, onOpenBook, focused, onToggleFocus, castDevice, onSound = { showSound = true }, onMinimize = onMinimize, onLock = { vm.setLocked(true) }) { showCast = true }
                    SleepMarkBar(vm, current, Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            PlayerCore(
                                np = current,
                                vm = vm,
                                skipBack = settings.skipBackSec,
                                skipForward = settings.skipForwardSec,
                                sleep = sleep,
                                coverMax = 360,
                                showListButton = false,
                                onSpeed = { showSpeed = true },
                                onSleep = { showSleep = true },
                                onBookmark = { showAddBookmark = true },
                                onSound = { showSound = true },
                                onClip = onClip,
                                onList = {},
                                bookTime = settings.bookTimeLeft,
                                onToggleTime = { vm.setBookTimeLeft(!settings.bookTimeLeft) },
                                onLog = onLog,
                                onPdf = onPdf,
                            )
                        }
                        GlassPanel(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            padding = PaddingValues(8.dp),
                        ) {
                            ChaptersAndBookmarks(current, bookmarks, onChapter, onBookmark = onChapter, onDeleteBookmark = vm::deleteBookmark, saved = saved)
                        }
                    }
                }
            } else {
                // Everything fits on one screen: the cover takes whatever height is
                // left after the controls, so speed and sleep never need a scroll.
                // Very short screens (landscape on the cover screen) fall back to scrolling.
                val fits = availHeight >= 560f
                Column(
                    Modifier
                        .fillMaxSize()
                        .then(if (fits) Modifier else Modifier.verticalScroll(rememberScrollState())),
                ) {
                    PlayerTopBar(current, embedded, onCollapse, onOpenBook, focused, onToggleFocus, castDevice, onSound = { showSound = true }, onMinimize = onMinimize, onLock = { vm.setLocked(true) }) { showCast = true }
                    SleepMarkBar(vm, current, Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .then(if (fits) Modifier.weight(1f) else Modifier)
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PlayerCore(
                            np = current,
                            vm = vm,
                            skipBack = settings.skipBackSec,
                            skipForward = settings.skipForwardSec,
                            sleep = sleep,
                            coverMax = if (fits) (if (embedded) 460 else 360) else 180,
                            flexibleCover = fits,
                            showListButton = true,
                            onSpeed = { showSpeed = true },
                            onSleep = { showSleep = true },
                            onBookmark = { showAddBookmark = true },
                            onSound = { showSound = true },
                            onClip = onClip,
                            onList = { showList = true },
                            bookTime = settings.bookTimeLeft,
                            onToggleTime = { vm.setBookTimeLeft(!settings.bookTimeLeft) },
                            onLog = onLog,
                            onPdf = onPdf,
                            onCar = { onOpenRoute("car") },
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    if (showSound) SoundSheet(vm) { showSound = false }
    if (showLog) {
        ListenLogSheet(vm, current.book.id, current.book.title, current.chapters, current.book.durationMs) { showLog = false }
    }
    if (showCast) CastSheet { showCast = false }
    if (showSpeed) {
        SpeedSheet(current.speed, onDismiss = { showSpeed = false }, onSpeed = vm::setSpeed)
    }
    if (showSleep) {
        SleepSheet(
            sleep = sleep,
            onDismiss = { showSleep = false },
            onMinutes = {
                vm.sleepFor(it)
                showSleep = false
            },
            onEndOfChapter = {
                vm.sleepEndOfChapter()
                showSleep = false
            },
            onCancel = {
                vm.cancelSleep()
                showSleep = false
            },
        )
    }
    if (showAddBookmark) {
        AddBookmarkDialog(
            at = formatClock(current.position),
            onDismiss = { showAddBookmark = false },
            onSave = {
                vm.addBookmark(it)
                showAddBookmark = false
            },
        )
    }
    clipDraft?.let { d ->
        ClipSheet(vm, d, onDismiss = { clipDraft = null }, onMakeCard = { id -> onOpenRoute("card/clip/$id") })
    }
    editingClip?.let { clip ->
        EditClipNoteDialog(clip, onDismiss = { editingClip = null }) {
            vm.updateClipNote(clip.id, it)
            editingClip = null
        }
    }
    if (showList) {
        ModalBottomSheet(
            onDismissRequest = { showList = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = EmberColors.Surface,
        ) {
            Box(Modifier.fillMaxWidth().height(560.dp)) {
                ChaptersAndBookmarks(
                    current,
                    bookmarks,
                    onChapter = {
                        onChapter(it)
                        showList = false
                    },
                    onBookmark = {
                        onChapter(it)
                        showList = false
                    },
                    onDeleteBookmark = vm::deleteBookmark,
                    textColor = EmberColors.Text,
                    mutedColor = EmberColors.TextMuted,
                    saved = saved.copy(
                        onPlayClip = {
                            vm.playClip(it)
                            showList = false
                        },
                        onClipCard = {
                            showList = false
                            onOpenRoute("card/clip/$it")
                        },
                        onBookmarkCard = saved.onBookmarkCard?.let { open ->
                            { id: Long ->
                                showList = false
                                open(id)
                            }
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun PlayerTopBar(
    np: NowPlaying,
    embedded: Boolean,
    onCollapse: (() -> Unit)?,
    onOpenBook: (String) -> Unit,
    focused: Boolean,
    onToggleFocus: (() -> Unit)?,
    castDevice: String?,
    onSound: () -> Unit,
    onMinimize: (() -> Unit)? = null,
    onLock: () -> Unit = {},
    onCast: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onCollapse != null) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Close player", tint = GlassText, modifier = Modifier.size(30.dp))
            }
        } else if (onToggleFocus != null) {
            // Beside the library: shrink to the corner bar, or take over the screen.
            if (onMinimize != null && !focused) {
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Rounded.CloseFullscreen, "Minimize player", tint = GlassText)
                }
            }
            IconButton(onClick = onToggleFocus) {
                Icon(
                    if (focused) Icons.Rounded.CloseFullscreen else Icons.Rounded.OpenInFull,
                    if (focused) "Show library" else "Full-screen player",
                    tint = GlassText,
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    np.isEpisode -> np.book.author.ifBlank { "Podcast" }
                    else -> "Now playing"
                },
                style = MaterialTheme.typography.labelSmall,
                color = GlassMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (castDevice != null) {
                Text("Playing on $castDevice", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } else if (np.chapters.size > 1) {
                Text(
                    "Chapter ${np.chapterIndex + 1} of ${np.chapters.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = GlassText,
                )
            }
        }
        IconButton(onClick = onLock) {
            Icon(Icons.Rounded.Lock, "Lock the screen", tint = GlassText)
        }
        IconButton(onClick = onSound) {
            Icon(Icons.Rounded.GraphicEq, "Sound and equalizer", tint = GlassText)
        }
        IconButton(onClick = onCast) {
            Icon(
                if (castDevice != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                if (castDevice != null) "Casting to $castDevice" else "Cast",
                tint = if (castDevice != null) MaterialTheme.colorScheme.primary else GlassText,
            )
        }
        IconButton(onClick = { onOpenBook(np.book.id) }) {
            Icon(Icons.Rounded.Info, "Book details", tint = GlassText)
        }
    }
}

@Composable
private fun ColumnScope.PlayerCore(
    np: NowPlaying,
    vm: MainViewModel,
    skipBack: Int,
    skipForward: Int,
    sleep: SleepTimer.State,
    coverMax: Int,
    showListButton: Boolean,
    flexibleCover: Boolean = false,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onBookmark: () -> Unit,
    onList: () -> Unit,
    onSound: () -> Unit = {},
    onClip: () -> Unit = {},
    bookTime: Boolean = false,
    onToggleTime: () -> Unit = {},
    onLog: () -> Unit = {},
    onPdf: (() -> Unit)? = null,
    onCar: (() -> Unit)? = null,
) {
    val accent = bookAccent(np.book)
    val tick = rememberTick()
    if (flexibleCover) {
        // Shrinks to the space left over; never pushes the controls off screen.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            DoubleTapSkip(skipBack, skipForward, vm::skipBack, vm::skipForward) {
                BookCover(
                    np.book,
                    Modifier
                        .widthIn(max = coverMax.dp)
                        .fillMaxHeight()
                        .aspectRatio(1f, matchHeightConstraintsFirst = true)
                        .shadow(24.dp, RoundedCornerShape(14.dp)),
                    corner = 14.dp,
                    showBadge = false,
                )
            }
        }
    } else {
        DoubleTapSkip(skipBack, skipForward, vm::skipBack, vm::skipForward) {
            BookCover(
                np.book,
                Modifier
                    .widthIn(max = coverMax.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .shadow(24.dp, RoundedCornerShape(14.dp)),
                corner = 14.dp,
                showBadge = false,
            )
        }
    }
    Spacer(Modifier.height(if (flexibleCover) 8.dp else 16.dp))
    Text(
        np.book.displayTitle,
        style = MaterialTheme.typography.headlineMedium,
        color = GlassText,
        textAlign = TextAlign.Center,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
    val byline = listOf(np.book.author, np.book.narrator.takeIf { it.isNotBlank() }?.let { "read by $it" })
        .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ")
    if (byline.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(byline, style = MaterialTheme.typography.bodyMedium, color = GlassMuted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(10.dp))
    PlayerExtrasRow(onLog, onPdf, tint = GlassText, modifier = Modifier.fillMaxWidth(), onCar = onCar)
    Spacer(Modifier.height(12.dp))

    GlassPanel(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Column {
            Text(
                np.chapter?.title ?: np.book.displayTitle,
                style = MaterialTheme.typography.titleSmall,
                color = GlassText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ChapterSlider(np, onSeek = vm::seekGlobal, bookTime = bookTime, onToggleTime = onToggleTime)
            val left = (np.remaining / np.speed.coerceAtLeast(0.1f)).toLong()
            Text(
                "${(np.progress * 100).toInt()}% of ${if (np.isEpisode) "episode" else "book"} · ${formatDuration(left)} left" +
                    if (np.speed != 1f) " at ${formatSpeed(np.speed)}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = GlassMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { tick(); vm.previousChapter() }) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous chapter", tint = GlassText, modifier = Modifier.size(28.dp))
                }
                IconButton(onClick = { tick(); vm.skipBack() }, modifier = Modifier.size(56.dp)) {
                    SkipIcon(skipBack, forward = false, tint = GlassText)
                }
                Box(
                    Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(accent)
                        .clickable(onClick = vm::togglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    if (np.buffering && !np.isPlaying) {
                        CircularProgressIndicator(color = onColorFor(accent), strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                    } else {
                        AnimatedContent(np.isPlaying, label = "playpause") { playing ->
                            Icon(
                                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                if (playing) "Pause" else "Play",
                                tint = onColorFor(accent),
                                modifier = Modifier.size(42.dp),
                            )
                        }
                    }
                }
                IconButton(onClick = { tick(); vm.skipForward() }, modifier = Modifier.size(56.dp)) {
                    SkipIcon(skipForward, forward = true, tint = GlassText)
                }
                IconButton(onClick = { tick(); vm.nextChapter() }) {
                    Icon(Icons.Rounded.SkipNext, "Next chapter", tint = GlassText, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        PillButton(Icons.Rounded.Speed, formatSpeed(np.speed), onSpeed)
        val sleepLabel = when {
            sleep.stopAtPositionMs != null -> "End of ch."
            sleep.endsAt != null -> formatClock(sleep.remainingMs() ?: 0)
            else -> "Sleep"
        }
        PillButton(Icons.Rounded.Bedtime, sleepLabel, onSleep, highlighted = sleep.active)
        PillButton(Icons.Rounded.BookmarkAdd, "Bookmark", onBookmark)
        PillButton(Icons.Rounded.ContentCut, "Clip", onClip)
        if (showListButton) PillButton(Icons.Rounded.List, "Chapters", onList)
    }
}

/**
 * The scrubber, built to stop accidental jumps:
 *  - A tap does nothing (it only shows a hint); you have to drag.
 *  - Dragging moves from where the thumb is, not to where your finger lands.
 *  - Nothing changes until you let go, and a bubble shows where you'll land.
 *  - Slide your finger down while dragging for finer control (half, quarter, tenth speed).
 */
@Composable
internal fun ChapterSlider(np: NowPlaying, onSeek: (Long) -> Unit, bookTime: Boolean = false, onToggleTime: () -> Unit = {}) {
    val length = np.chapterLength.coerceAtLeast(1)
    val current = (np.chapterPosition.toFloat() / length).coerceIn(0f, 1f)
    val latest by androidx.compose.runtime.rememberUpdatedState(current)
    val seek by androidx.compose.runtime.rememberUpdatedState(onSeek)
    val start by androidx.compose.runtime.rememberUpdatedState(np.chapterStart)
    val len by androidx.compose.runtime.rememberUpdatedState(length)
    var drag by remember { mutableStateOf<Float?>(null) }
    var dragFrom by remember { mutableStateOf(0f) }
    var fine by remember { mutableStateOf(1f) }
    var hint by remember { mutableIntStateOf(0) }
    val tick = rememberTick()
    val fraction = drag ?: current
    val track = EmberColors.Wash.copy(alpha = 0.2f)

    LaunchedEffect(hint) {
        if (hint > 0) {
            kotlinx.coroutines.delay(2_800)
            hint = 0
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .semantics {
                progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress(label = "Move in this chapter") { v ->
                    seek(start + (v.coerceIn(0f, 1f) * len).toLong())
                    true
                }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    var lastX = down.position.x
                    var travelled = 0f
                    var moving = false
                    var value = latest
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val dx = change.position.x - lastX
                        lastX = change.position.x
                        travelled += dx
                        if (!moving && kotlin.math.abs(travelled) > slop) {
                            moving = true
                            dragFrom = latest
                            value = latest
                            drag = value
                        }
                        if (moving) {
                            val below = (change.position.y - size.height).coerceAtLeast(0f)
                            val factor = when {
                                below > 150.dp.toPx() -> 0.1f
                                below > 90.dp.toPx() -> 0.25f
                                below > 40.dp.toPx() -> 0.5f
                                else -> 1f
                            }
                            if (factor != fine) {
                                fine = factor
                                tick()
                            }
                            value = (value + dx / size.width * factor).coerceIn(0f, 1f)
                            drag = value
                            change.consume()
                        }
                    }
                    if (moving) {
                        seek(start + (value * len).toLong())
                    } else {
                        hint++
                    }
                    drag = null
                    fine = 1f
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val active = GlassText
        val dragging = drag != null
        Canvas(Modifier.fillMaxWidth().height(24.dp)) {
            val h = 6.dp.toPx()
            val y = size.height / 2
            val x = size.width * fraction
            drawRoundRect(track, topLeft = Offset(0f, y - h / 2), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
            drawRoundRect(active, topLeft = Offset(0f, y - h / 2), size = Size(x, h), cornerRadius = CornerRadius(h / 2))
            if (dragging) {
                // Where you started, so it's easy to go back.
                drawCircle(active.copy(alpha = 0.45f), radius = 4.dp.toPx(), center = Offset(size.width * dragFrom, y))
            }
            drawCircle(active, radius = (if (dragging) 11.dp else 8.dp).toPx(), center = Offset(x, y))
        }
    }

    val shownPos = (fraction * length).toLong()
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(formatClock(shownPos), style = MaterialTheme.typography.labelSmall, color = GlassMuted)
            // Tap to switch between time left in this chapter and in the whole book.
            val chapterLeft = length - shownPos
            val bookLeft = (np.remaining - (np.chapterPosition - shownPos)).coerceAtLeast(0)
            Text(
                if (bookTime) "${if (np.isEpisode) "Episode" else "Book"} -" + formatClock(bookLeft) else "-" + formatClock(chapterLeft),
                style = MaterialTheme.typography.labelSmall,
                color = if (bookTime) GlassText else GlassMuted,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClickLabel = if (bookTime) "Show time left in chapter" else "Show time left in book", onClick = onToggleTime)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        val d = drag
        val bubble = when {
            d != null -> {
                val delta = ((d - dragFrom) * length).toLong()
                val speed = when (fine) {
                    0.5f -> " · half speed"
                    0.25f -> " · quarter speed"
                    0.1f -> " · fine"
                    else -> ""
                }
                formatClock(shownPos) + "  (" + (if (delta < 0) "−" else "+") + formatClock(kotlin.math.abs(delta)) + ")" + speed
            }
            hint > 0 -> "Drag to move · slide down for finer control"
            else -> null
        }
        if (bubble != null) {
            Text(
                bubble,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, onClick: () -> Unit, highlighted: Boolean = false) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (highlighted) MaterialTheme.colorScheme.primary else EmberColors.Wash.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = if (highlighted) MaterialTheme.colorScheme.onPrimary else GlassText, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = GlassMuted, maxLines = 1)
    }
}

/** Circular-arrow skip icon with the number of seconds inside, for any skip amount. */
@Composable
fun SkipIcon(seconds: Int, forward: Boolean, tint: Color, size: androidx.compose.ui.unit.Dp = 36.dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Rounded.Replay,
            contentDescription = if (forward) "Forward $seconds seconds" else "Back $seconds seconds",
            tint = tint,
            modifier = Modifier
                .size(size)
                .graphicsLayer { if (forward) scaleX = -1f },
        )
        Text(
            "$seconds",
            color = tint,
            fontSize = (size.value * 0.27f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = (size.value * 0.12f).dp),
        )
    }
}

fun backIcon(sec: Int): ImageVector = when (sec) {
    5 -> Icons.Rounded.Replay5
    10 -> Icons.Rounded.Replay10
    30 -> Icons.Rounded.Replay30
    else -> Icons.Rounded.FastRewind
}

fun forwardIcon(sec: Int): ImageVector = when (sec) {
    5 -> Icons.Rounded.Forward5
    10 -> Icons.Rounded.Forward10
    30 -> Icons.Rounded.Forward30
    else -> Icons.Rounded.FastForward
}

@Composable
fun ChaptersAndBookmarks(
    np: NowPlaying,
    bookmarks: List<BookmarkEntity>,
    onChapter: (Long) -> Unit,
    onBookmark: (Long) -> Unit,
    onDeleteBookmark: (Long) -> Unit,
    textColor: Color = GlassText,
    mutedColor: Color = GlassMuted,
    compact: Boolean = false,
    saved: SavedExtras? = null,
) {
    val clips = saved?.clips.orEmpty()
    val savedCount = bookmarks.size + clips.size
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = Color.Transparent,
            contentColor = textColor,
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(if (compact) "Chapters" else "Chapters (${np.chapters.size})", maxLines = 1) })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(if (compact) "Saved" else "Saved ($savedCount)", maxLines = 1) })
        }
        if (tab == 0) {
            val listState = rememberLazyListState()
            LaunchedEffect(np.book.id) {
                if (np.chapterIndex > 2) listState.scrollToItem(np.chapterIndex - 2)
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                itemsIndexed(np.chapters, key = { _, ch -> ch.idx }) { i, ch ->
                    val isCurrent = i == np.chapterIndex
                    val done = ch.endMs <= np.position
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isCurrent) EmberColors.Wash.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { onChapter(ch.startMs) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else mutedColor,
                            modifier = Modifier.width(32.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                ch.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (done && !isCurrent) mutedColor else textColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (isCurrent) {
                                Spacer(Modifier.height(6.dp))
                                ThinProgress(np.chapterPosition.toFloat() / np.chapterLength, height = 2.dp)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(formatClock(ch.endMs - ch.startMs), style = MaterialTheme.typography.labelSmall, color = mutedColor)
                    }
                }
            }
        } else {
            if (bookmarks.isEmpty() && clips.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Tap Bookmark to mark a spot, or Clip to save the last few seconds with a note. Both show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = mutedColor,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                // Bookmarks and clips together, in the order they happen in the book.
                val entries: List<Pair<Long, Any>> = (bookmarks.map { it.positionMs to it } + clips.map { it.startMs to it }).sortedBy { it.first }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(entries, key = { (_, e) -> if (e is BookmarkEntity) "b${e.id}" else "c${(e as app.ember.data.ClipEntity).id}" }) { (_, e) ->
                        if (e is app.ember.data.ClipEntity) {
                            ClipRow(
                                e,
                                onPlay = { saved?.onPlayClip?.invoke(e) },
                                onCard = { saved?.onClipCard?.invoke(e.id) },
                                onEdit = saved?.onEditClip?.let { edit -> { edit(e) } },
                                onDelete = { saved?.onDeleteClip?.invoke(e.id) },
                                textColor = textColor,
                                mutedColor = mutedColor,
                            )
                            return@items
                        }
                        val bm = e as BookmarkEntity
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onBookmark(bm.positionMs) }
                                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    formatClock(bm.positionMs) + if (bm.chapterTitle.isNotBlank()) " · ${bm.chapterTitle}" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (bm.note.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(bm.note, style = MaterialTheme.typography.bodyMedium, color = textColor)
                                }
                            }
                            val card = saved?.onBookmarkCard
                            if (card != null && bm.note.isNotBlank()) {
                                IconButton(onClick = { card(bm.id) }) {
                                    Icon(Icons.Rounded.FormatQuote, "Make a quote card", tint = mutedColor)
                                }
                            }
                            IconButton(onClick = { onDeleteBookmark(bm.id) }) {
                                Icon(Icons.Rounded.Delete, "Delete bookmark", tint = mutedColor)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Clips and quote-card actions for the Saved tab. */
data class SavedExtras(
    val clips: List<app.ember.data.ClipEntity>,
    val onPlayClip: (app.ember.data.ClipEntity) -> Unit,
    val onClipCard: (Long) -> Unit,
    val onEditClip: ((app.ember.data.ClipEntity) -> Unit)?,
    val onDeleteClip: (Long) -> Unit,
    val onBookmarkCard: ((Long) -> Unit)?,
)

@Composable
fun SpeedSheet(speed: Float, onDismiss: () -> Unit, onSpeed: (Float) -> Unit) {
    var value by remember { mutableStateOf(speed) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EmberColors.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Playback speed", style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
            Spacer(Modifier.height(12.dp))
            Text(formatSpeed(value), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Slider(
                value = value,
                onValueChange = { value = Math.round(it * 20) / 20f },
                onValueChangeFinished = { onSpeed(value) },
                valueRange = 0.5f..3f,
                steps = 49,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                listOf(1f, 1.2f, 1.5f, 1.8f, 2f).forEach { s ->
                    FilterChip(
                        selected = value == s,
                        onClick = {
                            value = s
                            onSpeed(s)
                        },
                        label = { Text(formatSpeed(s)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
fun SleepSheet(
    sleep: SleepTimer.State,
    onDismiss: () -> Unit,
    onMinutes: (Int) -> Unit,
    onEndOfChapter: () -> Unit,
    onCancel: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EmberColors.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                "Sleep timer",
                style = MaterialTheme.typography.titleMedium,
                color = EmberColors.Text,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                if (sleep.active) {
                    "On. Shake your phone to start it over" + if (sleep.stopAtPositionMs != null) " (it moves to the end of the next chapter)." else "."
                } else {
                    "Shake your phone to start it over. Fell asleep anyway? Shake within 5 minutes of it stopping to keep listening."
                },
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextMuted,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                SheetRow(if (m < 60) "$m minutes" else if (m == 60) "1 hour" else "1 hour 30 minutes") { onMinutes(m) }
            }
            SheetRow("End of chapter", onClick = onEndOfChapter)
            if (sleep.active) {
                SheetRow("Turn off timer", color = MaterialTheme.colorScheme.primary, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, color: Color = EmberColors.Text, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
    )
}

@Composable
private fun AddBookmarkDialog(at: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmberColors.SurfaceHigh,
        title = { Text("Bookmark at $at") },
        text = {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("Add a note (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Compact bar above the bottom navigation on the cover screen.
 * Swipe up to open the player, swipe left to skip forward, right to skip back.
 * Long-press play for the sleep timer.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MiniPlayer(
    np: NowPlaying,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    sleepActive: Boolean,
    modifier: Modifier = Modifier,
    /** The running sleep timer, for the minutes-left label. */
    sleep: SleepTimer.State = SleepTimer.State(),
    skipBackSec: Int = 15,
    skipForwardSec: Int = 30,
    onSkipBack: () -> Unit = {},
    onSkipForward: () -> Unit = {},
) {
    val accent = bookAccent(np.book)
    val tick = rememberTick()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val threshold = with(density) { 64.dp.toPx() }
    var dragX by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var dragY by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val shownX by androidx.compose.animation.core.animateFloatAsState(if (dragging) dragX * 0.6f else 0f, label = "miniSwipe")
    val open by androidx.compose.runtime.rememberUpdatedState(onOpen)
    val back by androidx.compose.runtime.rememberUpdatedState(onSkipBack)
    val forward by androidx.compose.runtime.rememberUpdatedState(onSkipForward)

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        // What a sideways swipe will do, revealed under the bar as it slides.
        Row(
            Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(18.dp))
                .background(accent.copy(alpha = 0.22f))
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (shownX > 0f) SkipIcon(skipBackSec, forward = false, tint = EmberColors.Text, size = 30.dp)
            Spacer(Modifier.weight(1f))
            if (shownX < 0f) SkipIcon(skipForwardSec, forward = true, tint = EmberColors.Text, size = 30.dp)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = shownX }
                .clip(RoundedCornerShape(18.dp))
                .background(EmberColors.SurfaceHigh)
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            dragX = 0f
                            dragY = 0f
                            dragging = true
                        },
                        onDragEnd = {
                            dragging = false
                            when {
                                -dragY > threshold && kotlin.math.abs(dragY) > kotlin.math.abs(dragX) -> open()
                                dragX < -threshold -> {
                                    tick()
                                    forward()
                                }
                                dragX > threshold -> {
                                    tick()
                                    back()
                                }
                            }
                        },
                        onDragCancel = { dragging = false },
                    ) { change, amount ->
                        change.consume()
                        dragX += amount.x
                        dragY += amount.y
                    }
                }
                .clickable(onClick = onOpen),
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(accent.copy(alpha = 0.10f)),
            )
            Column {
                Row(
                    Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(np.book, Modifier.size(46.dp), corner = 8.dp, showBadge = false)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(np.book.displayTitle, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            np.chapter?.title ?: np.book.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = EmberColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // Icons light up in the book's accent color when not at their defaults.
                    IconButton(onClick = onSpeed) {
                        Icon(
                            Icons.Rounded.Speed,
                            "Playback speed, ${formatSpeed(np.speed)}",
                            tint = if (np.speed != 1f) accent else EmberColors.TextMuted,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(onClick = onSleep) {
                        // Minutes left while a timer runs (or "Ch." for end of chapter).
                        var now by remember { mutableStateOf(System.currentTimeMillis()) }
                        androidx.compose.runtime.LaunchedEffect(sleep) {
                            while (sleep.endsAt != null) {
                                now = System.currentTimeMillis()
                                kotlinx.coroutines.delay(5_000)
                            }
                        }
                        val left = sleep.endsAt?.let { ((it - now).coerceAtLeast(0) + 59_999) / 60_000 }
                        when {
                            left != null -> Text(
                                "${left}m",
                                style = MaterialTheme.typography.labelLarge,
                                color = accent,
                                modifier = Modifier.semantics { contentDescription = "Sleep timer, $left minutes left" },
                            )
                            sleep.stopAtPositionMs != null -> Text(
                                "Ch.",
                                style = MaterialTheme.typography.labelLarge,
                                color = accent,
                                modifier = Modifier.semantics { contentDescription = "Sleep timer, end of chapter" },
                            )
                            else -> Icon(
                                Icons.Rounded.Bedtime,
                                if (sleepActive) "Sleep timer on" else "Sleep timer",
                                tint = if (sleepActive) accent else EmberColors.TextMuted,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    PlayPauseWithSleep(np.isPlaying, accent, onToggle, onSleep)
                }
                ThinProgress(
                    np.chapterPosition.toFloat() / np.chapterLength,
                    modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 6.dp),
                    color = accent,
                    height = 2.dp,
                )
            }
        }
    }
}

/** Play/pause; a long press opens the sleep timer. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PlayPauseWithSleep(isPlaying: Boolean, accent: Color, onToggle: () -> Unit, onSleep: () -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .combinedClickable(
                onClick = onToggle,
                onLongClickLabel = "Sleep timer",
                onLongClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onSleep()
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (isPlaying) "Pause" else "Play",
            tint = accent,
            modifier = Modifier.size(32.dp),
        )
    }
}

/** Slim, no-scroll player for the right-hand column when unfolded. */
@Composable
private fun SidePanePlayer(
    np: NowPlaying,
    vm: MainViewModel,
    bookmarks: List<BookmarkEntity>,
    skipBack: Int,
    skipForward: Int,
    sleep: SleepTimer.State,
    castDevice: String?,
    showList: Boolean,
    onMinimize: (() -> Unit)?,
    onExpand: (() -> Unit)?,
    onOpenBook: (String) -> Unit,
    onCast: () -> Unit,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onBookmark: () -> Unit,
    onSound: () -> Unit,
    onClip: () -> Unit,
    onChapters: () -> Unit,
    onChapter: (Long) -> Unit,
    bookTime: Boolean = false,
    onToggleTime: () -> Unit = {},
    onLog: () -> Unit = {},
    onPdf: (() -> Unit)? = null,
    onLock: () -> Unit = {},
) {
    val accent = bookAccent(np.book)
    val tick = rememberTick()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onMinimize != null) {
                IconButton(onClick = onMinimize) {
                    Icon(Icons.Rounded.CloseFullscreen, "Minimize player", tint = GlassText)
                }
            }
            if (onExpand != null) {
                IconButton(onClick = onExpand) {
                    Icon(Icons.Rounded.OpenInFull, "Full-screen player", tint = GlassText)
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onSound) {
                Icon(Icons.Rounded.GraphicEq, "Sound and equalizer", tint = GlassText)
            }
            IconButton(onClick = onCast) {
                Icon(
                    if (castDevice != null) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                    if (castDevice != null) "Casting to $castDevice" else "Cast",
                    tint = if (castDevice != null) MaterialTheme.colorScheme.primary else GlassText,
                )
            }
            IconButton(onClick = { onOpenBook(np.book.id) }) {
                Icon(Icons.Rounded.Info, "Details", tint = GlassText)
            }
        }

        val coverModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
        if (showList) {
            BookCover(
                np.book,
                coverModifier
                    .widthIn(max = 240.dp)
                    .align(Alignment.CenterHorizontally)
                    .shadow(20.dp, RoundedCornerShape(12.dp)),
                corner = 12.dp,
                showBadge = false,
            )
        } else {
            // Largest square that fits both the column's width and the leftover height.
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                val side = minOf(maxWidth, maxHeight)
                DoubleTapSkip(skipBack, skipForward, vm::skipBack, vm::skipForward) {
                    BookCover(
                        np.book,
                        Modifier
                            .size(side)
                            .shadow(20.dp, RoundedCornerShape(12.dp)),
                        corner = 12.dp,
                        showBadge = false,
                    )
                }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            Text(
                np.book.displayTitle,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontWeight = FontWeight.Normal),
                color = GlassText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (np.book.author.isNotBlank()) {
                Text(np.book.author, style = MaterialTheme.typography.bodySmall, color = GlassMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            PlayerExtrasRow(onLog, onPdf, tint = GlassText, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.SkipPrevious,
                    "Previous chapter",
                    tint = GlassMuted,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable { tick(); vm.previousChapter() }.padding(2.dp),
                )
                // Tap the chapter name to open the full chapter list.
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onChapters)
                        .padding(vertical = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        np.chapter?.title ?: "",
                        style = MaterialTheme.typography.labelMedium,
                        color = GlassText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    if (np.chapters.size > 1) {
                        Text(
                            "Chapter ${np.chapterIndex + 1} of ${np.chapters.size} \u25BE",
                            style = MaterialTheme.typography.labelSmall,
                            color = GlassMuted,
                        )
                    }
                }
                Icon(
                    Icons.Rounded.SkipNext,
                    "Next chapter",
                    tint = GlassMuted,
                    modifier = Modifier.size(28.dp).clip(CircleShape).clickable { tick(); vm.nextChapter() }.padding(2.dp),
                )
            }
            ChapterSlider(np, onSeek = vm::seekGlobal, bookTime = bookTime, onToggleTime = onToggleTime)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { tick(); vm.skipBack() }) { SkipIcon(skipBack, forward = false, tint = GlassText, size = 32.dp) }
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(accent)
                        .clickable(onClick = vm::togglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    if (np.buffering && !np.isPlaying) {
                        CircularProgressIndicator(color = onColorFor(accent), strokeWidth = 3.dp, modifier = Modifier.size(26.dp))
                    } else {
                        Icon(
                            if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (np.isPlaying) "Pause" else "Play",
                            tint = onColorFor(accent),
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
                IconButton(onClick = { tick(); vm.skipForward() }) { SkipIcon(skipForward, forward = true, tint = GlassText, size = 32.dp) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SmallRoundButton(Icons.Rounded.Lock, "Lock the screen", highlighted = false, onClick = onLock)
                SmallRoundButton(Icons.Rounded.Speed, "Speed ${formatSpeed(np.speed)}", highlighted = np.speed != 1f, onClick = onSpeed)
                SmallRoundButton(Icons.Rounded.Bedtime, "Sleep timer", highlighted = sleep.active, onClick = onSleep)
                SmallRoundButton(Icons.Rounded.BookmarkAdd, "Add bookmark", highlighted = false, onClick = onBookmark)
                SmallRoundButton(Icons.Rounded.ContentCut, "Clip the last few seconds", highlighted = false, onClick = onClip)
                if (!showList) SmallRoundButton(Icons.Rounded.List, "Chapters", highlighted = false, onClick = onChapters)
            }
            val status = when {
                sleep.stopAtPositionMs != null -> "Sleep at end of chapter"
                sleep.endsAt != null -> "Sleep in " + formatClock(sleep.remainingMs() ?: 0)
                np.speed != 1f -> "Playing at ${formatSpeed(np.speed)}"
                else -> null
            }
            if (status != null) {
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = GlassMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (showList) {
            GlassPanel(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                padding = PaddingValues(4.dp),
            ) {
                ChaptersAndBookmarks(np, bookmarks, onChapter, onBookmark = onChapter, onDeleteBookmark = vm::deleteBookmark, compact = true)
            }
        }
    }
}

@Composable
private fun SmallRoundButton(icon: ImageVector, label: String, highlighted: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (highlighted) MaterialTheme.colorScheme.primary else EmberColors.Wash.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (highlighted) MaterialTheme.colorScheme.onPrimary else GlassText, modifier = Modifier.size(20.dp))
    }
}

/** The minimized player: a small floating bar in the bottom-right corner of the unfolded screen. */
@Composable
fun DockedMiniPlayer(np: NowPlaying, onExpand: () -> Unit, onToggle: () -> Unit, modifier: Modifier = Modifier, onSleep: () -> Unit = {}) {
    val accent = bookAccent(np.book)
    Box(
        modifier
            .width(320.dp)
            .shadow(16.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(EmberColors.SurfaceHighest)
            .clickable(onClick = onExpand),
    ) {
        Box(Modifier.matchParentSize().background(accent.copy(alpha = 0.12f)))
        Column {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(np.book, Modifier.size(48.dp), corner = 8.dp, showBadge = false)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(np.book.displayTitle, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        np.chapter?.title ?: np.book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = EmberColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PlayPauseWithSleep(np.isPlaying, accent, onToggle, onSleep)
                IconButton(onClick = onExpand) {
                    Icon(Icons.Rounded.OpenInFull, "Show player", tint = EmberColors.TextMuted, modifier = Modifier.size(20.dp))
                }
            }
            ThinProgress(
                np.chapterPosition.toFloat() / np.chapterLength,
                modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 6.dp),
                color = accent,
                height = 2.dp,
            )
        }
    }
}


/**
 * Pull the player down to close it (like Spotify or Audible). Only from the top part of the
 * screen (the bar and the cover), so it never fights the scrubber or the buttons.
 */
@Composable
private fun Modifier.swipeDownToClose(onClose: () -> Unit): Modifier {
    val close by androidx.compose.runtime.rememberUpdatedState(onClose)
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return this
        .pointerInput(Unit) {
            // Looks at touches before the cover and buttons do, and takes over only once the
            // finger clearly moves down (sideways moves and taps are left alone).
            val pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
                if (down.position.y > size.height * 0.55f) return@awaitEachGesture
                val slop = viewConfiguration.touchSlop
                var dragging = false
                var pulled = 0f
                var released = false
                while (true) {
                    val event = awaitPointerEvent(pass)
                    val c = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) {
                        released = true
                        break
                    }
                    val dx = c.position.x - down.position.x
                    val dy = c.position.y - down.position.y
                    if (!dragging) {
                        if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) return@awaitEachGesture
                        if (dy < -slop) return@awaitEachGesture
                        if (dy > slop && dy > kotlin.math.abs(dx)) dragging = true else continue
                    }
                    pulled = (dy - slop).coerceAtLeast(0f)
                    c.consume()
                    scope.launch { offset.snapTo(pulled) }
                }
                if (!dragging) return@awaitEachGesture
                if (released && pulled > size.height * 0.18f) {
                    scope.launch {
                        offset.animateTo(size.height.toFloat(), androidx.compose.animation.core.tween(160))
                        close()
                        offset.snapTo(0f)
                    }
                } else {
                    scope.launch { offset.animateTo(0f, androidx.compose.animation.core.spring()) }
                }
            }
        }
        .graphicsLayer {
            translationY = offset.value
            alpha = 1f - (offset.value / size.height.coerceAtLeast(1f)).coerceIn(0f, 0.5f)
        }
}
