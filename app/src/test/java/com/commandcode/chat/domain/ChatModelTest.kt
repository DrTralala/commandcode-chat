package com.commandcode.chat.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatModelTest {
    @Test fun apiIdsAreStrictlyAllowlisted() {
        assertEquals(ChatModel.SOL, ChatModel.fromApiId("gpt-5.6-sol"))
        assertEquals(ChatModel.LUNA, ChatModel.fromApiId("gpt-5.6-luna"))
        assertNull(ChatModel.fromApiId("gpt-5.6-terra"))
        assertNull(ChatModel.fromApiId("GPT-5.6-SOL"))
    }
}
