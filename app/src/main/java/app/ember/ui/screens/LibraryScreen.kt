@file:OptIn(ExperimentalMaterial3Api::class)

package app.ember.ui.screens

import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.foundation.background
import app.ember.ui.components.bookAccent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.rounded.Check
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
    onOpenDownloads: () -> Unit = {},
) {
    val books by vm.books.collectAsState()
    val shelves by vm.shelves.collectAsState()
    val links by vm.shelfLinks.collectAsState()

    var view by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    // Chips turn on and off; several can be on (In progress + Not started hides finished books).
    // Remembered across launches.
    val settings by vm.settings.collectAsState()
    val picked = settings.libraryFilter.split(',').mapNotNull { n -> LibraryFilter.entries.firstOrNull { it.name == n } }
        .filter { it != LibraryFilter.ALL }.toSet()
    val sort = LibrarySort.entries.firstOrNull { it.name == settings.librarySort } ?: LibrarySort.RECENT
    var addMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var peopleMenu by remember { mutableStateOf(false) }
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
            DownloadsButton(vm, onOpenDownloads)
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

        // Status chips (and the sort, on All) sit above All, Series and Authors alike.
        val statuses = buildSet {
            if (LibraryFilter.IN_PROGRESS in picked) add(BookEntity.STATUS_IN_PROGRESS)
            if (LibraryFilter.NOT_STARTED in picked) add(BookEntity.STATUS_NEW)
            if (LibraryFilter.FINISHED in picked) add(BookEntity.STATUS_FINISHED)
        }
        // Status chips add together (any of them); Other apps narrows to books from other apps.
        val filtered = searched.filter {
            (statuses.isEmpty() || it.status in statuses) &&
                (LibraryFilter.OTHER_APPS !in picked || it.isExternal)
        }
        if (view <= 2) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (view <= 1) {
                    Box {
                        FilterChip(
                            selected = false,
                            onClick = { sortMenu = true },
                            label = { Text(sort.label) },
                            leadingIcon = { Icon(Icons.Rounded.Sort, null, Modifier.size(18.dp)) },
                        )
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            LibrarySort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    trailingIcon = { if (s == sort) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) },
                                    onClick = {
                                        vm.setLibrarySort(s.name)
                                        sortMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                if (view == 2) {
                    // Authors or narrators.
                    Box {
                        FilterChip(
                            selected = false,
                            onClick = { peopleMenu = true },
                            label = { Text(if (settings.byNarrator) "Narrators" else "Authors") },
                            leadingIcon = { Icon(if (settings.byNarrator) Icons.Rounded.RecordVoiceOver else Icons.Rounded.Person, null, Modifier.size(18.dp)) },
                            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(18.dp)) },
                        )
                        DropdownMenu(expanded = peopleMenu, onDismissRequest = { peopleMenu = false }) {
                            listOf(false to "Authors", true to "Narrators").forEach { (n, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    trailingIcon = { if (settings.byNarrator == n) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) },
                                    onClick = {
                                        vm.setByNarrator(n)
                                        peopleMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                LibraryFilter.entries.forEach { f ->
                    FilterChip(
                        selected = if (f == LibraryFilter.ALL) picked.isEmpty() else f in picked,
                        onClick = {
                            val next = when (f) {
                                LibraryFilter.ALL -> emptySet()
                                in picked -> picked - f
                                else -> picked + f
                            }
                            vm.setLibraryFilter(LibraryFilter.entries.filter { it in next }.joinToString(",") { it.name })
                        },
                        label = { Text(f.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }

        when (view) {
            0 -> {
                // A series with two or more matching books shows as one stack (not while searching).
                val stack = false // All always shows every book; series stacks live on the Series tab
                val entries: List<LibItem> = if (!stack) {
                    filtered.map { LibItem.One(it) }
                } else {
                    val (inSeries, loose) = filtered.partition { it.series.isNotBlank() }
                    loose.map { LibItem.One(it) } + inSeries.groupBy { it.series.trim().lowercase() }.values.map { g ->
                        if (g.size == 1) LibItem.One(g[0]) else LibItem.Stack(g[0].series.trim(), g.sortedBy { it.seriesIndex ?: Float.MAX_VALUE })
                    }
                }
                val shown = when (sort) {
                    LibrarySort.RECENT -> entries.sortedWith(compareByDescending<LibItem> { it.lastPlayedAt }.thenByDescending { it.addedAt })
                    LibrarySort.ADDED -> entries.sortedByDescending { it.addedAt }
                    LibrarySort.TITLE -> entries.sortedBy { sortTitle(it.title) }
                    LibrarySort.AUTHOR -> entries.sortedWith(compareBy<LibItem> { it.author.lowercase() }.thenBy { it.title.lowercase() })
                    LibrarySort.PROGRESS -> entries.sortedByDescending { it.progress }
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
                        items(shown, key = { it.key }) { e ->
                            when (e) {
                                is LibItem.One -> BookTile(e.book, onClick = { onOpenBook(e.book.id) })
                                is LibItem.Stack -> SeriesStackTile(
                                    name = e.name,
                                    books = e.books,
                                    all = books.filter { it.series.trim().equals(e.name, ignoreCase = true) },
                                    onClick = { onOpenCollection("series", e.books[0].series) },
                                )
                            }
                        }
                    }
                }
            }

            1 -> {
                // A series shows when any of its books match the chips; counts are for the whole series.
                val matching = filtered.filter { it.series.isNotBlank() }.map { it.series }.toSet()
                val groups = searched.filter { it.series in matching }.groupBy { it.series }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
                if (groups.isEmpty()) {
                    EmptyState(
                        title = if (books.none { it.series.isNotBlank() }) "No series yet" else "Nothing matches",
                        body = if (books.none { it.series.isNotBlank() }) "Open a book, tap Edit details, and add its series name and number. Ember groups them here." else "Try a different search or filter.",
                    )
                } else {
                    // Each series as a stack of its covers, with how many books and how many you've finished.
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(118.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Same sort as All: by the series' most recent play, newest book, name, author or how far along.
                        val ordered = groups.entries.toList().let { g ->
                            when (sort) {
                                LibrarySort.RECENT -> g.sortedByDescending { e -> e.value.maxOf { it.lastPlayedAt } }
                                LibrarySort.ADDED -> g.sortedByDescending { e -> e.value.maxOf { it.addedAt } }
                                LibrarySort.TITLE -> g.sortedBy { sortTitle(it.key) }
                                LibrarySort.AUTHOR -> g.sortedWith(compareBy({ e -> e.value.firstOrNull { it.author.isNotBlank() }?.author.orEmpty().lowercase() }, { it.key.lowercase() }))
                                LibrarySort.PROGRESS -> g.sortedByDescending { e -> e.value.sumOf { it.progress.toDouble() } / e.value.size }
                            }
                        }
                        items(ordered, key = { "series:" + it.key }) { e ->
                            val list = e.value.sortedBy { it.seriesIndex ?: Float.MAX_VALUE }
                            SeriesStackTile(
                                name = e.key,
                                books = list,
                                all = list,
                                onClick = { onOpenCollection("series", e.key) },
                            )
                        }
                    }
                }
            }

            2 -> {
                // People: authors, or narrators (a book with two narrators shows under both).
                val keyOf: (BookEntity) -> List<String> = if (settings.byNarrator) { b -> narratorsOf(b) } else { b -> listOfNotNull(b.author.takeIf { it.isNotBlank() }) }
                val matching = filtered.flatMap(keyOf).toSet()
                val groups = searched.flatMap { b -> keyOf(b).map { it to b } }
                    .filter { it.first in matching }
                    .groupBy({ it.first }, { it.second })
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                if (groups.isEmpty()) {
                    EmptyState(title = "Nothing matches", body = "Try a different search or filter.")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                        items(groups.entries.toList(), key = { it.key }) { e ->
                            GroupRow(
                                title = e.key,
                                subtitle = "${e.value.size} ${if (e.value.size == 1) "book" else "books"}",
                                books = e.value,
                                progress = null,
                                onClick = { onOpenCollection(if (settings.byNarrator) "narrator" else "author", e.key) },
                            )
                        }
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

/** One tile on Books › All: a book, or a series shown as a stack. */
private sealed class LibItem {
    abstract val key: String
    abstract val title: String
    abstract val author: String
    abstract val lastPlayedAt: Long
    abstract val addedAt: Long
    abstract val progress: Float

    data class One(val book: BookEntity) : LibItem() {
        override val key get() = book.id
        override val title get() = book.title
        override val author get() = book.author
        override val lastPlayedAt get() = book.lastPlayedAt
        override val addedAt get() = book.addedAt
        override val progress get() = book.progress
    }

    data class Stack(val name: String, val books: List<BookEntity>) : LibItem() {
        override val key get() = "series:" + name.lowercase()
        override val title get() = name
        override val author get() = books.firstOrNull { it.author.isNotBlank() }?.author.orEmpty()
        override val lastPlayedAt get() = books.maxOf { it.lastPlayedAt }
        override val addedAt get() = books.maxOf { it.addedAt }
        override val progress get() = books.maxOf { it.progress }
    }
}

/** The cover on top of a series stack: the book you're in, else the next one you haven't started. */
internal fun stackFront(books: List<BookEntity>): BookEntity =
    books.filter { it.status == BookEntity.STATUS_IN_PROGRESS }.maxByOrNull { it.lastPlayedAt }
        ?: books.firstOrNull { it.status == BookEntity.STATUS_NEW }
        ?: books.first()

@Composable
private fun SeriesStackTile(name: String, books: List<BookEntity>, all: List<BookEntity>, onClick: () -> Unit) {
    val front = stackFront(books)
    val behind = books.filter { it.id != front.id }.take(2)
    val total = all.size.coerceAtLeast(books.size)
    val done = all.count { it.status == BookEntity.STATUS_FINISHED }
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = "Open series", onClick = onClick)
            .padding(4.dp),
    ) {
        // Two covers peek out above and to the right, so it reads as a pile at a glance.
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp)) {
            behind.getOrNull(1)?.let {
                BookCover(it, Modifier.fillMaxWidth().offset(8.dp, (-8).dp).graphicsLayer { alpha = 0.45f }, showBadge = false)
            }
            behind.getOrNull(0)?.let {
                BookCover(it, Modifier.fillMaxWidth().offset(4.dp, (-4).dp).graphicsLayer { alpha = 0.75f }, showBadge = false)
            }
            BookCover(front, Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(10.dp)), showBadge = false)
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC0E0D10))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CollectionsBookmark, null, Modifier.size(12.dp), tint = Color.White)
                Spacer(Modifier.width(4.dp))
                Text("${books.size}", style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (done > 0 || books.any { it.status == BookEntity.STATUS_IN_PROGRESS }) {
            ThinProgress(done.toFloat() / total, color = bookAccent(front))
            Spacer(Modifier.height(6.dp))
        }
        Text(name, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "$total books · $done finished",
            style = MaterialTheme.typography.bodySmall,
            color = EmberColors.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
