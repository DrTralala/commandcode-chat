package com.commandcode.chat.data.security

class DatabaseRecoveryRequired(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class DatabaseKeyManager(
    private val store: EncryptedBlobStore,
    private val cipher: KeystoreCipher = KeystoreCipher(),
) {
    fun <T> withPassphrase(block: (ByteArray) -> T): T {
        val passphrase = try {
            val blob = store.get(DB_KEY) ?: throw DatabaseRecoveryRequired("Stored database key wrapper is missing")
            if (!cipher.hasKey(DB_ALIAS)) throw DatabaseRecoveryRequired("Stored database Keystore key is unavailable")
            cipher.decrypt(DB_ALIAS, blob)
        } catch (error: DatabaseRecoveryRequired) { throw error
        } catch (error: Exception) { throw DatabaseRecoveryRequired("Stored database key requires recovery", error) }
        return try { block(passphrase) } finally { passphrase.fill(0) }
    }

    fun initialiseIfMissing() {
        if (store.rawValue(DB_KEY) != null) return
        val generated = ByteArray(32)
        try {
            java.security.SecureRandom().nextBytes(generated)
            cipher.encrypt(DB_ALIAS, generated).also { store.put(DB_KEY, it) }
        } finally { generated.fill(0) }
    }

    companion object { const val DB_ALIAS = "commandcode-db-key-v1"; private const val DB_KEY = "databaseKey" }
}
