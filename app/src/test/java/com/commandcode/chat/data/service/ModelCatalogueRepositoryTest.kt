package com.commandcode.chat.data.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ModelCatalogueRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun bundledAssetLoadsTheLegacyCatalogueInOrder() = runTest {
        val repository = ModelCatalogueRepository(context, ModelCatalogueApi { error("offline") }, MemoryCatalogueStore())
        val snapshot = repository.loadLocal()

        assertEquals(1, snapshot.schemaVersion)
        assertEquals("2026-08-30", snapshot.catalogueVersion)
        assertEquals(1788048000000L, snapshot.generatedAt)
        assertEquals(EXPECTED_IDS, snapshot.models.map(ChatModel::apiId))
        assertEquals(EXPECTED_DISPLAY_NAMES, snapshot.models.map(ChatModel::displayName))
        assertTrue(snapshot.models.all { it.apiFamily == ApiFamily.OPENAI_CHAT })
        assertTrue(snapshot.models.all { it.contextWindowTokens == null })
        assertEquals(CatalogueOrigin.BUNDLED, snapshot.origin)
    }

    @Test
    fun validatedPersistentSnapshotOverridesBundledAssetAndIsMarkedCached() = runTest {
        val cached = catalogue("cached", "Cached model")
        val store = MemoryCatalogueStore(cached)

        val snapshot = ModelCatalogueRepository(context, ModelCatalogueApi { error("offline") }, store).loadLocal()

        assertEquals(listOf("vendor/cached"), snapshot.models.map(ChatModel::apiId))
        assertEquals(CatalogueOrigin.CACHED, snapshot.origin)
    }

    @Test
    fun knownContextWindowMetadataLoadsThroughTheCatalogueRepository() = runTest {
        val metadata = mapOf("vendor/retired" to 32_000L)
        val store = MemoryCatalogueStore().apply {
            knownContextWindowMetadata = metadata
            assertOffMainThread = true
        }
        val repository = ModelCatalogueRepository(context, ModelCatalogueApi { error("offline") }, store)

        assertEquals(metadata, repository.loadKnownContextWindowMetadata())
        assertTrue(store.cacheOperations > 0)
    }

    @Test
    fun corruptPersistentSnapshotFallsBackToBundledAsset() = runTest {
        clearCataloguePreferences()
        try {
            withContext(Dispatchers.IO) {
                context.getSharedPreferences("model_catalogue", Context.MODE_PRIVATE)
                    .edit()
                    .putString("snapshot", "not-json")
                    .commit()
            }
            val repository = ModelCatalogueRepository(context, ModelCatalogueApi { error("offline") })

            val snapshot = repository.loadLocal()

            assertEquals(CatalogueOrigin.BUNDLED, snapshot.origin)
            assertEquals(EXPECTED_IDS, snapshot.models.map(ChatModel::apiId))
        } finally {
            clearCataloguePreferences()
        }
    }

    @Test
    fun refreshReplacesCurrentSnapshotAndSavesValidatedLiveCandidate() = runTest {
        val original = catalogue("old", "Old model")
        val remote = catalogue("new", "New model")
        val store = MemoryCatalogueStore(original)
        val repository = ModelCatalogueRepository(context, ModelCatalogueApi { remote }, store)

        val snapshot = repository.refresh()

        assertEquals(listOf("vendor/new"), snapshot.models.map(ChatModel::apiId))
        assertEquals(CatalogueOrigin.LIVE, snapshot.origin)
        assertEquals(snapshot, repository.loadLocal())
        assertEquals(remote.models, store.snapshot?.models)
        assertEquals(
            CatalogueOrigin.CACHED,
            ModelCatalogueRepository(context, ModelCatalogueApi { error("offline") }, store).loadLocal().origin,
        )
    }

    @Test
    fun persistentRefreshSurvivesRepositoryRestart() = runTest {
        clearCataloguePreferences()
        try {
            val remote = catalogue("persisted", "Persisted model")
            val firstRepository = ModelCatalogueRepository(context, ModelCatalogueApi { remote })
            assertEquals(remote.models, firstRepository.refresh().models)

            val restarted = ModelCatalogueRepository(context, ModelCatalogueApi { error("offline") }).loadLocal()

            assertEquals(remote.models, restarted.models)
            assertEquals(CatalogueOrigin.CACHED, restarted.origin)
        } finally {
            clearCataloguePreferences()
        }
    }

    @Test
    fun apiFailureOrInvalidCandidatePreservesCurrentMemoryAndDiskSnapshot() = runTest {
        val original = catalogue("old", "Old model")
        val store = MemoryCatalogueStore(original)
        val apiFailureRepository = ModelCatalogueRepository(
            context,
            ModelCatalogueApi { throw IOException("offline") },
            store,
        )
        val initial = apiFailureRepository.loadLocal()

        val apiFailure = runCatching { apiFailureRepository.refresh() }.exceptionOrNull()
        assertTrue(apiFailure is IOException)
        assertEquals(initial, apiFailureRepository.loadLocal())
        assertEquals(original.models, store.snapshot?.models)

        val invalid = ModelCatalogueSnapshot(99, "invalid", 1_793_750_400_000L, listOf(model("vendor/invalid")))
        val invalidApiRepository = ModelCatalogueRepository(context, ModelCatalogueApi { invalid }, store)
        val beforeInvalidRefresh = invalidApiRepository.loadLocal()

        val invalidFailure = runCatching { invalidApiRepository.refresh() }.exceptionOrNull()
        assertTrue(invalidFailure is IllegalArgumentException)
        assertEquals(beforeInvalidRefresh, invalidApiRepository.loadLocal())
        assertEquals(original.models, store.snapshot?.models)
    }

    @Test
    fun failedCacheWriteDoesNotReplaceCurrentSnapshot() = runTest {
        val original = catalogue("old", "Old model")
        val store = MemoryCatalogueStore(original).apply { saveFailure = IOException("disk failure") }
        val repository = ModelCatalogueRepository(context, ModelCatalogueApi { catalogue("new", "New model") }, store)
        val initial = repository.loadLocal()

        val failure = runCatching { repository.refresh() }.exceptionOrNull()
        assertTrue(failure is IOException)

        assertEquals(initial, repository.loadLocal())
        assertEquals(original.models, store.snapshot?.models)
    }

    @Test
    fun repositoryUsesIoThreadForSynchronousCacheOperations() = runTest {
        val store = MemoryCatalogueStore().apply { assertOffMainThread = true }

        ModelCatalogueRepository(context, ModelCatalogueApi { catalogue("new", "New model") }, store)
            .refresh()

        assertTrue(store.cacheOperations > 0)
    }

    @Test
    fun changedIdsAndNamesAreValidCatalogueMetadata() {
        val changed = ModelCatalogueSnapshot(
            ModelCatalogueCodec.SUPPORTED_SCHEMA_VERSION,
            "renamed-models",
            1788048002000L,
            listOf(
                ChatModel("vendor/new-id", "New model name", ApiFamily.OPENAI_CHAT),
                ChatModel("gpt-5.6-sol", "Renamed Sol", ApiFamily.OPENAI_CHAT),
            ),
        )

        assertEquals(changed, ModelCatalogueCodec.validate(changed))
    }

    @Test
    fun schemaTwoDecodesExplicitFamiliesAndContext() {
        val encoded = """
            {"schemaVersion":2,"catalogueVersion":"live-1788048000000","generatedAt":1788048000000,
             "models":[
               {"id":"vendor/chat","displayName":"Chat","apiFamily":"openai-chat","contextWindowTokens":131072},
               {"id":"vendor/messages","displayName":"Messages","apiFamily":"anthropic-messages","contextWindowTokens":null}
             ]}
        """.trimIndent()

        val decoded = ModelCatalogueCodec.decode(encoded)

        assertEquals(2, decoded.schemaVersion)
        assertEquals(listOf("openai-chat", "anthropic-messages"), decoded.models.map { it.apiFamily.wireValue })
        assertEquals(listOf(131072L, null), decoded.models.map(ChatModel::contextWindowTokens))
        assertEquals(CatalogueOrigin.BUNDLED, decoded.origin)
    }

    @Test
    fun schemaTwoRoundTripsBothFamiliesAndContextWithoutPersistingOrigin() {
        val original = ModelCatalogueSnapshot(
            2,
            "live-1788048000000",
            1788048000000L,
            listOf(
                ChatModel("vendor/chat", "Chat", ApiFamily.OPENAI_CHAT, 131072L),
                ChatModel("vendor/messages", "Messages", ApiFamily.ANTHROPIC_MESSAGES),
            ),
            CatalogueOrigin.LIVE,
        )

        val encoded = ModelCatalogueCodec.encode(original)
        val decoded = ModelCatalogueCodec.decode(encoded)

        assertEquals(2, JSONObject(encoded).getInt("schemaVersion"))
        assertFalse(JSONObject(encoded).has("origin"))
        assertEquals(original.models, decoded.models)
        assertEquals(CatalogueOrigin.BUNDLED, decoded.origin)
    }

    @Test
    fun schemaTwoRejectsInvalidContextValues() {
        listOf("1.5", "0", "-1", "9223372036854775808", "\"131072\"", "true").forEach { value ->
            val encoded = """{"schemaVersion":2,"catalogueVersion":"v2","generatedAt":1788048000000,"models":[{"id":"vendor/model","displayName":"Model","apiFamily":"openai-chat","contextWindowTokens":$value}]}"""
            assertThrows(IllegalArgumentException::class.java) {
                ModelCatalogueCodec.decode(encoded)
            }
        }
    }

    @Test
    fun validationRequiresNonemptyBoundedUniqueModelList() {
        assertThrows(IllegalArgumentException::class.java) {
            ModelCatalogueCodec.validate(snapshot(models = emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelCatalogueCodec.validate(snapshot(models = listOf(model("duplicate"), model("duplicate"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModelCatalogueCodec.validate(snapshot(models = (0 until 1_001).map { model("vendor/$it") }))
        }
    }

    @Test
    fun decodeRejectsTrailingGarbage() {
        val valid = ModelCatalogueCodec.encode(snapshot(models = listOf(model("decode/model"))))

        assertThrows(IllegalArgumentException::class.java) {
            ModelCatalogueCodec.decode("$valid trailing")
        }
    }

    @Test
    fun decodeRejectsConcatenatedObjects() {
        val valid = ModelCatalogueCodec.encode(snapshot(models = listOf(model("decode/model"))))

        assertThrows(IllegalArgumentException::class.java) {
            ModelCatalogueCodec.decode(valid + valid)
        }
    }

    private fun snapshot(
        schemaVersion: Int = ModelCatalogueCodec.SUPPORTED_SCHEMA_VERSION,
        generatedAt: Long = 1788048000000L,
        models: List<ChatModel>,
    ) = ModelCatalogueSnapshot(
        schemaVersion,
        "2026-08-30",
        generatedAt,
        models,
    )

    private fun model(id: String) = ChatModel(id, "Display $id", ApiFamily.OPENAI_CHAT)

    private fun catalogue(id: String, name: String) = ModelCatalogueSnapshot(
        2,
        "fixture-$id",
        1_793_750_400_000L,
        listOf(ChatModel("vendor/$id", name, ApiFamily.ANTHROPIC_MESSAGES, 200_000L)),
    )

    private suspend fun clearCataloguePreferences() {
        withContext(Dispatchers.IO) {
            context.getSharedPreferences("model_catalogue", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private class MemoryCatalogueStore(initial: ModelCatalogueSnapshot? = null) : ModelCatalogueStore {
        var snapshot: ModelCatalogueSnapshot? = initial
        var knownContextWindowMetadata: Map<String, Long> = emptyMap()
        var saveFailure: Exception? = null
        var assertOffMainThread = false
        var cacheOperations = 0

        override fun loadCatalogue(): ModelCatalogueSnapshot? {
            checkThread()
            return snapshot
        }

        override fun saveCatalogue(snapshot: ModelCatalogueSnapshot) {
            checkThread()
            saveFailure?.let { throw it }
            this.snapshot = snapshot
        }

        override fun loadKnownContextWindowMetadata(): Map<String, Long> {
            checkThread()
            return knownContextWindowMetadata
        }

        private fun checkThread() {
            cacheOperations++
            if (assertOffMainThread) {
                assertTrue(android.os.Looper.myLooper() !== android.os.Looper.getMainLooper())
            }
        }
    }

    companion object {
        private val EXPECTED_IDS = listOf(
            "tencent/hy4-preview",
            "z-ai/glm-5.3-flash",
            "Qwen/Qwen3.8-Flash",
            "deepseek/deepseek-v4-flash-vision-exp",
            "zai-org/GLM-5.3",
            "Qwen/Qwen3.8-27B",
            "deepseek/deepseek-v4-pro",
            "google/gemini-3.7-flash",
            "xai/grok-4.6",
            "meta/muse-spark-1.2",
            "meta/muse-spark-1.2-contributor",
            "Qwen/Qwen3.8-Max",
            "deepseek/deepseek-v4-flash",
            "thinkingmachines/inkling-small",
            "Qwen/Qwen3.7-Flash",
            "poolside/laguna-s-2.1-free",
            "thinkingmachines/inkling",
            "moonshotai/Kimi-K3",
            "gpt-5.6-luna",
            "gpt-5.6-sol",
            "xai/grok-4.5",
            "tencent/hy3-paid",
            "zai-org/GLM-5.2-Fast",
            "zai-org/GLM-5.2",
            "moonshotai/Kimi-K2.7-Code-Highspeed",
            "moonshotai/Kimi-K2.7-Code",
            "nvidia/nemotron-3-ultra-550b-a55b",
            "minimax/minimax-m3-free",
            "MiniMaxAI/MiniMax-M3",
            "Qwen/Qwen3.7-Plus",
            "stepfun/Step-3.7-Flash",
            "xiaomi/mimo-v2.5",
            "xiaomi/mimo-v2.5-pro",
            "Qwen/Qwen3.7-Max",
            "stepfun/Step-3.5-Flash",
            "zai-org/GLM-5.1",
            "minimax/minimax-m2.7-free",
            "MiniMaxAI/MiniMax-M2.7",
            "Qwen/Qwen3.6-Max-Preview",
            "Qwen/Qwen3.6-Plus",
            "moonshotai/Kimi-K2.6",
            "zai-org/GLM-5",
            "moonshotai/Kimi-K2.5",
            "MiniMaxAI/MiniMax-M2.5",
        )
        private val EXPECTED_DISPLAY_NAMES = listOf(
            "Tencent Hy4 Preview",
            "GLM-5.3 Flash",
            "Qwen 3.8 Flash",
            "DeepSeek V4 Flash Vision (exp)",
            "GLM-5.3",
            "Qwen 3.8 27B",
            "DeepSeek V4 Pro (latest)",
            "Gemini 3.7 Flash",
            "Grok 4.6",
            "Muse Spark 1.2",
            "Muse Spark 1.2 Contributor",
            "Qwen 3.8 Max",
            "DeepSeek V4 Flash (latest)",
            "Inkling Small",
            "Qwen 3.7 Flash",
            "Laguna S 2.1",
            "Inkling",
            "Kimi K3",
            "GPT-5.6 Luna",
            "GPT-5.6 Sol",
            "Grok 4.5",
            "Tencent Hy3",
            "GLM-5.2 Fast",
            "GLM-5.2",
            "Kimi K2.7 Code HighSpeed",
            "Kimi K2.7 Code",
            "Nemotron 3 Ultra",
            "MiniMax M3",
            "MiniMax M3",
            "Qwen 3.7 Plus",
            "Step 3.7 Flash",
            "MiMo V2.5",
            "MiMo V2.5 Pro",
            "Qwen 3.7 Max",
            "Step 3.5 Flash",
            "GLM-5.1",
            "MiniMax M2.7",
            "MiniMax M2.7",
            "Qwen 3.6 Max Preview",
            "Qwen 3.6 Plus",
            "Kimi K2.6",
            "GLM-5",
            "Kimi K2.5",
            "MiniMax M2.5",
        )
    }
}
