package com.beamdrop.app.api

import android.content.Context
import android.net.Uri
import com.beamdrop.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AuthResponse(
    val success: Boolean,
    val token: String? = null,
    val userName: String? = null,
    val userEmail: String? = null,
    val error: String? = null
)

data class CloudShareFile(val name: String, val size: Long, val mime: String)

data class CreatedShare(
    val code: String,
    val ownerKey: String,
    val url: String,
    val expiresAt: String,
    val recipientName: String? = null,
    val releaseAt: Long? = null,
    /** Encrypted shares: the receivers' phones to lock the key for. */
    val recipientKeys: List<RecipientKey> = emptyList()
)

data class RecipientKey(val userId: Int, val deviceId: String, val publicKey: String)

data class Plan(val id: Int, val name: String, val quotaMb: Int, val maxFileMb: Int, val maxTtlHours: Int, val price: String)

/** My plan now, the free limits and the plans I can buy. */
data class PlansInfo(val current: Plan, val until: Long?, val free: Plan, val plans: List<Plan>, val buyInfo: String)

data class ShareStatus(val downloads: Int, val status: String)

/** My live shares plus how much of my storage they use. */
data class MyShares(val shares: List<ShareSummary>, val usedBytes: Long, val quotaBytes: Long)

/** Choices the sender makes before uploading. */
data class ShareOptions(
    val ttlHours: Int = 24,
    val oneTime: Boolean = false,
    val password: String = "",
    val toEmail: String = "",
    /** Optional note shown to the receiver. */
    val message: String = "",
    /** Send to one of my groups instead of one person. */
    val groupId: Int? = null,
    val groupName: String? = null,
    /** Send later: epoch millis when receivers may see the files (null = now). */
    val sendAt: Long? = null,
    /** Photos the receiver can look at once, inside the app only. */
    val viewOnce: Boolean = false,
    /** Send to my own other phones/tablets (same account). */
    val toSelf: Boolean = false,
    /** End-to-end encrypted: the server can't read the files. */
    val encrypt: Boolean = false,
    /** Secret files delete themselves this many seconds after they're opened (0 = off). */
    val destructSeconds: Int = 0,
    /** Only opens near this place. */
    val geoLat: Double? = null,
    val geoLng: Double? = null,
    val geoRadius: Int = 500
)

data class ContactGroup(val id: Int, val name: String, val members: List<Contact>)

/** Something that happened to me: "downloaded", "expiring", "support". */
data class Notice(val id: Long, val kind: String, val code: String?, val name: String?, val count: Int, val body: String?)

data class SupportMessage(val id: Int, val fromAdmin: Boolean, val body: String, val createdAt: Long)

data class Contact(val id: Int, val name: String, val email: String, val autoAccept: Boolean)

/** A link others use to upload files to me. */
data class FileRequest(val code: String, val title: String, val url: String, val expiresAt: Long, val uploads: Int)

data class Broadcast(val id: Int, val title: String, val body: String)

/** Server switches for the app: announcement and updates (versionCode numbers). */
data class AppConfig(val announcement: String, val minVersion: Int, val latestVersion: Int, val updateUrl: String, val updateNote: String)

/** A newer app exists. [required] = this version may not be used any more. */
data class UpdateInfo(val required: Boolean, val url: String, val note: String)

/** One share as listed in "My shares" or the inbox. */
data class ShareSummary(
    val code: String,
    val url: String,
    val sender: String,
    val downloads: Int,
    val expiresAt: Long,
    val oneTime: Boolean,
    val hasPassword: Boolean,
    val recipientName: String?,
    val fileCount: Int,
    val totalSize: Long,
    val firstName: String,
    val senderEmail: String? = null,
    val message: String = "",
    val releaseAt: Long? = null,
    val viewOnce: Boolean = false,
    val e2e: Boolean = false,
    val destructSeconds: Int = 0,
    /** Metres; 0 = opens anywhere. */
    val geoRadius: Int = 0,
    /** Names of signed-in people who downloaded it. */
    val downloaders: List<String> = emptyList()
)

class ApiException(message: String, val httpCode: Int = 0) : IOException(message)

class BeamDropApiClient(
    private val context: Context,
    private val settingsStore: SettingsStore
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    // Uploads can take a long time on slow mobile data; only fail if nothing moves for 2 minutes.
    private val uploadClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeUpload: okhttp3.Call? = null

    val baseUrl: String
        get() = settingsStore.serverUrl.removeSuffix("/")

    fun friendlyError(e: Throwable): String = when (e) {
        is ApiException -> e.message ?: "Server error"
        is java.net.SocketTimeoutException -> "The Sendit server took too long to answer. Please try again."
        is java.net.UnknownHostException,
        is java.net.ConnectException -> "Can't reach the Sendit server. Check your internet connection."
        is org.json.JSONException -> "The server sent an unexpected reply. Is the server address correct?"
        else -> e.localizedMessage ?: "Network connection error"
    }

    private fun errorFrom(code: Int, body: String, fallback: String): ApiException {
        val msg = runCatching { JSONObject(body).getString("error") }.getOrNull()
        return ApiException(
            msg ?: when (code) {
                401 -> "Your session expired. Please sign in again."
                413 -> "That file is too large for the server."
                else -> "$fallback ($code)"
            },
            code
        )
    }

    private fun authBuilder(url: String): Request.Builder {
        val b = Request.Builder().url(url)
        settingsStore.authToken?.let { b.addHeader("Authorization", "Bearer $it") }
        b.addHeader("X-Device-Id", settingsStore.installId)
        b.addHeader("X-Sendit-App", "1")
        b.addHeader("X-App-Version", com.beamdrop.app.BuildConfig.VERSION_CODE.toString())
        return b
    }

    // ---------------- Accounts ----------------

    suspend fun signup(name: String, email: String, pass: String): AuthResponse =
        auth("$baseUrl/api/auth/signup", JSONObject().put("name", name).put("email", email).put("password", pass), "Sign up failed")

    suspend fun login(email: String, pass: String): AuthResponse =
        auth("$baseUrl/api/auth/login", JSONObject().put("email", email).put("password", pass), "Sign in failed")

    private suspend fun auth(url: String, json: JSONObject, fallback: String): AuthResponse = withContext(Dispatchers.IO) {
        try {
            val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
            client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw errorFrom(response.code, respBody, fallback)
                val obj = JSONObject(respBody)
                val user = obj.getJSONObject("user")
                AuthResponse(
                    success = true,
                    token = obj.getString("token"),
                    userName = user.getString("name"),
                    userEmail = user.getString("email")
                )
            }
        } catch (e: Exception) {
            AuthResponse(success = false, error = friendlyError(e))
        }
    }

    // ---------------- "Anywhere" shares ----------------

    /** Link a receiver opens for a code, e.g. https://your-server/s/AB12CD */
    fun shareUrlForCode(code: String): String =
        "$baseUrl/s/${code.trim().uppercase().filter { it.isLetterOrDigit() }}"

    suspend fun createShare(senderName: String, files: List<CloudShareFile>, options: ShareOptions, e2eMeta: String? = null): CreatedShare = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        files.forEach { arr.put(JSONObject().put("name", it.name).put("size", it.size).put("mime", it.mime)) }
        val json = JSONObject()
            .put("senderName", senderName)
            .put("files", arr)
            .put("ttlHours", options.ttlHours)
            .put("oneTime", options.oneTime)
        if (options.password.isNotEmpty()) json.put("password", options.password)
        if (options.toEmail.isNotBlank()) json.put("toEmail", options.toEmail.trim())
        if (options.message.isNotBlank()) json.put("message", options.message.trim())
        options.groupId?.let { json.put("groupId", it) }
        options.sendAt?.let { json.put("sendAt", it) }
        if (options.viewOnce) json.put("viewOnce", true)
        if (options.toSelf) json.put("toSelf", true).remove("toEmail")
        if (options.destructSeconds > 0) json.put("destructSeconds", options.destructSeconds)
        if (options.geoLat != null && options.geoLng != null) {
            json.put("geo", JSONObject().put("lat", options.geoLat).put("lng", options.geoLng).put("radius", options.geoRadius))
        }
        if (e2eMeta != null) json.put("e2e", true).put("e2eMeta", e2eMeta).remove("message")
        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        client.newCall(authBuilder("$baseUrl/api/shares").post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, text, "Could not start the upload")
            val o = JSONObject(text)
            CreatedShare(
                code = o.getString("code"),
                ownerKey = o.getString("ownerKey"),
                url = o.getString("url"),
                expiresAt = o.optString("expiresAt"),
                recipientName = o.optString("recipientName").takeIf { it.isNotBlank() && it != "null" },
                releaseAt = parseTime(o.optString("releaseAt")),
                recipientKeys = o.optJSONArray("recipientKeys")?.let { a ->
                    (0 until a.length()).map { i -> a.getJSONObject(i).let { RecipientKey(it.optInt("userId"), it.getString("deviceId"), it.getString("publicKey")) } }
                } ?: emptyList()
            )
        }
    }

    /** Encrypted share: the key, locked for each receiver phone. */
    suspend fun postShareKeys(share: CreatedShare, epk: String, keys: List<Pair<RecipientKey, String>>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        keys.forEach { (k, wrapped) -> arr.put(JSONObject().put("deviceId", k.deviceId).put("userId", k.userId).put("wrapped", wrapped)) }
        val body = JSONObject().put("epk", epk).put("keys", arr).toString().toRequestBody("application/json".toMediaTypeOrNull())
        client.newCall(Request.Builder().url("$baseUrl/api/shares/${share.code}/keys").header("X-Owner-Key", share.ownerKey).post(body).build())
            .execute().use { r -> if (!r.isSuccessful) throw errorFrom(r.code, r.body?.string().orEmpty(), "Could not lock the files") }
        Unit
    }

    /** This phone's public key, so others can send it encrypted files. */
    suspend fun registerKey(publicKey: String, name: String) = withContext(Dispatchers.IO) {
        send("POST", "/api/keys", JSONObject().put("publicKey", publicKey).put("name", name), "Could not save the key")
        Unit
    }

    private fun JSONObject.toPlan() = Plan(optInt("id"), optString("name"), optInt("quotaMb"), optInt("maxFileMb"), optInt("maxTtlHours"), optString("price"))

    suspend fun plans(): PlansInfo = withContext(Dispatchers.IO) {
        val o = getJson("/api/plans", "Could not load plans")
        val cur = o.getJSONObject("current")
        PlansInfo(
            current = cur.toPlan(),
            until = parseTime(cur.optString("until")),
            free = o.getJSONObject("free").toPlan().copy(name = "Free"),
            plans = o.optJSONArray("plans")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).toPlan() } } ?: emptyList(),
            buyInfo = o.optString("buyInfo")
        )
    }

    suspend fun redeem(code: String): String = withContext(Dispatchers.IO) {
        send("POST", "/api/plans/redeem", JSONObject().put("code", code), "Could not use the code").optString("plan")
    }

    private fun parseTime(raw: String?): Long? =
        raw?.takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun serverOffset(share: CreatedShare, index: Int): Long {
        val request = Request.Builder()
            .url("$baseUrl/api/shares/${share.code}/files/$index/offset")
            .header("X-Owner-Key", share.ownerKey)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, text, "Upload failed")
            return JSONObject(text).optLong("offset", 0L)
        }
    }

    /**
     * Uploads one file. If the connection drops, it asks the server how much already arrived and
     * continues from there, waiting a little longer after each failure ([onWaiting] = reconnecting).
     */
    suspend fun uploadShareFile(
        share: CreatedShare,
        index: Int,
        uri: Uri,
        size: Long,
        onProgress: (sent: Long) -> Unit,
        onWaiting: (Boolean) -> Unit = {},
        isPaused: () -> Boolean = { false },
        /** Encrypted uploads: gives the bytes to send from this position on. */
        openAt: ((offset: Long) -> java.io.InputStream)? = null
    ) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var failures = 0
        var resumeFromServer = false
        while (true) {
            coroutineContext.ensureActive()
            if (isPaused()) {
                while (isPaused()) { coroutineContext.ensureActive(); delay(300) }
                resumeFromServer = true
            }
            try {
                val offset = if (failures == 0 && !resumeFromServer) 0L else serverOffset(share, index)
                onWaiting(false)
                if (offset >= size && size > 0) {
                    onProgress(size)
                    return@withContext
                }
                onProgress(offset)
                val body = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength() = size - offset
                    override fun writeTo(sink: BufferedSink) {
                        val input = openAt?.invoke(offset) ?: resolver.openInputStream(uri) ?: throw IOException("Could not open the file.")
                        input.use { stream ->
                            var skipped = if (openAt != null) offset else 0L
                            while (skipped < offset) {
                                val n = stream.skip(offset - skipped)
                                if (n <= 0) throw IOException("Could not read the file.")
                                skipped += n
                            }
                            stream.source().use { source ->
                                val buffer = okio.Buffer()
                                var total = offset
                                while (true) {
                                    if (isPaused()) throw IOException("Paused")
                                    val read = source.read(buffer, 64 * 1024)
                                    if (read == -1L) break
                                    sink.write(buffer, read)
                                    total += read
                                    onProgress(total)
                                }
                            }
                        }
                    }
                }
                val request = Request.Builder()
                    .url("$baseUrl/api/shares/${share.code}/files/$index")
                    .header("X-Owner-Key", share.ownerKey)
                    .header("X-Upload-Offset", offset.toString())
                    .put(body)
                    .build()
                val call = uploadClient.newCall(request)
                activeUpload = call
                try {
                    call.execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (response.code == 409) throw IOException("Upload position changed")
                        if (!response.isSuccessful) throw errorFrom(response.code, text, "Upload failed")
                        if (JSONObject(text).optBoolean("done")) return@withContext
                        throw IOException("Upload incomplete")
                    }
                } finally {
                    activeUpload = null
                }
            } catch (e: ApiException) {
                throw e // the server said no (expired, too large...): retrying won't help
            } catch (e: IOException) {
                coroutineContext.ensureActive()
                if (isPaused()) continue // paused on purpose: not a failure
                failures++
                if (failures > 40) throw e
                onWaiting(true)
                delay(minOf(2_000L * failures, 15_000L))
            }
        }
    }

    suspend fun lookupUser(email: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("email", email.trim()).toString().toRequestBody("application/json".toMediaTypeOrNull())
        client.newCall(authBuilder("$baseUrl/api/users/lookup").post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, text, "Lookup failed")
            JSONObject(text).getString("name")
        }
    }

    private fun parseSummaries(text: String): List<ShareSummary> {
        val arr = JSONObject(text).optJSONArray("shares") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ShareSummary(
                code = o.getString("code"),
                url = o.getString("url"),
                sender = o.optString("sender"),
                downloads = o.optInt("downloads"),
                expiresAt = runCatching { java.time.Instant.parse(o.getString("expiresAt")).toEpochMilli() }.getOrDefault(0L),
                oneTime = o.optBoolean("oneTime"),
                hasPassword = o.optBoolean("hasPassword"),
                recipientName = o.optString("recipientName").takeIf { it.isNotBlank() && it != "null" },
                fileCount = o.optInt("fileCount"),
                totalSize = o.optLong("totalSize"),
                firstName = o.optString("firstName"),
                senderEmail = o.optString("senderEmail").takeIf { it.isNotBlank() && it != "null" },
                message = o.optString("message").takeIf { it != "null" } ?: "",
                releaseAt = parseTime(o.optString("releaseAt")),
                viewOnce = o.optBoolean("viewOnce"),
                e2e = o.optBoolean("e2e"),
                destructSeconds = o.optInt("destructSeconds"),
                geoRadius = o.optInt("geoRadius"),
                downloaders = o.optJSONArray("downloaders")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            )
        }
    }

    suspend fun myShares(): MyShares = withContext(Dispatchers.IO) {
        client.newCall(authBuilder("$baseUrl/api/my/shares").get().build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, text, "Could not load your shares")
            val o = JSONObject(text)
            MyShares(parseSummaries(text), o.optLong("usedBytes"), o.optLong("quotaBytes"))
        }
    }

    private fun postJson(path: String, json: JSONObject, fallback: String, auth: Boolean = false): JSONObject {
        val body = json.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val builder = if (auth) authBuilder("$baseUrl$path") else Request.Builder().url("$baseUrl$path")
        client.newCall(builder.post(body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, text, fallback)
            return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        }
    }

    /** Asks the server for a reset code. Returns true when it was emailed (false: ask the admin). */
    suspend fun forgotPassword(email: String): Boolean = withContext(Dispatchers.IO) {
        postJson("/api/auth/forgot", JSONObject().put("email", email.trim()), "Could not send the code").optBoolean("emailEnabled")
    }

    suspend fun resetPassword(email: String, code: String, password: String) = withContext(Dispatchers.IO) {
        postJson("/api/auth/reset", JSONObject().put("email", email.trim()).put("code", code.trim()).put("password", password), "Could not change the password")
        Unit
    }

    private fun getJson(path: String, fallback: String): JSONObject {
        client.newCall(authBuilder("$baseUrl$path").get().build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, text, fallback)
            return JSONObject(text)
        }
    }

    private fun send(method: String, path: String, json: JSONObject?, fallback: String): JSONObject {
        val body = json?.toString()?.toRequestBody("application/json".toMediaTypeOrNull())
        client.newCall(authBuilder("$baseUrl$path").method(method, body).build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, text, fallback)
            return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        }
    }

    // ---------------- Contacts ----------------

    private fun JSONObject.toContact() = Contact(getInt("id"), optString("name"), optString("email"), optBoolean("autoAccept"))

    suspend fun contacts(): List<Contact> = withContext(Dispatchers.IO) {
        val arr = getJson("/api/contacts", "Could not load contacts").optJSONArray("contacts") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it).toContact() }
    }

    suspend fun addContact(email: String): Contact = withContext(Dispatchers.IO) {
        send("POST", "/api/contacts", JSONObject().put("email", email.trim()), "Could not add the contact").toContact()
    }

    suspend fun setAutoAccept(id: Int, on: Boolean) = withContext(Dispatchers.IO) {
        send("PATCH", "/api/contacts/$id", JSONObject().put("autoAccept", on), "Could not save"); Unit
    }

    suspend fun removeContact(id: Int) = withContext(Dispatchers.IO) {
        send("DELETE", "/api/contacts/$id", null, "Could not remove the contact"); Unit
    }

    // ---------------- Groups ----------------

    private fun parseGroups(o: JSONObject): List<ContactGroup> {
        val arr = o.optJSONArray("groups") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val g = arr.getJSONObject(i)
            val m = g.optJSONArray("members") ?: JSONArray()
            ContactGroup(g.getInt("id"), g.optString("name"), (0 until m.length()).map { k ->
                m.getJSONObject(k).let { Contact(it.getInt("id"), it.optString("name"), it.optString("email"), false) }
            })
        }
    }

    suspend fun groups(): List<ContactGroup> = withContext(Dispatchers.IO) { parseGroups(getJson("/api/groups", "Could not load groups")) }

    suspend fun saveGroup(id: Int?, name: String, emails: List<String>): List<ContactGroup> = withContext(Dispatchers.IO) {
        val json = JSONObject().put("name", name.trim()).put("emails", JSONArray(emails))
        parseGroups(if (id == null) send("POST", "/api/groups", json, "Could not save the group") else send("PUT", "/api/groups/$id", json, "Could not save the group"))
    }

    suspend fun deleteGroup(id: Int) = withContext(Dispatchers.IO) { send("DELETE", "/api/groups/$id", null, "Could not delete the group"); Unit }

    // ---------------- Notices, extend, support ----------------

    suspend fun notices(after: Long): List<Notice> = withContext(Dispatchers.IO) {
        val arr = getJson("/api/notices?after=$after", "Notices failed").optJSONArray("notices") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it).let { n ->
            Notice(n.getLong("id"), n.optString("kind"), n.optString("code").takeIf { c -> c.isNotBlank() && c != "null" },
                n.optString("name").takeIf { c -> c.isNotBlank() && c != "null" }, n.optInt("count"), n.optString("body").takeIf { c -> c.isNotBlank() && c != "null" })
        } }
    }

    suspend fun extendShare(code: String, hours: Int = 24) = withContext(Dispatchers.IO) {
        send("POST", "/api/my/shares/$code/extend", JSONObject().put("hours", hours), "Could not extend"); Unit
    }

    suspend fun supportMessages(): List<SupportMessage> = withContext(Dispatchers.IO) {
        val arr = getJson("/api/support", "Could not load messages").optJSONArray("messages") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it).let { m ->
            SupportMessage(m.getInt("id"), m.optBoolean("fromAdmin"), m.optString("body"), parseTime(m.optString("createdAt")) ?: 0L)
        } }
    }

    suspend fun sendSupport(body: String) = withContext(Dispatchers.IO) {
        send("POST", "/api/support", JSONObject().put("body", body.trim()), "Could not send"); Unit
    }

    // ---------------- File requests ----------------

    private fun JSONObject.toRequest() = FileRequest(
        code = getString("code"), title = optString("title").takeIf { it != "null" } ?: "", url = optString("url"),
        expiresAt = runCatching { java.time.Instant.parse(getString("expiresAt")).toEpochMilli() }.getOrDefault(0L),
        uploads = optInt("uploads")
    )

    suspend fun createRequest(title: String): FileRequest = withContext(Dispatchers.IO) {
        send("POST", "/api/requests", JSONObject().put("title", title.trim()), "Could not create the link").toRequest()
    }

    suspend fun myRequests(): List<FileRequest> = withContext(Dispatchers.IO) {
        val arr = getJson("/api/requests", "Could not load your links").optJSONArray("requests") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it).toRequest() }
    }

    suspend fun deleteRequest(code: String) = withContext(Dispatchers.IO) {
        send("DELETE", "/api/requests/$code", null, "Could not delete the link"); Unit
    }

    // ---------------- Crash reports & broadcasts ----------------

    suspend fun reportCrash(stack: String, appVersion: String, device: String, android: String) = withContext(Dispatchers.IO) {
        send("POST", "/api/crash", JSONObject().put("stack", stack).put("appVersion", appVersion).put("device", device).put("android", android), "Report failed")
        Unit
    }

    suspend fun broadcasts(after: Int): List<Broadcast> = withContext(Dispatchers.IO) {
        val arr = getJson("/api/broadcasts?after=$after", "Messages failed").optJSONArray("broadcasts") ?: JSONArray()
        (0 until arr.length()).map { arr.getJSONObject(it).let { b -> Broadcast(b.getInt("id"), b.optString("title"), b.optString("body")) } }
    }

    /** The admin's announcement text ("" when none). */
    suspend fun appConfig(): AppConfig = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url("$baseUrl/api/app/config").get().build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, text, "Config failed")
            val o = JSONObject(text)
            AppConfig(
                announcement = o.optString("announcement"),
                minVersion = o.optInt("minVersion"),
                latestVersion = o.optInt("latestVersion"),
                updateUrl = o.optString("updateUrl"),
                updateNote = o.optString("updateNote")
            )
        }
    }

    /** "This file is bad": goes to the Sendit admins. */
    suspend fun report(code: String, reason: String, details: String) = withContext(Dispatchers.IO) {
        send("POST", "/api/report", JSONObject().put("code", code).put("reason", reason).put("details", details), "Could not send the report")
        Unit
    }

    suspend fun deleteAccount(password: String) = withContext(Dispatchers.IO) {
        postJson("/api/auth/delete-account", JSONObject().put("password", password), "Could not delete the account", auth = true)
        Unit
    }

    suspend fun inbox(): List<ShareSummary> = withContext(Dispatchers.IO) {
        client.newCall(authBuilder("$baseUrl/api/inbox").get().build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, text, "Could not load your inbox")
            parseSummaries(text)
        }
    }

    suspend fun deleteMyShare(code: String) = withContext(Dispatchers.IO) {
        client.newCall(authBuilder("$baseUrl/api/my/shares/$code").delete().build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, text, "Could not delete")
        }
    }

    fun cancelUpload() {
        runCatching { activeUpload?.cancel() }
    }

    suspend fun completeShare(share: CreatedShare) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/shares/${share.code}/complete")
            .header("X-Owner-Key", share.ownerKey)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, text, "Could not finish the upload")
        }
    }

    suspend fun shareStatus(share: CreatedShare): ShareStatus = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/shares/${share.code}/status")
            .header("X-Owner-Key", share.ownerKey)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw errorFrom(response.code, text, "Status failed")
            val o = JSONObject(text)
            ShareStatus(downloads = o.optInt("downloads"), status = o.optString("status"))
        }
    }

    suspend fun deleteShare(share: CreatedShare) = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$baseUrl/api/shares/${share.code}")
                .header("X-Owner-Key", share.ownerKey)
                .delete()
                .build()
            client.newCall(request).execute().close()
        }
    }
}
