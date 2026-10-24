package app.ember.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.BookEntity
import app.ember.data.LibraryScanner
import app.ember.ui.MainViewModel
import app.ember.ui.components.BookCover
import app.ember.ui.theme.EmberColors

/** A good name for books that are parts of one: what their titles share ("The Bone Season CD1", "...CD2" → "The Bone Season"). */
fun mergedName(books: List<BookEntity>): String {
    val titles = books.map { it.title.trim() }
    if (titles.isEmpty()) return ""
    var prefix = titles.first()
    for (t in titles.drop(1)) {
        var i = 0
        while (i < prefix.length && i < t.length && prefix[i].equals(t[i], ignoreCase = true)) i++
        prefix = prefix.substring(0, i)
    }
    val cleaned = prefix
        .replace(Regex("(?i)[\\s,:._-]*(cd|disc|disk|part|pt)?\\.?\\s*\\d*$"), "")
        .trim()
        .trimEnd('-', '–', ':', ',', '(', '[', '_', '.')
        .trim()
    return if (cleaned.length >= 3) cleaned else titles.first()
}

/**
 * Merging picked books into one: put the parts in order (the first one's cover is kept) and
 * choose the name. Bookmarks, clips, the listening log and your place all carry over.
 */
@Composable
fun MergeBooksDialog(vm: MainViewModel, books: List<BookEntity>, onDismiss: () -> Unit, onMerged: () -> Unit) {
    var order by remember { mutableStateOf(books.sortedWith { a, b -> LibraryScanner.naturalCompare(a.title, b.title) }) }
    var name by remember { mutableStateOf(mergedName(books)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmberColors.SurfaceHigh,
        title = { Text("Merge into one book") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Play order (the first one's cover is kept)", style = MaterialTheme.typography.labelLarge, color = EmberColors.TextMuted)
                Spacer(Modifier.height(4.dp))
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    order.forEachIndexed { i, b ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("${i + 1}", style = MaterialTheme.typography.titleSmall, color = EmberColors.TextMuted, modifier = Modifier.width(22.dp))
                            BookCover(b, Modifier.size(36.dp), corner = 6.dp, showBadge = false)
                            Spacer(Modifier.width(10.dp))
                            Text(b.title, style = MaterialTheme.typography.bodyMedium, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { order = order.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) } }, enabled = i > 0) {
                                Icon(Icons.Rounded.KeyboardArrowUp, "Move up")
                            }
                            IconButton(onClick = { order = order.toMutableList().also { l -> l.add(i + 1, l.removeAt(i)) } }, enabled = i < order.size - 1) {
                                Icon(Icons.Rounded.KeyboardArrowDown, "Move down")
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Bookmarks, clips, the listening log and your place move to the merged book.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmberColors.TextMuted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.mergeBooks(order.map { it.id }, name)
                onMerged()
            }, enabled = name.isNotBlank()) { Text("Merge") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SplitBookDialog(vm: MainViewModel, book: BookEntity, onDismiss: () -> Unit, onSplit: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmberColors.SurfaceHigh,
        title = { Text("Split back apart?") },
        text = { Text("${book.displayTitle} becomes its separate parts again. Each keeps its own bookmarks, clips and listening log.") },
        confirmButton = {
            TextButton(onClick = {
                vm.splitBook(book.id)
                onSplit()
            }) { Text("Split") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
