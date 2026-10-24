package app.ember.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors

/**
 * Shown across the top while books are picked: how many, and what to do with all of them.
 * Every action clears the picks; Undo (at the bottom) puts things back.
 */
@Composable
fun BookSelectionBar(
    vm: MainViewModel,
    ids: Set<String>,
    onSelectAll: (() -> Unit)?,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val books by vm.books.collectAsState()
    val picked = books.filter { it.id in ids }
    val allLinked = picked.isNotEmpty() && picked.all { it.isExternal }
    var shelfPicker by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    val mergeable = picked.size >= 2 && vm.canMerge(ids)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        color = EmberColors.SurfaceHighest,
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "Stop selecting", tint = EmberColors.Text) }
            Text(
                "${ids.size} selected",
                style = MaterialTheme.typography.titleMedium,
                color = EmberColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onSelectAll != null) {
                IconButton(onClick = onSelectAll) { Icon(Icons.Rounded.SelectAll, "Select all", tint = EmberColors.TextMuted) }
            }
            if (!allLinked) {
                IconButton(onClick = {
                    vm.addToUpNext(ids)
                    onClear()
                }) { Icon(Icons.Rounded.QueueMusic, "Add to Up Next", tint = EmberColors.TextMuted) }
            }
            IconButton(onClick = { shelfPicker = true }) { Icon(Icons.Rounded.LibraryAdd, "Add to a shelf", tint = EmberColors.TextMuted) }
            IconButton(onClick = {
                vm.markFinished(ids)
                onClear()
            }) { Icon(Icons.Rounded.CheckCircle, "Mark as finished", tint = EmberColors.TextMuted) }
            IconButton(onClick = {
                vm.resetProgress(ids)
                onClear()
            }) { Icon(Icons.Rounded.RestartAlt, "Mark as not finished", tint = EmberColors.TextMuted) }
            if (mergeable) {
                IconButton(onClick = { merging = true }) { Icon(Icons.Rounded.CallMerge, "Merge into one book", tint = EmberColors.TextMuted) }
            }
            if (allLinked) {
                IconButton(onClick = { confirmRemove = true }) { Icon(Icons.Rounded.Delete, "Remove from Ember", tint = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (shelfPicker) {
        val shelves by vm.shelves.collectAsState()
        var naming by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { shelfPicker = false },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Add ${ids.size} ${if (ids.size == 1) "book" else "books"} to") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    shelves.forEach { s ->
                        ShelfChoice(s.name, Icons.Rounded.LibraryAdd) {
                            vm.addToShelf(s.id, ids)
                            shelfPicker = false
                            onClear()
                        }
                    }
                    ShelfChoice("New shelf", Icons.Rounded.Add) { naming = true }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { shelfPicker = false }) { Text("Cancel") } },
        )
        if (naming) {
            NameDialog(title = "New shelf", placeholder = "Comfort rereads", onDismiss = { naming = false }) {
                vm.createShelfWith(it, ids)
                naming = false
                shelfPicker = false
                onClear()
            }
        }
    }
    if (merging) {
        MergeBooksDialog(vm, picked, onDismiss = { merging = false }) {
            merging = false
            onClear()
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Remove ${ids.size} linked ${if (ids.size == 1) "book" else "books"}?") },
            text = { Text("They disappear from Ember only. Nothing changes in the other apps.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    vm.removeBooks(ids)
                    onClear()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ShelfChoice(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = EmberColors.TextMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
    }
}
