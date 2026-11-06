package com.beamdrop.app.data

import android.net.Uri

enum class Direction { SEND, RECEIVE }

enum class TransferStatus {
    IDLE, WAITING, ACTIVE, PAUSED, COMPLETE, FAILED, CANCELLED
}

enum class FileStatus { QUEUED, ACTIVE, DONE, FAILED }

data class SelectedFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val lastModified: Long,
    val selected: Boolean = true
)

data class TransferFileProgress(
    val id: Int,
    val name: String,
    val size: Long,
    val mimeType: String,
    val bytesDone: Long = 0L,
    val status: FileStatus = FileStatus.QUEUED,
    /** Where a received file was saved (content:// URI), so it can be opened. */
    val savedUri: String? = null
)

data class TransferUi(
    val direction: Direction,
    val peerName: String,
    val status: TransferStatus,
    val files: List<TransferFileProgress>,
    val speedBps: Long = 0L,
    val peakBps: Long = 0L,
    val error: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val historyRecorded: Boolean = false,
    /** Receiving from the Sendit server instead of a nearby phone. */
    val viaCloud: Boolean = false,
    /** Connection dropped; waiting to continue the download. */
    val waiting: Boolean = false,
    /** View-once photos: kept in the app's cache, shown once, then deleted. */
    val viewOnce: Boolean = false,
    /** Secret files that delete themselves this many seconds after opening (0 = when closed). */
    val destructSeconds: Int = 0,
    val encrypted: Boolean = false
) {
    val totalBytes: Long = files.sumOf { it.size }
    val bytesDone: Long = files.sumOf { it.bytesDone }
    val progress: Float
        get() = if (totalBytes > 0L) (bytesDone.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f

    val completedFiles: Int
        get() = files.count { it.status == FileStatus.DONE }
}

data class PeerDevice(
    val serviceName: String,
    val name: String,
    val host: String,
    val port: Int,
    val token: String,
    val code: String
) {
    val url: String
        get() = "http://$host:$port/s/$token"
}

data class ConnectSession(
    val url: String,
    val token: String,
    val port: Int,
    val files: List<SelectedFile>,
    val localIp: String?
)

data class RemoteFile(
    val id: Int,
    val name: String,
    val size: Long,
    val mimeType: String
)

data class RemoteManifest(
    val sender: String,
    val code: String,
    val files: List<RemoteFile>,
    /** True when the files come from the Sendit server (works on any network). */
    val cloud: Boolean = false,
    val oneTime: Boolean = false,
    /** Note from the sender. */
    val message: String = "",
    val viewOnce: Boolean = false,
    val destructSeconds: Int = 0,
    /** End-to-end encrypted share: names/message are in [E2eInfo.meta], bytes need the key. */
    val e2e: E2eInfo? = null
) {
    val totalBytes: Long = files.sumOf { it.size }
}

data class ReceiveOffer(
    val baseUrl: String,
    val manifest: RemoteManifest,
    val password: String? = null,
    /** The share key of an end-to-end encrypted share. */
    val key: ByteArray? = null
)

data class E2eInfo(val meta: String, val epk: String?, val wrappedKey: String?)

data class ServerSnapshot(
    val isRunning: Boolean = false,
    val connected: Boolean = false,
    val remoteName: String? = null,
    val bytesSent: Long = 0L,
    val perFileBytes: List<Long> = emptyList(),
    val activeFileId: Int = -1,
    val paused: Boolean = false,
    val completed: Boolean = false,
    val downloadCount: Int = 0
)

data class HistoryEntry(
    val id: String,
    val fileName: String,
    val fileCount: Int,
    val totalBytes: Long,
    val direction: Direction,
    val peerName: String,
    val timestamp: Long,
    val success: Boolean,
    /** Received files that were saved on this phone, so they can be opened from History. */
    val files: List<SavedFile> = emptyList()
)

data class SavedFile(val name: String, val uri: String, val mimeType: String, val size: Long = 0L)

/** Upload-to-server ("Anywhere") share shown on the Connect screen. */
enum class CloudPhase { PREPARING, UPLOADING, READY, FAILED }

data class CloudShareUi(
    val phase: CloudPhase,
    val files: List<TransferFileProgress>,
    val code: String? = null,
    val url: String? = null,
    val expiresAt: String? = null,
    val downloads: Int = 0,
    val speedBps: Long = 0L,
    val error: String? = null,
    val recipientName: String? = null,
    val oneTime: Boolean = false,
    val hasPassword: Boolean = false,
    val ttlHours: Int = 24,
    /** Connection dropped; waiting to continue the upload. */
    val waiting: Boolean = false,
    /** Paused by the user. */
    val paused: Boolean = false,
    /** Send later: when receivers can see it. */
    val releaseAt: Long? = null,
    /** End-to-end encrypted: the code alone isn't enough for strangers, they need the link. */
    val encrypted: Boolean = false
) {
    val totalBytes: Long = files.sumOf { it.size }
    val bytesDone: Long = files.sumOf { it.bytesDone }
    val progress: Float
        get() = if (totalBytes > 0L) (bytesDone.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
}
