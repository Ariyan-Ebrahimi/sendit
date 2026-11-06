package com.beamdrop.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("beamdrop_prefs", Context.MODE_PRIVATE)

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, null)
            ?: defaultDeviceName()
        set(value) {
            val cleaned = value.trim().take(60).ifBlank { defaultDeviceName() }
            prefs.edit().putString(KEY_DEVICE_NAME, cleaned).apply()
        }

    val deviceCode: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_CODE, null)
            if (existing != null) return existing
            val random = SecureRandom()
            val alphabet = "0123456789ABCDEF"
            val code = buildString {
                repeat(4) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
            prefs.edit().putString(KEY_DEVICE_CODE, code).apply()
            return code
        }

    var keepAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_AWAKE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()

    // Sendit relay server (VPS). Old builds saved the emulator address; treat that as "use the default".
    var serverUrl: String
        get() {
            val saved = prefs.getString(KEY_SERVER_URL, null)?.trim()
            return if (saved.isNullOrBlank() || saved.contains("10.0.2.2")) DEFAULT_SERVER_URL else saved
        }
        set(value) {
            val cleaned = value.trim().removeSuffix("/")
            prefs.edit().putString(KEY_SERVER_URL, cleaned).apply()
        }

    // Send mode: true = Anywhere (upload to the Sendit server), false = nearby Wi-Fi only.
    var preferAnywhere: Boolean
        get() = prefs.getBoolean(KEY_PREFER_ANYWHERE, true)
        set(value) = prefs.edit().putBoolean(KEY_PREFER_ANYWHERE, value).apply()

    /** Received photos and videos go to the Gallery (Pictures/Movies) instead of Downloads. */
    var saveToGallery: Boolean
        get() = prefs.getBoolean("save_to_gallery", true)
        set(value) = prefs.edit().putBoolean("save_to_gallery", value).apply()

    /** Random id for this install, so "send to my devices" skips the phone that sent it. */
    val installId: String
        get() = prefs.getString("install_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("install_id", it).apply()
        }

    /** "Send smaller": compress photos and videos before uploading. */
    var sendSmaller: Boolean
        get() = prefs.getBoolean(KEY_SEND_SMALLER, false)
        set(value) = prefs.edit().putBoolean(KEY_SEND_SMALLER, value).apply()

    /** "system" | "en" | "fa" */
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    /** Inbox shares already notified about / opened, so we don't repeat ourselves. */
    var notifiedInboxCodes: Set<String>
        get() = prefs.getStringSet(KEY_INBOX_NOTIFIED, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_INBOX_NOTIFIED, value.toList().takeLast(300).toSet()).apply()

    var openedInboxCodes: Set<String>
        get() = prefs.getStringSet(KEY_INBOX_OPENED, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_INBOX_OPENED, value.toList().takeLast(300).toSet()).apply()

    /** "system" | "dark" | "light" */
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) = prefs.edit().putString("theme", value).apply()

    var accent: String
        get() = prefs.getString("accent", "indigo") ?: "indigo"
        set(value) = prefs.edit().putString("accent", value).apply()

    /** This phone's key pair for end-to-end encrypted shares (made once). */
    var e2eKeys: com.beamdrop.app.util.E2e.DeviceKeys?
        get() {
            val pub = prefs.getString("e2e_pub", null) ?: return null
            val priv = prefs.getString("e2e_priv", null) ?: return null
            return com.beamdrop.app.util.E2e.DeviceKeys(pub, priv)
        }
        set(v) = prefs.edit().putString("e2e_pub", v?.publicB64).putString("e2e_priv", v?.privateB64).apply()

    /** The "New in Sendit" card on Home was closed. */
    var tipsSeen: Boolean
        get() = prefs.getBoolean("tips_v18", false)
        set(value) = prefs.edit().putBoolean("tips_v18", value).apply()

    /** New sends are end-to-end encrypted unless the user turns it off. */
    var encryptByDefault: Boolean
        get() = prefs.getBoolean("encrypt_default", true)
        set(value) = prefs.edit().putBoolean("encrypt_default", value).apply()

    /** Ask for fingerprint / PIN when opening Sendit. */
    var appLock: Boolean
        get() = prefs.getBoolean("app_lock", false)
        set(value) = prefs.edit().putBoolean("app_lock", value).apply()

    var lastNoticeId: Long
        get() = prefs.getLong("last_notice", 0L)
        set(value) = prefs.edit().putLong("last_notice", value).apply()

    /** The welcome tour was shown. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    var lastBroadcastId: Int
        get() = prefs.getInt("last_broadcast", 0)
        set(value) = prefs.edit().putInt("last_broadcast", value).apply()

    /** People I sent to recently: list of (email, name), newest first. */
    var recentPeople: List<Pair<String, String>>
        get() = runCatching {
            val arr = JSONArray(prefs.getString("recent_people", "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.getString("email") to o.optString("name") } }
        }.getOrDefault(emptyList())
        set(value) {
            val arr = JSONArray()
            value.take(8).forEach { (e, n) -> arr.put(JSONObject().put("email", e).put("name", n)) }
            prefs.edit().putString("recent_people", arr.toString()).apply()
        }

    // Auth Token & User info
    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    var userEmail: String?
        get() = prefs.getString(KEY_USER_EMAIL, null)
        set(value) = prefs.edit().putString(KEY_USER_EMAIL, value).apply()

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    val isLoggedIn: Boolean
        get() = !authToken.isNullOrBlank()

    fun logout() {
        prefs.edit()
            .remove(KEY_AUTH_TOKEN)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_NAME)
            .apply()
    }

    fun loadHistory(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        HistoryEntry(
                            id = o.optString("id", UUID.randomUUID().toString()),
                            fileName = o.optString("fileName", "File"),
                            fileCount = o.optInt("fileCount", 1),
                            totalBytes = o.optLong("totalBytes", 0L),
                            direction = if (o.optString("direction") == "RECEIVE") {
                                Direction.RECEIVE
                            } else {
                                Direction.SEND
                            },
                            peerName = o.optString("peerName", "Nearby device"),
                            timestamp = o.optLong("timestamp", 0L),
                            success = o.optBoolean("success", true),
                            files = o.optJSONArray("files")?.let { arr ->
                                (0 until arr.length()).mapNotNull { i ->
                                    arr.optJSONObject(i)?.let { f ->
                                        SavedFile(f.optString("name"), f.optString("uri"), f.optString("mime"), f.optLong("size"))
                                    }
                                }
                            } ?: emptyList()
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveHistory(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.take(MAX_HISTORY).forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("fileName", entry.fileName)
                    put("fileCount", entry.fileCount)
                    put("totalBytes", entry.totalBytes)
                    put("direction", entry.direction.name)
                    put("peerName", entry.peerName)
                    put("timestamp", entry.timestamp)
                    put("success", entry.success)
                    if (entry.files.isNotEmpty()) {
                        put("files", JSONArray().apply {
                            entry.files.forEach { f -> put(JSONObject().put("name", f.name).put("uri", f.uri).put("mime", f.mimeType).put("size", f.size)) }
                        })
                    }
                }
            )
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun defaultDeviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isBlank()) "Android device" else model
    }

    companion object {
        val DEFAULT_SERVER_URL: String = com.beamdrop.app.BuildConfig.SERVER_URL
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_CODE = "device_code"
        private const val KEY_KEEP_AWAKE = "keep_awake"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_HISTORY = "history"
        private const val KEY_PREFER_ANYWHERE = "prefer_anywhere"
        private const val KEY_SEND_SMALLER = "send_smaller"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_INBOX_NOTIFIED = "inbox_notified"
        private const val KEY_INBOX_OPENED = "inbox_opened"
        private const val MAX_HISTORY = 200
    }
}
