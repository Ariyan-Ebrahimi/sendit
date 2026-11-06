package com.beamdrop.app.util

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for "Anywhere" shares. The server only ever stores ciphertext.
 *
 *  - Every share gets a random 256-bit key. Files are encrypted in 1 MiB chunks with AES-256-GCM
 *    (nonce = file index + chunk index), so uploads and downloads can still resume mid-file.
 *  - File names, types and the message travel in an encrypted "meta" blob.
 *  - The share key reaches the receiver either in the link (#k=…, never sent to the server) or
 *    locked for each of their phones: ECDH(P-256) with a one-time key → HKDF → AES-GCM.
 */
object E2e {
    const val CHUNK = 1024 * 1024
    private const val TAG = 16
    private val random = SecureRandom()

    // ---------------- keys ----------------

    data class DeviceKeys(val publicB64: String, val privateB64: String)

    fun newDeviceKeys(): DeviceKeys {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
        val kp = g.generateKeyPair()
        return DeviceKeys(b64(kp.public.encoded), b64(kp.private.encoded))
    }

    fun newShareKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    private fun publicKeyOf(x509: ByteArray): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(x509))
    private fun privateKeyOf(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    /** One-time sender key for a share; its public half goes to the server as "epk". */
    fun newEphemeral(): DeviceKeys = newDeviceKeys()

    fun wrapKey(shareKey: ByteArray, ephemeral: DeviceKeys, recipientPublicB64: String): String =
        b64(wrapBytes(shareKey, unb64(ephemeral.privateB64), unb64(recipientPublicB64)))

    fun unwrapKey(wrappedB64: String, epkB64: String, device: DeviceKeys): ByteArray =
        unwrapBytes(unb64(wrappedB64), unb64(epkB64), unb64(device.privateB64))

    /** [privateKey] PKCS#8 / [publicKey] X.509 encoded. */
    internal fun wrapBytes(shareKey: ByteArray, senderPrivate: ByteArray, recipientPublic: ByteArray): ByteArray {
        val kek = kek(privateKeyOf(senderPrivate), publicKeyOf(recipientPublic))
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, nonce)) }
        return nonce + c.doFinal(shareKey)
    }

    internal fun unwrapBytes(raw: ByteArray, senderPublic: ByteArray, recipientPrivate: ByteArray): ByteArray {
        val kek = kek(privateKeyOf(recipientPrivate), publicKeyOf(senderPublic))
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, raw, 0, 12))
        }
        return c.doFinal(raw, 12, raw.size - 12)
    }

    internal fun newKeyPair(): java.security.KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    private fun kek(priv: PrivateKey, pub: PublicKey): ByteArray {
        val secret = KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pub, true); generateSecret() }
        // HKDF-SHA256 (extract with a zero salt, expand one block).
        val prk = hmac(ByteArray(32), secret)
        return hmac(prk, "sendit-e2e-wrap".toByteArray() + byteArrayOf(1))
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** Key for a link: https://…/s/CODE#k=<this>. */
    fun keyToLink(key: ByteArray): String = Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    fun keyFromLink(text: String): ByteArray? = runCatching {
        Base64.decode(text, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).takeIf { it.size == 32 }
    }.getOrNull()

    // ---------------- meta (names, types, message) ----------------

    data class MetaFile(val name: String, val mime: String, val size: Long)

    fun sealMeta(key: ByteArray, files: List<MetaFile>, message: String): String {
        val json = JSONObject()
            .put("files", JSONArray().apply { files.forEach { put(JSONObject().put("name", it.name).put("mime", it.mime).put("size", it.size)) } })
            .put("message", message)
        val c = cipher(Cipher.ENCRYPT_MODE, key, -1, 0)
        return b64(c.doFinal(json.toString().toByteArray()))
    }

    fun openMeta(key: ByteArray, sealed: String): Pair<List<MetaFile>, String> {
        val text = String(cipher(Cipher.DECRYPT_MODE, key, -1, 0).doFinal(unb64(sealed)))
        val o = JSONObject(text)
        val arr = o.getJSONArray("files")
        return (0 until arr.length()).map { i ->
            arr.getJSONObject(i).let { MetaFile(it.getString("name"), it.optString("mime", "application/octet-stream"), it.optLong("size")) }
        } to o.optString("message")
    }

    // ---------------- file bytes ----------------

    fun encryptedSize(plain: Long): Long = if (plain <= 0) 0 else plain + TAG * ((plain + CHUNK - 1) / CHUNK)

    private fun cipher(mode: Int, key: ByteArray, fileIndex: Int, chunk: Long): Cipher {
        val nonce = ByteBuffer.allocate(12).putInt(fileIndex).putLong(chunk).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
    }

    /**
     * The encrypted form of [open]'s bytes, starting at [encOffset] (for resumed uploads).
     */
    fun encryptingStream(open: () -> InputStream, key: ByteArray, fileIndex: Int, encOffset: Long): InputStream {
        val src = open()
        val firstChunk = encOffset / (CHUNK + TAG)
        var toSkip = firstChunk * CHUNK
        while (toSkip > 0) {
            val n = src.skip(toSkip)
            if (n <= 0) { if (src.read() < 0) break else toSkip-- } else toSkip -= n
        }
        return object : InputStream() {
            var chunk = firstChunk
            var buf = ByteArray(0)
            var pos = 0
            var drop = (encOffset - firstChunk * (CHUNK + TAG)).toInt()
            var ended = false
            private fun fill(): Boolean {
                while (pos >= buf.size) {
                    if (ended) return false
                    val plain = ByteArray(CHUNK)
                    var n = 0
                    while (n < CHUNK) {
                        val r = src.read(plain, n, CHUNK - n)
                        if (r < 0) { ended = true; break }
                        n += r
                    }
                    if (n == 0) return false
                    buf = cipher(Cipher.ENCRYPT_MODE, key, fileIndex, chunk++).doFinal(plain, 0, n)
                    pos = 0
                    if (drop > 0) { pos = minOf(drop, buf.size); drop -= pos }
                }
                return true
            }
            override fun read(): Int = if (!fill()) -1 else buf[pos++].toInt() and 0xFF
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (!fill()) return -1
                val n = minOf(len, buf.size - pos)
                System.arraycopy(buf, pos, b, off, n)
                pos += n
                return n
            }
            override fun close() = src.close()
        }
    }

    /** Decrypts a whole downloaded file. Throws if anything was changed (wrong key or tampering). */
    fun decrypt(input: InputStream, output: OutputStream, key: ByteArray, fileIndex: Int) {
        val block = ByteArray(CHUNK + TAG)
        var chunk = 0L
        while (true) {
            var n = 0
            while (n < block.size) {
                val r = input.read(block, n, block.size - n)
                if (r < 0) break
                n += r
            }
            if (n == 0) break
            if (n <= TAG) throw IOException("The encrypted file is damaged.")
            output.write(cipher(Cipher.DECRYPT_MODE, key, fileIndex, chunk++).doFinal(block, 0, n))
            if (n < block.size) break
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
