package app.ember.ui.screens

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.ember.data.CoverStore
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Draws shareable quote cards: the quote, the cover, the book, sized for Instagram. */
object QuoteCardRenderer {

    enum class Format(val label: String, val w: Int, val h: Int) {
        POST("Post 4:5", 1080, 1350),
        SQUARE("Square", 1080, 1080),
        STORY("Story", 1080, 1920),
    }

    enum class Style(val label: String) { COVER("Cover"), DARK("Dark"), LIGHT("Light") }

    fun render(
        src: MainViewModel.CardSource,
        quote: String,
        format: Format,
        style: Style,
    ): Bitmap {
        val w = format.w
        val h = format.h
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val accent = src.color?.let { CoverStore.tuneAccent(it) } ?: 0xFFD9774A.toInt()
        val cover = src.coverPath?.let { CoverStore.decodeFile(it, 900) }
        val light = style == Style.LIGHT
        val ink = if (light) 0xFF1E1A17.toInt() else 0xFFFFFFFF.toInt()
        val inkSoft = if (light) 0xB31E1A17.toInt() else 0xCCFFFFFF.toInt()
        val inkFaint = if (light) 0x801E1A17.toInt() else 0x99FFFFFF.toInt()

        // Background
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        when (style) {
            Style.COVER -> {
                c.drawColor(0xFF141216.toInt())
                if (cover != null) {
                    // A soft, blurred version of the cover: shrink it right down, then stretch it back.
                    val tiny = Bitmap.createScaledBitmap(cover, 24, 24, true)
                    val scale = maxOf(w, h) / 24f * 1.15f
                    val m = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate((w - 24 * scale) / 2f, (h - 24 * scale) / 2f)
                    }
                    c.drawBitmap(tiny, m, p)
                } else {
                    p.shader = RadialGradient(w * 0.3f, h * 0.2f, h * 0.9f, accent, 0xFF141216.toInt(), Shader.TileMode.CLAMP)
                    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                    p.shader = null
                }
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0x8C000000.toInt(), 0xE6000000.toInt(), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = null
            }
            Style.DARK -> {
                c.drawColor(0xFF141216.toInt())
                p.shader = RadialGradient(w * 0.15f, h * 0.05f, h * 0.75f, (accent and 0x00FFFFFF) or 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = null
            }
            Style.LIGHT -> {
                c.drawColor(0xFFF6F1EA.toInt())
                p.color = (accent and 0x00FFFFFF) or 0x1F000000
                c.drawRect(0f, 0f, w.toFloat(), 18f, p)
            }
        }

        val pad = 96f
        val textWidth = (w - pad * 2).toInt()
        val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val sansBold = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val sans = Typeface.create("sans-serif", Typeface.NORMAL)

        // Footer: the book, with its cover.
        val thumb = if (format == Format.STORY) 260f else 210f
        val footerMark = 44f
        val blockTop = h - pad - footerMark - thumb
        drawCover(c, cover, accent, RectF(pad, blockTop, pad + thumb, blockTop + thumb))
        val infoLeft = pad + thumb + 40f
        val infoWidth = (w - pad - infoLeft).toInt()
        val titleP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 46f; typeface = sansBold }
        val authorP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = inkSoft; textSize = 36f; typeface = sans }
        val detailP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = if (light) accent else lighten(accent); textSize = 30f; typeface = sansBold }
        val titleL = layout(src.title, titleP, infoWidth, 2)
        val authorL = layout(src.author, authorP, infoWidth, 1)
        val detailL = layout(src.detail, detailP, infoWidth, 1)
        val infoH = titleL.height + (if (src.author.isNotBlank()) 10 + authorL.height else 0) + (if (src.detail.isNotBlank()) 14 + detailL.height else 0)
        var y = blockTop + (thumb - infoH) / 2f
        y = drawLayout(c, titleL, infoLeft, y)
        if (src.author.isNotBlank()) y = drawLayout(c, authorL, infoLeft, y + 10)
        if (src.detail.isNotBlank()) drawLayout(c, detailL, infoLeft, y + 14)

        val markP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = inkFaint; textSize = 26f; typeface = sans; letterSpacing = 0.12f }
        val mark = "EMBER"
        c.drawText(mark, w - pad - markP.measureText(mark), h - pad + 8f, markP)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
        c.drawCircle(w - pad - markP.measureText(mark) - 22f, h - pad - 1f, 8f, dot)

        // The quote, as large as fits.
        val areaTop = pad + 40f
        val areaBottom = blockTop - 70f
        val text = quote.trim()
        if (text.isNotEmpty()) {
            val qmark = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = if (light) accent else lighten(accent); textSize = 240f; typeface = serif }
            c.drawText("“", pad - 10f, areaTop + 170f, qmark)
            val quoteTop = areaTop + 150f
            val avail = (areaBottom - quoteTop).toInt()
            val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; typeface = serif }
            var size = if (format == Format.STORY) 84f else 76f
            var ql: StaticLayout
            while (true) {
                qp.textSize = size
                ql = StaticLayout.Builder.obtain(text, 0, text.length, qp, textWidth)
                    .setLineSpacing(0f, 1.18f)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .build()
                if (ql.height <= avail || size <= 34f) break
                size -= 4f
            }
            if (ql.height > avail) {
                val lines = maxOf(1, (avail / (ql.height / ql.lineCount.toFloat())).toInt())
                ql = StaticLayout.Builder.obtain(text, 0, text.length, qp, textWidth)
                    .setLineSpacing(0f, 1.18f)
                    .setMaxLines(lines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .build()
            }
            val top = quoteTop + ((avail - ql.height) / 2f).coerceAtLeast(0f) * if (format == Format.POST) 0.6f else 1f
            drawLayout(c, ql, pad, top)
        } else if (cover != null) {
            // No words: let the cover take the stage.
            val side = minOf(w - pad * 2, areaBottom - areaTop - 40f)
            val left = (w - side) / 2f
            val top = areaTop + (areaBottom - areaTop - side) / 2f
            drawCover(c, cover, accent, RectF(left, top, left + side, top + side))
        }
        return bmp
    }

    private fun lighten(color: Int): Int {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        fun up(v: Int) = v + ((255 - v) * 0.35f).toInt()
        return (0xFF shl 24) or (up(r) shl 16) or (up(g) shl 8) or up(b)
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, maxOf(width, 1))
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 1.1f)
            .build()

    private fun drawLayout(c: Canvas, l: StaticLayout, x: Float, y: Float): Float {
        c.save()
        c.translate(x, y)
        l.draw(c)
        c.restore()
        return y + l.height
    }

    private fun drawCover(c: Canvas, cover: Bitmap?, accent: Int, r: RectF) {
        val radius = r.width() * 0.08f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x00000000
            setShadowLayer(r.width() * 0.08f, 0f, r.width() * 0.03f, 0x80000000.toInt())
        }
        c.drawRoundRect(r, radius, radius, shadow.apply { color = 0xFF000000.toInt() })
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (cover != null) {
            val shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = maxOf(r.width() / cover.width, r.height() / cover.height)
            shader.setLocalMatrix(
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(r.left + (r.width() - cover.width * scale) / 2f, r.top + (r.height() - cover.height * scale) / 2f)
                },
            )
            p.shader = shader
        } else {
            p.color = accent
        }
        c.drawRoundRect(r, radius, radius, p)
    }

    /** Writes the card where the share sheet can read it. */
    fun shareFile(context: Context, bmp: Bitmap): android.net.Uri {
        val dir = File(context.cacheDir, "cards").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "ember-quote-${System.currentTimeMillis()}.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }

    /** Saves the card to Pictures/Ember so it shows up in Gallery and Google Photos. */
    fun saveToPhotos(context: Context, bmp: Bitmap): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Ember quote ${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Ember")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no uri")
        resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (e: Exception) {
        false
    }
}

/** Make a quote card from a clip or bookmark: edit the words, pick a size and look, share. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuoteCardScreen(vm: MainViewModel, kind: String, id: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<MainViewModel.CardSource?>(null) }
    var missing by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf("") }
    var format by remember { mutableStateOf(QuoteCardRenderer.Format.POST) }
    var style by remember { mutableStateOf(QuoteCardRenderer.Style.COVER) }
    var image by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(kind, id) {
        val s = vm.cardSource(kind, id)
        if (s == null) missing = true else {
            source = s
            quote = s.quote
        }
    }
    LaunchedEffect(source, quote, format, style) {
        val s = source ?: return@LaunchedEffect
        delay(120)
        image = withContext(Dispatchers.Default) { QuoteCardRenderer.render(s, quote, format, style) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
            Text("Quote card", style = MaterialTheme.typography.headlineSmall, color = EmberColors.Text)
        }
        if (missing) {
            Text("That clip or bookmark no longer exists.", color = EmberColors.TextMuted, modifier = Modifier.padding(24.dp))
            return@Column
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 640.dp
            val previewMax = maxHeight * 0.55f
            val preview: @Composable (Modifier) -> Unit = { mod ->
                Box(mod, contentAlignment = Alignment.Center) {
                    val bmp = image
                    if (bmp == null) {
                        CircularProgressIndicator()
                    } else {
                        Image(
                            bmp.asImageBitmap(),
                            contentDescription = "Quote card preview",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .aspectRatio(format.w / format.h.toFloat())
                                .shadow(18.dp, RoundedCornerShape(12.dp))
                                .clip(RoundedCornerShape(12.dp)),
                        )
                    }
                }
            }
            val controls: @Composable (Modifier) -> Unit = { mod ->
                Column(mod.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = quote,
                        onValueChange = { quote = it },
                        label = { Text("Quote") },
                        placeholder = { Text("Type the line you want to share") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("Size", style = MaterialTheme.typography.labelLarge, color = EmberColors.TextMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuoteCardRenderer.Format.entries.forEach { f ->
                            Chip(f.label, f == format) { format = f }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Look", style = MaterialTheme.typography.labelLarge, color = EmberColors.TextMuted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuoteCardRenderer.Style.entries.forEach { s ->
                            Chip(s.label, s == style) { style = s }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                val bmp = image ?: return@Button
                                scope.launch {
                                    val uri = withContext(Dispatchers.IO) { QuoteCardRenderer.shareFile(context, bmp) }
                                    val send = Intent(Intent.ACTION_SEND)
                                        .setType("image/png")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(Intent.createChooser(send, "Share quote card"))
                                }
                            },
                            enabled = image != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Share, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                        OutlinedButton(
                            onClick = {
                                val bmp = image ?: return@OutlinedButton
                                scope.launch {
                                    val ok = withContext(Dispatchers.IO) { QuoteCardRenderer.saveToPhotos(context, bmp) }
                                    Toast.makeText(context, if (ok) "Saved to Pictures › Ember" else "Couldn't save the image", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = image != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Save")
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    preview(Modifier.weight(1f).fillMaxHeight())
                    controls(Modifier.width(360.dp).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    preview(Modifier.fillMaxWidth().heightIn(max = previewMax).weight(1f, fill = false).padding(vertical = 8.dp))
                    controls(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}
