package com.commandcode.chat.domain

import org.junit.Assert.assertEquals
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
}
