package com.commandcode.chat.data.service

import android.content.Context
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Release-pinned public Provider metadata. Never infer capacities from model aliases. */
data class ContextWindowCatalogue(
    val limits: Map<String, Long?> = emptyMap(),
    val source: String = "https://api.commandcode.ai/provider/v1/models",
    val verifiedAt: String = "",
) {
    fun limitFor(modelId: String): Long? = limits[modelId]?.takeIf { it > 0 }

    companion object {
        private const val MAX_BYTES = 65_536

        suspend fun load(context: Context): ContextWindowCatalogue = withContext(Dispatchers.IO) {
            context.applicationContext.assets.open("model-context-windows.json").use { input ->
                val bytes = ByteArray(MAX_BYTES + 1)
                var size = 0
                while (size < bytes.size) {
                    val count = input.read(bytes, size, bytes.size - size)
                    if (count < 0) break
                    size += count
                }
                require(size <= MAX_BYTES) { "Context metadata exceeds maximum size" }
                decode(String(bytes, 0, size, Charsets.UTF_8))
            }
        }

        fun decode(json: String): ContextWindowCatalogue {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val root = JSONObject(json)
            require(root.get("schemaVersion") == 1)
            val source = root.getString("source")
            require(source == "https://api.commandcode.ai/provider/v1/models")
            val date = root.getString("verifiedAt")
            LocalDate.parse(date)
            val models = root.getJSONArray("models")
            val limits = linkedMapOf<String, Long?>()
            for (index in 0 until models.length()) {
                val model = models.getJSONObject(index)
                val id = model.getString("id")
                require(id.isNotBlank() && id == id.trim() && !limits.containsKey(id))
                val value = model.get("contextWindowTokens")
                val limit = if (value == JSONObject.NULL) null else {
                    require(value is Int || value is Long) { "Context capacity must be an integer" }
                    (value as Number).toLong().also { require(it > 0) }
                }
                limits[id] = limit
            }
            return ContextWindowCatalogue(limits.toMap(), source, date)
        }
    }
}
