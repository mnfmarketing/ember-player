@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.ember.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.BookEntity
import app.ember.ui.ExternalApp
import app.ember.ui.MainViewModel
import app.ember.ui.components.BookCover
import app.ember.ui.components.RatingBar
import app.ember.ui.components.bookAccent
import app.ember.ui.theme.EmberColors
import app.ember.ui.theme.onColorFor
import app.ember.util.formatDuration

/** A book's settings in a sheet, straight from its cover: no need to open the book first. */
@Composable
fun BookQuickSheet(
    vm: MainViewModel,
    bookId: String,
    onDismiss: () -> Unit,
    onOpenBook: () -> Unit,
    onEdit: () -> Unit,
    onEditChapters: () -> Unit,
    onPlayed: () -> Unit,
    /** Starts picking several books, with this one ticked. Null where selecting isn't offered. */
    onSelect: (() -> Unit)? = null,
    onOpenPdf: () -> Unit = {},
    onFindInfo: () -> Unit = {},
) {
    val bookState by remember(bookId) { vm.observeBook(bookId) }.collectAsState(initial = null)
    val book = bookState ?: return
    val shelves by vm.shelves.collectAsState()
    val links by vm.shelfLinks.collectAsState()
    val np by vm.nowPlaying.collectAsState()
    val context = LocalContext.current
    val accent = bookAccent(book)
    val myShelves = links.filter { it.bookId == book.id }.map { it.shelfId }.toSet()
    var newShelf by remember { mutableStateOf(false) }
    var coverSearch by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var exportNotes by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsState()
    val chapters by remember(bookId) { vm.observeChapters(bookId) }.collectAsState(initial = emptyList())
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.applyCoverImage(book.id, uri)
    }
    val finished = book.status == BookEntity.STATUS_FINISHED
    val playing = np?.book?.id == book.id && np?.isPlaying == true

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = EmberColors.Surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            // The book
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                BookCover(book, Modifier.size(84.dp), corner = 10.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.title, style = MaterialTheme.typography.titleLarge, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotBlank()) {
                        Text(book.author, style = MaterialTheme.typography.bodyMedium, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        when {
                            finished -> "Finished"
                            book.isExternal -> "${book.positionMs}% in ${ExternalApp.from(book.externalApp).label}"
                            book.positionMs > 0 -> "${(book.progress * 100).toInt()}% · ${formatDuration(book.remainingMs)} left"
                            book.durationMs > 0 -> "Not started · ${formatDuration(book.durationMs)}"
                            else -> "Not started"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = EmberColors.TextMuted,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // Play and open
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        if (book.isExternal) vm.openExternal(context, book) else if (playing) vm.togglePlay() else {
                            vm.play(book.id)
                            onPlayed()
                        }
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onColorFor(accent)),
                    modifier = Modifier.weight(1f).height(50.dp),
                ) {
                    Icon(if (book.isExternal) Icons.Rounded.OpenInNew else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            book.isExternal -> "Open in ${ExternalApp.from(book.externalApp).label}"
                            playing -> "Pause"
                            book.positionMs > 0 && !finished -> "Resume"
                            else -> "Play"
                        },
                    )
                }
                OutlinedButton(onClick = {
                    onDismiss()
                    onOpenBook()
                }, modifier = Modifier.height(50.dp)) {
                    Icon(Icons.Rounded.Info, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Details")
                }
            }

            // Rating
            Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Your rating", style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text, modifier = Modifier.weight(1f))
                RatingBar(book.rating, onRate = { vm.setRating(book.id, if (it == book.rating) 0 else it) }, size = 30.dp)
            }

            // Shelves
            Text(
                "Shelves",
                style = MaterialTheme.typography.labelLarge,
                color = EmberColors.TextMuted,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
            )
            FlowRow(
                Modifier.padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                shelves.forEach { s ->
                    val on = s.id in myShelves
                    FilterChip(
                        selected = on,
                        onClick = { vm.setOnShelf(s.id, book.id, !on) },
                        label = { Text(s.name) },
                        leadingIcon = if (on) { { Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp)) } } else null,
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = onColorFor(accent), selectedLeadingIconColor = onColorFor(accent)),
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = { newShelf = true },
                    label = { Text("New shelf") },
                    leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(16.dp)) },
                )
            }

            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = EmberColors.Wash.copy(alpha = 0.10f))

            // Everything else, one tap each
            // These close the sheet so the Undo bar underneath can be seen.
            if (finished) {
                SheetAction(Icons.Rounded.RestartAlt, "Mark as not finished") {
                    vm.resetProgress(book.id)
                    onDismiss()
                }
            } else {
                SheetAction(Icons.Rounded.CheckCircle, "Mark as finished") {
                    vm.markFinished(book.id)
                    onDismiss()
                }
                if (book.positionMs > 0) {
                    SheetAction(Icons.Rounded.RestartAlt, "Start over (reset progress)") {
                        vm.resetProgress(book.id)
                        onDismiss()
                    }
                }
            }
            if (onSelect != null) {
                SheetAction(Icons.Rounded.Checklist, "Select more books") {
                    onDismiss()
                    onSelect()
                }
            }
            if (book.hasPdf) {
                SheetAction(Icons.Rounded.PictureAsPdf, "Open the PDF") {
                    onDismiss()
                    onOpenPdf()
                }
            }
            if (!book.isExternal) {
                val queued = settings.upNext.contains(book.id)
                SheetAction(Icons.Rounded.QueueMusic, if (queued) "Remove from Up Next" else "Add to Up Next") {
                    if (queued) vm.removeFromUpNext(book.id) else vm.addToUpNext(listOf(book.id))
                    onDismiss()
                }
            }
            if (!book.isExternal) SheetAction(Icons.Rounded.History, "Listening log") { showLog = true }
            SheetAction(Icons.Rounded.Description, "Export notes (Notion, Markdown)") { exportNotes = true }
            SheetAction(Icons.Rounded.Edit, "Edit details") {
                onDismiss()
                onEdit()
            }
            if (!book.isExternal) {
                SheetAction(Icons.Rounded.List, "Edit chapters") {
                    onDismiss()
                    onEditChapters()
                }
            }
            SheetAction(Icons.Rounded.TravelExplore, "Find book info (series, narrator…)") {
                onDismiss()
                onFindInfo()
            }
            SheetAction(Icons.Rounded.ImageSearch, "Find cover online") { coverSearch = true }
            SheetAction(Icons.Rounded.Image, "Choose cover image") {
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            if (book.source == BookEntity.SOURCE_DRIVE) {
                SheetAction(Icons.Rounded.DownloadForOffline, "Download for offline listening") { vm.downloadDriveBook(book.id) }
            }
            if (book.isExternal) {
                SheetAction(Icons.Rounded.Delete, "Remove from Ember", danger = true) { confirmRemove = true }
            }
        }
    }

    if (showLog) {
        ListenLogSheet(vm, book.id, book.title, chapters, book.durationMs) { showLog = false }
    }
    if (exportNotes) ExportNotesDialog(vm, book.id) { exportNotes = false }
    if (coverSearch) {
        CoverSearchSheet(
            vm = vm,
            initialQuery = book.title,
            author = book.author,
            onDismiss = { coverSearch = false },
            onPick = {
                vm.applyCoverUrl(book.id, it)
                coverSearch = false
            },
        )
    }
    if (newShelf) {
        NameDialog(title = "New shelf", placeholder = "Comfort rereads", onDismiss = { newShelf = false }) {
            vm.createShelf(it, thenAddBook = book.id)
            newShelf = false
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Remove this book?") },
            text = { Text("It disappears from Ember only. Nothing changes in ${ExternalApp.from(book.externalApp).label}.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    vm.deleteBook(book.id)
                    onDismiss()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (danger) MaterialTheme.colorScheme.error else EmberColors.TextMuted
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else EmberColors.Text)
    }
}
