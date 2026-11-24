package app.ember.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Cuts a clip's audio out of the book (or episode) into a small .m4a to share.
 * A clip can cross from one file into the next; each piece is cut and they're joined.
 */
object ClipAudio {

    /** A stretch of one file: [startMs, endMs) within that file. */
    data class Part(val uri: Uri, val startMs: Long, val endMs: Long)

    /** Splits a stretch of the whole book across its files (lengths in order). */
    fun parts(uris: List<String>, lengths: List<Long>, startMs: Long, endMs: Long, resolve: (String) -> Uri?): List<Part>? =
        spans(lengths, startMs, endMs)?.map { (i, from, to) -> Part(resolve(uris[i]) ?: return null, from, to) }

    /** Which files a stretch of the whole book covers: (file index, start, end) within each file. */
    fun spans(lengths: List<Long>, startMs: Long, endMs: Long): List<Triple<Int, Long, Long>>? {
        val out = mutableListOf<Triple<Int, Long, Long>>()
        var offset = 0L
        for (i in lengths.indices) {
            val len = lengths[i]
            val last = i == lengths.lastIndex
            // A file with no known length (or the last one) takes the rest.
            val fileEnd = if (len > 0 && !last) offset + len else Long.MAX_VALUE
            val from = maxOf(startMs, offset)
            val to = minOf(endMs, fileEnd)
            if (to > from) out += Triple(i, from - offset, to - offset)
            if (endMs <= fileEnd) break
            offset += len
        }
        return out.takeIf { it.isNotEmpty() }
    }

    suspend fun export(context: Context, parts: List<Part>, out: File): File = withContext(Dispatchers.Main) {
        out.parentFile?.mkdirs()
        out.delete()
        val items = parts.map { p ->
            val clipping = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(p.startMs)
                .setEndPositionMs(p.endMs)
                .build()
            EditedMediaItem.Builder(MediaItem.Builder().setUri(p.uri).setClippingConfiguration(clipping).build())
                .setRemoveVideo(true)
                .build()
        }
        val composition = Composition.Builder(EditedMediaItemSequence(items)).build()
        suspendCancellableCoroutine { cont ->
            val transformer = Transformer.Builder(context)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(out)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        out.delete()
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()
            cont.invokeOnCancellation { transformer.cancel() }
            transformer.start(composition, out.absolutePath)
        }
    }

    /** A file name from the book's title, safe for any app it's shared to. */
    fun fileName(title: String, startMs: Long): String {
        val base = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(Regex("\\s+"), " ").take(40).ifBlank { "Clip" }
        val s = startMs / 1000
        return "$base - clip at ${s / 3600}h${(s % 3600) / 60}m${s % 60}s.m4a"
    }
}
