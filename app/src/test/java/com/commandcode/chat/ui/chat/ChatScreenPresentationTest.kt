package com.commandcode.chat.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatScreenPresentationTest {
    @Test
    fun modelOptionTagSanitisesEveryNonLetterOrDigitAndPreservesCase() {
        assertEquals(
            "model_option_Qwen_Qwen3_8_Flash",
            modelOptionTestTag("Qwen/Qwen3.8-Flash"),
        )
    }
}
