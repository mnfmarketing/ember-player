package app.ember.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.BookEntity
import app.ember.podcasts.EpisodeEntity
import app.ember.ui.MainViewModel
import app.ember.ui.components.BookCover
import app.ember.ui.components.ThinProgress
import app.ember.ui.theme.EmberColors
import app.ember.util.formatClock
import app.ember.util.formatDuration
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** One box that finds books, authors, narrators, series, podcasts, episodes, bookmarks and clips. */
@Composable
fun SearchScreen(
    vm: MainViewModel,
    initialQuery: String = "",
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenCollection: (kind: String, key: String) -> Unit,
    onOpenShow: (String) -> Unit,
    onOpenCard: (Long) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var results by remember { mutableStateOf(MainViewModel.SearchResults()) }
    val podcasts by vm.podcasts.collectAsState()
    val shows = remember(podcasts) { podcasts.associateBy { it.id } }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { if (initialQuery.isEmpty()) focus.requestFocus() }
    LaunchedEffect(query) {
        delay(180)
        results = vm.searchEverything(query)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Books, authors, narrators, podcasts, notes…") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = EmberColors.SurfaceHigh,
                    unfocusedContainerColor = EmberColors.SurfaceHigh,
                ),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focus),
            )
        }

        val r = results
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            if (r.query.length >= 2 && r.isEmpty) {
                item {
                    Text(
                        "Nothing matches “${r.query}”.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = EmberColors.TextMuted,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            if (r.query.length < 2) {
                item {
                    Text(
                        "Search your whole library: titles, authors, narrators and series, podcast episodes, and the notes on your bookmarks and clips.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmberColors.TextMuted,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                }
            }
            if (r.books.isNotEmpty()) {
                item { Heading("Books", r.books.size) }
                items(r.books.take(30), key = { "b-" + it.id }) { b -> BookResult(b) { onOpenBook(b.id) } }
            }
            if (r.people.isNotEmpty() || r.series.isNotEmpty()) {
                item { Heading("People and series", r.people.size + r.series.size) }
                items(r.people, key = { "p-" + it.second + it.first }) { (name, role) ->
                    IconRow(
                        if (role == "Narrator") Icons.Rounded.RecordVoiceOver else Icons.Rounded.Person,
                        name,
                        role + " · " + vm.books.value.count { if (role == "Narrator") it.narrator == name else it.author == name }.let { "$it book${if (it == 1) "" else "s"}" },
                    ) {
                        // Narrators open as a search for their name; authors have their own page.
                        if (role == "Author") onOpenCollection("author", name) else query = name
                    }
                }
                items(r.series, key = { "s-$it" }) { s ->
                    IconRow(Icons.Rounded.CollectionsBookmark, s, "Series") { onOpenCollection("series", s) }
                }
            }
            if (r.shows.isNotEmpty()) {
                item { Heading("Podcasts", r.shows.size) }
                items(r.shows, key = { "show-" + it.id }) { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onOpenShow(p.id) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BookCover(BookEntity(id = p.id, title = p.title, coverPath = p.artworkPath, coverColor = p.color), Modifier.size(48.dp), corner = 8.dp, showBadge = false)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(p.author, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (r.episodes.isNotEmpty()) {
                item { Heading("Episodes", r.episodes.size) }
                items(r.episodes, key = { "e-" + it.id }) { ep -> EpisodeResult(ep, shows[ep.podcastId]?.title ?: "", shows[ep.podcastId]?.artworkPath, onPlay = { vm.playEpisode(ep.id) }, onOpen = { onOpenShow(ep.podcastId) }) }
            }
            if (r.clips.isNotEmpty()) {
                item { Heading("Clips", r.clips.size) }
                items(r.clips, key = { "c-" + it.id }) { clip ->
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        ClipRow(clip, onPlay = { vm.playClip(clip) }, onCard = { onOpenCard(clip.id) }, onEdit = null, onDelete = { vm.deleteClip(clip.id) }, showTitle = true)
                    }
                }
            }
            if (r.bookmarks.isNotEmpty()) {
                item { Heading("Bookmarks", r.bookmarks.size) }
                items(r.bookmarks, key = { "m-" + it.first.id }) { (m, b) ->
                    IconRow(
                        Icons.Rounded.Bookmark,
                        m.note.ifBlank { m.chapterTitle },
                        "${b.title} · ${formatClock(m.positionMs)}",
                    ) { vm.playFrom(b.id, m.positionMs) }
                }
            }
        }
    }
}

@Composable
private fun Heading(title: String, count: Int) {
    Text(
        "$title ($count)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun BookResult(b: BookEntity, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(b, Modifier.size(56.dp), corner = 8.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(b.title, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOfNotNull(
                b.author.takeIf { it.isNotBlank() },
                b.narrator.takeIf { it.isNotBlank() }?.let { "read by $it" },
                b.series.takeIf { it.isNotBlank() }?.let { s -> b.seriesIndex?.let { "$s #${formatIndex(it)}" } ?: s },
            ).joinToString(" · ")
            if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (b.status == BookEntity.STATUS_IN_PROGRESS) {
                Spacer(Modifier.size(4.dp))
                ThinProgress(b.progress, height = 2.dp)
            }
        }
    }
}

private fun formatIndex(f: Float): String = if (f % 1f == 0f) f.toInt().toString() else f.toString()

@Composable
private fun EpisodeResult(ep: EpisodeEntity, show: String, art: String?, onPlay: () -> Unit, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(BookEntity(id = ep.id, title = show, coverPath = art), Modifier.size(48.dp), corner = 8.dp, showBadge = false)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(ep.title, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    show.takeIf { it.isNotBlank() },
                    ep.publishedAt.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
                    ep.durationMs.takeIf { it > 0 }?.let { formatDuration(it) },
                    if (ep.played) "played" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayCircle, "Play", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) }
    }
}

@Composable
private fun IconRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
