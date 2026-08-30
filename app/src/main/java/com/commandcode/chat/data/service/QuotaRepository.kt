package com.commandcode.chat.data.service

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class QuotaRepository(
    context: Context,
    private val api: QuotaApi,
    private val store: QuotaSnapshotStore = ServiceSnapshotStore(context),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : QuotaSource {
    private var activeSnapshot: QuotaSnapshot? = null
    private var cacheInvalidated = false

    override suspend fun loadCached(): QuotaSnapshot? = withContext(ioDispatcher) {
        if (cacheInvalidated) return@withContext null
        activeSnapshot ?: run {
            val cached = runCatching {
                store.loadQuota()?.let(QuotaSnapshotCodec::validate)
            }.getOrNull()
            activeSnapshot = cached
            cached
        }
    }

    override suspend fun refresh(apiKey: CharArray): QuotaSnapshot {
        val refreshed = QuotaSnapshotCodec.validate(api.fetchQuota(apiKey))
        withContext(ioDispatcher) {
            store.saveQuota(refreshed)
            activeSnapshot = refreshed
            cacheInvalidated = false
        }
        return refreshed
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        activeSnapshot = null
        cacheInvalidated = true
        store.clearQuota()
    }
}
