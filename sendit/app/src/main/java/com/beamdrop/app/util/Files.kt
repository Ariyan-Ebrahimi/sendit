package com.beamdrop.app.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.beamdrop.app.data.SelectedFile
import java.util.Locale

object Files {

    fun query(context: Context, uri: Uri): SelectedFile? {
        var name: String? = null
        var size = 0L
        var modified = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
            context.contentResolver.query(uri, arrayOf("last_modified"), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex("last_modified")
                    if (index >= 0 && cursor.moveToFirst()) modified = cursor.getLong(index)
                }
        }
        if (size <= 0L) {
            // Some document providers omit OpenableColumns.SIZE. Ask the descriptor
            // directly so the HTTP server can send an accurate Content-Length.
            runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    if (descriptor.length > 0L) size = descriptor.length
                }
            }
            if (size <= 0L) {
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                        if (descriptor.statSize > 0L) size = descriptor.statSize
                    }
                }
            }
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/') ?: "File"
        val displayName = name?.takeIf { it.isNotBlank() } ?: fallbackName
        if (size < 0) size = 0
        return SelectedFile(
            uri = uri,
            name = displayName,
            size = size,
            mimeType = mime,
            lastModified = modified
        )
    }

    /**
     * Lists every file under a folder picked with OpenDocumentTree. Names keep the folder path,
     * e.g. "Trip/day 1/photo.jpg", so the receiver gets the same folders.
     */
    fun listFolder(context: Context, treeUri: Uri, limit: Int = 2000): List<SelectedFile> {
        val resolver = context.contentResolver
        val result = mutableListOf<SelectedFile>()
        val rootId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = resolver.query(
            android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId),
            arrayOf(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "Folder"

        fun walk(docId: String, prefix: String) {
            if (result.size >= limit) return
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val cols = arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                android.provider.DocumentsContract.Document.COLUMN_SIZE,
                android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED
            )
            runCatching {
                resolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext() && result.size < limit) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2) ?: "application/octet-stream"
                        if (name.startsWith(".")) continue // skip hidden files
                        if (mime == android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                            walk(id, "$prefix$name/")
                        } else {
                            result += SelectedFile(
                                uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                name = "$prefix$name",
                                size = if (c.isNull(3)) 0L else c.getLong(3),
                                mimeType = mime,
                                lastModified = if (c.isNull(4)) 0L else c.getLong(4)
                            )
                        }
                    }
                }
            }
        }
        walk(rootId, "$rootName/")
        return result
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024
            index++
        }
        return if (index == 0 || value >= 100) {
            String.format(Locale.US, "%.0f %s", value, units[index])
        } else {
            String.format(Locale.US, "%.1f %s", value, units[index])
        }
    }

    fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "0 MB/s"
        val mb = bytesPerSecond / 1_048_576.0
        return if (mb >= 10) {
            String.format(Locale.US, "%.0f MB/s", mb)
        } else {
            String.format(Locale.US, "%.1f MB/s", mb)
        }
    }

    fun fileKind(name: String, mime: String): FileKind {
        val lower = name.lowercase(Locale.US)
        return when {
            mime.startsWith("image/") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".heic") -> FileKind.IMAGE
            mime.startsWith("video/") || lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov") ||
                lower.endsWith(".webm") || lower.endsWith(".3gp") || lower.endsWith(".avi") -> FileKind.VIDEO
            mime.startsWith("audio/") || lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") ||
                lower.endsWith(".ogg") || lower.endsWith(".flac") || lower.endsWith(".aac") -> FileKind.AUDIO
            mime == "application/pdf" || lower.endsWith(".pdf") -> FileKind.PDF
            lower.endsWith(".apk") || mime == "application/vnd.android.package-archive" -> FileKind.APK
            lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") ||
                lower.endsWith(".tar") || lower.endsWith(".gz") -> FileKind.ARCHIVE
            mime.startsWith("text/") || lower.endsWith(".doc") || lower.endsWith(".docx") ||
                lower.endsWith(".xls") || lower.endsWith(".xlsx") || lower.endsWith(".ppt") ||
                lower.endsWith(".pptx") -> FileKind.DOCUMENT
            else -> FileKind.GENERIC
        }
    }

    fun shortTypeLabel(name: String, mime: String): String {
        val extension = name.substringAfterLast('.', "").uppercase(Locale.US)
        return when {
            extension.isNotBlank() && extension.length <= 5 -> extension
            mime.isNotBlank() -> mime.substringAfter('/').take(8).uppercase(Locale.US)
            else -> "FILE"
        }
    }
}

enum class FileKind { IMAGE, VIDEO, AUDIO, PDF, APK, ARCHIVE, DOCUMENT, GENERIC }
