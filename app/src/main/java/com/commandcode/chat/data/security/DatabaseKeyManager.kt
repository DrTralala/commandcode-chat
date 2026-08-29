package com.commandcode.chat.data.security

class DatabaseRecoveryRequired(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class DatabaseKeyManager(
    private val store: EncryptedBlobStore,
    private val cipher: KeystoreCipher = KeystoreCipher(),
    private val alias: String = DB_ALIAS,
    private val blobKey: String = DB_KEY,
) {
    fun <T> withPassphrase(block: (ByteArray) -> T): T {
        val passphrase = try {
            val blob = store.get(blobKey) ?: throw DatabaseRecoveryRequired("Stored database key wrapper is missing")
            cipher.decrypt(alias, blob)
        } catch (error: DatabaseRecoveryRequired) { throw error
        } catch (error: Exception) { throw DatabaseRecoveryRequired("Stored database key requires recovery", error) }
        return try { block(passphrase) } finally { passphrase.fill(0) }
    }

    fun initialiseIfMissing() {
        if (store.rawValue(blobKey) != null) return
        val generated = ByteArray(32)
        try {
            java.security.SecureRandom().nextBytes(generated)
            cipher.encrypt(alias, generated).also { store.put(blobKey, it) }
        } catch (error: Exception) {
            throw DatabaseRecoveryRequired("Database key wrapper could not be persisted", error)
        } finally { generated.fill(0) }
    }

    companion object { const val DB_ALIAS = "commandcode-db-key-v1"; private const val DB_KEY = "databaseKey" }
}
