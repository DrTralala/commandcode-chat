package com.commandcode.chat.data.security

import android.content.Context
import android.util.Base64
import org.json.JSONObject

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

class EncryptedBlobStore(context: Context, private val name: String = "secure_secrets") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun put(key: String, blob: EncryptedBlob) { preferences.edit().putString(key, blob.encode()).apply() }
    fun get(key: String): EncryptedBlob? = preferences.getString(key, null)?.let(EncryptedBlob::decode)
    fun remove(key: String) { preferences.edit().remove(key).apply() }
    fun rawValue(key: String): String? = preferences.getString(key, null)
    fun allRawValues(): Map<String, *> = preferences.all
}
