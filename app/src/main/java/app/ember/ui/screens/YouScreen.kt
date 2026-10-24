package app.ember.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.ember.data.ProfilePhoto
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import app.ember.ui.theme.onColorFor
import app.ember.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------------------------------------
// The profile circle: your photo, or the first letter of your name
// ---------------------------------------------------------------------------------------------

/**
 * Your photo in a circle. Without one, the first letter of your name on Ember's orange
 * (a plain person outline until you've set a name). [selected] adds a ring, for the tab bar.
 */
@Composable
fun ProfileAvatar(vm: MainViewModel, size: Dp, selected: Boolean = false, modifier: Modifier = Modifier) {
    val s by vm.settings.collectAsState()
    val context = LocalContext.current
    val photo by produceState<ImageBitmap?>(null, s.profilePhotoAt) {
        value = if (s.profilePhotoAt > 0) withContext(Dispatchers.IO) { ProfilePhoto.load(context)?.asImageBitmap() } else null
    }
    val accent = MaterialTheme.colorScheme.primary
    val ringed = if (selected) modifier.border(2.dp, accent, CircleShape).padding(3.dp) else modifier
    Box(ringed.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        val p = photo
        val initial = s.profileName.trim().firstOrNull()?.uppercaseChar()
        when {
            p != null -> Image(p, "Your profile photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            initial != null -> Box(Modifier.fillMaxSize().background(accent), contentAlignment = Alignment.Center) {
                Text(
                    initial.toString(),
                    color = onColorFor(accent),
                    fontSize = (size.value * 0.46f).sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Serif,
                )
            }
            else -> Box(Modifier.fillMaxSize().background(EmberColors.SurfaceHighest), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, "Your profile", tint = EmberColors.TextMuted, modifier = Modifier.size(size * 0.62f))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The tab
// ---------------------------------------------------------------------------------------------

/** Settings topics shown on the profile tab; each opens Settings at that section. */
private data class Topic(val label: String, val sub: String, val icon: ImageVector, val section: String)

private val topics = listOf(
    Topic("Playback", "Skip times, smart rewind, flip to pause", Icons.Rounded.PlayCircle, "Playback"),
    Topic("Sleep timer", "Bedtime, fade out, shake to restart", Icons.Rounded.Bedtime, "Sleep timer"),
    Topic("Sound and equalizer", "Presets and narration boost", Icons.Rounded.GraphicEq, "sound"),
    Topic("Library folders and Google Drive", "Where Ember finds your books", Icons.Rounded.Folder, "Library folders"),
    Topic("Car mode and buttons", "Your car, headphone presses", Icons.Rounded.DirectionsCar, "Car mode"),
    Topic("Podcasts", "Auto-download", Icons.Rounded.Podcasts, "Podcasts"),
    Topic("Backup and restore", "Your library, progress and notes", Icons.Rounded.Backup, "Backup and restore"),
)

@Composable
fun YouScreen(
    vm: MainViewModel,
    onOpenSettings: (section: String?) -> Unit,
    onOpenStats: () -> Unit,
    onOpenClips: () -> Unit,
    onOpenRecap: () -> Unit,
    onOpenUpNext: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenCar: () -> Unit,
) {
    val s by vm.settings.collectAsState()
    val stats by vm.stats.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    var photoMenu by remember { mutableStateOf(false) }
    var cropping by remember { mutableStateOf<Bitmap?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                cropping = withContext(Dispatchers.IO) { ProfilePhoto.decodeForCrop(context, uri) }
                if (cropping == null) app.ember.playback.PlaybackStatus.notice.value = "That photo couldn't be opened"
            }
        }
    }
    val pickPhoto = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.let { "Ember-$it" }
        } catch (e: Exception) {
            "Ember"
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        // Gear in the corner: all of Settings.
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { onOpenSettings(null) }) { Icon(Icons.Rounded.Settings, "Settings", tint = EmberColors.TextMuted) }
            }
        }

        // You
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box {
                    ProfileAvatar(
                        vm,
                        104.dp,
                        modifier = Modifier.clickable(onClickLabel = "Change your photo") {
                            if (s.profilePhotoAt > 0) photoMenu = true else pickPhoto()
                        },
                    )
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(EmberColors.SurfaceHighest)
                            .border(2.dp, EmberColors.Background, CircleShape)
                            .clickable(onClickLabel = "Change your photo") { if (s.profilePhotoAt > 0) photoMenu = true else pickPhoto() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.PhotoCamera, "Change your photo", tint = EmberColors.Text, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (s.profileName.isBlank()) {
                    TextButton(onClick = { naming = true }) { Text("Add your name", style = MaterialTheme.typography.titleMedium) }
                } else {
                    Text(
                        s.profileName,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Serif,
                        color = EmberColors.Text,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.clickable(onClickLabel = "Change your name") { naming = true }.padding(horizontal = 16.dp),
                    )
                }
                val finished = stats.finishedAllTime
                Text(
                    "$finished ${if (finished == 1) "book" else "books"} finished · ${formatDuration(stats.totalMs)} listened",
                    style = MaterialTheme.typography.bodySmall,
                    color = EmberColors.TextMuted,
                )
                Spacer(Modifier.height(16.dp))
            }
        }

        // Your listening, in short
        item { ListeningSummary(vm, onOpenStats) }

        // Your stuff
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Shortcut(Icons.Rounded.ContentCut, "Clips", Modifier.weight(1f), onOpenClips)
                Shortcut(Icons.Rounded.AutoAwesome, "Recap", Modifier.weight(1f), onOpenRecap)
                Shortcut(Icons.Rounded.QueueMusic, "Up Next", Modifier.weight(1f), onOpenUpNext)
                Shortcut(Icons.Rounded.DownloadForOffline, "Downloads", Modifier.weight(1f), onOpenDownloads)
                Shortcut(Icons.Rounded.DirectionsCar, "Car mode", Modifier.weight(1f), onOpenCar)
            }
        }

        // Settings by topic
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleMedium,
                color = EmberColors.Text,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        topics.forEach { t ->
            item(key = t.section) { TopicRow(t) { onOpenSettings(t.section) } }
        }
        item { TopicRow(Topic("Good to know", "Tips and gestures", Icons.Rounded.Lightbulb, "Good to know")) { onOpenSettings("Good to know") } }

        item {
            Text(
                version,
                style = MaterialTheme.typography.labelSmall,
                color = EmberColors.TextFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            )
        }
    }

    if (naming) {
        NameDialog(title = "Your name", placeholder = "Ben", initial = s.profileName, onDismiss = { naming = false }) {
            vm.setProfileName(it)
            naming = false
        }
    }
    if (photoMenu) {
        AlertDialog(
            onDismissRequest = { photoMenu = false },
            containerColor = EmberColors.SurfaceHigh,
            title = { Text("Profile photo") },
            text = { Text("It stays on this phone (and in your backups).") },
            confirmButton = {
                TextButton(onClick = {
                    photoMenu = false
                    pickPhoto()
                }) { Text("Change photo") }
            },
            dismissButton = {
                TextButton(onClick = {
                    photoMenu = false
                    vm.removeProfilePhoto()
                }) { Text("Remove photo") }
            },
        )
    }
    cropping?.let { bmp ->
        PhotoCropDialog(bmp, onDismiss = { cropping = null }) { square ->
            cropping = null
            vm.setProfilePhoto(square)
        }
    }
    // Test hook: a photo handed in by the emulator checks goes straight to the crop step.
    val testCrop by ProfilePhoto.testCrop.collectAsState()
    androidx.compose.runtime.LaunchedEffect(testCrop) {
        val uri = testCrop ?: return@LaunchedEffect
        ProfilePhoto.testCrop.value = null
        cropping = withContext(Dispatchers.IO) { ProfilePhoto.decodeForCrop(context, uri) }
    }
}

@Composable
private fun ListeningSummary(vm: MainViewModel, onOpenStats: () -> Unit) {
    val stats by vm.stats.collectAsState()
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(EmberColors.Surface)
            .clickable(onClickLabel = "See all stats", onClick = onOpenStats)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your listening", style = MaterialTheme.typography.titleMedium, color = EmberColors.Text, modifier = Modifier.weight(1f))
            Text("See all stats", style = MaterialTheme.typography.labelLarge, color = accent)
            Icon(Icons.Rounded.ChevronRight, null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // Today, against the daily goal
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    val goalMs = stats.dailyGoalMin * 60_000f
                    val frac = if (goalMs > 0) (stats.todayMs / goalMs).coerceIn(0f, 1f) else 0f
                    val track = EmberColors.SurfaceHighest
                    Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                        val stroke = 7.dp.toPx()
                        drawArc(track, -90f, 360f, false, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                        drawArc(accent, -90f, 360f * frac, false, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    }
                    Text("${stats.todayMs / 60_000}", style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
                }
                Text("of ${stats.dailyGoalMin} min today", style = MaterialTheme.typography.labelSmall, color = EmberColors.TextMuted)
            }
            // Streak
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.LocalFireDepartment, null, tint = if (stats.streak > 0) accent else EmberColors.TextFaint, modifier = Modifier.size(26.dp))
                        Text("${stats.streak}", style = MaterialTheme.typography.headlineSmall, color = EmberColors.Text)
                    }
                }
                Text("day streak", style = MaterialTheme.typography.labelSmall, color = EmberColors.TextMuted)
            }
            // This year's goal
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "${stats.finishedThisYear.size}/${stats.yearlyGoal}",
                        style = MaterialTheme.typography.headlineSmall,
                        color = EmberColors.Text,
                    )
                }
                Text("books in ${LocalDate.now().year}", style = MaterialTheme.typography.labelSmall, color = EmberColors.TextMuted)
            }
        }
        Spacer(Modifier.height(14.dp))
        // This week, as small bars
        val max = (stats.week.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(stats.dailyGoalMin * 60_000L).coerceAtLeast(1L)
        val track = EmberColors.SurfaceHighest
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val n = stats.week.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = slot * 0.42f
            stats.week.forEachIndexed { i, (_, ms) ->
                val left = i * slot + (slot - barW) / 2
                drawRoundRect(track, Offset(left, 0f), Size(barW, size.height), CornerRadius(barW / 2, barW / 2))
                val h = ms.toFloat() / max * size.height
                if (h > 0f) drawRoundRect(accent, Offset(left, size.height - h), Size(barW, h), CornerRadius(barW / 2, barW / 2))
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            stats.week.forEach { (date, _) ->
                Text(
                    date.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (date == LocalDate.now()) accent else EmberColors.TextMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Shortcut(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(EmberColors.Surface)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TopicRow(t: Topic, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(t.icon, null, tint = EmberColors.TextMuted, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(t.label, style = MaterialTheme.typography.bodyLarge, color = EmberColors.Text)
            Text(t.sub, style = MaterialTheme.typography.bodySmall, color = EmberColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = EmberColors.TextFaint)
    }
}

// ---------------------------------------------------------------------------------------------
// Cropping a photo to a circle
// ---------------------------------------------------------------------------------------------

/** Drag and pinch the photo inside the circle; Save keeps what's inside it. */
@Composable
fun PhotoCropDialog(source: Bitmap, onDismiss: () -> Unit, onSave: (Bitmap) -> Unit) {
    val image = remember(source) { source.asImageBitmap() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offX by remember { mutableFloatStateOf(0f) }
    var offY by remember { mutableFloatStateOf(0f) }
    var circlePx by remember { mutableFloatStateOf(1f) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
            Text(
                "Move and pinch to fit",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val density = LocalDensity.current
                val wPx = with(density) { maxWidth.toPx() }
                val hPx = with(density) { maxHeight.toPx() }
                val circle = min(wPx, hPx) * 0.82f
                circlePx = circle
                val base = circle / min(source.width, source.height)
                fun clamp() {
                    val sc = base * zoom
                    val maxX = max(0f, (source.width * sc - circle) / 2)
                    val maxY = max(0f, (source.height * sc - circle) / 2)
                    offX = offX.coerceIn(-maxX, maxX)
                    offY = offY.coerceIn(-maxY, maxY)
                }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInputTransform { pan, z ->
                            zoom = (zoom * z).coerceIn(1f, 6f)
                            offX += pan.x
                            offY += pan.y
                            clamp()
                        },
                ) {
                    val sc = base * zoom
                    val w = source.width * sc
                    val h = source.height * sc
                    val left = size.width / 2 + offX - w / 2
                    val top = size.height / 2 + offY - h / 2
                    drawImage(
                        image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(source.width, source.height),
                        dstOffset = IntOffset(left.toInt(), top.toInt()),
                        dstSize = IntSize(w.toInt(), h.toInt()),
                    )
                    val c = Offset(size.width / 2, size.height / 2)
                    val shade = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(Offset.Zero, size))
                        addOval(Rect(c, circle / 2))
                    }
                    drawPath(shade, Color.Black.copy(alpha = 0.62f))
                    drawCircle(Color.White.copy(alpha = 0.9f), radius = circle / 2, center = c, style = Stroke(2.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                TextButton(onClick = {
                    val base = circlePx / min(source.width, source.height)
                    val sc = base * zoom
                    val side = circlePx / sc
                    val cx = source.width / 2f - offX / sc
                    val cy = source.height / 2f - offY / sc
                    val l = (cx - side / 2).coerceIn(0f, source.width - side)
                    val t = (cy - side / 2).coerceIn(0f, source.height - side)
                    val out = Bitmap.createBitmap(ProfilePhoto.SIZE, ProfilePhoto.SIZE, Bitmap.Config.ARGB_8888)
                    android.graphics.Canvas(out).drawBitmap(
                        source,
                        android.graphics.Rect(l.toInt(), t.toInt(), (l + side).toInt(), (t + side).toInt()),
                        android.graphics.Rect(0, 0, ProfilePhoto.SIZE, ProfilePhoto.SIZE),
                        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
                    )
                    onSave(out)
                }) { Text("Save", color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

private fun Modifier.pointerInputTransform(onChange: (Offset, Float) -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, z, _ -> onChange(pan, z) }
        },
    )
