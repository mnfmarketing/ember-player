package app.ember.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.ember.data.CoverStore
import app.ember.ui.MainViewModel
import app.ember.ui.theme.EmberColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Draws the shareable recap card. */
object RecapCardRenderer {

    enum class Format(val label: String, val w: Int, val h: Int) {
        STORY("Story", 1080, 1920),
        POST("Post 4:5", 1080, 1350),
    }

    fun render(r: MainViewModel.Recap, covers: List<Bitmap>, format: Format, periodWord: String): Bitmap {
        val w = format.w
        val h = format.h
        val story = format == Format.STORY
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val accent = r.accent ?: 0xFFC2410C.toInt()
        val deep = mix(accent, 0xFF0B0807.toInt(), 0.72f)
        val bg = Paint().apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), deep, 0xFF0B0807.toInt(), Shader.TileMode.CLAMP) }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        val ink = 0xFFF8F1EB.toInt()
        val soft = 0xCCF3E6DC.toInt()
        val faint = 0x80F3E6DC.toInt()
        val pop = lighten(accent)
        val serif = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        val sansBold = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val sans = Typeface.create("sans-serif", Typeface.NORMAL)
        val pad = 84f
        val width = (w - pad * 2).toInt()
        var y = if (story) 150f else 100f

        // Heading
        val kicker = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = pop; textSize = 32f; typeface = sansBold; letterSpacing = 0.14f }
        c.drawText("MY ${r.label.uppercase()}", pad, y, kicker)
        y += 20f
        y = draw(c, text("in audiobooks", TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = if (story) 92f else 80f; typeface = serif }, width, 1), pad, y)

        // The big number
        y += if (story) 70f else 40f
        val hours = r.listenedMs / 3_600_000.0
        val big = if (hours >= 10) "%.0f".format(hours) else "%.1f".format(hours)
        val bigP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = if (story) 260f else 200f; typeface = sansBold }
        val bigTop = y
        c.drawText(big, pad - 8f, y + bigP.textSize * 0.82f, bigP)
        val unitP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; textSize = 44f; typeface = sans }
        val unitX = pad + bigP.measureText(big) + 24f
        c.drawText(if (hours == 1.0) "hour" else "hours", unitX, bigTop + bigP.textSize * 0.52f, unitP)
        c.drawText("listened", unitX, bigTop + bigP.textSize * 0.52f + 54f, unitP)
        y += bigP.textSize * 0.9f
        val days = "on ${r.daysListened} ${if (r.daysListened == 1) "day" else "days"} this $periodWord"
        y = draw(c, text(days, TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = faint; textSize = 34f; typeface = sans }, width, 1), pad, y + 6f)

        // Covers of what you finished
        if (covers.isNotEmpty()) {
            y += if (story) 70f else 44f
            val size = if (story) 230f else 190f
            // Side by side when they fit, overlapping a little when there are many.
            val step = minOf(size + 24f, (width - size) / maxOf(1, covers.size - 1).toFloat())
            covers.take(6).forEachIndexed { i, b -> drawCover(c, b, accent, RectF(pad + i * step, y, pad + i * step + size, y + size)) }
            y += size
        }

        // Stat tiles
        y += if (story) 70f else 46f
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = faint; textSize = 28f; typeface = sansBold; letterSpacing = 0.1f }
        val value = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = if (story) 54f else 46f; typeface = sansBold }
        val colW = (width - 40) / 2
        val tiles = listOfNotNull(
            "BOOKS FINISHED" to r.finished.size.toString(),
            "LONGEST STREAK" to "${r.longestStreak} ${if (r.longestStreak == 1) "day" else "days"}",
            r.topAuthor?.let { "TOP AUTHOR" to it },
            r.topNarrator?.let { "TOP NARRATOR" to it },
        )
        tiles.chunked(2).forEach { row ->
            var rowH = 0f
            row.forEachIndexed { i, (k, v) ->
                val x = pad + i * (colW + 40)
                c.drawText(k, x, y + 28f, label)
                val vl = text(v, value, colW, 2)
                draw(c, vl, x, y + 44f)
                rowH = maxOf(rowH, 44f + vl.height)
            }
            y += rowH + (if (story) 44f else 30f)
        }

        // Extras, while there's room
        val bodyP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = soft; textSize = 36f; typeface = sans }
        val footerTop = h - pad - 40f
        r.favorite?.let { f ->
            val l = text("Favorite: ${f.title}  " + "★".repeat(f.rating), bodyP, width, 2)
            if (y + l.height < footerTop - 40) y = draw(c, l, pad, y) + 24f
        }
        if (r.bestDayLabel != null && r.bestDayMs > 0) {
            val l = text("Biggest day: ${r.bestDayLabel} · ${app.ember.util.formatDuration(r.bestDayMs)}", bodyP, width, 1)
            if (y + l.height < footerTop - 40) y = draw(c, l, pad, y) + 24f
        }
        if (story && r.clipQuote != null) {
            val qp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 42f; typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC) }
            val room = (footerTop - 60 - y).toInt()
            if (room > 120) {
                val lines = maxOf(1, room / 56)
                val l = StaticLayout.Builder.obtain("“${r.clipQuote}”", 0, r.clipQuote.length + 2, qp, width)
                    .setMaxLines(minOf(lines, 5)).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.15f)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
                draw(c, l, pad, y + 16f)
            }
        }

        // Footer
        val markP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = faint; textSize = 28f; typeface = sans; letterSpacing = 0.12f }
        val mark = "LISTENED WITH EMBER"
        c.drawText(mark, w - pad - markP.measureText(mark), h - pad + 8f, markP)
        c.drawCircle(w - pad - markP.measureText(mark) - 22f, h - pad - 1f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pop })
        return bmp
    }

    private fun text(s: String, p: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(s, 0, s.length, p, maxOf(width, 1))
            .setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.05f).build()

    private fun draw(c: Canvas, l: StaticLayout, x: Float, y: Float): Float {
        c.save()
        c.translate(x, y)
        l.draw(c)
        c.restore()
        return y + l.height
    }

    private fun drawCover(c: Canvas, cover: Bitmap, accent: Int, r: RectF) {
        val radius = r.width() * 0.07f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            setShadowLayer(r.width() * 0.08f, 0f, r.width() * 0.03f, 0x99000000.toInt())
        }
        c.drawRoundRect(r, radius, radius, shadow)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = maxOf(r.width() / cover.width, r.height() / cover.height)
        shader.setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate(r.left + (r.width() - cover.width * scale) / 2f, r.top + (r.height() - cover.height * scale) / 2f)
            },
        )
        p.shader = shader
        c.drawRoundRect(r, radius, radius, p)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 0xff
        fun m(s: Int) = (ch(a, s) * (1 - t) + ch(b, s) * t).toInt()
        return (0xFF shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
    }

    private fun lighten(color: Int): Int = mix(color, 0xFFFFFFFF.toInt(), 0.35f)
}

/** Your month or year in audiobooks, as a card to share or save. */
@Composable
fun RecapScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var period by remember { mutableIntStateOf(0) }
    var format by remember { mutableIntStateOf(0) }
    val fmt = RecapCardRenderer.Format.entries[format]
    val image by produceState<Bitmap?>(null, period, format) {
        value = null
        val r = vm.recap(period)
        value = withContext(Dispatchers.Default) {
            val covers = r.finished.takeLast(6).mapNotNull { b -> b.coverPath?.let { CoverStore.decodeFile(it, 400) } }
            RecapCardRenderer.render(r, covers, fmt, if (period == 2) "year" else "month")
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
            Text("Your recap", style = MaterialTheme.typography.headlineSmall, color = EmberColors.Text)
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("This month", "Last month", "This year").forEachIndexed { i, l ->
                FilterChip(selected = period == i, onClick = { period = i }, label = { Text(l) })
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecapCardRenderer.Format.entries.forEachIndexed { i, f ->
                FilterChip(selected = format == i, onClick = { format = i }, label = { Text(f.label) })
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
            val b = image
            if (b == null) {
                CircularProgressIndicator()
            } else {
                Image(
                    b.asImageBitmap(),
                    contentDescription = "Your listening recap",
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .aspectRatio(fmt.w.toFloat() / fmt.h)
                        .shadow(12.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp)),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    val b = image ?: return@Button
                    val uri = QuoteCardRenderer.shareFile(context, b)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("image/png")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Share your recap"))
                },
                enabled = image != null,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Rounded.Share, null)
                Spacer(Modifier.width(8.dp))
                Text("Share")
            }
            OutlinedButton(
                onClick = {
                    val b = image ?: return@OutlinedButton
                    val ok = QuoteCardRenderer.saveToPhotos(context, b)
                    Toast.makeText(context, if (ok) "Saved to Pictures/Ember" else "Couldn't save the image", Toast.LENGTH_SHORT).show()
                },
                enabled = image != null,
                modifier = Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Save")
            }
        }
    }
}
