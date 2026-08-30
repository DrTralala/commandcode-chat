package com.commandcode.chat.data.service

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelCatalogueRepository(
    context: Context,
    private val api: ModelCatalogueApi,
    private val store: ModelCatalogueStore = ServiceSnapshotStore(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModelCatalogueSource {
    private val applicationContext = context.applicationContext
    private var activeSnapshot: ModelCatalogueSnapshot? = null

    override suspend fun loadLocal(): ModelCatalogueSnapshot = withContext(ioDispatcher) {
        activeSnapshot ?: run {
            val bundled = readBundledSnapshot()
            val cached = runCatching {
                store.loadModels()?.let(ModelCatalogueCodec::validate)
            }.getOrNull()
            val selected = if (cached != null && cached.generatedAt > bundled.generatedAt) cached else bundled
            activeSnapshot = selected
            selected
        }
    }

    override suspend fun refresh(): ModelCatalogueSnapshot {
        loadLocal()
        val refreshed = ModelCatalogueCodec.validate(api.fetchModels())
        withContext(ioDispatcher) {
            store.saveModels(refreshed)
            activeSnapshot = refreshed
        }
        return refreshed
    }

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
