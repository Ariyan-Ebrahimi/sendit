package com.beamdrop.app.util

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class VaultItem(val id: String, val name: String, val mimeType: String, val size: Long, val addedAt: Long)

/**
 * The hidden vault: files kept inside Sendit's private storage (not in the Gallery or Downloads,
 * invisible to other apps), opened with a separate PIN.
 */
class Vault(private val context: Context) {
    private val prefs = context.getSharedPreferences("sendit_vault", Context.MODE_PRIVATE)
    private val dir get() = File(context.filesDir, "vault").apply { mkdirs() }

    // ---------------- PIN ----------------

    val hasPin: Boolean get() = prefs.contains("pin_hash")

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .apply()
    }

    fun checkPin(pin: String): Boolean {
        val salt = Base64.decode(prefs.getString("pin_salt", null) ?: return false, Base64.NO_WRAP)
        val want = Base64.decode(prefs.getString("pin_hash", null) ?: return false, Base64.NO_WRAP)
        return java.security.MessageDigest.isEqual(hash(pin, salt), want)
    }

    var useFingerprint: Boolean
        get() = prefs.getBoolean("fingerprint", false)
        set(v) = prefs.edit().putBoolean("fingerprint", v).apply()

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)).encoded

    // ---------------- files ----------------

    fun list(): List<VaultItem> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { VaultItem(it.getString("id"), it.getString("name"), it.optString("mime"), it.optLong("size"), it.optLong("at")) }
        }.filter { File(dir, it.id).exists() }.sortedByDescending { it.addedAt }
    }.getOrDefault(emptyList())

    private fun save(items: List<VaultItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mimeType).put("size", it.size).put("at", it.addedAt)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    /** Copies a file into the vault. */
    @Synchronized
    fun add(source: Uri, name: String, mimeType: String): VaultItem? = runCatching {
        val id = "${System.currentTimeMillis()}-${(1000..9999).random()}"
        val target = File(dir, id)
        context.contentResolver.openInputStream(source)!!.use { input -> target.outputStream().use { input.copyTo(it, 64 * 1024) } }
        val item = VaultItem(id, name.substringAfterLast('/'), mimeType, target.length(), System.currentTimeMillis())
        save(list() + item)
        item
    }.getOrNull()

    @Synchronized
    fun delete(item: VaultItem) {
        File(dir, item.id).delete()
        save(list().filterNot { it.id == item.id })
    }

    /** Puts a copy back in Downloads / the Gallery. */
    fun export(item: VaultItem): Uri? = runCatching {
        val sink = DownloadSaver.create(context, item.name, item.mimeType, toGallery = true)
        try {
            File(dir, item.id).inputStream().use { it.copyTo(sink.output, 64 * 1024) }
            sink.finish()
        } catch (t: Throwable) { sink.abort(); throw t }
        sink.uri
    }.getOrNull()

    /** A temporary address other apps can open (for viewing). */
    fun uriFor(item: VaultItem): Uri =
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", File(dir, item.id))

    fun file(item: VaultItem) = File(dir, item.id)
}
