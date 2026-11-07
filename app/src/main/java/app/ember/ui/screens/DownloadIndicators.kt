package app.ember.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import app.ember.ui.theme.onColorFor

/**
 * The Downloads button for the top of Books and Podcasts. While anything downloads it shows a
 * progress ring around the arrow and how many are going.
 */
@Composable
fun DownloadsButton(vm: MainViewModel, onClick: () -> Unit) {
    val a by vm.downloadActivity.collectAsState()
    val accent = MaterialTheme.colorScheme.primary
    val label = if (a.count > 0) "Downloads, ${a.count} downloading" else "Downloads"
    // The count sits outside the button, so the button's round touch area doesn't cut it off.
    Box {
        IconButton(onClick = onClick) {
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                if (a.count > 0) {
                    if (a.progress >= 0f) {
                        CircularProgressIndicator(
                            progress = { a.progress },
                            modifier = Modifier.size(28.dp),
                            color = accent,
                            trackColor = EmberColors.SurfaceHighest,
                            strokeWidth = 2.5.dp,
                            strokeCap = StrokeCap.Round,
                        )
                    } else {
                        CircularProgressIndicator(Modifier.size(28.dp), color = accent, strokeWidth = 2.5.dp, trackColor = EmberColors.SurfaceHighest)
                    }
                }
                Icon(
                    Icons.Rounded.Download,
                    label,
                    tint = if (a.count > 0) accent else EmberColors.Text,
                    modifier = Modifier.size(if (a.count > 0) 16.dp else 24.dp),
                )
            }
        }
        if (a.count > 1) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 4.dp)
                    .size(17.dp)
                    .clip(CircleShape)
                    .background(EmberColors.Background)
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Text("${a.count.coerceAtMost(9)}", style = MaterialTheme.typography.labelSmall, color = onColorFor(accent))
            }
        }
    }
}

/** A slim bar on Home while anything downloads: "Downloading 2 · 45%". Tap for Downloads. */
@Composable
fun DownloadingBar(vm: MainViewModel, onClick: () -> Unit) {
    val a by vm.downloadActivity.collectAsState()
    if (a.count == 0) return
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(EmberColors.Surface)
            .clickable(onClickLabel = "Open downloads", onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Download, null, tint = accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                buildString {
                    append("Downloading ")
                    append(a.count)
                    if (a.progress >= 0f) append(" · ${(a.progress * 100).toInt()}%")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = EmberColors.Text,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ChevronRight, null, tint = EmberColors.TextFaint)
        }
        Spacer(Modifier.height(8.dp))
        if (a.progress >= 0f) {
            LinearProgressIndicator(
                progress = { a.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = accent,
                trackColor = EmberColors.SurfaceHighest,
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = accent, trackColor = EmberColors.SurfaceHighest)
        }
    }
}
