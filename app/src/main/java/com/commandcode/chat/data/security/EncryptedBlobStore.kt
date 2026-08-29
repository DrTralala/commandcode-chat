package com.commandcode.chat.data.security

import android.content.Context
import android.util.Base64
import org.json.JSONObject

class SecureStoragePersistenceFailure(cause: Throwable? = null) :
    IllegalStateException("Secure storage update failed", cause)

data class EncryptedBlob(val version: Int, val nonceBase64: String, val ciphertextBase64: String) {
    fun encode(): String = JSONObject().put("version", version).put("nonceBase64", nonceBase64)
        .put("ciphertextBase64", ciphertextBase64).toString()

    companion object {
        fun decode(value: String): EncryptedBlob {
            val json = JSONObject(value)
            return EncryptedBlob(json.getInt("version"), json.getString("nonceBase64"), json.getString("ciphertextBase64"))
        }
    }
}

open class EncryptedBlobStore(context: Context, private val name: String = "secure_secrets") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    open fun put(key: String, blob: EncryptedBlob) {
        if (!preferences.edit().putString(key, blob.encode()).commit()) throw SecureStoragePersistenceFailure()
    }
    fun get(key: String): EncryptedBlob? = preferences.getString(key, null)?.let(EncryptedBlob::decode)
    fun remove(key: String) {
        if (!preferences.edit().remove(key).commit()) throw SecureStoragePersistenceFailure()
    }
    fun rawValue(key: String): String? = preferences.getString(key, null)
    fun allRawValues(): Map<String, *> = preferences.all
}
