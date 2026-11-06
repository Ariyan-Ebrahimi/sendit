package com.beamdrop.app.net

import android.content.Context
import com.beamdrop.app.data.SelectedFile
import com.beamdrop.app.data.ServerSnapshot
import com.beamdrop.app.util.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference

/**
 * A tiny LAN-only HTTP server. It streams the selected content URIs straight from
 * [android.content.ContentResolver] without copying them into app storage first.
 *
 * Routes:
 *   /s/{token}                  browser download page
 *   /s/{token}/manifest.json    JSON manifest used by the BeamDrop receiver
 *   /s/{token}/f/{id}           the actual file bytes (supports Range)
 */
class LocalHttpServer(
    private val context: Context,
    private val files: List<SelectedFile>,
    initialSenderName: String,
    private val senderCode: String,
    initialToken: String
) {

    @Volatile
    private var senderName: String = initialSenderName

    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(0))
    }
    val port: Int get() = serverSocket.localPort

    @Volatile
    var token: String = initialToken
        private set

    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val sentBytes = AtomicLongArray(files.size)
    private val activeFileId = AtomicInteger(-1)
    private val everConnected = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)
    private val downloadCount = AtomicInteger(0)
    private val remoteName = AtomicReference<String?>(null)

    private val _state = MutableStateFlow(
        ServerSnapshot(perFileBytes = List(files.size) { 0L })
    )
    val state: StateFlow<ServerSnapshot> = _state.asStateFlow()

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "sendit-client").apply { isDaemon = true }
    }
    private val sockets: MutableSet<Socket> = Collections.synchronizedSet(mutableSetOf())
    private var acceptThread: Thread? = null
    private var statsThread: Thread? = null

    private val totalSize: Long = files.sumOf { it.size }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        publish()
        acceptThread = Thread({ acceptLoop() }, "sendit-accept").apply {
            isDaemon = true
            start()
        }
        statsThread = Thread({
            while (running.get()) {
                publish()
                try {
                    Thread.sleep(150)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "sendit-stats").apply {
            isDaemon = true
            start()
        }
    }

    fun rotateToken(newToken: String) {
        if (newToken.isNotBlank()) token = newToken
    }

    fun updateSenderName(name: String) {
        if (name.isNotBlank()) senderName = name
    }

    fun setPaused(value: Boolean) {
        paused.set(value)
        publish()
    }

    fun stop() {
        running.set(false)
        paused.set(true)
        runCatching { serverSocket.close() }
        synchronized(sockets) {
            sockets.toList().forEach { runCatching { it.close() } }
            sockets.clear()
        }
        executor.shutdownNow()
        acceptThread?.interrupt()
        statsThread?.interrupt()
        publish()
    }

    private fun acceptLoop() {
        while (running.get()) {
            val socket = try {
                serverSocket.accept()
            } catch (_: IOException) {
                break
            }
            sockets += socket
            try {
                executor.execute { handle(socket) }
            } catch (_: Exception) {
                runCatching { socket.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readAsciiLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }

            if (method != "GET" && method != "HEAD") {
                writeText(output, method, 405, "Method Not Allowed", "text/plain; charset=utf-8", "Method not allowed")
                return
            }

            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "")

            when {
                path == "/" || path.isBlank() -> writeText(
                    output, method, 200, "OK", "text/html; charset=utf-8", landingPage()
                )

                path == "/health" -> writeText(
                    output, method, 200, "OK", "text/plain; charset=utf-8", "ok"
                )

                !path.startsWith("/s/") -> writeText(
                    output, method, 404, "Not Found", "text/html; charset=utf-8",
                    messagePage("Nothing here", "Open the Sendit link shown on the sending phone.")
                )

                else -> route(path, query, method, headers, output)
            }
            output.flush()
        } catch (_: IOException) {
            // A receiver closed the socket, a transfer was cancelled, or the server stopped.
        } catch (_: Exception) {
            // Never let one malformed request take down the server.
        } finally {
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun route(
        path: String,
        query: String,
        method: String,
        headers: Map<String, String>,
        output: BufferedOutputStream
    ) {
        val rest = path.removePrefix("/s/")
        val pathToken = rest.substringBefore('/')
        if (pathToken != token) {
            writeText(
                output, method, 404, "Not Found", "text/html; charset=utf-8",
                messagePage("This link expired", "Ask the sender to regenerate the Sendit QR code.")
            )
            return
        }

        when {
            rest == pathToken || rest == "$pathToken/" -> {
                everConnected.set(true)
                writeText(
                    output, method, 200, "OK", "text/html; charset=utf-8",
                    downloadPage(pathToken)
                )
            }

            rest == "$pathToken/manifest.json" -> {
                everConnected.set(true)
                val name = queryParameter(query, "name")
                if (!name.isNullOrBlank()) {
                    remoteName.set(name.take(60))
                }
                writeText(
                    output, method, 200, "OK", "application/json; charset=utf-8",
                    manifestJson()
                )
            }

            rest.startsWith("$pathToken/f/") -> {
                val id = rest.removePrefix("$pathToken/f/").substringBefore('/').toIntOrNull()
                if (id == null || id !in files.indices) {
                    writeText(
                        output, method, 404, "Not Found", "text/plain; charset=utf-8",
                        "Unknown file"
                    )
                    return
                }
                serveFile(id, method, headers, output)
            }

            else -> writeText(
                output, method, 404, "Not Found", "text/plain; charset=utf-8", "Not found"
            )
        }
    }

    private fun serveFile(
        id: Int,
        method: String,
        headers: Map<String, String>,
        output: BufferedOutputStream
    ) {
        val file = files[id]
        val length = file.size
        val range = parseRange(headers["range"], length)
        if (headers.containsKey("range") && range == null) {
            val response = buildString {
                append("HTTP/1.1 416 Range Not Satisfiable\r\n")
                append("Content-Range: bytes */$length\r\n")
                append("Content-Length: 0\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }
            output.write(response.toByteArray(Charsets.UTF_8))
            output.flush()
            return
        }

        val start = range?.first ?: 0L
        val end = range?.second ?: (length - 1L).coerceAtLeast(0L)
        val contentLength = if (length == 0L) 0L else (end - start + 1L)
        val partial = range != null
        val status = if (partial) "206 Partial Content" else "200 OK"

        everConnected.set(true)
        downloadCount.incrementAndGet()
        activeFileId.set(id)
        publish()

        val mime = file.mimeType.ifBlank { "application/octet-stream" }
        val asciiName = asciiFileName(file.name)
        val encodedName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")

        val responseHeaders = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $mime\r\n")
            append("Content-Length: $contentLength\r\n")
            append("Content-Disposition: attachment; filename=\"$asciiName\"; filename*=UTF-8''$encodedName\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            if (partial) {
                append("Content-Range: bytes $start-$end/$length\r\n")
            }
            append("\r\n")
        }

        try {
            output.write(responseHeaders.toByteArray(Charsets.UTF_8))
            output.flush()
            if (method == "HEAD" || contentLength == 0L) return

            val input: InputStream = context.contentResolver.openInputStream(file.uri)
                ?: throw IOException("Cannot open ${file.name}")
            input.use { stream ->
                skipFully(stream, start)
                val buffer = ByteArray(64 * 1024)
                var remaining = contentLength
                while (remaining > 0 && running.get()) {
                    while (paused.get() && running.get()) {
                        Thread.sleep(80)
                    }
                    if (!running.get()) break
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val read = stream.read(buffer, 0, toRead)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                    sentBytes.addAndGet(id, read.toLong())
                    remaining -= read
                }
            }
        } catch (_: IOException) {
            // Receiver cancelled mid-file; nothing else to do.
        } finally {
            activeFileId.set(-1)
            publish()
        }
    }

    private fun manifestJson(): String {
        val root = JSONObject()
        root.put("app", "Sendit")
        root.put("version", 1)
        root.put("sender", senderName)
        root.put("code", senderCode)
        val array = JSONArray()
        files.forEachIndexed { index, file ->
            array.put(
                JSONObject().apply {
                    put("id", index)
                    put("name", file.name)
                    put("size", file.size)
                    put("mime", file.mimeType)
                }
            )
        }
        root.put("files", array)
        return root.toString()
    }

    private fun landingPage(): String = page(
        title = "Sendit",
        header = "Sendit",
        sub = "Instant wireless transfer",
        body = """
            <div class="empty">
              <div class="badge">LOCAL TRANSFER</div>
              <h2>Open the link from the sending phone</h2>
              <p>Scan the QR code or paste the BeamDrop link to see the files. Both devices need to be on the same Wi-Fi network.</p>
            </div>
        """.trimIndent()
    )

    private fun downloadPage(pathToken: String): String {
        val rows = files.mapIndexed { index, file ->
            val label = Files.shortTypeLabel(file.name, file.mimeType)
            """
            <a class="file" href="/s/$pathToken/f/$index">
              <div class="icon">$label</div>
              <div class="meta">
                <span class="name">${escapeHtml(file.name)}</span>
                <span class="sub">${Files.formatBytes(file.size)} · ${escapeHtml(file.mimeType)}</span>
              </div>
              <div class="get">Download</div>
            </a>
            """.trimIndent()
        }.joinToString("\n")

        return page(
            title = "Sendit · ${escapeHtml(senderName)}",
            header = escapeHtml(senderName),
            sub = "${files.size} ${if (files.size == 1) "file" else "files"} · ${Files.formatBytes(totalSize)} · Sendit local transfer",
            body = """
                <div class="files">$rows</div>
                <div class="note">Direct Wi-Fi transfer. No cloud, no account, no upload.</div>
            """.trimIndent()
        )
    }

    private fun page(title: String, header: String, sub: String, body: String): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>${escapeHtml(title)}</title>
          <style>
            :root { color-scheme: dark; }
            * { box-sizing: border-box; }
            body {
              margin: 0; min-height: 100vh; background: #0f131d; color: #dfe2f1;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
              display: flex; justify-content: center;
              background-image: radial-gradient(circle at 20% 0%, rgba(79,70,229,.28), transparent 45%),
                                radial-gradient(circle at 90% 30%, rgba(76,215,246,.14), transparent 40%);
            }
            main { width: min(680px, 100%); padding: 40px 20px 56px; }
            .brand { display: flex; align-items: center; gap: 12px; margin-bottom: 28px; }
            .mark { width: 42px; height: 42px; border-radius: 13px; display: grid; place-items: center;
              background: linear-gradient(135deg, #4f46e5, #7c3aed); font-size: 22px; font-weight: 800; color: white; }
            h1 { font-size: 24px; letter-spacing: -.02em; margin: 0; }
            .sub { color: #9ca3af; font-size: 13px; margin-top: 3px; }
            .badge { display: inline-block; padding: 5px 10px; border-radius: 999px; background: #262a35;
              color: #4cd7f6; font-size: 10px; font-weight: 700; letter-spacing: .12em; margin-bottom: 14px; }
            .empty { background: #171b26; border: 1px solid rgba(255,255,255,.07); border-radius: 24px; padding: 28px; }
            .empty h2 { margin: 0 0 8px; font-size: 21px; letter-spacing: -.02em; }
            .empty p { color: #c7c4d8; line-height: 1.55; margin: 0; }
            .files { display: flex; flex-direction: column; gap: 10px; }
            .file { display: flex; align-items: center; gap: 14px; padding: 14px; text-decoration: none;
              color: inherit; background: #171b26; border: 1px solid rgba(255,255,255,.06); border-radius: 18px; }
            .file:active { transform: scale(.99); }
            .icon { width: 46px; height: 46px; flex: 0 0 auto; border-radius: 12px; background: #262a35;
              color: #4cd7f6; display: grid; place-items: center; font-size: 10px; font-weight: 800; letter-spacing: .04em; }
            .meta { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 3px; }
            .name { font-weight: 600; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            .sub { color: #918fa1; font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            .get { flex: 0 0 auto; padding: 9px 14px; border-radius: 999px; background: #4f46e5; color: white;
              font-size: 12px; font-weight: 700; }
            .note { color: #918fa1; font-size: 12px; text-align: center; margin-top: 22px; line-height: 1.5; }
            @media (max-width: 420px) { .get { display: none; } main { padding-top: 28px; } }
          </style>
        </head>
        <body>
          <main>
            <div class="brand">
              <div class="mark">↑</div>
              <div>
                <h1>$header</h1>
                <div class="sub">$sub</div>
              </div>
            </div>
            $body
          </main>
        </body>
        </html>
    """.trimIndent()

    private fun messagePage(title: String, message: String): String = page(
        title = "Sendit",
        header = "Sendit",
        sub = "Local file transfer",
        body = """<div class="empty"><h2>${escapeHtml(title)}</h2><p>${escapeHtml(message)}</p></div>"""
    )

    private fun publish() {
        val perFile = List(files.size) { sentBytes.get(it) }
        val total = perFile.sum()
        val allDone = everConnected.get() && downloadCount.get() > 0 && files.isNotEmpty() &&
            perFile.indices.all { files[it].size == 0L || perFile[it] >= files[it].size }
        if (allDone) finished.set(true)
        _state.value = ServerSnapshot(
            isRunning = running.get(),
            connected = everConnected.get(),
            remoteName = remoteName.get(),
            bytesSent = total,
            perFileBytes = perFile,
            activeFileId = activeFileId.get(),
            paused = paused.get(),
            completed = finished.get(),
            downloadCount = downloadCount.get()
        )
    }

    private fun writeText(
        output: BufferedOutputStream,
        method: String,
        code: Int,
        reason: String,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        if (method != "HEAD") output.write(bytes)
        output.flush()
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val value = input.read()
            if (value == -1) return if (buffer.isEmpty()) null else buffer.toString()
            if (value == '\n'.code) break
            if (value != '\r'.code) buffer.append(value.toChar())
            if (buffer.length > 8192) return null
        }
        return buffer.toString()
    }

    private fun parseRange(header: String?, length: Long): Pair<Long, Long>? {
        if (header == null || length <= 0L) return null
        val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
        if (spec.isEmpty()) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startRaw = spec.substring(0, dash).trim()
        val endRaw = spec.substring(dash + 1).trim()
        return runCatching {
            if (startRaw.isEmpty()) {
                val suffix = endRaw.toLong()
                if (suffix <= 0) return null
                val start = (length - suffix).coerceAtLeast(0)
                start to (length - 1)
            } else {
                val start = startRaw.toLong()
                val end = if (endRaw.isEmpty()) length - 1 else endRaw.toLong()
                if (start !in 0 until length) return null
                start to end.coerceIn(start, length - 1)
            }
        }.getOrNull()
    }

    private fun skipFully(stream: InputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(8 * 1024)
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val read = stream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                remaining -= read
            }
        }
    }

    private fun asciiFileName(name: String): String {
        val cleaned = name.map { char ->
            if (char.code in 32..126 && char != '"' && char != '\\') char else '_'
        }.joinToString("").trim()
        return cleaned.ifBlank { "sendit-file" }.take(120)
    }

    private fun queryParameter(query: String, key: String): String? {
        if (query.isBlank()) return null
        return query.split('&').firstNotNullOfOrNull { pair ->
            val name = pair.substringBefore('=')
            if (name == key) {
                runCatching { URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") }.getOrNull()
            } else {
                null
            }
        }
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
