package com.commandcode.chat.data.service

import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

internal object CommandCodeModelsResponseCodec {
    private const val MAX_RESPONSE_BYTES = 1_048_576
    private const val MAX_RAW_MODELS = 1_000

    fun decode(text: String, fetchedAt: Instant): ModelCatalogueSnapshot {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_RESPONSE_BYTES) {
            "Models response exceeds the maximum size"
        }
        return try {
            decodeObject(parseUniqueJsonObject(text, "Models response"), fetchedAt)
        } catch (exception: IllegalArgumentException) {
            throw exception
        } catch (exception: Exception) {
            throw IllegalArgumentException("Invalid models response JSON", exception)
        }
    }

    private fun decodeObject(root: JSONObject, fetchedAt: Instant): ModelCatalogueSnapshot {
        val data = root.get("data")
        require(data is JSONArray) { "data must be an array" }
        require(data.length() <= MAX_RAW_MODELS) {
            "Models response exceeds the maximum model count"
        }

        val ids = HashSet<String>(data.length())
        val models = ArrayList<ChatModel>(data.length())
        for (index in 0 until data.length()) {
            val modelObject = data.get(index)
            require(modelObject is JSONObject) { "Model entry must be an object" }

            val id = stringValue(modelObject, "id")
            val name = stringValue(modelObject, "name")
            val contextWindowTokens = nullableContextWindowValue(modelObject)
            ModelCatalogueCodec.validateModelMetadata(id, name, contextWindowTokens)
            require(ids.add(id)) { "Duplicate model id" }

            val endpoints = stringArrayValue(modelObject, "supported_endpoints")
            val family = when {
                "/chat/completions" in endpoints -> ApiFamily.OPENAI_CHAT
                "/messages" in endpoints -> ApiFamily.ANTHROPIC_MESSAGES
                else -> null
            }
            if (family != null) {
                models += ChatModel(id, name, family, contextWindowTokens)
            }
        }

        val generatedAt = epochMillis(fetchedAt)
        return ModelCatalogueCodec.validate(
            ModelCatalogueSnapshot(
                schemaVersion = ModelCatalogueCodec.SUPPORTED_SCHEMA_VERSION,
                catalogueVersion = "live-$generatedAt",
                generatedAt = generatedAt,
                models = models,
                origin = CatalogueOrigin.LIVE,
            ),
        )
    }

    private fun stringValue(objectValue: JSONObject, key: String): String {
        val value = objectValue.get(key)
        require(value is String) { "$key must be a string" }
        return value
    }

    private fun stringArrayValue(objectValue: JSONObject, key: String): List<String> {
        val value = objectValue.get(key)
        require(value is JSONArray) { "$key must be an array" }
        return List(value.length()) { index ->
            val item = value.get(index)
            require(item is String) { "$key entries must be strings" }
            item
        }
    }

    private fun nullableContextWindowValue(modelObject: JSONObject): Long? {
        if (!modelObject.has("context_length")) return null
        val value = modelObject.get("context_length")
        if (value === JSONObject.NULL) return null
        require(value is Int || value is Long) { "context_length must be an integer or null" }
        val contextWindowTokens = value.toLong()
        require(contextWindowTokens > 0) { "context_length must be positive" }
        return contextWindowTokens
    }

    private fun epochMillis(instant: Instant): Long {
        val value = try {
            instant.toEpochMilli()
        } catch (exception: ArithmeticException) {
            throw IllegalArgumentException("fetchedAt must be a valid millisecond timestamp", exception)
        }
        require(value > 0) { "fetchedAt must be positive" }
        return value
    }
}
