@file:OptIn(ExperimentalLayoutApi::class)

package app.ember.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.BookEntity
import app.ember.podcasts.EpisodeEntity
import app.ember.podcasts.Feeds
import app.ember.podcasts.ParsedEpisode
import app.ember.podcasts.ParsedFeed
import app.ember.podcasts.PodcastEntity
import app.ember.ui.MainViewModel
import app.ember.ui.components.CoverBackdrop
import app.ember.ui.theme.EmberColors
import app.ember.ui.theme.onColorFor
import app.ember.util.formatDuration
import app.ember.util.formatSpeed
import java.text.DateFormat
import java.util.Date

private const val PAGE = 30

/** Big centered cover, then category, title and author, like the show pages in other podcast apps. */
@Composable
private fun ShowHeader(
    show: PodcastEntity,
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val accent = showAccent(show)
    Box(Modifier.fillMaxWidth()) {
        CoverBackdrop(
            BookEntity(id = show.id, title = show.title, coverPath = show.artworkPath, coverColor = show.color),
            Modifier.matchParentSize(),
        )
        Column(Modifier.fillMaxWidth().statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
            }
            ShowArt(
                show,
                Modifier
                    .widthIn(max = 240.dp)
                    .fillMaxWidth(0.62f)
                    .shadow(20.dp, RoundedCornerShape(16.dp)),
                corner = 16.dp,
                urlFallback = show.artworkUrl,
            )
            Spacer(Modifier.height(18.dp))
            val sub = listOf(show.category, show.author).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodyMedium,
                    color = accent,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(
                show.title,
                style = MaterialTheme.typography.headlineMedium,
                color = EmberColors.Text,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(14.dp))
            actions()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, on: Boolean, accent: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(if (on) accent else EmberColors.Wash.copy(alpha = 0.10f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (on) onColorFor(accent) else EmberColors.Text, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = EmberColors.TextMuted, maxLines = 1, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------- A followed show

@Composable
fun PodcastDetailScreen(vm: MainViewModel, podcastId: String, onBack: () -> Unit, onSettings: () -> Unit = {}) {
    val showState by remember(podcastId) { vm.observePodcast(podcastId) }.collectAsState(initial = null)
    val episodes by remember(podcastId) { vm.observeEpisodes(podcastId) }.collectAsState(initial = emptyList())
    val queue by vm.queue.collectAsState()
    val np by vm.nowPlaying.collectAsState()
    val askNotify = rememberNotificationAsk()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var limit by rememberSaveable(filter) { mutableIntStateOf(PAGE) }
    var expanded by remember { mutableStateOf(false) }
    var confirmUnfollow by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<EpisodeEntity?>(null) }
    val show = showState ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val accent = showAccent(show)
    val queued = queue.map { it.id }.toSet()
    val archivedCount = episodes.count { it.archived }
    val all = when (filter) {
        1 -> episodes.filter { !it.played && !it.archived }
        2 -> episodes.filter { it.downloadState == EpisodeEntity.DL_DONE || it.downloadState == EpisodeEntity.DL_RUNNING }
        3 -> episodes.filter { it.archived }
        else -> episodes.filter { !it.archived }
    }
    val shown = all.take(limit)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            ShowHeader(show, onBack) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RoundAction(Icons.Rounded.Check, "Following", true, accent) { confirmUnfollow = true }
                    RoundAction(
                        if (show.notify) Icons.Rounded.Notifications else Icons.Rounded.NotificationsNone,
                        "Alerts",
                        show.notify,
                        accent,
                    ) {
                        if (!show.notify) askNotify()
                        vm.setPodcastOptions(show.id, notify = !show.notify)
                    }
                    RoundAction(Icons.Rounded.DownloadForOffline, "Auto-get", show.autoDownload, accent) {
                        vm.setPodcastOptions(show.id, autoDownload = !show.autoDownload)
                    }
                    RoundAction(Icons.Rounded.Settings, "Settings", false, accent, onSettings)
                }
            }
        }
        if (show.description.isNotBlank()) {
            item {
                Text(
                    show.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = EmberColors.Text,
                    maxLines = if (expanded) Int.MAX_VALUE else 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .clickable { expanded = !expanded },
                )
            }
        }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("Episodes", "Unplayed", "Downloaded", if (archivedCount > 0) "Archived · $archivedCount" else "Archived").forEachIndexed { i, label ->
                    FilterChip(
                        selected = filter == i,
                        onClick = { filter = i },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = EmberColors.Wash.copy(alpha = 0.10f))
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    when (filter) {
                        2 -> "Nothing downloaded."
                        3 -> "Nothing archived. Played episodes, and ones you archive, go here."
                        else -> "No episodes here."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = EmberColors.TextMuted,
                    modifier = Modifier.padding(20.dp),
                )
            }
        }
        items(shown, key = { it.id }) { ep ->
            EpisodeRow(
                ep = ep,
                onTogglePlayed = { vm.setPlayed(ep.id, !ep.played) },
                onDownloadAction = {
                    if (ep.downloadState == EpisodeEntity.DL_DONE || ep.downloadState == EpisodeEntity.DL_RUNNING) vm.removeEpisodeDownload(ep) else vm.downloadEpisode(ep)
                },
                onArchive = { vm.setArchived(ep.id, !ep.archived) },
                show = show,
                playing = np?.episodeId == ep.id && np?.isPlaying == true,
                onPlay = { if (np?.episodeId == ep.id) vm.togglePlay() else vm.playEpisode(ep.id) },
                onMore = { sheetFor = ep },
                showArt = false,
            )
        }
        if (all.size > shown.size) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    OutlinedButton(onClick = { limit += PAGE }) {
                        Text("Load more · ${all.size - shown.size} older")
                    }
                }
            }
        }
    }

    sheetFor?.let { ep ->
        EpisodeSheet(vm, ep.id, inQueue = ep.id in queued, onDismiss = { sheetFor = null }, onOpenShow = { sheetFor = null })
    }

    if (confirmUnfollow) {
        UnfollowDialog(show, onDismiss = { confirmUnfollow = false }) {
            confirmUnfollow = false
            vm.unfollow(show.id)
            onBack()
        }
    }
}

@Composable
internal fun UnfollowDialog(show: PodcastEntity, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = EmberColors.SurfaceHigh,
        title = { Text("Unfollow ${show.title}?") },
        text = { Text("Its episodes and downloads are removed from Ember.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Unfollow") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- A show's own settings

@Composable
fun ShowSettingsScreen(vm: MainViewModel, podcastId: String, onBack: () -> Unit, onUnfollowed: () -> Unit) {
    val showState by remember(podcastId) { vm.observePodcast(podcastId) }.collectAsState(initial = null)
    val settings by vm.settings.collectAsState()
    val askNotify = rememberNotificationAsk()
    var confirmUnfollow by remember { mutableStateOf(false) }
    val show = showState ?: return
    val accent = showAccent(show)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
                Text(
                    show.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            SettingSwitch(Icons.Rounded.Notifications, "Notifications", "Alert me when a new episode comes out", show.notify, accent) {
                if (it) askNotify()
                vm.updateShow(show.id) { s -> s.copy(notify = it) }
            }
            SettingSwitch(
                Icons.Rounded.Download,
                "Auto download",
                "Download new episodes automatically, up to ${settings.autoDownloadCount} unplayed at a time. Older episodes are never downloaded to fill in.",
                show.autoDownload,
                accent,
            ) { vm.updateShow(show.id) { s -> s.copy(autoDownload = it) } }
            SettingSwitch(Icons.Rounded.PlaylistAdd, "Add to Up Next", "Queue new episodes automatically", show.autoQueue, accent) {
                vm.updateShow(show.id) { s -> s.copy(autoQueue = it) }
            }
            SettingSwitch(Icons.Rounded.Archive, "Auto archive", "Archive episodes once you've played them", show.autoArchive, accent) {
                vm.updateShow(show.id) { s -> s.copy(autoArchive = it) }
            }
            SettingDivider()
        }
        item {
            SettingTitle(Icons.Rounded.GraphicEq, "Playback effects", if (show.customEffects) "Just for this show" else "Same as the rest of Ember", accent)
            Column(Modifier.padding(start = 72.dp, end = 20.dp)) {
                Text("Speed", style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0.8f, 1f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2f).forEach { sp ->
                        FilterChip(
                            selected = show.speed == sp,
                            onClick = { vm.setShowSpeed(show.id, sp) },
                            label = { Text(formatSpeed(sp)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent, selectedLabelColor = onColorFor(accent)),
                        )
                    }
                }
            }
            SettingSwitch(null, "Use effects just for this show", "Otherwise the show follows the app's trim silence and voice boost", show.customEffects, accent) {
                vm.updateShow(show.id) { s -> s.copy(customEffects = it, trimSilence = if (it) settings.skipSilence else s.trimSilence, voiceBoost = if (it) settings.narrationBoost else s.voiceBoost) }
            }
            if (show.customEffects) {
                SettingSwitch(null, "Trim silence", "Shortens long pauses", show.trimSilence, accent) {
                    vm.updateShow(show.id) { s -> s.copy(trimSilence = it) }
                }
                SettingSwitch(null, "Voice boost", "Makes voices clearer and more even", show.voiceBoost, accent) {
                    vm.updateShow(show.id) { s -> s.copy(voiceBoost = it) }
                }
            }
            SettingDivider()
        }
        item {
            SettingStepper(Icons.Rounded.SkipNext, "Skip first", show.skipIntroSec, accent, "Jumps past the intro when an episode starts from the beginning") { v ->
                vm.updateShow(show.id) { it.copy(skipIntroSec = v) }
            }
            SettingStepper(Icons.Rounded.SkipPrevious, "Skip last", show.skipOutroSec, accent, "Finishes the episode this long before the end") { v ->
                vm.updateShow(show.id) { it.copy(skipOutroSec = v) }
            }
            SettingDivider()
        }
        item {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { confirmUnfollow = true }) {
                    Text("Unfollow", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (confirmUnfollow) {
        UnfollowDialog(show, onDismiss = { confirmUnfollow = false }) {
            confirmUnfollow = false
            vm.unfollow(show.id)
            onUnfollowed()
        }
    }
}

@Composable
private fun SettingDivider() {
    HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 20.dp), color = EmberColors.Wash.copy(alpha = 0.10f))
}

@Composable
private fun SettingTitle(icon: ImageVector?, title: String, subtitle: String?, accent: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(52.dp)) { if (icon != null) Icon(icon, null, tint = accent, modifier = Modifier.size(28.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = accent)
        }
    }
}

@Composable
private fun SettingSwitch(icon: ImageVector?, title: String, subtitle: String?, checked: Boolean, accent: Color, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(52.dp)) { if (icon != null) Icon(icon, null, tint = accent, modifier = Modifier.size(28.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = EmberColors.TextMuted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** A number of seconds with − and + buttons (5 seconds a step). */
@Composable
private fun SettingStepper(icon: ImageVector, title: String, seconds: Int, accent: Color, note: String, onChange: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(52.dp)) { Icon(icon, null, tint = accent, modifier = Modifier.size(28.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
            Text(if (seconds == 0) "Off" else "$seconds secs", style = MaterialTheme.typography.bodyLarge, color = accent)
            Text(note, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
        }
        StepButton(Icons.Rounded.Remove, "Less", seconds > 0, accent) { onChange((seconds - 5).coerceAtLeast(0)) }
        Spacer(Modifier.width(12.dp))
        StepButton(Icons.Rounded.Add, "More", seconds < 600, accent) { onChange((seconds + 5).coerceAtMost(600)) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, label: String, enabled: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (enabled) accent else EmberColors.TextFaint, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = if (enabled) accent else EmberColors.TextFaint)
    }
}

// ---------------------------------------------------------------- A show you don't follow yet

/** See a show's details and latest episodes before following it. */
@Composable
fun PodcastPreviewScreen(
    vm: MainViewModel,
    feedUrl: String,
    artworkUrl: String,
    onBack: () -> Unit,
    onFollowed: (String) -> Unit,
) {
    val shows by vm.podcasts.collectAsState()
    val askNotify = rememberNotificationAsk()
    var feed by remember { mutableStateOf<ParsedFeed?>(null) }
    var failed by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    var limit by remember { mutableIntStateOf(PAGE) }
    val id = Feeds.podcastId(feedUrl)
    val already = shows.any { it.id == id }

    LaunchedEffect(feedUrl) {
        val f = vm.previewFeed(feedUrl)
        if (f == null) failed = true else feed = f
    }

    val f = feed
    val show = PodcastEntity(
        id = id,
        feedUrl = feedUrl,
        title = f?.title ?: "",
        author = f?.author ?: "",
        description = f?.description ?: "",
        artworkUrl = f?.artworkUrl?.ifBlank { artworkUrl } ?: artworkUrl,
        category = f?.category ?: "",
    )

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            ShowHeader(show, onBack) {
                when {
                    already -> OutlinedButton(onClick = { onFollowed(id) }) { Text("Following · open") }
                    following -> CircularProgressIndicator(Modifier.size(28.dp))
                    else -> Button(
                        onClick = {
                            askNotify()
                            following = true
                            vm.follow(feedUrl, artworkUrl) { newId ->
                                following = false
                                if (newId != null) onFollowed(newId)
                            }
                        },
                        enabled = f != null,
                        modifier = Modifier.height(48.dp),
                    ) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Follow")
                    }
                }
            }
        }
        when {
            failed -> item {
                Text(
                    "Couldn't load this show. Check your connection and try again.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = EmberColors.TextMuted,
                    modifier = Modifier.padding(20.dp),
                )
            }
            f == null -> item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            else -> {
                if (f.description.isNotBlank()) {
                    item {
                        Text(
                            f.description,
                            style = MaterialTheme.typography.bodyLarge,
                            color = EmberColors.Text,
                            maxLines = if (expanded) Int.MAX_VALUE else 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                                .clickable { expanded = !expanded },
                        )
                    }
                }
                item {
                    Text(
                        "${f.episodes.size} episodes",
                        style = MaterialTheme.typography.titleMedium,
                        color = EmberColors.Text,
                        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = EmberColors.Wash.copy(alpha = 0.10f))
                }
                val list = f.episodes.sortedByDescending { it.publishedAt }
                items(list.take(limit), key = { it.guid }) { ep ->
                    PreviewEpisode(ep, open == ep.guid) { open = if (open == ep.guid) null else ep.guid }
                }
                if (list.size > limit) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            OutlinedButton(onClick = { limit += PAGE }) { Text("Load more · ${list.size - limit} older") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewEpisode(ep: ParsedEpisode, open: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(ep.title, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, maxLines = if (open) 6 else 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                ep.publishedAt.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
                ep.durationMs.takeIf { it > 0 }?.let { formatDuration(it) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = EmberColors.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (open && ep.description.isNotBlank()) {
            Text(ep.description, style = MaterialTheme.typography.bodyMedium, color = EmberColors.TextMuted, modifier = Modifier.padding(top = 8.dp))
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = EmberColors.Wash.copy(alpha = 0.10f))
}
