package com.commandcode.chat.data.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    fun bundledAssetLoadsTheCanonicalCatalogueInOrder() = runTest {
        val loaded = repository().loadLocal()

        assertEquals(1, loaded.schemaVersion)
        assertEquals("2026-08-30", loaded.catalogueVersion)
        assertEquals(1788048000000L, loaded.generatedAt)
        assertEquals(EXPECTED_IDS, loaded.models.map(ChatModel::apiId))
        assertEquals(EXPECTED_DISPLAY_NAMES, loaded.models.map(ChatModel::displayName))
        assertTrue(loaded.models.all { it.apiFamily == ApiFamily.OPENAI_CHAT })
    }

    @Test
    fun newerValidCacheWinsOverTheBundledCatalogue() = runTest {
        val cached = snapshot(generatedAt = 1788048000001L, models = listOf(model("cached/model")))
        val store = MemoryStore(cached)

        assertEquals(cached, repository(store = store).loadLocal())
    }

    @Test
    fun olderCacheIsIgnored() = runTest {
        val store = MemoryStore(snapshot(generatedAt = 1788047999999L, models = listOf(model("old/model"))))

        val loaded = repository(store = store).loadLocal()

        assertEquals(EXPECTED_IDS, loaded.models.map(ChatModel::apiId))
    }

    @Test
    fun malformedCacheIsIgnored() = runTest {
        val store = MemoryStore(snapshot(schemaVersion = 2, generatedAt = 1788048000001L, models = listOf(model("bad/model"))))

        val loaded = repository(store = store).loadLocal()

        assertEquals(EXPECTED_IDS, loaded.models.map(ChatModel::apiId))
    }

    @Test
    fun validRemoteDataIsSavedAndReturned() = runTest {
        val remote = snapshot(generatedAt = 1788048001000L, models = listOf(model("remote/model")))
        val store = MemoryStore()
        val api = FakeApi(Result.success(remote))
        val repository = repository(api = api, store = store)

        repository.loadLocal()
        assertEquals(remote, repository.refresh())
        assertEquals(remote, store.snapshot)
    }

    @Test
    fun failedRemoteDataLeavesTheActiveLocalSnapshotUntouched() = runTest {
        val store = MemoryStore()
        val api = FakeApi(Result.failure(IOException()))
        val repository = repository(api = api, store = store)
        val local = repository.loadLocal()

        val failure = runCatching { repository.refresh() }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(local, repository.loadLocal())
        assertEquals(null, store.snapshot)
    }

    @Test
    fun invalidRemoteDataLeavesTheActiveLocalSnapshotUntouched() = runTest {
        val local = snapshot(generatedAt = 1788048001000L, models = listOf(model("local/model")))
        val store = MemoryStore(local)
        val duplicate = snapshot(
            generatedAt = 1788048002000L,
            models = listOf(model("same"), model("same")),
        )
        val repository = repository(api = FakeApi(Result.success(duplicate)), store = store)

        assertEquals(local, repository.loadLocal())
        val failure = runCatching { repository.refresh() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(local, repository.loadLocal())
        assertEquals(local, store.snapshot)
    }

    @Test
    fun emptyAndSolLessRemoteCataloguesLeaveTheActiveSnapshotUntouched() = runTest {
        val invalidSnapshots = listOf(
            ModelCatalogueSnapshot(1, "empty", 1788048002000L, emptyList()),
            ModelCatalogueSnapshot(1, "sol-less", 1788048002000L, listOf(model("remote/model"))),
        )

        invalidSnapshots.forEach { invalid ->
            val store = MemoryStore()
            val repository = repository(api = FakeApi(Result.success(invalid)), store = store)
            val local = repository.loadLocal()

            val failure = runCatching { repository.refresh() }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertEquals(local, repository.loadLocal())
            assertEquals(null, store.snapshot)
        }
    }

    @Test
    fun nonCanonicalSolMetadataIsRejectedAtTheContractBoundary() {
        val invalid = ModelCatalogueSnapshot(
            1,
            "invalid-sol",
            1788048002000L,
            listOf(ChatModel("gpt-5.6-sol", "Wrong name", ApiFamily.OPENAI_CHAT)),
        )

        assertThrows(IllegalArgumentException::class.java) {
            ModelCatalogueCodec.validate(invalid)
        }
    }

    @Test
    fun remoteCatalogueWithMoreThanOneHundredModelsIsRejected() = runTest {
        val oversized = snapshot(
            generatedAt = 1788048002000L,
            models = List(101) { index -> model("model-$index") },
        )
        val repository = repository(api = FakeApi(Result.success(oversized)))

        val failure = runCatching { repository.refresh() }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun oversizedEncodedRemoteCatalogueLeavesActiveAndCachedSnapshotsUntouched() = runTest {
        val local = snapshot(
            generatedAt = 1788048000001L,
            models = listOf(model("local/model")),
        )
        val store = EncodingMemoryStore(local)
        val oversized = snapshot(
            generatedAt = 1788048002000L,
            models = listOf(
                ChatModel(
                    apiId = "remote/model",
                    displayName = "x".repeat(ModelCatalogueCodec.MAX_CATALOGUE_BYTES),
                    apiFamily = ApiFamily.OPENAI_CHAT,
                ),
            ),
        )
        val repository = repository(api = FakeApi(Result.success(oversized)), store = store)

        assertEquals(local, repository.loadLocal())
        val failure = runCatching { repository.refresh() }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertEquals(local, repository.loadLocal())
        assertEquals(local, store.snapshot)
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

    private fun repository(
        api: ModelCatalogueApi = FakeApi(Result.success(snapshot(models = listOf(model("unused"))))),
        store: ModelCatalogueStore = MemoryStore(),
    ) = ModelCatalogueRepository(context, api, store)

    private fun snapshot(
        schemaVersion: Int = 1,
        generatedAt: Long = 1788048000000L,
        models: List<ChatModel>,
    ) = ModelCatalogueSnapshot(
        schemaVersion,
        "2026-08-30",
        generatedAt,
        if (models.any { it.apiId == ChatModel.SOL.apiId }) models else listOf(ChatModel.SOL) + models,
    )

    private fun model(id: String) = ChatModel(id, "Display $id", ApiFamily.OPENAI_CHAT)

    private class FakeApi(private var result: Result<ModelCatalogueSnapshot>) : ModelCatalogueApi {
        override suspend fun fetchModels(): ModelCatalogueSnapshot = result.getOrThrow()
    }

    private class MemoryStore(initial: ModelCatalogueSnapshot? = null) : ModelCatalogueStore {
        var snapshot: ModelCatalogueSnapshot? = initial

        override fun loadModels(): ModelCatalogueSnapshot? = snapshot

        override fun saveModels(snapshot: ModelCatalogueSnapshot) {
            this.snapshot = snapshot
        }
    }

    private class EncodingMemoryStore(initial: ModelCatalogueSnapshot? = null) : ModelCatalogueStore {
        var snapshot: ModelCatalogueSnapshot? = initial

        override fun loadModels(): ModelCatalogueSnapshot? = snapshot

        override fun saveModels(snapshot: ModelCatalogueSnapshot) {
            ModelCatalogueCodec.encode(snapshot)
            this.snapshot = snapshot
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
