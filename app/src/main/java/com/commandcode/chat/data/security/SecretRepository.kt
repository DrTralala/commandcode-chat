package com.commandcode.chat.data.security

class KeyRecoveryRequired(cause: Throwable? = null) : IllegalStateException("Stored API key requires recovery", cause)

class SecretRepository(
    private val store: EncryptedBlobStore,
    private val cipher: KeystoreCipher = KeystoreCipher(),
    private val alias: String = API_ALIAS,
    private val blobKey: String = API_KEY,
) {
    fun saveApiKey(value: CharArray) {
        val bytes = value.concatToString().toByteArray(Charsets.UTF_8)
        try { store.put(blobKey, cipher.encrypt(alias, bytes)) } finally { bytes.fill(0); value.fill('\u0000') }
    }

    fun readApiKey(): CharArray? {
        val blob = try { store.get(blobKey) } catch (error: Exception) { throw KeyRecoveryRequired(error) } ?: return null
        val plaintext = try { cipher.decrypt(alias, blob) }
        catch (error: Exception) { throw KeyRecoveryRequired(error) }
        return try { plaintext.toString(Charsets.UTF_8).toCharArray() } finally { plaintext.fill(0) }
    }

    fun clearApiKey() = store.remove(blobKey)

    companion object { const val API_ALIAS = "commandcode-api-key-v1"; private const val API_KEY = "apiKey" }
}
