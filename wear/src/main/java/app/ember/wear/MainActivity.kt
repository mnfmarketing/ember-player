package app.ember.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import kotlinx.coroutines.delay

private val Ember = Color(0xFFE2683C)
private val Ink = Color(0xFFFBF5F0)
private val InkSoft = Color(0xB3FBF5F0)
private val Glass = Color(0x33FFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = PhoneLink(applicationContext).apply { demo = intent.getBooleanExtra("demo", false) }
        val demoScreen = intent.getStringExtra("screen") ?: "main"
        setContent { EmberWatch(link, demoScreen) }
    }
}

@Composable
fun EmberWatch(link: PhoneLink, startScreen: String = "main") {
    DisposableEffect(link) {
        link.start()
        onDispose { link.stop() }
    }
    var screen by remember { mutableStateOf(startScreen) }
    MaterialTheme(colors = Colors(primary = Ember, onPrimary = Color(0xFF1A0E09), background = Color.Black, onBackground = Ink)) {
        Scaffold(timeText = { TimeText() }) {
            if (screen == "sleep") {
                BackHandler { screen = "main" }
                SleepPicker(link) { screen = "main" }
            } else {
                NowPlayingScreen(link, onSleep = { screen = "sleep" })
            }
        }
    }
}

@Composable
private fun NowPlayingScreen(link: PhoneLink, onSleep: () -> Unit) {
    val now by link.now.collectAsState()
    val cover by link.cover.collectAsState()
    val phone by link.phone.collectAsState()
    val haptics = LocalHapticFeedback.current
    var bookmarked by remember { mutableStateOf(false) }
    LaunchedEffect(bookmarked) {
        if (bookmarked) {
            delay(2_000)
            bookmarked = false
        }
    }
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            clock = System.currentTimeMillis()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        cover?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.45f)
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.75f)))),
        )
        val n = now
        when {
            phone == false && n == null -> Message("Can't reach your phone", "Make sure it's nearby with Bluetooth on.")
            n == null -> Message("Ember", "Connecting to your phone…")
            else -> Column(
                Modifier.fillMaxSize().padding(horizontal = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(Modifier.height(18.dp))
                Text(
                    n.title.ifBlank { "Nothing playing" },
                    color = Ink,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 18.dp),
                )
                val sub = when {
                    !n.hasMedia -> if (n.title.isNotBlank()) "Tap play to resume" else "Start a book on your phone"
                    n.chapter.isNotBlank() -> n.chapter
                    else -> n.author
                }
                Text(sub, color = InkSoft, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp))
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SkipButton(n.backSec, forward = false, enabled = n.hasMedia) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        link.send("back")
                    }
                    Button(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            link.send("toggle")
                        },
                        modifier = Modifier.size(58.dp),
                        colors = ButtonDefaults.primaryButtonColors(),
                    ) {
                        Icon(
                            if (n.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (n.playing) "Pause" else "Play",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    SkipButton(n.forwardSec, forward = true, enabled = n.hasMedia) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        link.send("forward")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallRound(
                        if (bookmarked) Icons.Rounded.Check else Icons.Rounded.BookmarkAdd,
                        if (bookmarked) "Bookmarked" else "Bookmark",
                        enabled = n.hasMedia,
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        link.send("bookmark")
                        bookmarked = true
                    }
                    val sleepLabel = when {
                        n.sleepChapter -> "Ch."
                        n.sleepEndsAt > clock -> "${((n.sleepEndsAt - clock) / 60_000L) + 1}m"
                        else -> null
                    }
                    SmallRound(Icons.Rounded.Bedtime, "Sleep timer", enabled = n.hasMedia, highlighted = sleepLabel != null, label = sleepLabel, onClick = onSleep)
                }
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String) {
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Ink, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(body, color = InkSoft, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SkipButton(seconds: Int, forward: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp),
        colors = ButtonDefaults.buttonColors(backgroundColor = Glass, contentColor = Ink),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Replay,
                if (forward) "Forward $seconds seconds" else "Back $seconds seconds",
                modifier = Modifier.size(30.dp).graphicsLayer { if (forward) scaleX = -1f },
            )
            Text("$seconds", fontSize = 8.sp, color = Ink, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun SmallRound(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    highlighted: Boolean = false,
    label: String? = null,
    size: Dp = 40.dp,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size),
        colors = ButtonDefaults.buttonColors(backgroundColor = if (highlighted) Ember.copy(alpha = 0.45f) else Glass, contentColor = Ink),
    ) {
        if (label != null) {
            Text(label, fontSize = 11.sp, color = Ink)
        } else {
            Icon(icon, description, modifier = Modifier.size(20.dp))
        }
    }
}

/** Sleep timer choices, sent to the phone. */
@Composable
private fun SleepPicker(link: PhoneLink, onDone: () -> Unit) {
    val now by link.now.collectAsState()
    val active = (now?.sleepEndsAt ?: 0L) > System.currentTimeMillis() || now?.sleepChapter == true
    val state = rememberScalingLazyListState()
    val choices = buildList {
        add("15 minutes" to "15")
        add("30 minutes" to "30")
        add("45 minutes" to "45")
        add("1 hour" to "60")
        add("End of chapter" to "-1")
        if (active) add("Turn off timer" to "0")
    }
    ScalingLazyColumn(Modifier.fillMaxSize(), state = state, horizontalAlignment = Alignment.CenterHorizontally) {
        item { ListHeader { Text("Sleep timer", color = Ink) } }
        items(choices) { (label, arg) ->
            Chip(
                onClick = {
                    link.send("sleep", arg)
                    onDone()
                },
                label = { Text(label) },
                colors = if (arg == "0") ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(onClick = onDone, label = { Text("Back") }, colors = ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth())
        }
    }
}
