@file:OptIn(ExperimentalMaterial3Api::class)

package app.ember.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.data.ChapterEntity
import app.ember.data.ListenEventEntity
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import app.ember.util.formatDuration
import app.ember.util.formatSpeed
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import kotlin.math.abs

/**
 * Every play, pause, skip and jump for one book, newest first. Tap one to go back to that spot,
 * so an accidental skip or scrubber slip never loses your place.
 */
@Composable
fun ListenLogSheet(
    vm: MainViewModel,
    bookId: String,
    bookTitle: String,
    chapters: List<ChapterEntity>,
    durationMs: Long,
    onDismiss: () -> Unit,
) {
    val events by remember(bookId) { vm.observeListenLog(bookId) }.collectAsState(initial = null)
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val tall = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = EmberColors.Surface,
    ) {
        Column(Modifier.fillMaxWidth().height(tall)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Listening log", style = MaterialTheme.typography.titleLarge, color = EmberColors.Text)
                    Text(bookTitle, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!events.isNullOrEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear all") }
            }
            Text(
                "Tap any entry to go back to that spot.",
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextMuted,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 8.dp),
            )
            val list = events
            when {
                list == null -> {}
                list.isEmpty() -> Box(Modifier.fillMaxWidth().weight(1f).padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Nothing yet. Every play, pause, skip and jump will show up here, so you can always get back to where you were.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = EmberColors.TextMuted,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(
                    Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    var lastDay = ""
                    list.forEach { e ->
                        val day = dayLabel(e.at)
                        if (day != lastDay) {
                            lastDay = day
                            item(key = "d$day") {
                                Text(
                                    day,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = EmberColors.Text,
                                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
                                )
                            }
                        }
                        item(key = e.id) {
                            LogRow(
                                vm,
                                e,
                                chapters,
                                durationMs,
                                onAwake = {
                                    vm.goToLogEntry(e.copy(positionMs = e.toMs))
                                    Toast.makeText(context, "Back to " + vm.describeSpot(chapters, e.toMs), Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                },
                            ) {
                                vm.goToLogEntry(e)
                                Toast.makeText(context, "Back to " + vm.describeSpot(chapters, e.positionMs), Toast.LENGTH_SHORT).show()
                                onDismiss()
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Clear the listening log?") },
            text = { Text("This only clears the log for this book. Your place in the book stays where it is.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clearListenLog(bookId)
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LogRow(vm: MainViewModel, e: ListenEventEntity, chapters: List<ChapterEntity>, durationMs: Long, onAwake: () -> Unit = {}, onClick: () -> Unit) {
    val delta = e.toMs - e.positionMs
    val toChapter = if (e.kind == ListenEventEntity.JUMP) chapters.firstOrNull { abs(it.startMs - e.toMs) < 1_500 } else null
    val (icon, title) = when (e.kind) {
        ListenEventEntity.PLAY -> Icons.Rounded.PlayArrow to "Play" + if (e.reason == ListenEventEntity.REASON_FLIPPED) " · phone turned up" else ""
        ListenEventEntity.PAUSE -> Icons.Rounded.Pause to "Pause" + when (e.reason) {
            ListenEventEntity.REASON_SLEEP -> " · sleep timer"
            ListenEventEntity.REASON_UNPLUGGED -> " · headphones disconnected"
            ListenEventEntity.REASON_OTHER_APP -> " · another app"
            ListenEventEntity.REASON_FINISHED -> " · finished"
            ListenEventEntity.REASON_FLIPPED -> " · phone turned over"
            else -> ""
        }
        ListenEventEntity.SKIP -> (if (delta < 0) Icons.Rounded.FastRewind else Icons.Rounded.FastForward) to
            (if (delta < 0) "Skipped back " else "Skipped forward ") + amount(abs(delta))
        else -> Icons.Rounded.SwapVert to when {
            toChapter != null -> "Went to ${toChapter.title}"
            delta < 0 -> "Jumped back " + amount(abs(delta))
            else -> "Jumped ahead " + amount(delta)
        }
    }
    val moved = e.kind == ListenEventEntity.SKIP || e.kind == ListenEventEntity.JUMP
    val left = ((durationMs - e.positionMs).coerceAtLeast(0) / e.speed.coerceAtLeast(0.1f)).toLong()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(EmberColors.SurfaceHigh)
            .clickable(onClickLabel = "Go back to this spot", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (moved) "From " else "") + vm.describeSpot(chapters, e.positionMs),
                style = MaterialTheme.typography.bodyMedium,
                color = EmberColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.at)),
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextMuted,
            )
            // The sleep timer remembers where you were when you last set or shook it.
            if (e.kind == ListenEventEntity.PAUSE && e.reason == ListenEventEntity.REASON_SLEEP && e.toMs >= 0) {
                Text(
                    "Last awake around " + vm.describeSpot(chapters, e.toMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = "Go back to where you were last awake", onClick = onAwake)
                        .padding(vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("${formatDuration(left)} left", style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
            if (e.speed != 1f) Text("at ${formatSpeed(e.speed)}", style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
        }
    }
}

private fun amount(ms: Long): String {
    val sec = (ms / 1000).toInt()
    return if (sec < 3600) formatSkip(sec) else formatDuration(ms)
}

private fun dayLabel(at: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(day, today)) return "Today"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (same(day, today)) return "Yesterday"
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(at))
}

/** Small pills under the title in the player: Listening log, and PDF when the book has one. */
@Composable
fun PlayerExtrasRow(onLog: () -> Unit, onPdf: (() -> Unit)?, tint: Color, modifier: Modifier = Modifier, onCar: (() -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        ExtraPill(Icons.Rounded.History, "Listening log", tint, onLog)
        if (onPdf != null) ExtraPill(Icons.Rounded.PictureAsPdf, "PDF", tint, onPdf)
        if (onCar != null) ExtraPill(Icons.Rounded.DirectionsCar, "Car", tint, onCar)
    }
}

@Composable
private fun ExtraPill(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}
