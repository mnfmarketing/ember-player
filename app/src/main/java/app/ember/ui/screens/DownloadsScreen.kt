package app.ember.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.BookEntity
import app.ember.ui.MainViewModel
import app.ember.ui.MainViewModel.StoredItem
import app.ember.ui.components.BookCover
import app.ember.ui.components.EmptyState
import app.ember.ui.theme.EmberColors

/** What's downloaded on this phone, how much room it takes, and a quick way to clear it. */
@Composable
fun DownloadsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val storage by vm.storage.collectAsState()
    val torrents by vm.torrentViews.collectAsState()
    var filter by remember { mutableStateOf<StoredItem.Kind?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf<List<StoredItem>?>(null) }
    LaunchedEffect(Unit) { vm.refreshStorage() }
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)

    val shown = storage.items.filter { filter == null || it.kind == filter }
    val played = storage.items.filter { it.played }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
                Text("Downloads and storage", style = MaterialTheme.typography.headlineSmall, color = EmberColors.Text, modifier = Modifier.weight(1f))
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                val total = storage.episodeBytes + storage.driveBytes
                Text(size(total), style = MaterialTheme.typography.displaySmall, color = EmberColors.Text)
                Text(
                    "used by downloads · ${size(storage.freeBytes)} free on this phone",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmberColors.TextMuted,
                )
                Spacer(Modifier.height(14.dp))
                UsageBar(
                    listOf(
                        storage.episodeBytes to MaterialTheme.colorScheme.primary,
                        storage.driveBytes to EmberColors.Success,
                        (storage.coverBytes + storage.backupBytes) to EmberColors.TextFaint,
                    ),
                )
                Spacer(Modifier.height(10.dp))
                Legend(MaterialTheme.colorScheme.primary, "Podcast episodes", size(storage.episodeBytes))
                Legend(EmberColors.Success, "Google Drive books", size(storage.driveBytes))
                Legend(EmberColors.TextFaint, "Covers and backups", size(storage.coverBytes + storage.backupBytes))
                if (storage.inProgress > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text("${storage.inProgress} still downloading", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "Books in your own folders aren't counted here: Ember plays them where they are and never copies them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmberColors.TextFaint,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (played.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    OutlinedButton(onClick = { confirm = played }) {
                        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Remove ${played.size} finished · frees ${size(played.sumOf { it.bytes })}")
                    }
                }
            }
        }
        if (torrents.isNotEmpty()) {
            item { TorrentRows(vm, torrents) }
        }
        item {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterPill("All", filter == null) { filter = null }
                FilterPill("Episodes", filter == StoredItem.Kind.EPISODE) { filter = StoredItem.Kind.EPISODE }
                FilterPill("Drive books", filter == StoredItem.Kind.DRIVE_BOOK) { filter = StoredItem.Kind.DRIVE_BOOK }
                Spacer(Modifier.weight(1f))
                if (selected.isNotEmpty()) {
                    TextButton(onClick = { confirm = storage.items.filter { it.key in selected } }) {
                        Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Remove ${selected.size}")
                    }
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                EmptyState(
                    "Nothing downloaded",
                    "Podcast episodes download automatically on Wi-Fi. Google Drive books download while you listen, or from their page.",
                )
            }
        }
        items(shown, key = { it.key }) { item ->
            val checked = item.key in selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { selected = if (checked) selected - item.key else selected + item.key }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { selected = if (it) selected + item.key else selected - item.key })
                BookCover(BookEntity(id = item.key, title = item.title, coverPath = item.coverPath), Modifier.size(48.dp), corner = 6.dp, showBadge = false)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        item.subtitle + if (item.played) " · finished" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmberColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(size(item.bytes), style = MaterialTheme.typography.labelMedium, color = EmberColors.TextMuted)
                IconButton(onClick = { confirm = listOf(item) }) { Icon(Icons.Rounded.Delete, "Remove download", tint = EmberColors.TextFaint) }
            }
        }
    }

    confirm?.let { list ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text(if (list.size == 1) "Remove this download?" else "Remove ${list.size} downloads?") },
            text = {
                Text(
                    "Frees ${size(list.sumOf { it.bytes })}. Your progress is kept, and you can still stream or download again any time.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeStored(list)
                    selected = selected - list.map { it.key }.toSet()
                    confirm = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UsageBar(parts: List<Pair<Long, Color>>) {
    val total = parts.sumOf { it.first }.coerceAtLeast(1)
    Row(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(EmberColors.SurfaceHigh),
    ) {
        parts.filter { it.first > 0 }.forEach { (bytes, color) ->
            Box(
                Modifier
                    .weight(bytes.toFloat() / total)
                    .fillMaxHeight()
                    .background(color),
            )
        }
    }
}

@Composable
private fun Legend(color: Color, label: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = EmberColors.Text)
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}
