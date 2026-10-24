package app.ember.ui.screens

import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings: automatic backups, saving a copy anywhere (Google Drive included), and restoring. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupSection(vm: MainViewModel) {
    val settings by vm.settings.collectAsState()
    val busy by vm.backupBusy.collectAsState()
    val message by vm.backupMessage.collectAsState()
    val pending by vm.pendingRestore.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var autoList by remember { mutableStateOf<List<File>?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.setBackupFolder(it) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { vm.saveBackupTo(it) }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.pickRestore(it) }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        if (m.endsWith("…")) return@LaunchedEffect
        Toast.makeText(context, m, if (m.length > 60) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        vm.clearBackupMessage()
    }

    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        val last = settings.lastBackupAt
        Text(
            if (last > 0) "Last backup " + DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) else "No backup yet",
            style = MaterialTheme.typography.bodyLarge,
            color = EmberColors.Text,
        )
        Text(
            "Every night Ember saves your progress, bookmarks, clips, shelves, stats, edits, podcasts and settings. Your audio files aren't included.",
            style = MaterialTheme.typography.bodySmall,
            color = EmberColors.TextMuted,
        )
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { folderPicker.launch(null) }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Backup folder", style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
                Text(
                    if (settings.backupFolderUri.isBlank()) {
                        "Only inside Ember. Pick a folder to also keep copies somewhere that survives losing your phone."
                    } else {
                        "Also saving to \"${settings.backupFolderName}\". Tap to change."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = EmberColors.TextMuted,
                )
            }
            if (settings.backupFolderUri.isNotBlank()) {
                TextButton(onClick = vm::clearBackupFolder) { Text("Stop") }
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::backUpNow, enabled = !busy) {
                Icon(Icons.Rounded.Backup, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Back up now")
            }
            OutlinedButton(onClick = {
                val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                saveAs.launch("Ember backup $stamp.json")
            }, enabled = !busy) {
                Icon(Icons.Rounded.SaveAlt, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save a copy…")
            }
            OutlinedButton(onClick = { openFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) }, enabled = !busy) {
                Icon(Icons.Rounded.Restore, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Restore from file…")
            }
            OutlinedButton(onClick = { scope.launch { autoList = vm.automaticBackups() } }, enabled = !busy) {
                Text("Earlier backups")
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Save a copy… can save straight to Google Drive: pick Drive in the side menu of the save screen.",
            style = MaterialTheme.typography.bodySmall,
            color = EmberColors.TextFaint,
        )
        if (busy && message != null) {
            Spacer(Modifier.height(6.dp))
            Text(message ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }

    autoList?.let { files ->
        AlertDialog(
            onDismissRequest = { autoList = null },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Earlier backups") },
            text = {
                if (files.isEmpty()) {
                    Text("No automatic backups yet.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(files, key = { it.name }) { f ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        autoList = null
                                        vm.pickAutomaticRestore(f)
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.lastModified())),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = EmberColors.Text,
                                )
                                Text(
                                    when {
                                        f.name.contains("before-restore") -> "Made just before a restore"
                                        f.name.contains("manual") -> "Backed up by you"
                                        else -> "Nightly backup"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = EmberColors.TextMuted,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { autoList = null }) { Text("Close") } },
        )
    }

    pending?.let { p ->
        val s = p.summary
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Restore this backup?") },
            text = {
                Column {
                    Text(
                        "${p.label} from " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.createdAt)),
                        color = EmberColors.Text,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${s.books} books · ${s.bookmarks} bookmarks · ${s.clips} clips · ${s.shelves} shelves · ${s.podcasts} podcasts",
                        color = EmberColors.TextMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This adds to your library rather than replacing it. Progress is taken from whichever was played more recently. " +
                            "Ember backs up your library as it is now first, so you can undo this from Earlier backups.",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmberColors.TextMuted,
                    )
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("Restore") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } },
        )
    }
}
