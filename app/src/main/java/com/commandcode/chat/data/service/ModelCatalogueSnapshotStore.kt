package com.commandcode.chat.data.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class ModelCatalogueSnapshotStore(context: Context) : ModelCatalogueStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun loadCatalogue(): ModelCatalogueSnapshot? = synchronized(preferences) {
        try {
            preferences.getString(SNAPSHOT_KEY, null)?.let(ModelCatalogueCodec::decode)
        } catch (_: Exception) {
            null
        }
    }

    override fun loadKnownContextWindowMetadata(): Map<String, Long> = synchronized(preferences) {
        readKnownContextWindowMetadata()
    }

    override fun saveCatalogue(snapshot: ModelCatalogueSnapshot) {
        val validated = ModelCatalogueCodec.validate(snapshot)
        val encoded = ModelCatalogueCodec.encode(validated)
        synchronized(preferences) {
            val previousSnapshot = readPreferenceString(SNAPSHOT_KEY)
            val previousContextWindows = readPreferenceString(CONTEXT_WINDOWS_KEY)
            val knownContextWindows = LinkedHashMap(readKnownContextWindowMetadata())
            val previousModels = try {
                previousSnapshot?.let(ModelCatalogueCodec::decode)?.models.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            previousModels.forEach { model ->
                model.contextWindowTokens?.let {
                    knownContextWindows.remove(model.apiId)
                    knownContextWindows[model.apiId] = it
                }
            }
            validated.models.forEach { model ->
                model.contextWindowTokens?.let {
                    knownContextWindows.remove(model.apiId)
                    knownContextWindows[model.apiId] = it
                }
            }
            while (knownContextWindows.size > MAX_CONTEXT_WINDOW_METADATA_ENTRIES) {
                knownContextWindows.remove(knownContextWindows.keys.first())
            }
            val encodedContextWindows = encodeKnownContextWindowMetadata(knownContextWindows)
            if (!preferences.edit()
                    .putString(SNAPSHOT_KEY, encoded)
                    .putString(CONTEXT_WINDOWS_KEY, encodedContextWindows)
                    .commit()
            ) {
                val rollback = preferences.edit()
                if (previousSnapshot == null) {
                    rollback.remove(SNAPSHOT_KEY)
                } else {
                    rollback.putString(SNAPSHOT_KEY, previousSnapshot)
                }
                if (previousContextWindows == null) {
                    rollback.remove(CONTEXT_WINDOWS_KEY)
                } else {
                    rollback.putString(CONTEXT_WINDOWS_KEY, previousContextWindows)
                }
                check(rollback.commit()) {
                    "Could not restore previous model catalogue snapshot"
                }
                throw IllegalStateException("Could not persist model catalogue snapshot")
            }
        }
    }

    private fun readKnownContextWindowMetadata(): Map<String, Long> = try {
        readPreferenceString(CONTEXT_WINDOWS_KEY)?.let(::decodeKnownContextWindowMetadata) ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun readPreferenceString(key: String): String? = try {
        preferences.getString(key, null)
    } catch (_: ClassCastException) {
        null
    }

    private fun encodeKnownContextWindowMetadata(metadata: Map<String, Long>): String {
        require(metadata.size <= MAX_CONTEXT_WINDOW_METADATA_ENTRIES)
        val encoded = JSONObject()
            .put("schemaVersion", CONTEXT_WINDOW_METADATA_SCHEMA_VERSION)
            .put("models", JSONArray().apply {
                metadata.forEach { (id, contextWindowTokens) ->
                    ModelCatalogueCodec.validateModelMetadata(id, "Known model", contextWindowTokens)
                    put(
                        JSONObject()
                            .put("id", id)
                            .put("contextWindowTokens", contextWindowTokens),
                    )
                }
            })
            .toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_CONTEXT_WINDOW_METADATA_BYTES)
        return encoded
    }

    private fun decodeKnownContextWindowMetadata(text: String): Map<String, Long> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_CONTEXT_WINDOW_METADATA_BYTES)
        val tokener = JSONTokener(text)
        val root = tokener.nextValue()
        require(root is JSONObject && tokener.nextClean() == '\u0000')
        require(root.keys().asSequence().toSet() == setOf("schemaVersion", "models"))
        require(root.get("schemaVersion") == CONTEXT_WINDOW_METADATA_SCHEMA_VERSION)
        val models = root.get("models")
        require(models is JSONArray && models.length() <= MAX_CONTEXT_WINDOW_METADATA_ENTRIES)
        val metadata = linkedMapOf<String, Long>()
        for (index in 0 until models.length()) {
            val model = models.get(index)
            require(model is JSONObject && model.keys().asSequence().toSet() == setOf("id", "contextWindowTokens"))
            val idValue = model.get("id")
            require(idValue is String)
            val contextWindowValue = model.get("contextWindowTokens")
            require(contextWindowValue is Int || contextWindowValue is Long)
            val contextWindowTokens = (contextWindowValue as Number).toLong()
            ModelCatalogueCodec.validateModelMetadata(idValue, "Known model", contextWindowTokens)
            require(metadata.put(idValue, contextWindowTokens) == null) { "Duplicate context metadata model id" }
        }
        return metadata
    }

    private companion object {
        const val PREFERENCES_NAME = "model_catalogue"
        const val SNAPSHOT_KEY = "snapshot"
        const val CONTEXT_WINDOWS_KEY = "context_windows"
        const val CONTEXT_WINDOW_METADATA_SCHEMA_VERSION = 1
        const val MAX_CONTEXT_WINDOW_METADATA_ENTRIES = ModelCatalogueCodec.MAX_MODELS
        const val MAX_CONTEXT_WINDOW_METADATA_BYTES = ModelCatalogueCodec.MAX_CATALOGUE_BYTES
    }
}
