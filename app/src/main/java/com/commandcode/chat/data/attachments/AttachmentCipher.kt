package com.commandcode.chat.data.attachments

import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import java.security.GeneralSecurityException
import java.security.KeyException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM format and operation boundary for one attachment. */
class AttachmentCipher(private val keys: AttachmentKeyProvider) {
    fun encrypt(id: String, plaintext: ByteArray): ByteArray {
        if (plaintext.size.toLong() > MAX_IMAGE_BYTES) throw failure(AttachmentFailure.TOO_LARGE)
        var ciphertext: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keys.existingKey())
            val nonce = cipher.iv
            if (nonce.size != NONCE_BYTES) throw failure(AttachmentFailure.STORAGE)

            val header = header(nonce)
            val aad = aad(header, id)
            cipher.updateAAD(aad)
            val encrypted = cipher.doFinal(plaintext)
            ciphertext = encrypted
            val encoded = ByteArray(header.size + encrypted.size)
            header.copyInto(encoded)
            encrypted.copyInto(encoded, destinationOffset = header.size)
            if (encoded.size > MAX_ENCODED_BYTES) throw failure(AttachmentFailure.TOO_LARGE)
            return encoded
        } catch (error: AttachmentException) {
            throw error
        } catch (error: KeyException) {
            throw failure(AttachmentFailure.KEY_UNAVAILABLE, error)
        } catch (error: GeneralSecurityException) {
            throw failure(AttachmentFailure.STORAGE, error)
        } catch (error: RuntimeException) {
            throw failure(AttachmentFailure.STORAGE, error)
        } finally {
            ciphertext?.fill(0)
        }
    }

    fun decrypt(id: String, encoded: ByteArray): ByteArray {
        if (encoded.size < MIN_ENCODED_BYTES || encoded.size > MAX_ENCODED_BYTES) {
            throw failure(AttachmentFailure.STORAGE)
        }
        var nonce: ByteArray? = null
        var encryptedPayload: ByteArray? = null
        try {
            if (!hasMagic(encoded) || encoded[VERSION_OFFSET].toInt() != VERSION) {
                throw failure(AttachmentFailure.STORAGE)
            }
            val header = encoded.copyOfRange(0, HEADER_BYTES)
            nonce = encoded.copyOfRange(NONCE_OFFSET, HEADER_BYTES)
            encryptedPayload = encoded.copyOfRange(HEADER_BYTES, encoded.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                keys.existingKey(),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(aad(header, id))
            val plaintext = cipher.doFinal(encryptedPayload)
            if (plaintext.size.toLong() > MAX_IMAGE_BYTES) {
                plaintext.fill(0)
                throw failure(AttachmentFailure.STORAGE)
            }
            return plaintext
        } catch (error: AttachmentException) {
            throw error
        } catch (error: KeyException) {
            throw failure(AttachmentFailure.KEY_UNAVAILABLE, error)
        } catch (error: GeneralSecurityException) {
            throw failure(AttachmentFailure.STORAGE, error)
        } catch (error: RuntimeException) {
            throw failure(AttachmentFailure.STORAGE, error)
        } finally {
            nonce?.fill(0)
            encryptedPayload?.fill(0)
        }
    }

    private fun header(nonce: ByteArray): ByteArray =
        ByteArray(HEADER_BYTES).also {
            MAGIC.copyInto(it)
            it[VERSION_OFFSET] = VERSION.toByte()
            nonce.copyInto(it, destinationOffset = NONCE_OFFSET)
        }

    private fun aad(header: ByteArray, id: String): ByteArray {
        val idBytes = id.toByteArray(Charsets.UTF_8)
        return ByteArray(header.size + idBytes.size).also {
            header.copyInto(it)
            idBytes.copyInto(it, destinationOffset = header.size)
            idBytes.fill(0)
        }
    }

    private fun hasMagic(encoded: ByteArray): Boolean =
        encoded[0] == MAGIC[0] && encoded[1] == MAGIC[1] &&
            encoded[2] == MAGIC[2] && encoded[3] == MAGIC[3]

    private fun failure(kind: AttachmentFailure, cause: Throwable? = null): AttachmentException =
        AttachmentException(kind).also {
            if (cause != null) it.initCause(cause)
        }

    companion object {
        const val HEADER_BYTES: Int = 17
        const val NONCE_BYTES: Int = 12
        const val GCM_TAG_BYTES: Int = 16
        const val OVERHEAD_BYTES: Int = HEADER_BYTES + GCM_TAG_BYTES
        const val MAX_IMAGE_BYTES: Long = 10_000_000L
        const val MAX_ENCODED_BYTES: Int = 10_000_033

        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = GCM_TAG_BYTES * 8
        private const val VERSION_OFFSET = 4
        private const val NONCE_OFFSET = 5
        private const val VERSION = 1
        private const val MIN_ENCODED_BYTES = HEADER_BYTES + GCM_TAG_BYTES
        private val MAGIC = "CCAI".toByteArray(Charsets.US_ASCII)
    }
}
