@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.ember.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.BookEntity
import app.ember.data.ClipEntity
import app.ember.ui.MainViewModel
import app.ember.ui.components.BookCover
import app.ember.ui.components.EmptyState
import app.ember.ui.theme.EmberColors
import app.ember.util.formatClock
import java.text.DateFormat
import java.util.Date

/** Shows a clip in the same shape as a book so covers render the usual way. */
private fun ClipEntity.asBook(): BookEntity = BookEntity(id = mediaId, title = title, author = subtitle, coverPath = coverPath)

/** "Clip": the last few seconds you heard, saved with an optional note. */
@Composable
fun ClipSheet(
    vm: MainViewModel,
    draft: ClipEntity,
    onDismiss: () -> Unit,
    onMakeCard: (Long) -> Unit,
) {
    val context = LocalContext.current
    val end = draft.endMs
    var start by remember { mutableLongStateOf(draft.startMs) }
    var note by remember { mutableStateOf("") }
    val length = ((end - start) / 1000).toInt()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = EmberColors.Surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(draft.asBook(), Modifier.size(56.dp), corner = 8.dp, showBadge = false)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("New clip", style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
                    Text(draft.title, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${formatClock(start)} – ${formatClock(end)}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "$length seconds" + if (draft.chapterTitle.isNotBlank()) " · ${draft.chapterTitle}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmberColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(onClick = { vm.previewRange(draft.mediaId, start, end) }) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Listen")
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 90).forEach { sec ->
                    FilterChip(
                        selected = length == sec,
                        onClick = { start = (end - sec * 1000L).coerceAtLeast(0) },
                        label = { Text("${sec}s") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Quote or note") },
                placeholder = { Text("What was said, or why it stuck with you") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    vm.saveClip(draft.copy(startMs = start), note) {
                        Toast.makeText(context, "Clip saved", Toast.LENGTH_SHORT).show()
                    }
                    onDismiss()
                }, modifier = Modifier.weight(1f)) { Text("Save clip") }
                Button(onClick = {
                    vm.saveClip(draft.copy(startMs = start), note) { id -> onMakeCard(id) }
                    onDismiss()
                }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.FormatQuote, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Quote card")
                }
            }
        }
    }
}

/** One clip in a list: play it, make a card, edit the note, or delete it. */
@Composable
fun ClipRow(
    clip: ClipEntity,
    onPlay: () -> Unit,
    onCard: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit,
    textColor: Color = EmberColors.Text,
    mutedColor: Color = EmberColors.TextMuted,
    showTitle: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onPlay)
            .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.ContentCut, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (showTitle) {
                Text(clip.title, style = MaterialTheme.typography.titleSmall, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${formatClock(clip.startMs)} · ${(clip.lengthMs / 1000)}s" + if (clip.chapterTitle.isNotBlank()) " · ${clip.chapterTitle}" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (clip.note.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text("“${clip.note}”", style = MaterialTheme.typography.bodyMedium, color = textColor, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onCard) { Icon(Icons.Rounded.FormatQuote, "Make a quote card", tint = mutedColor) }
        if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Edit note", tint = mutedColor) }
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete clip", tint = mutedColor) }
    }
}

@Composable
fun EditClipNoteDialog(clip: ClipEntity, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by remember(clip.id) { mutableStateOf(clip.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmberColors.SurfaceHigh,
        title = { Text("Clip note") },
        text = {
            OutlinedTextField(value = note, onValueChange = { note = it }, minLines = 3, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Every clip you've saved, newest first. */
@Composable
fun ClipsScreen(vm: MainViewModel, onBack: () -> Unit, onOpenCard: (Long) -> Unit) {
    val clips by vm.clips.collectAsState()
    var editing by remember { mutableStateOf<ClipEntity?>(null) }
    var deleting by remember { mutableStateOf<ClipEntity?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
                Text("Clips", style = MaterialTheme.typography.headlineLarge, color = EmberColors.Text)
            }
        }
        if (clips.isEmpty()) {
            item {
                EmptyState(
                    "No clips yet",
                    "While you listen, tap Clip in the player to save the last ${vm.settings.value.clipLengthSec} seconds with a note. " +
                        "Turn any clip into a quote card to share.",
                )
            }
        }
        val grouped = clips.groupBy { it.mediaId }
        grouped.forEach { (_, list) ->
            val first = list.first()
            item(key = "h-${first.mediaId}") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BookCover(first.asBook(), Modifier.size(44.dp), corner = 6.dp, showBadge = false)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(first.title, style = MaterialTheme.typography.titleMedium, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${list.size} clip${if (list.size == 1) "" else "s"} · last " +
                                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(list.maxOf { it.createdAt })),
                            style = MaterialTheme.typography.bodySmall,
                            color = EmberColors.TextMuted,
                        )
                    }
                }
            }
            items(list.sortedBy { it.startMs }, key = { it.id }) { clip ->
                Box(Modifier.padding(horizontal = 8.dp)) {
                    ClipRow(
                        clip,
                        onPlay = { vm.playClip(clip) },
                        onCard = { onOpenCard(clip.id) },
                        onEdit = { editing = clip },
                        onDelete = { deleting = clip },
                    )
                }
            }
        }
    }

    editing?.let { clip ->
        EditClipNoteDialog(clip, onDismiss = { editing = null }) {
            vm.updateClipNote(clip.id, it)
            editing = null
        }
    }
    deleting?.let { clip ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Delete this clip?") },
            text = { Text(if (clip.note.isNotBlank()) "“${clip.note}”" else "${formatClock(clip.startMs)} in ${clip.title}") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteClip(clip.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
