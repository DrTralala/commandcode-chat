package com.commandcode.chat.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class KeystoreCipher {
    fun encrypt(alias: String, plaintext: ByteArray): EncryptedBlob {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(alias))
        return EncryptedBlob(1, Base64.encodeToString(cipher.iv, Base64.NO_WRAP), Base64.encodeToString(cipher.doFinal(plaintext), Base64.NO_WRAP))
    }

    fun decrypt(alias: String, blob: EncryptedBlob): ByteArray {
        require(blob.version == 1) { "Unsupported encrypted blob version" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(alias), GCMParameterSpec(128, Base64.decode(blob.nonceBase64, Base64.DEFAULT)))
        return cipher.doFinal(Base64.decode(blob.ciphertextBase64, Base64.DEFAULT))
    }

    private fun key(alias: String): java.security.Key {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).setUserAuthenticationRequired(false).build())
                generateKey()
            }
        }
        return store.getKey(alias, null)
    }

    companion object { private const val TRANSFORMATION = "AES/GCM/NoPadding" }
}
