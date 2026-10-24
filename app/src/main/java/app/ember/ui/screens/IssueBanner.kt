package app.ember.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import app.ember.playback.PlaybackIssue
import app.ember.ui.theme.EmberColors

/** A playback problem in plain words, with the button most likely to fix it. */
@Composable
fun IssueBanner(issue: PlaybackIssue, onAction: (PlaybackIssue.Action) -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .shadow(10.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(EmberColors.SurfaceHighest)
            .padding(start = 14.dp, top = 12.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(issue.title, style = MaterialTheme.typography.titleSmall, color = EmberColors.Text)
            Text(issue.detail, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted)
            val label = issue.action.label
            if (label != null) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onAction(issue.action) }) { Text(label) }
                }
            }
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss", tint = EmberColors.TextFaint) }
    }
}
