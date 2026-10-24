package app.ember.ui.screens

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.BookEntity
import app.ember.ui.MainViewModel
import app.ember.ui.NowPlaying
import app.ember.ui.components.BookCover
import app.ember.ui.components.BookTile
import app.ember.ui.components.CoverBackdrop
import app.ember.ui.components.EmptyState
import app.ember.ui.components.SectionHeader
import app.ember.ui.components.ThinProgress
import app.ember.ui.components.bookAccent
import app.ember.ui.theme.EmberColors
import app.ember.ui.theme.onColorFor
import app.ember.util.formatDuration
import java.time.LocalTime

@Composable
fun HomeScreen(
    vm: MainViewModel,
    compact: Boolean,
    onOpenBook: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    onAddFolder: () -> Unit,
    onAddExternal: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCollection: (String, String) -> Unit,
    onOpenLibrary: () -> Unit,
    onSearch: () -> Unit = {},
    onOpenClips: () -> Unit = {},
    onOpenUpNext: () -> Unit = {},
) {
    val books by vm.books.collectAsState()
    val clips by vm.clips.collectAsState()
    val np by vm.nowPlaying.collectAsState()
    val stats by vm.stats.collectAsState()
    val scan by vm.scanProgress.collectAsState()

    val inProgress = books.filter { it.status == BookEntity.STATUS_IN_PROGRESS && it.id != np?.book?.id }
        .sortedByDescending { it.lastPlayedAt }
    val recent = books.filter { !it.isExternal }.sortedByDescending { it.addedAt }.take(14)
    val external = books.filter { it.isExternal && it.status != BookEntity.STATUS_FINISHED }
    val series = books.filter { it.series.isNotBlank() }.groupBy { it.series }
        .entries.sortedByDescending { e -> e.value.maxOf { it.lastPlayedAt } }
    val finished = books.filter { it.status == BookEntity.STATUS_FINISHED }.sortedByDescending { it.finishedAt }.take(14)

    // While the big "Continue listening" card is on screen, the mini player below would only repeat it.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val heroShown by androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf {
            listState.layoutInfo.visibleItemsInfo.any { it.key == "hero" && it.offset + it.size > it.size / 3 }
        }
    }
    androidx.compose.runtime.LaunchedEffect(heroShown) { vm.setHomeHeroShown(heroShown) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.setHomeHeroShown(false) } }

    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Just the logo, like Audible's: the flame and the name.
                Row(
                    Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) { contentDescription = "Ember" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(app.ember.R.drawable.ic_w_flame),
                        contentDescription = null,
                        modifier = Modifier.height(26.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Ember",
                        style = MaterialTheme.typography.titleLarge,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                        color = EmberColors.Text,
                    )
                }
                IconButton(onClick = onSearch) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.Search, "Search everything", tint = EmberColors.TextMuted)
                }
                if (clips.isNotEmpty()) {
                    IconButton(onClick = onOpenClips) {
                        Icon(androidx.compose.material.icons.Icons.Rounded.ContentCut, "Your clips", tint = EmberColors.TextMuted)
                    }
                }
            }
        }

        scan?.let { s ->
            item {
                Text(
                    "Scanning ${s.folder}: ${s.booksFound} found · ${s.current}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
        }

        if (books.isEmpty()) {
            item {
                EmptyState(
                    title = "Your shelf is waiting",
                    body = "Point Ember at the folder where your audiobooks live. M4B, MP3 and most audio formats work, and every subfolder becomes a book.",
                ) {
                    Button(onClick = onAddFolder) {
                        Icon(Icons.Rounded.CreateNewFolder, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add audiobook folder")
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onAddExternal) {
                        Text("Add a book from Audible, Libby or Spotify")
                    }
                }
            }
            return@LazyColumn
        }

        np?.let { current ->
            item(key = "hero") { ContinueHero(current, onOpen = onOpenPlayer, onToggle = vm::togglePlay, onDetails = { onOpenBook(current.book.id) }) }
        }

        item {
            TodayStrip(
                streak = stats.streak,
                todayMin = (stats.todayMs / 60_000).toInt(),
                goalMin = stats.dailyGoalMin,
            )
        }

        item { UpNextSection(vm, onOpenBook, onManage = onOpenUpNext) }

        if (inProgress.isNotEmpty()) {
            item { SectionHeader("Keep listening") }
            item { BookRow(inProgress, onOpenBook) }
        }

        if (external.isNotEmpty()) {
            item { SectionHeader("In your other apps", action = "Add", onAction = onAddExternal) }
            item { BookRow(external, onOpenBook) }
        }

        if (series.isNotEmpty()) {
            item { SectionHeader("Series") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(series, key = { it.key }) { e ->
                        SeriesCard(e.key, e.value.sortedBy { it.seriesIndex ?: Float.MAX_VALUE }) {
                            onOpenCollection("series", e.key)
                        }
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item { SectionHeader("Recently added", action = "See all", onAction = onOpenLibrary) }
            item { BookRow(recent, onOpenBook) }
        }

        if (finished.isNotEmpty()) {
            item { SectionHeader("Finished") }
            item { BookRow(finished, onOpenBook) }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onAddFolder, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add folder", maxLines = 1)
                }
                OutlinedButton(onClick = onAddExternal, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Other app book", maxLines = 1)
                }
            }
        }
    }
}


@Composable
private fun ContinueHero(np: NowPlaying, onOpen: () -> Unit, onToggle: () -> Unit, onDetails: () -> Unit) {
    val accent = bookAccent(np.book)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen),
    ) {
        CoverBackdrop(np.book, Modifier.matchParentSize(), strength = 0.7f)
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(
                np.book,
                Modifier
                    .width(112.dp)
                    .clickable(onClick = onDetails),
                corner = 12.dp,
                showBadge = false,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Continue listening", style = MaterialTheme.typography.labelSmall, color = accent)
                Spacer(Modifier.height(4.dp))
                Text(
                    np.book.displayTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    color = EmberColors.Text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                np.chapter?.takeIf { np.chapters.size > 1 }?.let {
                    Text(it.title, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                ThinProgress(np.progress, color = accent)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${formatDuration(np.remaining)} left",
                    style = MaterialTheme.typography.labelSmall,
                    color = EmberColors.TextMuted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(accent)
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (np.isPlaying) "Pause" else "Play",
                    tint = onColorFor(accent),
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

@Composable
private fun TodayStrip(streak: Int, todayMin: Int, goalMin: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(EmberColors.Surface)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.LocalFireDepartment,
            null,
            tint = if (streak > 0) MaterialTheme.colorScheme.primary else EmberColors.TextFaint,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when (streak) {
                    0 -> "Start a streak today"
                    1 -> "1-day streak"
                    else -> "$streak-day streak"
                },
                style = MaterialTheme.typography.titleSmall,
                color = EmberColors.Text,
            )
            val met = goalMin > 0 && todayMin >= goalMin
            Text(
                if (met) "Goal met · $todayMin min today" else "$todayMin of $goalMin min today",
                style = MaterialTheme.typography.bodySmall,
                color = if (met) MaterialTheme.colorScheme.primary else EmberColors.TextMuted,
            )
        }
        if (goalMin > 0 && todayMin >= goalMin) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Check, "Daily goal met", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
            }
        } else {
            Box(Modifier.width(96.dp)) {
                ThinProgress(if (goalMin > 0) todayMin.toFloat() / goalMin else 0f, height = 6.dp)
            }
        }
    }
}

@Composable
fun BookRow(books: List<BookEntity>, onOpenBook: (String) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(books, key = { it.id }) { b ->
            BookTile(b, onClick = { onOpenBook(b.id) }, coverSize = 132.dp)
        }
    }
}

@Composable
fun SeriesCard(name: String, books: List<BookEntity>, onClick: () -> Unit) {
    Column(
        Modifier
            .width(168.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            books.take(3).reversed().forEachIndexed { i, b ->
                val depth = minOf(books.size, 3) - 1 - i
                BookCover(
                    b,
                    Modifier
                        .size(132.dp - (depth * 10).dp)
                        .offset(x = (depth * 16).dp, y = (depth * 6).dp),
                    showBadge = false,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(name, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val done = books.count { it.status == BookEntity.STATUS_FINISHED }
        Text("${books.size} books · $done finished", style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
    }
}
