@file:OptIn(ExperimentalLayoutApi::class)

package app.ember.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import app.ember.util.formatClock

private class EditableChapter(val key: Long, start: Long, title: String) {
    var start by mutableStateOf(start)
    var title by mutableStateOf(title)
}

/** Rename, add, remove and re-time chapters. Changes are saved in Ember, not in the audio file. */
@Composable
fun ChapterEditorScreen(vm: MainViewModel, bookId: String, onDone: () -> Unit) {
    val np by vm.nowPlaying.collectAsState()
    val book by remember(bookId) { vm.observeBook(bookId) }.collectAsState(initial = null)
    val rows = remember { mutableStateListOf<EditableChapter>() }
    var loaded by remember { mutableStateOf(false) }
    var nextKey by remember { mutableStateOf(0L) }
    var confirmNumber by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var writeFile by remember { mutableStateOf(true) }

    LaunchedEffect(bookId) {
        val chapters = vm.chaptersFor(bookId)
        rows.clear()
        chapters.forEach { rows += EditableChapter(nextKey++, it.startMs, it.title) }
        loaded = true
    }

    // "Now" only makes sense while this book is the one in the player.
    val here = np?.takeIf { it.book.id == bookId }?.position
    val leave: () -> Unit = { if (dirty) confirmLeave = true else onDone() }

    fun addAt(ms: Long) {
        val title = "New chapter"
        val at = rows.indexOfFirst { it.start > ms }.let { if (it < 0) rows.size else it }
        rows.add(at, EditableChapter(nextKey++, ms, title))
        dirty = true
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = leave) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
            Column(Modifier.weight(1f)) {
                Text("Edit chapters", style = MaterialTheme.typography.titleLarge, color = EmberColors.Text)
                book?.let { Text(it.title, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1) }
            }
        }

        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssistChip(
                onClick = { addAt(here ?: (rows.lastOrNull()?.start ?: 0L)) },
                label = { Text(if (here != null) "Add at ${formatClock(here)}" else "Add chapter") },
                leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
            )
            AssistChip(
                onClick = { confirmNumber = true },
                label = { Text("Number all") },
                leadingIcon = { Icon(Icons.Rounded.FormatListNumbered, null, Modifier.size(18.dp)) },
            )
        }
        Text(
            if (here != null) {
                "Tip: play the book to the right spot, then tap the pin on a chapter to move its start there."
            } else {
                "Tip: start playing this book to add chapters or move their starts to the exact spot you're hearing."
            },
            style = MaterialTheme.typography.bodySmall,
            color = EmberColors.TextFaint,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        Box(Modifier.weight(1f)) {
            if (!loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                    itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = EmberColors.TextFaint)
                                Text(
                                    formatClock(if (i == 0) 0 else r.start),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { vm.playFrom(bookId, r.start) }
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                )
                            }
                            OutlinedTextField(
                                value = r.title,
                                onValueChange = {
                                    r.title = it
                                    dirty = true
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { vm.playFrom(bookId, r.start) }) {
                                Icon(Icons.Rounded.PlayArrow, "Play from this chapter", tint = EmberColors.TextMuted)
                            }
                            if (here != null && i > 0) {
                                IconButton(onClick = {
                                    r.start = here
                                    rows.sortBy { it.start }
                                    dirty = true
                                }) {
                                    Icon(Icons.Rounded.PinDrop, "Start this chapter at ${formatClock(here)}", tint = EmberColors.TextMuted)
                                }
                            }
                            if (rows.size > 1) {
                                IconButton(onClick = {
                                    rows.remove(r)
                                    dirty = true
                                }) {
                                    Icon(Icons.Rounded.Delete, "Remove chapter", tint = EmberColors.TextFaint)
                                }
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.padding(horizontal = 16.dp)) {
            SaveToFileOption(vm, bookId, writeFile, { writeFile = it }, what = "chapter names and times")
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = leave) { Text("Cancel") }
            Button(
                onClick = {
                    vm.saveChapters(bookId, rows.map { it.start to it.title }, writeFile = writeFile)
                    onDone()
                },
                enabled = loaded && rows.isNotEmpty(),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text("Save chapters") }
        }
    }

    if (confirmNumber) {
        AlertDialog(
            onDismissRequest = { confirmNumber = false },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Number all chapters?") },
            text = { Text("Renames every chapter to Chapter 1, Chapter 2, and so on. Nothing is saved until you tap Save chapters.") },
            confirmButton = {
                TextButton(onClick = {
                    rows.forEachIndexed { i, r -> r.title = "Chapter ${i + 1}" }
                    dirty = true
                    confirmNumber = false
                }) { Text("Number all") }
            },
            dismissButton = { TextButton(onClick = { confirmNumber = false }) { Text("Cancel") } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Discard your changes?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    onDone()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Keep editing") } },
        )
    }
}
