package app.ember.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Your profile photo: one small square JPEG in Ember's private storage (never uploaded anywhere).
 * Backups carry it, so a restore brings it back.
 */
object ProfilePhoto {

    const val SIZE = 512

    /** Test hook: a photo for the profile tab to open in its crop step. */
    val testCrop = kotlinx.coroutines.flow.MutableStateFlow<Uri?>(null)

    fun file(context: Context): File = File(File(context.filesDir, "profile").apply { mkdirs() }, "photo.jpg")

    fun exists(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }

    fun load(context: Context): Bitmap? = file(context).takeIf { it.exists() }?.let { CoverStore.decodeFile(it.absolutePath, SIZE) }

    /** Saves an already-cropped square photo. */
    fun save(context: Context, square: Bitmap): Boolean = try {
        val out = if (square.width == SIZE && square.height == SIZE) square else Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        FileOutputStream(file(context)).use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        true
    } catch (e: Exception) {
        false
    }

    fun saveBytes(context: Context, bytes: ByteArray): Boolean = try {
        file(context).writeBytes(bytes)
        true
    } catch (e: Exception) {
        false
    }

    fun delete(context: Context) {
        file(context).delete()
    }

    /** A picked photo, upright and at most [max] pixels on its longer side, ready to crop. */
    fun decodeForCrop(context: Context, uri: Uri, max: Int = 1600): Bitmap? = try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > max * 2 || bounds.outHeight / sample > max * 2) sample *= 2
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        if (raw == null) {
            null
        } else {
            val rotation = try {
                resolver.openInputStream(uri)?.use {
                    when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (e: Exception) {
                0f
            }
            val scale = minOf(1f, max.toFloat() / maxOf(raw.width, raw.height))
            if (rotation == 0f && scale == 1f) {
                raw
            } else {
                val m = Matrix().apply {
                    postScale(scale, scale)
                    postRotate(rotation)
                }
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            }
        }
    } catch (e: Exception) {
        null
    }
}
