package com.commandcode.chat.server

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoatModelRegistryTest {
    private val json = Json { encodeDefaults = true }

    private val expectedIds = listOf(
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
    private val expectedDisplayNames = listOf(
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

    @Test
    fun loadsTheCanonicalCatalogueInOrder() {
        val response = GoatModelRegistry.load(
            requireNotNull(javaClass.classLoader.getResourceAsStream("goat-models.json"))
        )

        assertEquals(1, response.schemaVersion)
        assertEquals("2026-08-30", response.catalogueVersion)
        assertEquals(1788048000000L, response.generatedAt)
        assertEquals(44, response.models.size)
        assertEquals(expectedIds, response.models.map(ModelDto::id))
        assertEquals(expectedDisplayNames, response.models.map(ModelDto::displayName))
        assertEquals(setOf("openai-chat"), response.models.map(ModelDto::apiFamily).toSet())
    }

    @Test
    fun rejectsUnsupportedSchema() {
        assertFailsWith<IllegalArgumentException> {
            GoatModelRegistry.load(input(catalogue(schemaVersion = 2)))
        }
    }

    @Test
    fun rejectsEmptyAndSolLessCatalogues() {
        assertFailsWith<IllegalArgumentException> {
            GoatModelRegistry.load(input(catalogue(models = emptyList())))
        }
        assertFailsWith<IllegalArgumentException> {
            GoatModelRegistry.load(input(catalogue(models = listOf(model("other")))))
        }
    }

    @Test
    fun rejectsNonCanonicalSolMetadata() {
        assertFailsWith<IllegalArgumentException> {
            GoatModelRegistry.load(
                input(catalogue(models = listOf(ModelDto("gpt-5.6-sol", "Wrong name", "openai-chat"))))
            )
        }
    }

    @Test
    fun rejectsDuplicateIds() {
        assertFailsWith<IllegalArgumentException> {
            GoatModelRegistry.load(
                input(catalogue(models = listOf(model("same"), model("same"))))
            )
        }
    }

    @Test
    fun rejectsBlankFields() {
        assertFailsWith<IllegalArgumentException> {
            GoatModelRegistry.load(
                input(catalogue(models = listOf(ModelDto(" ", "Name", "openai-chat"))))
            )
        }
        assertFailsWith<IllegalArgumentException> {
            GoatModelRegistry.load(
                input(catalogue(models = listOf(ModelDto("id", "", "openai-chat"))))
            )
        }
    }

    @Test
    fun rejectsUnsupportedApiFamily() {
        assertFailsWith<IllegalArgumentException> {
            GoatModelRegistry.load(
                input(catalogue(models = listOf(ModelDto("id", "Name", "other"))))
            )
        }
    }

    @Test
    fun rejectsMoreThanOneHundredModels() {
        val models = List(101) { index -> model("model-$index") }

        assertFailsWith<IllegalArgumentException> {
            GoatModelRegistry.load(input(catalogue(models = models)))
        }
    }

    @Test
    fun acceptsExactlyTheByteLimitWithoutReadingPastEnd() {
        val input = CountingInputStream(paddedCanonicalCatalogue(GoatModelRegistry.MAX_CATALOGUE_BYTES))

        val loaded = GoatModelRegistry.load(input)

        assertEquals("gpt-5.6-sol", loaded.models.single().id)
        assertEquals(GoatModelRegistry.MAX_CATALOGUE_BYTES, input.bytesRead)
    }

    @Test
    fun rejectsLimitPlusOneAfterOnlyTheOverflowProbeByte() {
        val input = CountingInputStream(paddedCanonicalCatalogue(GoatModelRegistry.MAX_CATALOGUE_BYTES + 1))

        assertFailsWith<IllegalArgumentException> { GoatModelRegistry.load(input) }

        assertEquals(GoatModelRegistry.MAX_CATALOGUE_BYTES + 1, input.bytesRead)
    }

    private fun model(id: String) = ModelDto(id, "Display $id", "openai-chat")

    private fun catalogue(
        schemaVersion: Int = 1,
        models: List<ModelDto> = listOf(model("id")),
    ) = json.encodeToString(
        ModelCatalogueResponse(
            schemaVersion = schemaVersion,
            catalogueVersion = "2026-08-30",
            generatedAt = 1788048000000L,
            models = models,
        )
    )

    private fun input(value: String) = value.byteInputStream()

    private fun paddedCanonicalCatalogue(length: Int): ByteArray {
        val encoded = catalogue(models = listOf(ModelDto("gpt-5.6-sol", "GPT-5.6 Sol", "openai-chat")))
            .toByteArray(Charsets.UTF_8)
        require(encoded.size <= length)
        return encoded + ByteArray(length - encoded.size) { ' '.code.toByte() }
    }

    private class CountingInputStream(content: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(content)
        var bytesRead: Int = 0
            private set

        override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead += 1 }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            delegate.read(buffer, offset, length).also { if (it > 0) bytesRead += it }
    }
}
