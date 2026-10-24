package app.ember.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.ember.ui.MainViewModel
import app.ember.ui.NowPlaying
import app.ember.ui.components.BookCover
import app.ember.ui.components.CoverBackdrop
import app.ember.ui.components.bookAccent
import app.ember.ui.theme.onColorFor
import app.ember.util.formatDuration
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val Ink = Color(0xFFFBF5F0)
private val InkSoft = Color(0xB3FBF5F0)
private val InkFaint = Color(0x73FBF5F0)
private val Glass = Color(0x1AFFFFFF)
private val GlassEdge = Color(0x24FFFFFF)

/**
 * Car mode: the player, simplified for a glance. The book's cover sets the mood (blurred,
 * dimmed), and only four things are touchable, all large: back, play/pause, forward and
 * bookmark. Nothing to scrub by accident. The screen stays on while it's open.
 */
@Composable
fun CarModeScreen(vm: MainViewModel, onExit: () -> Unit) {
    val np by vm.nowPlaying.collectAsState()
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        app.ember.playback.CarMode.screenShowing = true
        onDispose {
            view.keepScreenOn = false
            app.ember.playback.CarMode.screenShowing = false
        }
    }
    DarkSystemBars()
    val current = np
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0908))) {
        if (current != null) {
            CoverBackdrop(current.book, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.55f),
                            0.5f to Color.Black.copy(alpha = 0.72f),
                            1f to Color.Black.copy(alpha = 0.9f),
                        ),
                    ),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            TopRow(onExit)
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Start a book, then come back to car mode.", color = InkSoft, fontSize = 22.sp, textAlign = TextAlign.Center)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    if (maxWidth > maxHeight * 1.15f) {
                        // Landscape (a phone in a dash mount): cover on the left, controls on the right.
                        val areaHeight = maxHeight
                        val compact = areaHeight < 380.dp
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                            BoxWithConstraints(Modifier.fillMaxHeight().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                BookCover(
                                    current.book,
                                    Modifier.size(minOf(maxHeight, 420.dp)).shadow(28.dp, RoundedCornerShape(18.dp)),
                                    corner = 18.dp,
                                    showBadge = false,
                                )
                            }
                            Column(Modifier.weight(1f).widthIn(max = 640.dp), verticalArrangement = Arrangement.Center) {
                                Details(current, center = false, compact = compact)
                                if (compact) {
                                    // Not enough height for a bookmark bar: it joins the row as a fourth button.
                                    Spacer(Modifier.height(20.dp))
                                    Controls(vm, current, playSize = minOf(112.dp, areaHeight * 0.4f), sideSize = minOf(80.dp, areaHeight * 0.28f), withBookmark = true)
                                } else {
                                    Spacer(Modifier.height(28.dp))
                                    Controls(vm, current, playSize = 120.dp, sideSize = 84.dp)
                                    Spacer(Modifier.height(24.dp))
                                    BookmarkPill(vm)
                                }
                            }
                        }
                    } else {
                        // Portrait. On the wide inner screen everything stays in a comfortable column.
                        Column(Modifier.fillMaxHeight().widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                                // Square, with room to breathe on every side.
                                val side = minOf(maxWidth * 0.82f, maxHeight, 380.dp)
                                BookCover(
                                    current.book,
                                    Modifier.size(side).shadow(28.dp, RoundedCornerShape(18.dp)),
                                    corner = 18.dp,
                                    showBadge = false,
                                )
                            }
                            Details(current, center = true)
                            Spacer(Modifier.height(28.dp))
                            Controls(vm, current, playSize = 132.dp, sideSize = 88.dp)
                            Spacer(Modifier.height(24.dp))
                            BookmarkPill(vm)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopRow(onExit: () -> Unit) {
    val clock by produceState(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())) {
        while (true) {
            value = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
            delay(15_000)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(Glass)
                .border(1.dp, GlassEdge, CircleShape)
                .clickable(onClickLabel = "Exit car mode", onClick = onExit),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Close, "Exit car mode", tint = Ink, modifier = Modifier.size(26.dp)) }
        Text(
            "CAR MODE",
            color = InkFaint,
            fontSize = 13.sp,
            letterSpacing = 2.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(52.dp), contentAlignment = Alignment.CenterEnd) {
            Text(clock, color = InkSoft, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun ColumnScope.Details(np: NowPlaying, center: Boolean, compact: Boolean = false) {
    val align = if (center) TextAlign.Center else TextAlign.Start
    Text(
        np.book.displayTitle,
        color = Ink,
        fontSize = if (compact) 26.sp else 30.sp,
        lineHeight = if (compact) 30.sp else 34.sp,
        fontFamily = FontFamily.Serif,
        textAlign = align,
        maxLines = if (compact) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    val left = (np.remaining / np.speed.coerceAtLeast(0.1f)).toLong()
    Text(
        listOfNotNull(np.chapter?.title?.takeIf { np.chapters.size > 1 }, "${formatDuration(left)} left").joinToString("  ·  "),
        color = InkSoft,
        fontSize = 18.sp,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    // Where you are in the chapter: shown, not touchable (nothing to slip on).
    val accent = bookAccent(np.book)
    val frac = (np.chapterPosition.toFloat() / np.chapterLength.coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.16f))) {
        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(accent))
    }
}

@Composable
private fun Controls(vm: MainViewModel, np: NowPlaying, playSize: Dp, sideSize: Dp, withBookmark: Boolean = false) {
    val settings by vm.settings.collectAsState()
    val tick = rememberTick()
    val haptics = LocalHapticFeedback.current
    val accent = bookAccent(np.book)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        GlassCircle(sideSize, "Back ${settings.skipBackSec} seconds") {
            tick()
            vm.skipBack()
        }
        Box(
            Modifier
                .size(playSize)
                .shadow(32.dp, CircleShape, ambientColor = accent, spotColor = accent)
                .clip(CircleShape)
                .background(accent)
                .clickable(onClickLabel = if (np.isPlaying) "Pause" else "Play") {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.togglePlay()
                }
                .semantics { contentDescription = if (np.isPlaying) "Pause" else "Play" },
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(np.isPlaying, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "carPlay") { playing ->
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    null,
                    tint = onColorFor(accent),
                    modifier = Modifier.size(playSize * 0.5f),
                )
            }
        }
        GlassCircle(sideSize, "Forward ${settings.skipForwardSec} seconds") {
            tick()
            vm.skipForward()
        }
        if (withBookmark) BookmarkCircle(vm, sideSize)
    }
}

/** A glassy round skip button; the skip icon itself is drawn by [SkipIcon]. */
@Composable
private fun GlassCircle(size: Dp, label: String, onClick: () -> Unit) {
    val forward = label.startsWith("Forward")
    val seconds = label.filter { it.isDigit() }.toIntOrNull() ?: 30
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Glass)
            .border(1.dp, GlassEdge, CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SkipIcon(seconds, forward = forward, tint = Ink, size = size * 0.52f)
    }
}

@Composable
private fun BookmarkPill(vm: MainViewModel) {
    val haptics = LocalHapticFeedback.current
    var saved by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(saved) {
        if (saved != null) {
            delay(2_500)
            saved = null
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(Glass)
            .border(1.dp, GlassEdge, RoundedCornerShape(34.dp))
            .clickable(onClickLabel = "Add a bookmark") {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                saved = vm.quickBookmark()
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val s = saved
        Icon(if (s != null) Icons.Rounded.Check else Icons.Rounded.BookmarkAdd, null, tint = Ink, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Text(if (s != null) "Bookmarked at $s" else "Bookmark", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.Medium)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** The bookmark as a round button, for a short landscape screen. A tick shows it saved. */
@Composable
private fun BookmarkCircle(vm: MainViewModel, size: Dp) {
    val haptics = LocalHapticFeedback.current
    var saved by remember { mutableStateOf(false) }
    LaunchedEffect(saved) {
        if (saved) {
            delay(2_500)
            saved = false
        }
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (saved) Color.White.copy(alpha = 0.22f) else Glass)
            .border(1.dp, GlassEdge, CircleShape)
            .clickable(onClickLabel = "Add a bookmark") {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                saved = vm.quickBookmark() != null
            }
            .semantics { contentDescription = if (saved) "Bookmarked" else "Bookmark" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (saved) Icons.Rounded.Check else Icons.Rounded.BookmarkAdd, null, tint = Ink, modifier = Modifier.size(size * 0.42f))
    }
}

/**
 * Car mode is always dark, so the status and navigation bars go dark too (light icons, no
 * white strip). Applied again after a rotation, since the activity resets the bars then.
 * The previous look comes back when car mode closes.
 */
@Composable
private fun DarkSystemBars() {
    val view = LocalView.current
    val config = LocalConfiguration.current
    val window = remember(view) { view.context.findActivity()?.window }
    val bars = remember(window) { window?.let { WindowCompat.getInsetsController(it, view) } }
    val original = remember(bars) {
        Triple(
            bars?.isAppearanceLightStatusBars,
            bars?.isAppearanceLightNavigationBars,
            if (Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced else null,
        )
    }
    DisposableEffect(config.orientation, config.screenWidthDp, config.screenHeightDp, config.uiMode) {
        val apply = Runnable {
            bars?.isAppearanceLightStatusBars = false
            bars?.isAppearanceLightNavigationBars = false
            if (Build.VERSION.SDK_INT >= 29) window?.isNavigationBarContrastEnforced = false
        }
        apply.run()
        view.post(apply)
        onDispose { view.removeCallbacks(apply) }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (bars != null) {
                original.first?.let { bars.isAppearanceLightStatusBars = it }
                original.second?.let { bars.isAppearanceLightNavigationBars = it }
            }
            if (Build.VERSION.SDK_INT >= 29 && window != null) original.third?.let { window.isNavigationBarContrastEnforced = it }
        }
    }
}
