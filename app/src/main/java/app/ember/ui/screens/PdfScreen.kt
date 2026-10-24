package app.ember.ui.screens

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ember.container
import app.ember.ui.MainViewModel
import kotlinx.coroutines.launch
import app.ember.ui.theme.EmberColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** An open PDF. Android's renderer handles one page at a time, so every use goes through [lock]. */
private class OpenPdf(val fd: ParcelFileDescriptor, val renderer: PdfRenderer) {
    val lock = Mutex()
    val pageCount = renderer.pageCount
    /** Width / height of each page, read once up front so the list doesn't jump while pages draw. */
    val ratios = FloatArray(pageCount)

    fun close() {
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }
}

/** A book's companion PDF, right in Ember: scroll the pages, pinch or double-tap to zoom. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun PdfScreen(vm: MainViewModel, bookId: String, onBack: () -> Unit) {
    val bookState by remember(bookId) { vm.observeBook(bookId) }.collectAsState(initial = null)
    val book = bookState
    val uri = book?.pdfUri?.takeIf { it.isNotBlank() }
    val context = LocalContext.current
    val io = remember { Dispatchers.IO.limitedParallelism(1) }

    val doc by produceState<Result<OpenPdf>?>(null, uri) {
        if (uri == null) return@produceState
        value = withContext(io) {
            runCatching {
                val fd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: error("no file")
                val r = PdfRenderer(fd)
                OpenPdf(fd, r).also { d ->
                    for (i in 0 until d.pageCount) {
                        r.openPage(i).use { p -> d.ratios[i] = if (p.height > 0) p.width.toFloat() / p.height else 0.77f }
                    }
                }
            }
        }
    }
    DisposableEffect(doc) {
        val d = doc?.getOrNull()
        // Close only after any page still drawing has finished.
        onDispose { if (d != null) context.container.appScope.launch(io) { d.lock.withLock { d.close() } } }
    }

    Column(Modifier.fillMaxSize().background(EmberColors.Background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Back", tint = EmberColors.Text) }
            Column(Modifier.weight(1f)) {
                Text("PDF", style = MaterialTheme.typography.labelMedium, color = EmberColors.TextMuted)
                Text(book?.title ?: "", style = MaterialTheme.typography.titleMedium, color = EmberColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (uri != null) {
                IconButton(onClick = {
                    if (!vm.openPdfElsewhere(context, uri)) Toast.makeText(context, "No app on this phone can open PDFs.", Toast.LENGTH_LONG).show()
                }) { Icon(Icons.Rounded.OpenInNew, "Open in another app", tint = EmberColors.Text) }
            }
        }

        val result = doc
        when {
            book != null && uri == null -> Message("This book doesn't have a PDF. Add one in Edit details.")
            result == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            result.isFailure -> Message("Ember couldn't open this PDF. It may have been moved or deleted. Try opening it in another app with the button at the top.")
            else -> Pages(result.getOrThrow(), io)
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = EmberColors.TextMuted, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Pages(pdf: OpenPdf, io: kotlinx.coroutines.CoroutineDispatcher) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val page by remember { derivedStateOf { listState.firstVisibleItemIndex + 1 } }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            // Two fingers: zoom and slide sideways. One finger keeps scrolling the pages.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                            val maxX = (scale - 1f) * size.width / 2f
                            offsetX = (offsetX + event.calculatePan().x).coerceIn(-maxX, maxX)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1.05f) {
                        scale = 1f
                        offsetX = 0f
                    } else {
                        scale = 2f
                    }
                })
            },
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                },
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(pdf.pageCount) { i -> PdfPage(pdf, i, widthPx, io) }
        }
        Text(
            "Page $page of ${pdf.pageCount}",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun PdfPage(pdf: OpenPdf, index: Int, widthPx: Int, io: kotlinx.coroutines.CoroutineDispatcher) {
    val ratio = pdf.ratios[index].takeIf { it > 0f } ?: 0.77f
    // Drawn at 1.5x the screen width so zooming in stays sharp.
    val bitmap by produceState<Bitmap?>(null, index, widthPx) {
        value = withContext(io) {
            pdf.lock.withLock {
                runCatching {
                    pdf.renderer.openPage(index).use { p ->
                        val w = (widthPx * 1.5f).toInt().coerceAtMost(2400)
                        val h = (w / ratio).toInt().coerceAtLeast(1)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                            b.eraseColor(android.graphics.Color.WHITE)
                            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }.getOrNull()
            }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b == null) {
            CircularProgressIndicator(Modifier.padding(24.dp), color = Color.Gray)
        } else {
            Image(b.asImageBitmap(), contentDescription = "Page ${index + 1}", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
        }
    }
}
