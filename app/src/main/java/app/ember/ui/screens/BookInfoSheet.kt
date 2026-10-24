@file:OptIn(ExperimentalMaterial3Api::class)

package app.ember.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.BookEntity
import app.ember.data.BookInfo
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import app.ember.util.formatDuration
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Find book info: matches from Audible's catalog for this book. Pick one, choose which
 * details to use (series and number, narrator, description, cover…), and apply.
 */
@Composable
fun BookInfoSheet(vm: MainViewModel, bookId: String, onDismiss: () -> Unit) {
    val bookState by remember(bookId) { vm.observeBook(bookId) }.collectAsState(initial = null)
    val book = bookState ?: return
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(listOf(BookInfo.cleanTitle(book.title), book.author.substringBefore(',')).filter { it.isNotBlank() }.joinToString(" ")) }
    var results by remember { mutableStateOf<List<BookInfo.Match>?>(null) }
    var picked by remember { mutableStateOf<BookInfo.Match?>(null) }
    val tall = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

    fun search(keywords: String?) {
        results = null
        scope.launch { results = vm.searchBookInfo(book.title, book.author, keywords) }
    }
    LaunchedEffect(bookId) { search(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = EmberColors.Surface,
    ) {
        Column(Modifier.fillMaxWidth().height(tall)) {
            val p = picked
            if (p == null) {
                Text("Find book info", style = MaterialTheme.typography.titleLarge, color = EmberColors.Text, modifier = Modifier.padding(horizontal = 20.dp))
                Text(
                    "From Audible's catalog. Pick the edition that matches yours.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmberColors.TextMuted,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Title and author") },
                    trailingIcon = { IconButton(onClick = { search(query) }) { Icon(Icons.Rounded.Search, "Search") } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search(query) }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                Spacer(Modifier.height(8.dp))
                val list = results
                when {
                    list == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() -> Text(
                        "No matches. Try fewer words, or just the title.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmberColors.TextMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(list, key = { it.asin.ifBlank { it.title } }) { m -> MatchRow(m, book) { picked = m } }
                    }
                }
            } else {
                ApplyInfo(book, p, onBack = { picked = null }) { fields ->
                    vm.applyBookInfo(book.id, p, fields)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun MatchRow(m: BookInfo.Match, book: BookEntity, onClick: () -> Unit) {
    val lengthNote = if (book.durationMs > 0 && !book.isExternal && m.lengthMs > 0) {
        val diff = kotlin.math.abs(m.lengthMs - book.durationMs).toDouble() / book.durationMs
        if (diff < 0.05) " · same length as yours" else ""
    } else {
        ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(EmberColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = m.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(EmberColors.Surface),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.title, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (m.authors.isNotBlank()) Text(m.authors, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (m.narrators.isNotBlank()) Text("Read by ${m.narrators}", style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val bits = listOfNotNull(
                m.series.takeIf { it.isNotBlank() }?.let { s -> s + (m.seriesIndex?.let { " #" + fmtIndex(it) } ?: "") },
                m.lengthMs.takeIf { it > 0 }?.let { formatDuration(it) },
                m.year.takeIf { it.isNotBlank() },
            )
            if (bits.isNotEmpty()) {
                Text(bits.joinToString(" · ") + lengthNote, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ApplyInfo(book: BookEntity, m: BookInfo.Match, onBack: () -> Unit, onApply: (Set<String>) -> Unit) {
    val fields = listOf(
        InfoField("title", "Title", book.title, m.title),
        InfoField("author", "Author", book.author, m.authors),
        InfoField("narrator", "Narrator", book.narrator, m.narrators),
        InfoField(
            "series",
            "Series",
            listOf(book.series, book.seriesIndex?.let { "#" + fmtIndex(it) }.orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
            listOf(m.series, m.seriesIndex?.let { "#" + fmtIndex(it) }.orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
        ),
        InfoField("description", "Description", book.description, m.description),
        InfoField("cover", "Cover", if (book.coverPath != null) "Current cover" else "", if (m.coverUrl != null) "Audible cover" else ""),
    ).filter { it.new.isNotBlank() }
    // Ticked: anything new or different, except a title you've already got.
    var chosen by remember(m) {
        mutableStateOf(fields.filter { it.new != it.now && (it.key != "title" || book.title.isBlank()) }.map { it.key }.toSet())
    }
    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back to matches", tint = EmberColors.Text) }
        Text("Use these details", style = MaterialTheme.typography.titleLarge, color = EmberColors.Text)
    }
    LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                AsyncImage(m.coverUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.size(84.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(m.title, style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
                    Text(m.authors, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
                }
            }
        }
        items(fields, key = { it.key }) { f ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { chosen = if (f.key in chosen) chosen - f.key else chosen + f.key }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Checkbox(checked = f.key in chosen, onCheckedChange = { chosen = if (it) chosen + f.key else chosen - f.key })
                Column(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text(f.label, style = MaterialTheme.typography.labelLarge, color = EmberColors.TextMuted)
                    Text(f.new, style = MaterialTheme.typography.bodyMedium, color = EmberColors.Text, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (f.now.isNotBlank() && f.now != f.new && f.key != "cover") {
                        Text("Now: ${f.now}", style = MaterialTheme.typography.bodySmall, color = EmberColors.TextFaint, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    Button(
        onClick = { onApply(chosen) },
        enabled = chosen.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
    ) { Text(if (chosen.isEmpty()) "Nothing ticked" else "Use ${chosen.size} ${if (chosen.size == 1) "detail" else "details"}") }
}

private data class InfoField(val key: String, val label: String, val now: String, val new: String)

private fun fmtIndex(f: Float): String = if (f % 1f == 0f) f.toInt().toString() else f.toString()
