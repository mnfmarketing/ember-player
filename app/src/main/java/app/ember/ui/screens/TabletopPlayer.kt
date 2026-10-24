package app.ember.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ember.ui.MainViewModel
import app.ember.ui.Posture
import app.ember.ui.components.BookCover
import app.ember.ui.components.CoverBackdrop
import app.ember.ui.components.bookAccent
import app.ember.ui.theme.EmberColors
import app.ember.ui.theme.onColorFor
import app.ember.util.formatClock
import app.ember.util.formatDuration
import app.ember.util.formatSpeed

/**
 * The Fold half-open like a little laptop (tabletop): the top half shows the cover and what's
 * playing, facing you; the bottom half, lying flat, has big controls. Opens by itself when
 * you fold the phone that way while a book is loaded.
 */
@Composable
fun TabletopPlayer(vm: MainViewModel, posture: Posture.Tabletop, onExit: () -> Unit) {
    val npState by vm.nowPlaying.collectAsState()
    val np = npState ?: return
    val settings by vm.settings.collectAsState()
    val sleep by vm.sleep.collectAsState()
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    val accent = bookAccent(np.book)
    val text = EmberColors.GlassText
    val muted = EmberColors.GlassMuted
    val tick = rememberTick()

    Box(
        Modifier
            .fillMaxSize()
            .background(EmberColors.Background)
            // Nothing underneath reacts while this is up.
            .clickable(enabled = true, onClickLabel = null, indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {},
    ) {
        CoverBackdrop(np.book, Modifier.fillMaxSize())
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val topH = maxHeight * posture.hingeTop
            val bottomStart = maxHeight * posture.hingeBottom
            val bottomH = maxHeight - bottomStart

            // ---- Top half: what's playing ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(topH)
                    .statusBarsPadding()
                    .padding(horizontal = 40.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                BoxWithConstraints(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val side: Dp = minOf(maxHeight, 340.dp)
                    BookCover(np.book, Modifier.size(side).shadow(24.dp, RoundedCornerShape(16.dp)), corner = 16.dp, showBadge = false)
                }
                Spacer(Modifier.width(36.dp))
                Column(Modifier.weight(1f).widthIn(max = 560.dp)) {
                    Text("Now playing", style = MaterialTheme.typography.labelLarge, color = muted)
                    Text(
                        np.book.displayTitle,
                        style = MaterialTheme.typography.headlineLarge,
                        fontFamily = FontFamily.Serif,
                        color = text,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (np.book.author.isNotBlank()) {
                        Text(np.book.author, style = MaterialTheme.typography.titleMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(14.dp))
                    np.chapter?.takeIf { np.chapters.size > 1 }?.let {
                        Text(it.title, style = MaterialTheme.typography.titleMedium, color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val left = (np.remaining / np.speed.coerceAtLeast(0.1f)).toLong()
                    Text(
                        "${formatDuration(left)} left in the ${if (np.isEpisode) "episode" else "book"}" + if (np.speed != 1f) " at ${formatSpeed(np.speed)}" else "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = muted,
                    )
                }
            }

            // ---- Bottom half: controls, lying flat ----
            Column(
                Modifier
                    .fillMaxWidth()
                    .offset(y = bottomStart)
                    .height(bottomH)
                    .navigationBarsPadding()
                    .padding(horizontal = 40.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.widthIn(max = 760.dp)) {
                    ChapterSlider(np, onSeek = vm::seekGlobal, bookTime = settings.bookTimeLeft, onToggleTime = { vm.setBookTimeLeft(!settings.bookTimeLeft) })
                }
                Row(
                    Modifier.widthIn(max = 760.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { tick(); vm.previousChapter() }, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous chapter", tint = text, modifier = Modifier.size(34.dp))
                    }
                    IconButton(onClick = { tick(); vm.skipBack() }, modifier = Modifier.size(72.dp)) {
                        SkipIcon(settings.skipBackSec, forward = false, tint = text, size = 44.dp)
                    }
                    Box(
                        Modifier
                            .size(104.dp)
                            .shadow(20.dp, CircleShape, ambientColor = accent, spotColor = accent)
                            .clip(CircleShape)
                            .background(accent)
                            .clickable(onClickLabel = if (np.isPlaying) "Pause" else "Play", onClick = vm::togglePlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (np.isPlaying) "Pause" else "Play",
                            tint = onColorFor(accent),
                            modifier = Modifier.size(56.dp),
                        )
                    }
                    IconButton(onClick = { tick(); vm.skipForward() }, modifier = Modifier.size(72.dp)) {
                        SkipIcon(settings.skipForwardSec, forward = true, tint = text, size = 44.dp)
                    }
                    IconButton(onClick = { tick(); vm.nextChapter() }, modifier = Modifier.size(64.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Next chapter", tint = text, modifier = Modifier.size(34.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TablePill(Icons.Rounded.Speed, formatSpeed(np.speed), text) { showSpeed = true }
                    val sleepLabel = when {
                        sleep.stopAtPositionMs != null -> "End of ch."
                        sleep.endsAt != null -> formatClock(sleep.remainingMs() ?: 0)
                        else -> "Sleep"
                    }
                    TablePill(Icons.Rounded.Bedtime, sleepLabel, text, highlighted = sleep.active) { showSleep = true }
                    TablePill(Icons.Rounded.BookmarkAdd, "Bookmark", text) {
                        vm.quickBookmark()?.let { app.ember.playback.PlaybackStatus.notice.value = "Bookmarked at $it" }
                    }
                    TablePill(Icons.Rounded.CloseFullscreen, "Library", text, onClick = onExit)
                }
            }
        }
    }

    if (showSpeed) SpeedSheet(np.speed, onDismiss = { showSpeed = false }, onSpeed = vm::setSpeed)
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
}

@Composable
private fun TablePill(icon: ImageVector, label: String, tint: Color, highlighted: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else EmberColors.GlassFill)
            .border(1.dp, EmberColors.GlassBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = tint)
    }
}
