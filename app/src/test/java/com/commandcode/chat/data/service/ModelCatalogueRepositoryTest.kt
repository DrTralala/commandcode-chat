package com.commandcode.chat.data.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
        val repository = ModelCatalogueRepository(context)
        val local = repository.loadLocal()
        val refreshed = repository.refresh()

        assertSame(local, refreshed)
        assertEquals(1, refreshed.schemaVersion)
        assertEquals("2026-08-30", refreshed.catalogueVersion)
        assertEquals(1788048000000L, refreshed.generatedAt)
        assertEquals(EXPECTED_IDS, refreshed.models.map(ChatModel::apiId))
        assertEquals(EXPECTED_DISPLAY_NAMES, refreshed.models.map(ChatModel::displayName))
        assertTrue(refreshed.models.all { it.apiFamily == ApiFamily.OPENAI_CHAT })
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
