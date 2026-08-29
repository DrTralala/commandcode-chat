package com.commandcode.chat.data.security

class KeyRecoveryRequired(cause: Throwable? = null) : IllegalStateException("Stored API key requires recovery", cause)

class SecretRepository(
    private val store: EncryptedBlobStore,
    private val cipher: KeystoreCipher = KeystoreCipher(),
) {
    fun saveApiKey(value: CharArray) {
        val bytes = value.concatToString().toByteArray(Charsets.UTF_8)
        try { store.put(API_KEY, cipher.encrypt(API_ALIAS, bytes)) } finally { bytes.fill(0); value.fill('\u0000') }
    }

    fun readApiKey(): CharArray? {
        val blob = try { store.get(API_KEY) } catch (error: Exception) { throw KeyRecoveryRequired(error) } ?: return null
        return try { cipher.decrypt(API_ALIAS, blob).toString(Charsets.UTF_8).toCharArray() }
        catch (error: Exception) { throw KeyRecoveryRequired(error) }
    }

    fun clearApiKey() = store.remove(API_KEY)

    companion object { const val API_ALIAS = "commandcode-api-key-v1"; private const val API_KEY = "apiKey" }
}
