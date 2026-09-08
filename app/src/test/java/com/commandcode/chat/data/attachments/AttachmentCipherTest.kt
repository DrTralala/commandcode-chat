package com.commandcode.chat.data.attachments

import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import java.security.Key
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentCipherTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val keys = FixedKeyProvider(key)
    private val cipher = AttachmentCipher(keys)

    @Test
    fun encryptsWithFreshNonceAndAuthenticatesAttachmentIdAndPayload() {
        val first = cipher.encrypt("image-a", "private image".toByteArray())
        val second = cipher.encrypt("image-a", "private image".toByteArray())

        assertFalse(first.contentEquals(second))
        assertArrayEquals("private image".toByteArray(), cipher.decrypt("image-a", first))
        assertThrows(AttachmentException::class.java) { cipher.decrypt("image-b", first) }

        first[first.lastIndex] = (first.last().toInt() xor 1).toByte()
        assertThrows(AttachmentException::class.java) { cipher.decrypt("image-a", first) }
    }

    @Test
    fun encodedFormatHasMagicVersionNonceAndGcmTagOverhead() {
        val encoded = cipher.encrypt("image-a", byteArrayOf(1, 2, 3))

        assertArrayEquals("CCAI".toByteArray(Charsets.US_ASCII), encoded.copyOfRange(0, 4))
        assertTrue(encoded[4].toInt() == 1)
        assertTrue(encoded.size == 3 + 33)
    }

    @Test
    fun malformedMagicVersionBoundsAndOversizedEncodingAreRejected() {
        val encoded = cipher.encrypt("image-a", byteArrayOf(1, 2, 3))

        val badMagic = encoded.copyOf().also { it[0] = 'X'.code.toByte() }
        val badVersion = encoded.copyOf().also { it[4] = 2 }
        val tooShort = encoded.copyOf(32)
        val tooLarge = ByteArray(10_000_034)

        assertFailure(AttachmentFailure.STORAGE) { cipher.decrypt("image-a", badMagic) }
        assertFailure(AttachmentFailure.STORAGE) { cipher.decrypt("image-a", badVersion) }
        assertFailure(AttachmentFailure.STORAGE) { cipher.decrypt("image-a", tooShort) }
        assertFailure(AttachmentFailure.STORAGE) { cipher.decrypt("image-a", tooLarge) }
    }

    @Test
    fun nonceTamperingFailsAuthentication() {
        val encoded = cipher.encrypt("image-a", byteArrayOf(1, 2, 3))
        encoded[5] = (encoded[5].toInt() xor 1).toByte()

        assertFailure(AttachmentFailure.STORAGE) { cipher.decrypt("image-a", encoded) }
    }

    @Test
    fun missingKeyIsKeptDistinctFromCiphertextAuthenticationFailure() {
        val unavailable = AttachmentCipher(MissingKeyProvider())

        val error = assertThrows(AttachmentException::class.java) {
            unavailable.encrypt("image-a", byteArrayOf(1))
        }

        assertTrue(error.kind == AttachmentFailure.KEY_UNAVAILABLE)
    }

    @Test
    fun invalidKeyExceptionIsClassifiedAsKeyUnavailable() {
        val unavailable = AttachmentCipher(InvalidKeyProvider())

        val error = assertThrows(AttachmentException::class.java) {
            unavailable.encrypt("image-a", byteArrayOf(1))
        }

        assertTrue(error.kind == AttachmentFailure.KEY_UNAVAILABLE)
    }

    @Test
    fun encryptionRejectsPlaintextAboveImageCeiling() {
        val error = assertThrows(AttachmentException::class.java) {
            cipher.encrypt("image-a", ByteArray(10_000_001))
        }

        assertTrue(error.kind == AttachmentFailure.TOO_LARGE)
    }

    private fun assertFailure(expected: AttachmentFailure, block: () -> Unit) {
        val error = assertThrows(AttachmentException::class.java, block)
        assertTrue("expected $expected but was ${error.kind}", error.kind == expected)
    }

    private class FixedKeyProvider(private val key: Key) : AttachmentKeyProvider {
        override fun initialise(allowCreate: Boolean) = Unit

        override fun existingKey(): Key = key
    }

    private class MissingKeyProvider : AttachmentKeyProvider {
        override fun initialise(allowCreate: Boolean) {
            throw AttachmentException(AttachmentFailure.KEY_UNAVAILABLE)
        }

        override fun existingKey(): Key {
            throw AttachmentException(AttachmentFailure.KEY_UNAVAILABLE)
        }
    }

    private class InvalidKeyProvider : AttachmentKeyProvider {
        override fun initialise(allowCreate: Boolean) = Unit

        override fun existingKey(): Key = SecretKeySpec(ByteArray(7), "AES")
    }
}
