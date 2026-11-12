package com.commandcode.chat.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatModelTest {
    @Test fun dynamicModelsExposeStableApiIdentityAndRejectBlankIds() {
        assertEquals("gpt-5.6-sol", ChatModel.SOL.apiId)
        assertEquals("GPT-5.6 Sol", ChatModel.SOL.displayName)
        assertEquals(ApiFamily.OPENAI_CHAT, ChatModel.SOL.apiFamily)
        assertEquals(ChatModel.SOL, ChatModel("gpt-5.6-sol", "GPT-5.6 Sol", ApiFamily.OPENAI_CHAT))
        assertThrows(IllegalArgumentException::class.java) {
            ChatModel("", "Blank", ApiFamily.OPENAI_CHAT)
        }
    }

    @Test
    fun apiFamilyIncludesBothSupportedProtocolFamilies() {
        assertEquals(
            listOf(ApiFamily.OPENAI_CHAT, ApiFamily.ANTHROPIC_MESSAGES),
            ApiFamily.values().toList(),
        )
        assertEquals(
            listOf("openai-chat", "anthropic-messages"),
            ApiFamily.values().map(ApiFamily::wireValue),
        )
    }

    @Test
    fun threeArgumentConstructionDefaultsContextWindowToUnknown() {
        val model = ChatModel("vendor/model", "Model", ApiFamily.OPENAI_CHAT)
        assertNull(model.contextWindowTokens)
        assertEquals(128_000L, ChatModel("vendor/large", "Large", ApiFamily.ANTHROPIC_MESSAGES, 128_000L).contextWindowTokens)
    }

    @Test
    fun contextWindowMustBePositiveWhenKnown() {
        assertThrows(IllegalArgumentException::class.java) {
            ChatModel("vendor/model", "Model", ApiFamily.OPENAI_CHAT, 0)
        }
    }
}
