package app.ember.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ember.data.BookEntity
import app.ember.ui.MainViewModel
import app.ember.ui.components.BookTile
import app.ember.ui.components.ThinProgress
import app.ember.ui.theme.EmberColors
import app.ember.util.formatDuration

/** A series, an author, or one of your shelves. */
@Composable
fun CollectionScreen(vm: MainViewModel, kind: String, key: String, onBack: () -> Unit, onOpenBook: (String) -> Unit) {
    val books by vm.books.collectAsState()
    val shelves by vm.shelves.collectAsState()
    val links by vm.shelfLinks.collectAsState()

    val (title, label, list) = when (kind) {
        "series" -> Triple(key, "Series", books.filter { it.series == key }.sortedBy { it.seriesIndex ?: Float.MAX_VALUE })
        "author" -> Triple(key, "Author", books.filter { it.author == key }.sortedWith(compareBy<BookEntity> { it.series.lowercase() }.thenBy { it.seriesIndex ?: 0f }))
        else -> {
            val id = key.toLongOrNull()
            val ids = links.filter { it.shelfId == id }.map { it.bookId }.toSet()
            Triple(shelves.firstOrNull { it.id == id }?.name ?: "Shelf", "Shelf", books.filter { it.id in ids })
        }
    }
    val finished = list.count { it.status == BookEntity.STATUS_FINISHED }
    val totalMs = list.filter { !it.isExternal }.sumOf { it.durationMs }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(118.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
                    Text(label, style = MaterialTheme.typography.labelLarge, color = EmberColors.TextMuted)
                }
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineLarge, color = EmberColors.Text)
                    val parts = mutableListOf("${list.size} ${if (list.size == 1) "book" else "books"}", "$finished finished")
                    if (totalMs > 0) parts += formatDuration(totalMs) + " of listening"
                    Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
                    if (kind == "series" && list.isNotEmpty()) {
                        ThinProgress(finished.toFloat() / list.size, modifier = Modifier.padding(top = 12.dp), height = 5.dp)
                    }
                }
            }
        }
        items(list, key = { it.id }) { b ->
            BookTile(b, onClick = { onOpenBook(b.id) })
        }
    }
}
