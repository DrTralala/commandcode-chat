package com.commandcode.chat.data.service

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ModelCatalogueRepository(
    context: Context,
    private val api: ModelCatalogueApi = CommandCodeModelsClient(),
    private val store: ModelCatalogueStore = ModelCatalogueSnapshotStore(context),
) : ModelCatalogueSource {
    private val applicationContext = context.applicationContext
    private val mutex = Mutex()
    private var activeSnapshot: ModelCatalogueSnapshot? = null

    override suspend fun loadLocal(): ModelCatalogueSnapshot = mutex.withLock {
        withContext(Dispatchers.IO) { resolveLocalSnapshot() }
    }

    override suspend fun loadKnownContextWindowMetadata(): Map<String, Long> =
        withContext(Dispatchers.IO) { store.loadKnownContextWindowMetadata() }

    override suspend fun refresh(): ModelCatalogueSnapshot = mutex.withLock {
        withContext(Dispatchers.IO) { resolveLocalSnapshot() }
        val candidate = ModelCatalogueCodec.validate(api.fetchCatalogue())
        withContext(Dispatchers.IO) {
            store.saveCatalogue(candidate)
            candidate.copy(origin = CatalogueOrigin.LIVE).also { activeSnapshot = it }
        }
    }

    private fun resolveLocalSnapshot(): ModelCatalogueSnapshot = activeSnapshot ?: run {
        val cached = runCatching {
            store.loadCatalogue()?.let(ModelCatalogueCodec::validate)
        }.getOrNull()
        val resolved = cached?.copy(origin = CatalogueOrigin.CACHED) ?: readBundledSnapshot()
        resolved.also { activeSnapshot = it }
    }

    private fun readBundledSnapshot(): ModelCatalogueSnapshot {
        val bytes = applicationContext.assets.open(BUNDLED_ASSET).use(::readBounded)
        return ModelCatalogueCodec.decode(bytes.toString(Charsets.UTF_8)).copy(origin = CatalogueOrigin.BUNDLED)
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
