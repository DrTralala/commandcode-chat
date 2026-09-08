package com.commandcode.chat.data.security

/** Saved global system prompt instructions, kept encrypted in dedicated storage. */
interface SystemPromptStore {
    fun read(): String
    fun save(value: String)
    fun clear()
}

class PromptRecoveryRequired(cause: Throwable? = null) :
    IllegalStateException("Stored system prompt requires recovery", cause)

/**
 * Encrypted persistence for the global system prompt, following the Keystore-backed
 * conventions of [SecretRepository] with its own dedicated alias and blob key.
 */
class EncryptedSystemPromptStore(
    private val store: EncryptedBlobStore,
    private val cipher: KeystoreCipher = KeystoreCipher(),
    private val alias: String = PROMPT_ALIAS,
    private val blobKey: String = PROMPT_BLOB_KEY,
) : SystemPromptStore {
    override fun save(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            store.put(blobKey, cipher.encrypt(alias, bytes))
        } finally {
            bytes.fill(0)
        }
    }

    override fun read(): String {
        val blob = try {
            store.get(blobKey)
        } catch (error: Exception) {
            throw PromptRecoveryRequired(error)
        } ?: return ""
        val plaintext = try {
            cipher.decrypt(alias, blob)
        } catch (error: Exception) {
            throw PromptRecoveryRequired(error)
        }
        return try {
            plaintext.toString(Charsets.UTF_8)
        } finally {
            plaintext.fill(0)
        }
    }

    override fun clear() = store.remove(blobKey)

    companion object {
        const val PROMPT_ALIAS = "commandcode-system-prompt-v1"
        private const val PROMPT_BLOB_KEY = "systemPrompt"
    }
}

/** Blank and whitespace-only instructions have the same effective meaning as no prompt. */
fun effectiveSystemPrompt(value: String): String = if (value.isBlank()) "" else value
