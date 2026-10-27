package app.ember.ui.screens

import android.app.Activity
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

/** Settings → Backup and restore → Sync with Google Drive. */
@Composable
fun SyncSection(vm: MainViewModel) {
    val status by vm.syncStatus.collectAsState()
    val authError by vm.syncAuthError.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf(false) }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        vm.syncComplete(res.data)
    }
    val turnOn: () -> Unit = {
        val activity = context as? Activity
        if (activity != null) {
            scope.launch {
                val pi = vm.syncBegin(activity)
                if (pi != null) consent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
            }
        }
    }

    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { if (status.on) vm.turnSyncOff() else turnOn() }
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Sync, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Sync with Google Drive", style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
                Text(
                    when {
                        !status.on -> "Keeps your place, bookmarks, stats, podcasts and listening log the same on your other devices with Ember (an iPhone or iPad)."
                        status.running -> "Syncing…"
                        status.error != null -> "Couldn't sync: ${status.error}"
                        status.lastSyncAt > 0 -> "On · last synced " + DateUtils.getRelativeTimeSpanString(status.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                        else -> "On · syncing for the first time"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.on && status.error != null) MaterialTheme.colorScheme.error else EmberColors.TextMuted,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = status.on, onCheckedChange = { if (it) turnOn() else vm.turnSyncOff() })
        }
        if (!status.on && authError != null) {
            Text(
                "Google didn't allow it: $authError",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        if (status.on) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { renaming = true }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Devices, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("This device: ${status.deviceName}", style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
                    Text(
                        if (status.otherDevices.isEmpty()) "No other devices yet. Turn on sync in Ember on your iPhone or iPad with the same Google account."
                        else "Also syncing with: " + status.otherDevices.joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = EmberColors.TextMuted,
                    )
                }
            }
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = vm::syncNow, enabled = !status.running) {
                    Icon(Icons.Rounded.Sync, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sync now")
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Library folders, the theme and car settings stay separate on each device. Turning sync off doesn't delete anything.",
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextFaint,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(status.deviceName) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Name this device") },
            text = {
                Column {
                    Text("Shown in the listening log and on your other devices.", color = EmberColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it.take(24) }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameThisDevice(name)
                    renaming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

/** "You're further along on your iPhone. Go there?" */
@Composable
fun SyncPromptDialog(vm: MainViewModel) {
    val p by vm.syncPrompt.collectAsState()
    val np by vm.nowPlaying.collectAsState()
    val prompt = p ?: return
    val chapters = np?.takeIf { it.book.id == prompt.bookId }?.chapters.orEmpty()
    val ago = if (prompt.at > 0) DateUtils.getRelativeTimeSpanString(prompt.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() else ""
    AlertDialog(
        onDismissRequest = {},
        containerColor = EmberColors.SurfaceHigh,
        title = { Text("Continue from your ${prompt.deviceName}?") },
        text = {
            Text(
                "You're at ${vm.describeSpot(chapters, prompt.positionMs)} on your ${prompt.deviceName}" +
                    (if (ago.isNotBlank()) " ($ago)" else "") + ".",
                color = EmberColors.Text,
            )
        },
        confirmButton = { TextButton(onClick = { vm.answerSyncPrompt(true) }) { Text("Go there") } },
        dismissButton = { TextButton(onClick = { vm.answerSyncPrompt(false) }) { Text("Stay here") } },
    )
}
