package com.commandcode.chat.data.security

class DatabaseKeyManager(
    private val store: EncryptedBlobStore,
    private val cipher: KeystoreCipher = KeystoreCipher(),
) {
    fun <T> withPassphrase(block: (ByteArray) -> T): T {
        val passphrase = try {
            val blob = store.get(DB_KEY) ?: run {
                val generated = ByteArray(32)
                try {
                    java.security.SecureRandom().nextBytes(generated)
                    cipher.encrypt(DB_ALIAS, generated).also { store.put(DB_KEY, it) }
                } finally { generated.fill(0) }
            }
            cipher.decrypt(DB_ALIAS, blob)
        } catch (error: Exception) { throw KeyRecoveryRequired(error) }
        return try { block(passphrase) } finally { passphrase.fill(0) }
    }

    companion object { const val DB_ALIAS = "commandcode-db-key-v1"; private const val DB_KEY = "databaseKey" }
}
