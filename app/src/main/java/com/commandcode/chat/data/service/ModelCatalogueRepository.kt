package com.commandcode.chat.data.service

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelCatalogueRepository(
    context: Context,
) : ModelCatalogueSource {
    private val applicationContext = context.applicationContext
    private var activeSnapshot: ModelCatalogueSnapshot? = null

    override suspend fun loadLocal(): ModelCatalogueSnapshot = withContext(Dispatchers.IO) {
        activeSnapshot ?: run {
            readBundledSnapshot().also { activeSnapshot = it }
        }
    }

    override suspend fun refresh(): ModelCatalogueSnapshot = loadLocal()

    private fun readBundledSnapshot(): ModelCatalogueSnapshot {
        val bytes = applicationContext.assets.open(BUNDLED_ASSET).use(::readBounded)
        return ModelCatalogueCodec.decode(bytes.toString(Charsets.UTF_8))
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream(ModelCatalogueCodec.MAX_CATALOGUE_BYTES)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (total <= ModelCatalogueCodec.MAX_CATALOGUE_BYTES) {
            val count = input.read(
                buffer,
                0,
                minOf(buffer.size, ModelCatalogueCodec.MAX_CATALOGUE_BYTES + 1 - total),
            )
            if (count < 0) break
            if (count == 0) continue
            output.write(buffer, 0, count)
            total += count
            if (total > ModelCatalogueCodec.MAX_CATALOGUE_BYTES) {
                throw IllegalArgumentException("Model catalogue exceeds the maximum size")
            }
        }
        return output.toByteArray()
    }

    companion object {
        private const val BUNDLED_ASSET = "goat-models.json"
    }
}
