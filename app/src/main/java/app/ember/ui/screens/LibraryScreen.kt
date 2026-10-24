@file:OptIn(ExperimentalMaterial3Api::class)

package app.ember.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.BookEntity
import app.ember.ui.MainViewModel
import app.ember.ui.components.BookCover
import app.ember.ui.components.BookTile
import app.ember.ui.components.EmptyState
import app.ember.ui.components.ThinProgress
import app.ember.ui.theme.EmberColors

private enum class LibraryFilter(val label: String) {
    ALL("All"), IN_PROGRESS("In progress"), NOT_STARTED("Not started"), FINISHED("Finished"), OTHER_APPS("Other apps"),
}

private enum class LibrarySort(val label: String) {
    RECENT("Recently played"), ADDED("Recently added"), TITLE("Title"), AUTHOR("Author"), PROGRESS("Progress"),
}

@Composable
fun LibraryScreen(
    vm: MainViewModel,
    onOpenBook: (String) -> Unit,
    onOpenCollection: (String, String) -> Unit,
    onAddFolder: () -> Unit,
    onAddExternal: () -> Unit,
    onAddDrive: () -> Unit = {},
    onAddTorrent: () -> Unit = {},
) {
    val books by vm.books.collectAsState()
    val shelves by vm.shelves.collectAsState()
    val links by vm.shelfLinks.collectAsState()

    var view by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(LibraryFilter.ALL) }
    var sort by rememberSaveable { mutableStateOf(LibrarySort.RECENT) }
    var addMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var newShelf by remember { mutableStateOf(false) }

    val q = query.trim().lowercase()
    val searched = if (q.isEmpty()) books else books.filter {
        it.title.lowercase().contains(q) || it.author.lowercase().contains(q) ||
            it.series.lowercase().contains(q) || it.narrator.lowercase().contains(q)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 8.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Books", style = MaterialTheme.typography.headlineLarge, color = EmberColors.Text, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = { addMenu = true }) {
                    Icon(Icons.Rounded.Add, "Add books", tint = EmberColors.Text)
                }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Add audiobook folder") }, onClick = {
                        addMenu = false
                        onAddFolder()
                    })
                    DropdownMenuItem(text = { Text("Add Google Drive folder") }, onClick = {
                        addMenu = false
                        onAddDrive()
                    })
                    DropdownMenuItem(text = { Text("Add book from another app") }, onClick = {
                        addMenu = false
                        onAddExternal()
                    })
                    DropdownMenuItem(text = { Text("Download from a torrent") }, onClick = {
                        addMenu = false
                        onAddTorrent()
                    })
                }
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search titles, authors, series") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = EmberColors.Surface,
                focusedContainerColor = EmberColors.Surface,
                unfocusedBorderColor = EmberColors.Surface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        val views = listOf("All", "Series", "Authors", "Shelves")
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            views.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = view == i,
                    onClick = { view = i },
                    shape = SegmentedButtonDefaults.itemShape(i, views.size),
                    icon = {},
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                        activeContentColor = EmberColors.Text,
                        inactiveContainerColor = EmberColors.Background,
                        inactiveContentColor = EmberColors.TextMuted,
                    ),
                ) { Text(label, maxLines = 1) }
            }
        }

        when (view) {
            0 -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        FilterChip(
                            selected = false,
                            onClick = { sortMenu = true },
                            label = { Text(sort.label) },
                            leadingIcon = { Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)) },
                        )
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            LibrarySort.entries.forEach { s ->
                                DropdownMenuItem(text = { Text(s.label) }, onClick = {
                                    sort = s
                                    sortMenu = false
                                })
                            }
                        }
                    }
                    LibraryFilter.entries.forEach { f ->
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
                val shown = searched.filter {
                    when (filter) {
                        LibraryFilter.ALL -> true
                        LibraryFilter.IN_PROGRESS -> it.status == BookEntity.STATUS_IN_PROGRESS
                        LibraryFilter.NOT_STARTED -> it.status == BookEntity.STATUS_NEW
                        LibraryFilter.FINISHED -> it.status == BookEntity.STATUS_FINISHED
                        LibraryFilter.OTHER_APPS -> it.isExternal
                    }
                }.let { list ->
                    when (sort) {
                        LibrarySort.RECENT -> list.sortedWith(compareByDescending<BookEntity> { it.lastPlayedAt }.thenByDescending { it.addedAt })
                        LibrarySort.ADDED -> list.sortedByDescending { it.addedAt }
                        LibrarySort.TITLE -> list.sortedBy { sortTitle(it.title) }
                        LibrarySort.AUTHOR -> list.sortedWith(compareBy<BookEntity> { it.author.lowercase() }.thenBy { it.series.lowercase() }.thenBy { it.seriesIndex ?: 0f })
                        LibrarySort.PROGRESS -> list.sortedByDescending { it.progress }
                    }
                }
                if (shown.isEmpty()) {
                    EmptyState(
                        title = if (books.isEmpty()) "No books yet" else "Nothing matches",
                        body = if (books.isEmpty()) "Add a folder of audiobooks to fill your library." else "Try a different search or filter.",
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(118.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(shown, key = { it.id }) { b ->
                            BookTile(b, onClick = { onOpenBook(b.id) })
                        }
                    }
                }
            }

            1 -> {
                val groups = searched.filter { it.series.isNotBlank() }.groupBy { it.series }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                if (groups.isEmpty()) {
                    EmptyState(
                        title = "No series yet",
                        body = "Open a book, tap Edit details, and add its series name and number. Ember groups them here.",
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                        items(groups.entries.toList(), key = { it.key }) { e ->
                            val list = e.value.sortedBy { it.seriesIndex ?: Float.MAX_VALUE }
                            GroupRow(
                                title = e.key,
                                subtitle = "${list.size} books · ${list.count { it.status == BookEntity.STATUS_FINISHED }} finished",
                                books = list,
                                progress = list.count { it.status == BookEntity.STATUS_FINISHED }.toFloat() / list.size,
                                onClick = { onOpenCollection("series", e.key) },
                            )
                        }
                    }
                }
            }

            2 -> {
                val groups = searched.filter { it.author.isNotBlank() }.groupBy { it.author }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                    items(groups.entries.toList(), key = { it.key }) { e ->
                        GroupRow(
                            title = e.key,
                            subtitle = "${e.value.size} ${if (e.value.size == 1) "book" else "books"}",
                            books = e.value,
                            progress = null,
                            onClick = { onOpenCollection("author", e.key) },
                        )
                    }
                }
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                    item {
                        TextButton(onClick = { newShelf = true }, modifier = Modifier.padding(4.dp)) {
                            Icon(Icons.Rounded.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text("New shelf")
                        }
                    }
                    if (shelves.isEmpty()) {
                        item {
                            Text(
                                "Shelves are your own collections, like \"Comfort rereads\" or \"Road trip\". Add books to them from a book's page.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EmberColors.TextMuted,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                    items(shelves, key = { it.id }) { shelf ->
                        val ids = links.filter { it.shelfId == shelf.id }.map { it.bookId }.toSet()
                        val list = books.filter { it.id in ids }
                        GroupRow(
                            title = shelf.name,
                            subtitle = "${list.size} ${if (list.size == 1) "book" else "books"}",
                            books = list,
                            progress = null,
                            onClick = { onOpenCollection("shelf", shelf.id.toString()) },
                            trailing = {
                                IconButton(onClick = { vm.deleteShelf(shelf.id) }) {
                                    Icon(Icons.Rounded.Delete, "Delete shelf", tint = EmberColors.TextFaint)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (newShelf) {
        NameDialog(title = "New shelf", placeholder = "Comfort rereads", onDismiss = { newShelf = false }) {
            vm.createShelf(it)
            newShelf = false
        }
    }
}

fun sortTitle(title: String): String =
    title.lowercase().removePrefix("the ").removePrefix("a ").removePrefix("an ").trim()

@Composable
private fun GroupRow(
    title: String,
    subtitle: String,
    books: List<BookEntity>,
    progress: Float?,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp)) {
            val first = books.firstOrNull()
            if (first != null) {
                BookCover(first, Modifier.size(72.dp), showBadge = false)
            } else {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(EmberColors.Surface),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(120.dp)) { ThinProgress(progress) }
            }
        }
        trailing()
    }
}

@Composable
fun NameDialog(title: String, placeholder: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmberColors.SurfaceHigh,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(placeholder) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onSave(text) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
