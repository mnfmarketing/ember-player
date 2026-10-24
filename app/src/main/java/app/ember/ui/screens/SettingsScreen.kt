@file:OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.ember.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.LibraryFolderEntity
import app.ember.data.LibraryScanner
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import java.text.DateFormat
import java.util.Date

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onBack: (() -> Unit)?,
    onAddFolder: () -> Unit,
    onPickDriveFolder: () -> Unit,
    onOpenDownloads: () -> Unit = {},
    onOpenCar: () -> Unit = {},
) {
    val settings by vm.settings.collectAsState()
    val folders by vm.folders.collectAsState()
    val scan by vm.scanProgress.collectAsState()
    var removing by remember { mutableStateOf<LibraryFolderEntity?>(null) }
    var showSound by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val folderProblems by vm.folderProblems.collectAsState()
    // Android 12+ asks before Ember may notice Bluetooth headphones and cars connecting.
    var askBattery by remember { mutableStateOf(false) }
    val torrentFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.setTorrentFolder(it) }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setResumeOnConnect(true)
        if (!app.ember.playback.ResumeOnConnect.canStartOnItsOwn(context)) askBattery = true
        if (!granted) {
            android.widget.Toast.makeText(
                context,
                "Without Bluetooth permission, Ember can only resume for wired headphones, or Bluetooth while Ember is open.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
    var pickCar by remember { mutableStateOf(false) }
    val carPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pickCar = true else android.widget.Toast.makeText(context, "Ember needs Bluetooth permission to list your devices.", android.widget.Toast.LENGTH_LONG).show()
    }
    val infoFill by vm.infoFill.collectAsState()
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.exportBooksCsv(uri)
    }
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = if (onBack != null) 4.dp else 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
                }
                Text("Settings", style = MaterialTheme.typography.headlineLarge, color = EmberColors.Text)
            }
        }

        item { Group("Library folders") }
        items(folders, key = { it.id }) { f ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (f.uri.startsWith("drive:")) Icons.Rounded.CloudQueue else Icons.Rounded.Folder,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (f.lastScanAt > 0) "Scanned " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.lastScanAt)) else "Not scanned yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmberColors.TextMuted,
                    )
                    folderProblems[f.id]?.let { problem ->
                        Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onAddFolder, contentPadding = PaddingValues(0.dp)) { Text("Choose the folder again") }
                    }
                    if (LibraryScanner.isDriveAppUri(f.uri)) {
                        Text(
                            "Picked through the Drive app, which downloads each whole file before it plays. Connect Google Drive below and add this folder there to stream instantly. Your progress carries over.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = { removing = f }) { Icon(Icons.Rounded.Delete, "Remove folder", tint = EmberColors.TextFaint) }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                scan?.let {
                    Text(
                        "Scanning ${it.folder}: ${it.booksFound} books · ${it.current}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onAddFolder) {
                        Icon(Icons.Rounded.CreateNewFolder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add folder")
                    }
                    if (folders.isNotEmpty()) {
                        OutlinedButton(onClick = vm::rescan, enabled = scan == null) {
                            Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Rescan")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tip: each subfolder becomes one book, and each M4B file is its own book with its chapters. Add a cover.jpg to a folder to use it as the cover.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmberColors.TextFaint,
                )
            }
        }

        item { DriveCard(vm, onPickFolder = onPickDriveFolder) }

        item { Group("Playback") }
        item {
            val st by vm.settings.collectAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { showSound = true }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Equalizer and narration boost", style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
                    val eqLabel = if (st.eqEnabled) {
                        (app.ember.sound.SoundPresets.presets.firstOrNull { it.key == st.eqPreset }?.label ?: "Custom") + " preset"
                    } else {
                        "Off"
                    }
                    val boostLabel = if (st.narrationBoost) " · boost on" else ""
                    Text(
                        eqLabel + boostLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = EmberColors.TextMuted,
                    )
                }
            }
        }
        item {
            ChoiceRow("Skip back", listOf(5, 10, 15, 30, 60), settings.skipBackSec, { "${it}s" }, { vm.setSkipBack(it) })
            ChoiceRow("Skip forward", listOf(10, 15, 30, 45, 60), settings.skipForwardSec, { "${it}s" }, { vm.setSkipForward(it) })
            ChoiceRow(
                "Rewind a little after a pause",
                listOf(0, 3, 5, 10, 15),
                settings.autoRewindSec,
                { if (it == 0) "Off" else "${it}s" },
                { vm.setAutoRewind(it) },
                note = "Smart rewind: nothing after a few seconds, half this after a short pause, this after a few minutes, double after an hour, triple after a night",
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Skip silence", style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
                    Text("Trims long pauses in the narration", style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
                }
                Switch(checked = settings.skipSilence, onCheckedChange = { vm.setSkipSilence(it) })
            }
            ToggleRow(
                "Resume when headphones or car connect",
                "Picks up where you left off when Bluetooth headphones, your car, or wired headphones connect, if you listened in the last 3 days.",
                settings.resumeOnConnect,
            ) { on ->
                val needsAsk = on && android.os.Build.VERSION.SDK_INT >= 31 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (needsAsk) {
                    btPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    vm.setResumeOnConnect(on)
                    if (on && !app.ember.playback.ResumeOnConnect.canStartOnItsOwn(context)) askBattery = true
                }
            }
            ToggleRow(
                "Flip to pause",
                "Put your phone face down to pause. Turn it back up within 30 minutes to pick up again.",
                settings.flipToPause,
            ) { vm.setFlipToPause(it) }
            ToggleRow(
                "Rate books when you finish them",
                "When a book plays to the end, Ember asks for your rating and offers to open it in StoryGraph or Goodreads.",
                settings.rateOnFinish,
            ) { vm.setRateOnFinish(it) }
            ToggleRow(
                "Play the next book in a series",
                "When a book ends, the next unfinished book in the same series starts. Set the series and number in a book's details.",
                settings.seriesAutoPlay,
            ) { vm.setSeriesAutoPlay(it) }
            ChoiceRow(
                "Clip length",
                listOf(15, 30, 60, 90),
                settings.clipLengthSec,
                { "${it}s" },
                { vm.setClipLength(it) },
                note = "How far back a clip reaches when you tap Clip in the player",
            )
        }

        item { Group("Sleep timer") }
        item {
            ToggleRow(
                "Bedtime",
                "Start the sleep timer by itself when you listen at night. Turn the timer off and it stays off for the night.",
                settings.bedtimeOn,
            ) { vm.setBedtime(it, settings.bedtimeFromMin, settings.bedtimeToMin, settings.bedtimeTimerMin) }
            if (settings.bedtimeOn) {
                ChoiceRow(
                    "From",
                    listOf(20 * 60, 21 * 60, 22 * 60, 23 * 60, 0),
                    settings.bedtimeFromMin,
                    { clockLabel(it) },
                    { vm.setBedtime(true, it, settings.bedtimeToMin, settings.bedtimeTimerMin) },
                )
                ChoiceRow(
                    "Until",
                    listOf(4 * 60, 5 * 60, 6 * 60, 7 * 60, 8 * 60),
                    settings.bedtimeToMin,
                    { clockLabel(it) },
                    { vm.setBedtime(true, settings.bedtimeFromMin, it, settings.bedtimeTimerMin) },
                )
                ChoiceRow(
                    "Timer length",
                    listOf(15, 30, 45, 60),
                    settings.bedtimeTimerMin,
                    { "$it min" },
                    { vm.setBedtime(true, settings.bedtimeFromMin, settings.bedtimeToMin, it) },
                )
            }
            ToggleRow(
                "Fade out at the end",
                "Lowers the volume over the last 10 seconds. Off: it simply pauses.",
                settings.sleepFade,
            ) { vm.setSleepFade(it) }
            Text(
                "Shake your phone to start the timer over. If it already stopped, shake within 5 minutes to keep listening. When the timer stops a book, the player offers to go back to where you set it.",
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        item { Group("Car mode") }
        item {
            ToggleRow(
                "Open car mode when your car connects",
                "Big buttons and the screen stays on. Works with cars that report themselves as car audio; pick yours below if it doesn't open.",
                settings.carModeAuto,
            ) { vm.setCarModeAuto(it) }
            val chosen = settings.carDevices.split(',').filter { it.isNotBlank() }
            NavRow(
                Icons.Rounded.DirectionsCar,
                "Your car",
                if (chosen.isEmpty()) "Tap to choose your car's Bluetooth" else "${chosen.size} ${if (chosen.size == 1) "device" else "devices"} chosen",
            ) {
                val need = android.os.Build.VERSION.SDK_INT >= 31 &&
                    androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                if (need) carPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT) else pickCar = true
            }
            NavRow(Icons.Rounded.DirectionsCar, "Open car mode now", "Also on the Car button in the player, and on Ember's home screen icon", onOpenCar)
        }

        item { Group("Headphone and car buttons") }
        item {
            ActionRow("Press twice", settings.btnDouble) { vm.setButton("double", it) }
            ActionRow("Press three times", settings.btnTriple) { vm.setButton("triple", it) }
            ActionRow("Next button", settings.btnNext) { vm.setButton("next", it) }
            ActionRow("Previous button", settings.btnPrevious) { vm.setButton("previous", it) }
            Text(
                "Press twice and three times are for headphones with one button (holding it opens your voice assistant; Android keeps that). Most earbuds and cars send Next and Previous for their own double and triple taps. A short beep confirms a bookmark.",
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextMuted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        item { Group("Book info") }
        item {
            NavRow(
                Icons.Rounded.TravelExplore,
                "Fill in missing book info",
                infoFill ?: "Series, number, narrator, description and covers from Audible's catalog. Only fills what's missing, and only clear matches.",
            ) { vm.fillMissingInfo() }
            NavRow(
                Icons.Rounded.IosShare,
                "Export for StoryGraph or Goodreads",
                "A CSV of your books, ratings and finish dates. Import it on either site.",
            ) { exportCsv.launch("ember-books.csv") }
        }

        item { Group("Podcasts") }
        item {
            ChoiceRow(
                "Auto-download up to",
                listOf(1, 2, 3, 5, 10),
                settings.autoDownloadCount,
                { "$it" },
                { vm.setAutoDownloadCount(it) },
                note = "Unplayed episodes kept per show, for shows with Auto download on (turn it on in a show's settings). Only newly released episodes are downloaded, never older ones.",
            )
        }

        item { Group("Storage") }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenDownloads)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.DownloadForOffline, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Downloads and storage", style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
                    Text("See what's saved on this phone and free up space", style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { torrentFolderPicker.launch(null) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Torrent downloads go to", style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
                    Text(
                        settings.torrentFolderName.ifBlank { "Not chosen yet. Tap to choose a folder (your audiobooks folder works well)." },
                        style = MaterialTheme.typography.bodySmall,
                        color = EmberColors.TextMuted,
                    )
                }
            }
            ToggleRow(
                "Torrents on Wi-Fi only",
                "Torrent downloads wait for Wi-Fi so audiobooks don't use your mobile data.",
                settings.torrentWifiOnly,
            ) { vm.setTorrentWifiOnly(it) }
        }

        item { Group("Backup and restore") }
        item { BackupSection(vm) }

        item { Group("Goals") }
        item {
            ChoiceRow("Daily listening goal", listOf(15, 30, 45, 60, 90), settings.dailyGoalMin, { "$it min" }, { vm.setDailyGoal(it) })
            ChoiceRow("Books this year", listOf(12, 24, 36, 52, 100), settings.yearlyGoalBooks, { "$it" }, { vm.setYearlyGoal(it) })
        }

        item { CrashReportCard() }

        item { Group("Good to know") }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Info("Pocket lock", "Tap the lock in the player so pocket taps do nothing. Press and hold anywhere to unlock. Headphone and car buttons keep working.")
                Info("Scrubbing", "Drag the slider to move; a tap does nothing. Slide your finger down while dragging for finer control.")
                Info("Sleep timer", "Fades out over the last 10 seconds. Shake the phone to restart it.")
                Info("Android Auto", "Ember shows up in Android Auto. Because it is sideloaded, turn on Unknown sources in Android Auto's developer settings first.")
                Info("Chromecast", "Tap the cast icon in the player. Your place carries over both ways when you start or stop casting.")
                Info("Home screen widget", "Long-press your home screen, tap Widgets, and find Ember.")
                Spacer(Modifier.height(16.dp))
                Text("Ember $version", style = MaterialTheme.typography.labelSmall, color = EmberColors.TextFaint)
            }
        }
    }

    if (showSound) SoundSheet(vm) { showSound = false }
    if (pickCar) CarDevicePicker(vm, settings.carDevices) { pickCar = false }

    if (askBattery) {
        AlertDialog(
            onDismissRequest = { askBattery = false },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Let Ember start playing by itself?") },
            text = {
                Text(
                    "Android only lets an app start playing on its own when its battery use is set to Unrestricted. " +
                        "Allow it on the next screen. If you'd rather not, Ember will show a one-tap Resume notification when your headphones or car connect.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askBattery = false
                    try {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + context.packageName),
                            ),
                        )
                    } catch (e: Exception) {
                        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) { Text("Allow") }
            },
            dismissButton = { TextButton(onClick = { askBattery = false }) { Text("Use notification") } },
        )
    }

    removing?.let { f ->
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Remove ${f.name}?") },
            text = { Text("Its books leave your Ember library, including their progress and bookmarks. Your audio files stay where they are.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeFolder(f)
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Group(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun ChoiceRow(
    label: String,
    options: List<Int>,
    selected: Int,
    format: (Int) -> String,
    onSelect: (Int) -> Unit,
    note: String? = null,
) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o ->
                FilterChip(
                    selected = o == selected,
                    onClick = { onSelect(o) },
                    label = { Text(format(o)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
    }
}

/** If Ember closed unexpectedly last time, offer to share what happened. */
@Composable
private fun CrashReportCard() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(app.ember.data.CrashReports.latest(context)) }
    val r = report ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(EmberColors.SurfaceHigh)
            .padding(16.dp),
    ) {
        Text("Ember closed unexpectedly", style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
        Text(
            r.lines().take(3).joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = EmberColors.TextMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = {
                val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "Ember problem report")
                    .putExtra(android.content.Intent.EXTRA_TEXT, r)
                context.startActivity(android.content.Intent.createChooser(send, "Share problem report"))
            }) { Text("Share details") }
            TextButton(onClick = {
                app.ember.data.CrashReports.clear(context)
                report = null
            }) { Text("Dismiss") }
        }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
            Text(body, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Info(title: String, body: String) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text)
        Text(body, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
    }
}

@Composable
private fun NavRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
            Text(body, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
        }
    }
}

/** One headphone/car gesture and what it does, picked from a short menu. */
@Composable
private fun ActionRow(label: String, value: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text, modifier = Modifier.weight(1f))
            Text(app.ember.playback.ButtonActions.label(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.align(Alignment.TopEnd)) {
            app.ember.playback.ButtonActions.choices.forEach { (action, name) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onPick(action)
                        open = false
                    },
                )
            }
        }
    }
}

/** Mark which paired Bluetooth devices are your car. */
@Composable
private fun CarDevicePicker(vm: MainViewModel, current: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val devices = remember {
        try {
            val bt = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
            @Suppress("MissingPermission")
            bt?.bondedDevices?.map { (it.name ?: it.address) to it.address }?.sortedBy { it.first.lowercase() }.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
    }
    var chosen by remember { mutableStateOf(current.split(',').filter { it.isNotBlank() }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmberColors.SurfaceHigh,
        title = { Text("Which is your car?") },
        text = {
            if (devices.isEmpty()) {
                Text("No paired Bluetooth devices. Pair your car in Android's Bluetooth settings first.")
            } else {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(devices.size) { i ->
                        val (name, address) = devices[i]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { chosen = if (address in chosen) chosen - address else chosen + address }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = address in chosen,
                                onCheckedChange = { chosen = if (it) chosen + address else chosen - address },
                            )
                            Text(name, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.setCarDevices(chosen)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "10 PM", "Midnight", "6 AM" for minutes after midnight. */
private fun clockLabel(min: Int): String {
    val h = (min / 60) % 24
    return when (h) {
        0 -> "Midnight"
        12 -> "Noon"
        else -> "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "AM" else "PM"}"
    }
}
