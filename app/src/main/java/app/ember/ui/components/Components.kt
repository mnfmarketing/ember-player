package app.ember.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ember.data.BookEntity
import app.ember.ui.ExternalApp
import app.ember.ui.theme.EmberColors
import app.ember.ui.theme.deepTint
import app.ember.ui.theme.paleTint
import app.ember.ui.theme.readableAccent
import coil.compose.AsyncImage
import java.io.File
import kotlin.math.abs

private val placeholderTones = listOf(
    Color(0xFF7A2E18), Color(0xFF2E4A62), Color(0xFF4B2E5C), Color(0xFF2F5446),
    Color(0xFF6B4A1E), Color(0xFF5A2238), Color(0xFF34405E), Color(0xFF4F3A2A),
)

fun bookAccent(book: BookEntity?): Color =
    book?.coverColor?.let { readableAccent(Color(it)) } ?: EmberColors.DefaultAccent

@Composable
fun BookCover(
    book: BookEntity,
    modifier: Modifier = Modifier,
    corner: Dp = 10.dp,
    showBadge: Boolean = true,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(EmberColors.SurfaceHigh),
    ) {
        if (book.coverPath != null) {
            AsyncImage(
                model = File(book.coverPath),
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            GeneratedCover(book)
        }
        if (showBadge && book.isExternal) {
            val app = ExternalApp.from(book.externalApp)
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xCC0E0D10))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.OpenInNew, null, Modifier.size(10.dp), tint = EmberColors.Text)
                Spacer(Modifier.width(3.dp))
                Text(app.label, fontSize = 11.sp, color = EmberColors.Text, maxLines = 1)
            }
        }
        if (showBadge && book.status == BookEntity.STATUS_FINISHED) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = "Finished",
                tint = EmberColors.Success,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC0E0D10)),
            )
        }
    }
}

@Composable
private fun BoxScope.GeneratedCover(book: BookEntity) {
    val tone = placeholderTones[abs(book.title.hashCode()) % placeholderTones.size]
    Box(
        Modifier
            .matchParentSize()
            .background(Brush.linearGradient(listOf(tone, deepTint(tone, 0.1f))))
            .padding(12.dp),
    ) {
        Box(
            Modifier
                .size(18.dp)
                .border(1.5.dp, EmberColors.DefaultAccent.copy(alpha = 0.8f), CircleShape),
        )
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                book.displayTitle,
                fontFamily = FontFamily.Serif,
                color = Color(0xFFFBE3BC),
                fontSize = 15.sp,
                lineHeight = 18.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.author.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(book.author, color = Color(0xB3FBE3BC), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Blurred cover-art backdrop used by the immersive (glassy) screens. */
@Composable
fun CoverBackdrop(book: BookEntity, modifier: Modifier = Modifier, strength: Float = 0.9f) {
    val dark = EmberColors.isDark
    val raw = book.coverColor?.let { Color(it) } ?: EmberColors.DefaultAccent
    val tint = if (dark) deepTint(raw, 0.14f) else paleTint(raw, 0.90f)
    Box(modifier.clipToBounds().background(tint)) {
        if (book.coverPath != null) {
            AsyncImage(
                model = File(book.coverPath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.4f
                        scaleY = 1.4f
                        alpha = if (dark) 0.85f else 0.45f
                    }
                    .blur(90.dp),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to tint.copy(alpha = (if (dark) 0.25f else 0.55f) * strength),
                        0.55f to tint.copy(alpha = (if (dark) 0.55f else 0.78f) * strength),
                        1f to EmberColors.Background.copy(alpha = 0.92f),
                    ),
                ),
        )
    }
}

/** Frosted glass panel. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(EmberColors.GlassFill)
            .border(0.75.dp, EmberColors.GlassBorder, shape)
            .padding(padding),
    ) { content() }
}

@Composable
fun ThinProgress(progress: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, track: Color = EmberColors.Wash.copy(alpha = 0.12f), height: Dp = 3.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .drawBehind {
                val r = CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                drawRoundRect(color, size = size.copy(width = size.width * progress.coerceIn(0f, 1f)), cornerRadius = r)
            },
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = EmberColors.Text)
        if (action != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun RatingBar(rating: Int, onRate: ((Int) -> Unit)?, size: Dp = 26.dp, color: Color = MaterialTheme.colorScheme.primary) {
    Row {
        for (i in 1..5) {
            val filled = i <= rating
            Icon(
                if (filled) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = "$i stars",
                tint = if (filled) color else EmberColors.TextFaint,
                modifier = Modifier
                    .size(size)
                    .then(
                        if (onRate != null) {
                            Modifier.clickable { onRate(if (rating == i) 0 else i) }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}

/** Long-pressing a book cover anywhere calls this with the book's id (provided in EmberRoot). */
val LocalBookMenu = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Books picked for a bulk action. While any are picked, tapping a cover ticks it instead of opening it. */
class BookSelection(val ids: Set<String>, val toggle: (String) -> Unit)

val LocalBookSelection = androidx.compose.runtime.compositionLocalOf<BookSelection?> { null }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun BookTile(book: BookEntity, onClick: () -> Unit, modifier: Modifier = Modifier, coverSize: Dp? = null) {
    val menu = LocalBookMenu.current
    val selection = LocalBookSelection.current
    val selecting = selection != null && selection.ids.isNotEmpty()
    val picked = selection?.ids?.contains(book.id) == true
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val buzz = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
    Column(
        modifier
            .then(if (coverSize != null) Modifier.width(coverSize) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = { if (selecting) selection!!.toggle(book.id) else onClick() },
                onLongClickLabel = if (selecting) "Select" else "Book settings",
                onLongClick = when {
                    selecting -> {
                        {
                            buzz()
                            selection!!.toggle(book.id)
                        }
                    }
                    menu != null -> {
                        {
                            buzz()
                            menu(book.id)
                        }
                    }
                    else -> null
                },
            )
            .padding(4.dp),
    ) {
        Box {
            BookCover(
                book,
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val s = if (picked) 0.9f else 1f
                        scaleX = s
                        scaleY = s
                    },
            )
            if (selecting) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(if (picked) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f))
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (picked) Icon(androidx.compose.material.icons.Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (book.status == BookEntity.STATUS_IN_PROGRESS) {
            ThinProgress(book.progress, color = bookAccent(book))
            Spacer(Modifier.height(6.dp))
        }
        Text(
            book.displayTitle,
            style = MaterialTheme.typography.titleSmall,
            color = EmberColors.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.author.isNotBlank()) {
            Text(
                book.author,
                style = MaterialTheme.typography.bodySmall,
                color = EmberColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(28.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = EmberColors.Text, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = EmberColors.TextMuted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        actions()
    }
}
