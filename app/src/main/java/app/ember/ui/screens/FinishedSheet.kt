@file:OptIn(ExperimentalMaterial3Api::class)

package app.ember.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.ui.MainViewModel
import app.ember.ui.components.BookCover
import app.ember.ui.components.RatingBar
import app.ember.ui.components.bookAccent
import app.ember.ui.theme.EmberColors

/** You finished a book: rate it, then log it on StoryGraph or Goodreads in one tap. */
@Composable
fun FinishedSheet(vm: MainViewModel, bookId: String, onDismiss: () -> Unit, onTurnOff: () -> Unit) {
    val bookState by remember(bookId) { vm.observeBook(bookId) }.collectAsState(initial = null)
    val book = bookState ?: return
    val context = LocalContext.current
    val accent = bookAccent(book)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = EmberColors.Surface,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BookCover(book, Modifier.size(120.dp), corner = 12.dp, showBadge = false)
            Spacer(Modifier.height(14.dp))
            Text("You finished it", style = MaterialTheme.typography.labelLarge, color = accent)
            Text(
                book.title,
                style = MaterialTheme.typography.headlineSmall,
                color = EmberColors.Text,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(16.dp))
            Text("How was it?", style = MaterialTheme.typography.bodyLarge, color = EmberColors.TextMuted)
            RatingBar(book.rating, onRate = { vm.setRating(book.id, if (it == book.rating) 0 else it) }, size = 40.dp, color = accent)
            Spacer(Modifier.height(18.dp))
            Text("Log it where you track your reading", style = MaterialTheme.typography.bodyMedium, color = EmberColors.TextMuted)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.openOnReadingSite(context, book, "storygraph") }, modifier = Modifier.weight(1f).height(50.dp)) {
                    Text("StoryGraph")
                    Spacer(Modifier.size(6.dp))
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp))
                }
                OutlinedButton(onClick = { vm.openOnReadingSite(context, book, "goodreads") }, modifier = Modifier.weight(1f).height(50.dp)) {
                    Text("Goodreads")
                    Spacer(Modifier.size(6.dp))
                    Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onTurnOff) { Text("Don't ask again") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            // Keeps the last row clear of the navigation bar.
            Spacer(Modifier.height(40.dp))
        }
    }
}
