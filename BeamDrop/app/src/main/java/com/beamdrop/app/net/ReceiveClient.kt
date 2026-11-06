package com.beamdrop.app.net

import android.content.Context
import com.beamdrop.app.data.ReceiveOffer
import com.beamdrop.app.data.RemoteFile
import com.beamdrop.app.data.RemoteManifest
import com.beamdrop.app.util.DownloadSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class TransferCancelledException : IOException("Transfer cancelled")

/** An answer from the sender that retrying will not fix (expired, not found, wrong password...). */
open class FatalTransferException(message: String) : IOException(message)

/** The share opens only near a place: send where we are. */
class LocationRequiredException(message: String, val radius: Int, val distance: Int) : FatalTransferException(message)

/** The share is protected: ask the user for its password. */
class PasswordRequiredException(message: String) : FatalTransferException(message)

private const val MAX_RETRIES = 30

/**
 * [authFor] gives the account token for links on our own Sendit server (so the sender sees who downloaded);
 * [toGallery] says whether photos/videos go to the Gallery.
 */
class ReceiveClient(
    private val authFor: (String) -> String? = { null },
    private val toGallery: () -> Boolean = { false },
    private val deviceId: String? = null
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var activeCall: okhttp3.Call? = null

    fun cancel() {
        runCatching { activeCall?.cancel() }
    }

    private fun Request.Builder.withPassword(password: String?): Request.Builder =
        if (password.isNullOrEmpty()) this else header("X-Share-Password", password)

    /** "lat,lng" for place-locked shares (set after the user allows location). */
    @Volatile
    var location: String? = null

    private fun Request.Builder.asApp(url: String): Request.Builder {
        header("X-Sendit-App", "1")
        deviceId?.let { header("X-Device-Id", it) }
        location?.let { header("X-Sendit-Location", it) }
        authFor(url)?.let { header("Authorization", "Bearer $it") }
        return this
    }

    private fun failure(response: okhttp3.Response, fallback404: String): FatalTransferException {
        val obj = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
        val serverMsg = obj?.optString("error")?.takeIf { it.isNotBlank() }
        if (response.code == 403 && obj?.optBoolean("needsLocation") == true) {
            return LocationRequiredException(serverMsg ?: "These files open only near a place.", obj.optInt("radius"), obj.optInt("distance", -1))
        }
        if (response.code == 401 && obj?.optBoolean("needsPassword") == true) {
            return PasswordRequiredException(serverMsg ?: "This share is protected by a password.")
        }
        return FatalTransferException(
            serverMsg ?: when (response.code) {
                404 -> fallback404
                429 -> "Too many tries. Please wait a minute."
                else -> "Sender returned HTTP ${response.code}."
            }
        )
    }

    suspend fun fetchManifest(baseUrl: String, receiverName: String, password: String? = null): RemoteManifest =
        withContext(Dispatchers.IO) {
            val normalized = normalizeBaseUrl(baseUrl)
                ?: throw FatalTransferException("That does not look like a Sendit link.")
            val name = URLEncoder.encode(receiverName, "UTF-8")
            val call = client.newCall(
                Request.Builder()
                    .url("$normalized/manifest.json?name=$name")
                    .header("Accept", "application/json")
                    .withPassword(password)
                    .asApp(normalized)
                    .get()
                    .build()
            )
            activeCall = call
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw failure(response, "This link expired. Ask the sender for a new code.")
                    val body = response.body?.string()
                        ?: throw IOException("The sender returned an empty file list.")
                    parseManifest(body)
                }
            } finally {
                activeCall = null
            }
        }

    /**
     * Downloads every file of [offer]. Each file first goes to a temporary file; if the connection
     * drops, it waits and continues from the last byte received (HTTP Range) instead of starting over.
     * Finished files are moved into Downloads/Sendit and reported through [onSaved].
     */
    suspend fun downloadFiles(
        context: Context,
        offer: ReceiveOffer,
        onFileStart: (RemoteFile, Int) -> Unit,
        onProgress: (fileId: Int, bytes: Long, total: Long) -> Unit,
        onSaved: (fileId: Int, uri: Uri) -> Unit,
        onWaiting: (Boolean) -> Unit,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean
    ): Int = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "incoming").apply { mkdirs() }
        val key = Integer.toHexString(offer.baseUrl.hashCode())
        var completed = 0
        for (file in offer.manifest.files.sortedBy { it.id }) {
            if (isCancelled()) throw TransferCancelledException()
            onFileStart(file, file.id)
            val temp = File(tempDir, "$key-${file.id}.part")
            onProgress(file.id, temp.length(), file.size)

            var failures = 0
            while (true) {
                if (isCancelled()) throw TransferCancelledException()
                val have = if (temp.exists()) temp.length() else 0L
                if (file.size > 0 && have >= file.size) break
                try {
                    downloadPart(offer, file, temp, have, onProgress, isPaused, isCancelled)
                    onWaiting(false)
                    if (file.size <= 0 || temp.length() >= file.size) break
                } catch (e: TransferCancelledException) {
                    throw e
                } catch (e: FatalTransferException) {
                    throw e
                } catch (e: IOException) {
                    if (isCancelled()) throw TransferCancelledException()
                    failures++
                    if (failures > MAX_RETRIES) throw e
                    // Connection dropped: wait a little longer each time, then continue where we stopped.
                    onWaiting(true)
                    val waitMs = minOf(2_000L * failures, 15_000L)
                    var waited = 0L
                    while (waited < waitMs) {
                        if (isCancelled()) throw TransferCancelledException()
                        delay(250)
                        waited += 250
                    }
                }
            }

            if (offer.manifest.viewOnce) {
                // View once: never leaves the app; deleted after it is looked at.
                val dir = viewOnceDir(context)
                val kept = File(dir, "${file.id}-" + file.name.substringAfterLast('/').replace(Regex("[^\\w.\\- ]"), "_"))
                val key = offer.key
                if (key != null) {
                    temp.inputStream().use { input -> kept.outputStream().use { com.beamdrop.app.util.E2e.decrypt(input, it, key, file.id) } }
                    temp.delete()
                } else if (!temp.renameTo(kept)) { temp.copyTo(kept, overwrite = true); temp.delete() }
                onProgress(file.id, file.size, file.size)
                onSaved(file.id, Uri.fromFile(kept))
                completed++
                continue
            }
            val sink = DownloadSaver.create(context, file.name, file.mimeType, toGallery())
            try {
                val key = offer.key
                if (key != null) temp.inputStream().use { com.beamdrop.app.util.E2e.decrypt(it, sink.output, key, file.id) }
                else temp.inputStream().use { it.copyTo(sink.output, 64 * 1024) }
                sink.finish()
            } catch (t: Throwable) {
                sink.abort()
                throw t
            }
            temp.delete()
            onProgress(file.id, file.size, file.size)
            onSaved(file.id, sink.uri)
            completed++
        }
        completed
    }

    private suspend fun downloadPart(
        offer: ReceiveOffer,
        file: RemoteFile,
        temp: File,
        have: Long,
        onProgress: (fileId: Int, bytes: Long, total: Long) -> Unit,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean
    ) {
        val builder = Request.Builder()
            .url("${offer.baseUrl.trimEnd('/')}/f/${file.id}")
            .header("Accept", "*/*")
            .withPassword(offer.password)
            .asApp(offer.baseUrl)
        if (have > 0) builder.header("Range", "bytes=$have-")
        val call = client.newCall(builder.get().build())
        activeCall = call
        try {
            call.execute().use { response ->
                if (response.code == 416) {
                    // We already have everything (or a stale piece): start this file again cleanly.
                    if (file.size > 0 && have >= file.size) return
                    temp.delete()
                    throw IOException("Restarting ${file.name}")
                }
                if (!response.isSuccessful) throw failure(response, "This file is no longer available.")
                val resumed = response.code == 206 && have > 0
                val body = response.body ?: throw IOException("No data received for ${file.name}.")
                FileOutputStream(temp, resumed).use { out ->
                    body.byteStream().use { input ->
                        var downloaded = if (resumed) have else 0L
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) throw TransferCancelledException()
                            while (isPaused()) {
                                if (isCancelled()) throw TransferCancelledException()
                                delay(100)
                            }
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            onProgress(file.id, downloaded, file.size)
                        }
                    }
                }
            }
        } finally {
            activeCall = null
        }
    }

    fun viewOnceDir(context: Context) = File(context.cacheDir, "viewonce").apply { mkdirs() }

    /** Removes half-downloaded leftovers (e.g. after a cancel). */
    fun clearPartials(context: Context) {
        File(context.cacheDir, "incoming").listFiles()?.forEach { it.delete() }
    }

    fun parseManifest(json: String): RemoteManifest {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            throw IOException("The sender sent an unreadable file list.")
        }
        val array = root.optJSONArray("files")
            ?: throw IOException("The sender sent an unreadable file list.")
        val files = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name", "File ${index + 1}").ifBlank { "File ${index + 1}" }
                add(
                    RemoteFile(
                        id = item.optInt("id", index),
                        name = name,
                        size = item.optLong("size", 0L).coerceAtLeast(0L),
                        mimeType = item.optString("mime", "application/octet-stream")
                    )
                )
            }
        }
        if (files.isEmpty()) throw IOException("The sender did not include any files.")
        return RemoteManifest(
            sender = root.optString("sender", "Nearby device").ifBlank { "Nearby device" },
            code = root.optString("code", ""),
            files = files,
            cloud = root.optBoolean("cloud", false),
            oneTime = root.optBoolean("oneTime", false),
            message = root.optString("message", ""),
            viewOnce = root.optBoolean("viewOnce", false),
            destructSeconds = root.optInt("destructSeconds", 0),
            e2e = root.optJSONObject("e2e")?.let { e ->
                com.beamdrop.app.data.E2eInfo(
                    meta = e.optString("meta"),
                    epk = e.optString("epk").takeIf { it.isNotBlank() && it != "null" },
                    wrappedKey = e.optString("wrappedKey").takeIf { it.isNotBlank() && it != "null" }
                )
            }
        )
    }

    fun normalizeBaseUrl(raw: String): String? {
        var value = raw.trim()
        if (value.isBlank()) return null
        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            value = "http://$value"
        }
        val parsed = value.toHttpUrlOrNull() ?: return null
        val cleaned = parsed.newBuilder()
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
        return cleaned.ifBlank { null }
    }
}
