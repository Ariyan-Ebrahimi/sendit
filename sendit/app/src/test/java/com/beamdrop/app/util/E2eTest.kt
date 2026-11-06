package com.beamdrop.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class E2eTest {
    private val key = ByteArray(32) { it.toByte() }
    private val tag = 16

    private fun encryptAll(plain: ByteArray, fileIndex: Int = 0, offset: Long = 0): ByteArray =
        E2e.encryptingStream({ ByteArrayInputStream(plain) }, key, fileIndex, offset).readBytes()

    private fun decryptAll(enc: ByteArray, k: ByteArray = key, fileIndex: Int = 0): ByteArray =
        ByteArrayOutputStream().also { E2e.decrypt(ByteArrayInputStream(enc), it, k, fileIndex) }.toByteArray()

    @Test
    fun roundTripAndSize() {
        for (size in listOf(0, 1, 1000, E2e.CHUNK - 1, E2e.CHUNK, E2e.CHUNK + 1, 3 * E2e.CHUNK + 12345)) {
            val plain = Random(size).nextBytes(size)
            val enc = encryptAll(plain)
            assertEquals("size for $size", E2e.encryptedSize(size.toLong()), enc.size.toLong())
            assertArrayEquals("round trip $size", plain, decryptAll(enc))
        }
    }

    /** A resumed upload continues the exact same byte stream from any position. */
    @Test
    fun resumeFromAnyOffset() {
        val plain = Random(7).nextBytes(3 * E2e.CHUNK + 999)
        val full = encryptAll(plain, fileIndex = 2)
        val block = E2e.CHUNK + tag
        for (off in listOf(1L, 100L, block - 1L, block.toLong(), block + 5L, 2L * block, full.size - 1L, full.size.toLong())) {
            val rest = encryptAll(plain, fileIndex = 2, offset = off)
            assertArrayEquals("offset $off", full.copyOfRange(off.toInt(), full.size), rest)
        }
    }

    @Test
    fun wrongKeyOrTamperingFails() {
        val enc = encryptAll(Random(1).nextBytes(5000))
        try { decryptAll(enc, ByteArray(32)); fail("wrong key must fail") } catch (_: Exception) { }
        enc[100] = (enc[100].toInt() xor 1).toByte()
        try { decryptAll(enc); fail("changed byte must fail") } catch (_: Exception) { }
        // Each file has its own nonces: file 0's bytes don't decrypt as file 1.
        try { decryptAll(encryptAll(ByteArray(10)), fileIndex = 1); fail("wrong file index must fail") } catch (_: Exception) { }
    }

    @Test
    fun keyWrapReachesOnlyTheRightPhone() {
        val phone = E2e.newKeyPair()
        val other = E2e.newKeyPair()
        val eph = E2e.newKeyPair()
        val shareKey = E2e.newShareKey()
        val wrapped = E2e.wrapBytes(shareKey, eph.private.encoded, phone.public.encoded)
        assertArrayEquals(shareKey, E2e.unwrapBytes(wrapped, eph.public.encoded, phone.private.encoded))
        try { E2e.unwrapBytes(wrapped, eph.public.encoded, other.private.encoded); fail("other phone must not unlock") } catch (_: Exception) { }
    }
}
