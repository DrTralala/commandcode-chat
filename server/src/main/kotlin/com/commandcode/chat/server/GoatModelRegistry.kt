package com.commandcode.chat.server

import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object GoatModelRegistry {
    const val SUPPORTED_SCHEMA = 1
    const val MAX_MODELS = 100
    const val MAX_CATALOGUE_BYTES = 131_072
    const val SUPPORTED_API_FAMILY = "openai-chat"

    private val json = Json { ignoreUnknownKeys = false }

    fun load(input: InputStream): ModelCatalogueResponse {
        val body = readBounded(input)
        val catalogue = try {
            json.decodeFromString<ModelCatalogueResponse>(body.toString(Charsets.UTF_8))
        } catch (exception: SerializationException) {
            throw IllegalArgumentException("Invalid model catalogue JSON", exception)
        }

        require(catalogue.schemaVersion == SUPPORTED_SCHEMA) {
            "Unsupported model catalogue schema"
        }
        require(catalogue.catalogueVersion.isNotBlank()) {
            "Catalogue version must not be blank"
        }
        require(catalogue.generatedAt > 0) {
            "Catalogue generatedAt must be positive"
        }
        require(catalogue.models.size <= MAX_MODELS) {
            "Model catalogue exceeds the maximum model count"
        }

        val ids = HashSet<String>(catalogue.models.size)
        catalogue.models.forEach { model ->
            require(model.id.isNotBlank()) { "Model id must not be blank" }
            require(model.displayName.isNotBlank()) { "Model displayName must not be blank" }
            require(model.apiFamily == SUPPORTED_API_FAMILY) {
                "Unsupported model API family"
            }
            require(ids.add(model.id)) { "Duplicate model id" }
        }
        require(catalogue.models.singleOrNull { it.id == SOL_ID } == CANONICAL_SOL) {
            "Model catalogue must contain the canonical GPT-5.6 Sol entry"
        }

        return catalogue
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(MAX_CATALOGUE_BYTES)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0

        while (total <= MAX_CATALOGUE_BYTES) {
            val bytesToRead = minOf(buffer.size, MAX_CATALOGUE_BYTES + 1 - total)
            val count = input.read(buffer, 0, bytesToRead)
            if (count < 0) break
            if (count == 0) continue

            output.write(buffer, 0, count)
            total += count
            if (total > MAX_CATALOGUE_BYTES) {
                throw IllegalArgumentException("Model catalogue exceeds the maximum size")
            }
        }

        return output.toByteArray()
    }

    private const val SOL_ID = "gpt-5.6-sol"
    private val CANONICAL_SOL = ModelDto(SOL_ID, "GPT-5.6 Sol", SUPPORTED_API_FAMILY)
}
