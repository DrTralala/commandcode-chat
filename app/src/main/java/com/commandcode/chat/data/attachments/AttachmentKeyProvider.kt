package com.commandcode.chat.data.attachments

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.commandcode.chat.domain.AttachmentException
import com.commandcode.chat.domain.AttachmentFailure
import java.security.Key
import java.security.KeyStore
import javax.crypto.KeyGenerator

interface AttachmentKeyProvider {
    fun initialise(allowCreate: Boolean)
    fun existingKey(): Key
}

/** The Android Keystore-backed key used for authenticated attachment files. */
class AndroidAttachmentKeyProvider(
    private val alias: String = ALIAS,
) : AttachmentKeyProvider {
    override fun initialise(allowCreate: Boolean) = synchronized(KEYSTORE_LOCK) {
        try {
            val store = keyStore()
            if (store.containsAlias(alias)) {
                existingKey(store)
                return@synchronized
            }
            if (!allowCreate) throw unavailable()

            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .setUserAuthenticationRequired(false)
                        .build(),
                )
                generateKey()
            }
            existingKey(keyStore())
        } catch (error: AttachmentException) {
            throw error
        } catch (error: Exception) {
            throw unavailable(error)
        }
    }

    override fun existingKey(): Key = synchronized(KEYSTORE_LOCK) {
        try {
            existingKey(keyStore())
        } catch (error: AttachmentException) {
            throw error
        } catch (error: Exception) {
            throw unavailable(error)
        }
    }

    private fun existingKey(store: KeyStore): Key =
        store.getKey(alias, null) ?: throw unavailable()

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun unavailable(cause: Throwable? = null): AttachmentException =
        AttachmentException(AttachmentFailure.KEY_UNAVAILABLE).also {
            if (cause != null) it.initCause(cause)
        }

    companion object {
        const val ALIAS: String = "commandcode-attachment-key-v1"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private val KEYSTORE_LOCK = Any()
    }
}
