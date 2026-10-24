package app.ember.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.drive.DriveAuth
import app.ember.drive.DriveFile
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import kotlinx.coroutines.launch

/** Connect / reconnect / disconnect card shown in Settings. */
@Composable
fun DriveCard(vm: MainViewModel, onPickFolder: () -> Unit) {
    val status by vm.driveStatus.collectAsState()
    val error by vm.driveError.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (vm.driveComplete(res.data)) onPickFolder()
    }
    val connect: () -> Unit = {
        val activity = context as? Activity
        if (activity != null) {
            scope.launch {
                val pi = vm.driveBegin(activity)
                if (pi != null) {
                    consent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else if (vm.driveStatus.value == DriveAuth.Status.CONNECTED) {
                    onPickFolder()
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(EmberColors.Surface)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CloudQueue, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Google Drive", style = MaterialTheme.typography.titleSmall, color = EmberColors.Text)
                Text(
                    when (status) {
                        DriveAuth.Status.CONNECTED -> "Connected. Books stream instantly and save for offline on Wi-Fi as you listen."
                        DriveAuth.Status.NEEDS_CONSENT -> "Google needs you to approve access again."
                        DriveAuth.Status.ERROR -> "Couldn't connect" + (error?.let { ": $it" } ?: ".")
                        DriveAuth.Status.DISCONNECTED -> "Stream and download audiobooks from a Drive folder. Read-only: Ember never changes your files."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == DriveAuth.Status.ERROR) MaterialTheme.colorScheme.primary else EmberColors.TextMuted,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when (status) {
                DriveAuth.Status.CONNECTED -> {
                    Button(onClick = onPickFolder) { Text("Add a Drive folder") }
                    TextButton(onClick = vm::disconnectDrive) { Text("Disconnect") }
                }
                DriveAuth.Status.DISCONNECTED -> Button(onClick = connect) { Text("Connect Google Drive") }
                else -> {
                    Button(onClick = connect) { Text("Reconnect") }
                    TextButton(onClick = vm::disconnectDrive) { Text("Disconnect") }
                }
            }
        }
    }
}

/** Browse your Drive and choose the folder that holds your audiobooks. */
@Composable
fun DriveFolderPickerScreen(vm: MainViewModel, onDone: () -> Unit) {
    val path = remember { mutableStateListOf("root" to "My Drive") }
    var folders by remember { mutableStateOf<List<DriveFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val current = path.last()

    LaunchedEffect(current.first) {
        folders = null
        error = null
        try {
            folders = vm.driveFolders(current.first)
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load your Drive"
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (path.size > 1) path.removeAt(path.lastIndex) else onDone()
            }) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
            Text("Choose a Drive folder", style = MaterialTheme.typography.titleLarge, color = EmberColors.Text)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            path.forEachIndexed { i, (_, name) ->
                if (i > 0) Icon(Icons.Rounded.ChevronRight, null, tint = EmberColors.TextFaint, modifier = Modifier.size(18.dp))
                Text(
                    name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (i == path.lastIndex) EmberColors.Text else EmberColors.TextMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { while (path.size > i + 1) path.removeAt(path.lastIndex) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        Box(Modifier.weight(1f)) {
            val list = folders
            when {
                error != null -> Text(error ?: "", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(20.dp))
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> Text(
                    "No folders inside. If your audiobooks are here, add this folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = EmberColors.TextMuted,
                    modifier = Modifier.padding(20.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { path.add(f.id to f.name) }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Text(f.name, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.ChevronRight, null, tint = EmberColors.TextFaint)
                        }
                    }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(16.dp),
        ) {
            Text(
                if (current.first == "root") {
                    "Tip: pick the folder that holds your audiobooks rather than all of My Drive. Scanning is faster."
                } else {
                    "Each subfolder becomes a book. Ember scans it now and on every rescan."
                },
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextFaint,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDone) { Text("Cancel") }
                Button(
                    onClick = {
                        vm.addDriveFolder(current.first, current.second)
                        onDone()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Use \"${current.second}\"", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
