@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package app.ember.ui.screens

import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.ui.text.font.FontWeight
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DownloadDone
import app.ember.podcasts.EpisodeEntity
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.BookEntity
import app.ember.data.LibraryScanner
import app.ember.ui.ExternalApp
import app.ember.ui.MainViewModel
import app.ember.ui.components.BookCover
import app.ember.ui.components.CoverBackdrop
import app.ember.ui.components.RatingBar
import app.ember.ui.components.ThinProgress
import app.ember.ui.components.bookAccent
import app.ember.ui.theme.EmberColors
import app.ember.ui.theme.onColorFor
import app.ember.util.formatClock
import app.ember.util.formatDuration
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun BookDetailScreen(
    vm: MainViewModel,
    bookId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    onEditChapters: (String) -> Unit = {},
    onOpenCollection: (String, String) -> Unit,
    onPlayed: () -> Unit,
    onOpenRoute: (String) -> Unit = {},
) {
    val bookState by remember(bookId) { vm.observeBook(bookId) }.collectAsState(initial = null)
    val book = bookState ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val chapters by remember(bookId) { vm.observeChapters(bookId) }.collectAsState(initial = emptyList())
    val bookmarks by remember(bookId) { vm.observeBookmarks(bookId) }.collectAsState(initial = emptyList())
    val clips by remember(bookId) { vm.observeClipsFor(bookId) }.collectAsState(initial = emptyList())
    var editingClip by remember { mutableStateOf<app.ember.data.ClipEntity?>(null) }
    val shelves by vm.shelves.collectAsState()
    val links by vm.shelfLinks.collectAsState()
    val np by vm.nowPlaying.collectAsState()
    val context = LocalContext.current

    var menu by remember { mutableStateOf(false) }
    var coverSearch by remember { mutableStateOf(false) }
    var shelfDialog by remember { mutableStateOf(false) }
    var progressDialog by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var exportNotes by remember { mutableStateOf(false) }
    val settings by vm.settings.collectAsState()
    var infoSheet by remember { mutableStateOf(false) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.applyCoverImage(book.id, uri)
    }

    val accent = bookAccent(book)
    val myShelves = links.filter { it.bookId == book.id }.map { it.shelfId }.toSet()
    val isCurrent = np?.book?.id == book.id

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var chaptersOpen by androidx.compose.runtime.saveable.rememberSaveable(bookId) { mutableStateOf(true) }
    var savedOpen by androidx.compose.runtime.saveable.rememberSaveable(bookId) { mutableStateOf(false) }
    var detailsOpen by androidx.compose.runtime.saveable.rememberSaveable(bookId) { mutableStateOf(false) }
    var finishOpen by androidx.compose.runtime.saveable.rememberSaveable(bookId) { mutableStateOf(false) }
    // Where "Bookmarks & clips" sits in the list: after the header, the main block and the chapters.
    val savedHeaderIndex = 2 + if (!book.isExternal && chapters.size > 1) 1 + (if (chaptersOpen) chapters.size else 0) else 0

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth()) {
                CoverBackdrop(book, Modifier.matchParentSize())
                Column(
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
                        Spacer(Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = EmberColors.Text) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Edit details") }, onClick = {
                                    menu = false
                                    onEdit(book.id)
                                })
                                if (!book.isExternal) {
                                    DropdownMenuItem(text = { Text("Edit chapters") }, onClick = {
                                        menu = false
                                        onEditChapters(book.id)
                                    })
                                }
                                if (!book.isExternal) {
                                    DropdownMenuItem(text = { Text("Get chapter names") }, onClick = {
                                        menu = false
                                        onOpenRoute("chapters/${book.id}?lookup=true")
                                    })
                                }
                                DropdownMenuItem(text = { Text("Find book info") }, onClick = {
                                    menu = false
                                    infoSheet = true
                                })
                                DropdownMenuItem(text = { Text("Find cover online") }, onClick = {
                                    menu = false
                                    coverSearch = true
                                })
                                DropdownMenuItem(text = { Text("Choose cover image") }, onClick = {
                                    menu = false
                                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                })
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text("Export notes") }, onClick = {
                                    menu = false
                                    exportNotes = true
                                })
                                if (book.status == BookEntity.STATUS_FINISHED) {
                                    DropdownMenuItem(text = { Text("Mark as not finished") }, onClick = {
                                        menu = false
                                        vm.resetProgress(book.id)
                                    })
                                } else {
                                    DropdownMenuItem(text = { Text("Mark as finished") }, onClick = {
                                        menu = false
                                        vm.markFinished(book.id)
                                    })
                                }
                                if (book.positionMs > 0 && book.status != BookEntity.STATUS_FINISHED) {
                                    DropdownMenuItem(text = { Text("Reset progress") }, onClick = {
                                        menu = false
                                        vm.resetProgress(book.id)
                                    })
                                }
                                if (book.isExternal) {
                                    DropdownMenuItem(text = { Text("Remove from Ember") }, onClick = {
                                        menu = false
                                        confirmRemove = true
                                    })
                                }
                            }
                        }
                    }
                    BookCover(
                        book,
                        Modifier
                            .width(220.dp)
                            .shadow(28.dp, RoundedCornerShape(14.dp)),
                        corner = 14.dp,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        book.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = EmberColors.Text,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    if (book.author.isNotBlank()) {
                        Text(
                            book.author,
                            style = MaterialTheme.typography.titleSmall,
                            color = accent,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenCollection("author", book.author) }
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    if (book.narrator.isNotBlank()) {
                        // Tap a narrator for everything they've read.
                        val first = narratorsOf(book).firstOrNull()
                        Text(
                            "Read by ${book.narrator}",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmberColors.TextMuted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .then(if (first != null) Modifier.clickable(onClickLabel = "Books read by $first") { onOpenCollection("narrator", first) } else Modifier)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                    if (book.series.isNotBlank()) {
                        val num = book.seriesIndex?.let { if (it % 1f == 0f) " · Book ${it.toInt()}" else " · Book $it" } ?: ""
                        Text(
                            book.series + num,
                            style = MaterialTheme.typography.bodySmall,
                            color = EmberColors.Text,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(EmberColors.Wash.copy(alpha = 0.10f))
                                .clickable { onOpenCollection("series", book.series) }
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }

        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                if (book.isExternal) {
                    val app = ExternalApp.from(book.externalApp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { vm.openExternal(context, book) },
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onColorFor(accent)),
                            modifier = Modifier.weight(1f).height(52.dp),
                        ) {
                            Icon(Icons.Rounded.OpenInNew, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Open in ${if (app == ExternalApp.OTHER) "app" else app.label}")
                        }
                        OutlinedButton(onClick = { progressDialog = true }, modifier = Modifier.height(52.dp)) {
                            Text("Log progress")
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    ThinProgress(book.progress, color = accent, height = 4.dp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${(book.progress * 100).toInt()}% complete · listened in ${app.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmberColors.TextMuted,
                    )
                } else {
                    val label = when {
                        isCurrent && np?.isPlaying == true -> "Playing"
                        book.status == BookEntity.STATUS_FINISHED -> "Listen again"
                        book.positionMs > 0 -> "Resume"
                        else -> "Start listening"
                    }
                    // One big button, and Up Next beside it.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                vm.play(book.id)
                                onPlayed()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onColorFor(accent)),
                            modifier = Modifier.weight(1f).height(54.dp),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.width(10.dp))
                        val queued = settings.upNext.contains(book.id)
                        Box(
                            Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = if (queued) 0.30f else 0.14f))
                                .clickable(onClickLabel = if (queued) "Remove from Up Next" else "Add to Up Next") {
                                    if (queued) vm.removeFromUpNext(book.id) else vm.addToUpNext(listOf(book.id))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (queued) Icons.Rounded.PlaylistAddCheck else Icons.Rounded.PlaylistAdd,
                                if (queued) "In Up Next" else "Add to Up Next",
                                tint = EmberColors.Text,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    ThinProgress(book.progress, color = accent, height = 4.dp)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Text(
                            when (book.status) {
                                BookEntity.STATUS_FINISHED -> "Finished"
                                BookEntity.STATUS_NEW -> "Not started"
                                else -> "${(book.progress * 100).toInt()}%"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = EmberColors.TextMuted,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (book.status == BookEntity.STATUS_IN_PROGRESS) "${formatDuration(book.remainingMs)} left of ${formatDuration(book.durationMs)}" else formatDuration(book.durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = EmberColors.TextMuted,
                        )
                    }
                    // The chapter you're in; tap to jump to the list.
                    val pos = if (isCurrent) np?.position ?: book.positionMs else book.positionMs
                    val chIdx = chapters.indexOfLast { it.startMs <= pos }
                    if (book.status == BookEntity.STATUS_IN_PROGRESS && chapters.size > 1 && chIdx >= 0) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClickLabel = "Show chapters") {
                                    chaptersOpen = true
                                    scope.launch { listState.animateScrollToItem(2) }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.List, null, tint = EmberColors.Text, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                chapters[chIdx].title,
                                style = MaterialTheme.typography.titleSmall,
                                color = EmberColors.Text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Text(" · ${chIdx + 1} of ${chapters.size}", style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Rounded.ChevronRight, null, tint = EmberColors.TextFaint, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                if (book.source == BookEntity.SOURCE_DRIVE) {
                    Spacer(Modifier.height(10.dp))
                    DriveDownloadRow(vm, book.id)
                }
                if (LibraryScanner.isDriveAppUri(book.sourceUri)) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "This book comes through the Drive app, so it fully downloads before playing. Connect Google Drive in Settings to stream it instantly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmberColors.TextMuted,
                    )
                }
                // Four quick ones, like the player's bottom row.
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = EmberColors.TextFaint.copy(alpha = 0.18f))
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    if (!book.isExternal) PageAction(Icons.Rounded.History, "Listening log") { showLog = true }
                    if (book.hasPdf) PageAction(Icons.Rounded.PictureAsPdf, "PDF") { onOpenRoute("pdf/${book.id}") }
                    PageAction(Icons.Rounded.CollectionsBookmark, if (myShelves.isEmpty()) "Shelves" else "Shelves · ${myShelves.size}") { shelfDialog = true }
                    if (!book.isExternal) {
                        PageAction(Icons.Rounded.Bookmarks, "Saved · ${bookmarks.size + clips.size}") {
                            savedOpen = true
                            scope.launch { listState.animateScrollToItem(savedHeaderIndex) }
                        }
                    }
                }
                HorizontalDivider(color = EmberColors.TextFaint.copy(alpha = 0.18f))
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Your rating", style = MaterialTheme.typography.labelLarge, color = EmberColors.TextMuted, modifier = Modifier.weight(1f))
                    RatingBar(book.rating, onRate = { vm.setRating(book.id, it) }, color = accent)
                }
                if (book.description.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    var more by remember(book.id) { mutableStateOf(false) }
                    Text(
                        book.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmberColors.TextMuted,
                        maxLines = if (more) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClickLabel = if (more) "Show less" else "Show more") { more = !more },
                    )
                    if (!more && book.description.length > 220) {
                        Text("More", style = MaterialTheme.typography.labelLarge, color = accent, modifier = Modifier.clickable { more = true }.padding(top = 4.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // Sections that open and close. Chapters comes first (index 2, for the chapter line above).
        if (!book.isExternal && chapters.size > 1) {
            item(key = "chaptersHeader") { SectionToggle("Chapters", "${chapters.size}", chaptersOpen) { chaptersOpen = !chaptersOpen } }
            if (chaptersOpen) {
                // Where you are in the book: heard chapters are dimmed with a check, the one you're in is highlighted.
                val pos = when {
                    isCurrent -> np?.position ?: book.positionMs
                    book.status == BookEntity.STATUS_FINISHED -> book.durationMs
                    else -> book.positionMs
                }
                val started = book.status != BookEntity.STATUS_NEW || pos > 0
                itemsIndexed(chapters, key = { _, ch -> "ch${ch.idx}" }) { i, ch ->
                    val current = started && book.status != BookEntity.STATUS_FINISHED && pos >= ch.startMs && pos < ch.endMs
                    val heard = started && !current && ch.endMs <= pos
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (current) accent.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable { vm.playFrom(book.id, ch.startMs) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(36.dp)) {
                            if (heard) {
                                Icon(Icons.Rounded.Check, "Heard", tint = EmberColors.TextFaint, modifier = Modifier.size(16.dp))
                            } else {
                                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = if (current) accent else EmberColors.TextFaint)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                ch.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                                color = when {
                                    current -> accent
                                    heard -> EmberColors.TextMuted
                                    else -> EmberColors.Text
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (current && ch.endMs > ch.startMs) {
                                Spacer(Modifier.height(6.dp))
                                ThinProgress((pos - ch.startMs).toFloat() / (ch.endMs - ch.startMs), color = accent, height = 2.dp)
                            }
                        }
                        Text(formatClock(ch.endMs - ch.startMs), style = MaterialTheme.typography.labelSmall, color = EmberColors.TextMuted)
                    }
                }
            }
        }

        if (!book.isExternal) {
            item(key = "savedHeader") {
                editingClip?.let { clip ->
                    EditClipNoteDialog(clip, onDismiss = { editingClip = null }) {
                        vm.updateClipNote(clip.id, it)
                        editingClip = null
                    }
                }
                SectionToggle("Bookmarks & clips", "${bookmarks.size + clips.size}", savedOpen) { savedOpen = !savedOpen }
            }
            if (savedOpen) {
                if (bookmarks.isEmpty() && clips.isEmpty()) {
                    item {
                        Text(
                            "Nothing saved yet. Tap Bookmark or Clip in the player while you listen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = EmberColors.TextMuted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
                items(bookmarks, key = { "bm${it.id}" }) { bm ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.playFrom(book.id, bm.positionMs) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Bookmark, null, tint = accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                formatClock(bm.positionMs) + if (bm.chapterTitle.isNotBlank()) " · ${bm.chapterTitle}" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = EmberColors.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (bm.note.isNotBlank()) Text(bm.note, style = MaterialTheme.typography.bodyMedium, color = EmberColors.Text)
                        }
                    }
                }
                items(clips, key = { "clip${it.id}" }) { clip ->
                    Box(Modifier.padding(horizontal = 8.dp)) {
                        ClipRow(
                            clip,
                            onPlay = { vm.playClip(clip) },
                            onCard = { onOpenRoute("card/clip/${clip.id}") },
                            onEdit = { editingClip = clip },
                            onDelete = { vm.deleteClip(clip.id) },
                        )
                    }
                }
            }
        }

        item(key = "detailsHeader") { SectionToggle("Details", null, detailsOpen) { detailsOpen = !detailsOpen } }
        if (detailsOpen) {
            item {
                val listened by androidx.compose.runtime.produceState(0L, book.id, book.lastPlayedAt) { value = vm.listenedMs(book.id) }
                Column(Modifier.padding(horizontal = 20.dp)) {
                    val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
                    if (!book.isExternal) {
                        DetailLine("Length", formatDuration(book.durationMs))
                        if (listened >= 60_000) DetailLine("Time listened", formatDuration(listened))
                    }
                    DetailLine("Added", df.format(Date(book.addedAt)))
                    if (book.finishedAt > 0) DetailLine("Finished", df.format(Date(book.finishedAt)))
                    if (!book.isExternal) DetailLine("Speed", app.ember.util.formatSpeed(book.speed))
                    DetailLine(
                        "Source",
                        when {
                            book.isExternal -> ExternalApp.from(book.externalApp).label
                            book.source == BookEntity.SOURCE_DRIVE -> "Google Drive"
                            else -> "On this phone"
                        },
                    )
                }
            }
        }

        item(key = "finishHeader") { SectionToggle("When you finish", "StoryGraph · Goodreads", finishOpen) { finishOpen = !finishOpen } }
        if (finishOpen) {
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.openOnReadingSite(context, book, "storygraph") }) {
                        Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("StoryGraph")
                    }
                    OutlinedButton(onClick = { vm.openOnReadingSite(context, book, "goodreads") }) {
                        Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Goodreads")
                    }
                }
            }
        }
    }

    if (infoSheet) BookInfoSheet(vm, book.id) { infoSheet = false }
    if (exportNotes) ExportNotesDialog(vm, book.id) { exportNotes = false }
    if (showLog) {
        ListenLogSheet(vm, book.id, book.title, chapters, book.durationMs) { showLog = false }
    }
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

    if (shelfDialog) {
        var creating by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { shelfDialog = false },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Shelves") },
            text = {
                Column {
                    if (shelves.isEmpty()) {
                        Text("No shelves yet. Create one to start a collection.", color = EmberColors.TextMuted)
                    }
                    shelves.forEach { s ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { vm.setOnShelf(s.id, book.id, s.id !in myShelves) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = s.id in myShelves, onCheckedChange = { vm.setOnShelf(s.id, book.id, it) })
                            Text(s.name, color = EmberColors.Text)
                        }
                    }
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("New shelf")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { shelfDialog = false }) { Text("Done") } },
        )
        if (creating) {
            NameDialog(title = "New shelf", placeholder = "Comfort rereads", onDismiss = { creating = false }) {
                vm.createShelf(it, thenAddBook = book.id)
                creating = false
            }
        }
    }

    if (progressDialog) {
        ExternalProgressDialog(
            book = book,
            onDismiss = { progressDialog = false },
            onSave = { percent, minutes ->
                vm.setExternalProgress(book.id, percent)
                vm.logListening(book.id, minutes)
                progressDialog = false
            },
        )
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
                    onBack()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DetailHeader(title: String) {
    Column {
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = EmberColors.SurfaceHigh, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = EmberColors.Text,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = EmberColors.TextMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = EmberColors.Text)
    }
}

@Composable
private fun ExternalProgressDialog(book: BookEntity, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var percent by remember { mutableFloatStateOf(book.positionMs.toFloat().coerceIn(0f, 100f)) }
    var minutes by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmberColors.SurfaceHigh,
        title = { Text("Log progress") },
        text = {
            Column {
                Text("${percent.toInt()}% complete", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Slider(value = percent, onValueChange = { percent = Math.round(it).toFloat() }, valueRange = 0f..100f)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { v -> minutes = v.filter { it.isDigit() }.take(4) },
                    label = { Text("Minutes listened (optional)") },
                    supportingText = { Text("Counts toward your streak and stats") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(percent.toInt(), minutes.toIntOrNull() ?: 0) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CoverSearchSheet(
    vm: MainViewModel,
    initialQuery: String,
    author: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun search() {
        loading = true
        scope.launch {
            results = vm.searchCovers(query, author)
            loading = false
        }
    }
    LaunchedEffect(Unit) { search() }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EmberColors.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(600.dp)
                .padding(horizontal = 16.dp),
        ) {
            Text("Find a cover", style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
            Text("From Open Library", style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { search() }) { Icon(Icons.Rounded.Search, "Search") }
            }
            Spacer(Modifier.height(12.dp))
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                results.isNullOrEmpty() -> Text(
                    "No covers found. Try a shorter title.",
                    color = EmberColors.TextMuted,
                    modifier = Modifier.padding(16.dp),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(100.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    items(results.orEmpty()) { url ->
                        AsyncImage(
                            model = url,
                            contentDescription = "Cover option",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(EmberColors.SurfaceHigh)
                                .clickable { onPick(url) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DriveDownloadRow(vm: MainViewModel, bookId: String) {
    val downloads by vm.driveDownloads.collectAsState()
    val ids by produceState(initialValue = emptyList<String>(), bookId) { value = vm.trackFileIds(bookId) }
    val mine = downloads.filter { it.targetId in ids }
    val total = ids.size
    val done = mine.count { it.state == EpisodeEntity.DL_DONE }
    val running = mine.count { it.state == EpisodeEntity.DL_RUNNING }
    val saved = total > 0 && done >= total
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (saved) Icons.Rounded.DownloadDone else Icons.Rounded.CloudQueue,
            null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                saved -> "Saved for offline listening"
                running > 0 -> "Downloading: $done of $total files saved"
                else -> "Streaming from Google Drive"
            },
            style = MaterialTheme.typography.bodySmall,
            color = EmberColors.TextMuted,
            modifier = Modifier.weight(1f),
        )
        when {
            saved || running > 0 -> TextButton(onClick = { vm.removeDriveBookDownload(bookId) }) {
                Text(if (saved) "Remove download" else "Cancel")
            }
            else -> TextButton(onClick = { vm.downloadDriveBook(bookId) }) { Text("Download") }
        }
    }
}


/** A section heading that opens and closes what's under it. */
@Composable
private fun SectionToggle(title: String, extra: String?, open: Boolean, onToggle: () -> Unit) {
    Column {
        HorizontalDivider(color = EmberColors.TextFaint.copy(alpha = 0.18f))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (open) "Close $title" else "Open $title", onClick = onToggle)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
            if (extra != null) {
                Spacer(Modifier.width(8.dp))
                Text(extra, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
            Spacer(Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = EmberColors.TextMuted)
        }
    }
}

/** An icon with a label under it (the book page's quick row). */
@Composable
private fun PageAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = EmberColors.Text, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = EmberColors.TextMuted, maxLines = 1)
    }
}
