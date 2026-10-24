@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.ember.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import app.ember.podcasts.ShowStats
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.ember.data.BookEntity
import app.ember.podcasts.EpisodeEntity
import app.ember.podcasts.Feeds
import app.ember.podcasts.PodcastEntity
import app.ember.podcasts.SearchResult
import app.ember.ui.MainViewModel
import app.ember.ui.components.CoverBackdrop
import app.ember.ui.components.EmptyState
import app.ember.ui.components.SectionHeader
import app.ember.ui.components.ThinProgress
import app.ember.ui.theme.EmberColors
import app.ember.ui.theme.onColorFor
import app.ember.util.formatDuration
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

private val placeholderTones = listOf(Color(0xFF34405E), Color(0xFF4B2E5C), Color(0xFF2F5446), Color(0xFF6B4A1E), Color(0xFF5A2238))

/** Square show artwork: saved copy, then the web image, then a lettered tile. */
@Composable
fun ShowArt(show: PodcastEntity?, modifier: Modifier = Modifier, corner: Dp = 10.dp, urlFallback: String? = null) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.aspectRatio(1f).clip(shape).background(EmberColors.SurfaceHigh), contentAlignment = Alignment.Center) {
        val path = show?.artworkPath
        val url = show?.artworkUrl?.takeIf { it.isNotBlank() } ?: urlFallback
        when {
            path != null -> AsyncImage(File(path), show?.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            url != null -> AsyncImage(url, show?.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> {
                val title = show?.title ?: "?"
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(placeholderTones[abs(title.hashCode()) % placeholderTones.size]),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(title.take(1).uppercase(), fontFamily = FontFamily.Serif, fontSize = 28.sp, color = Color(0xFFFBE3BC))
                }
            }
        }
    }
}

internal fun showAccent(show: PodcastEntity?): Color = show?.color?.let { app.ember.ui.theme.readableAccent(Color(it)) } ?: EmberColors.DefaultAccent

internal fun episodeMeta(ep: EpisodeEntity): String {
    val parts = mutableListOf<String>()
    if (ep.publishedAt > 0) parts += DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ep.publishedAt))
    when {
        ep.played -> parts += "Played"
        ep.positionMs > 0 && ep.durationMs > 0 -> parts += "${formatDuration(ep.remainingMs)} left"
        ep.durationMs > 0 -> parts += formatDuration(ep.durationMs)
    }
    return parts.joinToString(" · ")
}

@Composable
internal fun rememberNotificationAsk(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

// ---------------------------------------------------------------- Podcasts tab

@Composable
fun PodcastsScreen(
    vm: MainViewModel,
    onOpenShow: (String) -> Unit,
    onDiscover: () -> Unit,
    onShowSettings: (String) -> Unit = {},
) {
    val shows by vm.podcasts.collectAsState()
    var quickShow by remember { mutableStateOf<String?>(null) }
    val queue by vm.queue.collectAsState()
    val fresh by vm.newEpisodes.collectAsState()
    val stats by vm.showStats.collectAsState()
    val busy by vm.podcastBusy.collectAsState()
    val settings by vm.settings.collectAsState()
    val np by vm.nowPlaying.collectAsState()
    val askNotify = rememberNotificationAsk()
    var tab by rememberSaveable { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var addByLink by remember { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<EpisodeEntity?>(null) }
    val showsById = shows.associateBy { it.id }
    val queuedIds = queue.map { it.id }.toSet()
    val newOnes = fresh.filter { it.id !in queuedIds }
    val listView = settings.podcastListView

    val opml = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            askNotify()
            vm.importOpml(uri)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 20.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Podcasts", style = MaterialTheme.typography.headlineLarge, color = EmberColors.Text, modifier = Modifier.weight(1f))
            if (shows.isNotEmpty()) {
                IconButton(onClick = vm::refreshPodcasts) { Icon(Icons.Rounded.Refresh, "Check for new episodes", tint = EmberColors.Text) }
            }
            // Same as Books: one plus button for every way to add.
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.Add, "Add podcasts", tint = EmberColors.Text) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Find a show") }, onClick = {
                        menu = false
                        onDiscover()
                    })
                    DropdownMenuItem(text = { Text("Add by RSS link") }, onClick = {
                        menu = false
                        addByLink = true
                    })
                    DropdownMenuItem(text = { Text("Import OPML file") }, onClick = {
                        menu = false
                        opml.launch("*/*")
                    })
                }
            }
        }

        busy?.let { msg ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (msg.endsWith("…")) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                if (!msg.endsWith("…")) {
                    IconButton(onClick = vm::clearPodcastMessage) { Icon(Icons.Rounded.Close, "Dismiss", Modifier.size(18.dp)) }
                }
            }
        }

        if (shows.isEmpty()) {
            EmptyState(
                title = "Your shows, right next to your books",
                body = "Search for a show to follow, or bring everything over from another app. In Pocket Casts, Apple Podcasts, Overcast or AntennaPod, look for Export OPML in settings.",
            ) {
                Button(onClick = onDiscover) {
                    Icon(Icons.Rounded.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Find a show")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { opml.launch("*/*") }) { Text("Import OPML file") }
            }
        } else {
            // Section tabs + grid/list switch
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Shows",
                        if (newOnes.isEmpty()) "New" else "New · ${newOnes.size}",
                        if (queue.isEmpty()) "Up Next" else "Up Next · ${queue.size}",
                    ).forEachIndexed { i, label ->
                        FilterChip(
                            selected = tab == i,
                            onClick = { tab = i },
                            label = { Text(label, maxLines = 1) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
                if (tab == 0) {
                    IconButton(onClick = { vm.setPodcastListView(!listView) }) {
                        Icon(
                            if (listView) Icons.Rounded.GridView else Icons.Rounded.ViewList,
                            if (listView) "Show as covers" else "Show as list",
                            tint = EmberColors.TextMuted,
                        )
                    }
                }
            }

            when (tab) {
                0 -> if (listView) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(shows, key = { it.id }) { show ->
                            ShowListRow(show, stats[show.id], onLongClick = { quickShow = show.id }) { onOpenShow(show.id) }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        gridItems(shows, key = { it.id }) { show ->
                            ShowTile(show, stats[show.id]?.newCount ?: 0, onLongClick = { quickShow = show.id }) { onOpenShow(show.id) }
                        }
                    }
                }

                1 -> if (newOnes.isEmpty()) {
                    EmptyState(title = "You're all caught up", body = "New episodes from your shows appear here. Ember checks every few hours.")
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(newOnes, key = { "n" + it.id }) { ep ->
                            EpisodeRow(
                                ep = ep,
                                onTogglePlayed = { vm.setPlayed(ep.id, !ep.played) },
                                onDownloadAction = {
                                    if (ep.downloadState == EpisodeEntity.DL_DONE || ep.downloadState == EpisodeEntity.DL_RUNNING) vm.removeEpisodeDownload(ep) else vm.downloadEpisode(ep)
                                },
                                show = showsById[ep.podcastId],
                                playing = np?.episodeId == ep.id && np?.isPlaying == true,
                                onPlay = { if (np?.episodeId == ep.id) vm.togglePlay() else vm.playEpisode(ep.id) },
                                onMore = { sheetFor = ep },
                                onArchive = { vm.setArchived(ep.id, true) },
                            )
                        }
                    }
                }

                else -> if (queue.isEmpty()) {
                    EmptyState(
                        title = "Nothing queued",
                        body = "Tap any episode and choose Play next or Add to Up Next. Episodes play straight through in this order.",
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                        items(queue, key = { "q" + it.id }) { ep ->
                            val i = queue.indexOf(ep)
                            EpisodeRow(
                                ep = ep,
                                onTogglePlayed = { vm.setPlayed(ep.id, !ep.played) },
                                onDownloadAction = {
                                    if (ep.downloadState == EpisodeEntity.DL_DONE || ep.downloadState == EpisodeEntity.DL_RUNNING) vm.removeEpisodeDownload(ep) else vm.downloadEpisode(ep)
                                },
                                show = showsById[ep.podcastId],
                                playing = np?.episodeId == ep.id && np?.isPlaying == true,
                                onPlay = { if (np?.episodeId == ep.id) vm.togglePlay() else vm.playEpisode(ep.id) },
                                onMore = { sheetFor = ep },
                                trailing = {
                                    Column {
                                        if (i > 0) {
                                            Icon(
                                                Icons.Rounded.KeyboardArrowUp,
                                                "Move up",
                                                tint = EmberColors.TextMuted,
                                                modifier = Modifier.size(22.dp).clickable { vm.moveInQueue(ep.id, up = true) },
                                            )
                                        }
                                        if (i < queue.size - 1) {
                                            Icon(
                                                Icons.Rounded.KeyboardArrowDown,
                                                "Move down",
                                                tint = EmberColors.TextMuted,
                                                modifier = Modifier.size(22.dp).clickable { vm.moveInQueue(ep.id, up = false) },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (addByLink) {
        NameDialog(title = "Add by RSS link", placeholder = "https://example.com/feed.xml", onDismiss = { addByLink = false }) {
            askNotify()
            vm.follow(it)
            addByLink = false
        }
    }

    sheetFor?.let { ep ->
        EpisodeSheet(vm, ep.id, inQueue = ep.id in queuedIds, onDismiss = { sheetFor = null }, onOpenShow = {
            sheetFor = null
            onOpenShow(ep.podcastId)
        })
    }
    quickShow?.let { id ->
        ShowQuickSheet(
            vm = vm,
            podcastId = id,
            onDismiss = { quickShow = null },
            onOpenShow = { onOpenShow(id) },
            onSettings = { onShowSettings(id) },
        )
    }
}

/** Cover tile with a "new episodes" count. */
@Composable
private fun ShowTile(show: PodcastEntity, newCount: Int, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = "Show settings",
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    ) {
        Box {
            ShowArt(show, Modifier.fillMaxWidth(), corner = 12.dp)
            if (newCount > 0) {
                Text(
                    if (newCount > 99) "99+" else "$newCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = onColorFor(showAccent(show)),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(showAccent(show))
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(show.title, style = MaterialTheme.typography.labelLarge, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ShowListRow(show: PodcastEntity, stats: ShowStats?, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = "Show settings",
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShowArt(show, Modifier.size(64.dp), corner = 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(show.title, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (show.author.isNotBlank()) {
                Text(show.author, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            stats?.latest?.takeIf { it > 0 }?.let {
                Text(
                    "Latest: " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)),
                    style = MaterialTheme.typography.labelSmall,
                    color = EmberColors.TextFaint,
                )
            }
        }
        val n = stats?.newCount ?: 0
        if (n > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                "$n new",
                style = MaterialTheme.typography.labelMedium,
                color = onColorFor(showAccent(show)),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(showAccent(show))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun EpisodeRow(
    ep: EpisodeEntity,
    show: PodcastEntity?,
    playing: Boolean,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    showArt: Boolean = true,
    onTogglePlayed: (() -> Unit)? = null,
    onDownloadAction: (() -> Unit)? = null,
    onArchive: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Column {
        if (onTogglePlayed != null && onDownloadAction != null) {
            EpisodeSwipe(ep, onTogglePlayed, onDownloadAction, onArchive) {
                EpisodeRowContent(ep, show, playing, onPlay, onMore, showArt, trailing)
            }
        } else {
            EpisodeRowContent(ep, show, playing, onPlay, onMore, showArt, trailing)
        }
        // A hairline between episodes so each one reads as its own tap target.
        HorizontalDivider(
            Modifier.padding(start = if (showArt) 86.dp else 20.dp, end = 16.dp),
            color = EmberColors.Wash.copy(alpha = 0.10f),
        )
    }
}

/**
 * Swipe right: mark played (which archives it) or unplayed.
 * Swipe left: remove the download if there is one, otherwise archive the episode.
 */
@Composable
private fun EpisodeSwipe(
    ep: EpisodeEntity,
    onTogglePlayed: () -> Unit,
    onDownloadAction: () -> Unit,
    onArchive: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    val hasDownload = ep.downloadState == EpisodeEntity.DL_DONE || ep.downloadState == EpisodeEntity.DL_RUNNING
    val leftArchives = !hasDownload && onArchive != null
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            when (v) {
                SwipeToDismissBoxValue.StartToEnd -> onTogglePlayed()
                SwipeToDismissBoxValue.EndToStart -> if (leftArchives) onArchive?.invoke() else onDownloadAction()
                SwipeToDismissBoxValue.Settled -> {}
            }
            false // the row springs back; the change shows in its labels
        },
        positionalThreshold = { it * 0.3f },
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val dir = state.dismissDirection
            val (bg, icon, label, alignEnd) = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> SwipeLook(
                    EmberColors.Success,
                    Icons.Rounded.CheckCircle,
                    if (ep.played) "Mark unplayed" else "Mark played",
                    false,
                )
                SwipeToDismissBoxValue.EndToStart -> when {
                    hasDownload -> SwipeLook(MaterialTheme.colorScheme.error, Icons.Rounded.Delete, "Remove download", true)
                    leftArchives -> SwipeLook(EmberColors.TextMuted, Icons.Rounded.Archive, if (ep.archived) "Unarchive" else "Archive", true)
                    else -> SwipeLook(MaterialTheme.colorScheme.primary, Icons.Rounded.Download, "Download", true)
                }
                else -> SwipeLook(Color.Transparent, null, "", false)
            }
            Row(
                Modifier
                    .fillMaxSize()
                    .background(bg)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
            ) {
                if (icon != null) {
                    val fg = onColorFor(bg)
                    if (!alignEnd) Icon(icon, null, tint = fg)
                    Spacer(Modifier.width(10.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
                    Spacer(Modifier.width(10.dp))
                    if (alignEnd) Icon(icon, null, tint = fg)
                }
            }
        },
    ) {
        Box(Modifier.background(EmberColors.Background)) { content() }
    }
}

private data class SwipeLook(
    val bg: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector?,
    val label: String,
    val alignEnd: Boolean,
)

@Composable
private fun EpisodeRowContent(
    ep: EpisodeEntity,
    show: PodcastEntity?,
    playing: Boolean,
    onPlay: () -> Unit,
    onMore: () -> Unit,
    showArt: Boolean,
    trailing: @Composable () -> Unit,
) {
    val accent = showAccent(show)
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onMore,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onMore()
                },
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt) {
            ShowArt(show, Modifier.size(58.dp), corner = 8.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            if (showArt && show != null) {
                Text(show.title, style = MaterialTheme.typography.labelMedium, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
            }
            Text(
                ep.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (ep.played) EmberColors.TextMuted else EmberColors.Text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (ep.downloadState) {
                    EpisodeEntity.DL_DONE -> Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = accent, modifier = Modifier.size(14.dp))
                    EpisodeEntity.DL_RUNNING -> Icon(Icons.Rounded.Downloading, "Downloading", tint = EmberColors.TextMuted, modifier = Modifier.size(14.dp))
                }
                if (ep.downloadState == EpisodeEntity.DL_DONE || ep.downloadState == EpisodeEntity.DL_RUNNING) Spacer(Modifier.width(4.dp))
                Text(episodeMeta(ep), style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
            if (ep.positionMs > 0 && !ep.played && ep.durationMs > 0) {
                Spacer(Modifier.height(5.dp))
                Box(Modifier.width(120.dp)) { ThinProgress(ep.progress, color = accent, height = 2.dp) }
            }
        }
        trailing()
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.18f))
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = accent)
        }
    }
}

@Composable
fun EpisodeSheet(vm: MainViewModel, episodeId: String, inQueue: Boolean, onDismiss: () -> Unit, onOpenShow: () -> Unit) {
    val epState by remember(episodeId) { vm.observeEpisode(episodeId) }.collectAsState(initial = null)
    val ep = epState ?: return
    val showState by remember(ep.podcastId) { vm.observePodcast(ep.podcastId) }.collectAsState(initial = null)
    val show = showState
    val accent = showAccent(show)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = EmberColors.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onOpenShow)) {
                ShowArt(show, Modifier.size(44.dp), corner = 8.dp)
                Spacer(Modifier.width(12.dp))
                Text(show?.title ?: "", style = MaterialTheme.typography.labelLarge, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            Text(ep.title, style = MaterialTheme.typography.headlineSmall, color = EmberColors.Text)
            Spacer(Modifier.height(4.dp))
            Text(episodeMeta(ep), style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    vm.playEpisode(ep.id)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = accent, contentColor = onColorFor(accent)),
            ) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(if (ep.positionMs > 0 && !ep.played) "Resume" else "Play")
            }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (inQueue) {
                    SheetChip("Remove from Up Next") { vm.removeFromQueue(ep.id) }
                } else {
                    SheetChip("Play next") { vm.addToQueue(ep.id, next = true) }
                    SheetChip("Add to Up Next") { vm.addToQueue(ep.id, next = false) }
                }
                SheetChip(if (ep.played) "Mark unplayed" else "Mark played") {
                    vm.setPlayed(ep.id, !ep.played)
                    // Close so the Undo bar can be seen.
                    if (!ep.played) onDismiss()
                }
                SheetChip(if (ep.archived) "Unarchive" else "Archive") {
                    vm.setArchived(ep.id, !ep.archived)
                    if (!ep.archived) onDismiss()
                }
                when (ep.downloadState) {
                    EpisodeEntity.DL_DONE -> SheetChip("Remove download") { vm.removeEpisodeDownload(ep) }
                    EpisodeEntity.DL_RUNNING -> SheetChip("Cancel download") { vm.removeEpisodeDownload(ep) }
                    else -> SheetChip("Download") { vm.downloadEpisode(ep) }
                }
            }
            if (ep.description.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = EmberColors.SurfaceHigh)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    Text(ep.description, style = MaterialTheme.typography.bodyMedium, color = EmberColors.TextMuted)
                }
            }
        }
    }
}

@Composable
private fun SheetChip(label: String, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, label = { Text(label) })
}

// ---------------------------------------------------------------- Find shows

@Composable
fun DiscoverScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
    onOpenShow: (String) -> Unit,
    onPreview: (SearchResult) -> Unit = {},
) {
    val shows by vm.podcasts.collectAsState()
    val busy by vm.podcastBusy.collectAsState()
    val askNotify = rememberNotificationAsk()
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val followed = shows.map { it.id }.toSet()

    fun search() {
        if (query.isBlank()) return
        searching = true
        scope.launch {
            results = vm.searchPodcasts(query.trim())
            searching = false
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
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
            Text("Find a show", style = MaterialTheme.typography.titleLarge, color = EmberColors.Text)
        }
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                if (it.isBlank()) results = null
            },
            placeholder = { Text("Show name, topic or host") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = EmberColors.Surface,
                focusedContainerColor = EmberColors.Surface,
                unfocusedBorderColor = EmberColors.Surface,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        busy?.takeIf { !it.endsWith("…") }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp))
        }
        val resultRow: @Composable (SearchResult, Int?) -> Unit = { r, rank ->
            val id = Feeds.podcastId(r.feedUrl)
            val isFollowed = id in followed
            Row(
                Modifier
                    .fillMaxWidth()
                    // Any show opens: ones you follow go to their page, others to a preview.
                    .clickable { if (isFollowed) onOpenShow(id) else onPreview(r) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (rank != null) {
                    Text(
                        "$rank",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (rank <= 3) MaterialTheme.colorScheme.primary else EmberColors.TextFaint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(30.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                ShowArt(null, Modifier.size(64.dp), corner = 10.dp, urlFallback = r.artworkUrl)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(r.author, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                when {
                    isFollowed -> TextButton(onClick = { onOpenShow(id) }) { Text("Following") }
                    following == r.feedUrl -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else -> OutlinedButton(onClick = {
                        askNotify()
                        following = r.feedUrl
                        vm.follow(r.feedUrl, r.artworkUrl) { following = null }
                    }) {
                        Icon(Icons.Rounded.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Follow")
                    }
                }
            }
        }

        when {
            searching -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            results == null -> TopCharts(vm, resultRow)
            results.orEmpty().isEmpty() -> EmptyState(title = "No shows found", body = "Try fewer words or a host's name.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(results.orEmpty(), key = { it.feedUrl }) { r -> resultRow(r, null) }
            }
        }
    }
    LaunchedEffect(Unit) { vm.clearPodcastMessage() }
}

/** Apple Podcasts top charts, with a row of categories to pick from. */
@Composable
private fun TopCharts(vm: MainViewModel, row: @Composable (SearchResult, Int?) -> Unit) {
    var genre by rememberSaveable { mutableStateOf(-1) }
    var shows by remember { mutableStateOf<List<SearchResult>?>(null) }
    val categories = remember { listOf("Top overall" to -1) + Feeds.genres }
    LaunchedEffect(genre) {
        shows = null
        shows = vm.topPodcasts(genre, Feeds.genres.firstOrNull { it.second == genre }?.first)
    }
    Column(Modifier.fillMaxSize()) {
        Text(
            "Top shows",
            style = MaterialTheme.typography.titleMedium,
            color = EmberColors.Text,
            modifier = Modifier.padding(start = 20.dp, top = 8.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            categories.forEach { (name, id) ->
                FilterChip(
                    selected = genre == id,
                    onClick = { genre = id },
                    label = { Text(name, maxLines = 1) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
        val list = shows
        when {
            list == null -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyState(title = "Charts aren't loading", body = "Check your connection, or search for a show above.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(list, key = { _, r -> r.feedUrl }) { i, r -> row(r, i + 1) }
            }
        }
    }
}

