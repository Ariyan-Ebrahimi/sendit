package com.beamdrop.app.util

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * Writes a received file into Downloads/BeamDrop, using MediaStore on Android 10+
 * (no storage permission) and the legacy public directory on Android 8–9.
 */
class DownloadSink internal constructor(
    val output: OutputStream,
    /** content:// address of the saved file, used to open it afterwards. */
    val uri: Uri,
    private val onFinish: () -> Unit,
    private val onAbort: () -> Unit
) {
    private var completed = false

    fun finish() {
        if (completed) return
        completed = true
        runCatching { output.flush() }
        runCatching { output.close() }
        onFinish()
    }

    fun abort() {
        if (completed) return
        completed = true
        runCatching { output.close() }
        onAbort()
    }
}

object DownloadSaver {

    /** Photos/videos into the Gallery (Pictures/Sendit, Movies/Sendit) when [toGallery]; everything else into Downloads/Sendit. */
    fun create(context: Context, fileName: String, mimeType: String, toGallery: Boolean = false): DownloadSink {
        // "Trip/day 1/photo.jpg" -> folders ["Trip", "day 1"] + file "photo.jpg"
        val parts = fileName.split('/', '\\').map { sanitize(it) }.filter { it.isNotBlank() && it != "." && it != ".." }
        val safeName = parts.lastOrNull() ?: "sendit-file"
        val folders = parts.dropLast(1).take(8)
        val mime = mimeType.ifBlank { "application/octet-stream" }
        val kind = if (!toGallery) null else when {
            mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            else -> null
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (kind != null) runCatching { createMediaStoreSink(context, safeName, mime, folders, kind) }.getOrNull()
                ?.let { return it }
            createMediaStoreSink(context, safeName, mime, folders)
        } else {
            createLegacySink(context, safeName, mime, folders, kind ?: Environment.DIRECTORY_DOWNLOADS)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createMediaStoreSink(
        context: Context, fileName: String, mimeType: String, folders: List<String>,
        top: String = Environment.DIRECTORY_DOWNLOADS
    ): DownloadSink {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                (listOf(top, "Sendit") + folders).joinToString(File.separator)
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = when (top) {
            Environment.DIRECTORY_PICTURES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Environment.DIRECTORY_MOVIES -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val uri: Uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create a Downloads entry")
        val output = resolver.openOutputStream(uri)
            ?: run {
                resolver.delete(uri, null, null)
                throw IOException("Could not open Downloads entry")
            }
        return DownloadSink(
            output = output,
            uri = uri,
            onFinish = {
                runCatching {
                    val done = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                }
            },
            onAbort = {
                runCatching { resolver.delete(uri, null, null) }
            }
        )
    }

    @Suppress("DEPRECATION")
    private fun createLegacySink(context: Context, fileName: String, mimeType: String, folders: List<String>, top: String): DownloadSink {
        val downloads = Environment.getExternalStoragePublicDirectory(top)
        val directory = folders.fold(File(downloads, "Sendit")) { dir, name -> File(dir, name) }.apply { mkdirs() }
        val file = uniqueFile(directory, fileName)
        val output = FileOutputStream(file)
        return DownloadSink(
            output = output,
            uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file),
            onFinish = {
                runCatching {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(file.absolutePath),
                        arrayOf(mimeType),
                        null
                    )
                }
            },
            onAbort = {
                runCatching { file.delete() }
            }
        )
    }

    private fun uniqueFile(directory: File, name: String): File {
        val cleanName = name.ifBlank { "sendit-file" }
        val base = cleanName.substringBeforeLast('.', cleanName)
        val extension = cleanName.substringAfterLast('.', "")
        var candidate = File(directory, cleanName)
        var index = 1
        while (candidate.exists()) {
            val numbered = if (extension.isBlank()) {
                String.format(Locale.US, "%s (%d)", base, index)
            } else {
                String.format(Locale.US, "%s (%d).%s", base, index, extension)
            }
            candidate = File(directory, numbered)
            index++
        }
        return candidate
    }

    private fun sanitize(name: String): String {
        val cleaned = name
            .replace('/', '_')
            .replace('\\', '_')
            .map { if (it.code < 32) '_' else it }
            .joinToString("")
            .trim()
        return cleaned.ifBlank { "sendit-file" }.take(180)
    }
}
