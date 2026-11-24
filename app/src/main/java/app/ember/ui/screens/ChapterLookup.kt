package app.ember.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.BookEntity
import app.ember.data.BookInfo
import app.ember.ui.theme.EmberColors
import app.ember.util.formatClock
import app.ember.util.formatDuration
import kotlinx.coroutines.launch

/**
 * Real chapter names from the audiobook catalog: pick the edition, see the list,
 * then put it into the editor (names and times, or just names on your own times).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterLookupSheet(
    book: BookEntity,
    current: List<Pair<Long, String>>,
    onDismiss: () -> Unit,
    onApply: (List<Pair<Long, String>>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var matches by remember { mutableStateOf<List<BookInfo.Match>?>(null) }
    var picked by remember { mutableStateOf<BookInfo.Match?>(null) }
    var fit by remember { mutableStateOf<BookInfo.Fit?>(null) }
    var loading by remember { mutableStateOf(true) }
    var problem by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(book.id) {
        matches = BookInfo.search(book.title, book.author).ifEmpty { BookInfo.search(book.title, "") }
        loading = false
        if (matches.isNullOrEmpty()) problem = "No match in the catalog. Check the title and author in Edit details, then try again."
    }

    fun choose(m: BookInfo.Match) {
        picked = m
        loading = true
        problem = null
        scope.launch {
            val cat = BookInfo.chapters(m.asin)
            loading = false
            if (cat == null) {
                problem = "The catalog has no chapter list for this edition. Try another one."
                picked = null
            } else {
                fit = BookInfo.fit(cat, book.durationMs, current)
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = EmberColors.SurfaceHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Get chapter names", style = MaterialTheme.typography.titleLarge, color = EmberColors.Text)
            Text(
                if (fit == null) "Pick the edition you have. Its length should be close to yours (${formatDuration(book.durationMs)})." else picked?.title.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextMuted,
            )
            Spacer(Modifier.height(12.dp))
            problem?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
            }
            val f = fit
            when {
                loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                f != null -> {
                    Text(
                        if (f.exact) {
                            "${f.chapters.size} chapters. The length matches yours, so the times should line up."
                        } else {
                            "${f.chapters.size} chapters. This edition's length differs from yours, so the times are stretched to fit and may be a little off."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmberColors.Text,
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        itemsIndexed(f.chapters) { i, (t, name) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = EmberColors.TextFaint, modifier = Modifier.width(32.dp))
                                Text(name, style = MaterialTheme.typography.bodyMedium, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(formatClock(t), style = MaterialTheme.typography.labelSmall, color = EmberColors.TextMuted)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { onApply(f.chapters) }, modifier = Modifier.fillMaxWidth()) { Text("Use these names and times") }
                    f.namesOnly?.let { names ->
                        OutlinedButton(onClick = { onApply(names) }, modifier = Modifier.fillMaxWidth()) { Text("Use the names on my chapter times") }
                    }
                    TextButton(onClick = {
                        fit = null
                        picked = null
                    }) { Text("Pick another edition") }
                }
                else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    items(matches.orEmpty(), key = { it.asin }) { m ->
                        val close = book.durationMs > 0 && m.lengthMs > 0 &&
                            kotlin.math.abs(m.lengthMs - book.durationMs) <= maxOf(120_000L, book.durationMs / 100)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { choose(m) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                        ) {
                            Text(m.title, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(m.authors, m.narrators.takeIf { it.isNotBlank() }?.let { "read by $it" }, m.year).filter { !it.isNullOrBlank() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = EmberColors.TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (m.lengthMs > 0) {
                                Text(
                                    formatDuration(m.lengthMs) + if (close) " · same length as yours" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (close) MaterialTheme.colorScheme.primary else EmberColors.TextFaint,
                                )
                            }
                        }
                        HorizontalDivider(color = EmberColors.TextFaint.copy(alpha = 0.2f))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
