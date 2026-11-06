package com.beamdrop.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.beamdrop.app.data.SelectedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * "Send smaller": makes photos and videos lighter before they are uploaded, to save mobile data.
 * A smaller copy is only used when it really is smaller; anything else is sent as it is.
 */
object MediaShrinker {

    private const val MAX_PHOTO_SIDE = 2048
    private const val JPEG_QUALITY = 82
    private const val VIDEO_SHORT_SIDE = 720
    private const val VIDEO_BITRATE = 2_000_000

    fun canShrink(file: SelectedFile): Boolean =
        (file.mimeType.startsWith("image/") && !file.mimeType.contains("gif") && file.size > 400_000) ||
            (file.mimeType.startsWith("video/") && file.size > 8_000_000)

    /** Returns a lighter copy of [file], or the original when that isn't possible or not worth it. */
    suspend fun shrink(context: Context, file: SelectedFile): SelectedFile {
        if (!canShrink(file)) return file
        val dir = File(context.cacheDir, "smaller").apply { mkdirs() }
        val folder = file.name.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val base = file.name.substringAfterLast('/').substringBeforeLast('.').ifBlank { "file" }
        val result = runCatching {
            if (file.mimeType.startsWith("image/")) {
                shrinkPhoto(context, file.uri, File(dir, "$base.jpg"))
            } else {
                shrinkVideo(context, file.uri, File(dir, "$base.mp4"))
            }
        }.getOrNull() ?: return file
        val size = result.length()
        if (size <= 0 || size > file.size * 0.9) {
            result.delete()
            return file
        }
        return file.copy(
            uri = Uri.fromFile(result),
            name = folder + result.name,
            size = size,
            mimeType = if (result.extension == "jpg") "image/jpeg" else "video/mp4"
        )
    }

    fun clear(context: Context) {
        File(context.cacheDir, "smaller").listFiles()?.forEach { it.delete() }
    }

    private suspend fun shrinkPhoto(context: Context, uri: Uri, out: File): File? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_PHOTO_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@withContext null

        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        val longest = maxOf(decoded.width, decoded.height)
        val scale = if (longest > MAX_PHOTO_SIDE) MAX_PHOTO_SIDE.toFloat() / longest else 1f
        val matrix = Matrix().apply {
            if (scale != 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        val finalBitmap = if (matrix.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        out.outputStream().use { finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (finalBitmap !== decoded) finalBitmap.recycle()
        decoded.recycle()
        out
    }

    @OptIn(UnstableApi::class)
    private suspend fun shrinkVideo(context: Context, uri: Uri, out: File): File? {
        // Work out the on-screen size so the SHORT side becomes 720 px (portrait videos stay sharp).
        val (width, height) = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) h to w else w to h
            } finally {
                runCatching { retriever.release() }
            }
        }
        if (width <= 0 || height <= 0) return null
        val shortSide = minOf(width, height)
        val targetHeight = if (shortSide <= VIDEO_SHORT_SIDE) height else (height.toLong() * VIDEO_SHORT_SIDE / shortSide).toInt()
        out.delete()

        val ok = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build()
                            )
                            .build()
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (cont.isActive) cont.resume(false)
                        }
                    })
                    .build()
                val item = EditedMediaItem.Builder(MediaItem.fromUri(uri))
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(targetHeight))))
                    .build()
                transformer.start(item, out.absolutePath)
                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post { runCatching { transformer.cancel() } }
                }
            }
        }
        return if (ok && out.exists()) out else null
    }
}
