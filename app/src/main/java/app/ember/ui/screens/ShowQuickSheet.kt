@file:OptIn(ExperimentalMaterial3Api::class)

package app.ember.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import app.ember.ui.theme.onColorFor

/** A show's everyday settings from a long-press on its artwork, without opening the show. */
@Composable
fun ShowQuickSheet(
    vm: MainViewModel,
    podcastId: String,
    onDismiss: () -> Unit,
    onOpenShow: () -> Unit,
    onSettings: () -> Unit,
) {
    val showState by remember(podcastId) { vm.observePodcast(podcastId) }.collectAsState(initial = null)
    val show = showState ?: return
    val episodes by remember(podcastId) { vm.observeEpisodes(podcastId) }.collectAsState(initial = emptyList())
    val stats by vm.showStats.collectAsState()
    val askNotify = rememberNotificationAsk()
    val accent = showAccent(show)
    val newCount = stats[podcastId]?.newCount ?: 0
    val latest = episodes.firstOrNull { !it.played && !it.archived }
    var confirmUnfollow by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = EmberColors.Surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                ShowArt(show, Modifier.size(84.dp), corner = 10.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(show.title, style = MaterialTheme.typography.titleLarge, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (show.author.isNotBlank()) {
                        Text(show.author, style = MaterialTheme.typography.bodyMedium, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        if (newCount > 0) "$newCount new" else "All caught up",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmberColors.TextMuted,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (latest != null) {
                    Button(
                        onClick = {
                            vm.playEpisode(latest.id)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onColorFor(accent)),
                        modifier = Modifier.weight(1f).height(50.dp),
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (latest.positionMs > 0) "Resume latest" else "Play latest", maxLines = 1)
                    }
                }
                OutlinedButton(
                    onClick = {
                        onDismiss()
                        onOpenShow()
                    },
                    modifier = Modifier.height(50.dp).then(if (latest == null) Modifier.weight(1f) else Modifier),
                ) {
                    Icon(Icons.Rounded.Info, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Episodes")
                }
            }
            latest?.let {
                Text(
                    it.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = EmberColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            QuickSwitch("Notify me about new episodes", show.notify) {
                if (it) askNotify()
                vm.setPodcastOptions(show.id, notify = it)
            }
            QuickSwitch("Download new episodes", show.autoDownload) { vm.setPodcastOptions(show.id, autoDownload = it) }
            QuickSwitch("Add new episodes to Up Next", show.autoQueue) { on -> vm.updateShow(show.id) { p -> p.copy(autoQueue = on) } }

            HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = EmberColors.Wash.copy(alpha = 0.10f))

            QuickAction(Icons.Rounded.Refresh, "Check for new episodes") {
                vm.refreshShow(show.id)
                onDismiss()
            }
            if (newCount > 0 || latest != null) {
                QuickAction(Icons.Rounded.DoneAll, "Mark all as played") {
                    vm.markAllPlayed(show.id)
                    onDismiss()
                }
            }
            QuickAction(Icons.Rounded.Tune, "All show settings") {
                onDismiss()
                onSettings()
            }
            QuickAction(Icons.Rounded.PersonRemove, "Unfollow", danger = true) { confirmUnfollow = true }
        }
    }

    if (confirmUnfollow) {
        UnfollowDialog(show, onDismiss = { confirmUnfollow = false }) {
            confirmUnfollow = false
            vm.unfollow(show.id)
            onDismiss()
        }
    }
}

@Composable
private fun QuickSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (danger) MaterialTheme.colorScheme.error else EmberColors.TextMuted
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else EmberColors.Text)
    }
}
