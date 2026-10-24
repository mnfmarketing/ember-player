@file:OptIn(ExperimentalLayoutApi::class)

package app.ember.ui.screens

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.torrent.TorrentFile
import app.ember.torrent.TorrentJob
import app.ember.torrent.TorrentView
import app.ember.ui.MainViewModel
import app.ember.ui.MainViewModel.TorrentAdd
import app.ember.ui.components.ThinProgress
import app.ember.ui.theme.EmberColors

/** Add a book from a .torrent file, a magnet link, or a link to a .torrent file. */
@Composable
fun TorrentAddScreen(vm: MainViewModel, onBack: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val state by vm.torrentAdd.collectAsState()
    val settings by vm.settings.collectAsState()
    var link by remember { mutableStateOf("") }
    var selected by remember(state) {
        mutableStateOf((state as? TorrentAdd.Ready)?.preview?.let { vm.testSelection(it) ?: it.defaultSelection() } ?: emptySet())
    }
    var starting by remember { mutableStateOf(false) }
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.openTorrent(uri.toString())
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.setTorrentFolder(it) }

    // Opened from a magnet link or .torrent file elsewhere on the phone.
    LaunchedEffect(Unit) {
        vm.takeIncomingTorrent()?.let { vm.openTorrent(it) }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                vm.resetTorrentAdd()
                onBack()
            }) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
            Text("Download from a torrent", style = MaterialTheme.typography.titleLarge, color = EmberColors.Text)
        }

        when (val s = state) {
            is TorrentAdd.Idle, is TorrentAdd.Failed -> Column(Modifier.padding(horizontal = 20.dp)) {
                if (s is TorrentAdd.Failed) {
                    Text(s.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(16.dp))
                }
                Button(onClick = { pickFile.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Rounded.Description, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose a .torrent file")
                }
                Spacer(Modifier.height(24.dp))
                Text("Or paste a magnet link (or a link to a .torrent file)", style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    placeholder = { Text("magnet:?xt=urn:btih:…") },
                    trailingIcon = {
                        IconButton(onClick = { clipboard.getText()?.text?.let { link = it } }) {
                            Icon(Icons.Rounded.ContentPaste, "Paste")
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { vm.openTorrent(link) }, enabled = link.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Continue")
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "Free, legal audiobooks: LibriVox recordings on the Internet Archive (archive.org) each have a torrent link on their page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmberColors.TextMuted,
                )
            }

            is TorrentAdd.Loading -> Column(
                Modifier.fillMaxWidth().padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text(s.message, style = MaterialTheme.typography.bodyLarge, color = EmberColors.TextMuted)
            }

            is TorrentAdd.Ready -> {
                val p = s.preview
                val chosen = p.files.filter { it.index in selected }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                    item {
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            Text(p.name, style = MaterialTheme.typography.headlineSmall, color = EmberColors.Text)
                            Text(
                                "${chosen.size} of ${p.files.size} files · ${size(chosen.sumOf { it.size })}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = EmberColors.TextMuted,
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { pickFolder.launch(null) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Save to", style = MaterialTheme.typography.labelLarge, color = EmberColors.TextMuted)
                                    Text(
                                        settings.torrentFolderName.ifBlank { "Choose a folder" },
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (settings.torrentFolderUri.isBlank()) MaterialTheme.colorScheme.primary else EmberColors.Text,
                                    )
                                }
                                TextButton(onClick = { pickFolder.launch(null) }) { Text(if (settings.torrentFolderUri.isBlank()) "Choose" else "Change") }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                                Icon(Icons.Rounded.Wifi, null, tint = EmberColors.TextMuted, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(14.dp))
                                Text(
                                    if (settings.torrentWifiOnly) "Downloads on Wi-Fi only. Change this in Settings." else "Downloads on any connection, including mobile data.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EmberColors.TextMuted,
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = false, onClick = { selected = p.defaultSelection() }, label = { Text("Audio and covers") })
                                FilterChip(selected = false, onClick = { selected = p.files.map { it.index }.toSet() }, label = { Text("Everything") })
                                FilterChip(selected = false, onClick = { selected = emptySet() }, label = { Text("None") })
                            }
                            Spacer(Modifier.height(4.dp))
                            HorizontalDivider(color = EmberColors.Wash.copy(alpha = 0.10f))
                        }
                    }
                    items(p.files, key = { it.index }) { f ->
                        FileRow(f, f.index in selected, size(f.size)) { on ->
                            selected = if (on) selected + f.index else selected - f.index
                        }
                    }
                }
                Column(Modifier.navigationBarsPadding().padding(20.dp)) {
                    Button(
                        onClick = {
                            starting = true
                            vm.startTorrent(selected) { err ->
                                starting = false
                                if (err != null) {
                                    Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                    if (settings.torrentFolderUri.isBlank()) pickFolder.launch(null)
                                } else {
                                    onStarted()
                                }
                            }
                        },
                        enabled = selected.isNotEmpty() && !starting,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) {
                        Text(if (settings.torrentFolderUri.isBlank()) "Choose a folder, then download" else "Download ${size(chosen.sumOf { it.size })}")
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(f: TorrentFile, checked: Boolean, size: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Icon(
            when {
                f.isAudio -> Icons.Rounded.AudioFile
                f.isImage -> Icons.Rounded.Image
                else -> Icons.Rounded.Description
            },
            null,
            tint = if (f.isAudio) MaterialTheme.colorScheme.primary else EmberColors.TextMuted,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            f.path.substringAfterLast('/'),
            style = MaterialTheme.typography.bodyMedium,
            color = if (checked) EmberColors.Text else EmberColors.TextMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(size, style = MaterialTheme.typography.labelMedium, color = EmberColors.TextMuted)
    }
}

/** Torrent downloads, for the Downloads and storage screen. */
@Composable
fun TorrentRows(vm: MainViewModel, views: List<TorrentView>) {
    val context = LocalContext.current
    var confirmCancel by remember { mutableStateOf<TorrentView?>(null) }
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Torrent downloads", style = MaterialTheme.typography.titleMedium, color = EmberColors.Text, modifier = Modifier.weight(1f))
            if (views.any { it.job.state == TorrentJob.DONE }) TextButton(onClick = vm::clearFinishedTorrents) { Text("Clear finished") }
        }
        views.forEach { v ->
            val j = v.job
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(j.name, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val detail = when (j.state) {
                            TorrentJob.DOWNLOADING -> buildString {
                                append(v.phase)
                                if (v.rateBps > 0) append(" · ${size(v.rateBps)}/s")
                                if (v.peers > 0) append(" · ${v.peers} peers")
                                if (v.etaSec > 0) append(" · ${formatEta(v.etaSec)} left")
                            }
                            else -> v.phase
                        }
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (j.state == TorrentJob.FAILED) MaterialTheme.colorScheme.error else EmberColors.TextMuted,
                        )
                        Text(
                            "${size(v.bytesDone.takeIf { it > 0 } ?: (if (j.state == TorrentJob.DONE) j.wantedBytes else 0))} of ${size(j.wantedBytes)} · to ${j.destName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = EmberColors.TextFaint,
                        )
                    }
                    when (j.state) {
                        TorrentJob.DOWNLOADING -> IconButton(onClick = { vm.pauseTorrent(j.hash) }) { Icon(Icons.Rounded.Pause, "Pause") }
                        TorrentJob.PAUSED -> IconButton(onClick = { vm.resumeTorrent(j.hash) }) { Icon(Icons.Rounded.PlayArrow, "Resume") }
                        TorrentJob.FAILED -> IconButton(onClick = { vm.retryTorrent(j.hash) }) { Icon(Icons.Rounded.Refresh, "Retry") }
                    }
                    if (j.state != TorrentJob.DONE && j.state != TorrentJob.MOVING) {
                        IconButton(onClick = { confirmCancel = v }) { Icon(Icons.Rounded.Close, "Cancel download", tint = EmberColors.TextFaint) }
                    }
                }
                if (j.state != TorrentJob.DONE) {
                    Spacer(Modifier.height(6.dp))
                    ThinProgress(v.progress, height = 4.dp)
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = EmberColors.Wash.copy(alpha = 0.10f))
        }
    }

    confirmCancel?.let { v ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmCancel = null },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Cancel this download?") },
            text = { Text("What's been downloaded of \"${v.job.name}\" so far is deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.cancelTorrent(v.job.hash)
                    confirmCancel = null
                }) { Text("Cancel download") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = null }) { Text("Keep") } },
        )
    }
}

private fun formatEta(sec: Long): String = when {
    sec < 60 -> "${sec}s"
    sec < 3600 -> "${sec / 60}m"
    else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
}
