@file:OptIn(ExperimentalMaterial3Api::class)

package app.ember.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.BookEntity
import app.ember.ui.MainViewModel
import app.ember.ui.NowPlaying
import app.ember.ui.components.BookCover
import app.ember.ui.components.SectionHeader
import app.ember.ui.theme.EmberColors
import app.ember.util.formatClock
import app.ember.util.formatDuration
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------------
// Up Next
// ---------------------------------------------------------------------------------------------

/** Home: the books lined up after the one playing, numbered. */
@Composable
fun UpNextSection(vm: MainViewModel, onOpenBook: (String) -> Unit, onManage: () -> Unit) {
    val queue by vm.upNext.collectAsState()
    if (queue.isEmpty()) return
    SectionHeader("Up Next", action = "Edit", onAction = onManage)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(queue, key = { _, b -> "un" + b.id }) { i, b ->
            Column(
                Modifier
                    .width(116.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpenBook(b.id) }
                    .padding(4.dp),
            ) {
                Box {
                    BookCover(b, Modifier.size(108.dp), corner = 10.dp, showBadge = false)
                    Box(
                        Modifier
                            .padding(6.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.65f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(b.displayTitle, style = MaterialTheme.typography.bodyMedium, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Up Next, to reorder, play or remove. Plays in this order when a book finishes. */
@Composable
fun UpNextScreen(vm: MainViewModel, onBack: () -> Unit, onOpenBook: (String) -> Unit) {
    val queue by vm.upNext.collectAsState()
    val settings by vm.settings.collectAsState()
    Column(Modifier.fillMaxSize().background(EmberColors.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
            Text("Up Next", style = MaterialTheme.typography.titleLarge, color = EmberColors.Text, modifier = Modifier.weight(1f))
            if (queue.isNotEmpty()) TextButton(onClick = vm::clearUpNext) { Text("Clear") }
        }
        Text(
            if (settings.seriesAutoPlay) {
                "When a book ends, the first book here starts next. With nothing here, the next book in the series plays."
            } else {
                "When a book ends, the first book here starts next."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = EmberColors.TextMuted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        if (queue.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.QueueMusic, null, tint = EmberColors.TextFaint, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("Nothing lined up", style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
                Text(
                    "Long-press a book cover and choose Add to Up Next.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmberColors.TextMuted,
                )
            }
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(queue, key = { _, b -> b.id }) { i, b ->
                UpNextRow(
                    b,
                    position = i + 1,
                    canUp = i > 0,
                    canDown = i < queue.size - 1,
                    onOpen = { onOpenBook(b.id) },
                    onPlay = { vm.play(b.id) },
                    onUp = { vm.moveInUpNext(b.id, -1) },
                    onDown = { vm.moveInUpNext(b.id, +1) },
                    onRemove = { vm.removeFromUpNext(b.id) },
                )
            }
        }
    }
}

@Composable
private fun UpNextRow(
    b: BookEntity,
    position: Int,
    canUp: Boolean,
    canDown: Boolean,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$position", style = MaterialTheme.typography.titleMedium, color = EmberColors.TextMuted, modifier = Modifier.width(26.dp))
        BookCover(b, Modifier.size(56.dp), corner = 8.dp, showBadge = false)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(b.displayTitle, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = listOf(b.author, if (b.durationMs > 0) formatDuration(b.remainingMs) + " left" else "").filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, "Play now", tint = EmberColors.Text) }
        Column {
            IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.KeyboardArrowUp, "Move up", tint = if (canUp) EmberColors.Text else EmberColors.TextFaint)
            }
            IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Move down", tint = if (canDown) EmberColors.Text else EmberColors.TextFaint)
            }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove from Up Next", tint = EmberColors.TextMuted) }
    }
}

// ---------------------------------------------------------------------------------------------
// Fell asleep? (sleep timer)
// ---------------------------------------------------------------------------------------------

/**
 * In the player after the sleep timer stopped a book: offers to go back to where you last
 * set or shook the timer, which is the last moment you were surely awake.
 */
@Composable
fun SleepMarkBar(vm: MainViewModel, np: NowPlaying, modifier: Modifier = Modifier) {
    val mark by vm.sleepMark.collectAsState()
    val m = mark ?: return
    if (m.bookId != np.book.id) return
    // Gone once you've listened well past it, or gone back before it.
    if (np.position < m.awakeMs - 5_000 || np.position > m.stoppedMs + 10 * 60_000) return
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Bedtime, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Fell asleep?", style = MaterialTheme.typography.titleSmall, color = Color.White)
            Text(
                "You set the timer at ${describe(np, m.awakeMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = vm::goBackToSleepMark) { Text("Go back", color = Color.White) }
        IconButton(onClick = vm::dismissSleepMark) { Icon(Icons.Rounded.Close, "Dismiss", tint = Color.White.copy(alpha = 0.8f)) }
    }
}

private fun describe(np: NowPlaying, ms: Long): String {
    val ch = np.chapters.lastOrNull { it.startMs <= ms }
    return if (ch != null && np.chapters.size > 1) "${formatClock(ms - ch.startMs)} in ${ch.title}" else formatClock(ms)
}

// ---------------------------------------------------------------------------------------------
// Export notes
// ---------------------------------------------------------------------------------------------

/** Share or save a book's notes (details, rating, bookmarks, clips) as Markdown. */
@Composable
fun ExportNotesDialog(vm: MainViewModel, bookId: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) vm.saveNotes(uri, bookId)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmberColors.SurfaceHigh,
        icon = { Icon(Icons.Rounded.Description, null) },
        title = { Text("Export notes") },
        text = {
            Column {
                Text(
                    "Your rating, bookmarks and clip notes, with chapters and times, as one Markdown page. Notion, Obsidian and most notes apps read it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmberColors.TextMuted,
                )
                Spacer(Modifier.height(12.dp))
                ExportChoice(Icons.Rounded.Share, "Share", "Send straight to Notion or another app") {
                    vm.shareNotes(context, bookId)
                    onDismiss()
                }
                ExportChoice(Icons.Rounded.SaveAlt, "Save as a file", "A .md file, to import in Notion (Import → Markdown)") {
                    scope.launch { save.launch(vm.notesFileNameFor(bookId)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExportChoice(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// A book's own sound
// ---------------------------------------------------------------------------------------------

/** In the sound sheet: trim silence and voice boost just for this book (like a podcast's own settings). */
@Composable
fun BookSoundSection(vm: MainViewModel, bookId: String) {
    val s by vm.settings.collectAsState()
    val live by androidx.compose.runtime.remember(bookId) { vm.observeBook(bookId) }.collectAsState(initial = null)
    val book = live ?: return
    Column(Modifier.fillMaxWidth()) {
        Text("Just for this book", style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
        Text(book.displayTitle, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        SoundSwitch("Use its own settings", "Otherwise this book follows your app-wide settings", book.customEffects) {
            vm.setBookEffects(book.id, it, if (it) s.skipSilence else book.trimSilence, if (it) s.narrationBoost else book.voiceBoost)
        }
        if (book.customEffects) {
            SoundSwitch("Trim silence", "Shortens long pauses", book.trimSilence) {
                vm.setBookEffects(book.id, true, it, book.voiceBoost)
            }
            SoundSwitch("Voice boost", "Makes voices clearer and more even", book.voiceBoost) {
                vm.setBookEffects(book.id, true, book.trimSilence, it)
            }
        }
        Spacer(Modifier.height(8.dp))
        SkipChoices("Skip at the start of each file", "For a jingle or \"This is Audible\" on every file", book.skipIntroSec, listOf(0, 3, 5, 10, 15, 30)) {
            vm.setBookSkips(book.id, it, book.skipOutroSec)
        }
        SkipChoices("Skip at the end of each file", "Moves on to the next file that much sooner", book.skipOutroSec, listOf(0, 3, 5, 10, 15)) {
            vm.setBookSkips(book.id, book.skipIntroSec, it)
        }
    }
}

/** Off / 3s / 5s ... as a row of chips. */
@Composable
private fun SkipChoices(title: String, sub: String, value: Int, choices: List<Int>, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEach { c ->
                val on = c == value
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) MaterialTheme.colorScheme.primary else EmberColors.SurfaceHigh)
                        .clickable(onClickLabel = if (c == 0) "Off" else "$c seconds") { onPick(c) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (c == 0) "Off" else "${c}s",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else EmberColors.Text,
                    )
                }
            }
        }
    }
}

@Composable
private fun SoundSwitch(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onChange(!on) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}
